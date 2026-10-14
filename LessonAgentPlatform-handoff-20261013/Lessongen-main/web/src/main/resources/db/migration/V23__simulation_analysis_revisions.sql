CREATE TABLE platform_simulation_analysis (
    simulation_run_id CHAR(26) NOT NULL,
    analysis_revision INT NOT NULL,
    result_sha256 CHAR(64) NOT NULL,
    result_json LONGTEXT NOT NULL,
    summary_markdown LONGTEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (simulation_run_id, analysis_revision),
    UNIQUE (simulation_run_id, result_sha256),
    FOREIGN KEY (simulation_run_id) REFERENCES platform_simulation_run(id)
);
