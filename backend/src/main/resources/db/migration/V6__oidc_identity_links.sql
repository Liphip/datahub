CREATE TABLE external_identities (
    id UUID PRIMARY KEY,
    issuer VARCHAR(512) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (issuer, subject)
);
CREATE TABLE oidc_login_tickets (
    code_hash VARCHAR(64) PRIMARY KEY,
    browser_hash VARCHAR(64) NOT NULL,
    identity_id UUID NOT NULL REFERENCES external_identities(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_oidc_login_tickets_expiry ON oidc_login_tickets(expires_at);
