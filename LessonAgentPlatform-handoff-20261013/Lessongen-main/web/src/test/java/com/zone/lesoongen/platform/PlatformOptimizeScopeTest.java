package com.zone.lesoongen.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.zone.lesoongen.application.AppException;
import com.zone.lesoongen.application.LessonJobService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
class PlatformOptimizeScopeTest {
    @Autowired PlatformOptimizeService service;
    @Autowired PlatformLessonService lessons;
    @Autowired LessonJobService jobs;
    @Autowired ObjectMapper mapper;
    @MockitoBean PlatformIssueContextService issues;

    private tools.jackson.databind.JsonNode persistedInput(String jobId) {
        var value = mapper.readTree(jobs.get(jobId).getRequestSnapshot());
        // H2's JSON column wraps a JDBC String; production MySQL returns the object JSON.
        // Inspect the payload, and separately round-trip it through the actual engine DTO.
        if (value.isTextual()) value = mapper.readTree(value.asText());
        var input = mapper.treeToValue(value, com.zone.lesoongen.infrastructure.engine.EngineContracts.LessonInput.class);
        assertEquals(value, mapper.valueToTree(input));
        return value;
    }

    @Test void localScopeReachesPersistedEngineRequestAndReplayCannotWidenIt() {
        when(issues.optimizationFocus(anyString(), anyString())).thenReturn(List.of("公共建议：增加作业"));
        var lesson = lessons.create(new PlatformLessonController.CreateLesson("局部范围", "数学", "五年级", "面积", 45,
                "教学目标\n解释面积\n\n教学过程\n引导比较\n\n作业\n原题"));
        var payload = new java.util.HashMap<String, Object>(Map.of(
                "versionId", lesson.lesson().currentVersionId(), "requestKey", UUID.randomUUID().toString(),
                "optimizationFocus", List.of("让提问更具体"),
                "optimizationScope", Map.of("mode", "local", "editablePaths", List.of("/procedure_steps"))));
        var input = mapper.convertValue(payload, PlatformOptimizeController.Start.class);
        assertNotNull(input.optimizationScope(), "scope must survive API DTO deserialization");
        assertEquals("local", input.optimizationScope().mode());
        var accepted = service.start(lesson.lesson().id(), input);
        var persisted = persistedInput(accepted.jobId());
        assertEquals("local", persisted.path("optimization_scope").path("mode").asText(), persisted.toString());
        assertEquals("/procedure_steps", persisted.path("optimization_scope").path("editable_paths").get(0).asText());
        assertTrue(persisted.path("optimization_focus").toString().contains("公共建议"));
        assertEquals(accepted, service.start(lesson.lesson().id(), input));
        payload.put("optimizationScope", Map.of("mode", "full", "editablePaths", List.of()));
        assertEquals("IDEMPOTENCY_KEY_CONFLICT", assertThrows(AppException.class,
                () -> service.start(lesson.lesson().id(), mapper.convertValue(payload, PlatformOptimizeController.Start.class))).code());
    }

    @Test void legacyUserScopeIsNotMixedWithDerivedSuggestions() {
        when(issues.optimizationFocus(anyString(), anyString())).thenReturn(List.of("公共建议：增加作业"));
        var lesson = lessons.create(new PlatformLessonController.CreateLesson("历史范围", "数学", "五年级", "面积", 45, "原始教案"));
        var input = mapper.convertValue(Map.of("versionId", lesson.lesson().currentVersionId(),
                "requestKey", UUID.randomUUID().toString(), "optimizationFocus", List.of("教学过程"),
                "additionalRequirements", "用户原要求必须保留"), PlatformOptimizeController.Start.class);
        var accepted = service.start(lesson.lesson().id(), input);
        var persisted = persistedInput(accepted.jobId());
        assertEquals(List.of("教学过程"), mapper.convertValue(persisted.path("optimization_focus"), List.class), persisted.toString());
        assertTrue(persisted.path("additional_requirements").asText().contains("用户原要求必须保留"));
        assertTrue(persisted.path("additional_requirements").asText().contains("公共建议：增加作业"));
    }
}
