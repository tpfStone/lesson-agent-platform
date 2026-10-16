package com.zone.lesoongen.platform;

import com.zone.lesoongen.application.AppException;
import com.zone.lesoongen.auth.CurrentUserService;
import com.zone.lesoongen.domain.shared.Ulids;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** All version/head/structure writes share one short database transaction. */
@Service
public class LessonVersionWriteService {
    private final JdbcTemplate db;
    private final PlatformLessonDocumentService documents;
    private final CurrentUserService currentUser;
    public LessonVersionWriteService(JdbcTemplate db, PlatformLessonDocumentService documents, CurrentUserService currentUser) {
        this.db = db; this.documents = documents; this.currentUser = currentUser;
    }
    public record Written(String lessonId, String versionId, int versionNumber, boolean alreadySaved) {}

    @Transactional
    public Written createInitial(PlatformLessonController.CreateLesson request, String source,
            String reference, LessonDocument document, String parseStatus, String parser, List<String> warnings) {
        PlatformLessonDocumentService.validate(document);
        String lessonId = Ulids.next(Clock.systemUTC()), versionId = Ulids.next(Clock.systemUTC());
        Timestamp now = Timestamp.from(Instant.now());
        db.update("""
                INSERT INTO platform_lesson
                (id, owner_user_id, title, subject, grade, topic, duration_minutes, current_version_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)
                """, lessonId, currentUser.require().id(), request.title().trim(), request.subject().trim(),
                request.grade().trim(), request.topic().trim(), request.durationMinutes(), now, now);
        insert(versionId, lessonId, null, 0, request.content(), source, reference, now);
        documents.save(versionId, document, parseStatus, parser, "1.1", warnings);
        db.update("UPDATE platform_lesson SET current_version_id = ? WHERE id = ?", versionId, lessonId);
        return new Written(lessonId, versionId, 0, false);
    }

    @Transactional
    public Written append(VersionWriteCommand command) {
        var heads = db.query("SELECT current_version_id FROM platform_lesson WHERE id = ? AND owner_user_id = ? FOR UPDATE",
                (rs, row) -> rs.getString(1), command.lessonId(), currentUser.require().id());
        if (heads.isEmpty()) throw AppException.notFound("教案");
        String head = heads.get(0);
        String key = command.operationKey() == null ? null : command.lessonId() + ":" + command.operationKey();
        if (key != null) {
            var previous = db.queryForList("SELECT request_fingerprint, version_id FROM platform_version_operation WHERE operation_key = ?", key);
            // A prior owner lookup can establish an old MySQL REPEATABLE READ
            // snapshot. Only a changed head needs a current read to find a retry.
            // Never carry a missing-key gap lock into a new operation INSERT.
            if (previous.isEmpty() && !Objects.equals(head, command.expectedCurrentVersionId())) {
                previous = db.queryForList("SELECT request_fingerprint, version_id FROM platform_version_operation WHERE operation_key = ? FOR UPDATE", key);
            }
            if (!previous.isEmpty()) {
                if (!Objects.equals(command.requestFingerprint(), previous.get(0).get("request_fingerprint"))) throw conflict("同一操作编号对应不同内容");
                String versionId = (String) previous.get(0).get("version_id");
                return new Written(command.lessonId(), versionId, db.queryForObject("SELECT version_number FROM platform_lesson_version WHERE id = ? FOR UPDATE", Integer.class, versionId), true);
            }
        }
        if (!Objects.equals(head, command.expectedCurrentVersionId())) throw conflict("教案已被修改，请刷新后重新确认当前版本");
        LessonDocument document = command.document() == null ? LessonDocumentSupport.fromPlainText(command.content()) : command.document();
        PlatformLessonDocumentService.validate(document);
        int next = db.queryForObject("SELECT version_number FROM platform_lesson_version WHERE id = ?", Integer.class, head) + 1;
        String versionId = Ulids.next(Clock.systemUTC());
        Timestamp now = Timestamp.from(Instant.now());
        insert(versionId, command.lessonId(), head, next, command.content(), command.sourceModule(), command.nativeReference(), now);
        documents.save(versionId, document, command.document() == null ? "FALLBACK" : command.parseStatus(),
                command.document() == null ? "PLAIN_TEXT" : command.parserName(), "1.1", command.warnings());
        db.update("UPDATE platform_lesson_version_structure SET source_version_id = ? WHERE version_id = ?", head, versionId);
        int updated = db.update("UPDATE platform_lesson SET current_version_id = ?, updated_at = ? WHERE id = ? AND current_version_id = ?",
                versionId, now, command.lessonId(), head);
        if (updated != 1) throw conflict("教案版本已变化");
        // Issues can belong to any ancestor, including results ingested after that
        // version was edited. A new draft must not silently lose those review items.
        db.update("INSERT INTO platform_issue_revalidation (issue_id, target_version_id, status) SELECT id, ?, 'PENDING' FROM platform_issue WHERE lesson_id = ? AND section_ref IS NOT NULL",
                versionId, command.lessonId());
        if (key != null) db.update("INSERT INTO platform_version_operation (operation_key, request_fingerprint, lesson_id, version_id, created_at) VALUES (?, ?, ?, ?, ?)",
                key, command.requestFingerprint(), command.lessonId(), versionId, now);
        return new Written(command.lessonId(), versionId, next, false);
    }
    private void insert(String versionId, String lessonId, String parent, int number, String content,
            String source, String reference, Timestamp now) {
        if (content == null || content.isBlank() || content.length() > 100_000) throw new AppException(HttpStatus.UNPROCESSABLE_ENTITY,
                "LESSON_CONTENT_INVALID", "教案正文须为 1 至 100000 字符");
        db.update("""
                INSERT INTO platform_lesson_version (id, lesson_id, parent_version_id, version_number, content,
                content_sha256, source_module, native_reference, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, versionId, lessonId, parent, number, content, PlatformLessonDocumentService.sha256(content), source, reference, now);
    }
    private AppException conflict(String message) { return new AppException(HttpStatus.CONFLICT, "LESSON_VERSION_CONFLICT", message); }
}
