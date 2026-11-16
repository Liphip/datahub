-- AUD-05: audit records form a hash chain (each record's hash covers its content and the hash of
-- the record before it) and cannot be updated. Records written before this migration have no
-- sequence number and are reported as "unchained" by the verification, not as tampered.
ALTER TABLE audit_events ADD COLUMN seq bigint;
ALTER TABLE audit_events ADD COLUMN prev_hash varchar(64);
ALTER TABLE audit_events ADD COLUMN hash varchar(64);
CREATE UNIQUE INDEX idx_audit_events_seq ON audit_events (seq);

-- One row, locked while a record is appended, so the chain has a single well-defined order.
CREATE TABLE audit_chain_head (
    id smallint PRIMARY KEY,
    last_seq bigint NOT NULL,
    last_hash varchar(64) NOT NULL
);
INSERT INTO audit_chain_head (id, last_seq, last_hash) VALUES (1, 0, '');

-- Append-only: the application never updates an audit record, and the database refuses it.
-- DELETE stays possible for the retention job (AUD-09); the chain verification reports a gap.
CREATE FUNCTION audit_events_refuse_update() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER audit_events_no_update BEFORE UPDATE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION audit_events_refuse_update();

-- AUD-06: notes on an audit record, or standalone on a component's timeline. Immutable.
CREATE TABLE audit_notes (
    id uuid PRIMARY KEY,
    event_id uuid REFERENCES audit_events(id) ON DELETE SET NULL,
    tenant_id uuid REFERENCES tenants(id) ON DELETE CASCADE,
    resource_type varchar(128),
    resource_id varchar(255),
    author_id uuid REFERENCES users(id),
    note text NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL
);
CREATE INDEX idx_audit_notes_event ON audit_notes (event_id);
CREATE INDEX idx_audit_notes_resource ON audit_notes (tenant_id, resource_type, resource_id, created_at DESC);
CREATE FUNCTION audit_notes_refuse_change() RETURNS trigger AS $$
BEGIN
    -- The only change allowed is the reference being cleared when retention removes the record.
    IF NEW.note IS DISTINCT FROM OLD.note OR NEW.author_id IS DISTINCT FROM OLD.author_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.resource_type IS DISTINCT FROM OLD.resource_type OR NEW.resource_id IS DISTINCT FROM OLD.resource_id THEN
        RAISE EXCEPTION 'audit_notes are immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER audit_notes_no_change BEFORE UPDATE ON audit_notes
    FOR EACH ROW EXECUTE FUNCTION audit_notes_refuse_change();
