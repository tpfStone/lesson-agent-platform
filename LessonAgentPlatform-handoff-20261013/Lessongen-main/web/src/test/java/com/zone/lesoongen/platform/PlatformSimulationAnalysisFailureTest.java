package com.zone.lesoongen.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import com.zone.lesoongen.application.AppException;
import com.zone.lesoongen.auth.PlatformAccessService;
import com.zone.lesoongen.config.AppProperties;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/** Real HTTP response plus transactional archive readback, including the non-success path. */
@SpringBootTest
@ActiveProfiles("test")
class PlatformSimulationAnalysisFailureTest {
    @Autowired JdbcTemplate db;
    @Autowired PlatformLessonService lessons;
    @Autowired PlatformNavigationBridgeService navigation;
    @Autowired ObjectMapper mapper;
    @Autowired AppProperties properties;
    @Autowired PlatformLessonDocumentService documents;
    @Autowired PlatformSimulationService simulations;
    @Autowired CapabilityAdmissionPolicy admission;
    @Autowired PlatformAccessService access;
    @Autowired WebApplicationContext context;

    private HttpServer server;
    private PlatformSimulationLaunchService launches;
    private String lessonId, runId, sessionId;
    private Map<String, Object> original;
    private String originalJson;
    private final AtomicReference<Map<String, Object>> remote = new AtomicReference<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach void setup() throws Exception {
        var lesson = lessons.create(new PlatformLessonController.CreateLesson(
                "分析超时归档样本", "数学", "三年级", "面积", 40, "教学目标：用方格说明面积。"));
        lessonId = lesson.lesson().id();
        sessionId = UUID.randomUUID().toString();
        original = new LinkedHashMap<>();
        original.put("schemaVersion", "2.1");
        original.put("session", Map.of("sessionId", sessionId, "lessonId", lessonId,
                "versionId", lesson.lesson().currentVersionId(), "status", "INTERRUPTED",
                "stopReason", "MODEL_HARD_TIMEOUT", "modelMode", "MOCK",
                "startedAt", "2026-10-17T01:00:00Z", "finishedAt", "2026-10-17T01:00:04Z",
                "materials", List.of(Map.of("materialId", "row-1", "title", "探究", "teachingScript", "比较方格"))));
        original.put("events", List.of(Map.of("eventId", "event-1", "sequence", 1,
                "speaker", "teacher", "function", "teach", "content", "完整课堂事件必须保留", "status", "COMPLETED")));
        original.put("analysisStatus", "PENDING");
        original.put("analysisRevision", 0);
        original.put("analysisWarnings", List.of("OLD_ANALYSIS_WARNING"));
        original.put("issues", List.of());
        original.put("actionItems", List.of());
        runId = simulations.save(lessonId, request(original)).simulationRunId();
        originalJson = simulations.jsonArtifact(lessonId, runId);
        remote.set(result("FAILED", "ANALYSIS_HARD_TIMEOUT: existing classroom events retained; explicit retry required"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/classroom/sessions/" + sessionId, exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(exchange.getRequestMethod() + " " + path);
            Object response = path.endsWith("/result") ? remote.get()
                    : Map.of("sessionId", sessionId, "analysisTimeoutSeconds", 4);
            byte[] bytes = mapper.writeValueAsString(response).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        launches = new PlatformSimulationLaunchService(db, lessons, navigation, mapper, properties,
                documents, simulations, "unused-python", "unused-runner",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "http://127.0.0.1:1", "http://127.0.0.1:1", admission, access);
    }

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test void failedExplicitAnalysisIsArchivedBefore502AndCanLaterSucceed() {
        for (int attempt = 0; attempt < 2; attempt++) {
            AppException error = assertThrows(AppException.class, () -> launches.retryAnalysis(lessonId, runId));
            assertEquals("F3_ANALYSIS_INCOMPLETE", error.code());
            assertEquals(HttpStatus.BAD_GATEWAY, error.status());
            var stored = simulations.detail(lessonId, runId);
            assertEquals("FAILED", stored.sessionRecord().get("analysisStatus"));
            assertEquals(List.of("ANALYSIS_HARD_TIMEOUT: existing classroom events retained; explicit retry required"),
                    stored.sessionRecord().get("analysisWarnings"));
            assertEquals(original.get("events"), stored.sessionRecord().get("events"));
            assertEquals("INTERRUPTED", stored.status());
            assertEquals(1, revisionCount());
            assertEquals(0, issueCount()); // A partial failed analysis is evidence, not accepted issues.
            assertTrue(simulations.markdownArtifact(lessonId, runId).contains("失败"));
        }
        assertEquals(originalJson, db.queryForObject("SELECT result_json FROM platform_simulation_run WHERE id = ?",
                String.class, runId));
        remote.set(result("COMPLETED", "分析已完成"));
        var completed = launches.retryAnalysis(lessonId, runId);
        assertEquals("COMPLETED", completed.sessionRecord().get("analysisStatus"));
        assertEquals(original.get("events"), completed.sessionRecord().get("events"));
        assertEquals("INTERRUPTED", completed.status());
        assertEquals(2, revisionCount());
        assertEquals(1, issueCount());
        assertEquals(3, requests.stream().filter(value -> value.startsWith("POST ")).count());
    }

    @Test void successfulAnalysisStillAppendsOneRevisionAndIngestsIssues() {
        remote.set(result("COMPLETED", "分析已完成"));
        for (int attempt = 0; attempt < 2; attempt++) {
            var detail = launches.retryAnalysis(lessonId, runId);
            assertEquals("COMPLETED", detail.sessionRecord().get("analysisStatus"));
            assertEquals(original.get("events"), detail.sessionRecord().get("events"));
            assertEquals(1, revisionCount());
            assertEquals(1, issueCount());
        }
    }

    @Test void failedSnapshotCannotChangeExecutionOrAppendNonterminalAnalysis() {
        var changed = result("FAILED", "ANALYSIS_HARD_TIMEOUT");
        changed.put("events", List.of());
        assertEquals("SIMULATION_RESULT_CONFLICT", assertThrows(AppException.class,
                () -> simulations.save(lessonId, request(changed))).code());
        var running = result("RUNNING", "still working");
        assertEquals("SIMULATION_RESULT_CONFLICT", assertThrows(AppException.class,
                () -> simulations.save(lessonId, request(running))).code());
        assertEquals(0, revisionCount());
        assertEquals(originalJson, simulations.jsonArtifact(lessonId, runId));
    }

    @Test void anotherOwnerCannotRetryAnalysisOrReadArchive() throws Exception {
        String owner = com.zone.lesoongen.domain.shared.Ulids.next(java.time.Clock.systemUTC());
        db.update("INSERT INTO platform_user (id, username, password_hash, display_name, role, status, created_at, updated_at) VALUES (?, ?, 'test-only', 'Other owner', 'TEACHER', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", owner, owner);
        db.update("UPDATE platform_lesson SET owner_user_id = ? WHERE id = ?", owner, lessonId);
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        String path = "/api/platform/lessons/" + lessonId + "/simulations/" + runId;
        mvc.perform(post(path + "/analyze")).andExpect(status().isNotFound());
        mvc.perform(get(path)).andExpect(status().isNotFound());
        assertEquals(0, revisionCount());
        assertEquals(0, issueCount());
    }

    private Map<String, Object> result(String status, String warning) {
        var result = new LinkedHashMap<>(original);
        result.put("analysisStatus", status);
        result.put("analysisWarnings", List.of(warning));
        result.put("analysisRevision", "COMPLETED".equals(status) ? 1 : 0);
        result.put("issues", List.of(Map.of("id", "analysis-issue", "title", "补充面积追问",
                "description", "请学生说明方格与面积关系", "severity", "medium")));
        return result;
    }

    private PlatformSimulationController.SaveSimulationRequest request(Map<String, Object> result) {
        return new PlatformSimulationController.SaveSimulationRequest(result, "# 原课堂记录\n\n完整课堂事件必须保留");
    }

    private int revisionCount() {
        return db.queryForObject("SELECT COUNT(*) FROM platform_simulation_analysis WHERE simulation_run_id = ?", Integer.class, runId);
    }

    private int issueCount() {
        return db.queryForObject("SELECT COUNT(*) FROM platform_issue WHERE lesson_id = ?", Integer.class, lessonId);
    }
}
