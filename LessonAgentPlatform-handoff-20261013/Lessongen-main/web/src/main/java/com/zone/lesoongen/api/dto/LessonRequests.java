package com.zone.lesoongen.api.dto;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class LessonRequests {
    private LessonRequests() {
    }

    public record Generate(
            @NotBlank @Size(max = 64) String subject,
            @NotBlank @Size(max = 64) String grade,
            @NotBlank @Size(max = 255) String topic,
            @Min(5) @Max(240) Integer durationMinutes,
            @Size(max = 4000) String courseInformation,
            @Size(max = 255) String textbookVersion,
            @Size(max = 30000) String textbookContent,
            @Size(max = 50) List<@NotBlank @Size(max = 1000) String> curriculumStandards,
            @Size(max = 20) List<@NotBlank @Size(max = 1000) String> learningObjectives,
            @Size(max = 8000) String studentProfile,
            @Min(1) @Max(200) Integer classSize,
            @Size(max = 50) List<@NotBlank @Size(max = 500) String> availableResources,
            @Size(max = 8000) String additionalRequirements,
            @Pattern(regexp = "choose_the_best_fit_for_this_topic|inquiry_through_cognitive_conflict|authentic_problem_driven|dialogue_and_discussion|project_or_task_based|close_reading_and_evidence") String lessonStyle,
            @Pattern(regexp = "standard|showcase") String detailLevel,
            @Pattern(regexp = "[0-9A-HJKMNP-TV-Z]{26}") String retryOfJobId) {
    }

    public record Optimize(
            @NotBlank @Size(max = 64) String subject,
            @NotBlank @Size(max = 64) String grade,
            @NotBlank @Size(max = 255) String topic,
            @Min(5) @Max(240) Integer durationMinutes,
            @Size(max = 4000) String courseInformation,
            @Size(max = 255) String textbookVersion,
            @Size(max = 30000) String textbookContent,
            @Size(max = 50) List<@NotBlank @Size(max = 1000) String> curriculumStandards,
            @Size(max = 20) List<@NotBlank @Size(max = 1000) String> learningObjectives,
            @Size(max = 8000) String studentProfile,
            @Min(1) @Max(200) Integer classSize,
            @Size(max = 50) List<@NotBlank @Size(max = 500) String> availableResources,
            @Size(max = 8000) String additionalRequirements,
            @Pattern(regexp = "choose_the_best_fit_for_this_topic|inquiry_through_cognitive_conflict|authentic_problem_driven|dialogue_and_discussion|project_or_task_based|close_reading_and_evidence") String lessonStyle,
            @Pattern(regexp = "standard|showcase") String detailLevel,
            @Size(max = 20) List<@NotBlank @Size(max = 1000) String> optimizationFocus,
            @Size(max = 20) List<@NotBlank @Size(max = 2000) String> mustPreserveContent,
            @Pattern(regexp = "[0-9A-HJKMNP-TV-Z]{26}") String retryOfJobId,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) com.zone.lesoongen.api.dto.OptimizationScope optimizationScope) {
        public Optimize(String subject, String grade, String topic, Integer durationMinutes, String courseInformation, String textbookVersion, String textbookContent, List<String> curriculumStandards, List<String> learningObjectives, String studentProfile, Integer classSize, List<String> availableResources, String additionalRequirements, String lessonStyle, String detailLevel, List<String> optimizationFocus, List<String> mustPreserveContent, String retryOfJobId) {
            this(subject, grade, topic, durationMinutes, courseInformation, textbookVersion, textbookContent, curriculumStandards, learningObjectives, studentProfile, classSize, availableResources, additionalRequirements, lessonStyle, detailLevel, optimizationFocus, mustPreserveContent, retryOfJobId, null);
        }
    }
}
