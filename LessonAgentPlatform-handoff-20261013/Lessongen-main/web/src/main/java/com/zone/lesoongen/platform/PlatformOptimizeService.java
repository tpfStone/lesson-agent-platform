package com.zone.lesoongen.platform;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.ArrayList;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import java.util.Objects;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.multipart.MultipartFile;


import com.zone.lesoongen.api.dto.LessonRequests;
import com.zone.lesoongen.api.dto.LessonResponses;
import com.zone.lesoongen.application.AppException;
import com.zone.lesoongen.application.LessonJobService;
import com.zone.lesoongen.application.LessonResultService;
import com.zone.lesoongen.domain.artifact.ArtifactType;
import com.zone.lesoongen.platform.PlatformLessonController.LessonDetail;
import com.zone.lesoongen.platform.PlatformLessonController.LessonVersion;
import tools.jackson.databind.ObjectMapper;

@Service
public class PlatformOptimizeService {
    private final JdbcTemplate db;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    private final PlatformLessonDocumentService documents;
    private final DocxLessonExtractor extractor;
    private final PlatformLessonService lessons;
    private final LessonJobService jobs;
    private final LessonResultService results;
    private final PlatformIssueContextService issueContext;
    private final ObjectMapper mapper;

    public PlatformOptimizeService(JdbcTemplate db, PlatformLessonService lessons,
                                   LessonJobService jobs, LessonResultService results,
                                   PlatformIssueContextService issueContext, PlatformLessonDocumentService documents, DocxLessonExtractor extractor, org.springframework.transaction.PlatformTransactionManager transactionManager, ObjectMapper mapper) {
        this.tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.documents = documents; this.extractor = extractor;
        this.db = db; this.lessons = lessons; this.jobs = jobs; this.results = results;
        this.issueContext = issueContext;
        this.mapper = mapper;
    }

    public record Link(String jobId, String lessonId, String sourceVersionId, String savedVersionId) {}
    public record History(String jobId, String lessonId, String lessonTitle, String sourceVersionId,
            String savedVersionId, String status, Instant createdAt) {}
    public record Saved(String lessonId, String sourceVersionId, String versionId,
            boolean alreadySaved, boolean unchanged) {}

    private record RequestIdentity(String schema, String lessonId, PlatformOptimizeController.Start request) {}
    private record ExistingRequest(String jobId, String lessonId, String requestHash) {}

    // Owner row serialization provides same-key isolation; RC avoids gap locks
    // between different owners whose first request keys do not exist yet.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LessonResponses.JobAccepted start(String lessonId, PlatformOptimizeController.Start input) {
        String owner = jobs.lockSubmissionOwner();
        String requestHash = requestHash(lessonId, input);
        // This locking read sees a concurrent first submission after waiting for its owner lock.
        var previous = db.query("""
                SELECT j.id, l.lesson_id, l.request_sha256
                FROM lesson_job j LEFT JOIN platform_optimize_job_link l ON l.job_id = j.id
                WHERE j.owner_id = ? AND j.idempotency_key = ? FOR UPDATE
                """, (rs, row) -> new ExistingRequest(rs.getString(1), rs.getString(2), rs.getString(3)),
                owner, input.requestKey().toString()).stream().findFirst();
        if (previous.isPresent()) {
            var existing = previous.get();
            if (existing.lessonId() == null || !existing.lessonId().equals(lessonId))
                throw new AppException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT", "该请求编号已用于另一份请求");
            if (existing.requestHash() == null)
                throw new AppException(HttpStatus.CONFLICT, "OPTIMIZE_LEGACY_REQUEST_UNVERIFIED",
                        "此编号已有历史优化任务，无法核实原始请求；请从任务历史继续查看，本次未创建新任务");
            if (!existing.requestHash().equals(requestHash))
                throw new AppException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT", "该请求编号已用于另一份请求");
            return jobs.replayAfterSubmissionLock(existing.jobId());
        }
        LessonDetail detail = lessons.detail(lessonId);
        if (!Objects.equals(detail.lesson().currentVersionId(), input.versionId())) {
            throw new AppException(HttpStatus.CONFLICT, "OPTIMIZE_VERSION_CHANGED", "请先选择教案当前版本再优化");
        }
        LessonVersion version = detail.versions().stream().filter(v -> v.id().equals(input.versionId()))
                .findFirst().orElseThrow(() -> AppException.notFound("教案版本"));
        String subject = nonblank(input.subject(), nonblank(detail.lesson().subject(), "未指定学科"));
        String grade = nonblank(input.grade(), nonblank(detail.lesson().grade(), "未指定年级"));
        String topic = nonblank(input.topic(), nonblank(detail.lesson().topic(), detail.lesson().title()));
        Integer duration = input.durationMinutes() != null ? input.durationMinutes()
                : detail.lesson().durationMinutes() != null ? detail.lesson().durationMinutes() : 45;
        if (subject.length() > 64 || grade.length() > 64 || topic.length() > 255
                || duration < 5 || duration > 240) {
            throw new AppException(HttpStatus.BAD_REQUEST, "OPTIMIZE_METADATA_INVALID", "请检查学科、年级、课题和课时");
        }
        List<String> derivedFocus = issueContext.optimizationFocus(lessonId, version.id());
        List<String> focus = mergeFocus(input.optimizationFocus(), derivedFocus);
        String requirements = input.additionalRequirements();
        // Derived issue suggestions are never an authorization to expand scope.
        // A legacy request with no user focus retains its old full-task meaning.
        var scope = input.optimizationScope();
        if (scope == null && (input.optimizationFocus() == null || input.optimizationFocus().isEmpty()))
            scope = new com.zone.lesoongen.api.dto.OptimizationScope("full", List.of());
        else if (scope == null) {
            // Only the old user's exact field names may resolve authorization.
            // Natural-language issue context stays advisory and is never parsed as scope.
            focus = input.optimizationFocus();
            if (derivedFocus != null && !derivedFocus.isEmpty()) {
                requirements = (requirements == null ? "" : requirements) + "\n\n已有公共建议（不扩大本次范围）：\n"
                        + String.join("\n", derivedFocus);
                if (requirements.length() > 8000)
                    throw new AppException(HttpStatus.BAD_REQUEST, "OPTIMIZE_CONTEXT_TOO_LONG",
                            "原有要求和公共建议超过长度上限，请明确选择优化范围后重新提交；本次未创建任务");
            }
        }

        LessonRequests.Optimize request = new LessonRequests.Optimize(subject, grade, topic, duration,
                input.courseInformation(), input.textbookVersion(), input.textbookContent(),
                input.curriculumStandards(), input.learningObjectives(), input.studentProfile(),
                input.classSize(), input.availableResources(), requirements,
                input.lessonStyle(), input.detailLevel(),
                focus, input.mustPreserveContent(), null, scope);
        var accepted = jobs.createOptimize(request, inputDocx(lessonId, version), input.requestKey().toString());
        db.update("""
                INSERT INTO platform_optimize_job_link
                (job_id, lesson_id, source_version_id, saved_version_id, created_at, request_sha256)
                VALUES (?, ?, ?, NULL, ?, ?)
                """, accepted.jobId(), lessonId, version.id(), Timestamp.from(Instant.now()), requestHash);
        Link linked = link(lessonId, accepted.jobId());
        if (!linked.sourceVersionId().equals(version.id())) {
            throw new AppException(HttpStatus.CONFLICT, "OPTIMIZE_JOB_REUSED", "此优化任务已关联其他教案版本");
        }
        return accepted;
    }

    private String requestHash(String lessonId, PlatformOptimizeController.Start input) {
        byte[] source = mapper.writeValueAsString(new RequestIdentity("platform-optimize-1", lessonId, input))
                .getBytes(StandardCharsets.UTF_8);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public Link link(String lessonId, String jobId) {
        return db.query("""
                SELECT job_id, lesson_id, source_version_id, saved_version_id
                FROM platform_optimize_job_link WHERE lesson_id = ? AND job_id = ?
                """, (rs, i) -> new Link(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                lessonId, jobId).stream().findFirst().orElseThrow(() -> AppException.notFound("教案优化任务"));
    }

    public List<History> history(String ownerUserId) {
        return db.query("""
                SELECT l.job_id, l.lesson_id, p.title, l.source_version_id, l.saved_version_id,
                       j.status, l.created_at
                FROM platform_optimize_job_link l
                JOIN platform_lesson p ON p.id = l.lesson_id
                JOIN lesson_job j ON j.id = l.job_id
                WHERE p.owner_user_id = ?
                ORDER BY l.created_at DESC, l.job_id DESC LIMIT 100
                """, (rs, i) -> new History(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getTimestamp(7).toInstant()),
                ownerUserId);
    }

    public Saved save(String lessonId, String jobId) {
        Link before = link(lessonId, jobId);
        if (before.savedVersionId() != null) return new Saved(lessonId, before.sourceVersionId(), before.savedVersionId(), true,
                before.savedVersionId().equals(before.sourceVersionId()));
        var result = results.result(jobId);
        boolean changed = result.optimization() != null && result.optimization().path("content_changed").asBoolean(false);
        var word = !changed ? null : results.artifacts(jobId).stream().filter(a -> a.type() == ArtifactType.BEST_DOCX)
                .findFirst().orElseThrow(() -> new AppException(HttpStatus.CONFLICT, "OPTIMIZE_WORD_MISSING", "优化稿 Word 尚未生成，请稍后重试"));
        var extracted = word == null ? null : readStructuredDocx(jobId, word.artifactId());
        return tx.execute(transaction -> {
        List<Link> locked = db.query("""
                SELECT job_id, lesson_id, source_version_id, saved_version_id
                FROM platform_optimize_job_link WHERE lesson_id = ? AND job_id = ? FOR UPDATE
                """, (rs, i) -> new Link(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                lessonId, jobId);
        if (locked.isEmpty()) throw AppException.notFound("教案优化任务");
        Link binding = locked.get(0);
        if (binding.savedVersionId() != null) {
            return new Saved(lessonId, binding.sourceVersionId(), binding.savedVersionId(), true,
                    binding.savedVersionId().equals(binding.sourceVersionId()));
        }
        LessonDetail detail = lessons.detail(lessonId);
        if (!Objects.equals(detail.lesson().currentVersionId(), binding.sourceVersionId())) {
            throw new AppException(HttpStatus.CONFLICT, "OPTIMIZE_VERSION_CHANGED", "教案已有新版本，请先查看后再决定如何合并优化结果");
        }
        String savedId = binding.sourceVersionId();
        if (changed) {
            String content = extracted.extractedContent();
            if (content.length() > 100000) {
                throw new AppException(HttpStatus.BAD_REQUEST, "OPTIMIZE_CONTENT_TOO_LONG", "优化稿超过教案版本长度限制");
            }
            String original = detail.versions().stream().filter(v -> v.id().equals(binding.sourceVersionId()))
                    .findFirst().orElseThrow(() -> AppException.notFound("教案版本")).content();
            if (!content.isBlank() && !content.equals(original.trim())) {
                savedId = lessons.addStructuredVersion(lessonId, binding.sourceVersionId(), content,
                        "F2_OPTIMIZE", "f2:" + jobId, extracted.structuredContent(), "PARSED", "F2_BEST_DOCX", extracted.warnings()).lesson().currentVersionId();
                db.update("UPDATE platform_lesson_version_structure SET source_artifact_id = ?, source_artifact_sha256 = ? WHERE version_id = ?",
                        word.artifactId(), word.sha256(), savedId);
            }
        }
        db.update("UPDATE platform_optimize_job_link SET saved_version_id = ? WHERE job_id = ?", savedId, jobId);
        return new Saved(lessonId, binding.sourceVersionId(), savedId, false,
                savedId.equals(binding.sourceVersionId()));
        });
    }

    public record ReviewDraft(String lessonId, String jobId, String sourceVersionId, int sourceVersionNumber,
            String originalContent, String candidateContent, String candidateSource, String jobStatus,
            String savedVersionId) {}
    public record ReviewSaved(String lessonId, String versionId, int versionNumber,
            boolean alreadySaved, boolean unchanged) {}

    public ReviewDraft reviewDraft(String lessonId, String jobId) {
        Link binding = link(lessonId, jobId);
        String status = reviewableStatus(jobId);
        LessonDetail detail = lessons.detail(lessonId);
        LessonVersion source = detail.versions().stream().filter(v -> v.id().equals(binding.sourceVersionId()))
                .findFirst().orElseThrow(() -> AppException.notFound("教案版本"));
        String candidate = null;
        String sourceName = null;
        var available = results.artifacts(jobId);
        // A revised candidate is a suggestion only; it has not passed a teacher's review.
        var revised = available.stream().filter(a -> a.type() == ArtifactType.REVISED_CANDIDATE_DOCX)
                .findFirst();
        if (revised.isPresent()) {
            candidate = readDocx(jobId, revised.get().artifactId());
            sourceName = "修改候选稿（待教师核对）";
        } else {
            var best = available.stream().filter(a -> a.type() == ArtifactType.BEST_DOCX).findFirst();
            if (best.isPresent()) {
                candidate = readDocx(jobId, best.get().artifactId());
                sourceName = "任务交付稿（待教师核对）";
            }
        }
        if (candidate != null && (candidate.isBlank() || candidate.trim().equals(source.content().trim()))) {
            candidate = null;
            sourceName = null;
        }
        return new ReviewDraft(lessonId, jobId, source.id(), source.versionNumber(), source.content(),
                candidate, sourceName, status, binding.savedVersionId());
    }

    public ReviewSaved submitReview(String lessonId, String jobId, PlatformOptimizeReviewController.Submit request) {
        // Parse immutable artifacts before acquiring any database lock.
        Link before = link(lessonId, jobId);
        reviewableStatus(jobId);
        if (!Boolean.TRUE.equals(request.confirmed())) throw new AppException(HttpStatus.BAD_REQUEST,
                "REVIEW_NOT_CONFIRMED", "请先确认已核对教案正文");
        var available = before.savedVersionId() == null ? results.artifacts(jobId) : List.<LessonResponses.Artifact>of();
        var candidate = available.stream().filter(a -> a.type() == ArtifactType.REVISED_CANDIDATE_DOCX).findFirst()
                .orElseGet(() -> available.stream().filter(a -> a.type() == ArtifactType.BEST_DOCX).findFirst().orElse(null));
        var parsed = candidate == null ? null : readStructuredDocx(jobId, candidate.artifactId());
        return tx.execute(transaction -> {
        List<Link> locked = db.query("""
                SELECT job_id, lesson_id, source_version_id, saved_version_id
                FROM platform_optimize_job_link WHERE lesson_id = ? AND job_id = ? FOR UPDATE
                """, (rs, i) -> new Link(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                lessonId, jobId);
        if (locked.isEmpty()) throw AppException.notFound("教案优化任务");
        Link binding = locked.get(0);
        reviewableStatus(jobId);
        if (!Boolean.TRUE.equals(request.confirmed())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "REVIEW_NOT_CONFIRMED", "请先确认已核对教案正文");
        }
        if (!binding.sourceVersionId().equals(request.expectedVersionId())) {
            throw new AppException(HttpStatus.CONFLICT, "OPTIMIZE_SOURCE_MISMATCH", "复核所用教案版本不匹配，请刷新");
        }
        LessonDetail detail = lessons.detail(lessonId);
        if (binding.savedVersionId() != null) {
            LessonVersion saved = detail.versions().stream().filter(v -> v.id().equals(binding.savedVersionId()))
                    .findFirst().orElseThrow(() -> AppException.notFound("已保存的教案版本"));
            if (!saved.content().trim().equals(request.content().trim())) {
                throw new AppException(HttpStatus.CONFLICT, "OPTIMIZE_REVIEW_CONFLICT",
                        "此任务已保存另一份复核内容，请保留当前草稿并刷新后合并");
            }
            return new ReviewSaved(lessonId, saved.id(), saved.versionNumber(), true,
                    saved.id().equals(binding.sourceVersionId()));
        }
        if (!binding.sourceVersionId().equals(detail.lesson().currentVersionId())) {
            throw new AppException(HttpStatus.CONFLICT, "OPTIMIZE_VERSION_CHANGED", "教案已有新版本，请先查看后再决定如何合并优化结果");
        }
        LessonVersion original = detail.versions().stream().filter(v -> v.id().equals(binding.sourceVersionId()))
                .findFirst().orElseThrow(() -> AppException.notFound("教案版本"));
        String content = request.content().trim();
        String savedId = original.id();
        int number = original.versionNumber();
        if (!content.equals(original.content().trim())) {
            boolean sameCandidate = parsed != null && content.equals(parsed.extractedContent());
            LessonDetail updated = lessons.addStructuredVersion(lessonId, original.id(), content,
                    "F2_OPTIMIZE", "f2:" + jobId + ":teacher-reviewed",
                    sameCandidate ? parsed.structuredContent() : LessonDocumentSupport.fromPlainText(content),
                    sameCandidate ? "PARSED" : "EDITED_FALLBACK",
                    sameCandidate ? "F2_CANDIDATE_DOCX" : "PLAIN_TEXT", sameCandidate ? parsed.warnings()
                            : List.of("教师已自由编辑候选正文；按实际确认内容保存兼容结构。"));
            savedId = updated.lesson().currentVersionId();
            if (sameCandidate) db.update("UPDATE platform_lesson_version_structure SET source_artifact_id = ?, source_artifact_sha256 = ? WHERE version_id = ?",
                    candidate.artifactId(), candidate.sha256(), savedId);
            number = updated.versions().stream().mapToInt(LessonVersion::versionNumber).max().orElse(number);
        }
        db.update("UPDATE platform_optimize_job_link SET saved_version_id = ? WHERE job_id = ?", savedId, jobId);
        return new ReviewSaved(lessonId, savedId, number, false, savedId.equals(original.id()));
        });
    }

    private String reviewableStatus(String jobId) {
        String status = db.query("SELECT status FROM lesson_job WHERE id = ?", (rs, i) -> rs.getString(1), jobId)
                .stream().findFirst().orElseThrow(() -> AppException.notFound("优化任务"));
        if (!"COMPLETED".equals(status) && !"NEEDS_HUMAN".equals(status)) {
            throw new AppException(HttpStatus.CONFLICT, "REVIEW_NOT_READY", "任务尚未进入可复核状态");
        }
        return status;
    }

    private String readDocx(String jobId, String artifactId) {
        return readStructuredDocx(jobId, artifactId).extractedContent();
    }

    private DocxLessonExtractor.ExtractedDocument readStructuredDocx(String jobId, String artifactId) {
        try (InputStream stream = results.download(jobId, artifactId).resource().getInputStream()) {
            return extractor.extract(LessonDocumentFiles.upload(stream.readAllBytes()), 20L * 1024 * 1024);
        } catch (IOException error) {
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "REVIEW_DRAFT_READ_FAILED", "无法读取所采用的教案产物");
        }
    }

    private MultipartFile inputDocx(String lessonId, LessonVersion version) {
        try {
            var snapshot = documents.get(lessonId, version.id());
            if ("VERIFIED".equals(snapshot.bindingStatus())) return LessonDocumentFiles.upload(LessonDocumentDocxExporter.export(snapshot.document()));
        } catch (AppException error) {
            if (!"LESSON_DOCUMENT_NOT_FOUND".equals(error.code())) throw error;
        } catch (IOException error) {
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "OPTIMIZE_DOCX_FAILED", "无法生成优化输入 Word");
        }
        return docx(version.content());
    }

    private static String nonblank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static List<String> mergeFocus(List<String> userFocus, List<String> issueFocus) {
        List<String> result = new ArrayList<>();

        if (userFocus != null) {
            for (String item : userFocus) {
                if (item != null && !item.isBlank() && result.size() < 20) {
                    result.add(limit(item.trim(), 1000));
                }
            }
        }

        for (String item : issueFocus) {
            if (item != null && !item.isBlank() && result.size() < 20) {
                result.add(limit(item.trim(), 1000));
            }
        }

        return result.isEmpty() ? null : result;
    }

    private static String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private static MultipartFile docx(String content) {
        byte[] bytes;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String line : content.split("\\R", -1)) document.createParagraph().createRun().setText(line);
            document.write(out);
            bytes = out.toByteArray();
        } catch (IOException error) {
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "OPTIMIZE_DOCX_FAILED", "无法生成优化输入 Word");
        }
        return new MultipartFile() {
            public String getName() { return "document"; }
            public String getOriginalFilename() { return "lesson-version.docx"; }
            public String getContentType() { return "application/vnd.openxmlformats-officedocument.wordprocessingml.document"; }
            public boolean isEmpty() { return bytes.length == 0; }
            public long getSize() { return bytes.length; }
            public byte[] getBytes() { return bytes.clone(); }
            public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
            public void transferTo(File dest) throws IOException { java.nio.file.Files.write(dest.toPath(), bytes); }
        };
    }
}
