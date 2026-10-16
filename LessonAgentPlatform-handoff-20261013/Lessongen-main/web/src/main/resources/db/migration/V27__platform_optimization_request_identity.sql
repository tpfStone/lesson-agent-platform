-- Retain the original binary request digest in lesson_job. This independent
-- identity covers the platform source version and original user request only.
-- Legacy requests have no recoverable original user/derived-focus separation.
ALTER TABLE platform_optimize_job_link ADD COLUMN request_sha256 CHAR(64) NULL;
