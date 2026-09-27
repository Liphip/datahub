-- V1__Initial_schema.sql
-- DataTurntable Initial Database Schema

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Tenants (Recursive Hierarchy)
CREATE TABLE tenants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    parent_id UUID REFERENCES tenants(id) ON DELETE SET NULL,
    settings TEXT DEFAULT '{}',
    quota TEXT DEFAULT '{"max_users": 100, "max_pipelines": 50, "max_storage_mb": 10240}',
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_tenant_parent ON tenants(parent_id);

-- Roles (Tenant-scoped)
CREATE TABLE roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    abilities TEXT DEFAULT '[]',
    is_default BOOLEAN DEFAULT FALSE,
    is_system BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(tenant_id, name)
);

CREATE INDEX idx_roles_tenant ON roles(tenant_id);

-- Users
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    role_id UUID REFERENCES roles(id) ON DELETE SET NULL,
    abilities TEXT DEFAULT '[]',
    is_superadmin BOOLEAN DEFAULT FALSE,
    is_active BOOLEAN DEFAULT TRUE,
    last_login_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(tenant_id, email)
);

CREATE INDEX idx_users_tenant ON users(tenant_id);
CREATE INDEX idx_users_email ON users(email);

-- Code Modules (User Python Code)
CREATE TABLE code_modules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    module_type VARCHAR(50) NOT NULL CHECK (module_type IN ('import', 'db_load', 'processing', 'merge', 'export', 'db_save', 'alert')),
    default_version_id UUID,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    UNIQUE(tenant_id, name, module_type)
);

CREATE INDEX idx_code_modules_tenant ON code_modules(tenant_id);
CREATE INDEX idx_code_modules_type ON code_modules(module_type);

-- Module Versions
CREATE TABLE module_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    module_id UUID REFERENCES code_modules(id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    version_tag VARCHAR(100),
    content_fingerprint VARCHAR(128) NOT NULL,
    code TEXT NOT NULL,
    requirements TEXT DEFAULT '',
    config TEXT DEFAULT '{}',
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    UNIQUE(module_id, version)
);

CREATE INDEX idx_module_versions_module ON module_versions(module_id);
CREATE INDEX idx_module_versions_tag ON module_versions(module_id, version_tag);
CREATE UNIQUE INDEX idx_module_versions_fingerprint ON module_versions(module_id, content_fingerprint);
ALTER TABLE code_modules
    ADD CONSTRAINT fk_code_modules_default_version
    FOREIGN KEY (default_version_id) REFERENCES module_versions(id) ON DELETE SET NULL;

-- Pipelines
CREATE TABLE pipelines (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    import_module_version_id UUID REFERENCES module_versions(id) ON DELETE SET NULL,
    processing_module_version_ids UUID ARRAY DEFAULT '{}',
    export_module_version_ids UUID ARRAY DEFAULT '{}',
    kafka_consumer_group VARCHAR(255) UNIQUE,
    is_active BOOLEAN DEFAULT FALSE,
    config TEXT DEFAULT '{}',
    graph_definition TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    created_by UUID REFERENCES users(id) ON DELETE SET NULL
);

CREATE INDEX idx_pipelines_tenant ON pipelines(tenant_id);
CREATE INDEX idx_pipelines_active ON pipelines(is_active);

-- Pipeline Runs
CREATE TABLE pipeline_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pipeline_id UUID REFERENCES pipelines(id) ON DELETE CASCADE,
    status VARCHAR(50) DEFAULT 'pending' CHECK (status IN ('pending', 'running', 'success', 'failed', 'cancelled')),
    trigger_type VARCHAR(50) DEFAULT 'manual' CHECK (trigger_type IN ('manual', 'scheduled', 'event', 'data')),
    started_at TIMESTAMPTZ DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    error_message TEXT,
    retry_of_run_id UUID,
    metrics TEXT DEFAULT '{}',
    UNIQUE(pipeline_id, started_at)
);

CREATE INDEX idx_pipeline_runs_pipeline ON pipeline_runs(pipeline_id, started_at DESC);
CREATE INDEX idx_pipeline_runs_status ON pipeline_runs(status);
CREATE INDEX idx_pipeline_runs_retry_of ON pipeline_runs(retry_of_run_id);

-- Per-node pipeline execution logs
CREATE TABLE pipeline_run_node_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID NOT NULL REFERENCES pipeline_runs(id) ON DELETE CASCADE,
    node_id VARCHAR(255) NOT NULL,
    node_type VARCHAR(64) NOT NULL,
    module_name VARCHAR(255),
    success BOOLEAN NOT NULL DEFAULT FALSE,
    stdout TEXT,
    stderr TEXT,
    error_message TEXT,
    duration_ms BIGINT,
    executed_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_pipeline_run_node_logs_run_id ON pipeline_run_node_logs(run_id);

-- Registered tenant data tables
CREATE TABLE data_table_registry (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    table_name VARCHAR(255) NOT NULL,
    table_type VARCHAR(50) NOT NULL CHECK (table_type IN ('timeseries', 'relational')),
    schema_def JSONB,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(tenant_id, table_name)
);

CREATE INDEX idx_data_table_registry_tenant ON data_table_registry(tenant_id);

-- Uploaded blobs for tenant data workflows
CREATE TABLE data_blobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    content_type VARCHAR(255) DEFAULT 'application/octet-stream',
    size_bytes BIGINT NOT NULL,
    storage_path TEXT NOT NULL,
    uploaded_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_data_blobs_tenant_created_at ON data_blobs(tenant_id, created_at DESC);

-- Alert Channels
CREATE TABLE alert_channels (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(500),
    channel_type VARCHAR(50) NOT NULL CHECK (channel_type IN ('mqtt', 'webhook', 'rest', 'email', 'slack')),
    config TEXT NOT NULL,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    UNIQUE(tenant_id, name)
);

CREATE INDEX idx_alert_channels_tenant ON alert_channels(tenant_id);

-- Alert Rules
CREATE TABLE alert_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    pipeline_id UUID REFERENCES pipelines(id) ON DELETE SET NULL,
    channel_id UUID REFERENCES alert_channels(id) ON DELETE SET NULL,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(500),
    condition TEXT NOT NULL,
    enabled BOOLEAN DEFAULT TRUE,
    cooldown_seconds INTEGER DEFAULT 60,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    UNIQUE(tenant_id, name)
);

CREATE INDEX idx_alert_rules_tenant ON alert_rules(tenant_id);
CREATE INDEX idx_alert_rules_pipeline ON alert_rules(pipeline_id);
CREATE INDEX idx_alert_rules_enabled ON alert_rules(enabled);

-- Alert History
CREATE TABLE alert_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    rule_id UUID REFERENCES alert_rules(id) ON DELETE SET NULL,
    channel_id UUID REFERENCES alert_channels(id) ON DELETE SET NULL,
    severity VARCHAR(50) NOT NULL CHECK (severity IN ('info', 'warning', 'critical', 'error')),
    title VARCHAR(500) NOT NULL,
    message TEXT,
    payload TEXT,
    sent_at TIMESTAMPTZ DEFAULT NOW(),
    status VARCHAR(50) DEFAULT 'pending' CHECK (status IN ('pending', 'sent', 'failed', 'retry'))
);

CREATE INDEX idx_alert_history_tenant ON alert_history(tenant_id);
CREATE INDEX idx_alert_history_rule ON alert_history(rule_id);
CREATE INDEX idx_alert_history_sent ON alert_history(sent_at DESC);

-- Tenant Hierarchy View
CREATE OR REPLACE VIEW tenant_tree AS
WITH RECURSIVE tenant_tree AS (
    SELECT id, name, parent_id, 1 AS level, ARRAY[name]::VARCHAR(255)[] AS path
    FROM tenants
    WHERE parent_id IS NULL
    UNION ALL
    SELECT t.id, t.name, t.parent_id, tt.level + 1, (tt.path || t.name)::VARCHAR(255)[]
    FROM tenants t
    INNER JOIN tenant_tree tt ON t.parent_id = tt.id
)
SELECT * FROM tenant_tree;

-- Default Roles Insert
INSERT INTO roles (id, tenant_id, name, description, abilities, is_default, is_system) VALUES
('00000000-0000-0000-0000-000000000001', NULL, 'Superadmin', 'System super administrator', '["*"]', false, true),
('00000000-0000-0000-0000-000000000002', NULL, 'Viewer', 'Default viewer role', '["module:read", "pipeline:read", "data:read", "alert:read"]', true, true),
('00000000-0000-0000-0000-000000000003', NULL, 'Editor', 'Default editor role', '["module:read", "module:write", "pipeline:read", "pipeline:write", "pipeline:execute", "data:read", "data:write", "alert:read"]', false, true),
('00000000-0000-0000-0000-000000000004', NULL, 'TenantAdmin', 'Tenant administrator role', '["module:read", "module:write", "module:delete", "pipeline:read", "pipeline:write", "pipeline:execute", "data:read", "data:write", "data:export", "alert:read", "alert:configure", "tenant:users", "tenant:manage"]', false, true);

-- Connectors (Protocol Interface Definitions)
CREATE TABLE connectors (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    connector_type VARCHAR(50) NOT NULL CHECK (connector_type IN ('MQTT', 'REST', 'WEBSOCKET', 'UDP', 'FILE', 'CUSTOM')),
    direction VARCHAR(20) NOT NULL CHECK (direction IN ('INPUT', 'OUTPUT', 'BOTH')),
    config TEXT NOT NULL DEFAULT '{}',
    description VARCHAR(500),
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    UNIQUE(tenant_id, name)
);

CREATE INDEX idx_connectors_tenant ON connectors(tenant_id);
CREATE INDEX idx_connectors_type ON connectors(connector_type);
CREATE INDEX idx_connectors_direction ON connectors(direction);

-- Lease-based runtime ownership for horizontally scaled ingest connectors
CREATE TABLE connector_runtime_leases (
    connector_id UUID PRIMARY KEY REFERENCES connectors(id) ON DELETE CASCADE,
    runtime_role VARCHAR(64) NOT NULL,
    owner_instance_id VARCHAR(255) NOT NULL,
    lease_until TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_connector_runtime_leases_owner ON connector_runtime_leases(owner_instance_id);
CREATE INDEX idx_connector_runtime_leases_lease_until ON connector_runtime_leases(lease_until);

-- API Keys (for ingestion and service accounts)
CREATE TABLE api_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    key_hash VARCHAR(255) NOT NULL,
    abilities TEXT DEFAULT '[]',
    expires_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(tenant_id, name)
);

CREATE INDEX idx_api_keys_tenant ON api_keys(tenant_id);
CREATE INDEX idx_api_keys_hash ON api_keys(key_hash);

-- Memberships (separate from users for multi-tenant auth)
CREATE TABLE memberships (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    role_id UUID REFERENCES roles(id) ON DELETE SET NULL,
    abilities TEXT DEFAULT '[]',
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id, tenant_id)
);

CREATE INDEX idx_memberships_tenant ON memberships(tenant_id);
CREATE INDEX idx_memberships_user ON memberships(user_id);

-- Grafana sync state (dashboard and monitoring tenant/user mirroring)
CREATE TABLE grafana_sync_states (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    target_instance VARCHAR(32) NOT NULL CHECK (target_instance IN ('DASHBOARD', 'MONITORING')),
    scope_type VARCHAR(32) NOT NULL CHECK (scope_type IN ('TENANT', 'USER', 'MEMBERSHIP')),
    scope_id UUID NOT NULL,
    tenant_id UUID,
    user_id UUID,
    membership_id UUID,
    desired_state TEXT NOT NULL DEFAULT '{}',
    sync_status VARCHAR(32) NOT NULL DEFAULT 'PENDING' CHECK (sync_status IN ('PENDING', 'SYNCED', 'FAILED')),
    last_synced_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(target_instance, scope_type, scope_id)
);

CREATE INDEX idx_grafana_sync_states_status ON grafana_sync_states(sync_status, target_instance);
CREATE INDEX idx_grafana_sync_states_tenant ON grafana_sync_states(tenant_id);
CREATE INDEX idx_grafana_sync_states_user ON grafana_sync_states(user_id);

-- Readings (Time-series data - TimescaleDB hypertable)
CREATE TABLE readings (
    time TIMESTAMPTZ NOT NULL,
    tenant_id UUID NOT NULL,
    pipeline_id UUID,
    metric TEXT NOT NULL,
    value DOUBLE PRECISION,
    tags JSONB DEFAULT '{}',
    raw JSONB
);

-- Create hypertable if TimescaleDB extension is available
DO $$ 
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'timescaledb') THEN
        PERFORM create_hypertable('readings', 'time', if_not_exists => TRUE);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_readings_tenant_metric_time ON readings(tenant_id, metric, time DESC);
CREATE INDEX IF NOT EXISTS idx_readings_pipeline_time ON readings(pipeline_id, time DESC);
