package com.zone.lesoongen.platform;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.zone.lesoongen.application.AppException;
import com.zone.lesoongen.domain.shared.Ulids;

/** Operator-only repair primitive. It never changes historical body bytes or the lesson head. */
@Service
public class LessonStructureRepairService {
    private final JdbcTemplate db;
    private final PlatformLessonDocumentService documents;
    public LessonStructureRepairService(JdbcTemplate db, PlatformLessonDocumentService documents) {
        this.db = db; this.documents = documents;
    }
    public record Inspection(String lessonId, String versionId, String contentSha256,
            String oldRawStructureSha256, String classification, String explanation) {}

    public List<Inspection> inspect(String afterVersionId, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be 1..1000");
        return db.query("SELECT v.lesson_id, v.id, v.content, s.structured_content FROM platform_lesson_version v LEFT JOIN platform_lesson_version_structure s ON s.version_id = v.id WHERE v.id > ? ORDER BY v.id LIMIT ?",
                (rs, row) -> {
                    String lesson = rs.getString(1), version = rs.getString(2), content = rs.getString(3), raw = rs.getString(4);
                    String classification = "MISSING", explanation = "Missing structure; cause unknown";
                    if (raw != null) {
                        try {
                            var snapshot = documents.get(lesson, version);
                            classification = snapshot.bindingStatus();
                            explanation = snapshot.parseStatus();
                        } catch (AppException invalid) { classification = "INVALID"; explanation = invalid.code(); }
                    }
                    return new Inspection(lesson, version, PlatformLessonDocumentService.sha256(content),
                            raw == null ? null : PlatformLessonDocumentService.sha256(raw), classification, explanation);
                }, afterVersionId == null ? "" : afterVersionId, limit);
    }

    @Transactional
    public void repair(String versionId, String expectedContentSha256, String expectedOldRawStructureSha256,
            LessonDocument replacement, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 255) throw new IllegalArgumentException("repair reason required (max 255)");
        PlatformLessonDocumentService.validate(replacement);
        String body = db.queryForObject("SELECT content FROM platform_lesson_version WHERE id = ? FOR UPDATE", String.class, versionId);
        var existing = db.query("SELECT structured_content FROM platform_lesson_version_structure WHERE version_id = ?", (rs, row) -> rs.getString(1), versionId);
        String old = existing.isEmpty() ? null : existing.get(0);
        String oldHash = old == null ? null : PlatformLessonDocumentService.sha256(old);
        if (!Objects.equals(expectedContentSha256, PlatformLessonDocumentService.sha256(body))
                || !Objects.equals(expectedOldRawStructureSha256, oldHash)) throw new AppException(HttpStatus.CONFLICT,
                        "STRUCTURE_REPAIR_CONFLICT", "版本正文或结构已变化，请重新 dry-run 后复核");
        // save checks the replacement projection against the immutable historical body.
        documents.save(versionId, replacement, "PARSED", "AUDITED_REPAIR", "1.1", List.of(reason));
        db.update("INSERT INTO platform_structure_repair_audit (id, version_id, old_structure, old_structure_sha256, new_structure_sha256, content_sha256, reason, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                Ulids.next(java.time.Clock.systemUTC()), versionId, old, oldHash, documents.structureSha256(replacement), expectedContentSha256, reason, Timestamp.from(Instant.now()));
    }
}
