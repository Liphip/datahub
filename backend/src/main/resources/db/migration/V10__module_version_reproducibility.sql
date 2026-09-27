ALTER TABLE module_versions ADD COLUMN IF NOT EXISTS artifact_digest VARCHAR(128);
ALTER TABLE module_versions ADD COLUMN IF NOT EXISTS dependency_digest VARCHAR(128);
ALTER TABLE module_versions ADD COLUMN IF NOT EXISTS runtime_image_digest VARCHAR(512);

UPDATE module_versions
SET artifact_digest = content_fingerprint
WHERE artifact_digest IS NULL;
