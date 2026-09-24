# Target Architecture — DataTurntable as a Self-Contained Data Lakehouse

Status: **Draft — decisions agreed in design review, not yet implemented.**
Scope: records the target state and the phased plan. Where the current code differs, the gap is named with a file reference.

## 1. Product goals (not to be down-scoped)

| Goal | Meaning |
|---|---|
| Self-contained | One installation (one Helm chart / one Compose file). All components are bundled, deployed and lifecycle-managed by the app. Users never log into or operate a component directly. The only external prerequisites are a Kubernetes cluster with a storage class (or a Docker host for single-node) and a backup destination. |
| Single UI | Everything is reached through the app UI and the common app login. Grafana is embedded behind that login. |
| Lakehouse | Open table format on object storage, own catalog, embedded query engine, medallion layers, time travel and replay. |
| Data focus | Measurements, time series and structured data. Files, text and blobs are not a focus; file upload means importing structured files (CSV/Parquet/JSON) into tables. |
| Scale | 1B events/day (≈11.6k/s average; design target 50k/s sustained peak) on a multi-node, autoscaling Kubernetes deployment. Single-node is supported but not held to this number. The claim is only published with a reproducible benchmark. |
| Paper trail | Every action with any impact is recorded in a searchable audit log. |

## 2. Personas and permission planes

| Plane | Business role | Typical rights |
|---|---|---|
| System | IT department (system admins) | Install, upgrade, scale, back up, system monitoring, tenant creation and quotas, provisioning connections to external systems, role template management |
| Tenant | Key users of a department (tenant admins) | Members and role assignments inside the tenant, requesting and receiving connections, tenant dashboards, pipeline oversight |
| User | Department end users | Pipelines, modules, data, dashboards and SQL on connections granted to their tenant |

### 2.1 Permission model

- **The product defines the permission vocabulary** (because each permission maps to an enforcement point in code), using one naming scheme: `domain.resource.action`, e.g. `system.config.read`, `system.deployment.write`, `data.read`, `pipeline.write`, `audit.read`, `audit.note.write`.
  - Current gap: two naming styles coexist (`read:connector`, `manage:tenant` vs `module:read`, `tenant:manage`), so grants can silently fail to match.
- **Each installation defines roles and assignments.** Nothing about who may do what is fixed system-wide.
- **Scopes:** `system` (whole installation), `tenant` (optionally including child tenants), later `resource` (single pipeline/connection).
  - "Config only" IT role = `system.config.read` without `data.*`.
  - "Data of all tenants" IT role = `data.read` assigned at `system` scope.
- **The superadmin flag is removed.** Current gap: `is_superadmin` bypasses every check (`User.kt:142`, `:159`, `:188`). The installing admin receives a system role that cannot be removed while it is the last holder.
- **Time-bounded assignments:** every role assignment has `valid_from`, `valid_until`, `granted_by`, `reason`. Current gap: memberships have no validity window.
  - Permissions are resolved server-side per request (short cache, invalidated on change), never embedded in tokens, so expiry is effective immediately.
  - A scheduler performs expiry side effects (e.g. Grafana org removal) and records them in the audit log with actor `system`.
- **Guardrails:** nobody can grant a permission they do not hold; the last system admin cannot be removed; system-scope data access is always audited (not configurable).

### 2.2 Role templates

- Shipped with the installation, managed in the UI by users holding `system.roles.manage`.
- **Built-in templates** (System Operator, Config Auditor, Cross-Tenant Data Analyst, Tenant Admin, Pipeline Developer, Viewer):
  - marked as built-in in the UI;
  - **immutable** (API rejects edits; enforced in the database as well);
  - **inspectable**: permission list with descriptions, grouped by domain, plus which assignments currently use it;
  - **clonable** into an editable custom role;
  - **disableable**: shows an impact preview (number of affected assignments), requires a reason, and is audited. A disabled role grants nothing and cannot be newly assigned; existing assignments remain visible as inactive.
- **Upgrades:** built-in templates follow the product version. Template changes on upgrade are shown to system admins as a diff and recorded in the audit log. Clones never change automatically; when new permissions appear, admins are notified. Installations that need frozen privileges use clones.

### 2.3 External system connections

- **Connection** (system plane): endpoint, credentials, network/broker settings. Secrets are encrypted and never shown to tenants.
- **Connection grant** (to a tenant): scope such as topic prefix, path, rate limit, validity window.
- End users bind granted connections in the pipeline editor.
- **Request workflow in the UI:** key user requests a new external system → IT sees it in the admin area → provisions → grants. Each step is audited.

## 3. Authentication

- **Common app login for every component** (app, both Grafanas, Prometheus/Loki UIs if exposed, docs).
- **Reverse proxy authentication:** nginx `auth_request` validates the app session against the backend on each request and forwards the identity to Grafana using Grafana's auth-proxy mode. Components are not reachable except through the proxy (network policies). Separate Grafana passwords are removed. Current gap: `GrafanaProvisioningService.kt:313` creates Grafana users with generated passwords, and nginx forwards to components without checking the login.
- **Session:** HttpOnly, Secure, SameSite cookie plus CSRF protection. Current gap: the JWT is stored in `localStorage` and sent as a Bearer header (`frontend/src/services/api.ts:67`); browser navigation to proxied components cannot carry it, and it is exposed to XSS.
- **OIDC client:** the app logs in via customer identity providers (Entra ID, AD FS, Keycloak, Okta, …).
  - Providers configured at runtime in the UI by system admins (issuer, client id, encrypted secret, scopes, claim mappings).
  - Group/claim → role-assignment mapping with scope; just-in-time user provisioning.
  - Local login can be restricted to a break-glass account.
  - RP-initiated and back-channel logout; session revocation.
  - Limitation: without SCIM, IdP group removals take effect at next login or session refresh; session lifetime is configurable accordingly. SCIM is a later option.

## 4. Audit and activity log

Current gap: `logging/AuditLogger.kt` has no callers and only writes to stdout. There is no audit table or UI.

### 4.1 Coverage — every action with impact

- All create/update/delete on every entity (pipelines and versions, modules and versions, connections, grants, tenants, users, roles, assignments, dashboards, alert rules/channels, settings).
- Security: login/logout, failed login, session revocation, OIDC provider changes, permission denials.
- Permission lifecycle: grant, change, automatic activation and expiry (actor `system`).
- Operations: start/stop/cancel/retry of pipelines and connectors, deployments, scaling, upgrades, backups, restores, retention deletions, lake maintenance (compaction, snapshot expiry).
- Data access at system scope (always); data access at tenant scope and exports (configurable per installation).
- Grafana changes: dashboard/folder/datasource changes are captured from mutating requests at the proxy plus Grafana's dashboard version history (open point: completeness of this capture must be verified).

### 4.2 Event model

`id, occurred_at, actor (id, name snapshot, type: user|api-key|connector|system|scheduler, auth method, session, IP, user agent), action (same vocabulary as permissions), target (type, id, name snapshot, parent), tenant, scope, outcome (success|denied|failed, error), changes (before/after diff, secrets redacted), reason, source (ui|api|grafana|system), correlation id`.

- Secrets are never stored in diffs (only "changed"). Measured data values are not stored, only identifiers and counts.

### 4.3 Integrity

- The audit record is written **in the same transaction** as the change; if the audit write fails, the action fails.
- Actions outside the database (Kubernetes, Kafka, object store) write an intent record before and an outcome record after.
- Append-only: the application's database role has no UPDATE/DELETE on audit tables; a trigger rejects modifications.
- Tamper evidence: hash chain over records (each record includes the hash of its predecessor), verifiable from the UI.
- Retention configurable per installation with a minimum; old partitions are archived to the lakehouse and remain queryable.
- Optional forwarding to a SIEM (syslog / OTLP / webhook).
- Open point: interaction with personal-data deletion requests (actor snapshots) is a customer legal decision; pseudonymisation of actor data after user deletion is offered as an option.

### 4.4 Notes

- Users can attach notes to an audit event, or add a standalone note to a component's timeline (e.g. "vendor maintenance window Friday").
- Notes are immutable, append-only and themselves audited; corrections are follow-up notes.
- Permission: `audit.note.write` (scoped like everything else).

### 4.5 UI

- **Activity tab on every component's config page** (pipeline, connector/connection, tenant, user, role, dashboard, …), filtered to that component and its children (e.g. a pipeline includes its versions and run triggers).
- **Global audit page:** filters for time range, actor, action, target type/id, tenant, outcome, source, free text (full-text search over summary and notes); saved filters; CSV/JSON export (the export is itself audited).
- Visibility follows `audit.read` scope: tenant scope sees the tenant's events; system scope sees all.

## 5. Grafana

- **Application Grafana** stays for all user levels to build their own visualizations. One Grafana org per tenant for dashboard separation; users with cross-tenant data roles are members of each relevant org.
- **Data access through a bundled datasource plugin** that queries the app's query API with the logged-in user's identity. The app enforces the same fine-grained, time-bounded permissions for Grafana queries as everywhere else, and the plugin covers both the hot tier and the lakehouse. Current gap: all tenant datasources share one database user and are separated only by `searchPath` (`GrafanaProvisioningService.kt:242-251`), which is not a security boundary.
  - Open point: the plugin is unsigned; loading it via Grafana configuration must be verified for the pinned Grafana version.
- **System Grafana** reads Prometheus and Loki only, restricted to `system.monitoring.read`. User module output is kept out of Loki (it goes to tenant-scoped run logs), so no tenant data reaches the system plane.

## 6. Data platform

### 6.1 Ingest and processing

- Ingest-edge pods: stateless, API-key authenticated (current gap: `IngestResource.kt:46`, `:117` ignore the key), batch endpoints, write to Kafka keyed by tenant/pipeline, autoscaled.
- Processing: long-running Python worker pods per pipeline stage consuming Kafka micro-batches (the module contract already accepts DataFrames), scaled on consumer lag (KEDA). The backend creates these workloads through the Kubernetes API. Isolation via pod security context, network policies, optional gVisor. Current gap: modules run only via `docker run` (`DockerService.kt`), which does not exist on Kubernetes, and one container call per event cannot reach the target throughput.

### 6.2 Lakehouse

- **Table format:** Apache Iceberg via `iceberg-core` inside the backend.
- **Catalog:** Iceberg JDBC catalog in the existing Postgres; the backend also serves the Iceberg REST catalog API with a namespace per tenant and app authentication, so Python modules (PyIceberg/DuckDB) and external tools can attach.
- **Object storage:** bundled S3-compatible store (SeaweedFS default; external S3 optional). Open point: verify current licensing/distribution of candidate stores.
- **Writes:** sink consumer group writes Parquet; one committer per table elected via `ConnectorLeaseService`, committing every 30–60 s; app-scheduled compaction, snapshot expiry, orphan cleanup.
- **Layers:** bronze (all raw ingested events, enabling replay), silver/gold (`DB_SAVE` targets).
- **Time-series layout:** `tenant, series_id, tags, ts, value(s), quality, ingest_ts`, partitioned by `day(ts)` and a `series_id` bucket, sorted by `series_id, ts`. Late data handled by event-time partitioning plus compaction re-sort. Continuously maintained 1m/1h/1d rollups.
- **Query:** DuckDB embedded in a query runtime role, reading metadata resolved by the catalog (tenant isolation enforced in one place). Interactive over recent data and rollups; large scans as async jobs. A bundled distributed engine (Trino) is added only if benchmarks show it is needed.
- **Hot tier:** TimescaleDB with continuous aggregates and retention; access through per-tenant database roles.

## 7. Deployment

- One Helm chart with bundled dependencies managed by operators (CloudNativePG, Strimzi, KEDA), plus the object store and MQTT broker. Single-node Docker Compose uses the same images.
- Open point: `docker/helm/` currently relies on Bitnami charts; verify their current availability terms.
- Images pinned to versions/digests; lockfiles committed.

## 8. Phases

| Phase | Content |
|---|---|
| 0 — Foundations | Per-tenant Postgres roles + `SET ROLE` (fixes `DB_SAVE` schema escape and Grafana `searchPath` leak); ingest API-key validation; remove committed JWT private key; keep module output out of Loki; unify permission names; Testcontainers instead of drifted H2 schema; fix failing tests; CI; committed lockfile; pinned images |
| 1a — Identity, permissions, audit | Cookie session + CSRF; reverse-proxy auth for all components; OIDC client; scoped, time-bounded assignments; role templates; superadmin removal; connections/grants/request workflow; audit log (storage, integrity, notes, activity tabs, audit page) |
| 1b — Execution engine | Worker pods, Kafka keying/partitions, Kubernetes-managed workloads, KEDA |
| 2 — Lakehouse core | Object store, Iceberg, catalog + REST API, bronze sink, maintenance, time-series layout, rollups |
| 3 — Query and UI | DuckDB query role, SQL workspace, catalog UI, Grafana datasource plugin |
| 4 — Packaging and proof | Helm chart with operators, autoscaling, reproducible 1B/day benchmark |

The audit log is placed in phase 1a so that every later feature is built with its paper trail from the start.
