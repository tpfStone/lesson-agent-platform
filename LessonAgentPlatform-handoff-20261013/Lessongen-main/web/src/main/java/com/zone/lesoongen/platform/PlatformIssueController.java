package com.zone.lesoongen.platform;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/lessons/{lessonId}/versions/{versionId}/issues")
public class PlatformIssueController {
    private final PlatformIssueService issues;

    public PlatformIssueController(PlatformIssueService issues) {
        this.issues = issues;
    }

    public record Resolution(long expectedRevision, @jakarta.validation.constraints.NotBlank String resolutionStatus,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=2000) String reason) {}

    @GetMapping("/revalidations")
    public List<java.util.Map<String,Object>> revalidations(@PathVariable String lessonId, @PathVariable String versionId) {
        return issues.revalidations(lessonId, versionId);
    }

    @org.springframework.web.bind.annotation.PatchMapping("/revalidations/{issueId}")
    public List<java.util.Map<String,Object>> revalidate(@PathVariable String lessonId, @PathVariable String versionId,
            @PathVariable String issueId, @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody Resolution input) {
        issues.revalidate(lessonId, versionId, issueId, input);
        return issues.revalidations(lessonId, versionId);
    }

    @org.springframework.web.bind.annotation.PatchMapping("/{issueId}")
    public IssueDetail resolve(@PathVariable String lessonId, @PathVariable String versionId,
            @PathVariable String issueId, @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody Resolution input) {
        return issues.resolve(lessonId, versionId, issueId, input);
    }

    public record IssueSummary(
            String issueId,
            String lessonId,
            String versionId,
            int versionNumber,
            String sourceModule,
            String sourceRunId,
            String sourceIssueId,
            String category,
            String title,
            String description,
            String severity,
            String status,
            BigDecimal confidence,
            String sectionRef,
            int evidenceCount,
            int recommendationCount,
            Instant createdAt,
            Instant updatedAt, String sourceDecision, String resolutionStatus, long revision, String resolutionReason) {}

    public record IssuePage(
            List<IssueSummary> items,
            int page,
            int size,
            long totalElements,
            int totalPages) {}

    public record IssueEvidence(
            String evidenceId,
            String evidenceType,
            String sourceEventId,
            Integer sequenceNumber,
            String quoteText,
            String sectionRef,
            String payloadJson,
            Instant createdAt) {}

    public record IssueRecommendation(
            String recommendationId,
            String title,
            String actionText,
            String targetModule,
            String priority,
            String status,
            Instant createdAt) {}

    public record IssueContext(
            String contextId,
            String contextType,
            String sourceModule,
            String sourceRunId,
            String referenceId,
            String label,
            String payloadJson,
            Instant createdAt) {}

    public record IssueDetail(
            IssueSummary issue,
            List<IssueEvidence> evidence,
            List<IssueRecommendation> recommendations,
            List<IssueContext> context) {}

    @GetMapping
    public IssuePage list(
            @PathVariable String lessonId,
            @PathVariable String versionId,
            @RequestParam(required = false) String sourceModule,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return issues.list(lessonId, versionId, sourceModule, status, severity, page, size);
    }

    @GetMapping("/{issueId}")
    public IssueDetail detail(
            @PathVariable String lessonId,
            @PathVariable String versionId,
            @PathVariable String issueId) {
        return issues.detail(lessonId, versionId, issueId);
    }
}
