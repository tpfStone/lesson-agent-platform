package com.zone.lesoongen.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** Real production dialect, with a fresh disposable database per test. */
@Testcontainers(disabledWithoutDocker = true)
class MySqlUpgradeAcceptanceTest {
    @Container
    final MySQLContainer mysql = new MySQLContainer("mysql:8.0.44")
            .withDatabaseName("acceptance").withUsername("acceptance").withPassword("test-password");

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration", "classpath:db/vendor/mysql").target(target).load();
    }

    @Test
    void upgradesV17DataWithoutRewritingHistoricalContentAndCanRunAgain() {
        flyway("17").migrate();
        var db = new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
        String lesson = "01J00000000000000000000001", version = "01J00000000000000000000002";
        String body = "教学目标\n中文与 emoji 😀\n\n教师活动 | 学生活动\n提问 | 回答";
        String sha = "a".repeat(64);
        db.update("""
                INSERT INTO platform_lesson (id,owner_user_id,title,subject,grade,topic,current_version_id,created_at,updated_at)
                VALUES (?,'00000000000000000000000000','历史教案','数学','五年级','面积',NULL,NOW(6),NOW(6))
                """, lesson);
        db.update("""
                INSERT INTO platform_lesson_version (id,lesson_id,version_number,content,content_sha256,source_module,created_at)
                VALUES (?,?,0,?,?,'MANUAL',NOW(6))
                """, version, lesson, body, sha);
        db.update("UPDATE platform_lesson SET current_version_id=? WHERE id=?", version, lesson);
        db.update("""
                INSERT INTO platform_lesson_version_structure
                (version_id,schema_version,structured_content,parse_status,parser_name,parser_version,warnings,created_at,updated_at)
                VALUES (?,'1.0','{"schemaVersion":"1.0","blocks":[]}','FALLBACK','legacy','1','[]',NOW(6),NOW(6))
                """, version);
        int issueNumber = 10;
        for (String status : new String[]{"OPEN", "ACCEPTED", "RESOLVED", "DISMISSED"}) {
            db.update("""
                    INSERT INTO platform_issue
                    (id,lesson_id,version_id,source_module,source_run_id,source_issue_id,category,title,description,severity,status,created_at,updated_at)
                    VALUES (?,?,?,'F1','run',?,'test','问题','说明','LOW',?,NOW(6),NOW(6))
                    """, String.format("%026d", issueNumber++), lesson, version, status, status);
        }
        String session = "00000000-0000-0000-0000-000000000001";
        db.update("INSERT INTO platform_navigation_link VALUES (?,?,?,NOW(6))", session, lesson, version);
        db.update("INSERT INTO platform_navigation_writeback VALUES (?,1,?,?,'COMPLETED',NOW(6))", session, sha, version);
        String oldStructure = db.queryForObject("SELECT structured_content FROM platform_lesson_version_structure WHERE version_id=?", String.class, version);

        db.update("INSERT INTO platform_optimize_job_link VALUES ('01J00000000000000000000009',?,?,NULL,NOW(6))", lesson, version);
        assertEquals(11, flyway("28").migrate().migrationsExecuted);
        assertEquals(0, flyway("28").migrate().migrationsExecuted);
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM platform_navigation_submission", Integer.class));
        assertEquals(version, db.queryForObject("SELECT source_version_id FROM platform_navigation_link WHERE session_id=?", String.class, session));
        assertNull(db.queryForObject("SELECT request_sha256 FROM platform_optimize_job_link WHERE lesson_id = ?", String.class, lesson));
        assertEquals(body, db.queryForObject("SELECT content FROM platform_lesson_version WHERE id=?", String.class, version));
        assertEquals(sha, db.queryForObject("SELECT content_sha256 FROM platform_lesson_version WHERE id=?", String.class, version));
        assertEquals(version, db.queryForObject("SELECT current_version_id FROM platform_lesson WHERE id=?", String.class, lesson));
        assertEquals(oldStructure, db.queryForObject("SELECT structured_content FROM platform_lesson_version_structure WHERE version_id=?", String.class, version));
        assertNull(db.queryForObject("SELECT binding_status FROM platform_lesson_version_structure WHERE version_id=?", String.class, version));
        for (String status : new String[]{"OPEN", "ACCEPTED", "RESOLVED", "DISMISSED"}) {
            assertEquals(status, db.queryForObject("SELECT source_decision FROM platform_issue WHERE source_issue_id=?", String.class, status));
            assertEquals(status.equals("ACCEPTED") ? "OPEN" : status,
                    db.queryForObject("SELECT resolution_status FROM platform_issue WHERE source_issue_id=?", String.class, status));
        }
        assertEquals("LEGACY_UNVERIFIED", db.queryForObject("SELECT structure_sha256 FROM platform_navigation_snapshot WHERE session_id=?", String.class, session));
        assertEquals(version, db.queryForObject("SELECT version_id FROM platform_navigation_snapshot WHERE session_id=?", String.class, session));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> db.update(
                "INSERT INTO platform_version_operation VALUES ('orphan',?,?,?,NOW())", sha, lesson, "01J99999999999999999999999"));
    }

    @Test
    void invalidNextMigrationActuallyReachesMysqlAndFailsWithSqlSyntaxError(@TempDir Path directory) throws Exception {
        var installed = flyway("latest");
        installed.migrate();
        var nextVersion = new java.math.BigInteger(installed.info().current().getVersion().getVersion())
                .add(java.math.BigInteger.ONE);
        Files.writeString(directory.resolve("V" + nextVersion + "__invalid_acceptance_sql.sql"), "THIS IS NOT VALID MYSQL SQL;");
        var invalid = Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration", "classpath:db/vendor/mysql", "filesystem:" + directory.toString().replace('\\', '/')).load();
        var failure = assertThrows(FlywayException.class, invalid::migrate);
        Throwable cause = failure;
        while (cause != null && !(cause instanceof SQLException)) cause = cause.getCause();
        assertInstanceOf(SQLException.class, cause, "Must fail in MySQL, not because migration versions collide");
        assertEquals("42000", ((SQLException) cause).getSQLState());
    }

    @Test
    void navigationAdmissionMatchesRestoredParentCollationsInsteadOfNewDatabaseDefault() {
        var db = new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
        db.execute("ALTER DATABASE acceptance CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        flyway("27").migrate();
        String lesson = "01J00000000000000000000041", version = "01J00000000000000000000042";
        String owner = "00000000000000000000000000", session = "00000000-0000-0000-0000-000000000041";
        String body = "恢复库原文与备注 😀", hash = "c".repeat(64);
        db.update("INSERT INTO platform_lesson (id,owner_user_id,title,subject,grade,topic,created_at,updated_at) "
                        + "VALUES (?,?,'恢复样本','数学','五年级','面积',NOW(6),NOW(6))", lesson, owner);
        db.update("INSERT INTO platform_lesson_version (id,lesson_id,version_number,content,content_sha256,source_module,created_at) "
                + "VALUES (?,?,0,?,?,'MANUAL',NOW(6))", version, lesson, body, hash);
        db.update("UPDATE platform_lesson SET current_version_id=? WHERE id=?", version, lesson);
        db.update("INSERT INTO platform_navigation_link VALUES (?,?,?,NOW(6))", session, lesson, version);
        // A dump preserves table/column collations, while the restore database can have
        // a different default. Do not rewrite parent IDs or disable FK checks to migrate.
        db.execute("ALTER DATABASE acceptance CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        assertEquals("utf8mb4_0900_ai_ci", db.queryForObject("SELECT DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA "
                + "WHERE SCHEMA_NAME=DATABASE()", String.class));
        assertEquals(1, flyway("28").migrate().migrationsExecuted);
        assertEquals(0, flyway("28").migrate().migrationsExecuted);
        String legacyLookup = "SELECT COUNT(*) FROM platform_navigation_link n LEFT JOIN platform_navigation_submission s "
                + "ON s.session_id = n.session_id WHERE n.lesson_id = ? AND s.request_key IS NULL";
        assertEquals(1, db.queryForObject(legacyLookup, Integer.class, lesson));
        for (String child : new String[]{"owner_user_id", "lesson_id", "source_version_id", "session_id"}) {
            assertEquals("utf8mb4_unicode_ci", db.queryForObject("SELECT COLLATION_NAME FROM information_schema.COLUMNS "
                    + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='platform_navigation_submission' AND COLUMN_NAME=?", String.class, child));
        }
        assertEquals(3, db.queryForObject("SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='platform_navigation_submission' AND REFERENCED_TABLE_NAME IS NOT NULL", Integer.class));
        assertEquals(body, db.queryForObject("SELECT content FROM platform_lesson_version WHERE id=?", String.class, version));
        assertEquals(hash, db.queryForObject("SELECT content_sha256 FROM platform_lesson_version WHERE id=?", String.class, version));
        assertEquals(version, db.queryForObject("SELECT current_version_id FROM platform_lesson WHERE id=?", String.class, lesson));
        assertEquals(version, db.queryForObject("SELECT source_version_id FROM platform_navigation_link WHERE session_id=?", String.class, session));
        String insert = "INSERT INTO platform_navigation_submission (request_key,owner_user_id,lesson_id,source_version_id,"
                + "request_fingerprint,admission_version,created_at,updated_at) VALUES (?,?,?,?,?,'test',NOW(6),NOW(6))";
        for (int missing = 0; missing < 3; missing++) {
            var references = new String[]{owner, lesson, version};
            references[missing] = "01J99999999999999999999999";
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> db.update(insert,
                    java.util.UUID.randomUUID().toString(), references[0], references[1], references[2], hash));
        }
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM platform_navigation_submission", Integer.class));
        String requestKey = java.util.UUID.randomUUID().toString();
        db.update(insert, requestKey, owner, lesson, version, hash);
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM platform_navigation_submission", Integer.class));
        db.update("UPDATE platform_navigation_submission SET session_id=? WHERE request_key=?", session, requestKey);
        assertEquals(0, db.queryForObject(legacyLookup, Integer.class, lesson));
    }
}
