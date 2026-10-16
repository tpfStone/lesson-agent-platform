package com.zone.lesoongen.platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import com.zone.lesoongen.application.AppException;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test")
class LessonStructureRollbackTest {
    @Autowired PlatformLessonService lessons;
    @Autowired PlatformLessonDocumentService documents;
    @Autowired LessonStructureRepairService repairs;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper mapper;
    @TempDir Path temp;

    private String version() {
        return lessons.create(new PlatformLessonController.CreateLesson("回退", "数学", "五年级", "面积", 40, "原文")).lesson().currentVersionId();
    }
    private LessonStructureRepairService.RollbackInspection repair(String version) {
        var before = repairs.inspect("", 1000).stream().filter(i -> i.versionId().equals(version)).findFirst().orElseThrow();
        repairs.repair(version, before.contentSha256(), before.oldRawStructureSha256(), LessonDocumentSupport.fromPlainText("原文"), "reviewed source");
        return repairs.inspectRollbacks().stream().filter(i -> i.versionId().equals(version)).findFirst().orElseThrow();
    }
    @Test void rollbackRestoresCompleteSnapshotAndNeverMovesHeadOrBody() {
        String version = version();
        var old = db.queryForMap("SELECT * FROM platform_lesson_version_structure WHERE version_id = ?", version);
        String lesson = db.queryForObject("SELECT lesson_id FROM platform_lesson_version WHERE id = ?", String.class, version);
        var audit = repair(version);
        lessons.addVersionFromModule(lesson, version, "新版本正文", "MANUAL", null);
        String head = lessons.detail(lesson).lesson().currentVersionId();
        repairs.rollback(audit.auditId(), audit.contentSha256(), audit.currentRowSha256(), "verified rollback");
        assertEquals(old, db.queryForMap("SELECT * FROM platform_lesson_version_structure WHERE version_id = ?", version));
        assertEquals("原文", db.queryForObject("SELECT content FROM platform_lesson_version WHERE id = ?", String.class, version));
        assertEquals(head, lessons.detail(lesson).lesson().currentVersionId());
        assertNotNull(db.queryForObject("SELECT rolled_back_at FROM platform_structure_repair_audit WHERE id = ?", java.sql.Timestamp.class, audit.auditId()));
        assertThrows(AppException.class, () -> repairs.rollback(audit.auditId(), audit.contentSha256(), audit.currentRowSha256(), "repeat"));
    }
    @Test void staleMetadataOrManifestCannotOverwriteLaterWork() {
        String version = version();
        var audit = repair(version);
        assertThrows(AppException.class, () -> repairs.rollback(audit.auditId(), "0".repeat(64), audit.currentRowSha256(), "stale body"));
        db.update("UPDATE platform_lesson_version_structure SET warnings = ? WHERE version_id = ?", "[\"later operator\"]", version);
        var changed = db.queryForMap("SELECT * FROM platform_lesson_version_structure WHERE version_id = ?", version);
        assertThrows(AppException.class, () -> repairs.rollback(audit.auditId(), audit.contentSha256(), audit.currentRowSha256(), "stale metadata"));
        assertEquals(changed, db.queryForMap("SELECT * FROM platform_lesson_version_structure WHERE version_id = ?", version));
    }
    @Test void corruptSnapshotIsIneligibleInDryRunAndCannotBeApplied() {
        String version = version();
        var audit = repair(version);
        db.update("UPDATE platform_structure_repair_audit SET old_row_snapshot = ? WHERE id = ?", "null", audit.auditId());
        var inspected = repairs.inspectRollbacks().stream().filter(i -> i.auditId().equals(audit.auditId())).findFirst().orElseThrow();
        assertFalse(inspected.eligible());
        assertEquals("SNAPSHOT_CORRUPT", inspected.status());
        assertThrows(AppException.class, () -> repairs.rollback(audit.auditId(), audit.contentSha256(), audit.currentRowSha256(), "corrupt audit"));
    }
    @Test void missingStructureIsRestoredAsMissingAndLegacyAuditIsRejected() {
        String version = version();
        db.update("DELETE FROM platform_lesson_version_structure WHERE version_id = ?", version);
        var audit = repair(version);
        repairs.rollback(audit.auditId(), audit.contentSha256(), audit.currentRowSha256(), "restore absent row");
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM platform_lesson_version_structure WHERE version_id = ?", Integer.class, version));
        var next = repair(version);
        db.update("UPDATE platform_structure_repair_audit SET old_row_snapshot = NULL WHERE id = ?", next.auditId());
        assertThrows(AppException.class, () -> repairs.rollback(next.auditId(), next.contentSha256(), next.currentRowSha256(), "legacy audit"));
    }
    @Test void commandDefaultsToDryRunAndOnlyAppliesExplicitRollbackManifest() throws Exception {
        String version = version();
        var audit = repair(version);
        Path manifest = temp.resolve("rollback.json"), report = temp.resolve("report.json"), audits = temp.resolve("audits.json");
        Files.writeString(manifest, mapper.writeValueAsString(List.of(java.util.Map.of(
                "auditId", audit.auditId(), "expectedContentSha256", audit.contentSha256(),
                "expectedCurrentRowSha256", audit.currentRowSha256(), "reason", "approved rollback"))));
        var command = new LessonStructureRepairCommand(repairs, mapper);
        command.run(new DefaultApplicationArguments("--repair-rollback=true", "--repair-manifest=" + manifest,
                "--repair-report=" + report, "--repair-audit-report=" + audits));
        assertEquals("PARSED", db.queryForObject("SELECT parse_status FROM platform_lesson_version_structure WHERE version_id = ?", String.class, version));
        assertTrue(Files.readString(audits).contains(audit.auditId()));
        command.run(new DefaultApplicationArguments("--repair-rollback=true", "--repair-apply=true",
                "--repair-manifest=" + manifest, "--repair-report=" + report));
        assertEquals("FALLBACK", db.queryForObject("SELECT parse_status FROM platform_lesson_version_structure WHERE version_id = ?", String.class, version));
    }
}
