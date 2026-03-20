# Administrator Guide

## Responsibilities

- maintain tenant and role governance
- ensure safe releases and rollback readiness
- keep observability healthy (metrics/logs/alerts)
- run backup and recovery drills

## Access governance runbook

### Onboard user

1. Create user account.
2. Assign minimal role in target tenant.
3. Validate effective permissions with test login.
4. Record approval/change ticket.

### Review access

- weekly: privileged roles
- monthly: all tenant memberships
- immediate: offboarding events

## Change process for production

Before rollout:

- validate rollback target
- verify monitoring baseline
- communicate maintenance window

After rollout:

- monitor soak period
- compare error rate and p95 latency
- close only after stabilization

## Security baseline

- no plaintext secrets in git
- regular secret rotation
- separate admin identities
- restricted ingress and endpoint exposure
