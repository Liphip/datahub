ALTER TABLE pipeline_runs
    ADD COLUMN IF NOT EXISTS retry_of_run_id UUID REFERENCES pipeline_runs(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_pipeline_runs_retry_of ON pipeline_runs(retry_of_run_id);
