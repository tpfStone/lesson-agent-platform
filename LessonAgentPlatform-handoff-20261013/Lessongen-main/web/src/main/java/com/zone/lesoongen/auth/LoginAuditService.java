package com.zone.lesoongen.auth;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class LoginAuditService {
    public record AuditView(long id, String userId, String usernameAttempted,
            boolean success, String clientIp, Instant createdAt) {}
    private final JdbcTemplate db;
    private final Clock clock;

    public LoginAuditService(JdbcTemplate db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public void record(String userId, String username, boolean success, String clientIp) {
        db.update("""
                INSERT INTO platform_login_audit
                (user_id, username_attempted, success, client_ip, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, userId, username == null ? "" : username, success, clientIp,
                Timestamp.from(clock.instant()));
    }

    public List<AuditView> recent(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 200));
        return db.query("""
                SELECT id, user_id, username_attempted, success, client_ip, created_at
                FROM platform_login_audit
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """, (rs, row) -> new AuditView(rs.getLong("id"), rs.getString("user_id"),
                        rs.getString("username_attempted"), rs.getBoolean("success"),
                        rs.getString("client_ip"), rs.getTimestamp("created_at").toInstant()), limit);
    }
}
