package com.zone.lesoongen.platform;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import com.zone.lesoongen.auth.PlatformAccessService;
import com.zone.lesoongen.domain.shared.Ulids;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Real local F1 HTTP responses and transactional H2 writes; no model or production data. */
@SpringBootTest
@ActiveProfiles("test")
class PlatformIssueSyncRecoveryTest {
    @Autowired JdbcTemplate db;
    @Autowired PlatformLessonService lessons;
    @Autowired PlatformLessonDocumentService documents;
    @Autowired PlatformIssueIngestService ingest;
    @Autowired PlatformIssueService issues;
    @Autowired PlatformNavigationBridgeService navigation;
    @Autowired PlatformAccessService access;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper mapper;
    @Autowired CapabilityAdmissionPolicy admission;

    @Test
    void upstreamAndMidIngestFailuresRecoverWithoutDuplicatesOrErasingManualResolution() throws Exception {
        var lesson = lessons.create(new PlatformLessonController.CreateLesson(
                "同步故障隔离样本", "数学", "五年级", "面积", 40, "教学目标：理解面积。"));
        String lessonId = lesson.lesson().id(), versionId = lesson.lesson().currentVersionId();
        String annotationId = Ulids.next(Clock.systemUTC());
        String nativeId = UUID.randomUUID().toString().replace("-", "");
        db.update("INSERT INTO platform_annotation_run (id, lesson_id, version_id, native_run_id, created_at) VALUES (?, ?, ?, ?, ?)",
                annotationId, lessonId, versionId, nativeId, Timestamp.from(Instant.now()));
        AtomicInteger responseCode = new AtomicInteger(503), requests = new AtomicInteger();
        AtomicReference<byte[]> body = new AtomicReference<>(mapper.writeValueAsBytes(result(2)));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/runs/" + nativeId + "/result", exchange -> {
            requests.incrementAndGet();
            byte[] response = responseCode.get() == 200 ? body.get() : "{\"detail\":\"controlled failure\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseCode.get(), response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        String constraint = "sync_fault_" + nativeId;
        boolean constraintInstalled = false;
        try {
            var annotationService = new PlatformAnnotationService(db, lessons, ingest, mapper, documents,
                    transactions, access, "http://127.0.0.1:" + server.getAddress().getPort(), admission);
            var synchronizer = new PlatformIssueSyncService(db, annotationService, navigation);
            synchronizer.syncAnnotation(lessonId, annotationId);
            assertEquals("RETRY", syncState(annotationId));
            assertTrue(issueIds(annotationId).isEmpty());

            // Fail after the first issue and its evidence/recommendation/context were written.
            responseCode.set(200);
            db.execute("ALTER TABLE platform_issue ADD CONSTRAINT " + constraint
                    + " CHECK (source_run_id <> '" + annotationId + "' OR source_issue_id <> 'group-0-item-0-note-1')");
            constraintInstalled = true;
            synchronizer.syncAnnotation(lessonId, annotationId);
            assertEquals("RETRY", syncState(annotationId));
            assertTrue(issueIds(annotationId).isEmpty(), "The failed ingest transaction must not leave its first issue behind");
            db.execute("ALTER TABLE platform_issue DROP CONSTRAINT " + constraint);
            constraintInstalled = false;
            synchronizer.syncAnnotation(lessonId, annotationId);
            assertEquals("DONE", syncState(annotationId));
            List<String> originalIds = issueIds(annotationId);
            assertEquals(2, originalIds.size());
            String resolved = originalIds.get(0);
            issues.resolve(lessonId, versionId, resolved,
                    new PlatformIssueController.Resolution(0, "RESOLVED", "人工已核对，不应被重试覆盖"));

            // A later source result adds another issue, but its first sync fails mid-write.
            body.set(mapper.writeValueAsBytes(result(3)));
            db.execute("ALTER TABLE platform_issue ADD CONSTRAINT " + constraint
                    + " CHECK (source_run_id <> '" + annotationId + "' OR source_issue_id <> 'group-0-item-0-note-2')");
            constraintInstalled = true;
            synchronizer.syncAnnotation(lessonId, annotationId);
            assertEquals("RETRY", syncState(annotationId));
            assertEquals(originalIds, issueIds(annotationId));
            db.execute("ALTER TABLE platform_issue DROP CONSTRAINT " + constraint);
            constraintInstalled = false;
            synchronizer.syncAnnotation(lessonId, annotationId);
            List<String> restoredIds = issueIds(annotationId);
            assertEquals(3, restoredIds.size());
            assertTrue(restoredIds.containsAll(originalIds));
            synchronizer.syncAnnotation(lessonId, annotationId);
            assertEquals(restoredIds, issueIds(annotationId));
            assertEquals("DONE", syncState(annotationId));
            assertNull(db.queryForObject("SELECT last_error FROM platform_issue_sync WHERE source_key = ?", String.class, "F1:" + annotationId));
            var manual = issues.detail(lessonId, versionId, resolved).issue();
            assertEquals("RESOLVED", manual.resolutionStatus());
            assertEquals(1L, manual.revision());
            assertEquals("人工已核对，不应被重试覆盖", manual.resolutionReason());
            for (String table : List.of("platform_issue_evidence", "platform_issue_recommendation", "platform_issue_context"))
                assertEquals(3, db.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE issue_id IN (SELECT id FROM platform_issue WHERE source_run_id = ?)", Integer.class, annotationId));
            assertEquals(6, requests.get());
        } finally {
            if (constraintInstalled) db.execute("ALTER TABLE platform_issue DROP CONSTRAINT " + constraint);
            server.stop(0);
        }
    }

    private List<String> issueIds(String run) {
        return db.query("SELECT id FROM platform_issue WHERE source_run_id = ? ORDER BY source_issue_id", (rs, row) -> rs.getString(1), run);
    }
    private String syncState(String run) {
        return db.queryForObject("SELECT state FROM platform_issue_sync WHERE source_key = ?", String.class, "F1:" + run);
    }
    private Map<String, Object> result(int count) {
        var notes = java.util.stream.IntStream.range(0, count).mapToObj(index -> Map.of(
                "评价", "问题" + index, "具体分析", "需检查目标" + index, "原文引用", "教学目标",
                "位置", "目标", "细化建议", "补充可观察表现" + index)).toList();
        return Map.of("rubric", Map.of("groups", List.of(Map.of("name", "目标", "items", List.of(
                Map.of("维度名称", "教学目标", "等级", "C", "批注列表", notes))))));
    }
}
