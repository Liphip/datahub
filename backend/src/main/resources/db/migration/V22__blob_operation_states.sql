-- Make storage failures explicit and recoverable instead of leaving invisible
-- metadata/object divergence after an upload, read, or delete problem.
ALTER TABLE data_blobs
    ADD COLUMN IF NOT EXISTS status VARCHAR(32) NOT NULL DEFAULT 'AVAILABLE',
    ADD COLUMN IF NOT EXISTS last_error TEXT,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

ALTER TABLE data_blobs
    ADD CONSTRAINT chk_data_blobs_status
    CHECK (status IN ('PENDING', 'AVAILABLE', 'MISSING', 'DELETE_FAILED'));

CREATE INDEX IF NOT EXISTS idx_data_blobs_tenant_status ON data_blobs(tenant_id, status);
