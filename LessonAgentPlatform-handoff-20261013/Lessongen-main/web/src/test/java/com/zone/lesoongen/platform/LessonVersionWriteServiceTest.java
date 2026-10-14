package com.zone.lesoongen.platform;

import com.zone.lesoongen.application.AppException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test")
class LessonVersionWriteServiceTest {
    @Autowired PlatformLessonService lessons;
    @Autowired PlatformLessonDocumentService documents;
    @Autowired JdbcTemplate db;
    @Autowired LessonStructureRepairService repairs;
    @Test void structureFailureRollsBackVersion() {
        var before = db.queryForObject("SELECT COUNT(*) FROM platform_lesson", Integer.class);
        assertThrows(AppException.class, () -> lessons.createDocx(
                new PlatformLessonController.CreateLesson("课题", "数学", "五年级", "面积", 40, "正文"),
                new LessonDocument("9.0", List.of()), "PARSED", List.of()));
        assertEquals(before, db.queryForObject("SELECT COUNT(*) FROM platform_lesson", Integer.class));
    }
    @Test void parsedStructureMustMatchContent() {
        assertThrows(AppException.class, () -> lessons.createDocx(
                new PlatformLessonController.CreateLesson("课题", "数学", "五年级", "面积", 40, "已修改正文"),
                LessonDocumentSupport.fromPlainText("旧正文"), "PARSED", List.of()));
    }
    @Test void staleVersionCannotAppend() {
        var lesson = lessons.create(new PlatformLessonController.CreateLesson("课题", "数学", "五年级", "面积", 40, "正文"));
        var id = lesson.lesson().id();
        var version = lesson.lesson().currentVersionId();
        lessons.addVersionFromModule(id, version, "新版", "MANUAL", null);
        assertThrows(AppException.class, () -> lessons.addVersionFromModule(id, version, "过期", "MANUAL", null));
        assertEquals(2, lessons.detail(id).versions().size());
    }

    @Test void auditedRepairRejectsStaleSnapshotAndKeepsHistoricalBody() {
        var lesson = lessons.create(new PlatformLessonController.CreateLesson("课题", "数学", "五年级", "面积", 40, "正文"));
        var version = lesson.lesson().currentVersionId();
        var before = repairs.inspect("", 1000).stream().filter(i -> i.versionId().equals(version)).findFirst().orElseThrow();
        var replacement = LessonDocumentSupport.fromPlainText("正文");
        repairs.repair(version, before.contentSha256(), before.oldRawStructureSha256(), replacement, "operator verified source content");
        assertThrows(AppException.class, () -> repairs.repair(version, before.contentSha256(), before.oldRawStructureSha256(), replacement, "stale attempt"));
        assertEquals("正文", db.queryForObject("SELECT content FROM platform_lesson_version WHERE id = ?", String.class, version));
        assertEquals(version, lessons.detail(lesson.lesson().id()).lesson().currentVersionId());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM platform_structure_repair_audit WHERE version_id = ?", Integer.class, version));
        assertFalse(documents.insertIfAbsent(version, "不得覆盖"));
        assertEquals("VERIFIED", documents.get(lesson.lesson().id(), version).bindingStatus());
    }
}
