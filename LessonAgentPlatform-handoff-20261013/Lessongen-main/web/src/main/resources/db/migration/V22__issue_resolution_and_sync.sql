ALTER TABLE platform_issue ADD COLUMN source_decision VARCHAR(24);
ALTER TABLE platform_issue ADD COLUMN resolution_status VARCHAR(24) NOT NULL DEFAULT 'OPEN';
ALTER TABLE platform_issue ADD COLUMN resolution_reason VARCHAR(2000);
ALTER TABLE platform_issue ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
UPDATE platform_issue SET source_decision = status;
UPDATE platform_issue SET resolution_status = status WHERE status IN ('RESOLVED', 'DISMISSED');
CREATE TABLE platform_issue_sync (
    source_key VARCHAR(128) PRIMARY KEY,
    state VARCHAR(24) NOT NULL,
    last_error VARCHAR(255),
    updated_at TIMESTAMP NOT NULL
);
CREATE TABLE platform_issue_revalidation (
    issue_id CHAR(26) NOT NULL,
    target_version_id CHAR(26) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    evidence LONGTEXT,
    revision BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (issue_id, target_version_id),
    FOREIGN KEY (issue_id) REFERENCES platform_issue(id),
    FOREIGN KEY (target_version_id) REFERENCES platform_lesson_version(id)
);
