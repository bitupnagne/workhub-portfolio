CREATE TABLE IF NOT EXISTS app_release_patches (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  release_id BIGINT NOT NULL,
  base_version VARCHAR(40) NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  stored_file_name VARCHAR(255) NOT NULL,
  sha256 CHAR(64) NOT NULL,
  file_size BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_app_release_patches_release_base (release_id, base_version),
  KEY idx_app_release_patches_base (base_version),
  CONSTRAINT fk_app_release_patches_release FOREIGN KEY (release_id) REFERENCES app_releases(id) ON DELETE CASCADE
);

INSERT INTO app_release_patches(release_id, base_version, file_name, stored_file_name, sha256, file_size)
SELECT id, patch_base_version, patch_file_name, patch_stored_file_name, patch_sha256, patch_file_size
FROM app_releases
WHERE patch_base_version IS NOT NULL
  AND patch_file_name IS NOT NULL
  AND patch_stored_file_name IS NOT NULL
  AND patch_sha256 IS NOT NULL
  AND patch_file_size IS NOT NULL
ON DUPLICATE KEY UPDATE
  file_name = VALUES(file_name),
  stored_file_name = VALUES(stored_file_name),
  sha256 = VALUES(sha256),
  file_size = VALUES(file_size);
