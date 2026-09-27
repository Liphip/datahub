CREATE TABLE connector_revisions (
    id UUID PRIMARY KEY,
    connector_id UUID NOT NULL REFERENCES connectors(id) ON DELETE CASCADE,
    revision INTEGER NOT NULL,
    snapshot TEXT NOT NULL,
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_connector_revisions_connector_revision UNIQUE (connector_id, revision)
);
CREATE INDEX idx_connector_revisions_connector_revision ON connector_revisions(connector_id, revision DESC);
