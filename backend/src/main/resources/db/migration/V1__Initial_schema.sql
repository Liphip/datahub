-- V1__Initial_schema.sql
-- DataTurntable Initial Database Schema

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
    module_type VARCHAR(50) NOT NULL CHECK (module_type IN ('import', 'processing', 'export')),
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
    metrics TEXT DEFAULT '{}',
    UNIQUE(pipeline_id, started_at)
);

CREATE INDEX idx_pipeline_runs_pipeline ON pipeline_runs(pipeline_id, started_at DESC);
CREATE INDEX idx_pipeline_runs_status ON pipeline_runs(status);

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
