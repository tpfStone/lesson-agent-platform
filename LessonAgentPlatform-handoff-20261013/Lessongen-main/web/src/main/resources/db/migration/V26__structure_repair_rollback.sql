ALTER TABLE platform_structure_repair_audit ADD COLUMN old_row_snapshot LONGTEXT;
ALTER TABLE platform_structure_repair_audit ADD COLUMN old_row_snapshot_sha256 CHAR(64);
ALTER TABLE platform_structure_repair_audit ADD COLUMN new_row_sha256 CHAR(64);
ALTER TABLE platform_structure_repair_audit ADD COLUMN rolled_back_at TIMESTAMP NULL;
ALTER TABLE platform_structure_repair_audit ADD COLUMN rollback_reason VARCHAR(255);
