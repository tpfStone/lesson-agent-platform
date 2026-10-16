CREATE TABLE platform_navigation_submission (
    request_key CHAR(36) PRIMARY KEY,
    owner_user_id CHAR(26) NOT NULL,
    lesson_id CHAR(26) NOT NULL,
    source_version_id CHAR(26) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    request_json LONGTEXT NULL,
    admission_version VARCHAR(128) NOT NULL,
    session_id CHAR(36) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_navigation_submission_owner FOREIGN KEY (owner_user_id) REFERENCES platform_user(id),
    CONSTRAINT fk_navigation_submission_lesson FOREIGN KEY (lesson_id) REFERENCES platform_lesson(id),
    CONSTRAINT fk_navigation_submission_version FOREIGN KEY (source_version_id) REFERENCES platform_lesson_version(id)
);
CREATE INDEX idx_navigation_submission_session ON platform_navigation_submission(session_id);
