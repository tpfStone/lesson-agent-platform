package com.zone.lesoongen.platform;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.zone.lesoongen.application.LessonResultService;
import com.zone.lesoongen.application.AppException;
import com.zone.lesoongen.api.dto.LessonResponses;
import com.zone.lesoongen.domain.job.JobStatus;
import com.zone.lesoongen.domain.shared.Ulids;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest @ActiveProfiles("test")
class PlatformAdoptionTest {
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformGeneratedLessonService generated;
    @Autowired PlatformOptimizeService optimize;
    @Autowired PlatformLessonService lessons;
    @Autowired PlatformLessonDocumentService documents;
    @MockitoBean LessonResultService results;

    private String job(String mode) {
        String id = Ulids.next(java.time.Clock.systemUTC());
        db.update("INSERT INTO lesson_job (id, mode, status, subject, grade, topic, duration_minutes, request_snapshot, request_sha256, owner_id, best_version_id, created_at, updated_at) VALUES (?, ?, 'COMPLETED', '数学', '五', '面积', 40, '{}', ?, ?, 'revision-1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                id, mode, "a".repeat(64), com.zone.lesoongen.auth.CurrentUserService.LEGACY_USER_ID);
        return id;
    }
    @Test void serverAdoptionIsIdempotentAndKeepsProcedureAndAdditionalContent() throws Exception {
        String job = job("GENERATE");
        var plan = mapper.readTree("""
                {"metadata":{"subject":"数学","grade":"五","topic":"面积","duration_minutes":40},
                 "procedure_steps":[{"stage":"讨论","duration_minutes":8,"teacher_actions":["追问依据"],"student_actions":["说明依据"],"assessment":"同伴评价","custom":"附加内容"}],
                 "homework":"测量教室","design_thesis":"保留设计主张"}
                """);
        when(results.result(job)).thenReturn(new LessonResponses.LessonResult(job, JobStatus.COMPLETED, null,
                "revision-1", "revision-1", plan, Map.of(), null, null, null, null, List.of(), List.of(), List.of()));
        var first = generated.adopt(job);
        var replay = generated.adopt(job);
        assertEquals(first.versionId(), replay.versionId()); assertTrue(replay.alreadySaved());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM platform_generated_lesson WHERE job_id = ?", Integer.class, job));
        var snapshot = documents.get(first.lessonId(), first.versionId());
        assertTrue(snapshot.usableForSimulation());
        assertEquals(8, LessonDocumentMaterialAdapter.materials(snapshot).get(0).get("durationMinutes"));
        String content = lessons.detail(first.lessonId()).versions().get(0).content();
        assertTrue(content.contains("附加内容") && content.contains("测量教室") && content.contains("保留设计主张"));
        db.update("UPDATE lesson_job SET owner_id = NULL WHERE id = ?", job);
        assertThrows(AppException.class, () -> generated.adopt(job));
    }
    @Test void differentTeacherDraftCannotReplayAnExistingSave() {
        String job = job("OPTIMIZE");
        var source = lessons.create(new PlatformLessonController.CreateLesson("面积", "数学", "五", "面积", 40, "原稿"));
        String lesson = source.lesson().id(), version = source.lesson().currentVersionId();
        db.update("INSERT INTO platform_optimize_job_link (job_id, lesson_id, source_version_id, created_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)", job, lesson, version);
        when(results.artifacts(job)).thenReturn(List.of());
        var first = optimize.submitReview(lesson, job, new PlatformOptimizeReviewController.Submit(version, "教师稿 A", true));
        assertEquals(first.versionId(), optimize.submitReview(lesson, job, new PlatformOptimizeReviewController.Submit(version, "教师稿 A", true)).versionId());
        assertThrows(AppException.class, () -> optimize.submitReview(lesson, job, new PlatformOptimizeReviewController.Submit(version, "教师稿 B", true)));
        assertEquals(2, lessons.detail(lesson).versions().size());
    }
}
