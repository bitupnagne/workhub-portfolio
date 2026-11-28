ALTER TABLE users
    ADD COLUMN system_account BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE users
SET system_account = TRUE
WHERE id = 99
  AND username = 'demo.admin';
