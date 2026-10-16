package com.zone.lesoongen.platform;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.zone.lesoongen.application.AppException;
import com.zone.lesoongen.domain.shared.Ulids;

/** Operator-only repair primitive. It never changes historical body bytes or the lesson head. */
@Service
public class LessonStructureRepairService {
    private static final List<String> SNAPSHOT_COLUMNS = List.of("version_id", "schema_version", "structured_content",
            "parse_status", "parser_name", "parser_version", "warnings", "created_at", "updated_at", "content_sha256",
            "structure_sha256", "projection_version", "binding_status", "source_version_id", "source_artifact_id", "source_artifact_sha256");
    private final tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
    private final JdbcTemplate db;
    private final PlatformLessonDocumentService documents;
    public LessonStructureRepairService(JdbcTemplate db, PlatformLessonDocumentService documents) {
        this.db = db; this.documents = documents;
    }
    public record Inspection(String lessonId, String versionId, String contentSha256,
            String oldRawStructureSha256, String classification, String explanation) {}
    public record RollbackInspection(String auditId, String versionId, String contentSha256,
            String currentRowSha256, boolean eligible, String status) {}

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
        var existing = db.query("SELECT structured_content FROM platform_lesson_version_structure WHERE version_id = ? FOR UPDATE", (rs, row) -> rs.getString(1), versionId);
        String old = existing.isEmpty() ? null : existing.get(0);
        String oldHash = old == null ? null : PlatformLessonDocumentService.sha256(old);
        if (!Objects.equals(expectedContentSha256, PlatformLessonDocumentService.sha256(body))
                || !Objects.equals(expectedOldRawStructureSha256, oldHash)) throw new AppException(HttpStatus.CONFLICT,
                        "STRUCTURE_REPAIR_CONFLICT", "版本正文或结构已变化，请重新 dry-run 后复核");
        String oldSnapshot = snapshot(versionId, true);
        // save checks the replacement projection against the immutable historical body.
        documents.save(versionId, replacement, "PARSED", "AUDITED_REPAIR", "1.1", List.of(reason));
        db.update("INSERT INTO platform_structure_repair_audit (id, version_id, old_structure, old_structure_sha256, new_structure_sha256, content_sha256, reason, created_at, old_row_snapshot, old_row_snapshot_sha256, new_row_sha256) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Ulids.next(java.time.Clock.systemUTC()), versionId, old, oldHash, documents.structureSha256(replacement), expectedContentSha256, reason, Timestamp.from(Instant.now()),
                oldSnapshot, PlatformLessonDocumentService.sha256(oldSnapshot), PlatformLessonDocumentService.sha256(snapshot(versionId, true)));
    }

    /** Read-only evidence for an operator-reviewed rollback manifest; legacy audits remain ineligible. */
    public List<RollbackInspection> inspectRollbacks() {
        return db.query("SELECT id, version_id, content_sha256, old_row_snapshot, old_row_snapshot_sha256, new_row_sha256, rolled_back_at FROM platform_structure_repair_audit ORDER BY created_at, id",
                (rs, row) -> {
                    String version = rs.getString("version_id"), current = PlatformLessonDocumentService.sha256(snapshot(version));
                    String body = db.queryForObject("SELECT content FROM platform_lesson_version WHERE id = ?", String.class, version);
                    String status = rs.getTimestamp("rolled_back_at") != null ? "ROLLED_BACK"
                            : rs.getString("old_row_snapshot") == null || rs.getString("new_row_sha256") == null ? "LEGACY_SNAPSHOT_MISSING"
                            : !Objects.equals(PlatformLessonDocumentService.sha256(rs.getString("old_row_snapshot")), rs.getString("old_row_snapshot_sha256")) ? "SNAPSHOT_CORRUPT"
                            : !Objects.equals(current, rs.getString("new_row_sha256")) || !Objects.equals(PlatformLessonDocumentService.sha256(body), rs.getString("content_sha256")) ? "CHANGED"
                            : "ELIGIBLE";
                    return new RollbackInspection(rs.getString("id"), version, rs.getString("content_sha256"), current, "ELIGIBLE".equals(status), status);
                });
    }

    @Transactional
    public void rollback(String auditId, String expectedContentSha256, String expectedCurrentRowSha256, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 255) throw new IllegalArgumentException("rollback reason required (max 255)");
        var auditRows = db.queryForList("SELECT * FROM platform_structure_repair_audit WHERE id = ?", auditId);
        if (auditRows.isEmpty()) throw AppException.notFound("结构修复审计");
        String version = (String) auditRows.get(0).get("version_id");
        // The version row is the same lock used by repair and normal structure writes.
        String body = db.queryForObject("SELECT content FROM platform_lesson_version WHERE id = ? FOR UPDATE", String.class, version);
        var audit = db.queryForMap("SELECT * FROM platform_structure_repair_audit WHERE id = ? FOR UPDATE", auditId);
        String before = (String) audit.get("old_row_snapshot"), after = (String) audit.get("new_row_sha256");
        if (before == null || after == null) throw new AppException(HttpStatus.CONFLICT,
                "STRUCTURE_ROLLBACK_SNAPSHOT_MISSING", "旧审计没有完整结构快照，须使用已验证备份恢复");
        String current = PlatformLessonDocumentService.sha256(snapshot(version, true));
        if (audit.get("rolled_back_at") != null || !Objects.equals(expectedContentSha256, audit.get("content_sha256"))
                || !Objects.equals(expectedContentSha256, PlatformLessonDocumentService.sha256(body))
                || !Objects.equals(expectedCurrentRowSha256, after) || !Objects.equals(current, after)
                || !Objects.equals(PlatformLessonDocumentService.sha256(before), audit.get("old_row_snapshot_sha256"))) {
            throw new AppException(HttpStatus.CONFLICT, "STRUCTURE_ROLLBACK_CONFLICT", "正文、结构或审计已变化，请重新 dry-run 后复核");
        }
        var old = mapper.readTree(before);
        if (!old.isNull()) {
            if (!old.isObject() || old.size() != SNAPSHOT_COLUMNS.size() || !version.equals(old.path("version_id").asText()))
                throw new AppException(HttpStatus.CONFLICT, "STRUCTURE_ROLLBACK_INVALID_SNAPSHOT", "回退快照字段不完整");
            for (String column : SNAPSHOT_COLUMNS) if (!old.has(column))
                throw new AppException(HttpStatus.CONFLICT, "STRUCTURE_ROLLBACK_INVALID_SNAPSHOT", "回退快照字段不完整");
        }
        db.update("DELETE FROM platform_lesson_version_structure WHERE version_id = ?", version);
        if (!old.isNull()) {
            Object[] values = SNAPSHOT_COLUMNS.stream().map(column -> {
                var node = old.get(column);
                return node.isNull() ? null : node.asText();
            }).toArray();
            db.update("INSERT INTO platform_lesson_version_structure (" + String.join(",", SNAPSHOT_COLUMNS)
                    + ") VALUES (" + String.join(",", java.util.Collections.nCopies(SNAPSHOT_COLUMNS.size(), "?")) + ")", values);
        }
        db.update("UPDATE platform_structure_repair_audit SET rolled_back_at = ?, rollback_reason = ? WHERE id = ?",
                Timestamp.from(Instant.now()), reason, auditId);
    }

    private String snapshot(String versionId) {
        return snapshot(versionId, false);
    }

    private String snapshot(String versionId, boolean currentRead) {
        // InnoDB REPEATABLE READ can retain a view established before the parent
        // version lock. Mutation guards and before-images require a current read.
        List<Map<String, String>> rows = db.query("SELECT " + String.join(",", SNAPSHOT_COLUMNS)
                + " FROM platform_lesson_version_structure WHERE version_id = ?" + (currentRead ? " FOR UPDATE" : ""), (rs, row) -> {
            Map<String, String> values = new LinkedHashMap<>();
            for (String column : SNAPSHOT_COLUMNS) values.put(column, rs.getString(column));
            return values;
        }, versionId);
        return mapper.writeValueAsString(rows.isEmpty() ? null : rows.get(0));
    }
}
