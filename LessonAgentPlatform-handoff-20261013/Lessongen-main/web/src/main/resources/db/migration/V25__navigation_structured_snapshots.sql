CREATE TABLE platform_navigation_snapshot (
    session_id CHAR(36) NOT NULL,
    round_number INT NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    structure_sha256 CHAR(64) NOT NULL,
    version_id CHAR(26) NOT NULL,
    native_status VARCHAR(24) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (session_id, round_number, content_sha256, structure_sha256),
    FOREIGN KEY (session_id) REFERENCES platform_navigation_link(session_id),
    FOREIGN KEY (version_id) REFERENCES platform_lesson_version(id)
);
INSERT INTO platform_navigation_snapshot (session_id, round_number, content_sha256, structure_sha256, version_id, native_status, created_at)
SELECT session_id, round_number, content_sha256, 'LEGACY_UNVERIFIED', version_id, native_status, created_at FROM platform_navigation_writeback;
