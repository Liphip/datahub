-- V5__merge_module_connector_update.sql
-- Add MERGE module type; restrict connector direction to INPUT/OUTPUT only

-- Normalize existing lowercase module_type values to uppercase
UPDATE code_modules SET module_type = UPPER(module_type) WHERE module_type = LOWER(module_type);

-- Update module_type check constraint to include 'merge' and use uppercase (matches Hibernate @Enumerated(EnumType.STRING))
ALTER TABLE code_modules DROP CONSTRAINT IF EXISTS code_modules_module_type_check;
ALTER TABLE code_modules
    ADD CONSTRAINT code_modules_module_type_check
    CHECK (UPPER(module_type) IN ('IMPORT', 'PROCESSING', 'MERGE', 'EXPORT', 'ALERT'));

-- Update connector direction check constraint to remove 'BOTH'
-- First migrate any existing BOTH connectors to INPUT (safer default)
UPDATE connectors SET direction = 'INPUT' WHERE direction = 'BOTH';

ALTER TABLE connectors DROP CONSTRAINT IF EXISTS connectors_direction_check;
ALTER TABLE connectors
    ADD CONSTRAINT connectors_direction_check
    CHECK (direction IN ('INPUT', 'OUTPUT'));

-- Add polling metadata columns to connectors
-- These live in the existing config JSON, but we add explicit indexed columns
-- for the scheduler and trigger routing
ALTER TABLE connectors
    ADD COLUMN IF NOT EXISTS polling_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS polling_interval_seconds INTEGER DEFAULT 60;

-- Index for finding active polling connectors (used by future scheduler)
CREATE INDEX IF NOT EXISTS idx_connectors_polling
    ON connectors(polling_enabled, is_active)
    WHERE polling_enabled = TRUE AND is_active = TRUE;

-- Add a connector_id index on the graph_definition lookup (denormalised lookup table)
-- Maps connector_id → pipeline_id so push triggers can find their pipeline quickly
CREATE TABLE IF NOT EXISTS connector_pipeline_map (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connector_id UUID NOT NULL REFERENCES connectors(id) ON DELETE CASCADE,
    pipeline_id UUID NOT NULL REFERENCES pipelines(id) ON DELETE CASCADE,
    node_id VARCHAR(255) NOT NULL,
    node_type VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(connector_id, pipeline_id, node_id)
);

CREATE INDEX IF NOT EXISTS idx_cpm_connector ON connector_pipeline_map(connector_id);
CREATE INDEX IF NOT EXISTS idx_cpm_pipeline ON connector_pipeline_map(pipeline_id);
