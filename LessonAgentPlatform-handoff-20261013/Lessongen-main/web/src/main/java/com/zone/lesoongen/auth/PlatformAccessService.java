package com.zone.lesoongen.auth;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.sql.Timestamp;
import java.time.Instant;

import com.zone.lesoongen.application.AppException;

@Service
public class PlatformAccessService {
    private final JdbcTemplate db;
    private final CurrentUserService currentUser;

    public PlatformAccessService(JdbcTemplate db, CurrentUserService currentUser) {
        this.db = db;
        this.currentUser = currentUser;
    }

    public String currentUserId() {
        return currentUser.require().id();
    }

    public void requireOwnedLesson(String lessonId) {
        Integer count = db.queryForObject("""
                SELECT COUNT(*) FROM platform_lesson WHERE id = ? AND owner_user_id = ?
                """, Integer.class, lessonId, currentUserId());
        if (count == null || count == 0) {
            // Do not disclose whether another user's lesson exists.
            throw new AppException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "教案不存在");
        }
    }

    public void requireOwnedJob(String jobId) {
        requireCount("SELECT COUNT(*) FROM lesson_job WHERE id = ? AND owner_id = ?", jobId, "任务");
    }

    public void requireOwnedNavigationSession(String sessionId) {
        requireCount("""
                SELECT COUNT(*) FROM platform_navigation_link n
                JOIN platform_lesson l ON l.id = n.lesson_id
                WHERE n.session_id = ? AND l.owner_user_id = ?
                """, sessionId, "协同共析会话");
    }

    public void requireOwnedSimulationLaunch(String launchId) {
        requireCount("""
                SELECT COUNT(*) FROM platform_simulation_launch s
                JOIN platform_lesson l ON l.id = s.lesson_id
                WHERE s.launch_id = ? AND l.owner_user_id = ?
                """, launchId, "课堂推演任务");
    }

    public void requireOwnedAnnotation(String annotationId) {
        requireCount("""
                SELECT COUNT(*) FROM platform_annotation_run a
                JOIN platform_lesson l ON l.id = a.lesson_id
                WHERE a.id = ? AND l.owner_user_id = ?
                """, annotationId, "智能评价任务");
    }

    public void registerExternalTask(String type, String taskId) {
        db.update("""
                INSERT INTO platform_external_task_owner
                (task_type, task_id, owner_user_id, created_at)
                VALUES (?, ?, ?, ?)
                """, type, taskId, currentUserId(), Timestamp.from(Instant.now()));
    }

    public void requireOwnedExternalTask(String type, String taskId) {
        Integer count = db.queryForObject("""
                SELECT COUNT(*) FROM platform_external_task_owner
                WHERE task_type = ? AND task_id = ? AND owner_user_id = ?
                """, Integer.class, type, taskId, currentUserId());
        if (count == null || count == 0) notFound("外部任务");
    }

    public boolean ownsNavigationSession(String sessionId) {
        Integer count = db.queryForObject("""
                SELECT COUNT(*) FROM platform_navigation_link n
                JOIN platform_lesson l ON l.id = n.lesson_id
                WHERE n.session_id = ? AND l.owner_user_id = ?
                """, Integer.class, sessionId, currentUserId());
        return count != null && count > 0;
    }

    private void requireCount(String sql, String id, String label) {
        Integer count = db.queryForObject(sql, Integer.class, id, currentUserId());
        if (count == null || count == 0) notFound(label);
    }

    private static void notFound(String label) {
        throw new AppException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", label + "不存在");
    }
}
