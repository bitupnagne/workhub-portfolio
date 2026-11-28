ALTER TABLE users
  ADD COLUMN signature_filename VARCHAR(255) NULL AFTER profile_image_filename;
