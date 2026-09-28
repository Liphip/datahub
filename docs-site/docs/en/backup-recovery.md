# Backup and Recovery

## What is backed up

Everything durable lives in two PostgreSQL databases: the metadata database (tenants, users,
pipelines, connectors, secrets, metadata objects, audit events) and the pipeline data database
(readings and other tables pipelines write to). Nothing else needs a separate backup: there is no
file store (STR-08), and Kafka/Grafana state can be rebuilt.

Scripts: `scripts/backup/backup.sh` and `scripts/backup/restore.sh` (two `pg_dump`/`pg_restore`
calls). Full procedure, container names and environment overrides: see the developer section's
*Backup and Restore* page.

## Recovery drill

1. Restore both dumps into a fresh, isolated instance.
2. Confirm Flyway's schema history and a few key tables (`connectors`, `pipelines`, `tenants`).
3. Log in and run one pipeline end to end.
4. Measure the restore time against your recovery time objective and record it.

## Status

Verified for the single-node Docker Compose topology by an actual restore drill. **Not yet**
verified on Kubernetes (single-instance or the HA overlay) — see the requirements specification,
Appendix A, NFR-R11.
