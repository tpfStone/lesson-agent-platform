package com.zone.lesoongen.platform;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import com.zone.lesoongen.application.AppException;
import com.zone.lesoongen.config.AppProperties;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/** Real HTTP PATCH transport; a mocked RestClient would miss HttpURLConnection's limitation. */
@SpringBootTest
@ActiveProfiles("test")
class PlatformSimulationTransportTest {
    @Autowired JdbcTemplate db;
    @Autowired PlatformLessonService lessons;
    @Autowired PlatformNavigationBridgeService navigation;
    @Autowired ObjectMapper mapper;
    @Autowired AppProperties properties;
    @Autowired PlatformLessonDocumentService documents;
    @Autowired PlatformSimulationService simulations;
    @Autowired CapabilityAdmissionPolicy admission;
    @Autowired com.zone.lesoongen.auth.PlatformAccessService access;

    record Request(String method, String path, String body, String protocol) {}

    @Test void explicitAnalysisReadsItsCurrentLimitAndPostsOnlyOnce() throws Exception {
        var storage = org.mockito.Mockito.mock(PlatformSimulationService.class);
        var detail = new PlatformSimulationController.SimulationDetail("run", "lesson", "version", 0,
                null, null, "analysis-session", "COMPLETED", 0, 0, 0, "COMPLETED", 0, "", "", "REAL",
                null, null, null, java.util.Map.of(), "");
        org.mockito.Mockito.when(storage.detail("lesson", "run")).thenReturn(detail);
        var received = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/classroom/sessions/analysis-session", exchange -> {
            String path = exchange.getRequestURI().getPath();
            received.add(exchange.getRequestMethod() + " " + path);
            byte[] body = (path.endsWith("/result") ? "{\"analysisStatus\":\"COMPLETED\"}"
                    : "{\"analysisTimeoutSeconds\":300}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            var service = new PlatformSimulationLaunchService(db, lessons, navigation, mapper, properties,
                    documents, storage, "unused-python", "unused-runner", "http://127.0.0.1:" + server.getAddress().getPort(),
                    "http://127.0.0.1:1", "http://127.0.0.1:1", admission, access);
            assertSame(detail, service.retryAnalysis("lesson", "run"));
            assertEquals(List.of("GET /api/classroom/sessions/analysis-session", "POST /api/classroom/sessions/analysis-session/analyze",
                    "GET /api/classroom/sessions/analysis-session/result"), received);
            org.mockito.Mockito.verify(storage).save(org.mockito.ArgumentMatchers.eq("lesson"), org.mockito.ArgumentMatchers.any());
        } finally { server.stop(0); }
    }

    @Test
    void patchReachesF3WithJsonAndGetPostAndRemoteErrorsRemainCompatible() throws Exception {
        var lesson = lessons.create(new PlatformLessonController.CreateLesson(
                "F3传输隔离样本", "数学", "五年级", "面积", 40, "教学目标：理解面积。"));
        UUID launchId = UUID.randomUUID(), sessionId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        db.update("INSERT INTO platform_simulation_launch (launch_id, request_key, lesson_id, version_id, state, f3_session_id, timeout_seconds, message, created_at, updated_at) VALUES (?, ?, ?, ?, 'RUNNING', ?, 3, ?, ?, ?)",
                launchId.toString(), UUID.randomUUID().toString(), lesson.lesson().id(),
                lesson.lesson().currentVersionId(), sessionId.toString(), "controlled transport", now, now);
        List<Request> received = new CopyOnWriteArrayList<>();
        AtomicInteger responseCode = new AtomicInteger(200);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/classroom/sessions/", exchange -> {
            received.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), exchange.getProtocol()));
            byte[] response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseCode.get(), response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            var service = new PlatformSimulationLaunchService(db, lessons, navigation, mapper, properties,
                    documents, simulations, "unused-python", "unused-runner", "http://127.0.0.1:" + server.getAddress().getPort(),
                    "http://127.0.0.1:1", "http://127.0.0.1:1", admission, access);
            assertEquals(true, service.settings(launchId, 1).get("ok"));
            assertEquals(true, service.classroom(launchId).get("ok"));
            assertEquals(true, service.message(launchId, "继续比较面积").get("ok"));
            assertEquals(true, service.control(launchId, "pause").get("ok"));
            assertEquals(List.of("PATCH", "GET", "POST", "POST"), received.stream().map(Request::method).toList());
            assertEquals("/api/classroom/sessions/" + sessionId + "/settings", received.get(0).path());
            assertEquals(1, mapper.readTree(received.get(0).body()).path("timeoutSeconds").asInt());
            assertEquals("继续比较面积", mapper.readTree(received.get(2).body()).path("message").asText());
            assertTrue(received.stream().allMatch(request -> request.protocol().equals("HTTP/1.1")));
            responseCode.set(429);
            var error = assertThrows(AppException.class, () -> service.settings(launchId, 2));
            assertEquals("F3_CLASSROOM_ERROR", error.code());
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.status());
        } finally {
            server.stop(0);
            db.update("UPDATE platform_simulation_launch SET state = 'COMPLETED' WHERE launch_id = ?", launchId.toString());
        }
    }
}
