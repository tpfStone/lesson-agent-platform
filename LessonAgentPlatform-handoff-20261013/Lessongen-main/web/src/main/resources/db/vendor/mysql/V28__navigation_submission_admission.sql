-- Restored parent tables can retain collations different from the new database
-- default. Match each referenced ID and the legacy session join key without
-- rewriting historical tables.
SELECT CHARACTER_SET_NAME, COLLATION_NAME
INTO @navigation_owner_charset, @navigation_owner_collation
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_user' AND COLUMN_NAME = 'id';

SELECT CHARACTER_SET_NAME, COLLATION_NAME
INTO @navigation_lesson_charset, @navigation_lesson_collation
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_lesson' AND COLUMN_NAME = 'id';

SELECT CHARACTER_SET_NAME, COLLATION_NAME
INTO @navigation_version_charset, @navigation_version_collation
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_lesson_version' AND COLUMN_NAME = 'id';

SELECT CHARACTER_SET_NAME, COLLATION_NAME
INTO @navigation_session_charset, @navigation_session_collation
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_navigation_link' AND COLUMN_NAME = 'session_id';

SET @navigation_submission_ddl = CONCAT(
    'CREATE TABLE platform_navigation_submission (',
    'request_key CHAR(36) PRIMARY KEY,',
    'owner_user_id CHAR(26) CHARACTER SET ', @navigation_owner_charset, ' COLLATE ', @navigation_owner_collation, ' NOT NULL,',
    'lesson_id CHAR(26) CHARACTER SET ', @navigation_lesson_charset, ' COLLATE ', @navigation_lesson_collation, ' NOT NULL,',
    'source_version_id CHAR(26) CHARACTER SET ', @navigation_version_charset, ' COLLATE ', @navigation_version_collation, ' NOT NULL,',
    'request_fingerprint CHAR(64) NOT NULL,',
    'request_json LONGTEXT NULL,',
    'admission_version VARCHAR(128) NOT NULL,',
    'session_id CHAR(36) CHARACTER SET ', @navigation_session_charset, ' COLLATE ', @navigation_session_collation, ' NULL,',
    'created_at DATETIME(6) NOT NULL,',
    'updated_at DATETIME(6) NOT NULL,',
    'CONSTRAINT fk_navigation_submission_owner FOREIGN KEY (owner_user_id) REFERENCES platform_user(id),',
    'CONSTRAINT fk_navigation_submission_lesson FOREIGN KEY (lesson_id) REFERENCES platform_lesson(id),',
    'CONSTRAINT fk_navigation_submission_version FOREIGN KEY (source_version_id) REFERENCES platform_lesson_version(id)',
    ')'
);
PREPARE navigation_submission_create FROM @navigation_submission_ddl;
EXECUTE navigation_submission_create;
DEALLOCATE PREPARE navigation_submission_create;

CREATE INDEX idx_navigation_submission_session ON platform_navigation_submission(session_id);
