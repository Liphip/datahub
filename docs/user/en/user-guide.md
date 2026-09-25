# DataTurntable User Guide (English)

## 1. Purpose

DataTurntable helps teams ingest, process, route, monitor, and troubleshoot data pipelines in multi-tenant environments.

This guide is written for:

- tenant administrators
- platform operators
- module and pipeline maintainers
- support/on-call engineers

## 2. First Login and Initial Setup

1. Run first-time setup (`/setup`) once per environment.
2. Sign in at `/login`.
3. Confirm your active tenant context in the header.
4. Open Dashboard and check health/reliability cards.

Best practice:

- Keep superadmin credentials in a password manager.
- Use role-scoped users for daily operations.

## 3. Navigation and Global Controls

- Dashboard: runtime overview and quick actions
- Modules: reusable processing components
- Pipelines: orchestration and run history
- Connectors: external integration endpoints
- Data: table explorer, trend summary, exports
- Alerts: channels, rules, and delivery history
- Users/Tenants: identity and tenant governance

Header controls:

- tenant switcher
- language selector
- color theme toggle

## 4. Tenant Model and Permissions

Use tenants to isolate data and operation boundaries.

Typical pattern:

- root tenant per organization
- child tenants per environment/team/region

Permission principles:

- `*:read` for view-only users
- `*:write` for operational maintainers
- `manage:tenant` for tenant administration
- avoid wildcard abilities except for emergency/admin roles

## 5. Modules

Module types:

- IMPORT
- PROCESSING
- ALERT
- EXPORT

Recommended lifecycle:

1. Create module with clear intent and description.
2. Implement and test in lower environment.
3. Save version with explicit tag (`v1.3.0`, `staging`, `prod`).
4. Promote via pipeline updates.

Operational tips:

- Track dependency changes carefully.
- Version any behavior change, no exceptions.

## 6. Pipelines and Runs

Pipelines connect modules into executable flows.

When updating pipelines:

1. Validate source/destination compatibility.
2. Trigger a controlled test run.
3. Review run details and lineage diagnostics.
4. Observe reliability windows (1h/24h/7d).

If failures increase:

- correlate run timestamps with logs and metrics
- inspect upstream connector health and quotas
- verify recent module version changes

## 7. Data Explorer

Use Data Explorer for table inspection and quick analysis.

Capabilities:

- table listing and selection
- time range and row limit filters
- summary cards (min/max/avg/latest)
- trend and tag-distribution visualizations
- CSV/JSON exports

Safety guidance:

- create/delete tables only with `data:write`
- avoid destructive actions in production without change control

## 8. Connectors and Alerts

Connectors define integration points to external systems.

Alert workflow:

1. configure channels (email/webhook/etc.)
2. define rules and severity
3. validate with test triggers
4. monitor history (`PENDING`, `SENT`, `FAILED`, `RETRY`)

Keep escalation ownership explicit per tenant.

## 9. Monitoring, Logs, and Diagnostics

Observability stack includes Prometheus, Grafana, Loki, exporters, and probes.

Use dashboards for:

- service uptime and latency
- backend request rates
- host/container resource consumption
- cross-service logs

Diagnostics process:

1. confirm API health
2. check tenant context and permissions
3. inspect run-level failures
4. correlate metrics and logs in same time window
5. export diagnostics snapshot for support handover

## 10. Backup and Recovery

Protect critical state:

- PostgreSQL/Timescale volumes
- optional Grafana state (if dashboards edited in UI)
- exported module and pipeline definitions

Run regular restore drills and track RPO/RTO targets.

## 11. Troubleshooting Checklist

- Is API reachable and healthy?
- Is the correct tenant selected?
- Does the user have required abilities?
- Did a recent module/pipeline version change occur?
- Do logs and metrics agree on failure timing?
- Are external dependencies (DB/Kafka/Redis/MQTT) healthy?

## 12. FAQ

### Why do I see no data in tables?

Most common causes: wrong tenant context, insufficient permissions, or no ingest into selected table/time range.

### Why is a dashboard panel empty until edited?

Usually datasource UID mismatch in provisioned dashboards; ensure dashboard JSON uses fixed provisioned datasource UIDs.

### Can monitoring run separately from application services?

Yes. Monitoring can be centralized and scrape multi-instance Kubernetes application deployments.

## 13. Practical Tutorials

### Tutorial A: Create and Verify a New Pipeline

1. Create or select a tenant-specific module set.
2. Create pipeline linking import -> processing -> export.
3. Trigger a manual run.
4. Validate output table in Data Explorer.
5. Confirm alert silence/noise behavior is acceptable.

Success criteria:

- run status successful
- latency near baseline
- no unexpected alert storms

### Tutorial B: Investigate a Performance Regression

1. Identify regression start time from Dashboard trends.
2. Open Grafana and compare p95 latency before/after.
3. Check Loki logs for error bursts or retry loops.
4. Validate recent module/pipeline version changes.
5. Roll back if risk remains high.

### Tutorial C: Tenant Access Validation

1. Create a read-only test user.
2. Assign role in a non-production tenant.
3. Verify user can view but cannot mutate data.
4. Capture evidence (screenshots + audit note).

## 14. Operational Tips Library

- Keep a release-note template with `what changed`, `why`, `risk`, and `rollback`.
- For incidents, always capture timezone with timestamps.
- Use narrow query windows first, then widen gradually.
- Prefer one major variable change per release window.
- Regularly prune stale modules and orphaned connectors.

## 15. Escalation Template

When escalating to platform support, include:

- tenant ID and affected pipeline/module IDs
- first observed timestamp and timezone
- recent changes deployed
- metrics panel query and screenshot
- relevant log excerpts
- temporary mitigation already attempted

## 16. Documentation Site

Standalone docs site path:

- `/docs` (MkDocs Material documentation service)

Source of standalone docs site:

- `docs-site/`
