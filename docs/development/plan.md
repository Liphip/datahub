# DataTurntable — Implementation Plan

## Overview

DataTurntable is a high-performance, scalable data hub for IoT/sensor data processing. It provides:

- **Modular connectors** — Hot-pluggable MQTT, HTTP/REST, WebSocket, UDP and custom connectors. The system exposes protocol endpoints for external systems to push data to, and can also actively pull from external sources. On the output side it pushes to external endpoints or exposes pull endpoints.
- **Graph-based processing pipelines** — Non-linear DAG pipelines: fan-out (one module feeds multiple downstream modules) and fan-in (combination module merges multiple streams)
- **Four Python module types** — IMPORT, PROCESSING, ALERT, EXPORT; each with a defined interface contract
- **TimescaleDB** for time-series data storage
- **Multi-tenant** architecture with fine-grained RBAC
- **Alerting system** — Dedicated ALERT modules with built-in rule types and custom Python logic; multiple dispatch channels

### Performance Targets

- **1B readings/day** (~11,574 reads/sec)
- Horizontal scaling with failover
- Kafka-based message persistence

---

## Ship-Readiness Plan

This section catalogues every known gap between the current codebase and a shippable product, grouped by layer, with a clear priority ordering. Items are marked **[BLOCKER]** (product cannot work without it), **[HIGH]** (significantly degrades user experience), or **[LOW]** (nice-to-have / polish).

---

### Progress Update (Current Iteration)

- Implemented `/auth/register` compatibility endpoint (setup + immediate JWT login) to support frontend registration bootstrap.
- Fixed auth-user password persistence path to store BCrypt-wrapped hashes (login compatibility restored for users created via `/auth-users`).
- Fixed `AuthService` JWT generation when tenant is absent (`tenantId` claim omitted instead of null claim crash).
- Fixed `UserDto` tenant serialization to avoid stringified `"null"` tenant IDs.
- Added `tenantId` query-param support to `POST /auth-users` for explicit tenant-scoped create flows.
- Expanded module API payloads (`GET /modules`, `GET /modules/{id}`, `GET /modules/{id}/versions`) with fields required by editors and lists.
- Expanded pipeline API payloads (`GET /pipelines`, `GET /pipelines/{id}`, `GET /pipelines/{id}/runs`, `POST /pipelines/{id}/run`) and made trigger transactional.
- Updated alert persistence to serialize `condition` and channel `config` as JSON strings.
- Updated tenant create flow to seed default roles and added superadmin guards to tenant mutation endpoints.
- Added schema alignment migration `V2__auth_users_and_enum_schema_alignment.sql` for auth-user/membership column naming, enum CHECK constraints, and global email uniqueness.
- Updated frontend `PipelineEditor` to resolve selected modules to latest module version IDs before save.
- Updated frontend `PipelineEditor` and `ModuleEditor` to use toast notifications instead of blocking browser alerts.
- Added frontend `/setup` page and route for first-run bootstrap and linked it from login.
- Updated Tenants UI to replace browser confirm/alert flows with inline confirmation and toast notifications.
- Hardened Data API query/insert/delete endpoints with strict table name validation and parameterized SQL inputs for time/device/value/tags.
- Role API now enforces RBAC for create/update/delete and serializes abilities as JSON arrays in responses and persistence.
- Hardened Connector API UUID validation and PATCH behavior so empty config payloads no longer overwrite stored connector config.
- Added authenticated tenant-context APIs (`GET /auth/tenants`, `POST /auth/switch-tenant`) and wired frontend tenant switching in the top navigation.
- Replaced remaining browser confirm dialogs in Connectors, Alerts, and Data pages with inline/modal confirmations plus toast feedback.
- Added alert update APIs (`PATCH /alerts/channels/{id}`, `PATCH /alerts/rules/{id}`) and frontend edit flows for channels/rules.
- Updated frontend 401 interceptor to skip redirects for public setup/register/login auth calls.
- Improved Users page permission handling: partial-data loading, ability-gated actions, and graceful read-only states for non-admin roles.
- Membership API list/create/update responses now include `authUserEmail` and `roleName` fields for simpler frontend rendering.
- Hardened `SecurityUtils` tenant parsing and DTO conversion to avoid invalid UUID crashes and null tenant string leakage.
- Added Users page activate/deactivate controls with optimistic status updates and ability-gated actions.
- Hardened Auth Users API UUID validation paths to return `400` on invalid tenant/user identifiers instead of unhandled errors.
- Added synchronous execution fallback path for pipeline triggers and ingest/websocket data so runs transition out of `PENDING` in current environments.
- Improved Pipelines UI with automatic run-status refresh while recent runs are `RUNNING`/`PENDING`.
- Wired `pipeline-input` Kafka channel/topic (`dataturntable.pipelines.input`) so `PipelineConsumer` has an active upstream, with direct-consume fallback only when Kafka emitter is unavailable.
- Added backend alert-rule evaluation in pipeline execution (`field/operator/value`, `any`/`all`, cooldown checks) with automatic `alert_history` dispatch creation.
- Improved Alerts rule UX with JSON condition templates and inline validation errors.
- Alert Rule API now validates condition JSON structure on create/update (rejects malformed or incomplete condition objects).
- Fixed ingest/websocket pipeline lookup to use JPA entity manager lookups (avoids Panache static lookup failures in runtime/tests).
- Alert history API now returns full history payload fields (`severity`, `message`, `sentAt`, linked ids) for frontend consistency.
- Alerts UI now includes a guided condition builder (single/any/all logic) that generates valid JSON conditions.
- Pipeline runs now persist structured metrics (`executionMs`, result/error payloads, alert evaluation stats) and expose parsed metrics in `/pipelines/{id}/runs` responses.
- Pipelines list UI now surfaces latest run execution time and alerts-fired counters for quick operational visibility.
- Pipelines page now has expandable run details (status/timing/error + alert evaluation breakdown + raw result payload) for faster debugging.
- Pipeline listing now supports health-focused filtering (`problematic`, `running`, `failed`, `healthy`) and sort modes for faster incident triage.
- Pipeline list API supports optional `includeRunSummary=true` to return latest run status/timestamp metadata.
- Dashboard now highlights "Problem Pipelines" with direct links and a one-click jump to the pipelines triage view (`/pipelines?health=PROBLEMATIC`).
- Pipeline API now validates tenant/pipeline UUID inputs consistently (returns `400` instead of unhandled parse errors).
- Added backend-supported pipeline health filtering via `GET /pipelines?runHealth=...` and wired frontend list loading to use server-side filters.
- Added app-level API connectivity banner (network/server outage awareness) with automatic online/offline detection and retry action.
- Pipeline list API now validates `runHealth` filters and rejects unknown values with `400`.
- Tenant API now supports `GET /tenants?recursive=true` tree responses and validates tenant UUID path/query inputs with explicit `400` errors.
- Tenants UI now loads recursive tenant trees in a single request and supports deep parent selection for nested sub-tenant creation.
- Tenant API recursive listing now supports `query` filtering to return matching branches only.
- Tenants UI now includes tenant-name search and expand/collapse controls for large hierarchies.
- Dashboard now includes superadmin tenant topology insights (total/root/leaf/depth + last update) powered by recursive tenant metadata.
- Added `GET /pipelines/runs/summary` aggregate endpoint and dashboard reliability panel (success rate, failures, in-flight runs, average duration).
- Added reliability timeframe controls (`1h`/`24h`/`7d`) on dashboard and `sinceHours` support on run-summary API.
- Run summary API now includes trend buckets (`bucketSeconds`, `trendBuckets`) and dashboard shows a compact reliability trend sparkline.
- Dashboard trend bars now deep-link into pipelines triage with pre-applied health/time filters.
- Pipeline list API supports `latestSinceHours` filtering and validates allowed values (`1`, `24`, `168`).
- Pipeline list API now supports precise latest-run time window filtering (`latestFrom`/`latestTo`) for bucket-level triage.
- Pipelines page now understands dashboard sparkline bucket filters and shows active bucket context with clear action.
- Bucket drilldown table now has clickable status chips and quick status counters to jump to filtered triage states instantly.
- Bucket drilldown now supports shareable sort presets (`newest`, `failed-first`) persisted in URL query (`bucketSort`).
- Added one-click "Copy link" action in bucket drilldown so triage context URLs can be shared directly.
- Added optional auto-refresh toggle for bucket drilldown runs table to support live incident monitoring.
- Added bucket drilldown freshness controls (`Refresh now`) plus visible last-refreshed timestamp for live triage confidence.
- Bucket drilldown now shows freshness severity (`Fresh`/`Aging`/`Stale`) and pauses auto-refresh while browser tab is hidden.
- Bucket drilldown freshness age now updates live every second (while visible) for real-time staleness feedback.
- Tenant runs API now supports `runStatus` filtering (with validation), and bucket drilldown adds status + pipeline-name filters.
- Bucket drilldown now shows active triage filter pills (status/query/sort) with one-click clear actions.
- Added keyboard shortcuts for bucket triage (`/` focus query, `f` failed, `r` running, `a` all statuses).
- Added in-panel shortcut hint chips with hide/show toggle, persisted via localStorage for repeat triage users.
- Bucket drilldown now supports shareable pipeline pinning (`bucketPipelineId`) with one-click "Only" actions per run row.
- Bucket panel now exposes an "Open pinned" shortcut when pipeline pinning is active.
- Added one-click "Clear all triage" action to reset health/time/bucket drilldown filters back to default pipelines view.
- Added saved triage presets (save/apply/delete, persisted in localStorage) for repeat incident workflows.
- Added triage preset export/import (JSON) for cross-machine/team sharing of incident filter playbooks.
- Added triage preset tagging + tag filter dropdown to organize and quickly reuse larger preset collections.
- Added favorite triage presets (star/unstar) with favorites automatically pinned to the top of preset lists.
- Added manual ordering controls for favorite presets (up/down) so on-call teams can prioritize top incident playbooks.
- Added inline triage preset duplicate and rename actions for faster playbook evolution without recreating filters.
- Added triage preset usage metadata (`useCount`, `lastUsedAt`) and preset sort modes (`favorites`, `recently used`, `most used`).
- Preset chips now expose quick usage context (count + relative last-used tooltip) to help choose fresher playbooks.
- Added per-preset and global usage-stat reset actions to rebaseline triage playbook popularity tracking.
- Added optional confirmation guardrails for destructive preset actions (delete/reset), with persisted preference toggle.
- Expanded keyboard triage shortcuts to preset workflows (`n` focus new preset, `p` apply top visible preset).
- Hardened tenant read APIs (`GET /tenants`, `GET /tenants/{id}`, `GET /tenants/{id}/children`) with explicit authorization checks and coverage for forbidden authenticated access.
- Extended tenant management auth to allow scoped `manage:tenant` users (while keeping root-tenant create/delete superadmin-only), and updated Tenants navigation/page gating to use tenant-management abilities.
- Enhanced `ModuleEditor` version-history UX with one-click version loading plus unsaved-change guardrail (inline confirm) and loaded-version indicator.
- Hardened Role API UUID/error handling and role read access checks (`manage:tenant` + tenant scope), and improved Modules list version counters to use backend `versionCount` metadata.
- Added module RBAC enforcement in `ModuleResource` (`module:read`/`module:write`/`module:delete` + tenant scope), plus frontend module permission UX (read-only editor mode, gated create/delete actions, and clearer 403 guidance on Modules page).
- Added pipeline RBAC enforcement in `PipelineResource` (`pipeline:read`/`pipeline:write`/`pipeline:execute` + tenant scope), and upgraded `PipelineEditor` to select concrete module *versions* (not module IDs) with permission-aware read/write/execute UX states.
- Hardened Alert API authorization/tenant scoping (`alert:read`/`alert:configure`) including cross-tenant rule/channel/pipeline reference guards, and updated Alerts UI to present permission-aware read/configure states.
- Hardened Data API tenant RBAC (`data:read`/`data:write` + tenant scope) and updated Data Explorer to provide permission-aware read/write states (including guarded create/delete/query flows).
- Added connector scoped-access coverage for forbidden tenant access and updated Connectors page UX to enforce read/write/delete permission states in-page.
- Scoped `AuthUserResource` tenant operations to tenant memberships (including tenant-aware get/update/delete with safer delete semantics), and improved Users page UX with search/status/role filters plus filtered empty-state messaging.
- Replaced fragile `Role.findByTenantId(...)` static lookup usage in `RoleResource` list path with EntityManager-backed tenant query, added tenant role-list regression coverage, and improved Users page recovery UX with explicit retry action on partial-load errors.
- Added explicit connector lifecycle operations (`connect`, `disconnect`, `test`) with permission checks and validation API responses, plus Connectors page controls/status badges for connect/disconnect/test workflows.
- Added mobile navigation chips in `Layout` so all pages (including Connectors) are reachable on small screens, and widened Connectors page ability fallback to include `manage:tenant` for legacy-role compatibility.
- Fixed PostgreSQL bootstrap migration for optional Timescale hypertable creation (`PERFORM create_hypertable(...)` in `V1__Initial_schema.sql`) to prevent backend startup failure on empty schemas.
- Fixed setup-password handling so `/auth/setup` correctly hashes provided plaintext passwords before BCrypt storage (restoring custom-password login compatibility), with regression coverage in `AuthResourceTest`.
- Fixed pipeline entity mapping regression by binding `importModuleVersionId` to `import_module_version_id` directly (removing duplicate implicit camelCase column selection) to stop `/pipelines?...includeRunSummary=true` SQL 500 errors.
- Added tenant-context fallback (`selectedTenantId -> user.tenantId -> first auth tenant context`) across core frontend pages so create actions continue to work when JWT `tenantId` is absent/stale.
- Refined frontend visual contrast (buttons/inputs/cards/background typography) and added persistent light/dark theme toggle in app layout (`tailwind darkMode='class'`) to improve readability across key workflows.
- Reworked create/edit dialogs to be entity-type aware: Connectors now use protocol-specific config fields (instead of raw JSON), and Alert Channels now use channel-type-specific config forms with required-field validation.
- Added pipeline-run module observability: backend now captures per-module stdout/stderr/exit metadata and execution timeline in run metrics, and Pipelines run details now render those logs directly in the frontend.
- Added integrated MQTT broker assist: backend exposes `/connectors/mqtt/defaults`, and the Connectors MQTT form now offers quick-fill actions for integrated/local broker endpoints while still allowing custom broker configuration.
- Added tenant-scoped integrated MQTT broker credential management (separate from auth users) with topic ACL lists, password rotation, and admin-only UI/API controls (`/connectors/mqtt/credentials*`).
- Linked MQTT connectors to tenant broker credentials via `brokerCredentialId` selection in the UI and backend ACL validation (connector topic must match allowed broker credential topics).
- Improved pipeline graph guardrails: frontend now blocks save when required import/processing/export connectors/modules are missing and surfaces backend validation messages; backend graph validation errors now include offending node IDs for faster fixes.
- Added broker credential lifecycle guardrails: backend now blocks deletion when a credential is still referenced by tenant MQTT connectors (with reference details), and the Connectors UI shows per-credential usage plus disables delete while in use.
- Added runtime connector injection for pipeline module execution: import/export node `connectorId` configs are now resolved from graph definition and passed into module containers, with MQTT broker credentials expanded to runtime username/password when `brokerCredentialId` is configured; pipeline run module logs now include connector context in the UI.
- Improved MQTT broker credential usage visibility: credential list API now returns `referenceCount`, Connectors UI uses backend-provided counts, and connector create/update/delete flows refresh credential usage to keep delete guardrails accurate.
- Hardened MQTT connector secret handling when broker credentials are bound: backend strips inline `username`/`password` from connector config if `brokerCredentialId` is set, and the Connectors form now auto-clears/disables those fields to prevent conflicting auth sources.
- Expanded pipeline editor and engine support for ALERT module nodes: UI now lets users add/remove alert module versions in pipeline graphs, graph save/load preserves ALERT nodes, and pipeline execution runs graph-defined ALERT modules with module-level logs in run observability.
- Hardened graph contract validation end-to-end: backend `PipelineResource` now validates graph node module-version existence/tenant ownership/type alignment (`IMPORT/PROCESSING/ALERT/EXPORT`) plus edge node references, while `PipelineEditor` pre-validates stale module-version selections and duplicate export-connector assignments before save.
- Added DAG safety and editor guidance: backend graph validation now rejects cyclic pipelines, and `PipelineEditor` includes a live "Configuration Health" checklist panel to surface missing/stale module selections and export-connector issues before save.
- Improved pipeline run retrieval performance and UX: `/pipelines/{id}/runs` now supports `limit` and `includeDetails` query parameters, enabling lightweight recent-run polling; the Pipelines page now uses lightweight polling for cards and fetches detailed run payloads only when a pipeline is expanded.
- Added per-pipeline reliability summaries: backend now provides `GET /pipelines/{id}/runs/summary?sinceHours=...` with trend buckets/success stats, and the Pipelines details panel now shows a timeframe-switchable reliability snapshot (`1h`/`24h`/`7d`) alongside run logs.
- Enhanced pipeline run drilldown filtering: `GET /pipelines/{id}/runs` now accepts `startedFrom`/`startedTo`/`runStatus`, and the Pipelines reliability snapshot now renders clickable trend buckets + quick status filters (failed/running) to load focused run details during incident triage.
- Added pipeline run cancellation controls: backend now supports `POST /pipelines/{id}/runs/{runId}/cancel` (with execute-scope auth and completed-run guardrails), `PipelineConsumer` now respects cancelled runs to avoid overriding status, and the Pipelines run-details panel now exposes a `Cancel run` action for `RUNNING`/`PENDING` runs.
- Improved Pipelines page RBAC UX: added explicit read/write/execute permission gating for create/run/delete/cancel actions, disabled unauthorized controls with explanatory tooltips, and added a clear no-access state when users lack `pipeline:*` read scope.
- Added pipeline run retry workflow: backend now supports `POST /pipelines/{id}/runs/{runId}/retry` (execute-scope auth, in-progress guardrails, retry provenance via `_retryOfRunId`), and the Pipelines run-details panel now offers a `Retry run` action for completed runs to speed operational recovery.
- Added retry lineage persistence and visibility: `pipeline_runs` now stores `retry_of_run_id` (migration `V4`), run APIs include `retryOfRunId` in responses, and the Pipelines details panel surfaces retry ancestry so operators can track recovery attempts.
- Added targeted run-detail retrieval: backend now supports `GET /pipelines/{id}/runs/{runId}` (with optional `includeDetails`) and the Pipelines details panel includes a `Refresh run` action that re-fetches only the selected run for lower-noise troubleshooting.
- Added retry-lineage drilldown: backend now exposes `GET /pipelines/{id}/runs/{runId}/lineage` with ancestor/descendant retry chains, and Pipelines run details now provide on-demand `Load lineage` navigation buttons for faster root-cause and recovery tracing.
- Improved connector list filtering and scalability: connector list API now supports combined filtering (`type` + `direction` + `isActive` + `q` search), and the Connectors page adds status/search controls backed by server-side filtering for faster large-tenant navigation.
- Added connector deletion safety against pipeline graph dependencies: connector list responses now include `pipelineReferenceCount`, connector delete rejects with `409` when graph nodes still reference the connector (with reference details), and Connectors UI surfaces usage count + disables delete until references are removed.
- Added connector usage drilldown API/UI: backend now exposes `GET /connectors/{id}/references` for pipeline/node-level dependency visibility, and Connectors UI provides a `Usage` modal for quick investigation before edit/delete operations.
- Enhanced connector usage triage UX: usage drilldown now includes pipeline active/inactive state and direct "Open Pipeline" navigation to the referenced pipeline editor for faster dependency cleanup.
- Added connector cloning workflow: backend now supports `POST /connectors/{id}/clone` (write-scope, tenant-scoped, cloned connector defaults to disconnected), and Connectors UI exposes a `Clone` action for quick safe duplication of connector configs.
- Hardened connector cloning defaults: clone now strips sensitive config keys by default (`password`/`token`/`apiKey`/etc.) while preserving non-sensitive fields, and Connectors UI explicitly labels clone behavior as a safe disconnected copy.
- Expanded connector clone ergonomics: clone API now accepts optional `direction` and `description` overrides with validation, and Connectors UI now uses a dedicated clone modal (name/direction/description + keep-secrets toggle) instead of one-click cloning for safer operator control.
- Added connector impact analysis endpoint/UI: backend now exposes `GET /connectors/{id}/impact` with impacted pipelines and latest run status context, and Connectors UI adds an `Impact` modal to prioritize dependency remediation.
- Expanded connector impact analysis with time-windowed health: impact endpoint now accepts `sinceHours` (`1|24|168`) and returns recent run stats per impacted pipeline (runs/failed/in-flight/success rate); Connectors impact modal now supports window toggles and displays operational health columns.
- Added connector impact risk scoring: impact endpoint now computes per-pipeline `impactPriorityScore` + `riskLevel` (`HIGH`/`MEDIUM`/`LOW`) and sorts results by priority; Connectors impact modal now visualizes risk badges/scores and aggregate risk counts for faster triage.
- Added safe-disconnect impact gating: connector impact API now supports `activeOnly=true` and returns aggregate active/risk counters, while Connectors UI now performs pre-disconnect impact checks and shows a confirmation modal with prioritized active pipeline risk before allowing disconnect.
- Added impact-to-triage deep-linking: connector impact now includes latest failed run metadata, and the Impact modal provides direct `Triage Runs` navigation into the Pipelines run-details view via focus query params (`focusPipelineId`/`focusRunId`) for faster incident handoff.
- Added impact filtering for focused remediation: impact endpoint now supports `riskAtLeast` (`LOW|MEDIUM|HIGH`) and the Impact modal adds active-only + risk-threshold toggles so operators can quickly isolate high-risk active dependencies.
- Enforced safe-disconnect on backend: `/connectors/{id}/disconnect` now blocks with `409` when active pipeline dependencies exist unless `force=true`, and Connectors UI now escalates that response into the existing disconnect-impact confirmation flow for explicit operator override.
- Enhanced run retry lineage navigation: pipeline run lineage API now returns root/latest lineage pointers plus depth metadata, and Pipelines run-details UI now adds one-click `Go to root` / `Go to latest retry` actions with depth-labeled lineage nodes for faster retry-chain debugging.
- Added scalable lineage loading controls: pipeline lineage API now accepts `maxDepth` and returns truncation flags, while the Pipelines run-details panel surfaces current depth and `Load deeper` controls when lineage is truncated to avoid over-fetching large retry chains.
- Added directional lineage loading for focused triage: pipeline lineage API now accepts `direction` (`both|ancestors|descendants`) with validation and response echo, and the Pipelines run-details panel adds direction chips so operators can inspect only retry ancestry or only downstream retries when troubleshooting large chains.
- Improved lineage performance and depth guidance: lineage descendant truncation now uses batched frontier checks (avoiding per-node depth-limit count queries), and the lineage response now provides `nextRecommendedDepth` so the UI can offer a deterministic `Load to dN` action when chains are truncated.
- Added status-focused lineage triage: lineage API now supports optional `statuses` filtering (`PENDING|RUNNING|SUCCESS|FAILED|CANCELLED`) while still returning total ancestor/descendant counts, and Pipelines run-details now adds lineage status chips plus shown/total counters so operators can isolate failure-state retries without losing chain-size context.
- Expanded lineage triage context: lineage API now returns per-status counts for ancestors/descendants, and the Pipelines run-details lineage panel now renders count-aware status chips (with disabled empty states) plus quick presets (`Failures only`, `In-flight only`, `Clear`) for faster incident navigation.

---

### 1. Authentication & Session

| # | Gap | Priority | Notes |
|---|-----|----------|-------|
| A1 | **`/auth/register` endpoint is absent** — the frontend `api.register()` call has no backend handler. There is only `/auth/setup` (first-run) and `/auth/login`. Normal users cannot self-register. | LOW | Remove it from the frontend and rely entirely on admin-invite flow. |
| A2 | **JWT `tenantId` claim is set from the user's *first* membership only.** If the user switches tenant context or belongs to many tenants, the token becomes stale. No `/auth/switch-tenant` endpoint exists. | HIGH | Add `POST /auth/switch-tenant` that issues a new short-lived JWT with a different `tenantId` claim, or store tenantId client-side and pass it per-request. |
| A3 | **`/auth/me` response shape mismatch.** `AuthService.toUserDto` returns `tenantId` as a string that may be `"null"` when the user has no membership. Frontend reads `user.tenantId` and uses it as a required UUID parameter; a `"null"` string breaks every API call. | BLOCKER | Guard `toUserDto` so `tenantId` is `null` (JSON null) not the string `"null"` when absent. |
| A4 | **Password hash storage is inconsistent.** `AuthUserResource.create` stores the raw client hash (SHA-256 base64) directly as `passwordHash`, while `AuthService.setup` wraps it with `BCrypt.hashpw`. `AuthService.login` calls `BCrypt.checkpw`, so users created via `/auth-users` can never log in. | BLOCKER | `AuthUserResource.create/update` must BCrypt-hash the incoming SHA-256 hash before persisting, same as `AuthService.setup` does. |
| A5 | **`ModuleEditor` and `PipelineEditor` still call `alert()`/`confirm()`** for save success/failure and run trigger. | HIGH | Replace with inline toast notifications (same pattern as Pipelines page). |

---

### 2. Users (`/auth-users` + Memberships)

| # | Gap | Priority | Notes |
|---|-----|----------|-------|
| U1 | **`AuthUserResource.create` uses `getTenantIdFromToken()` for auth context but the frontend does not pass it**; result is a 403 for admins whose token holds the root tenant. The frontend `api.createUser()` call hits `POST /auth-users` without a `tenantId` query param. | BLOCKER | Add `@QueryParam("tenantId")` to `AuthUserResource.create` (same pattern as other resources), or resolve the tenantId from the token more robustly (superadmin can create anywhere). |
| U2 | **`MembershipResource.list` requires `manage:tenant` ability**, which is correct, but the default Viewer/Editor roles seeded by `TenantService.createDefaultRoles` do NOT include `manage:tenant`. Only `Tenant Admin` does. Any non-admin user who loads the Users page gets a 403, causing the page to show nothing but an error. | HIGH | Document the required role clearly. The frontend Users page should gracefully hide the invite/delete actions for non-admin users rather than showing an error banner for the whole page. |
| U3 | **Users page re-loads all users via `api.getUsers(tenantId)` after every mutation, but `api.getUsers` calls `GET /auth-users?tenantId=...` which requires `read:user` ability** — this ability is not included in the default Viewer role either. Any non-admin who lands on the Users page gets a wall of 403s. | HIGH | Same fix as U2: ability gate the UI components individually. |
| U4 | **No way to deactivate/reactivate a user** (set `isActive = false`) from the frontend. The backend `PATCH /auth-users/{id}` supports `isActive`, but the UI has no control for it. | LOW | Add a toggle in the user row. |
| U5 | **`MembershipResource` returns only `{ id, authUserId, roleId, abilities }` — no `roleName` or `email`.** The Users page joins memberships with the users list client-side using `authUserId`, which requires that `getUsers()` and `getTenantMemberships()` return the same set and that UUIDs align. If a user has no membership (they were created but not yet added), they appear in the users list but have no role displayed. | LOW | Include `roleName` and `authUserEmail` in the membership list response or do the join server-side. |

---

### 3. Modules

| # | Gap | Priority | Notes |
|---|-----|----------|-------|
| M1 | **`ModuleResource.get` (GET `/modules/{id}`) returns only `{ id, name, moduleType }` — no `versions`, `description`, or `isActive`.** `ModuleEditor` calls `api.getModule(id)` and then accesses `data.versions[0].code`, which will be `undefined`, so the editor always loads with an empty template instead of the last saved code. | BLOCKER | Extend `ModuleResource.get` to include `description`, `isActive`, and the full version list (or latest version code). |
| M2 | **`ModuleEditor.handleSave` calls `alert()` on success/failure** — UX regression vs rest of UI. | HIGH | Replace with inline toast. |
| M3 | **`ModuleVersion` sidebar in `ModuleEditor` renders `data.versions` from the initial `getModule` response.** Because `get` doesn't include versions (see M1), the sidebar always shows "No versions yet" even after saving. | HIGH | Fix M1; additionally refresh the version list after a successful save. |
| M4 | **No ability to load a previous version** into the editor. The version history sidebar is read-only; clicking a version entry does nothing. | LOW | Add a "Load" button on each version entry to populate the editor. |
| M5 | **`ModuleResource.list` does not return `description` or `versions`.** The Modules list page shows version count from `module.versions?.length`, which is always `undefined/0` from the API. | LOW | Include `description` and a `versionCount` field in the list response. |

---

### 4. Pipelines

| # | Gap | Priority | Notes |
|---|-----|----------|-------|
| P1 | **`PipelineResource.list` and `get` omit `description`, `createdAt`, `importModuleVersionId`, `processingModuleVersionIds`, `exportModuleVersionIds`, `isActive` (only returns `id`, `name`, `isActive`, `graphDefinition`).** `PipelineEditor` reads these fields from `api.getPipeline(id)` and shows blank selectors on load of an existing pipeline. | BLOCKER | Extend `PipelineResource.get` and `list` responses to include all pipeline fields the frontend needs. |
| P2 | **`PipelineEditor` uses module IDs as `moduleVersionId` values in `graphDefinition` nodes.** The pipeline node schema requires `moduleVersionId` (a module *version* UUID), but the editor `importModules` dropdown options have `value={m.id}` (the *module* UUID). When the pipeline executes, it will look up a non-existent module version. | BLOCKER | The editor must list module versions (via `GET /modules/{id}/versions`), not modules, in its dropdowns. Or the backend must accept module IDs and resolve the latest active version at runtime. |
| P3 | **`PipelineEditor.handleRun` still calls `alert()`** on success/failure. | HIGH | Replace with inline toast. |
| P4 | **`PipelineResource.trigger` (`POST /pipelines/{id}/run`) is not `@Transactional`** — the `run.persist()` call will fail silently outside a transaction in many JPA configurations. | HIGH | Add `@Transactional` to the trigger method. |
| P5 | **`PipelineResource.getRuns` uses `PipelineRun.findByPipelineId(UUID)` which is a Panache static helper** — the same pattern that caused `This method is normally automatically overridden...` errors elsewhere. | HIGH | Replace with `em.createQuery(...)` pattern. |
| P6 | **The runs response from `GET /pipelines/{id}/runs` omits `triggerType`, `startedAt`, `completedAt`, `errorMessage`** — only `id`, `status`, `startedAt` are returned. The `PipelineRun` type in the frontend expects all of them. | LOW | Extend the runs map response. |

---

### 5. Connectors

| # | Gap | Priority | Notes |
|---|-----|----------|-------|
| C1 | **`ConnectorResource` tenant ID is read from query param `tenantId` but is not validated as a UUID** before use in `UUID.fromString` for list/create — an invalid tenant UUID will throw an unhandled 500. | HIGH | Already partially handled for direction/type; add the same UUID validation guard for `tenantId`. |
| C2 | **`ConnectorResource.get` (GET `/connectors/{id}`) has no authentication or tenancy check** — any authenticated user can read any connector regardless of tenant. | HIGH | Add tenant ownership check: `connector.tenant.id` must match the caller's accessible tenants. |
| C3 | **Config masking runs at read time but the frontend edit form sends an empty config** when the user edits a connector without filling in the config field, which would overwrite the stored config with an empty object `{}`. | HIGH | Backend `PATCH /connectors/{id}` should ignore/skip a missing config key (already the case for `null`), but it should also treat an empty object `{}` as "no change" rather than overwriting. |

---

### 6. Alerts

| # | Gap | Priority | Notes |
|---|-----|----------|-------|
| AL1 | **`AlertResource` has no `PATCH` endpoints for channels or rules** (update/enable/disable). The frontend Alerts page has no edit flow for rules or channels — only create and delete. | HIGH | Add `PATCH /alerts/channels/{id}` and `PATCH /alerts/rules/{id}` for at minimum name/enabled/config updates. |
| AL2 | **`AlertResource.createRule` stores `condition` as a raw `toString()` of the request object** (e.g., `{field=temperature, operator=>, value=100}`), not as JSON. When the frontend sends a map, `conditionRaw.toString()` produces Kotlin map notation. | BLOCKER | Serialize `conditionRaw` to JSON using `ObjectMapper` before storing. |
| AL3 | **`AlertDispatcher` exists but has no integration with `AlertRule.condition`** — no dispatcher logic calls conditions against pipeline output. Alerts are purely static records; the evaluation engine is unimplemented. | HIGH | Implement `AlertDispatcher.evaluate(run, data)` that queries enabled rules for the pipeline's tenant and evaluates their conditions. Wire into `PipelineConsumer`. |

---

### 7. Tenants

| # | Gap | Priority | Notes |
|---|-----|----------|-------|
| T1 | **`TenantResource` has no authentication or RBAC checks on any endpoint.** Any authenticated user can list, create, update, or delete tenants. Only the frontend guards the Tenants page via `user?.superadmin`. | BLOCKER | Add superadmin/`manage:tenant` checks to `TenantResource`. |
| T2 | **`TenantResource.create` allows creating a second root tenant** only if called with no `parentId` and the check for existing root tenants is correct, but there is no role seeding for new sub-tenants created via the API directly (only `TenantService.createTenant` calls `createDefaultRoles`, while `TenantResource.create` uses raw `em.persist` without calling the service). New sub-tenants have no roles, so their memberships cannot be created from the frontend. | BLOCKER | `TenantResource.create` must call `tenantService.createDefaultRoles(tenant)` after persisting. |
| T3 | **`Tenants.tsx` still uses `alert()` and `confirm()`** for delete guard and error. | HIGH | Replace with inline toast/confirm pattern. |
| T4 | **`TenantResource.list` returns only root tenants (`WHERE t.parent IS NULL`).** `Tenants.tsx` then calls `getTenantChildren` per root tenant. There is no recursive depth — grandchild tenants never appear in the UI. | LOW | Either return the full tree recursively from `GET /tenants`, or add `?recursive=true`. |

---

### 8. Data Explorer

| # | Gap | Priority | Notes |
|---|-----|----------|-------|
| D1 | **`DataResource` builds raw SQL queries with string interpolation from user-supplied `table`, `start`, `end`, `limit` parameters** — this is a SQL injection vulnerability. The `tableName` is partially sanitised via `sanitizeTableName`, but `start`, `end`, and `limit` are interpolated directly. | BLOCKER | Use parameterised queries or at minimum validate that `start`/`end` are ISO timestamps and `limit` is an integer before interpolation. |
| D2 | **`DataResource.queryData` tries to query `tenant_{tenantId}_measurements` using H2 in tests**, but H2 doesn't support `TIMESTAMPTZ`; the create-table query will fail on test environments. The production path (PostgreSQL/TimescaleDB) is fine, but end-to-end tests will break. | LOW | Use Flyway conditional DDL or skip the ensure-table call in the H2 test profile. |
| D3 | **The "short table name" logic in `Data.tsx`** correctly strips the `tenant_{uuid}_` prefix for display, but passes the short name to `api.deleteTable` and `api.queryData` as the `table` param. The backend builds `tenant_{tenantId}_{table}` from the param, so this round-trips correctly — but only if the frontend-computed prefix matches the backend format exactly. Any UUID with uppercase letters will cause a mismatch (UUIDs from some databases are uppercase). | LOW | Normalise UUID to lowercase consistently on both sides. |

---

### 9. Roles

| # | Gap | Priority | Notes |
|---|-----|----------|-------|
| R1 | **`RoleResource` has no RBAC guard on `POST /roles` (create) or `DELETE /roles/{id}`** — any authenticated user can create or delete roles. | HIGH | Add `manage:tenant` ability check on create/delete/update. |
| R2 | **The default roles seeded in `V1__Initial_schema.sql` use ability strings like `module:read`, `pipeline:write`, etc., but `SecurityUtils.hasAbility` checks for strings like `read:user`, `write:user`, `manage:tenant`.** These are two different namespacing conventions. The RBAC gate on `AuthUserResource` requires `read:user` but no default role grants it — only `module:read` is seeded. This means the Users page 403s for everyone except superadmin.** | BLOCKER | Standardise ability strings across the codebase. Either update all resource ability checks to use `module:read` / `pipeline:write` convention, or update the seeded roles to use `read:user` / `write:user` convention. Then update `TenantService.createDefaultRoles` to match. |
| R3 | **`RoleResource.create` stores `abilities` as `it.toString()` (a Kotlin list toString like `[manage:tenant, read:user]`)** instead of a JSON array string. The role ability checks later do string contains which might accidentally match partial strings. | HIGH | Serialize `abilities` to JSON array string using `ObjectMapper` in `RoleResource.create`. |

---

### 10. Frontend — Cross-cutting

| # | Gap | Priority | Notes |
|---|-----|----------|-------|
| F1 | **No first-run / setup flow in the UI.** `POST /auth/setup` is the only way to create the initial admin user and root tenant, but there is no `/setup` page or route. New deployments require calling the API directly (e.g., via curl). | HIGH | Add a `/setup` route that calls `api.register` (or a dedicated setup call) when no users exist yet. |
| F2 | **The `401` interceptor in `api.ts` redirects to `/login` on every 401.** If the setup endpoint returns 401 (it doesn't require auth, but the interceptor fires on any 401 response from any call), users will be stuck in a redirect loop on first boot. | HIGH | Exempt the setup/register route from the 401-redirect interceptor. |
| F3 | **`/api/register` in `api.ts` calls `POST /auth/register` which does not exist** (only `/auth/setup` and `/auth/login`). Clicking any "register" link will 404. | BLOCKER | Wire `api.register` to `/auth/setup` for first-run, do not add a `/auth/register` endpoint (no self registration). |
| F4 | **`PipelineEditor` and `ModuleEditor` navigate to a pipeline/module ID route immediately after creating a new item**, before the server response is confirmed. If save fails, the user is left on a broken route. | LOW | Wait for `navigate()` until after the create call resolves successfully. (Already done for pipelines; ModuleEditor redirects on click of "Back".) |
| F5 | **No loading/error boundary at the app level.** If an API call fails during initial load (e.g., network error), all pages silently show empty state with no indication of a network problem. | LOW | Add an `ErrorBoundary` wrapper or a global error state that shows a "Failed to connect to API" banner. |
| F6 | **The `Tenants` page is only visible to `user?.superadmin`, but this flag is derived from the JWT which is set correctly only for the root-tenant admin.** Any regular Tenant Admin of a sub-tenant cannot access tenant management even though sub-tenant management should be permitted. | LOW | Revisit the `user.superadmin` check — perhaps show the page to anyone with `manage:tenant` ability. |

---

### 11. Pipeline Execution Engine

| # | Gap | Priority | Notes |
|---|-----|----------|-------|
| E1 | **`PipelineConsumer` and `DockerService` exist but are stub/incomplete.** Triggering a pipeline creates a `PipelineRun` record but does not actually execute any modules. The run stays in `PENDING` status forever. | HIGH | Implement at minimum: (a) update run to `RUNNING` on trigger, (b) call `DockerService` or a mock to execute the graph, (c) update run to `SUCCESS`/`FAILED` with metrics. |
| E2 | **The `PipelineRun.trigger` endpoint is `POST /pipelines/{id}/run` and is not `@Transactional`** (covered in P4). The `run.persist()` call happens outside a transaction. | HIGH | Add `@Transactional`. |
| E3 | **`IngestResource` (`POST /api/v1/ingest/{pipelineId}`) and `WebSocketIngestResource` route data to Kafka but `PipelineConsumer` is not connected** (confirmed by the SRMSG00207 warning at startup: "SubscriberMethod has no upstream"). Data pushed via ingest arrives in Kafka but is never consumed by the pipeline. | HIGH | Wire `PipelineConsumer` channel to the correct Kafka topic or fix the messaging config. |

---

### 12. Database Schema Mismatches

| # | Gap | Priority | Notes |
|---|-----|----------|-------|
| S1 | **`users` table in `V1__Initial_schema.sql` has `UNIQUE(tenant_id, email)` constraint**, but the `User` entity maps `email` with `unique = true` (column-level unique). The combination means a user's email must be unique globally (JPA constraint), which is correct for the auth-user model, but the SQL constraint is `UNIQUE(tenant_id, email)` — a weaker constraint that allows the same email in different tenants. When two tenants try to register the same email, JPA will reject it but the DB would allow it. | LOW | Align: either drop the compound unique constraint in the SQL, or change the JPA annotation to match. The JPA `unique=true` (global uniqueness) is the correct one for the auth-user model. |
| S2 | **`memberships` SQL column is `user_id`** but the JPA `Membership` entity maps `@JoinColumn(name = "auth_user_id")` (or similar name — needs verification against the entity definition). If the column names don't match, Hibernate will fail to map the relationship. | BLOCKER | Verify `Membership` entity `@JoinColumn` name matches the SQL `user_id` column in the `memberships` table. Add a Flyway migration if a column rename is needed. |
| S3 | **`code_modules.module_type` column CHECK constraint uses lowercase** (`'import', 'processing', 'export', 'alert'`) but the JPA `ModuleType` enum is uppercase (`IMPORT`, etc.) with `EnumType.STRING`. PostgreSQL CHECKs are case-sensitive, so inserting `IMPORT` will fail the constraint. | BLOCKER | Fix the CHECK constraint in the migration (or add a `V2` migration) to use uppercase values, OR add `@Column(name="module_type")` with a converter to lowercase. |
| S4 | **`alert_channels.channel_type` and `alert_history.severity`/`status` CHECK constraints use lowercase**, same issue as S3. The JPA enums are uppercase. | BLOCKER | Same fix as S3. |
| S5 | **`pipeline_runs.status` and `trigger_type` CHECK constraints use lowercase** (`'pending', 'running'`, etc.) but `PipelineRun` entity uses uppercase enums. | BLOCKER | Same fix as S3. |

---

### Priority Order for Implementation

**Must fix before any user can log in and use the system:**
1. S2 — `memberships.user_id` vs JPA column name  
2. S3–S5 — enum case mismatch in DB CHECK constraints  
3. A4 — password hash not BCrypt-wrapped on user create  
4. A3 — `tenantId` serialised as `"null"` string  
5. R2 — ability string convention mismatch (everyone 403s)  
6. T2 — new tenants missing default roles  
7. A1/F3 — no `/auth/setup` frontend route  

**Must fix before core features work:**
8. P1 — pipeline get/list missing fields  
9. P2 — module IDs used where module version IDs are needed  
10. M1 — module get missing versions  
11. AL2 — alert condition stored as Kotlin map notation  
12. T1 — tenant endpoints unprotected  
13. P4/P5 — pipeline trigger not transactional; getRuns uses static Panache  

**Should fix before release:**
14. U1–U3 — user management 403s for non-superadmin  
15. A2 — no tenant-switching mechanism  
16. AL1 — no rule/channel update endpoints  
17. AL3 — alert evaluation engine stub  
18. E1–E3 — pipeline execution engine incomplete  
19. R1, R3 — role endpoint missing guards and ability serialisation  
20. C2, C3 — connector tenancy check and empty config overwrite  
21. D1 — SQL injection in data queries  
22. F1, F2 — setup page and interceptor loop  
23. A5, M2, M3, P3, T3 — remaining `alert()`/`confirm()` calls  

---

## Architecture

### High-Level System Design

```text
┌────────────────────────────────────────────────────────────────────────────────┐
│                              DataTurntable                                      │
│                                                                                 │
│  ┌─────────────────────────────────────────────────────────────────────────┐   │
│  │                    Web Application (React + TypeScript)                  │   │
│  │   Code Editor │ Pipeline Builder │ Tenant Manager │ Alert Config         │   │
│  └──────────────────────────────────┬──────────────────────────────────────┘   │
│                                     │                                           │
│  ┌──────────────────────────────────▼──────────────────────────────────────┐   │
│  │              Management API (Quarkus/Kotlin - /api/internal/v1/)         │   │
│  │   Auth/RBAC │ Tenant Mgmt │ Module Registry │ Pipeline Mgmt             │   │
│  └──────────────────────────────────┬──────────────────────────────────────┘   │
│                                     │ Kafka                                     │
│  ┌──────────────────────────────────▼──────────────────────────────────────┐   │
│  │                    Ingestion Layer (/api/v1/ + MQTT + WS + UDP)          │   │
│  │   Exposes protocol endpoints; routes incoming data into pipeline topics  │   │
│  └──────────────────────────────────┬──────────────────────────────────────┘   │
│                                     │                                           │
│  ┌──────────────────────────────────▼──────────────────────────────────────┐   │
│  │              Core Engine Cluster (Quarkus Kafka Consumers)               │   │
│  │                                                                          │   │
│  │   Pipeline graph — each node is a module container; edges are topics:    │   │
│  │                                                                          │   │
│  │   [IMPORT] ──► [PROCESSING] ──┬──► [ALERT]                              │   │
│  │                               │                                          │   │
│  │   [IMPORT] ──► [PROCESSING] ──┤    [PROCESSING] (combine/fan-in)        │   │
│  │   (2nd stream) ───────────────┘         │                               │   │
│  │                                         ├──► [EXPORT]                   │   │
│  │                                         └──► [EXPORT]  (fan-out)        │   │
│  └──────────────────────────────────┬──────────────────────────────────────┘   │
│                                     │ Docker API                                │
│  ┌──────────────────────────────────▼──────────────────────────────────────┐   │
│  │              User Python Modules (Docker Containers)                     │   │
│  │   IMPORT Module │ PROCESSING Module │ ALERT Module │ EXPORT Module       │   │
│  └──────────────────────────────────────────────────────────────────────────┘  │
│                                                                                 │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐          │
│  │   Kafka     │  │TimescaleDB  │  │ PostgreSQL  │  │   MQTT      │          │
│  │  Cluster    │  │ (time-series)│  │ (metadata) │  │   Broker    │          │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘          │
└────────────────────────────────────────────────────────────────────────────────┘
```
### Technology Stack

| Component | Technology | Notes |
| --------- | ---------- | ----- |
| **Core API** | Quarkus (Kotlin) | High performance, low memory |
| **Core Engine** | Quarkus + Kafka Consumer API | Graph-based pipeline execution |
| **User Modules** | Python 3.11 in Docker | Isolated execution |
| **Message Queue** | Apache Kafka (KRaft) | Persistent, fault-tolerant |
| **Time-series DB** | TimescaleDB | Primary data storage |
| **Metadata DB** | PostgreSQL | Users, pipelines, module source, config |
| **Cache/State** | Redis | Session, dedup, rate limiting |
| **MQTT** | Eclipse Mosquitto | Inbound (subscribed by platform) + outbound |
| **Frontend** | React + TypeScript | Tailwind CSS, Monaco Editor, Vite |

---

## Connector & Module System

### Import (IMPORT) — Inbound Data

An IMPORT module defines how data enters a pipeline. There are two patterns:

**Passive (push from external):** The platform exposes protocol endpoints; external systems push data in.

- REST: `POST /api/v1/ingest/{pipelineId}` (no schema enforced)
- MQTT: Platform subscribes to a configured topic on the bundled or external broker
- WebSocket: Platform provides a WS endpoint; external clients connect and stream frames
- UDP: Platform opens a UDP socket on a configurable port

**Active (pull by platform):** The IMPORT Python module runs in a container and periodically fetches data from an external source (REST poll, database query, file watch, etc.).

### Processing (PROCESSING) — Transform / Filter / Enrich

A PROCESSING module receives data from any upstream node, transforms it, and emits to one or more downstream nodes. Combination (fan-in) is implemented as a PROCESSING module that receives from multiple upstream topics.

### Alert (ALERT) — Condition Evaluation

A dedicated ALERT module evaluates conditions on the data stream and fires alerts via configured channels. It does not modify or forward the data stream — it is a side-effect node. Both built-in rule types (threshold, rate-of-change, time window, absence) and custom Python logic are supported.

### Export (EXPORT) — Outbound Data

An EXPORT module sends data out of the platform. Two patterns:

**Active (push to external):** Module pushes to an external endpoint.

- REST: POST to external API
- MQTT: Publish to external broker topic
- WebSocket: Push frames to connected clients
- UDP: Send datagrams to configured target

**Passive (expose pull endpoint):** The platform exposes an endpoint that external systems poll (e.g. Grafana SimpleJSON data source, ThingsBoard MQTT pull, query endpoint).

---

## Pipeline Topology (DAG)

Pipelines are directed acyclic graphs. Nodes are module instances; edges are Kafka topics.

### Fan-out Example

```text
[IMPORT: MQTT] ──► [PROCESSING: Normalise] ──┬──► [ALERT: Threshold]
                                              ├──► [EXPORT: TimescaleDB]
                                              └──► [EXPORT: MQTT Dashboard]
```
### Fan-in Example

```text
[IMPORT: MQTT sensor A] ──► [PROCESSING: Parse A] ──┐
                                                      ├──► [PROCESSING: Merge] ──► [EXPORT: DB]
[IMPORT: HTTP feed B]   ──► [PROCESSING: Parse B] ──┘
```
### Kafka Topic Per Edge

Each directed edge in the graph has its own Kafka topic:

```text
dataturntable.pipelines.{pipelineId}.nodes.{sourceNodeId}.to.{targetNodeId}
```
System-level topics:

| Topic | Purpose |
| ----- | ------- |
| `dataturntable.pipelines.{id}.input` | Pipeline entry point (ingestion layer → first import node) |
| `dataturntable.pipelines.{id}.nodes.{src}.to.{dst}` | Edge between two nodes |
| `dataturntable.alerts.{tenantId}` | Alert events |
| `dataturntable.system.commands` | API → engine commands |
| `dataturntable.dlq.{pipelineId}` | Dead-letter queue |

---

## Database Schema

### Core Tables (PostgreSQL)

```sql
-- Tenants (strict tree: each node has exactly one parent, except the root which has parent_id = NULL)
-- Cycles and multiple parents are forbidden; enforced in application logic.
CREATE TABLE tenants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    parent_id UUID REFERENCES tenants(id),   -- NULL only for the single root tenant
    settings JSONB DEFAULT '{}',
    quota JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Roles (tenant-scoped)
CREATE TABLE roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    abilities JSONB NOT NULL DEFAULT '[]',
    is_default BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(tenant_id, name)
);

-- Login identities (platform-wide, one record per unique email)
-- Superadmin = auth_user who holds a TenantAdmin membership in the root tenant.
-- Root-tenant admins cannot simultaneously hold memberships in sub-tenants.
CREATE TABLE auth_users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,   -- BCrypt of SHA-256 pre-hash
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Per-tenant memberships (max 1 per auth_user per tenant)
CREATE TABLE tenant_memberships (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    auth_user_id UUID REFERENCES auth_users(id) ON DELETE CASCADE,
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    role_id UUID REFERENCES roles(id),
    abilities JSONB DEFAULT '[]',          -- per-membership ability overrides
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(auth_user_id, tenant_id)        -- one membership per (user, tenant)
);

-- Protocol Connectors (tenant-scoped, named interface definitions)
-- Import/Export module nodes reference a connector by connectorId in graph_definition.
CREATE TABLE connectors (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    connector_type VARCHAR(50) NOT NULL
        CHECK (connector_type IN ('MQTT', 'REST', 'WEBSOCKET', 'UDP', 'FILE', 'CUSTOM')),
    direction VARCHAR(20) NOT NULL
        CHECK (direction IN ('INPUT', 'OUTPUT', 'BOTH')),
    config JSONB NOT NULL,   -- type-specific: host, port, topic, credentials, etc.
    enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(tenant_id, name)
);

-- Code Modules
CREATE TABLE code_modules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    module_type VARCHAR(50) NOT NULL
        CHECK (module_type IN ('IMPORT', 'PROCESSING', 'ALERT', 'EXPORT')),
    description TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    created_by UUID REFERENCES auth_users(id),
    UNIQUE(tenant_id, name, module_type)
);

-- Module Versions
CREATE TABLE module_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    module_id UUID REFERENCES code_modules(id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    version_tag VARCHAR(100),
    code TEXT NOT NULL,
    requirements TEXT DEFAULT '',
    created_at TIMESTAMPTZ DEFAULT NOW(),
    created_by UUID REFERENCES auth_users(id),
    UNIQUE(module_id, version)
);

-- Pipelines (graph stored as JSON graph definition; arrays kept for MVP compat)
-- graph_definition nodes include connectorId for IMPORT/EXPORT nodes:
--   { "id": "n1", "type": "IMPORT", "moduleVersionId": "uuid", "connectorId": "uuid" }
CREATE TABLE pipelines (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    graph_definition JSONB,              -- full DAG: nodes + edges
    import_module_version_id UUID REFERENCES module_versions(id),
    processing_module_version_ids UUID[] DEFAULT '{}',
    export_module_version_ids UUID[] DEFAULT '{}',
    kafka_consumer_group VARCHAR(255) UNIQUE,
    is_active BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    created_by UUID REFERENCES auth_users(id)
);

-- Pipeline Runs
CREATE TABLE pipeline_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pipeline_id UUID REFERENCES pipelines(id) ON DELETE CASCADE,
    status VARCHAR(50) DEFAULT 'PENDING',  -- PENDING, RUNNING, SUCCESS, FAILED, CANCELLED
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    error_message TEXT,
    UNIQUE(pipeline_id, started_at)
);

-- Alert Channels
CREATE TABLE alert_channels (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    channel_type VARCHAR(50) NOT NULL,  -- MQTT, WEBHOOK, REST, EMAIL, SLACK
    config JSONB NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Alert Rules
CREATE TABLE alert_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    pipeline_id UUID REFERENCES pipelines(id) ON DELETE SET NULL,
    channel_id UUID REFERENCES alert_channels(id),
    enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Alert History
CREATE TABLE alert_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    rule_id UUID REFERENCES alert_rules(id),
    severity VARCHAR(50),       -- info, warning, critical
    message TEXT,
    payload JSONB,
    status VARCHAR(50),         -- PENDING, SENT, FAILED, RETRY
    sent_at TIMESTAMPTZ DEFAULT NOW()
);
```
### Indexes

```sql
CREATE INDEX idx_tenant_parent ON tenants(parent_id);
CREATE INDEX idx_memberships_auth_user ON tenant_memberships(auth_user_id);
CREATE INDEX idx_memberships_tenant ON tenant_memberships(tenant_id);
CREATE INDEX idx_connectors_tenant ON connectors(tenant_id);
CREATE INDEX idx_modules_tenant ON code_modules(tenant_id);
CREATE INDEX idx_pipelines_tenant ON pipelines(tenant_id);
CREATE INDEX idx_pipeline_runs_pipeline ON pipeline_runs(pipeline_id, started_at DESC);
```
### Time-Series Data (TimescaleDB)

```sql
CREATE TABLE readings (
    time        TIMESTAMPTZ NOT NULL,
    tenant_id   UUID NOT NULL,
    pipeline_id UUID NOT NULL,
    metric      TEXT NOT NULL,
    value       DOUBLE PRECISION,
    tags        JSONB,
    raw         JSONB
);
SELECT create_hypertable('readings', 'time');
CREATE INDEX ON readings(tenant_id, metric, time DESC);
```
---

## Module Contracts (Python)

All user modules run inside Docker containers. The platform injects helper functions (`alert_fn`, `query_fn`) via environment/sidecar.

### IMPORT Module

```python
class ImportModule:
    """Fetches or receives data from external sources."""

    def connect(self, config: dict):
        """Initialise connection to the data source."""
        pass

    def fetch(self) -> dict | pd.DataFrame:
        """Fetch one batch of data.

        Returns:
            dict: single record
            pd.DataFrame: batch of records
        """
        pass

    def disconnect(self):
        """Clean up connection."""
        pass
```
### PROCESSING Module

```python
class ProcessingModule:
    """Transforms, filters, or enriches data."""

    def process(self, data: dict | pd.DataFrame,
                query_fn=None) -> dict | pd.DataFrame | None:
        """Transform data.

        Return None to drop the message.
        Raise an exception to route to DLQ.
        """
        return data
```
### ALERT Module

```python
class AlertModule:
    """Evaluates conditions and dispatches alerts.

    Does NOT modify or forward the data stream.
    Runs as a side-effect node in the pipeline graph.
    """

    def evaluate(self, data: dict | pd.DataFrame,
                 alert_fn=None,
                 query_fn=None) -> bool:
        """Evaluate alert condition.

        Call alert_fn(severity, message, payload) to fire an alert.
        Return True if alert fired, False otherwise.

        Example:
            if data['value'] > 80:
                alert_fn(severity='critical',
                         message='Temperature exceeded 80°C',
                         payload=data)
                return True
            return False
        """
        return False
```
### EXPORT Module

```python
class ExportModule:
    """Sends data to external destinations or exposes pull endpoints."""

    def connect(self, config: dict):
        """Initialise connection to the destination."""
        pass

    def send(self, data: dict | pd.DataFrame):
        """Push data to the external system."""
        pass

    def disconnect(self):
        """Clean up connection."""
        pass
```
### Module Config Structure

The platform resolves the referenced connector and injects its config into the module at runtime:

```python
# Injected into module __init__ config parameter
{
    "connector": {
        # Resolved from connectors.config for the referenced connectorId.
        # MQTT:      {"broker": "mqtt://host:1883", "topic": "sensors/#", "clientId": "..."}
        # REST:      {"baseUrl": "https://...", "method": "POST", "headers": {...}}
        # WEBSOCKET: {"url": "wss://...", "path": "/stream"}
        # UDP:       {"host": "...", "port": 5005}
        # FILE:      {"path": "/data/input.csv", "format": "csv"}
        # CUSTOM:    user-defined key/value pairs
    },
    "pipeline_id": "uuid",
    "tenant_id": "uuid",
    "node_id": "uuid"
}
```
---

## Execution Flow

### Pipeline Start

```text
1. External system pushes data to an ingest endpoint:
      - POST /api/v1/ingest/{pipelineId}
      - MQTT topic: dataturntable/ingest/{pipelineId}
      - WebSocket frame / UDP datagram
   OR
   IMPORT module container polls external source and emits data

2. Ingestion layer publishes to Kafka:
      dataturntable.pipelines.{id}.input

3. Pipeline Engine reads message, resolves graph:
      For each outgoing edge from the entry node, publish to edge topic.

4. Each node's Kafka consumer calls its module container:
      - PROCESSING: transforms data, emits to all downstream edge topics
      - ALERT: evaluates condition, fires alert if condition met (does not forward)
      - EXPORT: pushes to external system or stores data

5. On error: message routed to DLQ topic.
6. PipelineRun record updated to SUCCESS / FAILED.
```
### Container Lifecycle

| Mode | Description | Use Case |
| ---- | ----------- | -------- |
| **Ephemeral** | New container per run | Batch imports, one-off processing |
| **Persistent** | Long-running container | Live streaming (MQTT → process → export) |

---

## Permission System

### Ability-Based Access Control (ABAC)

| Ability | Description |
| ------- | ----------- |
| `module:read` | View code modules |
| `module:write` | Create/edit code modules |
| `module:delete` | Delete code modules |
| `pipeline:read` | View pipelines |
| `pipeline:write` | Create/edit pipelines |
| `pipeline:execute` | Run pipelines |
| `data:read` | Query time-series data |
| `data:write` | Insert data |
| `data:export` | Export data |
| `alert:read` | View alerts |
| `alert:configure` | Configure alert channels/rules |
| `tenant:users` | Manage tenant users |
| `tenant:manage` | Manage tenant settings |
| `user:manage` | System user management |

### Default Roles

| Role | Abilities |
| ---- | --------- |
| **Viewer** | `module:read`, `pipeline:read`, `data:read`, `alert:read` |
| **Editor** | Viewer + `module:write`, `pipeline:write`, `pipeline:execute`, `data:write` |
| **TenantAdmin** | Editor + `alert:configure`, `tenant:users`, `tenant:manage` |
| **Superadmin** | All abilities + system management |

### Tenant Hierarchy

```text
Superadmin (platform-wide)
  └── Tenant A (TenantAdmin)
      ├── Sub-Tenant A1 (TenantAdmin)
      │   └── Sub-Tenant A1a
      └── Sub-Tenant A2 (TenantAdmin)
  └── Tenant B (TenantAdmin)
```
Permissions are inherited from parent tenants and can be overridden at any level.

---

## API Endpoints

### Authentication

| Method | Endpoint | Description |
| ------ | -------- | ----------- |
| POST | /auth/login | Login, receive JWT |
| POST | /auth/setup | First-run setup (creates root tenant + admin) |
| GET | /auth/me | Current user info |

### Tenants

| Method | Endpoint | Description |
| ------ | -------- | ----------- |
| GET | /tenants | List root tenants |
| POST | /tenants | Create tenant |
| GET | /tenants/{id} | Get tenant |
| PATCH | /tenants/{id} | Update tenant |
| DELETE | /tenants/{id} | Delete tenant |
| GET | /tenants/{id}/children | List child tenants |

### Auth Users, Memberships & Roles

| Method | Endpoint | Description |
| ------ | -------- | ----------- |
| GET | /auth-users | List auth users |
| POST | /auth-users | Create auth user |
| GET | /auth-users/{id} | Get auth user |
| PATCH | /auth-users/{id} | Update auth user |
| DELETE | /auth-users/{id} | Delete auth user |
| GET | /tenants/{tenantId}/memberships | List tenant memberships |
| POST | /tenants/{tenantId}/memberships | Create tenant membership |
| PATCH | /tenants/{tenantId}/memberships/{id} | Update tenant membership |
| DELETE | /tenants/{tenantId}/memberships/{id} | Delete tenant membership |
| GET | /roles | List roles |

### Modules

| Method | Endpoint | Description |
| ------ | -------- | ----------- |
| GET | /modules | List modules |
| POST | /modules | Create module |
| GET | /modules/{id} | Get module |
| DELETE | /modules/{id} | Delete module |
| GET | /modules/{id}/versions | List versions |
| POST | /modules/{id}/versions | Create version |

### Pipelines

| Method | Endpoint | Description |
| ------ | -------- | ----------- |
| GET | /pipelines | List pipelines |
| POST | /pipelines | Create pipeline |
| GET | /pipelines/{id} | Get pipeline |
| PATCH | /pipelines/{id} | Update pipeline |
| DELETE | /pipelines/{id} | Delete pipeline |
| POST | /pipelines/{id}/run | Trigger pipeline |
| GET | /pipelines/{id}/runs | List runs |

### Data

| Method | Endpoint | Description |
| ------ | -------- | ----------- |
| GET | /data/{tenantId}/query | Query time-series |
| POST | /data/{tenantId}/insert | Insert data |
| GET | /data/{tenantId}/tables | List tables |
| DELETE | /data/{tenantId}/tables/{table} | Drop table |

### Alerts

| Method | Endpoint | Description |
| ------ | -------- | ----------- |
| GET | /alerts/channels | List channels |
| POST | /alerts/channels | Create channel |
| GET | /alerts/rules | List rules |
| POST | /alerts/rules | Create rule |
| GET | /alerts/history | Alert history |

All paths are prefixed with `/api/internal/v1/`.

---

## Deployment

### Development (Docker Compose)

```yaml
services:
  postgresql:
    image: timescale/timescaledb:latest-pg16
    environment:
      POSTGRES_PASSWORD: dataturntable

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1

  redis:
    image: redis:7-alpine

  mosquitto:
    image: eclipse-mosquitto:2
    ports:
      - "1883:1883"

  backend:
    build: ./backend
    ports:
      - "8080:8080"

  frontend:
    build: ./frontend
    ports:
      - "3000:3000"
```
### Production (Kubernetes)

- Kafka cluster (3+ brokers, Strimzi operator)
- TimescaleDB (CloudNativePG or managed)
- PostgreSQL
- Redis cluster
- API deployment (HPA, 2+ replicas)
- Module containers spawned as Kubernetes Jobs
- Ingress with TLS termination

---

## Implementation Phases

| Phase | Component | Priority |
| ----- | --------- | -------- |
| 1 | DB Schema + Migrations | High |
| 2 | Auth + RBAC | High |
| 3 | Tenant Management | High |
| 4 | Module CRUD + Versioning | High |
| 5 | Pipeline CRUD | High |
| 6 | Kafka Consumer Engine | High |
| 7 | Docker Manager (module execution) | High |
| 8 | Alert System (ALERT module type + channels) | Medium |
| 9 | Data API | Medium |
| 10 | Frontend | Medium |
| 11 | Ingestion Layer (REST + MQTT + WS + UDP) | Medium |
| 12 | Full graph pipeline topology (DAG) | Medium |
| 13 | Deployment configs (K8s, Helm) | Medium |

---

## Security Considerations

1. **User code isolation**: All Python modules run in isolated Docker containers (no-root, read-only FS, no network by default)
2. **No fixed data schema**: Modules define their own data format; the platform does not enforce structure
3. **RBAC**: Fine-grained ability-based permissions scoped to tenant hierarchy
4. **Tenant isolation**: Data access restricted to tenant tree
5. **Secrets**: Alert channel credentials stored encrypted in the database
6. **Password hashing**: SHA-256 (client) + BCrypt (server)
7. **Deduplication**: System-generated duplicates (Kafka at-least-once retries) are suppressed; user data duplicates are preserved unless a deduplication PROCESSING module is added to the pipeline

---

## Performance Optimisations

1. **Kafka**: 3+ broker cluster, replication factor 3
2. **TimescaleDB**: Hypertables with proper chunking and compression
3. **Sequential node processing**: 1 consumer per pipeline node (prevents race conditions within a node)
4. **Parallel pipelines**: Each pipeline has its own consumer group; pipelines run independently
5. **Connection pooling**: PostgreSQL and Redis
6. **Batch processing**: DataFrame support for bulk operations

---

## Monitoring & Observability

- **Metrics**: Prometheus (`/q/metrics`) + Grafana
- **Logging**: Structured JSON logs; ELK Stack or Loki
- **Tracing**: Jaeger or Tempo (planned)
- **Health checks**: `/q/health/ready` and `/q/health/live` (Quarkus SmallRye Health)
