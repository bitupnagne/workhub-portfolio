ALTER TABLE app_releases
    ADD COLUMN IF NOT EXISTS patch_base_version VARCHAR(40) NULL AFTER mandatory,
    ADD COLUMN IF NOT EXISTS patch_file_name VARCHAR(255) NULL AFTER patch_base_version,
    ADD COLUMN IF NOT EXISTS patch_stored_file_name VARCHAR(255) NULL AFTER patch_file_name,
    ADD COLUMN IF NOT EXISTS patch_sha256 CHAR(64) NULL AFTER patch_stored_file_name,
    ADD COLUMN IF NOT EXISTS patch_file_size BIGINT NULL AFTER patch_sha256;
