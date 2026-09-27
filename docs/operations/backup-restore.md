# Backup and restore (NFR-017)

> **Status: Implemented for the single-node Docker Compose reference topology
> (`docker/docker-compose.dev.yml`) and verified by an actual restore drill during this session.
> NOT implemented, tested, or scheduled for the Kubernetes HA topology
> (`docker/k8s`, `docker/k8s-ha`) or for FR-022's target S3/MinIO blob storage** — see
> [Known gaps](#known-gaps) below. Per the support-matrix classification in
> [support-matrix.md](support-matrix.md), treat the Kubernetes backup path as **Planned**, not
> Implemented, until a real restore drill runs against a live cluster.

## What gets backed up

DataTurntable's durable state lives in three places:

1. **Primary database** (`dataturntable`, container `dataturntable-postgres` in dev) — identity,
   tenants, memberships, roles, pipelines, pipeline versions, runs, run node logs, connectors,
   connector runtime leases, alert rules/history/channels, module versions, API keys, secrets
   metadata, data table registry, `data_blobs` metadata rows, Flyway history.
2. **Pipeline data database** (`pipeline_data`, container `dataturntable-pipeline-postgres` in
   dev) — ingested time-series `readings` and any pipeline-created relational tables (Data
   Service).
3. **Blob storage directory** (`dataturntable.data.blob-dir`, default `/data/blobs`, currently
   served from local container filesystem — see [Known gaps](#known-gaps)) — uploaded/exported
   blob payloads referenced by `data_blobs` rows in the primary database.

The monitoring/dashboard Postgres instances (Grafana's own state) and Kafka/Redis are not covered
— they hold operational/cache state that is reasonable to lose and rebuild, not durable
tenant-owned state, and are out of scope for this procedure per the spec's own framing of NFR-017
("representative identity, pipeline/version/run, data, and blob state").

## Procedure

Scripts: [`scripts/backup/backup.sh`](../../scripts/backup/backup.sh) and
[`scripts/backup/restore.sh`](../../scripts/backup/restore.sh). Both target the container names
from `docker-compose.dev.yml` by default and accept overrides via environment variables
(`PRIMARY_DB_CONTAINER`, `PIPELINE_DB_CONTAINER`, `BLOB_CONTAINER`, `BLOB_DIR`, etc. — see the
scripts' own headers).

### Backup

```bash
./scripts/backup/backup.sh
# writes ./backups/<UTC timestamp>/{primary-dataturntable.dump, pipeline-pipeline_data.dump,
#                                    blobs.tar.gz, CHECKSUMS.sha256}
```

Each database is dumped with `pg_dump --format=custom` (the standard PostgreSQL
custom/compressed format, restorable with `pg_restore`), and the blob directory is archived with
`tar czf` executed inside the container so no host bind-mount is required. A `CHECKSUMS.sha256`
file covers every artifact in the backup directory.

### Restore

```bash
./scripts/backup/restore.sh ./backups/<UTC timestamp>
```

This is destructive to the target — it verifies checksums, then **drops and recreates** both
target databases before restoring into them, and replaces the blob directory wholesale. Only run
it against an environment you intend to fully overwrite (a fresh/empty stack, or a disaster
recovery target), never against a live environment holding data you want to keep.

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
16/`timescale/timescaledb`) plus a throwaway Alpine container standing in for the blob-storage
filesystem:

1. Seeded a marker row in each database and a marker file in the blob directory.
2. Ran `backup.sh` — produced real `.dump`/`.tar.gz` artifacts with matching checksums.
3. Deleted the marker rows/file to simulate loss.
4. First restore attempt used `pg_restore --clean --if-exists` and **failed silently** as
   described above — the checksum step passed, the script exited 0, but the marker rows were
   *not* restored. This is exactly the kind of gap NFR-017 exists to catch, and is the reason this
   procedure insists on an explicit post-restore integrity check rather than trusting script exit
   codes.
5. Fixed `restore.sh` to drop/recreate the target database before restoring (see above), reran the
   full drill from a clean seed, and confirmed the marker row in both databases and the marker
   file in blob storage all came back correctly, with the file's content byte-for-byte intact.

## Integrity checks to run after every restore

NFR-017's acceptance criterion is "passes documented integrity checks," not "the script exited
0." At minimum, after restoring:

```sql
-- Primary database
SELECT count(*) FROM tenants;
SELECT count(*) FROM users;
SELECT count(*) FROM pipelines;
SELECT count(*) FROM pipeline_runs;
SELECT count(*) FROM data_blobs;
SELECT max(version) FROM flyway_schema_history;  -- confirms every migration replayed

-- Pipeline data database
SELECT count(*) FROM readings;
```

Compare each count against a count taken immediately before backup (or against what's expected
for the specific drill/incident). For blob storage, spot-check that a sample of files referenced
by `data_blobs` rows with `status = 'AVAILABLE'` actually exist on disk with matching size, e.g.:

```bash
docker exec <blob-container> find <blob-dir> -type f | wc -l
```

A restore is only considered proven for a given environment once these checks have actually been
run and compared against pre-backup values — not merely once the scripts complete without error.

## Known gaps

- **Kubernetes topology is unverified.** `docker/k8s` and `docker/k8s-ha` have no backup/restore
  automation (no CronJob, no Velero/volume-snapshot configuration) and this procedure has never
  been run against them. The container-name-based approach here (`docker exec <name> pg_dump ...`)
  does not translate directly to Kubernetes (`kubectl exec` against a Pod, plus a strategy for
  PersistentVolume snapshots for blob storage) and would need its own drill before being trusted.
- **Blob storage is still a local filesystem path (`/data/blobs`), not S3/MinIO.** FR-022 (S3-
  compatible blob storage) has not been implemented yet — see the status tracker. Once blobs move
  to S3/MinIO, the `tar`-based blob backup step in `backup.sh` should be replaced with the object
  store's own backup/replication mechanism (e.g. MinIO's `mc mirror`, or S3 cross-region
  replication + versioning), not adapted to tar over an object-store mount.
- **No automated schedule.** These scripts are not invoked by any cron/CronJob/CI schedule — they
  are a manual/on-call procedure only. Scheduling and off-host/off-cluster storage of the backup
  artifacts (this procedure writes to local disk) are both required before this could be called a
  real production backup strategy, not just a documented and verified manual recovery path.
- **Point-in-time recovery is out of scope.** This procedure produces periodic full logical dumps,
  not continuous WAL archiving — recovery granularity is "as of the last backup run," not to an
  arbitrary point in time.
