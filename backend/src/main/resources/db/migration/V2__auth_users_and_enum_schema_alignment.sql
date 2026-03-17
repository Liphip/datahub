-- Align schema with auth-user + memberships model and enum casing used by JPA

-- memberships.user_id -> memberships.auth_user_id (if needed)
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'memberships' AND column_name = 'user_id'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'memberships' AND column_name = 'auth_user_id'
    ) THEN
        ALTER TABLE memberships RENAME COLUMN user_id TO auth_user_id;
    END IF;
END $$;

-- Users should be globally unique by email for auth-user model
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'users_tenant_id_email_key'
    ) THEN
        ALTER TABLE users DROP CONSTRAINT users_tenant_id_email_key;
    END IF;
EXCEPTION WHEN undefined_table THEN
    NULL;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'users_email_key'
    ) THEN
        ALTER TABLE users ADD CONSTRAINT users_email_key UNIQUE (email);
    END IF;
EXCEPTION WHEN undefined_table THEN
    NULL;
END $$;

-- Normalize enum CHECK constraints to uppercase values used by EnumType.STRING
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'code_modules_module_type_check') THEN
        ALTER TABLE code_modules DROP CONSTRAINT code_modules_module_type_check;
    END IF;
    ALTER TABLE code_modules
        ADD CONSTRAINT code_modules_module_type_check
        CHECK (module_type IN ('IMPORT', 'PROCESSING', 'EXPORT', 'ALERT'));
EXCEPTION WHEN undefined_table THEN
    NULL;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'pipeline_runs_status_check') THEN
        ALTER TABLE pipeline_runs DROP CONSTRAINT pipeline_runs_status_check;
    END IF;
    ALTER TABLE pipeline_runs
        ADD CONSTRAINT pipeline_runs_status_check
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'CANCELLED'));
EXCEPTION WHEN undefined_table THEN
    NULL;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'pipeline_runs_trigger_type_check') THEN
        ALTER TABLE pipeline_runs DROP CONSTRAINT pipeline_runs_trigger_type_check;
    END IF;
    ALTER TABLE pipeline_runs
        ADD CONSTRAINT pipeline_runs_trigger_type_check
        CHECK (trigger_type IN ('MANUAL', 'SCHEDULED', 'EVENT', 'DATA'));
EXCEPTION WHEN undefined_table THEN
    NULL;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'alert_channels_channel_type_check') THEN
        ALTER TABLE alert_channels DROP CONSTRAINT alert_channels_channel_type_check;
    END IF;
    ALTER TABLE alert_channels
        ADD CONSTRAINT alert_channels_channel_type_check
        CHECK (channel_type IN ('MQTT', 'WEBHOOK', 'REST', 'EMAIL', 'SLACK'));
EXCEPTION WHEN undefined_table THEN
    NULL;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'alert_history_severity_check') THEN
        ALTER TABLE alert_history DROP CONSTRAINT alert_history_severity_check;
    END IF;
    ALTER TABLE alert_history
        ADD CONSTRAINT alert_history_severity_check
        CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL', 'ERROR'));
EXCEPTION WHEN undefined_table THEN
    NULL;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'alert_history_status_check') THEN
        ALTER TABLE alert_history DROP CONSTRAINT alert_history_status_check;
    END IF;
    ALTER TABLE alert_history
        ADD CONSTRAINT alert_history_status_check
        CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'RETRY'));
EXCEPTION WHEN undefined_table THEN
    NULL;
END $$;

-- Align baseline tenant role abilities with current API authorization checks
UPDATE roles
SET abilities = '["module:read", "pipeline:read", "data:read", "alert:read", "read:connector", "read:user"]'
WHERE name = 'Viewer' AND tenant_id IS NULL;

UPDATE roles
SET abilities = '["module:read", "module:write", "pipeline:read", "pipeline:write", "pipeline:execute", "data:read", "data:write", "alert:read", "read:connector", "write:connector", "read:user"]'
WHERE name = 'Editor' AND tenant_id IS NULL;

UPDATE roles
SET abilities = '["module:read", "module:write", "module:delete", "pipeline:read", "pipeline:write", "pipeline:execute", "data:read", "data:write", "data:export", "alert:read", "alert:configure", "tenant:users", "tenant:manage", "manage:tenant", "read:user", "write:user", "delete:user", "read:connector", "write:connector", "delete:connector"]'
WHERE name IN ('TenantAdmin', 'Tenant Admin') AND tenant_id IS NULL;
