ALTER TABLE platform_user ADD COLUMN failed_login_count INT NOT NULL DEFAULT 0;
ALTER TABLE platform_user ADD COLUMN locked_until TIMESTAMP(6) NULL;

CREATE INDEX idx_platform_user_status_created
    ON platform_user(status, created_at);

CREATE INDEX idx_platform_login_audit_created
    ON platform_login_audit(created_at);
