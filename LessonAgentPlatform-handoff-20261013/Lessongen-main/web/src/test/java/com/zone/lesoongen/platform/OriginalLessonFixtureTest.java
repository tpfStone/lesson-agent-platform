package com.zone.lesoongen.platform;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class OriginalLessonFixtureTest {
    @Test void suppliedV0KeepsSixTeachingRowsThroughMaterialsAndWord() throws Exception {
        var mapper = JsonMapper.builder().build();
        LessonDocument document;
        try (var input = getClass().getResourceAsStream("/fixtures/original-v0-structure.json")) {
            document = mapper.readValue(input, LessonDocument.class);
        }
        assertTrue(LessonDocumentValidator.validate(document).valid());
        var snapshot = new PlatformLessonDocumentService.VersionDocument("lesson", "V0", "1.0", document,
                "PARSED", "SOURCE_FIXTURE", "1", List.of(), null, null, "body-hash", "structure-hash",
                "legacy-1.0", "VERIFIED", true);
        var materials = LessonDocumentMaterialAdapter.materials(snapshot);
        assertEquals(6, materials.size());
        assertEquals(List.of(5, 12, 8, 9, 4, 2), materials.stream().map(m -> m.get("durationMinutes")).toList());
        assertEquals(6, materials.stream().map(m -> m.get("materialId")).distinct().count());
        assertTrue(materials.stream().allMatch(m -> !m.get("studentActivity").toString().isBlank()));
        assertTrue(materials.stream().allMatch(m -> !m.get("evaluation").toString().isBlank()));
        var bytes = LessonDocumentDocxExporter.export(document);
        try (var word = new org.apache.poi.xwpf.usermodel.XWPFDocument(new java.io.ByteArrayInputStream(bytes))) {
            assertEquals(3, word.getTables().size());
            assertEquals(7, word.getTables().get(2).getRows().size());
        }
        var parsed = new DocxLessonExtractor().extract(LessonDocumentFiles.upload(bytes), 20L * 1024 * 1024);
        assertEquals(6, parsed.structuredContent().blocks().stream().filter(b -> b.teachingProcess() != null)
                .mapToInt(b -> b.teachingProcess().rows().size()).sum());
    }
}
