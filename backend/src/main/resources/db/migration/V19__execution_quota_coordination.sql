CREATE TABLE execution_quota_locks (
    scope VARCHAR(64) PRIMARY KEY,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO execution_quota_locks(scope) VALUES ('global');
