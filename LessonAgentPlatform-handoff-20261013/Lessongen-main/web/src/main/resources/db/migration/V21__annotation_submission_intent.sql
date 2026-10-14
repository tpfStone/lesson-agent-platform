CREATE TABLE platform_annotation_submission (
    id CHAR(26) PRIMARY KEY,
    operation_key VARCHAR(255) NOT NULL UNIQUE,
    lesson_id CHAR(26) NOT NULL,
    version_id CHAR(26) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    request_json LONGTEXT NOT NULL,
    state VARCHAR(24) NOT NULL,
    last_error VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    FOREIGN KEY (lesson_id) REFERENCES platform_lesson(id),
    FOREIGN KEY (version_id) REFERENCES platform_lesson_version(id)
);
