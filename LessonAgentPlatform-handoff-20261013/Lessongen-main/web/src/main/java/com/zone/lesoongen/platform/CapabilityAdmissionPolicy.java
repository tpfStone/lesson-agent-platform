package com.zone.lesoongen.platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import com.zone.lesoongen.application.AppException;
import com.zone.lesoongen.auth.CurrentUserService;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Read one operator-owned atomic file at the admission boundary, never during execution. */
@Component
public class CapabilityAdmissionPolicy {
    public enum Capability {
        STRUCTURE("structure", "结构教案"), F1("f1", "智能评价"), F2("f2", "教案生成与优化"),
        F3("f3", "课堂推演"), F4("f4", "协同共析");
        final String key, label;
        Capability(String key, String label) { this.key = key; this.label = label; }
    }
    private static final Logger log = LoggerFactory.getLogger(CapabilityAdmissionPolicy.class);
    private static final Set<String> FIELDS = Set.of("version", "structure", "f1", "f2", "f3", "f4");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final Path file;
    private final CurrentUserService currentUser;

    public CapabilityAdmissionPolicy(@Value("${platform.admission-file:}") String filename,
            CurrentUserService currentUser) {
        this.file = filename == null || filename.isBlank() ? null : Path.of(filename).toAbsolutePath().normalize();
        this.currentUser = currentUser;
    }

    /** The returned version identifies the immutable decision for the newly admitted intent. */
    public String requireNew(Capability capability) {
        currentUser.require();
        if (file == null) return "default-enabled";
        JsonNode snapshot;
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(65_537);
            if (bytes.length > 65_536) throw new IllegalArgumentException("oversized configuration");
            snapshot = JSON.readTree(bytes);
            if (snapshot == null || !snapshot.isObject() || snapshot.size() != FIELDS.size())
                throw new IllegalArgumentException("invalid fields");
            for (String field : FIELDS) {
                var value = snapshot.get(field);
                if (value == null || (field.equals("version") ? !value.isTextual() : !value.isBoolean()))
                    throw new IllegalArgumentException("invalid field type");
            }
            if (!snapshot.path("version").asText().matches("[A-Za-z0-9._:-]{1,100}"))
                throw new IllegalArgumentException("invalid version");
        } catch (Exception invalid) {
            throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "FEATURE_CONFIGURATION_INVALID",
                    "新任务开关配置暂不可用，请联系本机维护者；已开始任务和历史记录仍可使用。");
        }
        String version = snapshot.path("version").asText();
        Capability blocked = !snapshot.path("structure").asBoolean() ? Capability.STRUCTURE : capability;
        if (!snapshot.path(blocked.key).asBoolean())
            throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "FEATURE_DISABLED",
                    blocked.label + "暂时停止接收新任务；已开始任务、历史下载和已有复核仍可使用。");
        log.info("capability admitted capability={} configuration={}", capability.key, version);
        return version;
    }
}
