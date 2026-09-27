CREATE TABLE alert_rule_cooldowns (
    rule_id UUID PRIMARY KEY REFERENCES alert_rules(id) ON DELETE CASCADE,
    last_fired_at TIMESTAMPTZ NOT NULL DEFAULT to_timestamp(0)
);

-- Pre-create rows so runtime evaluation can take a row lock before deciding;
-- this avoids the first-event insert race across Alert Service replicas.
INSERT INTO alert_rule_cooldowns (rule_id, last_fired_at)
SELECT id, to_timestamp(0) FROM alert_rules
ON CONFLICT (rule_id) DO NOTHING;
