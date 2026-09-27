CREATE TABLE connector_revisions (
    id UUID PRIMARY KEY,
    connector_id UUID NOT NULL,
    revision INTEGER NOT NULL,
    snapshot TEXT NOT NULL,
    created_by UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_connector_revisions_connector_revision UNIQUE (connector_id, revision)
);
CREATE INDEX idx_connector_revisions_connector_revision ON connector_revisions(connector_id, revision DESC);
