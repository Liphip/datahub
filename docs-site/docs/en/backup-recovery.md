# Backup and Recovery

## Critical assets

- PostgreSQL/Timescale data
- module and pipeline exports
- optional Grafana state

## Backup policy example

- production: frequent incrementals + daily full
- staging: daily full
- config exports: each release

## Recovery drill

1. restore to isolated environment
2. validate schema and key tables
3. execute one pipeline end-to-end
4. measure restore time vs RTO
5. document results
