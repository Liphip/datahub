ALTER TABLE pipeline_runs ADD COLUMN IF NOT EXISTS pipeline_version_id UUID REFERENCES pipeline_versions(id);
CREATE INDEX IF NOT EXISTS idx_pipeline_runs_version ON pipeline_runs(pipeline_version_id);
