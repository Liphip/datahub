-- AUD-03: who and from where, as it was at the time. The actor's name is a snapshot: it stays
-- readable after the user is renamed or deleted. Covered by the record's hash (AUD-05).
ALTER TABLE audit_events ADD COLUMN actor_name varchar(320);
ALTER TABLE audit_events ADD COLUMN actor_ip varchar(64);
ALTER TABLE audit_events ADD COLUMN user_agent varchar(512);
ALTER TABLE audit_events ADD COLUMN source varchar(32);
