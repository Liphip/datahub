# Runbooks and Maintenance

## Daily Checks

- review overnight failures
- verify alert delivery health
- inspect top resource consumers
- ensure scheduled jobs completed

## Weekly Checks

- audit tenant/user changes
- review stale module versions
- verify backup job success
- check dashboard signal quality

## Release Runbook

1. Announce change window.
2. Snapshot critical state and exports.
3. Deploy to non-production and validate.
4. Deploy production in phased rollout.
5. Observe reliability and logs for defined soak period.

## Backup and Restore Runbook

- backup DB volumes
- validate integrity and retention
- test restore in isolated environment
- document measured RPO/RTO

## Post-Incident Review

- timeline of events
- root cause and contributing factors
- detection and response gaps
- preventive actions with owners and due dates
