CREATE TABLE alert_rule_actions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_id UUID NOT NULL REFERENCES alert_rules(id) ON DELETE CASCADE,
    channel_id UUID NOT NULL REFERENCES alert_channels(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(rule_id, channel_id)
);

CREATE INDEX idx_alert_rule_actions_rule ON alert_rule_actions(rule_id);

-- Preserve existing rule delivery behavior while allowing newly-created rules
-- to carry any number of independently addressable channel actions.
INSERT INTO alert_rule_actions (rule_id, channel_id)
SELECT id, channel_id FROM alert_rules WHERE channel_id IS NOT NULL
ON CONFLICT (rule_id, channel_id) DO NOTHING;
