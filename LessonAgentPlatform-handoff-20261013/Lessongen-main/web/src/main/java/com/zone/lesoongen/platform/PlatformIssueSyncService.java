package com.zone.lesoongen.platform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.sql.Timestamp;
import java.time.Instant;

/** Completion ingestion runs independently of opening a result page; failures remain retryable. */
@Service
public class PlatformIssueSyncService {
    private final JdbcTemplate db;
    private final PlatformAnnotationService annotations;
    private final PlatformNavigationBridgeService navigation;
    @Value("${app.scheduling-enabled:true}") private boolean enabled;
    public PlatformIssueSyncService(JdbcTemplate db, PlatformAnnotationService annotations, PlatformNavigationBridgeService navigation) {
        this.db = db; this.annotations = annotations; this.navigation = navigation;
    }
    @Scheduled(fixedDelay = 30000)
    public void synchronize() {
        if (!enabled) return;
        for (var run : db.queryForList("""
                SELECT a.id, a.lesson_id FROM platform_annotation_run a
                LEFT JOIN platform_issue_sync s ON s.source_key = CONCAT('F1:', a.id)
                WHERE s.state IS NULL OR s.state <> 'DONE'
                ORDER BY COALESCE(s.updated_at, a.created_at) LIMIT 20
                """)) syncAnnotation((String) run.get("lesson_id"), (String) run.get("id"));
        for (String id : db.query("SELECT b.id FROM platform_annotation_batch_submission b LEFT JOIN platform_issue_sync s ON s.source_key = CONCAT('F1_BATCH:', b.id) WHERE b.state = 'BOUND' AND (s.state IS NULL OR s.state <> 'DONE') ORDER BY COALESCE(s.updated_at, b.created_at) LIMIT 20", (rs, row) -> rs.getString(1))) {
            try { annotations.batchResult(id); record("F1_BATCH:" + id, "DONE", null); }
            catch (RuntimeException error) { record("F1_BATCH:" + id, "RETRY", error.getClass().getSimpleName()); }
        }
        for (String id : db.query("""
                SELECT n.session_id FROM platform_navigation_link n
                LEFT JOIN platform_issue_sync s ON s.source_key = CONCAT('F4:', n.session_id)
                ORDER BY COALESCE(s.updated_at, n.created_at) LIMIT 20
                """, (rs, row) -> rs.getString(1))) {
            try { navigation.syncIssues(id); record("F4:" + id, "RECHECK", null); }
            catch (RuntimeException error) { record("F4:" + id, "RETRY", error.getClass().getSimpleName()); }
        }
    }
    public void syncAnnotation(String lessonId, String id) {
        try { annotations.result(lessonId, id); record("F1:" + id, "DONE", null); }
        catch (RuntimeException error) { record("F1:" + id, "RETRY", error.getClass().getSimpleName()); }
    }
    private void record(String key, String state, String error) {
        Timestamp now = Timestamp.from(Instant.now());
        if (db.update("UPDATE platform_issue_sync SET state = ?, last_error = ?, updated_at = ? WHERE source_key = ?", state, error, now, key) == 0) {
            try { db.update("INSERT INTO platform_issue_sync (source_key, state, last_error, updated_at) VALUES (?, ?, ?, ?)", key, state, error, now); }
            catch (org.springframework.dao.DuplicateKeyException racingPoller) {
                db.update("UPDATE platform_issue_sync SET state = ?, last_error = ?, updated_at = ? WHERE source_key = ?", state, error, now, key);
            }
        }
    }
}
