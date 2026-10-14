package com.zone.lesoongen.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PlatformUserRepository {
    private final JdbcTemplate db;

    public PlatformUserRepository(JdbcTemplate db) {
        this.db = db;
    }

    public Optional<PlatformUser> findByUsername(String username) {
        List<PlatformUser> rows = db.query("""
                SELECT id, username, password_hash, display_name, role, status,
                       created_at, updated_at, last_login_at, failed_login_count, locked_until
                FROM platform_user WHERE username = ?
                """, (rs, row) -> map(rs), username);
        return rows.stream().findFirst();
    }

    public Optional<PlatformUser> findById(String id) {
        List<PlatformUser> rows = db.query("""
                SELECT id, username, password_hash, display_name, role, status,
                       created_at, updated_at, last_login_at, failed_login_count, locked_until
                FROM platform_user WHERE id = ?
                """, (rs, row) -> map(rs), id);
        return rows.stream().findFirst();
    }

    public void insert(PlatformUser user) {
        db.update("""
                INSERT INTO platform_user
                (id, username, password_hash, display_name, role, status,
                 created_at, updated_at, last_login_at, failed_login_count, locked_until)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, user.id(), user.username(), user.passwordHash(), user.displayName(),
                user.role(), user.status(), Timestamp.from(user.createdAt()),
                Timestamp.from(user.updatedAt()), null, user.failedLoginCount(), null);
    }

    public void updateLastLogin(String userId, Instant now) {
        db.update("""
                UPDATE platform_user
                SET last_login_at = ?, updated_at = ?, failed_login_count = 0, locked_until = NULL
                WHERE id = ?
                """,
                Timestamp.from(now), Timestamp.from(now), userId);
    }

    public List<PlatformUser> findAll() {
        return db.query("""
                SELECT id, username, password_hash, display_name, role, status,
                       created_at, updated_at, last_login_at, failed_login_count, locked_until
                FROM platform_user
                WHERE id <> '00000000000000000000000000'
                ORDER BY created_at DESC
                """, (rs, row) -> map(rs));
    }

    public void updateDisplayName(String userId, String displayName, Instant now) {
        db.update("UPDATE platform_user SET display_name = ?, updated_at = ? WHERE id = ?",
                displayName, Timestamp.from(now), userId);
    }

    public void updatePassword(String userId, String passwordHash, Instant now) {
        db.update("""
                UPDATE platform_user
                SET password_hash = ?, updated_at = ?, failed_login_count = 0, locked_until = NULL
                WHERE id = ?
                """, passwordHash, Timestamp.from(now), userId);
    }

    public void updateStatus(String userId, String status, Instant now) {
        db.update("""
                UPDATE platform_user
                SET status = ?, updated_at = ?, failed_login_count = 0, locked_until = NULL
                WHERE id = ?
                """, status, Timestamp.from(now), userId);
    }

    public void recordFailedLogin(String userId, int failedCount, Instant lockedUntil, Instant now) {
        db.update("""
                UPDATE platform_user
                SET failed_login_count = ?, locked_until = ?, updated_at = ?
                WHERE id = ?
                """, failedCount, lockedUntil == null ? null : Timestamp.from(lockedUntil),
                Timestamp.from(now), userId);
    }

    private static PlatformUser map(ResultSet rs) throws SQLException {
        Timestamp lastLogin = rs.getTimestamp("last_login_at");
        Timestamp lockedUntil = rs.getTimestamp("locked_until");
        return new PlatformUser(rs.getString("id"), rs.getString("username"),
                rs.getString("password_hash"), rs.getString("display_name"),
                rs.getString("role"), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                lastLogin == null ? null : lastLogin.toInstant(),
                rs.getInt("failed_login_count"),
                lockedUntil == null ? null : lockedUntil.toInstant());
    }
}
