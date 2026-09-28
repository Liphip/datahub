# Backup and restore (NFR-R11, OPS-03)

> **Status: Implemented for the single-node Docker Compose reference topology
> (`docker/docker-compose.dev.yml`) and verified by an actual restore drill during this session.
> NOT implemented, tested, or scheduled for the Kubernetes HA topology
> (`docker/k8s`, `docker/k8s-ha`)** — see
> [Known gaps](#known-gaps) below. The status is kept in the
> [requirements specification, Appendix A](../specification/requirements-specification.md#appendix-a-traceability-and-implementation-status)
> (NFR-R11: Partial); treat the Kubernetes backup path as planned until a real restore drill runs
> against a live cluster.

## What gets backed up

DataTurntable's durable state lives in two places:

1. **Primary database** (`dataturntable`, container `dataturntable-postgres` in dev) — identity,
   tenants, memberships, roles, pipelines, pipeline versions, runs, run node logs, connectors,
   connector runtime leases, alert history/channels, module versions, API keys, secrets,
   metadata objects, audit events, data table registry, Flyway history.
2. **Pipeline data database** (`pipeline_data`, container `dataturntable-pipeline-postgres` in
   dev) — ingested time-series `readings` and any pipeline-created relational tables (Data
   Service).

That is the whole of it. There is no third location and no file tree to copy: unstructured
payloads are not stored anywhere in the platform (STR-08), so a pulled file, a mail with
attachments or an exported binary is reduced to records before it is persisted, and the source
item stays where the remote system put it.

The monitoring/dashboard Postgres instances (Grafana's own state) and Kafka are not covered —
they hold operational state that can be rebuilt, not durable tenant-owned state. Records that are
still in Kafka, in an ingest spool, or **queued in the bundled MQTT broker's own persistent store**
(`mosquitto-data`, the Docker volume behind `docker/mosquitto/mosquitto.conf`'s
`persistence true`/`queue_qos0_messages true` — messages the broker has acknowledged for a
platform connector that is currently disconnected) when a backup is taken are not in the dumps;
drain ingestion first if a backup must contain everything acknowledged so far.

## Procedure

Scripts: [`scripts/backup/backup.sh`](../../scripts/backup/backup.sh) and
[`scripts/backup/restore.sh`](../../scripts/backup/restore.sh). Both target the container names
from `docker-compose.dev.yml` by default and accept overrides via environment variables
(`PRIMARY_DB_CONTAINER`, `PIPELINE_DB_CONTAINER`, etc. — see the scripts' own headers).

### Backup

```bash
./scripts/backup/backup.sh
# writes ./backups/<UTC timestamp>/{primary-dataturntable.dump, pipeline-pipeline_data.dump,
#                                    CHECKSUMS.sha256}
```

Each database is dumped with `pg_dump --format=custom` (the standard PostgreSQL
custom/compressed format, restorable with `pg_restore`). A `CHECKSUMS.sha256` file covers every
artifact in the backup directory.

### Restore

```bash
./scripts/backup/restore.sh ./backups/<UTC timestamp>
```

This is destructive to the target — it verifies checksums, then **drops and recreates** both
target databases before restoring into them. Only run it against an environment you intend to
fully overwrite (a fresh/empty stack, or a disaster recovery target), never against a live
environment holding data you want to keep.

**Why drop-and-recreate instead of `pg_restore --clean`:** this was not a design guess — it was
discovered by actually running the drill below. TimescaleDB installs its own catalog objects
(`_timescaledb_catalog.*`) inside the database, and `pg_restore --clean --if-exists` issues `DROP`
statements for those objects piecemeal, in TOC order, while the extension is still active. That
leaves the extension's internal state inconsistent partway through the restore, which silently
aborts significant portions of the restore — including, in the actual drill run, all of the
`public` schema table data — while `pg_restore` still exits with only a "warning: errors ignored
on restore: 30" rather than a hard failure. The database was left in a state where table
structures existed but were empty, and this would have gone unnoticed without an explicit
row-level integrity check after restore. Dropping and recreating the whole database first (which
correctly tears down the extension as a single unit via `DROP DATABASE`, not piecemeal catalog
drops), then restoring into a clean database with no `--clean` flag, restores correctly.

### Restore drill actually run this session

Using a real `docker-compose.dev.yml` `postgresql` + `pipeline-postgresql` pair (TimescaleDB
16/`timescale/timescaledb`):

1. Seeded a marker row in each database.
2. Ran `backup.sh` — produced real `.dump` artifacts with matching checksums.
3. Deleted the marker rows to simulate loss.
4. First restore attempt used `pg_restore --clean --if-exists` and **failed silently** as
   described above — the checksum step passed, the script exited 0, but the marker rows were
   *not* restored. This is exactly the kind of gap NFR-017 exists to catch, and is the reason this
   procedure insists on an explicit post-restore integrity check rather than trusting script exit
   codes.
5. Fixed `restore.sh` to drop/recreate the target database before restoring (see above), reran the
   full drill from a clean seed, and confirmed the marker row in both databases came back
   correctly.

## Integrity checks to run after every restore

NFR-017's acceptance criterion is "passes documented integrity checks," not "the script exited
0." At minimum, after restoring:

```sql
-- Primary database
SELECT count(*) FROM tenants;
SELECT count(*) FROM users;
SELECT count(*) FROM pipelines;
SELECT count(*) FROM pipeline_runs;
SELECT max(version) FROM flyway_schema_history;  -- confirms every migration replayed

-- Pipeline data database
SELECT count(*) FROM readings;
```

Compare each count against a count taken immediately before backup (or against what's expected
for the specific drill/incident). There is no file-tree check to run: because unstructured
payloads are not stored (STR-08), these two databases are the entirety of the durable state.

A restore is only considered proven for a given environment once these checks have actually been
run and compared against pre-backup values — not merely once the scripts complete without error.

## Known gaps

- **Kubernetes topology is unverified.** `docker/k8s` and `docker/k8s-ha` have no backup/restore
  automation (no CronJob, no Velero/volume-snapshot configuration) and this procedure has never
  been run against them. The container-name-based approach here (`docker exec <name> pg_dump ...`)
  does not translate directly to Kubernetes (`kubectl exec` against a Pod, plus a strategy for
  PersistentVolume snapshots for the databases) and would need its own drill before being trusted.
- **No automated schedule.** These scripts are not invoked by any cron/CronJob/CI schedule — they
  are a manual/on-call procedure only. Scheduling and off-host/off-cluster storage of the backup
  artifacts (this procedure writes to local disk) are both required before this could be called a
  real production backup strategy, not just a documented and verified manual recovery path.
- **Point-in-time recovery is out of scope.** This procedure produces periodic full logical dumps,
  not continuous WAL archiving — recovery granularity is "as of the last backup run," not to an
  arbitrary point in time.
- **The MQTT broker's own durable queue is not backed up.** `mosquitto-data` (the volume behind
  `persistence true`/`queue_qos0_messages true` in `docker/mosquitto/mosquitto.conf`) can hold
  messages the broker already acknowledged to a sender for a platform connector that is currently
  disconnected. `backup.sh` only dumps the two PostgreSQL databases, so a restore from those dumps
  alone can lose messages that were durably queued at the broker but never reached Kafka. A
  total-loss restore should either also restore that Docker volume, or the operator should accept
  that acknowledged-but-undelivered MQTT messages are not covered — this procedure does not yet
  do either automatically.
