package com.zone.lesoongen.platform;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import com.zone.lesoongen.application.AppException;

/** Real InnoDB read views: a parent row lock must not disguise a stale plain SELECT. */
@SpringBootTest(properties = {"app.scheduling-enabled=false", "app.engine-internal-token=test-only-token",
        "app.security.enabled=false"})
@Testcontainers(disabledWithoutDocker = true)
class LessonStructureRollbackMySqlTest {
    @Container @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.44")
            .withDatabaseName("repair_read_view").withUsername("acceptance").withPassword("test-password");
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired PlatformLessonService lessons;
    @Autowired LessonStructureRepairService repairs;

    private String version() {
        return lessons.create(new PlatformLessonController.CreateLesson("并发回退", "数学", "五年级", "面积", 40, "原文"))
                .lesson().currentVersionId();
    }
    private LessonStructureRepairService.Inspection inspect(String version) {
        return repairs.inspect("", 1000).stream().filter(x -> x.versionId().equals(version)).findFirst().orElseThrow();
    }
    private void repair(String version, LessonStructureRepairService.Inspection expected) {
        repairs.repair(version, expected.contentSha256(), expected.oldRawStructureSha256(),
                LessonDocumentSupport.fromPlainText("原文"), "reviewed replacement");
    }
    private LessonStructureRepairService.RollbackInspection audit(String version) {
        return repairs.inspectRollbacks().stream().filter(x -> x.versionId().equals(version)).findFirst().orElseThrow();
    }

    /** Two independent connections, with commits forced between read-view creation and the operation. */
    private void afterConcurrentCommit(String version, Runnable writer, Runnable operation) throws Exception {
        var snapshotReady = new CountDownLatch(1);
        var writerCommitted = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        var future = pool.submit(() -> {
            try {
                assertTrue(snapshotReady.await(10, TimeUnit.SECONDS));
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    db.queryForObject("SELECT id FROM platform_lesson_version WHERE id=? FOR UPDATE", String.class, version);
                    writer.run();
                });
            } finally { writerCommitted.countDown(); }
            return null;
        });
        try {
            var transaction = new TransactionTemplate(transactionManager);
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            transaction.executeWithoutResult(status -> {
                // The rollback audit lookup, or an outer caller's prior query,
                // establishes this view before the version lock is obtained.
                db.queryForList("SELECT id FROM platform_structure_repair_audit WHERE version_id=?", version);
                snapshotReady.countDown();
                try {
                    assertTrue(writerCommitted.await(10, TimeUnit.SECONDS));
                    future.get(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                catch (Exception failure) { throw new AssertionError(failure); }
                operation.run();
            });
            future.get(10, TimeUnit.SECONDS);
        } finally {
            snapshotReady.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test void rollbackRejectsMetadataCommittedAfterItsReadViewWasCreated() throws Exception {
        String version = version();
        repair(version, inspect(version));
        var audit = audit(version);
        var failure = assertThrows(AppException.class, () -> afterConcurrentCommit(version,
                () -> db.update("UPDATE platform_lesson_version_structure SET warnings='[\"later operator\"]' WHERE version_id=?", version),
                () -> repairs.rollback(audit.auditId(), audit.contentSha256(), audit.currentRowSha256(), "stale rollback")));
        assertEquals("STRUCTURE_ROLLBACK_CONFLICT", failure.code());
        assertTrue(db.queryForObject("SELECT warnings FROM platform_lesson_version_structure WHERE version_id=?", String.class, version)
                .contains("later operator"));
        assertNull(db.queryForObject("SELECT rolled_back_at FROM platform_structure_repair_audit WHERE id=?", Object.class, audit.auditId()));
    }

    @Test void repairRejectsStructureCommittedAfterItsReadViewWasCreated() throws Exception {
        String version = version();
        var expected = inspect(version);
        var failure = assertThrows(AppException.class, () -> afterConcurrentCommit(version,
                () -> db.update("UPDATE platform_lesson_version_structure SET structured_content=JSON_SET(structured_content,'$.concurrentMarker','later') WHERE version_id=?", version),
                () -> repair(version, expected)));
        assertEquals("STRUCTURE_REPAIR_CONFLICT", failure.code());
        assertEquals("later", db.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(structured_content,'$.concurrentMarker')) FROM platform_lesson_version_structure WHERE version_id=?", String.class, version));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM platform_structure_repair_audit WHERE version_id=?", Integer.class, version));
    }

    @Test void repairAuditCapturesCurrentMetadataEvenWithAnOlderReadView() throws Exception {
        String version = version();
        var expected = inspect(version);
        afterConcurrentCommit(version,
                () -> db.update("UPDATE platform_lesson_version_structure SET warnings='[\"metadata before repair\"]' WHERE version_id=?", version),
                () -> repair(version, expected));
        var audit = audit(version);
        repairs.rollback(audit.auditId(), audit.contentSha256(), audit.currentRowSha256(), "restore true before image");
        assertTrue(db.queryForObject("SELECT warnings FROM platform_lesson_version_structure WHERE version_id=?", String.class, version)
                .contains("metadata before repair"));
    }
}
