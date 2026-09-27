CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    actor_id UUID REFERENCES users(id),
    tenant_id UUID REFERENCES tenants(id),
    action VARCHAR(128) NOT NULL,
    resource_type VARCHAR(128) NOT NULL,
    resource_id VARCHAR(255),
    outcome VARCHAR(32) NOT NULL,
    request_id VARCHAR(128),
    metadata TEXT
);
CREATE INDEX idx_audit_events_tenant_occurred ON audit_events(tenant_id, occurred_at DESC);
CREATE INDEX idx_audit_events_resource ON audit_events(resource_type, resource_id, occurred_at DESC);
