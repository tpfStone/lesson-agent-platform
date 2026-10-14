CREATE TABLE platform_external_task_owner (
    task_type VARCHAR(24) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    owner_user_id CHAR(26) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (task_type, task_id),
    CONSTRAINT fk_external_task_owner_user FOREIGN KEY (owner_user_id) REFERENCES platform_user(id)
);

CREATE INDEX idx_external_task_owner_user ON platform_external_task_owner(owner_user_id, created_at);

UPDATE lesson_job
SET owner_id = '00000000000000000000000000'
WHERE owner_id IS NULL;

CREATE INDEX idx_lesson_job_owner_created ON lesson_job(owner_id, created_at);
ALTER TABLE lesson_job DROP INDEX uq_job_idempotency;
CREATE UNIQUE INDEX uq_job_owner_idempotency ON lesson_job(owner_id, idempotency_key);
