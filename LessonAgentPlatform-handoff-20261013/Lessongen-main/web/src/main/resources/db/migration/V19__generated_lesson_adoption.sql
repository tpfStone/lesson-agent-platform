CREATE TABLE platform_generated_lesson (
    job_id CHAR(26) NOT NULL,
    adopted_revision_id VARCHAR(128) NOT NULL,
    owner_id CHAR(26) NOT NULL,
    lesson_id CHAR(26) NOT NULL,
    version_id CHAR(26) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (job_id, adopted_revision_id, owner_id),
    FOREIGN KEY (job_id) REFERENCES lesson_job(id),
    FOREIGN KEY (lesson_id) REFERENCES platform_lesson(id),
    FOREIGN KEY (version_id) REFERENCES platform_lesson_version(id)
);
