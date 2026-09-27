-- Canonical relational permission model. The text columns remain temporarily
-- as a backwards-compatible representation for older API clients.
CREATE TABLE abilities (
    ability_key VARCHAR(255) PRIMARY KEY
);

CREATE TABLE role_abilities (
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    ability_value VARCHAR(255) NOT NULL REFERENCES abilities(ability_key) ON DELETE RESTRICT,
    PRIMARY KEY (role_id, ability_value)
);

-- V1 is the canonical source for this table. Keep the authorization migration
-- self-contained as well: a clean PostgreSQL migration must establish every
-- relation it references, including on databases whose initial schema predated
-- the memberships addition.
CREATE TABLE IF NOT EXISTS memberships (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    auth_user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    role_id UUID REFERENCES roles(id) ON DELETE SET NULL,
    abilities TEXT DEFAULT '[]',
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(auth_user_id, tenant_id)
);

CREATE TABLE membership_abilities (
    membership_id UUID NOT NULL REFERENCES memberships(id) ON DELETE CASCADE,
    ability_value VARCHAR(255) NOT NULL REFERENCES abilities(ability_key) ON DELETE RESTRICT,
    PRIMARY KEY (membership_id, ability_value)
);

CREATE TABLE user_abilities (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    ability_value VARCHAR(255) NOT NULL REFERENCES abilities(ability_key) ON DELETE RESTRICT,
    PRIMARY KEY (user_id, ability_value)
);

ALTER TABLE users ADD COLUMN IF NOT EXISTS is_platform_admin BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE users SET is_platform_admin = TRUE WHERE is_superadmin = TRUE;

INSERT INTO abilities (ability_key)
SELECT DISTINCT token.value
FROM roles r
CROSS JOIN LATERAL jsonb_array_elements_text(COALESCE(r.abilities, '[]')::jsonb) AS token(value)
WHERE token.value <> ''
UNION
SELECT DISTINCT token.value
FROM memberships m
CROSS JOIN LATERAL jsonb_array_elements_text(COALESCE(m.abilities, '[]')::jsonb) AS token(value)
WHERE token.value <> ''
UNION
SELECT DISTINCT token.value
FROM users u
CROSS JOIN LATERAL jsonb_array_elements_text(COALESCE(u.abilities, '[]')::jsonb) AS token(value)
WHERE token.value <> ''
ON CONFLICT DO NOTHING;

INSERT INTO role_abilities (role_id, ability_value)
SELECT r.id, token.value
FROM roles r
CROSS JOIN LATERAL jsonb_array_elements_text(COALESCE(r.abilities, '[]')::jsonb) AS token(value)
WHERE token.value <> ''
ON CONFLICT DO NOTHING;

INSERT INTO membership_abilities (membership_id, ability_value)
SELECT m.id, token.value
FROM memberships m
CROSS JOIN LATERAL jsonb_array_elements_text(COALESCE(m.abilities, '[]')::jsonb) AS token(value)
WHERE token.value <> ''
ON CONFLICT DO NOTHING;

INSERT INTO user_abilities (user_id, ability_value)
SELECT u.id, token.value
FROM users u
CROSS JOIN LATERAL jsonb_array_elements_text(COALESCE(u.abilities, '[]')::jsonb) AS token(value)
WHERE token.value <> ''
ON CONFLICT DO NOTHING;

CREATE INDEX idx_role_abilities_ability ON role_abilities(ability_value);
CREATE INDEX idx_membership_abilities_ability ON membership_abilities(ability_value);
CREATE INDEX idx_user_abilities_ability ON user_abilities(ability_value);
