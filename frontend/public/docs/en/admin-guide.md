# Administrator Guide

This guide focuses on secure, maintainable platform operations.

## 1. Platform responsibilities

As administrator, you own:

- tenant hierarchy and access governance
- release safety and rollback readiness
- observability baseline (metrics + logs + alerts)
- backup/restore quality and evidence

## 2. Access governance runbook

### New user onboarding

1. Create user account.
2. Assign minimum required role in correct tenant.
3. Validate effective access with test login.
4. Document ownership and approval ticket.

### Role review cadence

- weekly: high-privilege accounts
- monthly: all tenant memberships
- immediate: after incidents or offboarding

## 3. Change management

Before production change:

- verify rollback version exists
- verify monitoring dashboards are healthy
- announce maintenance window
- define success/failure thresholds

After production change:

- monitor soak period
- compare p95 latency and failure rate
- close change only after stability confirmation

## 4. Monitoring baseline

Minimum required signals:

- service uptime (`up`)
- backend request rate and p95 latency
- host CPU/memory
- container CPU/memory pressure
- log error burst detection

## 5. Incident command checklist

1. Declare incident severity and owner.
2. Freeze non-critical changes.
3. Confirm blast radius (tenant/service scope).
4. Apply mitigation (rollback/disable rule/reroute).
5. Gather evidence for RCA.
6. Publish post-incident actions.

## 6. Backup and recovery policy

- define RPO and RTO per environment
- automate backups for stateful services
- test restore at least monthly
- store restore evidence and elapsed time

## 7. Swarm and Kubernetes guidance

### Swarm

- run app stack and monitoring stack separately if needed
- use service/task discovery for metrics
- use global promtail/node-exporter for node visibility

### Kubernetes

- isolate by namespace
- use HPA for stateless services
- scrape pod/service metrics via discovery
- use DaemonSets for node-level logs/metrics

## 8. Security minimums

- no plaintext production secrets in git
- regular secret rotation schedule
- dedicated admin accounts (no shared credentials)
- strict exposure policy for admin endpoints

## 9. Admin KPIs (recommended)

- deployment change failure rate
- mean time to detect / recover
- percent of users with least-privilege compliant roles
- backup restore success rate
- alert fatigue index (non-actionable alert ratio)
