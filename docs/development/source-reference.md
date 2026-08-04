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
| `pipeline/PipelineRunNodeLog.kt` | Per-node execution log entity. |
| `alert/AlertChannel.kt` | Alert delivery-channel entity and channel type enum. |
| `alert/AlertRule.kt` | Alert rule entity and tenant lookup. |
| `alert/AlertHistory.kt` | Alert attempt/history entity, severity/status enums, and recent-history lookup. |
| `connectors/Connector.kt` | Connector configuration entity and connector type/direction/status enums. |
| `connectors/BrokerCredential.kt` | Broker credential entity and credential lifecycle metadata. |
| `grafana/GrafanaSyncState.kt` | Persisted Grafana synchronization cursor/state. |
| `grafana/GrafanaInstanceConfig.kt` | Resolves target-specific Grafana configuration and validates whether it is usable. |

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

## Services and application policy

| Source | Responsibility and key function family |
|---|---|
| `service/AuthService.kt` | Authenticates credentials, provisions setup users, creates JWTs, and resolves user context. |
| `service/UserService.kt` | User CRUD/business rules independent of HTTP transport. |
| `service/TenantService.kt` | Tenant creation, hierarchy traversal, inherited/default configuration, and tenant validation. |
| `service/ModuleService.kt` | Module/version creation, lookup, and version lifecycle policy. |
| `service/PipelineService.kt` | Pipeline creation/update, graph validation, trigger preparation, and run state transitions. |
| `service/PipelineDataService.kt` | Tenant schema/table creation and parameterized pipeline-data access. |
| `service/KafkaService.kt` | Topic naming/creation and pipeline message publication. |
| `service/GrafanaSyncService.kt` | Builds and synchronizes Grafana resources for supported scopes. |
| `service/GrafanaProvisioningService.kt` | Provisions datasources/dashboards against a configured Grafana instance. |
| `alert/AlertDispatcher.kt` | Evaluates condition trees, observes cooldowns, records alert history, and dispatches per channel. |
| `logging/AuditLogger.kt` | Emits structured audit records for security-relevant changes. |

## Connector runtime

| Source | Responsibility |
|---|---|
| `connectors/ConnectorInterfaces.kt` | Common connection, reader, and writer contracts implemented by connector types. |
| `connectors/ConnectorManager.kt` | Starts/stops registered connector implementations and coordinates lifecycle. |
| `connectors/ConnectorRuntimeService.kt` | Binds persisted connectors to the active runtime role and reconciles their runtime state. |
| `connectors/ConnectorLeaseService.kt` | Uses Redis leases to ensure one eligible instance owns a connector runtime. |
| `connectors/ConnectorStatusService.kt` | Records and exposes connection health/status changes. |
| `connectors/ConnectorMetrics.kt` | Creates and updates connector observability metrics. |
| `connectors/polling/ConnectorPollingService.kt` | Schedules polling connector reads and submits resulting records. |
| `connectors/http/HttpConnector.kt` | HTTP connector connection/read/write implementation. |
| `connectors/mqtt/MqttConnector.kt` | MQTT connector implementation and subscription/publication configuration. |
| `connectors/mqtt/MqttConnectionManager.kt` | Owns MQTT client connections and reconnection lifecycle. |
| `connectors/mqtt/MqttIngestService.kt` | Converts MQTT messages to ingestion work. |
| `connectors/mqtt/MqttMessageRouter.kt` | Routes MQTT payloads to matching connector/pipeline targets. |
| `connectors/websocket/WebSocketConnector.kt` | WebSocket connector implementation. |
| `connectors/websocket/WebSocketConnectionManager.kt` | Owns outbound WebSocket sessions, reconnects, and message delivery. |

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
| `health/HelloResource.kt` | Minimal diagnostic endpoint. |
| `grafana/GrafanaInstanceConfig.kt` | Central target selection for dashboard versus monitoring Grafana. |
| `grafana/GrafanaSyncState.kt` | Prevents duplicate/incorrect Grafana sync work by recording scope state. |

## Security and configuration

| Source | Responsibility |
|---|---|
| `security/JwtAuth.kt` | JWT creation/claims and authentication support. |
| `security/EncryptionUtils.kt` | Encryption/decryption utility for stored sensitive connector values. |
| `config/RuntimeRole.kt` | Parses configured runtime role values. |
| `config/RuntimeRoleConfig.kt` | Answers role-capability questions used to gate runtime components. |
| `config/RuntimeRoleFilter.kt` | Applies role restrictions to resources/components at runtime. |
| `config/DataSeeder.kt` | Creates initial development/bootstrap data on the relevant lifecycle path. |

## DTOs

| Source | Responsibility |
|---|---|
| `dto/AuthDto.kt` | Login/setup/token/current-user request and response shapes. |
| `dto/TenantDto.kt` | Tenant, hierarchy, defaults, and diagnostics shapes. |
| `dto/PermissionDto.kt` | Role permission request/response shapes. |
| `dto/ModuleDto.kt` | Module/version request and response shapes. |
| `dto/PipelineDto.kt` | Pipeline graph, run, and lineage request/response shapes. |

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

## Tests and non-production files

Test sources under `backend/src/test` and `frontend/src/__tests__` document
expected behavior rather than runtime functionality. Keep a test name aligned with
the corresponding endpoint/service contract. Compose, Swarm, Kubernetes, Helm,
and monitoring manifests are deployment configuration; see the Docker and
operations documents for their runtime purpose.
