CREATE TABLE IF NOT EXISTS broker_credentials (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(120) NOT NULL,
    username VARCHAR(120) NOT NULL,
    password_encrypted TEXT NOT NULL,
    allowed_topics TEXT NOT NULL DEFAULT '[]',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, name),
    UNIQUE (tenant_id, username)
);

CREATE INDEX IF NOT EXISTS idx_broker_credentials_tenant_id ON broker_credentials(tenant_id);
