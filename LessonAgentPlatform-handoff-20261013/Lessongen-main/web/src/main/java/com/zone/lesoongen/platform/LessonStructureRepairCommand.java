package com.zone.lesoongen.platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Opt-in local command: inspect by default; apply only an explicitly supplied reviewed manifest. */
@Component @Profile("structure-repair")
public class LessonStructureRepairCommand implements ApplicationRunner {
    private final LessonStructureRepairService repairs;
    private final ObjectMapper mapper;
    public LessonStructureRepairCommand(LessonStructureRepairService repairs, ObjectMapper mapper) { this.repairs = repairs; this.mapper = mapper; }
    private String option(ApplicationArguments args, String name, String fallback) {
        var values = args.getOptionValues(name); return values == null || values.isEmpty() ? fallback : values.get(0);
    }
    @Override public void run(ApplicationArguments args) throws Exception {
        boolean apply = Boolean.parseBoolean(option(args, "repair-apply", "false"));
        if (apply) {
            Path manifest = Path.of(option(args, "repair-manifest", "reviewed-repairs.json")).toAbsolutePath();
            for (var item : mapper.readTree(Files.readAllBytes(manifest))) {
                Path document = manifest.getParent().resolve(item.path("documentPath").asText()).normalize();
                repairs.repair(item.path("versionId").asText(), item.path("expectedContentSha256").asText(),
                        item.path("expectedOldRawStructureSha256").isNull() || item.path("expectedOldRawStructureSha256").isMissingNode()
                                ? null : item.path("expectedOldRawStructureSha256").asText(),
                        mapper.readValue(Files.readAllBytes(document), LessonDocument.class), item.path("reason").asText());
            }
        }
        var report = new ArrayList<LessonStructureRepairService.Inspection>();
        String cursor = "";
        while (true) {
            var batch = repairs.inspect(cursor, 100); if (batch.isEmpty()) break;
            report.addAll(batch); cursor = batch.get(batch.size() - 1).versionId();
        }
        Files.writeString(Path.of(option(args, "repair-report", "structure-repair-report.json")), mapper.writeValueAsString(report));
    }
}
