-- V6: pipeline node logs, data blob storage, tenant improvements
-- ─────────────────────────────────────────────────────────────────

-- 1. Persistent per-node execution logs for pipeline runs
CREATE TABLE IF NOT EXISTS pipeline_run_node_logs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id          UUID NOT NULL REFERENCES pipeline_runs(id) ON DELETE CASCADE,
    node_id         VARCHAR(255) NOT NULL,
    node_type       VARCHAR(50)  NOT NULL,
    module_name     VARCHAR(255),
    success         BOOLEAN NOT NULL DEFAULT FALSE,
    stdout          TEXT,
    stderr          TEXT,
    error_message   TEXT,
    duration_ms     BIGINT,
    executed_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_prnl_run_id     ON pipeline_run_node_logs(run_id);
CREATE INDEX IF NOT EXISTS idx_prnl_executed   ON pipeline_run_node_logs(executed_at DESC);

-- 2. Data blob storage metadata
--    Actual blob content is stored on the filesystem or object-store path referenced by storage_path.
CREATE TABLE IF NOT EXISTS data_blobs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name            VARCHAR(512) NOT NULL,
    content_type    VARCHAR(255),
    size_bytes      BIGINT,
    storage_path    TEXT,           -- server-side filesystem / object store path
    uploaded_by     UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_data_blobs_tenant ON data_blobs(tenant_id);

-- 3. Data table registry: track user-created tables with type metadata
CREATE TABLE IF NOT EXISTS data_table_registry (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    table_name      VARCHAR(255) NOT NULL,
    table_type      VARCHAR(50)  NOT NULL DEFAULT 'timeseries'
                        CHECK (table_type IN ('timeseries', 'relational')),
    schema_def      JSONB,          -- column definitions for relational tables
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(tenant_id, table_name)
);
CREATE INDEX IF NOT EXISTS idx_dtr_tenant ON data_table_registry(tenant_id);

-- 4. alert_rules: keep existing table but mark deprecated via comment
--    Logic now lives in ALERT module code; rules are no longer evaluated automatically.
COMMENT ON TABLE alert_rules IS 'DEPRECATED: alert logic is now in ALERT pipeline module nodes. This table is kept for backward-compat only and will be removed in a future migration.';

-- 5. connector_pipeline_map: add mqttTopic column so pipeline-level MQTT topic is persisted
ALTER TABLE connector_pipeline_map
    ADD COLUMN IF NOT EXISTS mqtt_topic VARCHAR(512);
