CREATE TABLE secrets (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT uq_secrets_tenant_name UNIQUE (tenant_id, name)
);

CREATE TABLE secret_versions (
    id UUID PRIMARY KEY,
    secret_id UUID NOT NULL REFERENCES secrets(id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    value_encrypted TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_secret_versions_secret_version UNIQUE (secret_id, version)
);

CREATE INDEX idx_secrets_tenant ON secrets(tenant_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_secret_versions_latest ON secret_versions(secret_id, version DESC);
