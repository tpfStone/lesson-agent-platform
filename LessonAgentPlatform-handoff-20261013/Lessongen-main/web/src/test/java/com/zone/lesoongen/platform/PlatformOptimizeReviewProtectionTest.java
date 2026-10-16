package com.zone.lesoongen.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.zone.lesoongen.api.dto.LessonResponses;
import com.zone.lesoongen.application.AppException;
import com.zone.lesoongen.application.LessonResultService;
import com.zone.lesoongen.auth.CurrentUserService;
import com.zone.lesoongen.domain.artifact.ArtifactType;
import com.zone.lesoongen.domain.shared.Ulids;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Review state guards and provenance must hold independently of model quality. */
@SpringBootTest
@ActiveProfiles("test")
class PlatformOptimizeReviewProtectionTest {
    @Autowired JdbcTemplate db;
    @Autowired PlatformOptimizeService optimize;
    @Autowired PlatformLessonService lessons;
    @Autowired PlatformLessonDocumentService documents;
    @Autowired DocxLessonExtractor extractor;
    @MockitoBean LessonResultService results;

    private record Fixture(String lessonId, String sourceVersionId, String jobId, String originalContent) {}

    private LessonDocument document(String activity) {
        return new LessonDocument("1.1", List.of(LessonDocumentSupport.tableBlock("process", List.of(
                List.of("教学环节", "教师活动", "学生活动", "评价"),
                List.of("探究", activity, "说明面积依据", "核对单位")))));
    }

    private Fixture fixture(String status) {
        var original = document("原稿活动，保留此来源");
        String content = LessonDocumentProjection.project(original).content();
        var source = lessons.create(new PlatformLessonController.CreateLesson(
                "复核保护专用", "数学", "五年级", "面积", 40, content));
        String lessonId = source.lesson().id(), version = source.lesson().currentVersionId();
        documents.save(version, original, "PARSED", "APACHE_POI_DOCX", "1.1", List.of());
        String job = Ulids.next(Clock.systemUTC());
        // CANCELLED is a defensive persisted-state probe, not a new public F2 state.
        db.update("""
                INSERT INTO lesson_job (id, mode, status, subject, grade, topic, duration_minutes,
                    request_snapshot, request_sha256, owner_id, created_at, updated_at)
                VALUES (?, 'OPTIMIZE', ?, '数学', '五年级', '面积', 40, '{}', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, job, status, "a".repeat(64), CurrentUserService.LEGACY_USER_ID);
        db.update("""
                INSERT INTO platform_optimize_job_link (job_id, lesson_id, source_version_id, created_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                """, job, lessonId, version);
        return new Fixture(lessonId, version, job, content);
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "RUNNING", "FAILED", "CANCELLED"})
    void nonReviewableTaskCannotReadCandidateOrChangeAnyVersion(String status) {
        var fixture = fixture(status);
        var before = lessons.detail(fixture.lessonId());
        var originalStructure = documents.get(fixture.lessonId(), fixture.sourceVersionId());

        var failure = assertThrows(AppException.class, () -> optimize.submitReview(
                fixture.lessonId(), fixture.jobId(), new PlatformOptimizeReviewController.Submit(
                        fixture.sourceVersionId(), "不能保存的自由稿", true)));

        assertEquals(HttpStatus.CONFLICT, failure.status());
        assertEquals("REVIEW_NOT_READY", failure.code());
        assertEquals(before, lessons.detail(fixture.lessonId()), "head, body and version count must stay unchanged");
        assertEquals(originalStructure, documents.get(fixture.lessonId(), fixture.sourceVersionId()));
        assertNull(optimize.link(fixture.lessonId(), fixture.jobId()).savedVersionId());
        assertEquals(1, db.queryForObject("""
                SELECT COUNT(*) FROM platform_lesson_version_structure s
                JOIN platform_lesson_version v ON v.id = s.version_id WHERE v.lesson_id = ?
                """, Integer.class, fixture.lessonId()));
        verifyNoInteractions(results);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void onlyUnchangedCandidateCanKeepItsVerifiedStructureAndArtifactSource(boolean freeEdit) throws Exception {
        var fixture = fixture("NEEDS_HUMAN");
        var originalStructure = documents.get(fixture.lessonId(), fixture.sourceVersionId());
        byte[] bytes = LessonDocumentDocxExporter.export(document("候选讨论，保留全部活动"));
        var parsed = extractor.extract(LessonDocumentFiles.upload(bytes), 20L * 1024 * 1024);
        assertTrue(parsed.extractedContent().contains("候选讨论"));
        String artifactId = Ulids.next(Clock.systemUTC());
        String artifactHash = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        var artifact = new LessonResponses.Artifact(artifactId, ArtifactType.REVISED_CANDIDATE_DOCX,
                "candidate.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                bytes.length, artifactHash, "/test-only");
        when(results.artifacts(fixture.jobId())).thenReturn(List.of(artifact));
        when(results.download(fixture.jobId(), artifactId)).thenAnswer(invocation -> new LessonResultService.Download(
                new InputStreamResource(new ByteArrayInputStream(bytes)), artifact.displayName(),
                artifact.mediaType(), bytes.length, artifactHash));
        String submitted = freeEdit ? parsed.extractedContent().replace("候选讨论", "教师自由编辑")
                : parsed.extractedContent();

        var saved = optimize.submitReview(fixture.lessonId(), fixture.jobId(),
                new PlatformOptimizeReviewController.Submit(fixture.sourceVersionId(), submitted, true));

        var detail = lessons.detail(fixture.lessonId());
        assertEquals(2, detail.versions().size());
        assertEquals(saved.versionId(), detail.lesson().currentVersionId());
        var version = detail.versions().stream().filter(v -> v.id().equals(saved.versionId())).findFirst().orElseThrow();
        assertEquals(submitted, version.content());
        assertEquals(fixture.sourceVersionId(), version.parentVersionId());
        assertEquals(originalStructure, documents.get(fixture.lessonId(), fixture.sourceVersionId()));
        assertEquals(fixture.originalContent(), detail.versions().stream()
                .filter(v -> v.id().equals(fixture.sourceVersionId())).findFirst().orElseThrow().content());
        var snapshot = documents.get(fixture.lessonId(), saved.versionId());
        var provenance = db.queryForMap("""
                SELECT source_version_id, source_artifact_id, source_artifact_sha256, binding_status
                FROM platform_lesson_version_structure WHERE version_id = ?
                """, saved.versionId());
        assertEquals(fixture.sourceVersionId(), provenance.get("source_version_id"));
        if (freeEdit) {
            assertEquals("EDITED_FALLBACK", snapshot.parseStatus());
            assertEquals("PLAIN_TEXT", snapshot.parserName());
            assertEquals("TEXT_FALLBACK", snapshot.bindingStatus());
            assertEquals("TEXT_FALLBACK", provenance.get("binding_status"));
            assertFalse(snapshot.usableForSimulation());
            assertTrue(snapshot.warnings().stream().anyMatch(w -> w.contains("自由编辑")));
            assertNull(provenance.get("source_artifact_id"));
            assertNull(provenance.get("source_artifact_sha256"));
            assertNotEquals(documents.structureSha256(parsed.structuredContent()), snapshot.structureSha256());
        } else {
            assertEquals("PARSED", snapshot.parseStatus());
            assertEquals("F2_CANDIDATE_DOCX", snapshot.parserName());
            assertEquals("VERIFIED", snapshot.bindingStatus());
            assertTrue(snapshot.usableForSimulation());
            assertEquals(artifactId, provenance.get("source_artifact_id"));
            assertEquals(artifactHash, provenance.get("source_artifact_sha256"));
        }
        var replay = optimize.submitReview(fixture.lessonId(), fixture.jobId(),
                new PlatformOptimizeReviewController.Submit(fixture.sourceVersionId(), submitted, true));
        assertEquals(saved.versionId(), replay.versionId());
        assertTrue(replay.alreadySaved());
        assertEquals(2, lessons.detail(fixture.lessonId()).versions().size());
        verify(results, times(1)).download(fixture.jobId(), artifactId);
    }
}
