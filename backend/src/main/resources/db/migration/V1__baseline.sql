-- DataTurntable baseline schema (PostgreSQL 18).
--
-- Pre-deployment baseline: earlier incremental migrations were squashed into this file.
-- Enum CHECK constraints mirror the Kotlin enums; keep both in sync.

--
--

-- alert_channels (table)

CREATE TABLE alert_channels (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid,
    name varchar(255) NOT NULL,
    description varchar(500),
    channel_type varchar(50) NOT NULL,
    config text NOT NULL,
    is_active boolean DEFAULT true,
    created_at timestamptz DEFAULT now(),
    updated_at timestamptz DEFAULT now(),
    created_by uuid,
    CONSTRAINT alert_channels_channel_type_check CHECK (channel_type IN ('MQTT', 'WEBHOOK', 'REST', 'EMAIL', 'SLACK'))
);

-- alert_history (table)

CREATE TABLE alert_history (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid,
    channel_id uuid,
    pipeline_id uuid,
    dedup_key varchar(512),
    severity varchar(50) NOT NULL,
    title varchar(500) NOT NULL,
    message text,
    payload text,
    sent_at timestamptz DEFAULT now(),
    status varchar(50) DEFAULT 'PENDING',
    CONSTRAINT alert_history_severity_check CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL', 'ERROR')),
    CONSTRAINT alert_history_status_check CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'RETRY'))
);

-- api_keys (table)

CREATE TABLE api_keys (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid,
    user_id uuid,
    name varchar(255) NOT NULL,
    key_hash varchar(255) NOT NULL,
    abilities text DEFAULT '[]',
    expires_at timestamptz,
    last_used_at timestamptz,
    is_active boolean DEFAULT true,
    created_at timestamptz DEFAULT now()
);

-- broker_credentials (table)

CREATE TABLE broker_credentials (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    name varchar(120) NOT NULL,
    username varchar(120) NOT NULL,
    password_encrypted text NOT NULL,
    allowed_topics text DEFAULT '[]' NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL
);

-- code_modules (table)

CREATE TABLE code_modules (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid,
    name varchar(255) NOT NULL,
    description varchar(1000),
    module_type varchar(50) NOT NULL,
    default_version_id uuid,
    is_active boolean DEFAULT true,
    created_at timestamptz DEFAULT now(),
    updated_at timestamptz DEFAULT now(),
    created_by uuid,
    CONSTRAINT code_modules_module_type_check CHECK (module_type IN ('IMPORT', 'DB_LOAD', 'PROCESSING', 'MERGE', 'ALERT', 'EXPORT', 'DB_SAVE'))
);

-- connector_pipeline_map (table)

CREATE TABLE connector_pipeline_map (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    connector_id uuid NOT NULL,
    pipeline_id uuid NOT NULL,
    node_id varchar(255) NOT NULL,
    node_type varchar(50) NOT NULL,
    created_at timestamptz DEFAULT now(),
    mqtt_topic varchar(512)
);

-- connector_runtime_leases (table)

CREATE TABLE connector_runtime_leases (
    connector_id uuid NOT NULL,
    runtime_role varchar(64) NOT NULL,
    owner_instance_id varchar(255) NOT NULL,
    lease_until timestamptz NOT NULL,
    updated_at timestamptz DEFAULT now()
);

-- connectors (table)

CREATE TABLE connectors (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid,
    name varchar(255) NOT NULL,
    connector_type varchar(50) NOT NULL,
    direction varchar(20) NOT NULL,
    config text DEFAULT '{}' NOT NULL,
    description varchar(500),
    is_active boolean DEFAULT true,
    created_at timestamptz DEFAULT now(),
    updated_at timestamptz DEFAULT now(),
    created_by uuid,
    polling_enabled boolean DEFAULT false NOT NULL,
    polling_interval_seconds integer DEFAULT 60,
    CONSTRAINT connectors_connector_type_check CHECK (connector_type IN ('MQTT', 'REST', 'WEBSOCKET', 'UDP', 'FILE', 'CUSTOM')),
    CONSTRAINT connectors_direction_check CHECK (direction IN ('INPUT', 'OUTPUT'))
);

-- data_blobs (table)

CREATE TABLE data_blobs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    name varchar(255) NOT NULL,
    content_type varchar(255) DEFAULT 'application/octet-stream',
    size_bytes bigint NOT NULL,
    storage_path text NOT NULL,
    uploaded_by uuid,
    created_at timestamptz DEFAULT now()
);

-- data_table_registry (table)

CREATE TABLE data_table_registry (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    table_name varchar(255) NOT NULL,
    table_type varchar(50) NOT NULL,
    schema_def jsonb,
    created_at timestamptz DEFAULT now(),
    updated_at timestamptz DEFAULT now(),
    CONSTRAINT data_table_registry_table_type_check CHECK (table_type IN ('timeseries', 'relational'))
);

-- grafana_sync_states (table)

CREATE TABLE grafana_sync_states (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    target_instance varchar(32) NOT NULL,
    scope_type varchar(32) NOT NULL,
    scope_id uuid NOT NULL,
    tenant_id uuid,
    user_id uuid,
    membership_id uuid,
    desired_state text DEFAULT '{}' NOT NULL,
    sync_status varchar(32) DEFAULT 'PENDING' NOT NULL,
    last_synced_at timestamptz,
    last_error text,
    created_at timestamptz DEFAULT now(),
    updated_at timestamptz DEFAULT now(),
    CONSTRAINT grafana_sync_states_scope_type_check CHECK (scope_type IN ('TENANT', 'USER', 'MEMBERSHIP')),
    CONSTRAINT grafana_sync_states_sync_status_check CHECK (sync_status IN ('PENDING', 'SYNCED', 'FAILED')),
    CONSTRAINT grafana_sync_states_target_instance_check CHECK (target_instance IN ('DASHBOARD', 'MONITORING'))
);

-- memberships (table)

CREATE TABLE memberships (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid,
    auth_user_id uuid,
    role_id uuid,
    abilities text DEFAULT '[]',
    created_at timestamptz DEFAULT now()
);

-- module_versions (table)

CREATE TABLE module_versions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    module_id uuid,
    version integer NOT NULL,
    version_tag varchar(100),
    content_fingerprint varchar(128) NOT NULL,
    code text NOT NULL,
    requirements text DEFAULT '',
    config text DEFAULT '{}',
    is_active boolean DEFAULT true,
    created_at timestamptz DEFAULT now(),
    created_by uuid
);

-- pipeline_run_node_logs (table)

CREATE TABLE pipeline_run_node_logs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    run_id uuid NOT NULL,
    node_id varchar(255) NOT NULL,
    node_type varchar(64) NOT NULL,
    module_name varchar(255),
    success boolean DEFAULT false NOT NULL,
    stdout text,
    stderr text,
    error_message text,
    duration_ms bigint,
    executed_at timestamptz DEFAULT now()
);

-- pipeline_runs (table)

CREATE TABLE pipeline_runs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    pipeline_id uuid,
    status varchar(50) DEFAULT 'PENDING',
    trigger_type varchar(50) DEFAULT 'MANUAL',
    started_at timestamptz DEFAULT now(),
    completed_at timestamptz,
    error_message text,
    retry_of_run_id uuid,
    metrics text DEFAULT '{}',
    input_payload text,
    CONSTRAINT pipeline_runs_status_check CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'CANCELLED')),
    CONSTRAINT pipeline_runs_trigger_type_check CHECK (trigger_type IN ('MANUAL', 'SCHEDULED', 'EVENT', 'DATA'))
);

-- pipelines (table)

CREATE TABLE pipelines (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid,
    name varchar(255) NOT NULL,
    description varchar(1000),
    kafka_consumer_group varchar(255),
    is_active boolean DEFAULT false,
    config text DEFAULT '{}',
    graph_definition text,
    created_at timestamptz DEFAULT now(),
    updated_at timestamptz DEFAULT now(),
    created_by uuid
);

-- roles (table)

CREATE TABLE roles (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid,
    name varchar(100) NOT NULL,
    description varchar(500),
    abilities text DEFAULT '[]',
    is_default boolean DEFAULT false,
    is_system boolean DEFAULT false,
    created_at timestamptz DEFAULT now(),
    updated_at timestamptz DEFAULT now()
);

-- tenants (table)

CREATE TABLE tenants (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    name varchar(255) NOT NULL,
    parent_id uuid,
    settings text DEFAULT '{}',
    quota text DEFAULT '{"max_users": 100, "max_pipelines": 50, "max_storage_mb": 10240}',
    created_at timestamptz DEFAULT now(),
    updated_at timestamptz DEFAULT now()
);

-- users (table)

CREATE TABLE users (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    email varchar(255) NOT NULL,
    password_hash varchar(255) NOT NULL,
    first_name varchar(100),
    last_name varchar(100),
    abilities text DEFAULT '[]',
    is_active boolean DEFAULT true,
    last_login_at timestamptz,
    created_at timestamptz DEFAULT now(),
    updated_at timestamptz DEFAULT now()
);

-- alert_channels alert_channels_pkey (constraint)

ALTER TABLE alert_channels
    ADD CONSTRAINT alert_channels_pkey PRIMARY KEY (id);

-- alert_channels alert_channels_tenant_id_name_key (constraint)

ALTER TABLE alert_channels
    ADD CONSTRAINT alert_channels_tenant_id_name_key UNIQUE (tenant_id, name);

-- alert_history alert_history_pkey (constraint)

ALTER TABLE alert_history
    ADD CONSTRAINT alert_history_pkey PRIMARY KEY (id);

-- api_keys api_keys_pkey (constraint)

ALTER TABLE api_keys
    ADD CONSTRAINT api_keys_pkey PRIMARY KEY (id);

-- api_keys api_keys_tenant_id_name_key (constraint)

ALTER TABLE api_keys
    ADD CONSTRAINT api_keys_tenant_id_name_key UNIQUE (tenant_id, name);

-- broker_credentials broker_credentials_pkey (constraint)

ALTER TABLE broker_credentials
    ADD CONSTRAINT broker_credentials_pkey PRIMARY KEY (id);

-- broker_credentials broker_credentials_tenant_id_name_key (constraint)

ALTER TABLE broker_credentials
    ADD CONSTRAINT broker_credentials_tenant_id_name_key UNIQUE (tenant_id, name);

-- broker_credentials broker_credentials_tenant_id_username_key (constraint)

ALTER TABLE broker_credentials
    ADD CONSTRAINT broker_credentials_tenant_id_username_key UNIQUE (tenant_id, username);

-- code_modules code_modules_pkey (constraint)

ALTER TABLE code_modules
    ADD CONSTRAINT code_modules_pkey PRIMARY KEY (id);

-- code_modules code_modules_tenant_id_name_module_type_key (constraint)

ALTER TABLE code_modules
    ADD CONSTRAINT code_modules_tenant_id_name_module_type_key UNIQUE (tenant_id, name, module_type);

-- connector_pipeline_map connector_pipeline_map_connector_id_pipeline_id_node_id_key (constraint)

ALTER TABLE connector_pipeline_map
    ADD CONSTRAINT connector_pipeline_map_connector_id_pipeline_id_node_id_key UNIQUE (connector_id, pipeline_id, node_id);

-- connector_pipeline_map connector_pipeline_map_pkey (constraint)

ALTER TABLE connector_pipeline_map
    ADD CONSTRAINT connector_pipeline_map_pkey PRIMARY KEY (id);

-- connector_runtime_leases connector_runtime_leases_pkey (constraint)

ALTER TABLE connector_runtime_leases
    ADD CONSTRAINT connector_runtime_leases_pkey PRIMARY KEY (connector_id);

-- connectors connectors_pkey (constraint)

ALTER TABLE connectors
    ADD CONSTRAINT connectors_pkey PRIMARY KEY (id);

-- connectors connectors_tenant_id_name_key (constraint)

ALTER TABLE connectors
    ADD CONSTRAINT connectors_tenant_id_name_key UNIQUE (tenant_id, name);

-- data_blobs data_blobs_pkey (constraint)

ALTER TABLE data_blobs
    ADD CONSTRAINT data_blobs_pkey PRIMARY KEY (id);

-- data_table_registry data_table_registry_pkey (constraint)

ALTER TABLE data_table_registry
    ADD CONSTRAINT data_table_registry_pkey PRIMARY KEY (id);

-- data_table_registry data_table_registry_tenant_id_table_name_key (constraint)

ALTER TABLE data_table_registry
    ADD CONSTRAINT data_table_registry_tenant_id_table_name_key UNIQUE (tenant_id, table_name);

-- grafana_sync_states grafana_sync_states_pkey (constraint)

ALTER TABLE grafana_sync_states
    ADD CONSTRAINT grafana_sync_states_pkey PRIMARY KEY (id);

-- grafana_sync_states grafana_sync_states_target_instance_scope_type_scope_id_key (constraint)

ALTER TABLE grafana_sync_states
    ADD CONSTRAINT grafana_sync_states_target_instance_scope_type_scope_id_key UNIQUE (target_instance, scope_type, scope_id);

-- memberships memberships_pkey (constraint)

ALTER TABLE memberships
    ADD CONSTRAINT memberships_pkey PRIMARY KEY (id);

-- memberships memberships_user_id_tenant_id_key (constraint)

ALTER TABLE memberships
    ADD CONSTRAINT memberships_user_id_tenant_id_key UNIQUE (auth_user_id, tenant_id);

-- module_versions module_versions_module_id_version_key (constraint)

ALTER TABLE module_versions
    ADD CONSTRAINT module_versions_module_id_version_key UNIQUE (module_id, version);

-- module_versions module_versions_pkey (constraint)

ALTER TABLE module_versions
    ADD CONSTRAINT module_versions_pkey PRIMARY KEY (id);

-- pipeline_run_node_logs pipeline_run_node_logs_pkey (constraint)

ALTER TABLE pipeline_run_node_logs
    ADD CONSTRAINT pipeline_run_node_logs_pkey PRIMARY KEY (id);

-- pipeline_runs pipeline_runs_pkey (constraint)

ALTER TABLE pipeline_runs
    ADD CONSTRAINT pipeline_runs_pkey PRIMARY KEY (id);

-- pipelines pipelines_kafka_consumer_group_key (constraint)

ALTER TABLE pipelines
    ADD CONSTRAINT pipelines_kafka_consumer_group_key UNIQUE (kafka_consumer_group);

-- pipelines pipelines_pkey (constraint)

ALTER TABLE pipelines
    ADD CONSTRAINT pipelines_pkey PRIMARY KEY (id);

-- roles roles_pkey (constraint)

ALTER TABLE roles
    ADD CONSTRAINT roles_pkey PRIMARY KEY (id);

-- roles roles_tenant_id_name_key (constraint)

ALTER TABLE roles
    ADD CONSTRAINT roles_tenant_id_name_key UNIQUE (tenant_id, name);

-- tenants tenants_pkey (constraint)

ALTER TABLE tenants
    ADD CONSTRAINT tenants_pkey PRIMARY KEY (id);

-- users users_email_key (constraint)

ALTER TABLE users
    ADD CONSTRAINT users_email_key UNIQUE (email);

-- users users_pkey (constraint)

ALTER TABLE users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);

-- idx_alert_channels_tenant (index)

CREATE INDEX idx_alert_channels_tenant ON alert_channels USING btree (tenant_id);

-- idx_alert_history_sent (index)

CREATE INDEX idx_alert_history_sent ON alert_history USING btree (sent_at DESC);

-- idx_alert_history_tenant (index)

CREATE INDEX idx_alert_history_tenant ON alert_history USING btree (tenant_id);

-- idx_api_keys_hash (index)

CREATE INDEX idx_api_keys_hash ON api_keys USING btree (key_hash);

-- idx_api_keys_tenant (index)

CREATE INDEX idx_api_keys_tenant ON api_keys USING btree (tenant_id);

-- idx_broker_credentials_tenant_id (index)

CREATE INDEX idx_broker_credentials_tenant_id ON broker_credentials USING btree (tenant_id);

-- idx_code_modules_tenant (index)

CREATE INDEX idx_code_modules_tenant ON code_modules USING btree (tenant_id);

-- idx_code_modules_type (index)

CREATE INDEX idx_code_modules_type ON code_modules USING btree (module_type);

-- idx_connector_runtime_leases_lease_until (index)

CREATE INDEX idx_connector_runtime_leases_lease_until ON connector_runtime_leases USING btree (lease_until);

-- idx_connector_runtime_leases_owner (index)

CREATE INDEX idx_connector_runtime_leases_owner ON connector_runtime_leases USING btree (owner_instance_id);

-- idx_connectors_direction (index)

CREATE INDEX idx_connectors_direction ON connectors USING btree (direction);

-- idx_connectors_polling (index)

CREATE INDEX idx_connectors_polling ON connectors USING btree (polling_enabled, is_active) WHERE ((polling_enabled = true) AND (is_active = true));

-- idx_connectors_tenant (index)

CREATE INDEX idx_connectors_tenant ON connectors USING btree (tenant_id);

-- idx_connectors_type (index)

CREATE INDEX idx_connectors_type ON connectors USING btree (connector_type);

-- idx_cpm_connector (index)

CREATE INDEX idx_cpm_connector ON connector_pipeline_map USING btree (connector_id);

-- idx_cpm_pipeline (index)

CREATE INDEX idx_cpm_pipeline ON connector_pipeline_map USING btree (pipeline_id);

-- idx_data_blobs_tenant (index)

CREATE INDEX idx_data_blobs_tenant ON data_blobs USING btree (tenant_id);

-- idx_data_blobs_tenant_created_at (index)

CREATE INDEX idx_data_blobs_tenant_created_at ON data_blobs USING btree (tenant_id, created_at DESC);

-- idx_data_table_registry_tenant (index)

CREATE INDEX idx_data_table_registry_tenant ON data_table_registry USING btree (tenant_id);

-- idx_dtr_tenant (index)

CREATE INDEX idx_dtr_tenant ON data_table_registry USING btree (tenant_id);

-- idx_grafana_sync_states_status (index)

CREATE INDEX idx_grafana_sync_states_status ON grafana_sync_states USING btree (sync_status, target_instance);

-- idx_grafana_sync_states_tenant (index)

CREATE INDEX idx_grafana_sync_states_tenant ON grafana_sync_states USING btree (tenant_id);

-- idx_grafana_sync_states_user (index)

CREATE INDEX idx_grafana_sync_states_user ON grafana_sync_states USING btree (user_id);

-- idx_memberships_tenant (index)

CREATE INDEX idx_memberships_tenant ON memberships USING btree (tenant_id);

-- idx_memberships_user (index)

CREATE INDEX idx_memberships_user ON memberships USING btree (auth_user_id);

-- idx_module_versions_fingerprint (index)

CREATE UNIQUE INDEX idx_module_versions_fingerprint ON module_versions USING btree (module_id, content_fingerprint);

-- idx_module_versions_module (index)

CREATE INDEX idx_module_versions_module ON module_versions USING btree (module_id);

-- idx_module_versions_tag (index)

CREATE INDEX idx_module_versions_tag ON module_versions USING btree (module_id, version_tag);

-- idx_pipeline_run_node_logs_run_id (index)

CREATE INDEX idx_pipeline_run_node_logs_run_id ON pipeline_run_node_logs USING btree (run_id);

-- idx_pipeline_runs_pipeline (index)

CREATE INDEX idx_pipeline_runs_pipeline ON pipeline_runs USING btree (pipeline_id, started_at DESC);

-- idx_pipeline_runs_retry_of (index)

CREATE INDEX idx_pipeline_runs_retry_of ON pipeline_runs USING btree (retry_of_run_id);

-- idx_pipeline_runs_status (index)

CREATE INDEX idx_pipeline_runs_status ON pipeline_runs USING btree (status);

-- idx_pipelines_active (index)

CREATE INDEX idx_pipelines_active ON pipelines USING btree (is_active);

-- idx_pipelines_tenant (index)

CREATE INDEX idx_pipelines_tenant ON pipelines USING btree (tenant_id);

-- idx_prnl_executed (index)

CREATE INDEX idx_prnl_executed ON pipeline_run_node_logs USING btree (executed_at DESC);

-- idx_prnl_run_id (index)

CREATE INDEX idx_prnl_run_id ON pipeline_run_node_logs USING btree (run_id);

-- idx_roles_tenant (index)

CREATE INDEX idx_roles_tenant ON roles USING btree (tenant_id);

-- idx_tenant_parent (index)

CREATE INDEX idx_tenant_parent ON tenants USING btree (parent_id);

-- alert_channels alert_channels_created_by_fkey (fk constraint)

ALTER TABLE alert_channels
    ADD CONSTRAINT alert_channels_created_by_fkey FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL;

-- alert_channels alert_channels_tenant_id_fkey (fk constraint)

ALTER TABLE alert_channels
    ADD CONSTRAINT alert_channels_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE;

-- alert_history alert_history_pipeline_id_fkey (fk constraint)

ALTER TABLE alert_history
    ADD CONSTRAINT alert_history_pipeline_id_fkey FOREIGN KEY (pipeline_id) REFERENCES pipelines(id) ON DELETE SET NULL;

-- alert_history idx_alert_history_dedup (index)

CREATE INDEX idx_alert_history_dedup ON alert_history USING btree (channel_id, dedup_key, sent_at);

-- alert_history alert_history_channel_id_fkey (fk constraint)

ALTER TABLE alert_history
    ADD CONSTRAINT alert_history_channel_id_fkey FOREIGN KEY (channel_id) REFERENCES alert_channels(id) ON DELETE SET NULL;

-- alert_history alert_history_tenant_id_fkey (fk constraint)

ALTER TABLE alert_history
    ADD CONSTRAINT alert_history_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE;

-- api_keys api_keys_tenant_id_fkey (fk constraint)

ALTER TABLE api_keys
    ADD CONSTRAINT api_keys_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE;

-- api_keys api_keys_user_id_fkey (fk constraint)

ALTER TABLE api_keys
    ADD CONSTRAINT api_keys_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

-- broker_credentials broker_credentials_tenant_id_fkey (fk constraint)

ALTER TABLE broker_credentials
    ADD CONSTRAINT broker_credentials_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE;

-- code_modules code_modules_created_by_fkey (fk constraint)

ALTER TABLE code_modules
    ADD CONSTRAINT code_modules_created_by_fkey FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL;

-- code_modules code_modules_tenant_id_fkey (fk constraint)

ALTER TABLE code_modules
    ADD CONSTRAINT code_modules_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE;

-- connector_pipeline_map connector_pipeline_map_connector_id_fkey (fk constraint)

ALTER TABLE connector_pipeline_map
    ADD CONSTRAINT connector_pipeline_map_connector_id_fkey FOREIGN KEY (connector_id) REFERENCES connectors(id) ON DELETE CASCADE;

-- connector_pipeline_map connector_pipeline_map_pipeline_id_fkey (fk constraint)

ALTER TABLE connector_pipeline_map
    ADD CONSTRAINT connector_pipeline_map_pipeline_id_fkey FOREIGN KEY (pipeline_id) REFERENCES pipelines(id) ON DELETE CASCADE;

-- connector_runtime_leases connector_runtime_leases_connector_id_fkey (fk constraint)

ALTER TABLE connector_runtime_leases
    ADD CONSTRAINT connector_runtime_leases_connector_id_fkey FOREIGN KEY (connector_id) REFERENCES connectors(id) ON DELETE CASCADE;

-- connectors connectors_created_by_fkey (fk constraint)

ALTER TABLE connectors
    ADD CONSTRAINT connectors_created_by_fkey FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL;

-- connectors connectors_tenant_id_fkey (fk constraint)

ALTER TABLE connectors
    ADD CONSTRAINT connectors_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE;

-- data_blobs data_blobs_tenant_id_fkey (fk constraint)

ALTER TABLE data_blobs
    ADD CONSTRAINT data_blobs_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE;

-- data_blobs data_blobs_uploaded_by_fkey (fk constraint)

ALTER TABLE data_blobs
    ADD CONSTRAINT data_blobs_uploaded_by_fkey FOREIGN KEY (uploaded_by) REFERENCES users(id) ON DELETE SET NULL;

-- data_table_registry data_table_registry_tenant_id_fkey (fk constraint)

ALTER TABLE data_table_registry
    ADD CONSTRAINT data_table_registry_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE;

-- code_modules fk_code_modules_default_version (fk constraint)

ALTER TABLE code_modules
    ADD CONSTRAINT fk_code_modules_default_version FOREIGN KEY (default_version_id) REFERENCES module_versions(id) ON DELETE SET NULL;

-- memberships memberships_role_id_fkey (fk constraint)

ALTER TABLE memberships
    ADD CONSTRAINT memberships_role_id_fkey FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE SET NULL;

-- memberships memberships_tenant_id_fkey (fk constraint)

ALTER TABLE memberships
    ADD CONSTRAINT memberships_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE;

-- memberships memberships_user_id_fkey (fk constraint)

ALTER TABLE memberships
    ADD CONSTRAINT memberships_user_id_fkey FOREIGN KEY (auth_user_id) REFERENCES users(id) ON DELETE CASCADE;

-- module_versions module_versions_created_by_fkey (fk constraint)

ALTER TABLE module_versions
    ADD CONSTRAINT module_versions_created_by_fkey FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL;

-- module_versions module_versions_module_id_fkey (fk constraint)

ALTER TABLE module_versions
    ADD CONSTRAINT module_versions_module_id_fkey FOREIGN KEY (module_id) REFERENCES code_modules(id) ON DELETE CASCADE;

-- pipeline_run_node_logs pipeline_run_node_logs_run_id_fkey (fk constraint)

ALTER TABLE pipeline_run_node_logs
    ADD CONSTRAINT pipeline_run_node_logs_run_id_fkey FOREIGN KEY (run_id) REFERENCES pipeline_runs(id) ON DELETE CASCADE;

-- pipeline_runs pipeline_runs_pipeline_id_fkey (fk constraint)

ALTER TABLE pipeline_runs
    ADD CONSTRAINT pipeline_runs_pipeline_id_fkey FOREIGN KEY (pipeline_id) REFERENCES pipelines(id) ON DELETE CASCADE;

-- pipelines pipelines_created_by_fkey (fk constraint)

ALTER TABLE pipelines
    ADD CONSTRAINT pipelines_created_by_fkey FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL;

-- pipelines pipelines_tenant_id_fkey (fk constraint)

ALTER TABLE pipelines
    ADD CONSTRAINT pipelines_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE;

-- roles roles_tenant_id_fkey (fk constraint)

ALTER TABLE roles
    ADD CONSTRAINT roles_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE;

-- tenants tenants_parent_id_fkey (fk constraint)

ALTER TABLE tenants
    ADD CONSTRAINT tenants_parent_id_fkey FOREIGN KEY (parent_id) REFERENCES tenants(id) ON DELETE SET NULL;

--
--

-- alert_rule_state (table): state of built-in alert rules, shared by all executor replicas

CREATE TABLE alert_rule_state (
    pipeline_id uuid NOT NULL REFERENCES pipelines(id) ON DELETE CASCADE,
    node_id varchar(255) NOT NULL,
    series_key varchar(512) NOT NULL,
    last_value double precision,
    last_ts timestamptz,
    window_samples text,
    last_seen timestamptz,
    absence_alerted boolean DEFAULT false NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    PRIMARY KEY (pipeline_id, node_id, series_key)
);

-- Global role templates

INSERT INTO roles (id, tenant_id, name, description, abilities, is_default, is_system) VALUES
('00000000-0000-0000-0000-000000000001', NULL, 'Superadmin', 'Full access to every tenant', '["*"]', FALSE, TRUE),
('00000000-0000-0000-0000-000000000002', NULL, 'Viewer', 'Read-only access', '["module:read", "pipeline:read", "data:read", "alert:read", "connector:read", "user:read"]', TRUE, TRUE),
('00000000-0000-0000-0000-000000000003', NULL, 'Editor', 'Build and run pipelines and modules', '["module:read", "module:write", "pipeline:read", "pipeline:write", "pipeline:execute", "data:read", "data:write", "alert:read", "connector:read", "connector:write", "user:read"]', FALSE, TRUE),
('00000000-0000-0000-0000-000000000004', NULL, 'Tenant Admin', 'Tenant administrator', '["module:read", "module:write", "module:delete", "pipeline:read", "pipeline:write", "pipeline:execute", "data:read", "data:write", "data:export", "alert:read", "alert:configure", "connector:read", "connector:write", "connector:delete", "user:read", "user:write", "user:delete", "tenant:manage"]', FALSE, TRUE);
