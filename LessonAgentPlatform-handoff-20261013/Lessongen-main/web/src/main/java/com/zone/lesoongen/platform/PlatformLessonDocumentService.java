package com.zone.lesoongen.platform;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zone.lesoongen.application.AppException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Persists the structured representation belonging to one immutable lesson version. */
@Service
public class PlatformLessonDocumentService {
    private final JdbcTemplate db;
    private final ObjectMapper mapper;

    public PlatformLessonDocumentService(JdbcTemplate db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    @Transactional
    public void save(String versionId, LessonDocument document, String parseStatus,
            String parserName, String parserVersion, List<String> warnings) {
        validate(document);
        String content = db.queryForObject("SELECT content FROM platform_lesson_version WHERE id = ? FOR UPDATE", String.class, versionId);
        boolean verified = LessonDocumentProjection.project(document).content().equals(content)
                || ("1.0".equals(document.schemaVersion()) && LessonDocumentSupport.renderText(document).equals(content));
        if ("PARSED".equals(parseStatus) && !verified) throw new AppException(HttpStatus.UNPROCESSABLE_ENTITY,
                "LESSON_DOCUMENT_CONTENT_MISMATCH", "结构与所保存正文不一致");
        String documentJson = json(document);
        String warningsJson = json(warnings == null ? List.of() : warnings);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        // UPDATE of a missing MySQL key takes a gap lock. Two new versions
        // could then deadlock on INSERT despite locking different parent rows.
        db.update("""
                INSERT INTO platform_lesson_version_structure
                (version_id, schema_version, structured_content, parse_status, parser_name,
                 parser_version, warnings, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE schema_version = ?, structured_content = ?,
                    parse_status = ?, parser_name = ?, parser_version = ?, warnings = ?, updated_at = ?
                """, versionId, document.schemaVersion(), documentJson, parseStatus, parserName,
                parserVersion, warningsJson, Timestamp.from(now), Timestamp.from(now),
                document.schemaVersion(), documentJson, parseStatus, parserName,
                parserVersion, warningsJson, Timestamp.from(now));
        db.update("""
                UPDATE platform_lesson_version_structure SET content_sha256 = ?, structure_sha256 = ?,
                    projection_version = ?, binding_status = ? WHERE version_id = ?
                """, sha256(content), structureSha256(document), LessonDocumentProjection.VERSION,
                "PARSED".equals(parseStatus) && verified ? "VERIFIED" : "TEXT_FALLBACK", versionId);
    }

    /** Serialize missing insertion with normal writes using the immutable version row. */
    @Transactional
    public boolean insertIfAbsent(String versionId, String content) {
        db.queryForObject("SELECT id FROM platform_lesson_version WHERE id = ? FOR UPDATE", String.class, versionId);
        if (db.queryForObject("SELECT COUNT(*) FROM platform_lesson_version_structure WHERE version_id = ?",
                Integer.class, versionId) > 0) return false;
        save(versionId, LessonDocumentSupport.fromPlainText(content), "MISSING_FALLBACK", "PLAIN_TEXT", "1",
                List.of("检测到结构缺失，已按该版本正文补录兼容结构；缺失原因未知，教学表格未确认恢复。"));
        var current = db.queryForMap("SELECT structure_sha256 FROM platform_lesson_version_structure WHERE version_id = ?", versionId);
        db.update("INSERT INTO platform_structure_repair_audit (id, version_id, old_structure, old_structure_sha256, new_structure_sha256, content_sha256, reason, created_at) VALUES (?, ?, NULL, NULL, ?, ?, ?, ?)",
                com.zone.lesoongen.domain.shared.Ulids.next(java.time.Clock.systemUTC()), versionId, current.get("structure_sha256"), sha256(content),
                "MISSING_FALLBACK: cause unknown; no semantic recovery claimed", Timestamp.from(Instant.now()));
        return true;
    }

    public static void validate(LessonDocument document) {
        var result = LessonDocumentValidator.validate(document);
        if (!result.valid()) throw new AppException(HttpStatus.UNPROCESSABLE_ENTITY,
                result.errors().contains("UNSUPPORTED_SCHEMA") ? "LESSON_DOCUMENT_UNSUPPORTED_SCHEMA" : "LESSON_DOCUMENT_INVALID",
                "结构化教案不符合内容契约：" + String.join(",", result.errors()));
    }

    public static String sha256(String content) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public String structureSha256(LessonDocument document) {
        return sha256(json(sorted(mapper.readValue(json(document), Object.class))));
    }

    private Object sorted(Object value) {
        if (value instanceof java.util.Map<?, ?> map) {
            var result = new java.util.TreeMap<String, Object>();
            map.forEach((key, item) -> result.put(key.toString(), sorted(item)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(this::sorted).toList();
        return value;
    }

    public VersionDocument get(String lessonId, String versionId) {
        List<Row> rows = db.query("""
                SELECT s.version_id, s.schema_version, s.structured_content, s.parse_status,
                       s.parser_name, s.parser_version, s.warnings, s.created_at, s.updated_at
                FROM platform_lesson_version_structure s
                JOIN platform_lesson_version v ON v.id = s.version_id
                WHERE v.lesson_id = ? AND v.id = ?
                """, (rs, row) -> mapRow(rs), lessonId, versionId);
        if (rows.isEmpty()) {
            Integer versionExists = db.queryForObject("""
                    SELECT COUNT(*) FROM platform_lesson_version WHERE lesson_id = ? AND id = ?
                    """, Integer.class, lessonId, versionId);
            if (versionExists == null || versionExists == 0) throw AppException.notFound("教案版本");
            throw new AppException(HttpStatus.NOT_FOUND, "LESSON_DOCUMENT_NOT_FOUND",
                    "该历史版本尚未生成结构化教案；可继续使用原正文，或重新解析后补齐");
        }
        Row row = rows.get(0);
        LessonDocument document = parseDocument(row.documentJson());
        validate(document);
        String content = db.queryForObject("SELECT content FROM platform_lesson_version WHERE id = ?", String.class, versionId);
        String structureHash = structureSha256(document);
        var stored = db.queryForMap("SELECT content_sha256, structure_sha256, binding_status FROM platform_lesson_version_structure WHERE version_id = ?", versionId);
        if ((stored.get("content_sha256") != null && !sha256(content).equals(stored.get("content_sha256")))
                || (stored.get("structure_sha256") != null && !structureHash.equals(stored.get("structure_sha256")))) throw corrupt();
        boolean matches = LessonDocumentProjection.project(document).content().equals(content)
                || ("1.0".equals(document.schemaVersion()) && LessonDocumentSupport.renderText(document).equals(content));
        String binding = "PARSED".equals(row.parseStatus()) ? (matches ? "VERIFIED" : "UNVERIFIED") : "TEXT_FALLBACK";
        return new VersionDocument(lessonId, row.versionId(), row.schemaVersion(),
                document, row.parseStatus(), row.parserName(),
                row.parserVersion(), parseWarnings(row.warningsJson()), row.createdAt(), row.updatedAt(),
                sha256(content), structureHash, "1.0".equals(document.schemaVersion()) ? "legacy-1.0" : LessonDocumentProjection.VERSION,
                binding, "VERIFIED".equals(binding) && document.blocks().stream().anyMatch(b -> b.teachingProcess() != null));
    }

    private Row mapRow(ResultSet rs) throws SQLException {
        return new Row(rs.getString("version_id"), rs.getString("schema_version"),
                rs.getString("structured_content"), rs.getString("parse_status"),
                rs.getString("parser_name"), rs.getString("parser_version"),
                rs.getString("warnings"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException error) {
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "LESSON_DOCUMENT_SERIALIZE_FAILED",
                    "结构化教案无法保存");
        }
    }

    private LessonDocument parseDocument(String value) {
        try {
            return mapper.readValue(value, LessonDocument.class);
        } catch (JacksonException error) {
            throw corrupt();
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> parseWarnings(String value) {
        try {
            return (List<String>) (List<?>) mapper.readValue(value, List.class);
        } catch (JacksonException error) {
            throw corrupt();
        }
    }

    private static AppException corrupt() {
        return new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "LESSON_DOCUMENT_CORRUPT",
                "已保存的结构化教案无法读取");
    }

    public record VersionDocument(String lessonId, String versionId, String schemaVersion,
            LessonDocument document, String parseStatus, String parserName, String parserVersion,
            List<String> warnings, Instant createdAt, Instant updatedAt,
            String contentSha256, String structureSha256, String projectionVersion,
            String bindingStatus, boolean usableForSimulation) {
    }

    private record Row(String versionId, String schemaVersion, String documentJson,
            String parseStatus, String parserName, String parserVersion, String warningsJson,
            Instant createdAt, Instant updatedAt) {
    }
}
