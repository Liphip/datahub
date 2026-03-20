# Backup and Recovery

## What to back up

- PostgreSQL/Timescale volumes
- exported module and pipeline definitions
- optional Grafana state (if UI-custom dashboards are used)

## Suggested backup schedule

- production DB: frequent incremental + daily full
- staging DB: daily
- config exports: on every release

## Recovery drill template

1. Restore latest backup to isolated environment.
2. Verify schema and core tables.
3. Validate one pipeline end-to-end.
4. Measure elapsed time and compare with RTO.
5. Record findings and fixes.

## Common pitfalls

- backup success assumed without restore validation
- no documented ownership for restore execution
- missing dependency credentials during restore test
