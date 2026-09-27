CREATE TABLE pipeline_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pipeline_id UUID NOT NULL REFERENCES pipelines(id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    graph_definition TEXT NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1,
    content_fingerprint VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(pipeline_id, version)
);

CREATE INDEX idx_pipeline_versions_pipeline ON pipeline_versions(pipeline_id, version DESC);
