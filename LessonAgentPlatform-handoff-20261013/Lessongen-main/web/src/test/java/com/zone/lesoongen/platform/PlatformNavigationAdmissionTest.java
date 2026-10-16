package com.zone.lesoongen.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import com.sun.net.httpserver.HttpServer;
import com.zone.lesoongen.application.AppException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

/** Real local HTTP: replay is a read of accepted intent, never a probing POST. */
@SpringBootTest @ActiveProfiles("test")
class PlatformNavigationAdmissionTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Map<String, Map<String, Object>> SESSIONS = new ConcurrentHashMap<>();
    static final List<Map<?, ?>> POSTS = new CopyOnWriteArrayList<>();
    static final AtomicBoolean FAIL = new AtomicBoolean();
    static final Path FILE;
    static final HttpServer SERVER;
    static {
        try {
            FILE = Files.createTempDirectory("navigation-admission-").resolve("switches.json");
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            SERVER.createContext("/api/sessions", exchange -> {
                String path = exchange.getRequestURI().getPath();
                Object result; int status = 200;
                if (exchange.getRequestMethod().equals("POST")) {
                    Map<?, ?> payload = JSON.readValue(exchange.getRequestBody(), Map.class);
                    POSTS.add(payload);
                    if (FAIL.get()) { status = 503; result = Map.of("detail", "controlled outage"); }
                    else {
                        String key = payload.get("request_key").toString();
                        Map<String, Object> saved = SESSIONS.computeIfAbsent(key,
                                ignored -> session(UUID.randomUUID().toString(), key, payload));
                        result = saved;
                    }
                } else if (path.equals("/api/sessions")) {
                    result = SESSIONS.values().stream().map(value -> value.get("session")).toList();
                } else {
                    String id = path.split("/")[3];
                    result = SESSIONS.values().stream().filter(value -> ((Map<?, ?>) value.get("session")).get("id").equals(id))
                            .findFirst().orElse(Map.of());
                }
                byte[] bytes = JSON.writeValueAsBytes(result);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            SERVER.start();
        } catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }
    static Map<String, Object> session(String id, String key, Map<?, ?> payload) {
        var plan = new java.util.LinkedHashMap<String, Object>();
        plan.put("original_content", payload.get("content"));
        plan.put("source_binding", payload.get("source_binding"));
        return Map.of("session", Map.of("id", id, "request_key", key, "lesson_metadata", payload.get("metadata")),
                "lesson_plan", plan);
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("platform.admission-file", FILE::toString);
        registry.add("platform.f4.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }
    @AfterAll static void stop() { SERVER.stop(0); }
    @Autowired PlatformNavigationBridgeService navigation;
    @Autowired PlatformLessonService lessons;
    @Autowired JdbcTemplate db;
    @Autowired org.springframework.web.context.WebApplicationContext webContext;
    @MockitoBean PlatformIssueContextService issueContext;
    @BeforeEach void setup() throws Exception {
        POSTS.clear(); SESSIONS.clear(); FAIL.set(false); config(true);
        when(issueContext.f4AdditionalContext(anyString(), anyString())).thenReturn("首次接纳时的问题建议");
    }
    static void config(boolean open) throws Exception {
        Path next = FILE.resolveSibling("switches.next");
        Files.writeString(next, "{\"version\":\"navigation-test\",\"structure\":" + open
                + ",\"f1\":true,\"f2\":true,\"f3\":true,\"f4\":" + open + "}");
        Files.move(next, FILE, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    PlatformLessonController.LessonDetail lesson() {
        return lessons.create(new PlatformLessonController.CreateLesson("导航准入样本", "数学", "五年级", "面积", 40, "必须保留的原稿。"));
    }
    PlatformNavigationBridgeService.Started start(PlatformLessonController.LessonDetail lesson, UUID key, String topic) {
        return navigation.start(lesson.lesson().id(), lesson.lesson().currentVersionId(), key, null, null, topic);
    }

    @Test void boundReplayAfterCloseDoesNotPostAndChangedInputStillConflicts() throws Exception {
        var lesson = lesson(); UUID key = UUID.randomUUID();
        var first = start(lesson, key, null);
        config(false);
        assertEquals(first.sessionId(), start(lesson, key, null).sessionId());
        assertEquals(1, POSTS.size());
        assertEquals("F4_REQUEST_REUSED", assertThrows(AppException.class, () -> start(lesson, key, "不同课题")).code());
        assertEquals(1, POSTS.size());
    }

    @Test void committedPendingIntentCanFinishAfterCloseWithItsOriginalPayload() throws Exception {
        var lesson = lesson(); UUID key = UUID.randomUUID();
        FAIL.set(true);
        assertThrows(AppException.class, () -> start(lesson, key, null));
        assertEquals(1, POSTS.size());
        config(false); FAIL.set(false);
        when(issueContext.f4AdditionalContext(anyString(), anyString())).thenReturn("关闭后改变的派生问题建议");
        var saved = start(lesson, key, null);
        assertNotNull(saved.sessionId());
        assertEquals(2, POSTS.size());
        assertEquals(POSTS.get(0), POSTS.get(1));
        assertEquals("首次接纳时的问题建议", ((Map<?, ?>) POSTS.get(1).get("metadata")).get("additional_context"));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM platform_navigation_link WHERE session_id = ?", Integer.class, saved.sessionId()));
    }

    @Test void preUpgradeBoundSessionReplaysByReadOnlyProofWhileClosed() throws Exception {
        var lesson = lesson(); UUID key = UUID.randomUUID(); String id = UUID.randomUUID().toString();
        var payload = Map.of("metadata", Map.of("subject", "数学", "grade", "五年级", "topic", "面积"), "content", lesson.versions().get(0).content());
        SESSIONS.put(key.toString(), session(id, key.toString(), payload));
        db.update("INSERT INTO platform_navigation_link (session_id, lesson_id, source_version_id, created_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                id, lesson.lesson().id(), lesson.lesson().currentVersionId());
        config(false);
        assertEquals(id, start(lesson, key, null).sessionId());
        assertTrue(POSTS.isEmpty());
        assertEquals("F4_REQUEST_REUSED", assertThrows(AppException.class, () -> start(lesson, key, "改变旧输入")).code());
        assertTrue(POSTS.isEmpty());
    }

    @Test void admittedF3ContinuationBypassesClosedF4ButRequiresPersistedActiveLaunch() throws Exception {
        var lesson = lesson(); UUID launch = UUID.randomUUID();
        db.update("INSERT INTO platform_simulation_launch (launch_id, request_key, lesson_id, version_id, state, timeout_seconds, message, created_at, updated_at) VALUES (?, ?, ?, ?, 'RUNNING', 3, 'admitted fixture', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                launch.toString(), UUID.randomUUID().toString(), lesson.lesson().id(), lesson.lesson().currentVersionId());
        config(false);
        var continued = navigation.startForAdmittedSimulation(launch);
        assertEquals(lesson.lesson().id(), continued.lessonId());
        assertEquals(1, POSTS.size());
        assertEquals("F3_ADMISSION_NOT_ACTIVE", assertThrows(AppException.class,
                () -> navigation.startForAdmittedSimulation(UUID.randomUUID())).code());
        db.update("UPDATE platform_simulation_launch SET state = 'INTERRUPTED' WHERE launch_id = ?", launch.toString());
        assertEquals("F3_ADMISSION_NOT_ACTIVE", assertThrows(AppException.class,
                () -> navigation.startForAdmittedSimulation(launch)).code());
        assertEquals(1, POSTS.size());
    }

    @Test void concurrentSameKeyCreatesOneDurableIntentAndOneBinding() throws Exception {
        var lesson = lesson(); UUID key = UUID.randomUUID();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var begin = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<String> submit = () -> {
                ready.countDown(); assertTrue(begin.await(10, java.util.concurrent.TimeUnit.SECONDS));
                return start(lesson, key, null).sessionId();
            };
            var first = pool.submit(submit); var second = pool.submit(submit);
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)); begin.countDown();
            String id = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(id, second.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM platform_navigation_submission WHERE request_key = ?", Integer.class, key.toString()));
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM platform_navigation_link WHERE session_id = ?", Integer.class, id));
        } finally { begin.countDown(); pool.shutdownNow(); }
    }

    @Test void closedGatePreservesOwnedWritebackButRemoteExistenceCannotAuthorizeUnboundSession() throws Exception {
        var lesson = lesson(); UUID key = UUID.randomUUID(), round = UUID.randomUUID();
        String id = start(lesson, key, null).sessionId();
        String content = "已接纳会话保存的修订正文。";
        Map<String, Object> remote = new java.util.LinkedHashMap<>(SESSIONS.get(key.toString()));
        var session = new java.util.LinkedHashMap<>((Map<String, Object>) remote.get("session"));
        session.put("status", "TERMINATED"); remote.put("session", session);
        var plan = new java.util.LinkedHashMap<>((Map<String, Object>) remote.get("lesson_plan"));
        plan.put("current_content", content); remote.put("lesson_plan", plan);
        remote.put("round", Map.of("id", round.toString(), "round_number", 1));
        SESSIONS.put(key.toString(), remote);
        config(false);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(webContext).build();
        String body = JSON.writeValueAsString(Map.of("expectedVersionId", lesson.lesson().currentVersionId(),
                "roundId", round.toString(), "expectedContentSha256", PlatformLessonDocumentService.sha256(content)));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                "/api/platform/navigation/sessions/" + id + "/writeback")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertEquals(2, lessons.detail(lesson.lesson().id()).versions().size());
        assertEquals(content, lessons.detail(lesson.lesson().id()).versions().get(1).content());

        // This valid remote session has no Java ownership binding or admission proof.
        String unbound = UUID.randomUUID().toString();
        session = new java.util.LinkedHashMap<>(session); session.put("id", unbound);
        remote = new java.util.LinkedHashMap<>(remote); remote.put("session", session);
        SESSIONS.put(UUID.randomUUID().toString(), remote);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                "/api/platform/navigation/sessions/" + unbound + "/writeback")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM platform_navigation_link WHERE session_id = ?", Integer.class, unbound));
    }
}
