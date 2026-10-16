package com.zone.lesoongen.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import com.zone.lesoongen.application.AppException;
import com.zone.lesoongen.config.AppProperties;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

/** Exercise cancellation across real HTTP and durable launch rows without any model call. */
@SpringBootTest
@ActiveProfiles("test")
class PlatformSimulationCancellationTest {
    @Autowired JdbcTemplate db;
    @Autowired PlatformLessonService lessons;
    @Autowired PlatformNavigationBridgeService navigation;
    @Autowired ObjectMapper mapper;
    @Autowired AppProperties properties;
    @Autowired PlatformLessonDocumentService documents;
    @Autowired PlatformSimulationService simulations;
    @Autowired CapabilityAdmissionPolicy admission;
    @Autowired com.zone.lesoongen.auth.PlatformAccessService access;
    String lessonId, versionId;
    HttpServer server;
    ExecutorService httpThreads;
    final List<String> calls = new CopyOnWriteArrayList<>();
    final AtomicReference<Function<String, Reply>> handler = new AtomicReference<>();
    record Reply(int status, Map<String, Object> body) {}

    @BeforeEach
    void setup() throws Exception {
        var lesson = lessons.create(new PlatformLessonController.CreateLesson(
                "启动取消隔离样本", "数学", "五年级", "面积", 40, "教学目标：理解面积。"));
        lessonId = lesson.lesson().id(); versionId = lesson.lesson().currentVersionId();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpThreads = Executors.newCachedThreadPool();
        server.setExecutor(httpThreads);
        server.createContext("/api/classroom/", exchange -> {
            String call = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            calls.add(call);
            Reply reply = handler.get().apply(call);
            byte[] bytes = mapper.writeValueAsString(reply.body()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
    }

    @AfterEach
    void cleanup() {
        server.stop(0); httpThreads.shutdownNow();
        db.update("UPDATE platform_simulation_launch SET state = 'INTERRUPTED' WHERE lesson_id = ?", lessonId);
    }

    PlatformSimulationLaunchService service(PlatformLessonDocumentService documentService) {
        return new PlatformSimulationLaunchService(db, lessons, navigation, mapper, properties,
                documentService, simulations, "must-not-start-python", "must-not-start-runner",
                "http://127.0.0.1:" + server.getAddress().getPort(), "http://127.0.0.1:1", "http://127.0.0.1:1", admission, access);
    }

    UUID insert(String state) {
        UUID id = UUID.randomUUID(); Timestamp now = Timestamp.from(Instant.now());
        db.update("INSERT INTO platform_simulation_launch (launch_id, request_key, lesson_id, version_id, state, timeout_seconds, message, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 3, 'controlled cancellation', ?, ?)",
                id.toString(), UUID.randomUUID().toString(), lessonId, versionId, state, now, now);
        return id;
    }

    Reply snapshot(UUID session, String state) {
        return new Reply(200, Map.of("sessionId", session.toString(), "lessonId", lessonId,
                "versionId", versionId, "status", state));
    }

    static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS), "controlled barrier timed out"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }

    @Test
    void queuedEndFencesTheRequestAndRepeatedEndDoesNotCreateOrCallAgain() {
        var service = service(documents); UUID id = insert("QUEUED");
        handler.set(call -> new Reply(200, Map.of("requestKey", id.toString(), "status", "NOT_CREATED")));
        assertDoesNotThrow(() -> service.control(id, "end"));
        assertEquals("INTERRUPTED", service.status(id).state());
        assertNull(service.status(id).f3SessionId());
        assertNull(service.status(id).simulationRunId());
        assertDoesNotThrow(() -> service.control(id, "end"));
        assertEquals(List.of("POST /api/classroom/requests/" + id + "/cancel-if-absent"), calls);
    }

    @Test
    void sessionCreatedBeforeLocalBindingIsFoundAndEndedWithoutClaimingItHasStopped() {
        var service = service(documents); UUID id = insert("RUNNING"), session = UUID.randomUUID();
        handler.set(call -> snapshot(session, call.endsWith("/end") ? "STOPPING" : "RUNNING"));
        assertDoesNotThrow(() -> service.control(id, "end"));
        assertEquals(session.toString(), service.status(id).f3SessionId());
        assertEquals("STOPPING", service.status(id).state());
        assertNull(service.status(id).simulationRunId());
        assertDoesNotThrow(() -> service.control(id, "end"));
        assertEquals(2, calls.stream().filter(call -> call.endsWith("/end")).count());
        assertEquals(1, calls.stream().filter(call -> call.endsWith("/cancel-if-absent")).count());
    }

    @Test
    void unavailableRemoteKeepsDurableCancellationAndRestartReconciliationRetriesIt() {
        var service = service(documents); UUID id = insert("QUEUED");
        handler.set(call -> new Reply(503, Map.of("detail", "controlled outage")));
        assertThrows(AppException.class, () -> service.control(id, "end"));
        assertEquals("STOPPING", service.status(id).state());
        var restarted = service(documents);
        ReflectionTestUtils.setField(restarted, "schedulingEnabled", true);
        restarted.reconcileAfterRestart();
        assertEquals("STOPPING", restarted.status(id).state());
        handler.set(call -> new Reply(200, Map.of("requestKey", id.toString(), "status", "NOT_CREATED")));
        restarted.reconcileRemoteSessions();
        assertEquals("INTERRUPTED", restarted.status(id).state());
        assertNull(restarted.status(id).f3SessionId());
        assertNull(restarted.status(id).simulationRunId());
    }

    @Test
    void latePreparationFailureCannotResurrectAnAcknowledgedImmediateEnd() throws Exception {
        var blockedDocuments = mock(PlatformLessonDocumentService.class);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        when(blockedDocuments.get(anyString(), anyString())).thenAnswer(invocation -> {
            worker.set(Thread.currentThread()); entered.countDown(); await(release);
            throw new IllegalStateException("late preparation failure");
        });
        var service = service(blockedDocuments);
        handler.set(call -> new Reply(200, Map.of("status", "NOT_CREATED")));
        var launch = service.start(new PlatformSimulationLaunchController.LaunchRequest(
                lessonId, versionId, UUID.randomUUID(), null, 3));
        try {
            await(entered);
            assertDoesNotThrow(() -> service.control(launch.launchId(), "end"));
        } finally {
            release.countDown();
            if (worker.get() != null) worker.get().join(10000);
        }
        assertFalse(worker.get().isAlive());
        assertEquals("INTERRUPTED", service.status(launch.launchId()).state());
        assertNull(service.status(launch.launchId()).simulationRunId());
    }

    @Test
    void staleReconciliationFailureCannotReplaceConfirmedCancellationWithUnknown() throws Exception {
        var service = service(documents); UUID id = insert("RECONCILING");
        ReflectionTestUtils.setField(service, "schedulingEnabled", true);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        handler.set(call -> {
            if (call.startsWith("GET ")) { entered.countDown(); await(release); return new Reply(503, Map.of("detail", "late lookup failure")); }
            return new Reply(200, Map.of("requestKey", id.toString(), "status", "NOT_CREATED"));
        });
        Thread reconcile = new Thread(service::reconcileRemoteSessions);
        reconcile.start();
        try {
            await(entered);
            assertDoesNotThrow(() -> service.control(id, "end"));
        } finally {
            release.countDown(); reconcile.join(10000);
        }
        assertFalse(reconcile.isAlive());
        assertEquals("INTERRUPTED", service.status(id).state());
        assertNull(service.status(id).simulationRunId());
    }
}
