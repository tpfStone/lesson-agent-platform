CREATE TABLE platform_annotation_batch_submission (
    id CHAR(32) PRIMARY KEY,
    operation_key VARCHAR(255) NOT NULL UNIQUE,
    owner_user_id CHAR(26) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    request_json LONGTEXT NOT NULL,
    state VARCHAR(24) NOT NULL,
    last_error VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
