package com.zone.lesoongen.platform;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Missing structures are compatibility repairs, never evidence of successful semantic recovery. */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "platform.structure-backfill.enabled", havingValue = "true")
@Order(Ordered.LOWEST_PRECEDENCE)
public class PlatformLessonDocumentBackfill implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(PlatformLessonDocumentBackfill.class);
    private final JdbcTemplate db;
    private final PlatformLessonDocumentService documents;

    public PlatformLessonDocumentBackfill(JdbcTemplate db, PlatformLessonDocumentService documents) {
        this.db = db;
        this.documents = documents;
    }

    @Override
    public void run(ApplicationArguments args) {
        int count = 0;
        while (true) {
        List<LegacyVersion> missing = db.query("""
                SELECT v.id, v.content
                FROM platform_lesson_version v
                LEFT JOIN platform_lesson_version_structure s ON s.version_id = v.id
                WHERE s.version_id IS NULL
                ORDER BY v.created_at ASC LIMIT 100
                """, (rs, row) -> new LegacyVersion(rs.getString("id"), rs.getString("content")));
        for (LegacyVersion version : missing) {
            if (documents.insertIfAbsent(version.id(), version.content())) count++;
        }
        if (missing.isEmpty()) break;
        }
        if (count > 0) log.info("inserted fallback structures for {} missing versions", count);
    }

    private record LegacyVersion(String id, String content) {
    }
}
