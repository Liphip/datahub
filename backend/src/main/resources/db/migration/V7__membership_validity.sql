ALTER TABLE memberships ADD COLUMN valid_from TIMESTAMPTZ;
ALTER TABLE memberships ADD COLUMN valid_until TIMESTAMPTZ;
ALTER TABLE memberships ADD CONSTRAINT membership_valid_interval
    CHECK (valid_from IS NULL OR valid_until IS NULL OR valid_from < valid_until);
