# Operations Runbook

## Daily checks

- failed pipeline runs in last 24h
- alert channel delivery status
- service health and uptime
- backlog and retry growth

## Weekly checks

- permission and membership audits
- stale modules/connectors cleanup
- backup job and retention checks
- dashboard/query quality review

## Release checklist

1. create backup snapshot/export
2. deploy to non-production
3. validate runs and alerts
4. deploy production incrementally
5. monitor soak period

## Escalation package template

- tenant and pipeline identifiers
- exact timestamps + timezone
- relevant metric query screenshot
- log snippets with context
- mitigation already attempted
