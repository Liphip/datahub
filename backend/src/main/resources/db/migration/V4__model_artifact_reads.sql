-- MOD-16: which model artifact version a run actually used (load_model_fn returned it), as
-- opposed to model_artifacts.created_by_run_id, which records the run that saved a version.
CREATE TABLE model_artifact_reads (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    module_id uuid NOT NULL REFERENCES code_modules(id) ON DELETE CASCADE,
    name varchar(128) NOT NULL,
    version integer NOT NULL,
    run_id varchar(64) NOT NULL,
    read_at timestamptz DEFAULT now() NOT NULL,
    CONSTRAINT uq_model_artifact_reads UNIQUE (run_id, module_id, name, version)
);
CREATE INDEX idx_model_artifact_reads_artifact ON model_artifact_reads (module_id, name, version);
