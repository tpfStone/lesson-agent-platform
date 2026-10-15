package com.zone.lesoongen.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zone.lesoongen.application.AppException;
import com.zone.lesoongen.platform.*;
import java.util.List;
import java.util.concurrent.*;
import com.zone.lesoongen.auth.CurrentUserService;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest(properties = {
        "app.scheduling-enabled=false",
        "app.engine-internal-token=test-only-token",
        "app.security.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class MySqlMigrationTest {
    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.44")
            .withDatabaseName("lesoongen")
            .withUsername("lesoongen")
            .withPassword("test-password");

    @Autowired
    DataSource dataSource;

    @Autowired PlatformLessonService lessons;
    @Autowired LessonVersionWriteService writes;
    @MockitoSpyBean PlatformLessonDocumentService documents;
    @MockitoSpyBean CurrentUserService currentUser;

    private VersionWriteCommand command(String lesson, String head, String content, String key) {
        return new VersionWriteCommand(lesson, head, content, null, "FALLBACK", "PLAIN_TEXT", List.of(),
                "MANUAL", null, key, PlatformLessonDocumentService.sha256(content));
    }

    private <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            var futures = List.of(first, second).stream().map(action -> pool.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("race was not released");
                return action.call();
            })).toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            return List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentDuplicateOperationCreatesExactlyOneVersion() throws Exception {
        var created = lessons.create(new PlatformLessonController.CreateLesson("并发", "数学", "五年级", "面积", 40, "原文"));
        String lesson = created.lesson().id(), head = created.lesson().currentVersionId();
        var request = command(lesson, head, "同一新版", "same-operation");
        var snapshots = new CyclicBarrier(2);
        // Both transactions must establish their user-read snapshot before either
        // obtains the lesson lock; serial scheduling must not hide the regression.
        doAnswer(call -> {
            var user = call.callRealMethod();
            snapshots.await(10, TimeUnit.SECONDS);
            return user;
        }).when(currentUser).require();
        List<LessonVersionWriteService.Written> outcomes;
        try { outcomes = race(() -> writes.append(request), () -> writes.append(request)); }
        finally { reset(currentUser); }
        assertEquals(outcomes.get(0).versionId(), outcomes.get(1).versionId());
        assertEquals(1, outcomes.stream().filter(LessonVersionWriteService.Written::alreadySaved).count());
        assertEquals(2, lessons.detail(lesson).versions().size());
        var error = assertThrows(AppException.class, () -> writes.append(command(lesson, head, "不同内容", "same-operation")));
        assertEquals("LESSON_VERSION_CONFLICT", error.code());
        assertEquals(2, lessons.detail(lesson).versions().size());
    }

    @Test
    void concurrentFirstOperationsOnDifferentLessonsDoNotDeadlock() throws Exception {
        var first = lessons.create(new PlatformLessonController.CreateLesson("独立A", "数学", "五年级", "面积", 40, "原文A"));
        var second = lessons.create(new PlatformLessonController.CreateLesson("独立B", "数学", "五年级", "面积", 40, "原文B"));
        // Only this class's disposable test database is affected. An empty index
        // makes both missing operation keys hit the same gap deterministically.
        new JdbcTemplate(dataSource).update("DELETE FROM platform_version_operation");
        var beforeSave = new CyclicBarrier(2);
        doAnswer(call -> {
            beforeSave.await(10, TimeUnit.SECONDS);
            return call.callRealMethod();
        }).when(documents).save(anyString(), any(LessonDocument.class), anyString(), anyString(), anyString(), anyList());
        List<LessonVersionWriteService.Written> outcomes;
        try {
            outcomes = race(() -> writes.append(command(first.lesson().id(), first.lesson().currentVersionId(), "新版A", "first")),
                    () -> writes.append(command(second.lesson().id(), second.lesson().currentVersionId(), "新版B", "first")));
        } finally { reset(documents); }
        assertEquals(2, outcomes.size());
        assertEquals(2, lessons.detail(first.lesson().id()).versions().size());
        assertEquals(2, lessons.detail(second.lesson().id()).versions().size());
    }

    @Test
    void concurrentDifferentEditsRejectOneStaleHeadWithoutPartialRows() throws Exception {
        var created = lessons.create(new PlatformLessonController.CreateLesson("竞争", "数学", "五年级", "面积", 40, "原文"));
        String lesson = created.lesson().id(), head = created.lesson().currentVersionId();
        var outcomes = race(() -> appendOutcome(command(lesson, head, "版本A", "A")),
                () -> appendOutcome(command(lesson, head, "版本B", "B")));
        assertEquals(1, outcomes.stream().filter("LESSON_VERSION_CONFLICT"::equals).count());
        assertEquals(2, lessons.detail(lesson).versions().size());
        var db = new JdbcTemplate(dataSource);
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM platform_version_operation WHERE lesson_id=?", Integer.class, lesson));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM platform_lesson_version_structure s JOIN platform_lesson_version v ON v.id=s.version_id WHERE v.lesson_id=?", Integer.class, lesson));
    }

    private String appendOutcome(VersionWriteCommand request) {
        try { return writes.append(request).versionId(); }
        catch (AppException error) { return error.code(); }
    }

    @Test
    void concurrentMissingStructureBackfillCreatesOneAuditedRecord() throws Exception {
        var created = lessons.create(new PlatformLessonController.CreateLesson("补录", "数学", "五年级", "面积", 40, "原文"));
        String lesson = created.lesson().id(), version = created.lesson().currentVersionId();
        var db = new JdbcTemplate(dataSource);
        db.update("DELETE FROM platform_lesson_version_structure WHERE version_id=?", version);
        var outcomes = race(() -> documents.insertIfAbsent(version, "原文"), () -> documents.insertIfAbsent(version, "原文"));
        assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM platform_structure_repair_audit WHERE version_id=?", Integer.class, version));
        assertEquals("TEXT_FALLBACK", documents.get(lesson, version).bindingStatus());
        assertEquals("原文", db.queryForObject("SELECT content FROM platform_lesson_version WHERE id=?", String.class, version));
        assertEquals(version, lessons.detail(lesson).lesson().currentVersionId());
    }

    @Test
    void flywayCreatesTheProductionTablesAndConstraints() throws SQLException {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer tableCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name IN ('lesson_job', 'lesson_source_file', 'lesson_artifact', 'lesson_job_event')
                """, Integer.class);
        assertEquals(4, tableCount);

        Integer jsonColumns = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND data_type = 'json'
                  AND column_name IN ('request_snapshot', 'parse_warnings', 'event_payload')
                """, Integer.class);
        assertEquals(3, jsonColumns);

        Integer nullableF4Columns = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'platform_simulation_run'
                  AND column_name IN ('f4_session_id', 'f4_lesson_plan_id')
                  AND is_nullable = 'YES'
                """, Integer.class);
        assertEquals(2, nullableF4Columns);

        assertThrows(Exception.class, () -> jdbc.update("""
                INSERT INTO lesson_source_file
                  (id, job_id, kind, original_filename, media_type, size_bytes, sha256,
                   storage_key, parse_status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6))
                """, "01J00000000000000000000000", "01J99999999999999999999999",
                "ORIGINAL_LESSON_DOCX", "lesson.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                1, "0".repeat(64), "jobs/test/input.docx", "PENDING"));
    }

    @Test
    void invalidFollowUpMigrationFailsLoudly(@TempDir Path migrationDirectory) throws Exception {
        Files.writeString(
                migrationDirectory.resolve("V4__intentionally_invalid.sql"),
                "THIS IS NOT VALID MYSQL SQL;");

        Flyway invalidFlyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "classpath:db/migration",
                        "classpath:db/vendor/mysql",
                        "filesystem:" + migrationDirectory.toAbsolutePath().toString().replace('\\', '/'))
                .load();

        assertThrows(FlywayException.class, invalidFlyway::migrate);
    }
}
