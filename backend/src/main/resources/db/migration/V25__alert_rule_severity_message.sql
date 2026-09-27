-- Severity and message were previously embedded inside the alert_rules.condition JSON blob and
-- parsed out only at dispatch time. Promote them to independent, queryable/patchable columns so
-- the rule editor and dispatcher no longer depend on JSON-key conventions for core rule fields.
ALTER TABLE alert_rules
    ADD COLUMN severity VARCHAR(20) NOT NULL DEFAULT 'WARNING',
    ADD COLUMN message TEXT;

-- Backfill from any existing condition JSON so previously configured rules keep firing with the
-- same severity/message they already had.
UPDATE alert_rules
SET severity = UPPER(condition::jsonb ->> 'severity')
WHERE condition IS NOT NULL
  AND condition::jsonb ->> 'severity' IS NOT NULL
  AND UPPER(condition::jsonb ->> 'severity') IN ('INFO', 'WARNING', 'CRITICAL', 'ERROR');

UPDATE alert_rules
SET message = condition::jsonb ->> 'message'
WHERE condition IS NOT NULL
  AND condition::jsonb ->> 'message' IS NOT NULL;
