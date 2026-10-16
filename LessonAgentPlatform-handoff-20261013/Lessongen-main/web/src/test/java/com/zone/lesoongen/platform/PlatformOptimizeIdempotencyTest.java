package com.zone.lesoongen.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.zone.lesoongen.api.dto.LessonRequests;
import com.zone.lesoongen.application.AppException;
import com.zone.lesoongen.application.LessonJobService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
class PlatformOptimizeIdempotencyTest {
    @Autowired PlatformOptimizeService service;
    @Autowired PlatformLessonService lessons;
    @Autowired LessonJobService jobs;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper mapper;
    @MockitoBean PlatformIssueContextService issues;

    @BeforeEach void context() {
        when(issues.optimizationFocus(anyString(), anyString())).thenReturn(List.of("[公共问题][F1] 首次问题"));
    }

    private PlatformLessonController.LessonDetail lesson() {
        return lessons.create(new PlatformLessonController.CreateLesson("幂等回归", "数学", "五年级", "面积", 40,
                "教学目标\n理解面积\n\n教师活动 | 学生活动\n提问 | 回答"));
    }

    private PlatformOptimizeController.Start request(String version, UUID key, String focus) {
        return mapper.convertValue(java.util.Map.of("versionId", version, "requestKey", key.toString(),
                "optimizationFocus", List.of(focus)), PlatformOptimizeController.Start.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retryAcrossSecondsAndChangedDerivedContextReusesFirstTask(boolean legacyPlain) throws Exception {
        var lesson = lesson();
        String lessonId = lesson.lesson().id(), version = lesson.lesson().currentVersionId();
        if (legacyPlain) db.update("DELETE FROM platform_lesson_version_structure WHERE version_id = ?", version);
        var input = request(version, UUID.randomUUID(), "保留课堂讨论");
        var first = service.start(lessonId, input);
        String snapshot = jobs.get(first.jobId()).getRequestSnapshot();
        String storedHash = db.queryForObject("SELECT sha256 FROM lesson_source_file WHERE job_id = ?", String.class, first.jobId());
        Thread.sleep(2100); // POI core properties differ across seconds: the original regression trigger.
        assertEquals(first, service.start(lessonId, input));
        when(issues.optimizationFocus(anyString(), anyString())).thenReturn(List.of("[公共问题][F3] 后来才同步的问题"));
        assertEquals(first, service.start(lessonId, input));
        assertEquals(snapshot, jobs.get(first.jobId()).getRequestSnapshot());
        assertEquals(storedHash, db.queryForObject("SELECT sha256 FROM lesson_source_file WHERE job_id = ?", String.class, first.jobId()));
        lessons.addVersionFromModule(lessonId, version, "后续已保存教案版本", "MANUAL", null);
        assertEquals(first, service.start(lessonId, input), "an exact retry still refers to the first source version");
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM lesson_job WHERE idempotency_key = ?", Integer.class, input.requestKey().toString()));
    }

    @Test void changedUserFocusOrSourceVersionCannotReuseTheKey() {
        var lesson = lesson();
        String lessonId = lesson.lesson().id(), version = lesson.lesson().currentVersionId();
        UUID key = UUID.randomUUID();
        service.start(lessonId, request(version, key, "原始焦点"));
        assertEquals("IDEMPOTENCY_KEY_CONFLICT", assertThrows(AppException.class,
                () -> service.start(lessonId, request(version, key, "修改焦点"))).code());
        var next = lessons.addVersionFromModule(lessonId, version, "新版内容", "MANUAL", null);
        assertEquals("IDEMPOTENCY_KEY_CONFLICT", assertThrows(AppException.class,
                () -> service.start(lessonId, request(next.lesson().currentVersionId(), key, "原始焦点"))).code());
    }

    @Test void concurrentFirstRequestsCreateOnlyOneJobAndSourceFile() throws Exception {
        var lesson = lesson();
        var input = request(lesson.lesson().currentVersionId(), UUID.randomUUID(), "并发焦点");
        var executor = Executors.newFixedThreadPool(6);
        var ready = new CountDownLatch(6);
        var go = new CountDownLatch(1);
        try {
            var pending = new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 6; i++) pending.add(executor.submit(() -> {
                ready.countDown(); assertTrue(go.await(10, TimeUnit.SECONDS));
                return service.start(lesson.lesson().id(), input).jobId();
            }));
            assertTrue(ready.await(10, TimeUnit.SECONDS)); go.countDown();
            var jobIds = new java.util.HashSet<String>();
            for (var future : pending) jobIds.add(future.get(30, TimeUnit.SECONDS));
            assertEquals(1, jobIds.size());
            String job = jobIds.iterator().next();
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM lesson_job WHERE idempotency_key = ?", Integer.class, input.requestKey().toString()));
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM lesson_source_file WHERE job_id = ?", Integer.class, job));
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM platform_optimize_job_link WHERE job_id = ?", Integer.class, job));
        } finally { go.countDown(); executor.shutdownNow(); }
    }

    @Test void legacyIdentityIsExplicitlyUnverifiedAndNeverCreatesAnotherJob() {
        var lesson = lesson();
        var input = request(lesson.lesson().currentVersionId(), UUID.randomUUID(), "历史焦点");
        var first = service.start(lesson.lesson().id(), input);
        db.update("UPDATE platform_optimize_job_link SET request_sha256 = NULL WHERE job_id = ?", first.jobId());
        assertEquals("OPTIMIZE_LEGACY_REQUEST_UNVERIFIED", assertThrows(AppException.class,
                () -> service.start(lesson.lesson().id(), input)).code());
        assertEquals(first.jobId(), service.link(lesson.lesson().id(), first.jobId()).jobId());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM lesson_job WHERE idempotency_key = ?", Integer.class, input.requestKey().toString()));
    }

    @Test void directUploadsContinueToCompareTheActualFileHash() throws Exception {
        var input = mapper.convertValue(java.util.Map.of("subject", "数学", "grade", "五年级", "topic", "面积"), LessonRequests.Optimize.class);
        String key = UUID.randomUUID().toString();
        byte[] firstFile = LessonDocumentDocxExporter.export(LessonDocumentSupport.fromPlainText("原始教案"));
        var first = jobs.createOptimize(input, LessonDocumentFiles.upload(firstFile), key);
        assertEquals(first, jobs.createOptimize(input, LessonDocumentFiles.upload(firstFile), key));
        byte[] changedFile = LessonDocumentDocxExporter.export(LessonDocumentSupport.fromPlainText("真正修改的教案"));
        assertEquals("IDEMPOTENCY_KEY_CONFLICT", assertThrows(AppException.class,
                () -> jobs.createOptimize(input, LessonDocumentFiles.upload(changedFile), key)).code());
    }
}
