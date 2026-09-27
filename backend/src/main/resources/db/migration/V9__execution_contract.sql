CREATE TABLE executions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    module_version_id UUID NOT NULL REFERENCES module_versions(id),
    status VARCHAR(32) NOT NULL,
    backend VARCHAR(64) NOT NULL,
    runtime_image VARCHAR(512) NOT NULL,
    runtime_image_digest VARCHAR(512),
    correlation_id VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_executions_tenant_created ON executions(tenant_id, created_at DESC);
