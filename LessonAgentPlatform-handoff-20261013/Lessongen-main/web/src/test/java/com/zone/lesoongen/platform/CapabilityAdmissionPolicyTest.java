package com.zone.lesoongen.platform;

import static org.junit.jupiter.api.Assertions.*;

import com.zone.lesoongen.api.dto.LessonRequests;
import com.zone.lesoongen.application.AppException;
import com.zone.lesoongen.application.LessonJobService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The switch is exercised through real admission services and a hot-replaced file. */
@SpringBootTest
@ActiveProfiles("test")
class CapabilityAdmissionPolicyTest {
    static final Path FILE;
    static {
        try { FILE = Files.createTempDirectory("admission-policy-").resolve("capabilities.json"); }
        catch (IOException error) { throw new ExceptionInInitializerError(error); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("platform.admission-file", FILE::toString);
        // A regression must never reach any configured live engine.
        registry.add("app.f1-base-url", () -> "http://127.0.0.1:1");
        registry.add("platform.f4.base-url", () -> "http://127.0.0.1:1");
        registry.add("platform.f3.base-url", () -> "http://127.0.0.1:1");
        registry.add("platform.f3.python", () -> "admission-test-no-executable");
    }
    @Autowired PlatformLessonService lessons;
    @Autowired PlatformLessonImportService imports;
    @Autowired PlatformAnnotationService annotations;
    @Autowired PlatformNavigationBridgeService navigation;
    @Autowired PlatformSimulationLaunchService simulations;
    @Autowired LessonJobService jobs;
    @Autowired JdbcTemplate db;

    @BeforeEach void openAll() throws Exception { config(true, true, true, true, true); }

    static void config(boolean structure, boolean f1, boolean f2, boolean f3, boolean f4) throws Exception {
        atomic("{\"version\":\"test-" + UUID.randomUUID() + "\",\"structure\":" + structure
                + ",\"f1\":" + f1 + ",\"f2\":" + f2 + ",\"f3\":" + f3 + ",\"f4\":" + f4 + "}");
    }
    static void atomic(String json) throws Exception {
        Path next = FILE.resolveSibling("capabilities.next");
        Files.writeString(next, json, StandardCharsets.UTF_8);
        Files.move(next, FILE, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    PlatformLessonController.LessonDetail lesson() {
        return lessons.create(new PlatformLessonController.CreateLesson("开关样本", "数学", "五年级", "面积", 40, "原稿需要保留。"));
    }
    static LessonRequests.Generate generate(String topic) {
        return new LessonRequests.Generate("数学", "五年级", topic, 40, "", "", "", List.of(),
                List.of(), "", null, List.of(), "", null, null, null);
    }
    static void disabled(org.junit.jupiter.api.function.Executable action) {
        AppException error = assertThrows(AppException.class, action);
        assertEquals("FEATURE_DISABLED", error.code());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.status());
    }

    @Test void structureOffBlocksManualCreateVersionAndImportBeforeAnyPersistence() throws Exception {
        var lesson = lesson();
        long before = db.queryForObject("SELECT COUNT(*) FROM platform_lesson", Long.class);
        config(false, true, true, true, true);
        disabled(this::lesson);
        disabled(() -> lessons.addVersion(lesson.lesson().id(), new PlatformLessonController.NewVersion(
                lesson.lesson().currentVersionId(), "新人工稿")));
        var file = new MockMultipartFile("file", "invalid.docx", "application/octet-stream", new byte[] {1});
        disabled(() -> imports.preview(file));
        disabled(() -> imports.importDocx(file, null));
        assertEquals(before, db.queryForObject("SELECT COUNT(*) FROM platform_lesson", Long.class));
        assertEquals(1, lessons.detail(lesson.lesson().id()).versions().size());
    }

    @Test void f1OffBlocksSingleAndBatchBeforeCreatingSubmissionIntent() throws Exception {
        var first = lesson(); var second = lesson();
        config(true, false, true, true, true);
        disabled(() -> annotations.create(first.lesson().id(), first.lesson().currentVersionId(), false, "new-single"));
        disabled(() -> annotations.createBatch(List.of(
                new PlatformAnnotationService.BatchItemRef(first.lesson().id(), first.lesson().currentVersionId()),
                new PlatformAnnotationService.BatchItemRef(second.lesson().id(), second.lesson().currentVersionId())), false, "new-batch"));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM platform_annotation_submission WHERE lesson_id = ?", Integer.class, first.lesson().id()));
    }

    @Test void f2OffBlocksGenerateBeforeSavingJob() throws Exception {
        config(true, true, false, true, true);
        String key = UUID.randomUUID().toString();
        disabled(() -> jobs.createGenerate(generate("新任务"), key));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM lesson_job WHERE idempotency_key = ?", Integer.class, key));
    }

    @Test void f1OffAlsoBlocksNewComparisonOfExistingOwnedResults() throws Exception {
        var first = lesson(); var second = lesson();
        var ids = new java.util.ArrayList<String>();
        for (var item : List.of(first, second)) {
            String id = com.zone.lesoongen.domain.shared.Ulids.next(java.time.Clock.systemUTC());
            db.update("INSERT INTO platform_annotation_run (id, lesson_id, version_id, native_run_id, created_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                    id, item.lesson().id(), item.lesson().currentVersionId(), UUID.randomUUID().toString().replace("-", ""));
            ids.add(id);
        }
        config(true, false, true, true, true);
        disabled(() -> annotations.createComparison(ids));
    }

    @Test void legacyUploadOptimizeReplaysWhileClosedButChangedFileStillConflicts() throws Exception {
        var request = new LessonRequests.Optimize("数学", "五年级", "面积", 40, "", "", "", List.of(),
                List.of(), "", null, List.of(), "", null, null, List.of(), List.of(), null);
        var file = new MockMultipartFile("file", "source.docx", "application/octet-stream",
                LessonDocumentDocxExporter.export(LessonDocumentSupport.fromPlainText("原上传内容")));
        String key = UUID.randomUUID().toString();
        var original = jobs.createOptimize(request, file, key);
        config(true, true, false, true, true);
        assertEquals(original.jobId(), jobs.createOptimize(request, file, key).jobId());
        var changed = new MockMultipartFile("file", "source.docx", "application/octet-stream",
                LessonDocumentDocxExporter.export(LessonDocumentSupport.fromPlainText("改变上传内容")));
        assertEquals("IDEMPOTENCY_KEY_CONFLICT", assertThrows(AppException.class,
                () -> jobs.createOptimize(request, changed, key)).code());
        disabled(() -> jobs.createOptimize(request, file, UUID.randomUUID().toString()));
    }

    @Test void f3OffBlocksLaunchBeforeBackgroundWork() throws Exception {
        var lesson = lesson();
        UUID key = UUID.randomUUID();
        config(true, true, true, false, true);
        disabled(() -> simulations.start(new PlatformSimulationLaunchController.LaunchRequest(
                lesson.lesson().id(), lesson.lesson().currentVersionId(), key, null, null)));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM platform_simulation_launch WHERE request_key = ?", Integer.class, key.toString()));
    }

    @Test void f4OffBlocksNewSessionBeforeRemoteCreation() throws Exception {
        var lesson = lesson();
        config(true, true, true, true, false);
        disabled(() -> navigation.start(lesson.lesson().id(), lesson.lesson().currentVersionId(), UUID.randomUUID(), null, null, null));
    }

    @Test void existingClassroomReplayPrecedesGateAndChangedInputStillConflicts() throws Exception {
        var lesson = lesson(); UUID id = UUID.randomUUID(), key = UUID.randomUUID();
        db.update("INSERT INTO platform_simulation_launch (launch_id, request_key, lesson_id, version_id, state, timeout_seconds, message, created_at, updated_at) VALUES (?, ?, ?, ?, 'COMPLETED', 3, 'completed fixture', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                id.toString(), key.toString(), lesson.lesson().id(), lesson.lesson().currentVersionId());
        config(false, false, false, false, false);
        assertEquals(id, simulations.start(new PlatformSimulationLaunchController.LaunchRequest(
                lesson.lesson().id(), lesson.lesson().currentVersionId(), key, null, null)).launchId());
        assertEquals("F3_REQUEST_REUSED", assertThrows(AppException.class, () -> simulations.start(
                new PlatformSimulationLaunchController.LaunchRequest(lesson.lesson().id(), lesson.lesson().currentVersionId(), key, null, 4))).code());
        assertEquals("COMPLETED", simulations.status(id).state());
    }

    @Test void closedStructureAlsoBlocksNewModuleAdmission() throws Exception {
        config(false, true, true, true, true);
        disabled(() -> jobs.createGenerate(generate("结构主开关"), UUID.randomUUID().toString()));
    }

    @Test void hotReplacementReopensNewTasksAndNeverBlocksExistingReplayOrRead() throws Exception {
        String key = UUID.randomUUID().toString();
        var first = jobs.createGenerate(generate("已准入"), key);
        config(true, true, false, true, true);
        assertEquals(first.jobId(), jobs.createGenerate(generate("已准入"), key).jobId());
        assertEquals(first.jobId(), jobs.get(first.jobId()).getId());
        assertEquals("IDEMPOTENCY_KEY_CONFLICT", assertThrows(AppException.class,
                () -> jobs.createGenerate(generate("改变输入"), key)).code());
        disabled(() -> jobs.createGenerate(generate("关闭中新任务"), UUID.randomUUID().toString()));
        config(true, true, true, true, true);
        assertNotEquals(first.jobId(), jobs.createGenerate(generate("重开新任务"), UUID.randomUUID().toString()).jobId());
    }

    @Test void malformedMissingOrDuplicateConfigurationFailsClosedButHistoryRemainsReadable() throws Exception {
        var original = lesson();
        String valid = "{\"version\":\"v\",\"structure\":true,\"f1\":true,\"f2\":true,\"f3\":true,\"f4\":true}";
        for (String invalid : List.of("{", "{}", "{\"version\":\"v\",\"structure\":true,\"f1\":true,\"f2\":false,\"f2\":true,\"f3\":true,\"f4\":true}",
                valid + " trailing-garbage", valid + " {}")) {
            atomic(invalid);
            AppException error = assertThrows(AppException.class, this::lesson);
            assertEquals("FEATURE_CONFIGURATION_INVALID", error.code());
            assertEquals(original.lesson().id(), lessons.detail(original.lesson().id()).lesson().id());
        }
        Files.delete(FILE);
        assertEquals("FEATURE_CONFIGURATION_INVALID", assertThrows(AppException.class, this::lesson).code());
    }

    @Test void closingStructureDoesNotBlockAlreadyAdmittedModuleWriteback() throws Exception {
        var original = lesson();
        config(false, false, false, false, false);
        var saved = lessons.addVersionFromModule(original.lesson().id(), original.lesson().currentVersionId(),
                "既有任务返回的完整稿", "F4", "existing-admitted-session");
        assertEquals(2, saved.versions().size());
        assertEquals("既有任务返回的完整稿", saved.versions().get(1).content());
    }

    @Test void ownershipIsCheckedBeforeDisabledResponseEvenForServiceCall() throws Exception {
        var original = lesson();
        String owner = com.zone.lesoongen.domain.shared.Ulids.next(java.time.Clock.systemUTC());
        db.update("INSERT INTO platform_user (id, username, password_hash, display_name, role, status, created_at, updated_at) VALUES (?, ?, 'test-only', 'Other owner', 'TEACHER', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", owner, owner);
        db.update("UPDATE platform_lesson SET owner_user_id = ? WHERE id = ?", owner, original.lesson().id());
        config(false, false, false, false, false);
        AppException error = assertThrows(AppException.class, () -> navigation.start(original.lesson().id(),
                original.lesson().currentVersionId(), UUID.randomUUID(), null, null, null));
        assertEquals(HttpStatus.NOT_FOUND, error.status());
    }
}
