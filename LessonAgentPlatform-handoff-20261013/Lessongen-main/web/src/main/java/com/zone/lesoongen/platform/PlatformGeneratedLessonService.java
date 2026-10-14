package com.zone.lesoongen.platform;
import com.zone.lesoongen.application.AppException;
import com.zone.lesoongen.application.LessonResultService;
import com.zone.lesoongen.auth.PlatformAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;

@Service
public class PlatformGeneratedLessonService {
    private final JdbcTemplate db;
    private final PlatformAccessService access;
    private final LessonResultService results;
    private final PlatformLessonService lessons;
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    public PlatformGeneratedLessonService(JdbcTemplate db, PlatformAccessService access, LessonResultService results, PlatformLessonService lessons,
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.db = db; this.access = access; this.results = results; this.lessons = lessons;
        this.transactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }
    public record Adopted(String lessonId, String versionId, int versionNumber, boolean alreadySaved) {}
    public Adopted adopt(String jobId) {
        access.requireOwnedJob(jobId);
        var result = results.result(jobId);
        String revision = result.bestVersionId();
        if (revision == null) throw new AppException(HttpStatus.CONFLICT, "GENERATED_REVISION_MISSING", "生成结果缺少修订标识");
        String owner = access.currentUserId();
        var document = F2LessonDocumentMapper.map(result.bestLessonPlan());
        String content = LessonDocumentProjection.project(document).content();
        return transactions.execute(status -> {
        var job = db.queryForMap("SELECT status, mode, best_version_id FROM lesson_job WHERE id = ? FOR UPDATE", jobId);
        if (!"COMPLETED".equals(job.get("status")) || !"GENERATE".equals(job.get("mode")))
            throw new AppException(HttpStatus.CONFLICT, "GENERATED_LESSON_NOT_READY", "仅能采用已完成的生成任务");
        if (!revision.equals(job.get("best_version_id"))) throw new AppException(HttpStatus.CONFLICT,
                "GENERATED_REVISION_CHANGED", "任务结果已变化，请刷新后重新采用");
        var existing = db.query("SELECT lesson_id, version_id FROM platform_generated_lesson WHERE job_id = ? AND adopted_revision_id = ? AND owner_id = ?",
                (rs, row) -> new Adopted(rs.getString(1), rs.getString(2), 0, true), jobId, revision, owner);
        if (!existing.isEmpty()) return existing.get(0);
        var metadata = result.bestLessonPlan().path("metadata");
        String topic = metadata.path("topic").asText("生成教案");
        var created = lessons.createGenerated(new PlatformLessonController.CreateLesson(topic,
                metadata.path("subject").asText("未指定"), metadata.path("grade").asText("未指定"), topic,
                metadata.path("duration_minutes").isNumber() ? metadata.path("duration_minutes").asInt() : null,
                content), document, "f2:" + jobId + ":revision:" + revision);
        String lessonId = created.lesson().id(), versionId = created.lesson().currentVersionId();
        db.update("INSERT INTO platform_generated_lesson (job_id, adopted_revision_id, owner_id, lesson_id, version_id, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                jobId, revision, owner, lessonId, versionId, Timestamp.from(Instant.now()));
        return new Adopted(lessonId, versionId, 0, false);
        });
    }
}
