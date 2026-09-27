#!/usr/bin/env bash
# Backs up the DataTurntable development/reference stack: the identity/pipeline/run-metadata
# database and the pipeline data database. There is no blob directory to archive — unstructured
# payloads are parsed into records and stored in the pipeline data database.
#
# This targets the docker-compose.dev.yml topology (container names dataturntable-postgres,
# dataturntable-pipeline-postgres, dataturntable-control-api). It is NOT wired into any
# scheduler/cron and does not run automatically anywhere — see docs/operations/backup-restore.md
# for the full procedure, verified restore steps, and what remains unproven (Kubernetes/HA
# topology, off-host storage, retention/rotation of the backups this script produces).
set -euo pipefail

BACKUP_ROOT="${BACKUP_ROOT:-./backups}"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DEST="${BACKUP_ROOT}/${TIMESTAMP}"

PRIMARY_CONTAINER="${PRIMARY_DB_CONTAINER:-dataturntable-postgres}"
PRIMARY_DB="${PRIMARY_DB_NAME:-dataturntable}"
PRIMARY_USER="${PRIMARY_DB_USER:-dataturntable}"

PIPELINE_CONTAINER="${PIPELINE_DB_CONTAINER:-dataturntable-pipeline-postgres}"
PIPELINE_DB="${PIPELINE_DB_NAME:-pipeline_data}"
PIPELINE_USER="${PIPELINE_DB_USER:-pipeline_data}"

mkdir -p "${DEST}"

echo "==> Backing up ${PRIMARY_DB} (identity/pipeline/run metadata) from ${PRIMARY_CONTAINER}"
docker exec "${PRIMARY_CONTAINER}" pg_dump -U "${PRIMARY_USER}" -d "${PRIMARY_DB}" --format=custom \
  > "${DEST}/primary-${PRIMARY_DB}.dump"

echo "==> Backing up ${PIPELINE_DB} (pipeline data / time-series) from ${PIPELINE_CONTAINER}"
docker exec "${PIPELINE_CONTAINER}" pg_dump -U "${PIPELINE_USER}" -d "${PIPELINE_DB}" --format=custom \
  > "${DEST}/pipeline-${PIPELINE_DB}.dump"

echo "==> Both databases dumped; no file storage to archive (records only)."

(cd "${DEST}" && sha256sum -- * > CHECKSUMS.sha256)

echo "==> Backup complete: ${DEST}"
echo "    $(ls -la "${DEST}")"
