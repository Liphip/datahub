# Production Source Reference

This is the maintained implementation map for all production Kotlin and TypeScript
sources. It documents the responsibility and collaboration boundary of every
source file. HTTP handler inputs, outputs, error statuses, and permissions are
specified in the [API reference](../api/README.md); data structures exposed to the
frontend are defined in `frontend/src/types/index.ts`.

## Reading function-level behavior

Kotlin resource functions are HTTP handlers when decorated with JAX-RS annotations;
their route and request contract belongs in the API reference. Lifecycle functions
accepting Quarkus startup/shutdown events are runtime hooks. Other public methods
are collaboration contracts between the units named below. Private `parse*`,
`require*`, `to*Dto`, and `find*` helpers respectively validate/convert input,
enforce access, map persistence entities, and perform the named lookup; their
containing unit owns their full behavior. This convention prevents duplicate,
often-stale comments while keeping every callable contract discoverable.

## Backend bootstrap and persistence

| Source | Responsibility and callable contract |
|---|---|
| `Main.kt` | Quarkus application entry point. |
| `RestApplication.kt` | Activates the JAX-RS application. |
| `PanacheBase.kt` | Shared Panache persistence base behavior. |
| `auth/User.kt` | User entity, membership lookup, ability calculation, tenant access, and user find/count queries. |
| `tenant/Tenant.kt` | Tenant entity and hierarchy/persistence helpers. |
| `tenant/Role.kt` | Role entity and ability definitions. |
| `tenant/Membership.kt` | User-to-tenant role assignment entity. |
| `module/CodeModule.kt` | Reusable module entity and module type metadata. |
| `module/ModuleVersion.kt` | Versioned module source/configuration entity. |
| `pipeline/Pipeline.kt` | Pipeline graph/configuration entity. |
| `pipeline/PipelineRun.kt` | Persisted pipeline execution state and result metadata. |
| `pipeline/PipelineRunHourlyStat.kt` | Aggregated run counts per pipeline/hour/status (OPS-07), populated by `RetentionCleanupService` as detail rows age out. |
| `pipeline/PipelineRunNodeLog.kt` | Per-node execution log entity. |
| `alert/AlertChannel.kt` | Alert delivery-channel entity and channel type enum. |
| `alert/AlertHistory.kt` | Alert attempt/history entity, severity/status enums, and recent-history lookup. |
| `connectors/Connector.kt` | Connector configuration entity and connector type/direction/status enums. |
| `connectors/BrokerCredential.kt` | Broker credential entity and credential lifecycle metadata. |
| `grafana/GrafanaSyncState.kt` | Persisted Grafana synchronization cursor/state. |
| `grafana/GrafanaInstanceConfig.kt` | Resolves target-specific Grafana configuration and validates whether it is usable. |
| `alert/AlertRuleState.kt` | Per rule node and series state of built-in alert rules (last value, window, cooldown). |
| `audit/AuditEvent.kt` | Append-only audit event entity; metadata is redacted before persistence. |
| `auth/ApiKey.kt` | Ingest API key entity: SHA-256 digest, tenant, granted `ingest:*` abilities, expiry, last use. |
| `authorization/Ability.kt` | Normalised permission token entity (`abilities` table). |
| `connectors/ConnectorPipelineMap.kt`, `connectors/ConnectorRuntimeLease.kt`, `data/DataTableRegistryRecord.kt` | Tables accessed with native SQL, mapped so the H2 test schema contains them. |
| `connectors/ConnectorRevision.kt` | Immutable encrypted configuration snapshot per connector change (CON-17). |
| `events/ConsumerDeadLetter.kt` | Record of a pipeline message the executor could not use. |
| `execution/Execution.kt`, `execution/ExecutionQuotaLock.kt` | Durable execution record and the lock row used while reserving execution capacity. |
| `metadata/MetadataEntities.kt` | Metadata types, objects, identifiers, attribute versions and relations with validity periods. |
| `pipeline/PipelineVersion.kt` | Immutable pipeline graph version referenced by runs (PIP-16). |
| `secrets/Secret.kt`, `secrets/SecretVersion.kt` | Secret and its encrypted, versioned values. |

## Management and ingestion API

Every resource validates caller identity and tenant/ability scope before mutation.
Routes, JSON payloads, response statuses, and examples are maintained in the API
reference; add a new route there in the same change as its handler.

| Source | Responsibility |
|---|---|
| `api/AuthResource.kt` | Login, initial setup/registration, current user, tenant-context listing and tenant switching. |
| `api/AuthUserResource.kt` | Tenant-scoped administrative user CRUD, password-hash handling, and membership checks. |
| `api/TenantResource.kt` | Tenant creation, hierarchy, defaults, diagnostics, and administrative mutation. |
| `api/RoleResource.kt` | Role and permission management endpoints. |
| `api/MembershipResource.kt` | Membership creation, listing, modification, and deletion endpoints. |
| `api/ModuleResource.kt` | Module and module-version management endpoints. |
| `api/PipelineResource.kt` | Pipeline graph CRUD, run triggering, run history, and run-detail endpoints. |
| `api/ConnectorResource.kt` | Connector CRUD, configuration validation, status, impact, and credential-facing endpoints. |
| `api/IngestResource.kt` | Public HTTP ingestion endpoint and payload routing. |
| `api/WebSocketIngestResource.kt` | HTTP resource that exposes WebSocket ingestion metadata/entry behavior. |
| `api/WebSocketEndpoint.kt` | WebSocket session lifecycle and message forwarding. |
| `api/DataResource.kt` | Tenant-schema table validation, data querying, summaries, inserts, table registration, and export. |
| `api/AlertResource.kt` | Alert-channel/rule CRUD and bounded delivery-history retrieval. |
| `api/MonitoringResource.kt` | Monitoring snapshot and status endpoints. |
| `api/GrafanaSyncResource.kt` | Explicit Grafana synchronization operations and state visibility. |
| `api/ApiErrorContract.kt` | Uniform error envelope (`code`, `message`, `details`), request ids and security response headers (API-10, NFR-S12). |
| `api/TransientFailureMapper.kt` | Maps lost database connections to `503` + `Retry-After` and constraint violations (SQLState 23xxx) to `409`/`400`. |
| `api/ApiKeyResource.kt` | Creates (shown once), lists and revokes ingest API keys (`secret:manage`). |
| `api/AuthorizationResource.kt` | `GET /authorization/explain`: why a user has or lacks a permission (IAM-15). |
| `api/EventDlqResource.kt` | Lists dead-lettered pipeline messages, platform administrators only; no requeue (PIP-13). |
| `api/AuditResource.kt` | Audit trail search, filter and CSV/JSON export under `audit:read` (AUD-07, AUD-08). |
| `api/IngestRejections.kt` | Counter for records and connections the ingest endpoints refuse (IMP-14). |
| `api/ExecutionResource.kt` | Submits, reads and cancels module executions through the execution service. |
| `api/MetadataResource.kt` | Metadata types, objects, upsert, CSV import, resolve and template installation (`metadata:read`/`metadata:write`). |
| `api/SecretResource.kt` | Creates and rotates secrets; exposes references only, values are write-only. |

## Services and application policy

| Source | Responsibility and key function family |
|---|---|
| `service/AuthService.kt` | Authenticates credentials, provisions setup users, creates JWTs, and resolves user context. |
| `service/TenantService.kt` | Seeds a new tenant's default role templates (Viewer/Editor/Tenant Admin). Tenant CRUD is `TenantResource`'s own; a same-shaped, unused service layer for it, `ModuleService.kt`, `UserService.kt` and the request/response DTOs only they used were removed as dead code. |
| `service/PipelineDataService.kt` | Tenant schema/table creation and parameterized pipeline-data access, under the tenant's own database role where available (`TenantDatabaseRoleService`, NFR-S8). |
| `service/TenantDatabaseRoleService.kt` | Provisions and switches into a per-tenant PostgreSQL role so cross-tenant access is refused by the database itself (NFR-S8/TEN-03). |
| `service/GrafanaSyncService.kt` | Builds and synchronizes Grafana resources for supported scopes. |
| `service/GrafanaProvisioningService.kt` | Provisions datasources/dashboards against a configured Grafana instance. |
| `alert/AlertDispatcher.kt` | Evaluates condition trees, observes cooldowns, records alert history, and dispatches per channel. |
| `logging/AuditLogger.kt` | Emits structured audit records for security-relevant changes. |
| `alert/AlertRuleEvaluator.kt` | Evaluates built-in ALERT node rules (threshold, rate of change, time window, absence) with cooldown. |
| `alert/AlertRuleScheduler.kt` | Periodically evaluates absence rules on every executor; each silence is reported once. |
| `alert/AlertChannelSecretReferenceResolver.kt` | Resolves channel secret references in memory only, for delivery. |
| `audit/AuditService.kt` | Writes audit events in the caller's transaction. |
| `authorization/AuthorizationGrantService.kt` | Replaces and normalises the permission tokens of roles, memberships and users. |
| `events/ConsumerDeadLetterService.kt` | Records and lists dead-lettered messages (redacted). |
| `metadata/MetadataService.kt` | Metadata upsert with as-of versioning, identifier resolution (including merge by another identifier), relations and enrichment lookups. |
| `pipeline/PipelineVersionService.kt` | Finds the version a run executes. |
| `secrets/SecretService.kt` | Encrypts, versions, rotates and resolves secrets. |
| `service/RetentionCleanupService.kt` | Deletes runs, alert history, audit events and readings past their configured age (control API only); folds deleted runs into `pipeline_run_hourly_stats` first (OPS-07). |

## Connector runtime

| Source | Responsibility |
|---|---|
| `connectors/ConnectorInterfaces.kt` | Common connection, reader, and writer contracts implemented by connector types. |
| `connectors/ConnectorManager.kt` | Starts/stops registered connector implementations and coordinates lifecycle. |
| `connectors/ConnectorRuntimeService.kt` | Binds persisted connectors to the active runtime role and reconciles their runtime state. |
| `connectors/ConnectorLeaseService.kt` | Uses PostgreSQL leases (heartbeat, expiry, fencing token) so one instance owns a connector runtime (NFR-R10). |
| `connectors/ConnectorStatusService.kt` | Records and exposes connection health/status changes. |
| `connectors/ConnectorMetrics.kt` | Creates and updates connector observability metrics. |
| `connectors/polling/ConnectorPollingService.kt` | Schedules polling connector reads and submits resulting records. |
| `connectors/http/HttpConnector.kt` | HTTP connector connection/read/write implementation. |
| `connectors/mqtt/MqttConnector.kt` | MQTT connector implementation and subscription/publication configuration. |
| `connectors/mqtt/MqttConnectionManager.kt` | Owns MQTT client connections and reconnection lifecycle. |
| `connectors/mqtt/MqttMessageRouter.kt` | Routes MQTT payloads to matching connector/pipeline targets. |
| `connectors/websocket/WebSocketConnector.kt` | WebSocket connector implementation. |
| `connectors/websocket/WebSocketConnectionManager.kt` | Owns outbound WebSocket sessions, reconnects, and message delivery. |
| `connectors/AfterCommit.kt` | Runs connection-manager updates after the transaction commits. |
| `connectors/ConnectorSecretReferenceResolver.kt` | Resolves connector secret references in runtime memory only. |
| `connectors/DestinationPolicy.kt` | Refuses loopback, private, link-local, multicast and metadata destinations unless allowed (NFR-S9). |
| `connectors/inbox/InboxPoller.kt` | Polls FILE/FTP/SFTP/EMAIL connectors, keeps the item ledger (`ConnectorInboxItem`), applies `minAge` and the post-read action. |
| `connectors/inbox/InboxSource.kt` | Source abstraction: list, read and finish items. |
| `connectors/inbox/DirectorySource.kt`, `FtpSource.kt`, `SftpSource.kt`, `ImapSource.kt` | Directory (sandboxed to the base dir), FTP/FTPS, SFTP (pinned host key) and IMAP sources. |
| `connectors/inbox/InboxParser.kt` | Turns files and attachments into records (CSV, JSON, JSON lines) or text/Base64 payloads. |

## Pipeline engine and observability

| Source | Responsibility |
|---|---|
| `engine/PipelineConsumer.kt` | Kafka consumer that executes incoming pipeline work and updates run outcomes. |
| `engine/DockerService.kt` | Creates, runs, observes, and removes Docker module containers. |
| `engine/WarmContainerPool.kt` | Maintains reusable module containers; acquire/release/spawn control pool lifecycle. |
| `engine/PipelineLogWriter.kt` | Persists and retrieves execution logs for pipeline runs/nodes. |
| `engine/PipelineMetrics.kt` | Publishes pipeline/module duration, success, and failure metrics. |
| `monitoring/MonitoringSnapshotService.kt` | Assembles current operational state for management monitoring. |
| `monitoring/MonitoringMetricsUpdater.kt` | Refreshes application monitoring metrics on its scheduled/lifecycle path. |
| `health/HealthChecks.kt` | Supplies liveness/readiness checks for dependencies and runtime roles. |
| `grafana/GrafanaInstanceConfig.kt` | Central target selection for dashboard versus monitoring Grafana. |
| `grafana/GrafanaSyncState.kt` | Prevents duplicate/incorrect Grafana sync work by recording scope state. |
| `engine/IngestSpool.kt` | Local fsync'd spool for records Kafka refused; drains when Kafka is back (NFR-R7). |
| `engine/PipelineRunQueue.kt` | Hands a record to Kafka (or the spool) with a deterministic run id; the caller acknowledges only after that. |
| `engine/PipelineDirectory.kt` | In-memory view of active pipelines, connector bindings and deduplication rules for DB-free ingest (NFR-R6). |
| `engine/PipelineInputListener.kt` | Kafka listener of the executor; distinguishes infrastructure failures (retry) from pipeline failures. |
| `engine/PendingRunReaper.kt` | Marks runs that never reached Kafka as failed. |
| `metadata/MetadataNodes.kt` | Executes the "Enrich with metadata" and "Save to metadata" nodes. |
| `execution/ExecutionService.kt`, `ExecutionContracts.kt`, `ExecutionStateMachine.kt` | Execution requests, limits and results, the state machine (PIP-18), and dispatch to the selected backend. |
| `execution/ExecutionBackendConfig.kt`, `ExecutionCapacityService.kt` | Backend selection per deployment and capacity reservation across replicas. |
| `execution/DockerExecutionBackend.kt` | Docker backend (single node). |
| `execution/KubernetesJobExecutionBackend.kt`, `KubernetesExecutionReconciler.kt` | Kubernetes Job backend (no Docker socket) and reconciliation after submitter loss. |

## Security and configuration

| Source | Responsibility |
|---|---|
| `security/JwtAuth.kt` | JWT creation/claims and authentication support. |
| `security/EncryptionUtils.kt` | Encryption/decryption utility for stored sensitive connector values. |
| `config/RuntimeRole.kt` | Parses configured runtime role values. |
| `config/RuntimeRoleConfig.kt` | Answers role-capability questions used to gate runtime components. |
| `config/RuntimeRoleFilter.kt` | Applies role restrictions to resources/components at runtime. |
| `config/DataSeeder.kt` | Creates initial development/bootstrap data on the relevant lifecycle path. |
| `security/AbilityEvaluator.kt` | Exact-token permission evaluation (IAM-01). |
| `security/AuthorizationVersionFilter.kt` | Rejects tokens issued before the user's last authorization change (IAM-14). |
| `security/IngestAuthorizationService.kt` | Cached ingest API key checks by SHA-256 digest; works while the database is down. |
| `security/IngestRateLimiter.kt` | Optional per-key ingest rate limit (off by default). |
| `security/SensitiveDataRedactor.kt`, `security/GlobalLogRedactionFilter.kt` | Call-site and global log redaction of credentials (NFR-S10). |
| `json/JsonObjectMappers.kt` | Jackson mapper with Java time support for application-owned JSON. |

## DTOs

| Source | Responsibility |
|---|---|
| `dto/AuthDto.kt` | Login/setup/token/current-user request and response shapes. |
| `dto/PermissionDto.kt` | Role permission request/response shapes. |

## Frontend application

| Source | Responsibility |
|---|---|
| `main.tsx` | Browser bootstrap and React root mount. |
| `App.tsx` | Top-level routes, protected-route behavior, and application composition. |
| `components/Layout.tsx` | Shared navigation, tenant context controls, language/theme controls, and page shell. |
| `services/api.ts` | Typed Axios clients, token interceptors, connectivity event emission, and all management/public API calls. |
| `stores/auth.ts` | Persisted authentication state, login/setup/logout, and tenant-context state. |
| `types/index.ts` | Canonical TypeScript contracts for API entities and payloads. |
| `i18n/index.ts` | Internationalisation bootstrap and translation resources. |
| `pages/Login.tsx` | Sign-in workflow and authentication failure presentation. |
| `pages/Setup.tsx` | First-run administrator setup workflow. |
| `pages/Dashboard.tsx` | Operational overview and quick navigation. |
| `pages/Modules.tsx` | Module inventory, filtering, and navigation to editing. |
| `pages/ModuleEditor.tsx` | Module/version authoring and validation workflow. |
| `pages/Pipelines.tsx` | Pipeline inventory, status, run triggering, and navigation. |
| `pages/PipelineEditor.tsx` | Pipeline graph creation/editing, module-version resolution, and save workflow. |
| `pages/RunDetail.tsx` | Pipeline run status, node execution evidence, and logs. |
| `pages/Connectors.tsx` | Connector lifecycle, settings, status, and broker credential UI. |
| `pages/Data.tsx` | Tenant data-table query, summary, and export UI. |
| `pages/Alerts.tsx` | Alert channels, rules, condition editing, and delivery history UI. |
| `pages/Users.tsx` | Tenant user administration and membership UI. |
| `pages/Tenants.tsx` | Tenant hierarchy, tenant settings, roles, and governance UI. |
| `vite-env.d.ts` | Vite environment type declarations. |
| `pages/ApiKeys.tsx` | Ingest API keys: create (key shown once), grants, last use, revoke. |
| `pages/Audit.tsx` | Audit trail: filters, paging, details, CSV/JSON export; dead-letter view for platform administrators. |
| `pages/Metadata.tsx` | Metadata types, objects, as-of view, CSV import, templates. |
| `components/AlertRuleForm.tsx`, `lib/alertRules.ts` | Built-in alert rule configuration of ALERT nodes. |
| `components/MetadataNodeForms.tsx`, `lib/metadataNodes.ts` | Enrich / Save-to-metadata node forms and their validation. |
| `lib/secrets.ts` | Stores credential form fields as secrets and replaces them by `secretRefs`. |
| `lib/csv.ts` | RFC 4180 CSV parser for metadata imports. |
| `hooks/useNow.ts` | Current time for relative-time rendering. |

## Tests and non-production files

Test sources under `backend/src/test` and `frontend/src/__tests__` document
expected behavior rather than runtime functionality. Keep a test name aligned with
the corresponding endpoint/service contract. Compose, Kubernetes and monitoring
manifests are deployment configuration; see `docs/docker/README.md` and the
operations documents for their runtime purpose.


## External identity login and component activity

- `auth/ExternalIdentity.kt`: explicit issuer/subject links and short-lived hashed login tickets.
- `auth/OidcLoginService.kt`: verified-subject lookup, browser binding, expiry and atomic handoff consumption.
- `api/OidcResource.kt`: OIDC login callback and same-origin handoff exchange; provider verification is handled by Quarkus.
- `api/ExternalIdentityResource.kt`: platform-administrator account links, audit and unlink revocation.
- `security/AuthenticatedTokenProducer.kt`: preserves injection of the authenticated local JWT alongside the OIDC extension.
- `frontend/src/components/IdentityLinks.tsx`: platform-administrator link editor on Users.
- `frontend/src/components/ComponentTabs.tsx`: lazy scoped activity, paged audit history and permanent component notes.
- `scripts/dev/test-desktop.sh`: representative local API/OIDC and frontend checks without Docker or Kubernetes.

`auth/LocalLoginPolicy.kt` validates the process-configured password-login policy and explicit break-glass account UUIDs. `AuthService.login` applies it after password verification and before issuing a token; verified external login follows its separate issuer/subject link path.
