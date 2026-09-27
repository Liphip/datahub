#!/usr/bin/env bash
# Restores a backup produced by backup.sh into a running docker-compose.dev.yml stack.
#
# Usage: ./restore.sh <backup-dir>
#
# This is destructive to the target databases/blob directory — it is meant for restoring into a
# freshly started, empty stack (disaster recovery, or a scratch environment for a restore drill),
# not for merging into a stack with existing data. See docs/operations/backup-restore.md for the
# full drill procedure including the identity/pipeline/run/data/blob integrity checks to run
# after this script completes.
set -euo pipefail

BACKUP_DIR="${1:?Usage: restore.sh <backup-dir>}"
if [[ ! -d "${BACKUP_DIR}" ]]; then
  echo "ERROR: ${BACKUP_DIR} is not a directory" >&2
  exit 1
fi

PRIMARY_CONTAINER="${PRIMARY_DB_CONTAINER:-dataturntable-postgres}"
PRIMARY_DB="${PRIMARY_DB_NAME:-dataturntable}"
PRIMARY_USER="${PRIMARY_DB_USER:-dataturntable}"

PIPELINE_CONTAINER="${PIPELINE_DB_CONTAINER:-dataturntable-pipeline-postgres}"
PIPELINE_DB="${PIPELINE_DB_NAME:-pipeline_data}"
PIPELINE_USER="${PIPELINE_DB_USER:-pipeline_data}"

BLOB_CONTAINER="${BLOB_CONTAINER:-dataturntable-control-api}"
BLOB_DIR="${BLOB_DIR:-/data/blobs}"

echo "==> Verifying checksums"
(cd "${BACKUP_DIR}" && sha256sum -c CHECKSUMS.sha256)

# NOTE: `pg_restore --clean --if-exists` against a TimescaleDB-enabled database is unsafe — DROP
# statements for `_timescaledb_catalog` objects run interleaved with the extension's own dependent
# state, which aborts enough of the restore (30+ ignored errors) that `public` schema table data
# silently never gets restored even though pg_restore exits 0/warns instead of failing hard. This
# was caught by actually running a restore drill against real TimescaleDB containers, not assumed.
# The safe, TimescaleDB-documented pattern instead: drop and recreate an *empty* database (which
# correctly tears down the extension as a whole via DROP DATABASE, not piecemeal object drops),
# then restore into that clean database with no --clean flag.
echo "==> Recreating ${PRIMARY_DB} (empty) on ${PRIMARY_CONTAINER}"
docker exec "${PRIMARY_CONTAINER}" psql -U "${PRIMARY_USER}" -d postgres \
  -c "DROP DATABASE IF EXISTS \"${PRIMARY_DB}\";" -c "CREATE DATABASE \"${PRIMARY_DB}\" OWNER \"${PRIMARY_USER}\";"
docker exec "${PRIMARY_CONTAINER}" psql -U "${PRIMARY_USER}" -d "${PRIMARY_DB}" \
  -c "CREATE EXTENSION IF NOT EXISTS timescaledb;"

echo "==> Restoring ${PRIMARY_DB} into ${PRIMARY_CONTAINER}"
docker exec -i "${PRIMARY_CONTAINER}" pg_restore -U "${PRIMARY_USER}" -d "${PRIMARY_DB}" \
  --no-owner < "${BACKUP_DIR}/primary-${PRIMARY_DB}.dump"

echo "==> Recreating ${PIPELINE_DB} (empty) on ${PIPELINE_CONTAINER}"
docker exec "${PIPELINE_CONTAINER}" psql -U "${PIPELINE_USER}" -d postgres \
  -c "DROP DATABASE IF EXISTS \"${PIPELINE_DB}\";" -c "CREATE DATABASE \"${PIPELINE_DB}\" OWNER \"${PIPELINE_USER}\";"
docker exec "${PIPELINE_CONTAINER}" psql -U "${PIPELINE_USER}" -d "${PIPELINE_DB}" \
  -c "CREATE EXTENSION IF NOT EXISTS timescaledb;"

echo "==> Restoring ${PIPELINE_DB} into ${PIPELINE_CONTAINER}"
docker exec -i "${PIPELINE_CONTAINER}" pg_restore -U "${PIPELINE_USER}" -d "${PIPELINE_DB}" \
  --no-owner < "${BACKUP_DIR}/pipeline-${PIPELINE_DB}.dump"

if [[ -f "${BACKUP_DIR}/blobs.tar.gz" ]]; then
  echo "==> Restoring blob storage into ${BLOB_CONTAINER}:${BLOB_DIR}"
  docker exec "${BLOB_CONTAINER}" rm -rf "${BLOB_DIR}"
  docker exec -i "${BLOB_CONTAINER}" tar -C "$(dirname "${BLOB_DIR}")" -xzf - < "${BACKUP_DIR}/blobs.tar.gz"
else
  echo "==> No blobs.tar.gz in backup — skipping blob restore"
fi

echo "==> Restore complete. Run the integrity checks in docs/operations/backup-restore.md before"
echo "    trusting this environment (NFR-017 requires a passing documented integrity check, not"
echo "    just a script exit code of 0)."
