ALTER TABLE platform_lesson_version_structure ADD COLUMN content_sha256 CHAR(64);
ALTER TABLE platform_lesson_version_structure ADD COLUMN structure_sha256 CHAR(64);
ALTER TABLE platform_lesson_version_structure ADD COLUMN projection_version VARCHAR(32);
ALTER TABLE platform_lesson_version_structure ADD COLUMN binding_status VARCHAR(24);
ALTER TABLE platform_lesson_version_structure ADD COLUMN source_version_id CHAR(26);
ALTER TABLE platform_lesson_version_structure ADD COLUMN source_artifact_id VARCHAR(128);
ALTER TABLE platform_lesson_version_structure ADD COLUMN source_artifact_sha256 CHAR(64);
CREATE TABLE platform_version_operation (
    operation_key VARCHAR(255) PRIMARY KEY,
    request_fingerprint CHAR(64) NOT NULL,
    lesson_id CHAR(26) NOT NULL,
    version_id CHAR(26) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    FOREIGN KEY (lesson_id) REFERENCES platform_lesson(id),
    FOREIGN KEY (version_id) REFERENCES platform_lesson_version(id)
);
CREATE TABLE platform_structure_repair_audit (
    id CHAR(26) PRIMARY KEY,
    version_id CHAR(26) NOT NULL,
    old_structure LONGTEXT,
    old_structure_sha256 CHAR(64),
    new_structure_sha256 CHAR(64) NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL
);
