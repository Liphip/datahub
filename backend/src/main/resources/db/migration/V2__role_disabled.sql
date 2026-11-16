-- IAM-05: a role can be disabled (its abilities stop applying and it can no longer be assigned)
-- without deleting it; built-in templates cannot be edited or deleted, only cloned or disabled.
ALTER TABLE roles ADD COLUMN is_disabled boolean DEFAULT false NOT NULL;
