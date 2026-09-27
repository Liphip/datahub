# DataTurntable Production Ready v1 — Status Tracker

This tracks progress against the `requirements-to-plan-schema-v1` plan for the
"DataTurntable Production Ready v1" project. The full plan spans many phases
(security baseline, execution-plane extraction, Kafka reliability, microservice
extraction, etc.) — far more than one session can implement. This document is
the durable cross-session record of what's done, what's in flight, and what's
broken, so work can resume without re-deriving context.

Last updated: 2026-09-27 (pull-input connector types — FILE/FTP/SFTP/EMAIL — added to the UI with
inbox-ledger retry and secret-reference handling; **unstructured blob storage removed end-to-end**
per STR-08, so the platform now stores records rather than payloads and FR-022 is no longer a gap;
both specifications updated for metadata, fire-and-forget, queue redundancy and §14a as an example
only; full backend suite re-verified fresh at 318 tests, 0 failures, 0 errors, 22 skipped, plus
frontend typecheck, 38 vitest tests and eslint 0 errors. See the 2026-09-27 session entry below for
detail, including the CI Java-21-vs-Java-25 discrepancy found but not yet fixed. The preceding
2026-08-20 summary follows.)

## Previous update: 2026-08-20 (WebSocket idle-timeout and PipelineConsumerDlqIT root causes both found
and fixed; connector two-replica lease fencing verified under real concurrent PostgreSQL
transactions; CI now builds+pushes images to GHCR and captures real digests for the kustomize
transformer; OpenTelemetry dependency added, disabled by default; two real secret-leak log-redaction
gaps found and fixed (AlertDispatcher, GrafanaProvisioningService/GrafanaSyncResource); CI now
generates and lints the OpenAPI contract for real; dead scaffold endpoint removed;
docs/operations/support-matrix.md now covers all 20 NFR IDs; a global JBoss LogManager log-redaction
filter now backs up call-site redaction as defense-in-depth; **a follow-up same-day session then
found and fixed a real uncaught-exception robustness bug in `AuthService.login()`/
`UserService.changePassword()` (malformed BCrypt hash crashed with 500 instead of 401) plus a
genuine test-suite-wide cross-test User-row pollution bug across 9 test classes that a prior
session's "301 tests, 0 failures" claim had not caught — see the "real cross-test pollution bug"
entry near the end of this file for full detail** — full backend suite is freshly re-verified green
at 305 tests, 0 failures, 0 errors, 11 skipped (H2), plus `PostgresMigrationBehaviorTest` and
`ConnectorLeaseConcurrencyPostgresIT` independently re-verified green against real PostgreSQL; see
the dated session entries below for the current, authoritative state; the "Verification update"
section directly below is stale and kept only for historical continuity).

## Project policy: no production data, no live instances yet

This project has **no production deployment and no live instances** as of this writing. It is
still under active development. Consequently:

- **No backward compatibility is required.** Do not add compatibility shims, dual-read/dual-write
  paths, deprecated-field fallbacks, or "legacy" support code for the sake of preserving old
  behavior. When a schema, contract, or API needs to change, change it directly.
- **No data migration scripts are required for existing rows** — there is no existing production
  data to migrate. Flyway schema migrations (DDL) are still required and still run in strict
  numeric order against a real database (see NFR-014 below), but there is no need to write
  data-backfill migrations, "mark legacy/incomplete" provenance flags, or old-record preservation
  logic *for the purpose of protecting real user data*, because none exists yet.
- Treat `NFR-013` ("preserve historical IDs/records through migration") and the plan's repeated
  emphasis on strangler migration / legacy-array preservation as **describing the target
  architecture's eventual production discipline**, not as constraints that block simplifying the
  current pre-production schema or removing now-unused legacy fields outright.
- If a future session reaches actual production readiness with real tenant data, this policy
  entry should be revisited and struck — at that point the standard migration-safety rules in
  `docs/production-ready-spec.json` (`existing_system_context.migration_considerations`) apply in
  full.

## Verification update

The full backend suite is currently green: **255 tests passed, 0 failed/errors,
11 skipped** (the skips are integration-dependent Docker/module tests). Focused
verification also passes for pipeline state transitions, Kubernetes manifest
construction, event-envelope validation, and cross-tenant isolation. The prior
16 failures were repaired or correctly updated to the common API error-envelope
contract. Conflict metadata is intentionally exposed in `details` (for example,
`details.referenceCount` and `details.currentVersion`) rather than as loose
top-level fields.

Broker-backed DLQ verification: a Testcontainers-backed integration test was
added to exercise the consumer poison-message path and verify `consumer_dead_letters`.
See `backend/src/test/kotlin/com/dataturntable/engine/PipelineConsumerDlqIT.kt` and
`backend/src/test/kotlin/com/dataturntable/test/KafkaTestResource.kt`.

An end-to-end outbox replay integration test, `EventOutboxReplayE2EIT`, was
implemented. It uses Testcontainers to start Kafka, creates a unique per-test
topic, persists a dead-lettered outbox entry (payload contains the DB-assigned
UUID), triggers the replay, and verifies delivery with a dedicated consumer.
The E2E test passes locally. Note: these changes and tests are present in the
workspace locally and have not been pushed to the remote repository.

The two order-dependent `TestAuthHelpers.createAdminToken()` failures were
fixed by resetting the identity fixture in the affected user-resource test
classes before they create the initial administrator. This prevents a different
test's setup credential from contaminating those tests.

## Phase 3 progress

`PipelineRunStatus` now includes `QUEUED`, `CANCELLING`, and `TIMED_OUT`, with
`PipelineRunStateMachine` enforcing legal domain transitions and the main
`PipelineLogWriter`/cancel route using the guarded lifecycle methods. The
pipeline resource and state-machine suite passes. Kafka producers now emit a
validated version-1 `EventEnvelope` carrying event, tenant, correlation,
causation, pipeline/run/node, and execution identifiers, and partition records by pipeline ID;
the consumer's durable inbox suppresses duplicate envelope IDs. Message
resolution (parse, envelope validation, inbox claim, RUNNING marker) is now
bounded-retried with exponential backoff before a transient failure reaches
the Kafka dead-letter path: `PipelineConsumer.consume()` delegates that phase
to the new `PipelineRunResolver` bean (a separate CDI bean so its
`@Transactional` boundary is honored per-retry-attempt — ArC does not
intercept self-invocation) and retries up to 3 times with 200/400/800ms
backoff on any exception other than the internal `PoisonPipelineEvent`, which
still dead-letters immediately since it signals unrecoverable message
content, not a transient condition. Actual pipeline execution
(`executeResolved`) is never retried at this layer — retrying after a
module/connector has already produced side effects could duplicate them.
Remaining Phase 3 work includes broader consumer-side reconciliation and
end-to-end broker-backed verification of the retry path itself (the existing
`PipelineConsumerDlqIT` exercises only the immediate-poison path; a
transient-failure-then-recovery integration test is not yet written).
`PipelineConsumer` now rejects malformed or
unsupported envelope wire data before using its payload and checks that the
envelope tenant/pipeline identifiers match the resolved target;
`PipelineEventEnvelopeValidatorTest` covers valid, legacy, malformed, and
unsupported envelopes. Unrecoverable parse, envelope, and target-mismatch
failures are raised to the Kafka connector; the `pipeline-input` channel uses
its dead-letter-queue strategy and publishes them to
`dataturntable.pipelines.dead-letter`. Parse and envelope failures are also
written in a separate `REQUIRES_NEW` transaction to redacted
`consumer_dead_letters` records, which platform administrators can inspect via
`GET /api/internal/v1/events/dlq/consumer`. Broker-backed poison/replay
verification remains outstanding.

## Phase 3 outbox progress

Pipeline-input publication now writes an `event_outbox` record in the same
database transaction as the triggering state change. `EventOutboxDispatcher`
uses pessimistic row locking for replica-safe batch dispatch, waits for the
Kafka connector acknowledgement before marking a record `PUBLISHED`, and keeps
failed sends pending with capped exponential backoff. Failed broker handoffs now
enter `DEAD_LETTERED` after the configured finite attempt budget; platform
administrators can inspect and explicitly requeue them through
`/api/internal/v1/events/dlq`. Migration `V15` adds the table and index. This
is covered by `EventOutboxServiceTest` and `EventDlqResourceTest`; DLQ payload
and error output is redacted before inspection responses. It still
needs a real Kafka integration test and a consumer poison-message DLQ flow.

## Phase 2 execution progress

`ExecutionLimits` now validates timeout, CPU, memory, PID, temporary-disk, and
output bounds. `DockerExecutionBackend` passes that policy into `DockerService`;
the resulting ephemeral Docker command enforces non-root/read-only/no-capability
execution, configured CPU and memory hard limits, PID limit, bounded tmpfs,
bounded logs, deadline, and opt-in network. Requests with custom limits bypass
the warm pool so they cannot inherit another request's sandbox policy.
`DockerSandboxPolicyTest` verifies that command policy without requiring a
Docker daemon, including its two-sided deployment/request network opt-in and
the strict bounded-output cap (including newline handling).

**Hostile-module live-execution coverage added (2026-08-19).** The previous `DockerServiceTest`
was `@Disabled` with an empty test body — zero live-execution coverage of the NFR-002 "user Python
is hostile by default" invariant existed anywhere (policy-construction tests never actually ran a
container). Replaced it with `DockerServiceHostileModuleTest`, which runs real hostile modules
against a real Docker daemon (Docker Desktop is available in this dev environment and GitHub
Actions' `ubuntu-latest` runners have Docker preinstalled, matching the precedent already set by
`PostgresMigrationBehaviorTest`, so this runs as part of the default suite, not behind an opt-in
profile): a well-behaved module executing successfully, an infinite loop terminated at the
configured timeout, a filesystem-escape write attempt rejected by `--read-only`, network egress
blocked by default even when the request opts in (deployment-level `allow-network` still gates
it), oversized stdout truncated to the configured cap, and a fork bomb bounded by `--pids-limit`
rather than hanging or exhausting host resources.

**Real bug found and fixed while writing these tests:** on Windows, `java.lang.ProcessBuilder`
launching `docker run` strips unescaped embedded double quotes from `-e KEY=value` arguments
(`CreateProcess` argument re-quoting silently drops them rather than escaping them) — reproduced
in isolation with a minimal Java `ProcessBuilder` call. This meant every `-e INPUT_DATA=<json>`,
`-e CONNECTOR_META=<json>`, and — more seriously — `-e MODULE_CODE=<raw python source>` value
containing a `"` arrived corrupted inside the container: JSON became invalid and silently parsed
to `{}` (the receiving Python helper's `except: return {}` swallowed the failure), and arbitrary
module source containing string literals would have been mangled. This is Windows-dev-machine-only
(POSIX `exec`, i.e. Linux prod/CI, passes argv literally and was never affected), but is a real
correctness bug for local Windows development. Fixed with a shared `envArg(key, value)` helper in
`DockerService.kt` (used by both `DockerService.runContainer` and `WarmContainerPool.spawn`, the
two places that shell out to `docker run`) that escapes `"` as `\"` only when `os.name` reports
Windows. `DockerSandboxPolicyTest` gained two unit tests for `envArg` itself, and
`DockerServiceHostileModuleTest` gained a regression test asserting a module containing an escaped
string literal and JSON input both survive the round trip intact — verified fixed against the real
bug (confirmed failing before the fix, passing after) rather than only unit-tested in isolation.

**Live cancellation coverage added (2026-08-19).** Cancellation had two disjoint layers of
coverage — `ExecutionStateMachine`'s legal-transition table, and `ExecutionServiceTest`'s
fake in-memory backend proving `cancelWithBackend` *calls* `backend.cancel()` — but nothing
verified `DockerExecutionBackend.cancel()` (which shells out to `docker ps`/`docker rm -f`)
actually stops a real running container, or that a cancelled execution's capacity slot becomes
reusable rather than leaked. Added `ExecutionCancellationLiveDockerTest`: submits a real
60-second-sleep module via `ExecutionService.execute()` on a background `ManagedExecutor` task
(CDI/JTA context propagation matters here — a plain `java.util.concurrent.Executors` thread pool
silently failed to reach the persistence layer at all, worth noting for future live-Docker tests
in this codebase), polls `docker ps` until the container is confirmed running, calls
`ExecutionService.cancel()` from the main test thread, and asserts: the background `execute()`
call returns promptly (well under the container's true 60s sleep or the 90s configured timeout)
with a non-`SUCCESS` status; the container is actually gone from `docker ps` afterward, not just
marked cancelled in the database; and — under a tight `max-concurrent-global=1` test profile — a
second execution submitted immediately after succeeds rather than hitting a 429, proving the freed
slot is real. Passed consistently across repeated runs.

The Kubernetes backend now derives a deterministic Job name, carries the
ephemeral-disk limit into the native manifest, and issues a native Job DELETE
on cancellation (an already-deleted Job is treated as cancelled).
The Kubernetes pipeline-executor deployment now selects this native backend
instead of Docker and uses a dedicated service account whose namespace Role is
limited to creating, reading, and deleting Jobs; user Jobs themselves do not
receive a service-account token. A default-deny NetworkPolicy selects all user
Job pods; the Kubernetes backend rejects network-enabled job requests until an
explicitly reviewed egress-policy design is added. `ExecutionBackendConfig`
normalizes and validates the deployment backend at startup, accepting only
`docker` or `kubernetes`; `ExecutionBackendConfigTest` covers that guard.
`KubernetesExecutionReconciler` runs only with the pipeline-executor role and
polls active native Jobs to persist `RUNNING`, `SUCCESS`, `FAILED`, or
`TIMED_OUT` states. A missing Job completes only a `CANCELLING` execution as
`CANCELLED`, avoiding false terminal success after TTL cleanup.

`ExecutionCapacityService` now reserves queued executions transactionally under
a short-lived database coordinator lock. It enforces global and tenant limits
(with a tenant `quota.maxConcurrentExecutions` override), then releases that
lock before backend execution begins. The execution submission API reports
quota rejection as the common `429` error envelope with safe scope/limit/active
details. `ExecutionResourceQuotaTest` verifies that the eleventh queued request
for one tenant is rejected after ten successful reservations;
`ExecutionGlobalQuotaTest` verifies a two-execution global cap is shared across
three separate tenants.

Still outstanding: hostile-module integration tests and direct backend
timeout/cancellation behavior against live Docker or Kubernetes infrastructure.

## Phase 4 connector HA progress

Connector runtime leases now have a persistent monotonic `fencing_token`
(migration `V16`). PostgreSQL acquisition compares expiry to database time and
increments the token when an expired foreign owner takes over; renewal by the
same owner preserves it. `ConnectorLeaseService.owns` rejects stale tokens and
`ConnectorLeaseServiceTest` covers takeover. MQTT and WebSocket runtime loops
now retain each acquisition's fencing generation and re-check it before routing
inbound data; stale callbacks disconnect rather than producing authoritative
events. Polling tasks likewise capture and re-check their fencing generation
both before a poll and after a potentially blocking fetch, preventing stale
owners from creating runs or publishing events. Two-replica integration
coverage now includes a shared-database two-runtime simulation: a standby is
denied while the first owner is live, then acquires a higher token after expiry
while the stale claim is rejected. `GET /api/internal/v1/connectors/{id}/lease`
now exposes safe ownership diagnostics — owned/unowned state, owner instance
ID, runtime role, fencing token, and lease expiry — so operators can answer
"which runtime instance currently owns this connector" during failover
diagnosis; it is tenant-scoped and requires `read:connector`, verified by
`ConnectorResourceTest` (unowned, owned-after-acquisition, and foreign-tenant
403 cases) and by `CrossTenantIsolationTest`. Real two-process/45-second
failover rehearsal against live infrastructure remains outstanding.

## Phase 5 secret-service progress

Migration `V17` introduces tenant-owned `secrets` and immutable encrypted
`secret_versions`. `SecretService` validates names, creates/rotates versions,
returns masked references rather than values, and rejects cross-tenant
resolution. `SecretServiceTest` covers immutable rotation and isolation.
`/api/internal/v1/secrets` provides tenant-scoped list/create/rotate operations
requiring `secret:manage`; its response contract is write-only for values.
`ConnectorRuntimeService` now resolves connector `secretRefs` only in memory
and removes the reference map before passing config to protocol execution;
`ConnectorSecretReferenceTest` verifies the generic, MQTT, and WebSocket
persistent runtime paths. Alert delivery now resolves channel `secretRefs` in
memory and strips them before dispatch; `AlertChannelSecretReferenceResolverTest`
verifies the path. Alert channel create/update now reject inline credential-like
fields in favor of `secretRefs`. `ConnectorResource.create`/`update` now apply
the equivalent `validateConnectorConfig` check: any `password`/`token`/`apiKey`/
`secret`/`authorization`/`clientSecret`-shaped field outside a `secretRefs`
block is rejected with 400 `"Connector credentials must use secretRefs"`. MQTT
broker-credential binding (`brokerCredentialId`) is exempt since it is the
already-correct non-`secretRefs` credential path for that connector type
(`sanitizeMqttConfig` strips inline `username`/`password` once a credential ID
is bound). Existing connectors written before this check remain readable and
maskable via `maskConnectorConfig` on GET, and are only revalidated if their
config is written again through the API — cloning also copies a legacy
config as-is (optionally stripped of secrets). `ConnectorResourceTest` covers
create-time rejection, `secretRefs`-based acceptance, and GET-time masking of
a directly-seeded legacy inline config.

## Phase 5 data-safety progress

Relational table registration no longer silently rewrites column names. It now
uses the centralized identifier validation and rejects case-insensitive duplicate
columns before issuing DDL; `DataResourceTest` covers both failures. Full
PostgreSQL/TimescaleDB transactional schema-mutation coverage and object-storage
blob work remain outstanding. Migration `V22` adds explicit blob operation
states; unavailable objects are persisted as `MISSING` and failed filesystem
deletes as `DELETE_FAILED`, allowing recovery work rather than silent metadata
drift. The configurable `BLOB_FAILED_RETENTION_DAYS` window defaults to 30
days; `POST /api/v1/data/{tenantId}/blobs/recover` retries object deletion and
tombstones stale failed metadata. `DataResourceTest` verifies both durable
missing-object state and retention recovery.

## Phase 5 PipelineVersion execution-pinning fix

Found and fixed a real reproducibility gap in the existing `PipelineVersion`/`PipelineRun.pipelineVersion`
scaffolding: every `PipelineRun` creation site (`PipelineResource`, `IngestResource` x2,
`WebSocketIngestResource`, `MqttMessageRouter`, `ConnectorPollingService`,
`WebSocketConnectionManager`, `PipelineService`) already correctly pinned `run.pipelineVersion` to
`PipelineVersionService.latestFor(pipeline.id)` at trigger time — but `PipelineConsumer`, the actual
execution engine, never read it. `resolveConnectorsByNodeId` and the topological-execution graph
parse both read the live, mutable `Pipeline.graphDefinition` directly, so a pipeline edited after a
run was triggered but before its Kafka message was consumed would silently execute the *new* graph
instead of the one the run was pinned to — defeating the immutability/reproducibility guarantee
`PipelineVersion` exists to provide (FR-009). Fixed by adding
`PipelineConsumer.resolveExecutionGraphJson(pipeline, runUuid)`, which resolves
`run.pipelineVersion?.graphDefinition`, falling back to the live `Pipeline.graphDefinition` only for
legacy runs with no linked version; both `resolveConnectorsByNodeId` and the topological execution
parse now use this resolved graph instead of reading `pipeline.graphDefinition` directly.
`PipelineConsumerVersionPinningTest` verifies both the pinned-version and legacy-fallback cases.
`PipelineVersion.graphDefinition` itself still has no DB/entity-level immutability enforcement
(nothing currently attempts to mutate an already-persisted version row, but that's convention, not
an enforced invariant) — a defense-in-depth follow-up, not required to close this specific gap.

## Phase 6 alert workflow progress

Alert history already enforces tenant-scoped, bounded deterministic pagination
and alert conditions support nested `all`/`any` groups. `POST
/api/v1/alerts/rules/{id}/test` now evaluates a rule against supplied payload
and previews its channel action with `wouldDispatch: false`; it sends no action
and writes no history. `AlertResourceTest` verifies the matching preview. The
Alerts frontend now exposes an authorized rule workspace: users can list,
create, edit, delete, enable/disable, set cooldowns, select a channel, build
recursive AND/OR conditions with typed values, select multiple channel actions,
and test a saved rule from the browser. Migration `V20` normalizes rule actions
and preserves legacy single-channel rules; the dispatcher emits one history and
delivery attempt per action. `AlertResourceTest` verifies multi-action create
and safe preview, and `npm run build` verifies the UI. Migration `V21` adds a
row-locked, database-clock cooldown record for each rule, so replicas make one
shared delivery decision; `AlertCooldownServiceTest` verifies the winner/loser
window behavior. Migration `V25` promotes severity and message to independent
`alert_rules` columns (`severity VARCHAR NOT NULL DEFAULT 'WARNING'`, `message
TEXT`), backfilled from any existing `condition.severity`/`condition.message`
JSON values so previously configured rules keep firing identically. `AlertRule`
exposes them as typed fields; `AlertDispatcher.evaluateRulesForPipeline` now
reads `rule.severity`/`rule.message` directly instead of parsing them out of
`condition` at dispatch time. `AlertResource` create/update accept and
validate `severity` (400 on an unrecognized value) and `message` as top-level
request fields, and `toRuleDto` returns them independently of `condition`.
The frontend rule editor now binds its severity/message controls to the
top-level `AlertRule.severity`/`message` fields (no longer nested inside the
condition JSON blob) and sends them accordingly; `npm run build` passes.
`AlertResourceTest` covers create/list/update round-tripping and invalid-
severity rejection. Browser E2E coverage remains unimplemented.

## Phase 6 authorization-debugging progress

The existing tenant-authorized `GET /api/internal/v1/authorization/explain`
endpoint is now exposed in the Users administration screen. Tenant managers can
select a member and ability, then inspect the allow/deny result, platform/user/
membership/role provenance, and the effective ability set. The frontend build
verifies the typed client contract and UI compilation.

## Phase 6 audit progress

Migration `V18` introduces append-only `audit_events`, and `AuditService`
redacts recursively named sensitive values before metadata is persisted. REST
request IDs are inherited automatically by audit records through the request-ID
filter. Secret
create/rotate operations now emit actor/tenant/resource audit records without
the secret value. Alert channel/rule create, update, and delete operations now
emit minimal actor/tenant/resource records without channel configuration or
payloads; `AlertResourceTest` verifies a marker configuration secret is absent
from the resulting event. `AuditServiceTest` verifies redaction. Authentication
(successful and failed login, registration/setup, tenant switch) and identity
administration (auth-user, membership, role, and tenant create/update/delete)
now emit minimal actor/tenant/resource/outcome events without credential
metadata. Pipeline create/update/delete, manual trigger, retry, and cancellation
are recorded; connector and MQTT broker-credential create/update/rotate/delete
records are also integrated, while operational connect/disconnect producers remain.
Direct execution submission and cancellation now emit matching audit records.
An operator retry of a dead-lettered Kafka outbox event now records the actor,
outbox event, and topic; `EventDlqResourceTest` verifies the audit event.
Pipeline-run cancellation now records its actor, tenant, pipeline,
run, and prior state without inputs, outputs, or logs; `PipelineResourceTest`
verifies it.

## Phase 8 CI progress

`.github/workflows/ci.yml` now runs backend tests (including the tenant suite),
frontend type/build checks, and applies every Flyway SQL migration in numeric
order to a clean PostgreSQL 16 service. The migration gate verifies critical
outbox/secret/audit/blob tables and the blob-state columns. CI also renders the
base and HA Kubernetes Kustomize overlays. V1 now enables `pgcrypto`, required by its
`gen_random_uuid()` defaults. Local `npm run build` passes (306 modules;
initial JavaScript gzip 120.50 kB, with PipelineEditor split into its own
63.07 kB gzip chunk); CI asserts the editor remains dynamically imported from
the application entry. Container scanning, SBOM/release digests, and
real Compose/Kubernetes end-to-end validation remain outstanding.

## How to use this file

- Update the phase/task tables as work completes.
- Log newly discovered bugs in "Known issues" with enough detail to resume
  investigation without re-deriving it.
- Do not mark a task "done" until its acceptance criteria are verified, not
  just implemented.

## Phase 1 — Security Baseline and Contract Foundation

Most of Phase 1 was already implemented in the working tree (uncommitted) when
this tracking file was created. This session verified, fixed test-infra bugs
around, and hardened that existing work rather than writing it from scratch.

| Task | Status | Notes |
|---|---|---|
| P1-T1 exact-token authorization | Done | `AbilityEvaluator` replaces substring `contains()` checks; `AbilityEvaluatorTest` passes. |
| P1-T2 relational Ability/RoleAbility/MembershipAbility + platform-admin split + explanation API | Done | `Ability`, `AuthorizationResource`, `AuthorizationGrantService`, `AuthorizationVersionFilter`, migrations V7/V8. `AuthorizationResourceTest` verifies user, membership, and role provenance plus the effective ability set end-to-end. |
| P1-T3 cross-tenant regression suite | In progress | `CrossTenantIsolationTest` now proves that a sibling-tenant membership granted `*` cannot discover Tenant B users, roles, modules, pipelines, connectors, broker credentials, secret references, alert channels/rules, tables, blobs, or executions; cannot read, rename, or delete a foreign tenant, role, module, or tenant-administered user; cannot create a role in another tenant; cannot inspect/cancel a foreign execution; cannot infer/mutate a foreign membership through its own tenant route; cannot read a foreign pipeline, its run history, run detail, or node logs; cannot inspect, mutate, delete, clone, connect/disconnect, test, or restore a foreign connector; cannot mutate/rotate/delete foreign broker credentials or secrets; cannot change/list/default a foreign module version; cannot query, write, register, delete, download, or list Tenant B data/blobs; cannot mutate foreign alert IDs; and cannot embed a foreign module version or alert channel in its own graph/rule. Additional tenant-addressed nested-reference coverage remains. |
| P1-T4 JWT key rotation / secrets / connector encryption | Done | `privateKey.pem`/`publicKey.pem` removed from `src/main/resources`; keys now sourced from `DATATURNTABLE_JWT_SIGNING_KEY_LOCATION`/`DATATURNTABLE_JWT_PUBLIC_KEY_LOCATION` env vars. `EncryptionUtils` migrated CBC→GCM (`v2:` prefix, backward-compat decrypt retained), enforces 32-byte key and throws in prod profile if missing. Grafana provisioning credentials no longer default to `admin`/`admin`; enabling either provisioner requires deployment-supplied credentials. `docker/k8s/storage/secrets.yaml` is empty of placeholder values. |
| P1-T5 Argon2id + legacy migration + session invalidation | Done | `AuthService.login()`/`setup()` use Argon2id; legacy BCrypt(SHA256) verified and silently rehashed on login. Frontend (`api.ts`) no longer SHA-256's passwords client-side. |
| P1-T6 ingest auth + rate limits + WS thread-safety | In progress | `IngestAuthorizationService` and `IngestRateLimiter` exist with tests. `WebSocketIngestResource` atomically registers sessions so concurrent opens cannot exceed the per-pipeline limit; `WebSocketIngestResourceTest` verifies 200 concurrent attempts admit exactly 100, configures a 64 KiB message buffer and idle timeout without retaining raw credentials, and rejects the 601st message in one minute. WebSocket producers now receive a safe `ingest.accepted` or `ingest.rejected` acknowledgement with the run ID, and failed submissions persist a redacted failed run rather than silently appearing accepted. **This session:** the previously-hardcoded 5-minute `session.maxIdleTimeout` in `WebSocketEndpoint.kt` is now sourced from `dataturntable.ingest.websocket.idle-timeout-ms` (default `300000`, still 5 minutes in prod) instead of a literal — a real fix (removes a hardcoded value with no override path), matching the `@ConfigProperty` pattern used elsewhere (`ConnectorLeaseService`, `DockerService`). Added `WebSocketIdleTimeoutE2ETest` (a real JSR-356 client/server round trip via a dedicated `QuarkusTestProfile`), intended to prove idle sessions are eventually closed by the server. **Left intentionally failing, not disabled — do not "fix" by disabling/skipping/deleting it without solving the real problem below; do not report the backend suite as fully green while this test exists until it's addressed.** Empirically, the actual server-side close timing does NOT track the configured value at all: across repeated runs with the override set to 500ms, the real close happened at drifting, ever-increasing wall-clock times within one session — ~10s, ~20s, ~45s, ~90s — with the delay growing across successive runs rather than settling on any fixed number tied to the config. This rules out a simple "wrong constant" explanation; it looks like either environmental/resource buildup across repeated Testcontainers-Kafka-backed test runs in this session, or `jakarta.websocket.Session.setMaxIdleTimeout()` genuinely isn't the mechanism driving idle disconnection in this Quarkus classic-websockets (Undertow/Vert.x-backed) JSR-356 stack and something else entirely (unrelated timeout, GC pause, thread starvation) is closing the connection. The source change itself (config-driven instead of a hardcoded literal) is still correct and worth keeping regardless of the outcome. **Needs real isolated investigation**, ideally: run `WebSocketIdleTimeoutE2ETest` alone in a fresh JVM/Maven invocation (not after a long suite run) and see if the close time is stable; if still drifting, add server-side logging inside `configureAuthorizedSession`/`onClose`/`onError` to capture what actually triggers the close (idle timeout vs. some other Undertow/Vert.x event) rather than inferring from wall-clock gaps alone. |
| P1-T7 monitoring/logging hardening + SSRF + secret redaction | In progress | `ConnectorResource.validateExternalDestination()` blocks SSRF targets for REST/WEBSOCKET connectors (loopback/private/link-local/metadata). `SensitiveDataRedactor` now removes key/value secrets, bearer credentials, and URI userinfo from detailed monitoring stdout/stderr/error output and AuditService metadata; pipeline-consumer Kafka payload diagnostics now pass through the same redactor. `SensitiveDataRedactorTest`, `AuditServiceTest`, and `MonitoringResourceContractTest` pass. **This session** audited every `LOG.info/warn/error` call site across the connector runtime for realistic secret-leak risk (connector destination URLs and connection-failure messages can embed credentials, e.g. MQTT `tcp://user:pass@host`) and wired `SensitiveDataRedactor` into the highest-risk sites: `MqttConnectionManager` (broker URL/connection-failure logging at connect, publish, reconnect, connection-lost, and the `/test` broker-connection-check response, plus the `clientErrors` map surfaced via the connector status API), `ConnectorRuntimeService` (REST INPUT/OUTPUT fetch/deliver URL and failure-response logging), `WebSocketConnectionManager` (connect/send/error/failure logging and the `connectionErrors` map), and `PipelineConsumer`'s `MODULE_STDOUT`/`MODULE_STDERR` structured logs (user Python module output could echo a secret it read from connector config). `SensitiveDataRedactorTest` gained a case proving the existing URL-userinfo regex correctly redacts non-HTTP broker schemes (`tcp://`, `wss://`) matching these call sites' actual log shapes. No global JBoss LogManager filter/formatter exists in the codebase (confirmed absent) — this remains call-site opt-in, not a single choke point; a global filter is a larger, separate architectural addition and was deliberately not built in this pass. **2026-08-20 session:** audited every remaining un-redacted call site; `AuthService.kt` has no logging at all (nothing to redact, prior note was stale). Found and fixed two real gaps: `AlertDispatcher.kt` (webhook/REST/Slack dispatch failure logging, zero redactor coverage previously — a channel's API key embedded in a webhook URL query string could leak) and `GrafanaProvisioningService.kt`/`GrafanaSyncResource.kt` (a Grafana validation-error response could echo a freshly generated provisioning password back into a thrown message that then reached both logs and a persisted, API-exposed `lastError` column). Both now redact; see that session's dedicated entry below for full detail and the regression tests added. OpenTelemetry dependency now exists (2026-08-20), disabled by default — see that session's entry for what's still unwired (span attributes don't yet carry this platform's correlation IDs). |
| P1-T8 API error envelope + OpenAPI | Done, with one gap fixed this session | `ApiErrorContract.kt` provides `ApiError`, `RequestIdFilter`, `UnhandledExceptionMapper`. **Fixed this session:** the 500 handler swallowed exceptions with zero server-side logging, making every unhandled error undiagnosable — added `LOG.error(...)` in `UnhandledExceptionMapper`. |
| P1-T9 repo hygiene (Vite proxy, dup H2, Quarkus config) | Done | Confirmed via diff — H2 dedup, Vite proxy rewrite fix, Kafka/Micrometer config warnings resolved. |

## Known issues found and fixed this session

These were discovered by actually compiling and running the backend test
suite (`mvn -o test`), not just reading the diff. Several are real production
bugs, not just test artifacts — flagged accordingly.

1. **`ConnectorLeaseService.tryAcquireOrRenew` / `EventInboxService.claim` used Postgres-only `ON CONFLICT` syntax with no safe H2 fallback** (production-relevant pattern bug, not H2-only). A caught `SQLGrammarException` inside a JTA-managed `@Transactional` method does **not** un-mark the surrounding transaction as rollback-only — so the outer transaction silently rolled back even though the exception was "handled." This caused `ConnectorResource.create()` to return HTTP 201 with a real-looking connector ID while persisting nothing at all (confirmed via debug logging: `POST` returns id X, immediate `GET /connectors/X` returns 404). Root cause, not just a test issue — the same failure mode would occur in a mixed environment or if any Postgres-syntax feature parses differently than expected.
   - Fixed by checking `quarkus.datasource.db-kind` **before** attempting the Postgres-only path, in `ConnectorLeaseService.kt` and `EventInboxService.kt`.
   - **Still broken, not yet fixed:** `PipelineResource.syncConnectorPipelineMap` had the same bug (fixed this session). Other unaudited `ON CONFLICT` usages remain in `DataResource.kt` (lines ~621, ~928) and `PipelineDataService.kt` (line ~178) — these operate on tenant-provisioned dynamic schemas that assume Postgres already, lower priority, but should be audited for the same rollback-only trap if they're ever called from a shared transaction with other work that must survive.
   - **General takeaway for future work:** never assume a locally-caught DB exception is locally contained under JTA. Either avoid triggering the exception (dialect branch, as done here) or run the risky statement in `REQUIRES_NEW` if a partial-failure-tolerant design is actually intended.

2. **Missing JPA entity mappings for native-SQL-only tables broke Hibernate's test schema generation.** Test profile uses `hibernate-orm.database.generation: drop-and-create` (not Flyway) — see Known issues #3. Tables only created via raw Flyway SQL with no `@Entity` never existed in the H2 test schema, causing hard 500s. Added minimal `@Entity` classes purely for schema parity (not used for queries — the native SQL call sites are untouched):
   - `ConnectorPipelineMap.kt` (backs `connector_pipeline_map`)
   - `ConnectorRuntimeLease.kt` (backs `connector_runtime_leases`)
   - Still native-SQL-only / no entity, not yet needed by any failing test but worth auditing: `data_table_registry`, `data_blobs`, `readings` (all in `DataResource.kt`, likely fine since `DataResourceTest` wasn't in the failing set — not independently verified this session).

3. **Test-fixture bug: `AuthResourceTest`/`AuthServiceTest` `@BeforeEach` cleanup deleted `Tenant` rows before deleting the new `ApiKey` rows that FK-reference them**, causing `ConstraintViolationException` on every test in those classes once the `ApiKey` entity was introduced by the in-flight P1-T5 work. Fixed by wrapping cleanup in `SET REFERENTIAL_INTEGRITY FALSE/TRUE` (matching the already-correct pattern in `TenantResourceTest`) instead of hand-enumerating every FK-dependent table.

4. **Test-fixture bug, not a real bug: several `AuthResourceTest` tests asserted against the deprecated client-side-SHA256 `passwordHash` login path using freshly-`setup()`'d accounts.** Since `setup()` now stores Argon2id of whatever raw string it's given (`password`, or `passwordHash` treated as an opaque credential — see design comment in `AuthService.kt:161-162`), a test that computes `SHA256(password)` itself and sends it as `passwordHash` on login will never match, because the stored hash was never derived from that SHA256 digest. Fixed 5 tests (`testLoginEndpoint`, `testLoginAcceptsHexEncodedSha256Hash`, `testSetupAcceptsCustomPasswordForLogin`, `testMeEndpointWithValidToken`, `testSwitchTenantUpdatesJwtContext`) to use the `password` field directly, matching what the real (already-updated) frontend now sends. Removed now-dead `hashPasswordHex` test helper.
   - Also fixed the shared `TestAuthHelpers.createAdminToken()` to use `password` instead of computing and sending a SHA256 digest as `passwordHash` — same root cause.

## Historical triage notes (superseded)

The notes below were captured before the subsequent fixes and are retained only
as investigation history. They are not an open-defect list: the current full
offline backend suite passes with 255 tests, zero failures, and zero errors.

### A. Stale test assertions (cosmetic — behavior is likely correct, wording changed)
- `PipelineResourceTest.testCreatePipelineRejectsCyclicGraph` — expects error text "contains a cycle", actual is "Node n-import (IMPORT) cannot have incoming edges" (a more specific, arguably better message — but should be confirmed this isn't masking an actual cycle-detection regression before just updating the assertion).
- `PipelineResourceTest.testCreatePipelineRejectsImportNodeWithOutputOnlyConnector` — expects "INPUT/BOTH", actual "requires INPUT connector direction" (consistent with FR-related direction validation removing "BOTH" as a valid value elsewhere in `ConnectorResource.kt` — `direction BOTH is no longer supported`). Test assertion is simply outdated.
- `PipelineResourceTest.testCreatePipelineRejectsNodeModuleTypeMismatch` — expects "expects EXPORT module version", actual "expects one of EXPORT/DB_SAVE, found PROCESSING" (message improved, assertion stale).
- `PipelineResourceTest.testGetRunSummaryReturnsAggregateFields` — expects `windowSize: 25`, actual `15` (or `14`, flaky across runs — see below). Need to check whether the default window size intentionally changed or if this is itself a bug.
- `ConnectorResourceTest.testTestConnectorValidatesConfig` — expects 400, got 200. Needs investigation — could be a real validation regression, not just message drift.

### B. Response-shape / computed-field gaps (need real investigation, not just assertion updates)
- `ConnectorResourceTest.testDeleteConnectorRejectsWhenReferencedByPipelineGraph` / `testDeleteMqttCredentialRejectsWhenReferencedByConnector` — expect `referenceCount: 1` in response body, actual `null`. Looks like a real field that's no longer being populated.
- `ConnectorResourceTest.testDisconnectConnectorRequiresForceWhenActivePipelinesReferenceIt` — expects `requiresForce: true`, actual `null`. Same pattern — likely a real regression in the connector-impact/reference-counting response building, worth checking whether it now depends on `connector_pipeline_map` being populated correctly (this session's fixes touched that path — re-verify these three together, they may already be fixed by picking up the `ConnectorPipelineMap`/`syncConnectorPipelineMap` fix if the test needs a pipeline-create step that wasn't re-run).
- `ConnectorResourceRbaCTest.createConnectorWithoutAuthReturnsForbidden` / `listConnectorsWithoutAuthReturnsForbidden` — expect 403, got 404. Possibly the same `TestAuthHelpers`/cross-test admin-account pollution described below, or a real auth-ordering regression (404 before 403 suggests a resource-existence check running before the auth check in some path). Needs isolated reproduction.
- `ConnectorResourceUnitTest.testListWithoutTenantIdReturns400` — expects 400, got 404. Same shape as above.
- `TenantResourceTest.testTenantManagerCanGetAndUpdateLineageDefaults` — expects a non-null `currentVersion` in a 409 conflict response body, got `null`. Optimistic-locking/conflict-response field not populated — real gap in the lineage-defaults PATCH handler, not a test artifact.

### C. Cross-test fixture pollution (test-infra only, not a product bug)
- `UserResourceRbaCTest.createUserMissingEmailReturnsBadRequestWithAuth` / `UserResourceUnitTest.testCreateMissingPasswordHash` — `TestAuthHelpers.createAdminToken()` gets 401 on login, meaning some earlier-running test class creates `admin@example.com` with a different password/state than `TestAuthHelpers`'s hardcoded `AdminPass!123`, and no cleanup removes it before these classes run. Root cause not yet isolated — grepped all `api` package test classes for `admin@example.com` literal and found none beyond `AuthResourceTest` (which does clean up after itself), so the polluting class must be going through `TestAuthHelpers` itself, then mutating or deactivating the account, or somehow the H2 file-backed DB isn't fully reset between test classes despite `drop-and-create`. **Needs a fresh isolated-run investigation** (`mvn -o test -Dtest=ClassA,ClassB` bisection to find the exact culprit) rather than guessing further.

## Historical architectural note (partially superseded)

**Test suite does not validate against the real Flyway/PostgreSQL schema at all.** Test profile (`src/test/resources/application.yaml`) uses `hibernate-orm.database.generation: drop-and-create` against H2, which derives its schema from JPA `@Entity` annotations — completely bypassing the Flyway migrations (`V1`...`V14`) that define the real production schema. This means:
- Tables/columns that exist only via raw Flyway SQL (no JPA entity) silently don't exist in tests, as discovered this session with `connector_pipeline_map` and `connector_runtime_leases`.
- Flyway migrations themselves are never actually executed or validated in CI/local test runs — a broken migration (bad SQL, wrong column type, missing `IF NOT EXISTS`, etc.) would not be caught until a real deploy.
- This is explicitly called out as NFR-014 in the plan ("CI migrates a representative previous/current schema on PostgreSQL/TimescaleDB and boots the affected services successfully") and is **not yet satisfied**.

CI now applies all Flyway migrations to PostgreSQL 16, but application tests
still primarily use H2. A PostgreSQL/TimescaleDB boot-and-behavior suite remains
an open NFR-014 task — see "2026-08-18 session" below for an in-progress, not-yet-working attempt.

## 2026-08-18 session

Continued from the "Next steps" list above. Picked up P1-T3, P1-T6, and NFR-014 in order.

**P1-T3 — closed four more nested-resource isolation gaps**, added to
`CrossTenantIsolationTest.kt`:

- Tenant A membership cannot be assigned a Tenant B `roleId` on create or PATCH (the underlying
  `isRoleAssignableToTenant` check in `MembershipResource.kt` was already correct — this was a
  test gap, not a product bug).
- Tenant A cannot reference a Tenant B channel inside a multi-action alert rule's `channelIds`
  array on create or PATCH (`AlertResource.resolveActionChannels` was already correct — test gap).
- Tenant A cannot make its pipeline graph reference a Tenant B connector on an IMPORT/EXPORT node
  (`PipelineResource.kt` connector-tenant check was already correct — test gap).
- A connector's `secretRefs` pointing at a foreign tenant's secret is inert as stored config and
  safely fails (not silently succeeds) at runtime resolution rather than at write time — confirmed
  intentional/safe behavior (`SecretService.resolve` requires exact tenant match; runtime
  resolution failures are caught, not propagated as data leaks), documented via test rather than
  changed. All 15 tests in the class pass.

**P1-T6 — added real end-to-end WebSocket coverage** (`WebSocketIngestE2ETest.kt`), replacing
reliance on the mocked-`Session` unit tests in `WebSocketIngestResourceTest.kt`. Driving the real
JSR-356 transport (real client connecting to the real `@ServerEndpoint`) surfaced **two previously
undetected production bugs**, not test artifacts:

1. **Every real WebSocket ingest connection was failing.** `WebSocketEndpoint.onOpen`/`onMessage`
   run on the Vert.x IO event-loop thread under the classic `jakarta.websocket` transport Quarkus
   uses here, and `WebSocketIngestResource.authorizeSession`/`handleWebSocketMessage` are
   `@Transactional`. Quarkus refuses to start a JTA transaction from an IO thread
   (`BlockingOperationNotAllowedException`), which was previously thrown, caught by `@OnError`,
   and silently unregistered the session — so *every* real WS ingest attempt failed regardless of
   credential validity, invisibly, because no prior test exercised the real transport.
   `@Blocking`/`@RunOnVirtualThread` cannot be applied directly to classic JSR-356 `@OnOpen`/
   `@OnMessage` methods (Quarkus's `ExecutionModelAnnotationsProcessor` build step rejects it —
   those annotations are only valid on framework-recognized "entrypoint" methods, and this
   endpoint style isn't one). Fixed by injecting a `ManagedExecutor` and dispatching the
   transactional work onto it explicitly from `onOpen`/`onMessage`, keeping the IO thread free.
2. **Every ingest publish through `KafkaService.sendToPipelineSpecificInput`/`sendToPipelineOutput`
   was failing**, independent of bug #1 and independent of whether Kafka itself was reachable.
   `KafkaService`'s private `ObjectMapper()` never registered `JavaTimeModule`, and
   `EventEnvelope.occurredAt` is a `java.time.Instant` — every `objectMapper.writeValueAsString(envelope)`
   call threw before the message ever reached the outbox or the broker. This affects **HTTP ingest
   too** (`IngestResource.kt` shares the same `KafkaService`), not just WebSocket. Fixed by
   registering `JavaTimeModule()` on that mapper. (Note: `KafkaService.isKafkaAvailable()` always
   returns `true` — the "local synchronous fallback" path in `WebSocketIngestResource`/
   `IngestResource` for a down broker is currently dead code, unrelated to this bug but worth
   knowing if debugging ingest-under-broker-outage behavior later.)

   Both bugs are fixed and covered by regression tests. The full backend suite (H2 profile) was
   green before and after these changes (255+ tests, 0 failures/errors). Given the severity —
   ingest was completely non-functional over the real transport in ways no existing test could
   catch — **a similar audit of other JSR-356/reactive-messaging entrypoints and other manually
   constructed `ObjectMapper()` instances (there are ~30 in the codebase; only `KafkaService`'s was
   fixed this session, since it's the only one confirmed to serialize an `Instant`-bearing type) is
   worth prioritizing before broader Kafka/ingest reliability work (Phase 3/4) is trusted.**

**NFR-014 — PostgreSQL/TimescaleDB test profile (superseded by the completion note below).** Added
`PostgresTestProfile.kt` (`@QuarkusTestProfile`, activates config profile `postgres-it`),
`src/test/resources/application-postgres-it.yaml` (sets `db-kind: postgresql`,
`hibernate-orm.database.generation: validate`, Flyway `migrate-at-start: true`), scoped the
existing H2 datasource block in `application.yaml` behind `"%test":` so it no longer leaks into
other profiles, and added `PostgresMigrationBehaviorTest.kt` (boots the app, then exercises a
tenant, a JSONB-backed pipeline graph, and encrypted/versioned secret rotation against the real
schema). **Currently broken**: Quarkus Dev Services correctly provisions a real ephemeral
PostgreSQL 18 container (confirmed in logs — "Dev Services for default datasource (postgresql)
started"), but Flyway's `migrate-at-start` connects to the default unbound `localhost:5432`
instead of the Dev-Services-injected URL/port, so migration fails with connection-refused. This is
a Quarkus config-ordering issue between the Dev Services datasource processor and
`FlywayRecorder`, not a fundamental blocker — needs either an explicit
`quarkus.flyway.datasource`/`quarkus.datasource."<default>".devservices` binding investigation, or
moving Flyway execution out of `migrate-at-start` into an explicit runtime call after the
datasource is confirmed live. **Do not assume this profile passes** — it has never successfully
completed a run this session. Requires Docker Desktop running locally to attempt at all (it was
started this session specifically for this work). The `application-postgres-it.yaml` file carries
a top-of-file comment recording this same caveat.

## NFR-014 completion (2026-08-18)

The real PostgreSQL behavior test now passes end-to-end with Docker Dev Services. The deployment
JDBC URL was moved out of the base Quarkus configuration (it remains explicitly configured in the
existing `%prod` profile), allowing Dev Services to supply its dynamic PostgreSQL URL before
Flyway starts in the `postgres-it` test profile. The test applies all migrations to a clean
PostgreSQL 18 database, validates the resulting Hibernate schema, then verifies tenant creation,
a JSONB pipeline graph round-trip, and encrypted versioned-secret rotation through the HTTP API.

This verification uncovered and repaired two clean-migration schema gaps: V7 now establishes the
`memberships` relation it references, and new migration V24 establishes the `api_keys` table
required by the `ApiKey` entity. The targeted command
`mvn -q -Dtest=PostgresMigrationBehaviorTest test` passes with Docker Desktop available.

## Serialization and callback audit completion (2026-08-18)

The manually constructed Jackson mapper audit is complete. All application-owned local mappers
now use `JsonObjectMappers`, which registers `JavaTimeModule` and writes ISO-8601 dates rather
than numeric timestamps. This prevents an untyped payload containing an `Instant` from failing
before Kafka, audit, alert, connector, execution, or JSON-persistence work reaches its transport
or database. `JsonObjectMappersTest` proves the regression case.

There is only one JSR-356 endpoint (`WebSocketEndpoint`), and it was already worker-dispatched
before calling transactional ingest operations. The Reactive Messaging pipeline consumer is
already explicitly `@Blocking` before its transaction. No further IO-thread transaction hazard
was found. The audit also exposed a test configuration issue caused by custom test profiles
replacing Quarkus's implicit `test` profile; `SmallGlobalExecutionQuotaProfile` now explicitly
keeps that profile, and the H2 test datasource supplies matching non-empty test-only credentials.
`mvn -q -Dtest=ExecutionGlobalQuotaTest,JsonObjectMappersTest,PipelineEventEnvelopeValidatorTest,AuditServiceTest test`
passes.

## Project policy update (2026-08-18)

Per explicit user direction: this project currently has no production deployment and no live
instances (see "Project policy: no production data, no live instances yet" near the top of this
file). Backward-compatibility shims and data-migration-for-existing-rows work are **not** required
right now — see that section for the exact scope of what still is required (schema/DDL migrations
still apply and are still tested against real Postgres per NFR-014).

## 2026-08-18 session (continued) — scoped code-level gap closure

Picked up four concrete, scoped backend gaps identified by an explore-agent audit against the
spec, plus one further round after checking in with the user. All are implemented, tested, and
documented in their respective phase-progress sections above:

1. **Kafka bounded retry before DLQ** (Phase 3) — `PipelineRunResolver` bean + `withTransientRetry`.
2. **Connector lease ownership diagnostic endpoint** (Phase 4) — `GET /connectors/{id}/lease`.
3. **Reject inline connector credentials** (Phase 5) — `validateConnectorConfig` in `ConnectorResource`.
4. **Alert severity/message as real DB columns** (Phase 6) — migration `V25`, `AlertRule` entity fields.
5. **PipelineVersion execution-pinning fix** (Phase 5) — `PipelineConsumer` now reads the run's
   pinned graph instead of the live mutable `Pipeline.graphDefinition`; a real reproducibility bug,
   not just a test gap.
6. **Targeted application-log credential redaction** (Phase 1/P1-T7) — wired `SensitiveDataRedactor`
   into the highest-risk connector-runtime log/error-surfacing call sites (MQTT, REST, WebSocket
   connector managers) identified by an explore-agent audit of every LOG call site in the codebase.

Full backend suite (excluding the pre-existing-flaky Testcontainers-Kafka `PipelineConsumerDlqIT`,
confirmed flaky independent of this session's changes by reproducing it against an unmodified
`PipelineConsumer.kt`) is green at 276 tests, 0 failures, 0 errors after each change. Frontend
`npm run build` passes. `PostgresMigrationBehaviorTest` confirms migration `V25` applies cleanly to
real PostgreSQL.

## 2026-08-19 session — further scoped gap closure

Continued from the "2026-08-18 session (continued)" list, verifying it was still accurate before
starting (it was — a stale test-pollution note about `admin@example.com`/`UserResourceRbaCTest`
described in "Historical triage notes" was checked and confirmed **no longer reproduces**; that
note is superseded/moot as of this session). Picked up four more concrete gaps:

1. **`data_table_registry` upsert — both the JTA rollback-only trap AND a missing-entity gap,
   both live** (Phase 5 data-safety). `DataResource.registerTable` used a bare PostgreSQL-only
   `ON CONFLICT` inside a `@Transactional` method with a generic `catch (e: Exception)` — the exact
   rollback-only trap already fixed elsewhere this session in `ConnectorLeaseService`/
   `EventInboxService`. Fixed with the same upfront-`dbKind`-check pattern
   (`upsertTableRegistryEntry`). Separately, `data_table_registry` had no JPA `@Entity` at all, so
   Hibernate's test-profile `drop-and-create` schema generation never created it — the real
   underlying error was `Table "data_table_registry" not found`, not just a SQL-grammar mismatch.
   Added `DataTableRegistryRecord` (schema-parity-only entity, matching the existing
   `ConnectorPipelineMap`/`DataBlobRecord` pattern) to close that gap too; its `schema_def` column
   uses `columnDefinition = "jsonb"` so `PostgresMigrationBehaviorTest`'s `validate`-mode schema
   check also passes against the real Flyway-migrated column type. `DataResourceTest` gained
   `testRegisterTableUpsertsRegistryEntryOnReRegistration`, which failed with the original
   "table not found" error before both fixes and passes now; this also means the success path of
   `registerTable` was previously never exercised by any test.
2. **`PipelineDataService` DB_SAVE upsert — genuinely broken against H2, not just untested**
   (Phase 5 data-safety). `executeSave`'s upsert-mode `ON CONFLICT ... DO UPDATE ... EXCLUDED`
   SQL, and separately `ensureTableExists`'s unconditional `TIMESTAMPTZ`/`JSONB` DDL types, are
   PostgreSQL-only and fail outright against H2 (confirmed via `SQLGrammarException`s for each,
   found by writing the first real test for this code path). Unlike Sites A/`ConnectorLeaseService`,
   this class uses a plain non-JTA JDBC `Connection` with manual commit/rollback, so it's a
   straightforward H2-compatibility bug rather than the rollback-only trap. Fixed by branching
   `executeSave`'s SQL to H2's `MERGE INTO ... KEY (...) VALUES (...)` form (matching the existing
   pattern in `DataResource.kt:618-630`) and making `ensureTableExists`/`inferSqlType`/
   `mapExplicitType` dialect-aware (`TIMESTAMP WITH TIME ZONE`/`CLOB` for H2). New
   `PipelineDataServiceTest` (previously nonexistent for this class) covers DB_SAVE upsert
   insert-then-update-by-key-columns and DB_SAVE append mode with JSON/timestamp columns — both
   failed before the fix, both pass now.
3. **WebSocket idle timeout made configurable, but its actual mechanism is now an open question**
   — see the P1-T6 row above for the full writeup. Source fix is real and correct; the proving test
   is intentionally left failing, not disabled, pending real investigation into what actually
   controls idle-close timing in this stack.

Full backend suite (excluding the pre-existing-flaky `PipelineConsumerDlqIT` and the intentionally-
left-failing `WebSocketIdleTimeoutE2ETest`) is green at 279 tests, 0 failures, 0 errors after each
change in this session. `PostgresMigrationBehaviorTest` and `DataResourceTest` both pass against
real PostgreSQL and H2 respectively after the `data_table_registry` entity fix.

## 2026-08-19 session (continued) — SQL-dialect sweep + hostile-module execution coverage

Verified the previous session's suite was still green before starting (it was). Followed up on
the recommended "Next steps" list with two items:

1. **Audited remaining SQL-dialect call sites (item 7 above).** Swept the whole backend for other
   unconditional `ON CONFLICT`/`TIMESTAMPTZ`/`JSONB`/`gen_random_uuid()`/`EXCLUDED.` usage.
   Everything else found was already dialect-branched (`ConnectorLeaseService`, `EventInboxService`,
   `DataResource`, `PipelineDataService`) — no new unbranched instances. One already-correct but
   completely untested site was found: `PipelineResource.syncConnectorPipelineMap`'s H2 fallback
   branch (line ~1071) ran on every pipeline create/update with a connector-referencing node, but no
   test ever asserted on the resulting `connector_pipeline_map` rows. Added
   `testCreateAndUpdatePipelineRebuildsConnectorPipelineMap` to `PipelineResourceTest`, which
   verifies both the initial H2 insert and that updating a pipeline's graph to reference a different
   connector deletes the old mapping row rather than accumulating it (the delete-then-reinsert
   contract `syncConnectorPipelineMap`'s doc comment describes). Passed on first correct attempt —
   this was a coverage gap, not a live bug, unlike the two dialect bugs found in the prior session.
2. **Hostile-module live-execution coverage for the Docker backend (item 1 above, Docker half).**
   See the Phase 2 execution progress section above for the full writeup — added
   `DockerServiceHostileModuleTest` (7 tests against a real Docker daemon) replacing the previously
   `@Disabled`, empty-bodied `DockerServiceTest`, and found + fixed a real Windows-only
   `ProcessBuilder` argument-quoting bug in the process (`envArg` helper). Kubernetes-side
   hostile-execution coverage remains out of scope — no local Kubernetes cluster/Dev-Services
   equivalent exists in this repo, a larger infra lift than the Docker path (which just needed a
   Docker daemon, already available).

Full backend suite (excluding the pre-existing-flaky `PipelineConsumerDlqIT` and the intentionally-
left-failing `WebSocketIdleTimeoutE2ETest`) is green at 289 tests, 0 failures, 0 errors — up from
279 (10 new tests: 1 connector-pipeline-map test, 7 hostile-module tests, 2 `envArg` policy tests).

## 2026-08-19 session (continued further) — live execution cancellation coverage

Added `ExecutionCancellationLiveDockerTest` — see the Phase 2 execution progress section above for
the full writeup. This closes the remaining half of "Next steps" item 1 that Docker actually can
cover (Kubernetes hostile/cancellation coverage is still out of scope — no local cluster).

**WebSocket idle-timeout — one real layer identified, but NOT confirmed as the actual mechanism;
do not build on this without re-verifying (2026-08-19).** `mvn -o dependency:tree` shows this
project's JSR-356 provider is **not Undertow** (no `io.undertow` dependency resolves at all) — it's
`io.quarkus.http:quarkus-http-websocket-vertx:5.4.0`, Quarkus's own Vert.x-native JSR-356
implementation (packaged under the `io.undertow.websockets.vertx` class namespace for API
compatibility, which is what caused the earlier "Undertow" misdiagnosis). Decompiling its classes
found no idle-timeout logic at all. Decompiling `io.vertx.core.http.HttpServerOptions`
(`vertx-core:4.5.25`) found `DEFAULT_WEBSOCKET_CLOSING_TIMEOUT = 10` (10 seconds), confirmed via
bytecode (`bipush 10 / putfield webSocketClosingTimeout`) — numerically matching the very first
close-timing observation. **However**, on reflection this field's well-documented Vert.x semantics
are "how long the server waits for the client's close-handshake acknowledgement after something
else has already decided to close the connection" — i.e. a close-handshake grace period, not an
independent idle-detection trigger. If that's correct, this finding does NOT actually explain what
*decides* to close an idle connection in the first place, and a `HttpServerOptionsCustomizer`
bean calling `setWebSocketClosingTimeout(...)` (Quarkus's real, confirmed-to-exist extension point
for this — `io.quarkus.vertx.http.HttpServerOptionsCustomizer`) would very likely NOT fix the
observed behavior. A draft of that customizer was written and then deliberately deleted rather than
committed once this doubt surfaced, rather than shipping a fix built on an unverified assumption.
Neither `quarkus.http.idle-timeout` (default `30M`) nor `quarkus.http.read-timeout` (default `60S`)
— the two real Quarkus-exposed HTTP-level timeout properties found via `quarkus-vertx-http`'s
config model — match the observed ~10s either. **Net state: still not resolved.** What IS actually
deciding to close an idle WebSocket connection in this stack remains unidentified; the
`webSocketClosingTimeout` finding is a real, verified fact (the constant exists and equals 10) but
its causal relevance to the observed bug is unconfirmed and should not be assumed correct by a
future session without checking Vert.x's actual source (not just decompiled bytecode) for what
`webSocketClosingTimeout` governs, or by re-running `WebSocketIdleTimeoutE2ETest` with added
server-side instrumentation logging exactly which timeout/handler fires. The separately-observed
drift across repeated `mvn -Dtest=...` invocations in one session (10s→20s→45s→90s) also remains
unexplained. `WebSocketIdleTimeoutE2ETest` remains intentionally failing, not disabled.

## 2026-08-19 session (continued further still) — retention, security headers, graph schema versioning

User asked to continue implementing the spec "until finished." Explicitly flagged before starting
that true completion (full Phase 7 microservice extraction, live Kubernetes HA validation, CI
container scanning/SBOM, backup/restore rehearsals, the Phase 9 acceptance gate) needs
infrastructure and time this session cannot provide — user chose "do everything," i.e. keep closing
every remaining code-level gap and produce scaffolding/documentation for the infra-heavy phases.
This entry covers the code-level half; infra scaffolding follows in a later entry.

Re-audited the spec's functional/non-functional requirements against current code (not just the
prior "Next steps" list) to find what hadn't been explicitly checked yet. Found and closed three:

1. **FR-031 retention policies — was almost entirely missing.** Only blobs had any retention
   mechanism (`BLOB_FAILED_RETENTION_DAYS`, manual-trigger only); zero `@Scheduled` jobs existed
   anywhere and `quarkus-scheduler` wasn't even a dependency. Added it, plus
   `RetentionCleanupService`: independently configurable retention windows (`dataturntable.retention.*-days`,
   each defaulting to `-1`/indefinite so nothing deletes until a deployment opts in explicitly,
   matching the existing blob-retention precedent) for `pipeline_runs` (node logs cascade-delete
   via the existing FK), `alert_history`, `audit_events`, and `readings` (raw time-series data), plus
   a daily `@Scheduled` job gated on `RuntimeRoleConfig.isControlApi()` so a multi-replica deployment
   doesn't race duplicate cleanup. `RetentionCleanupServiceTest` seeds old/recent rows in all four
   tables and verifies both halves of the contract: configured retention deletes old rows and
   spares recent ones, and the indefinite default (-1) never deletes anything. Execution
   output/logs and "intermediate results" have no separate storage outside `pipeline_runs`/
   `pipeline_run_node_logs`/the ephemeral `Execution` row, so no separate retention category exists
   for them — covered by the pipeline-runs rule.
   - **Detour that cost real time and taught a real lesson:** first attempt added a JPA `@Entity`
     for `readings` (matching the established `DataTableRegistryRecord`/`ConnectorPipelineMap`
     schema-parity-entity pattern) so H2's drop-and-create test schema would include it. This broke
     `PostgresMigrationBehaviorTest` with "missing table [public.readings]" under Hibernate's real-
     Postgres `validate` mode. Spent significant time chasing a wrong theory (that `readings` having
     no `PRIMARY KEY` in its Flyway DDL was confusing Hibernate's table-existence check) before
     discovering the actual cause: **a stale compiled `.class` file in `target/classes` left over
     from a still-broken intermediate version of the entity**, invisible to `mvn -q -o compile`
     because Maven's incremental compiler didn't recompile-and-overwrite it correctly after the
     file was deleted from source. A clean rebuild resolved it immediately. Given `readings` has no
     primary key in production and no test actually needs to query it via JPA, the entity was
     removed entirely rather than reintroduced — `RetentionCleanupServiceTest` instead creates the
     table directly via native `CREATE TABLE IF NOT EXISTS` DDL matching the real migration exactly,
     which is more correct anyway (H2 schema-parity entities are a workaround for tables real code
     needs to query through JPA; `readings` doesn't). **Lesson for future sessions**: if a
     Hibernate schema-validation or generation error looks inexplicable given the current source, rule out a
     stale `target/classes` artifact (`mvn clean` or a targeted `rm` of the specific `.class` files)
     before spending time on deep Hibernate/dialect theories.
2. **NFR-006 CSP/security headers — completely missing, now added.** Grepped for
   `Content-Security-Policy`/`X-Frame-Options`/`Strict-Transport-Security`/etc. across the entire
   backend and frontend trees — zero hits anywhere. Added `SecurityHeadersFilter`
   (`ApiErrorContract.kt`, alongside the existing `RequestIdFilter`/`ApiErrorResponseFilter`
   `ContainerResponseFilter`s) applying a maximal `default-src 'none'` CSP (this backend is a pure
   JSON API in production — the SPA is a separately-built static bundle, and Swagger UI is
   dev/test-only per Quarkus's default `quarkus.swagger-ui.always-include`, so it never reaches
   this filter in prod) plus `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`,
   `Referrer-Policy: no-referrer`, `Strict-Transport-Security`, and a restrictive
   `Permissions-Policy`. `ApiErrorContractTest` gained a case asserting all headers appear on a real
   JAX-RS response (note: `/openapi` is served by a Vert.x-level route that bypasses the JAX-RS
   filter chain entirely, so the test exercises a real resource method instead).
3. **FR-010 graph schemaVersion — was recorded but never validated.** `PipelineVersion.schemaVersion`
   existed and was populated from the graph's `schemaVersion` key at snapshot time, but nothing
   validated that value, and the live mutable `Pipeline.graphDefinition` (what's actually
   re-validated on every create/update) had no version-awareness at all.
   `PipelineResource.validateGraphDefinition` now checks `schemaVersion` against a
   `SUPPORTED_GRAPH_SCHEMA_VERSIONS` constant (currently `{1}`). Per explicit user direction, a
   **missing** `schemaVersion` defaults to implicit legacy version 1 rather than being rejected
   outright (non-breaking for every existing caller/test that predates this field), but an
   **explicitly wrong/unsupported** version IS rejected with a clear error — this closes the real
   gap (an incompatible graph schema silently accepted) without a breaking change with no live data
   to justify it. `testCreatePipelineAcceptsExplicitSupportedSchemaVersionAndRejectsUnsupported`
   covers both the accept and reject paths through the real `create` endpoint.

Full backend suite (excluding the pre-existing-flaky `PipelineConsumerDlqIT` and the intentionally-
left-failing `WebSocketIdleTimeoutE2ETest`) is green at 294 tests, 0 failures, 0 errors.
`PostgresMigrationBehaviorTest` passes against real PostgreSQL. Frontend `npm run build` passes.

## 2026-08-19 session (continued further still) — infra scaffolding for NFR-018/FR-032

Second half of the "do everything" mandate from the previous entry: since a live registry, CI
runner, and Kubernetes cluster aren't available in this sandbox, this batch produces
ready-but-unexecuted scaffolding for the image-immutability and CI-scanning requirements, with
everything that *could* be verified locally actually verified.

1. **Third-party image floating tags pinned to real digests.** `docker/k8s/storage/postgres.yaml`
   (`timescale/timescaledb:latest-pg16` → `timescale/timescaledb:2.17.2-pg16@sha256:4e459e21…`,
   both container references) and `docker/k8s/monitoring/alloy.yaml`
   (`grafana/alloy:latest` → `grafana/alloy:v1.5.1@sha256:01a63f4e…`). Digests were captured via a
   real `docker pull` + inspect against the actual public registries, not invented. These are the
   only two third-party `:latest` references that existed in `docker/k8s/**`.
2. **Kustomize `images:` transformer added for the project's own images**
   (`docker/k8s/kustomization.yaml`) — `dataturntable/backend`, `dataturntable/frontend`,
   `dataturntable/docs` each get an entry with `newTag: latest` and an inline comment stating
   plainly that this is a placeholder: CI must run
   `kustomize edit set image dataturntable/backend=registry/...@sha256:<digest>` (or equivalent)
   with the real pushed digest before any real apply. **Honestly, no CI job pushes an image to any
   registry yet** — this transformer is wired and ready but has nothing feeding it. Documented as a
   real, unresolved gap in the file itself, not glossed over.
3. **Hardcoded Grafana admin credentials removed from `docker/k8s/backend/deployment.yaml`.** All
   four `DATATURNTABLE_GRAFANA_{DASHBOARD,MONITORING}_ADMIN_{USER,PASSWORD}` env vars were
   `value: "admin"` literals in the manifest. Converted to `secretKeyRef` against
   `dataturntable-secrets` with `optional: true` — chosen deliberately over a required secret
   because these two Grafana provisioners are optional features that should silently stay disabled
   when no credential is supplied, not crash-loop the pod. Also replaced a stale TODO comment and
   changed `imagePullPolicy: Always` → `IfNotPresent` (4 occurrences) to match the immutable-digest
   pinning direction — once images are referenced by digest, `Always` no longer serves a purpose
   and only adds registry round-trips. `docker/k8s/storage/secrets.yaml` gained a comment
   documenting every key a deployer needs to populate, including the 4 new Grafana keys.
4. **CI container build/scan/SBOM + dependency scanning jobs added** (`.github/workflows/ci.yml`):
   - `container-build-scan-sbom`: builds the real backend (`backend/Dockerfile`) and frontend
     (`docker/frontend.Dockerfile`) images via `docker/build-push-action@v6` with `push: false,
     load: true` (no registry credentials exist in this repo), scans both with
     `aquasecurity/trivy-action@0.29.0` for CRITICAL/HIGH CVEs (`exit-code: '1'`, so the job fails
     the build on a real hit), and generates a CycloneDX SBOM per image with
     `anchore/sbom-action@v0`, uploaded via `actions/upload-artifact@v4` with a 90-day retention.
     **Verified locally**: ran `docker build -f backend/Dockerfile .` for real against this
     repository — multi-stage Maven+Quarkus build succeeds, `BUILD SUCCESS`, image exports
     cleanly. This confirms the exact Dockerfile the CI job builds is not broken; the job's own
     YAML structure and the Trivy/SBOM actions themselves are not executable in this sandbox (no
     GitHub Actions runner available) and remain unverified beyond careful manual re-reading.
   - `dependency-vulnerability-scan`: OWASP Dependency-Check on the backend Maven build
     (`-DfailBuildOnCVSS=9 -Dformat=SARIF`, reading an `NVD_API_KEY` from a `DC_NVD_API_KEY`
     repository secret that does not exist yet in this repo) and `npm audit --audit-level=critical`
     on the frontend. Commented honestly in the job itself: without the NVD API key configured and
     without `actions/cache` for the downloaded CVE database, this job is not yet guaranteed to
     pass or even complete promptly in real CI — it's structurally ready, not proven green.
   - Both jobs placed between the existing `kubernetes-manifests` and `postgresql-migrations` jobs.
     No YAML linter was available in this environment (no Python, no PowerShell `ConvertFrom-Yaml`
     cmdlet); correctness was checked by careful manual indentation review only.

**What remains a real, undone gap after this batch**: no CI job actually pushes an image to any
registry, so the kustomize `images:` transformer added in step 2 has no real digest to consume yet;
the two new CI jobs have not executed in real GitHub Actions (no runner available here); the
`DC_NVD_API_KEY` secret does not exist in the repo. All three are documented inline at their exact
location rather than left implicit.

## 2026-08-19 session (continued further still) — NFR-017 backup/restore, NFR-019 support matrix

Continuing the same "do everything" infra-scaffolding mandate, tackled the two remaining
documentation-shaped requirements that don't need a live Kubernetes cluster to genuinely complete:
NFR-017 (backup/restore must be *operationally proven*) and NFR-019 (documentation must classify
features as Implemented/Experimental/Deprecated/Planned).

1. **NFR-017 — actually ran a backup/restore drill, found and fixed a real bug.** Wrote
   `scripts/backup/backup.sh`/`restore.sh` targeting the Compose dev topology (`pg_dump
   --format=custom` for both databases, `tar` for the blob directory, SHA-256 manifest). Rather
   than leave these unverified, brought up real `dataturntable-postgres` +
   `dataturntable-pipeline-postgres` TimescaleDB containers, seeded marker rows, and ran the
   scripts for real. **First attempt genuinely failed**: `pg_restore --clean --if-exists` against
   a TimescaleDB-enabled database issues piecemeal `DROP` statements for `_timescaledb_catalog.*`
   objects while the extension is still active, which corrupts enough restore state that `public`
   schema table data silently never comes back — `pg_restore` still exits with only "warning:
   errors ignored on restore: 30", not a hard failure, so this would have been very easy to ship
   undetected if the drill had trusted the exit code instead of checking row counts. Fixed by
   having `restore.sh` `DROP DATABASE`/`CREATE DATABASE`/`CREATE EXTENSION timescaledb` first (a
   clean single-unit extension teardown) and restoring into the empty database with no `--clean`
   flag. Reran the full drill from a fresh seed — primary DB, pipeline DB, and blob storage
   (tested against a throwaway container standing in for the filesystem-backed blob dir) all
   round-tripped correctly, including exact byte-for-byte blob content. Documented the whole
   incident, the fix, and the mandatory post-restore integrity-check queries in
   `docs/operations/backup-restore.md` — explicitly scoped to the Compose topology only; the
   Kubernetes path is flagged Planned/unverified since no cluster exists here.
2. **NFR-019 — feature-level support matrix.** `README.md` already had a deployment-*target*
   support table; NFR-019 asks for feature-level classification too. Added
   `docs/operations/support-matrix.md` covering every FR/NFR from the spec's functional list plus
   the operational capabilities (backup/restore, CI scanning), classified Implemented/
   Experimental/Planned based on the honest current state (cross-checked against this status
   tracker, not aspirational) — notably marks FR-029 (microservice/gateway split) and FR-022's
   S3/MinIO target as Planned rather than overstating the current runtime-role/local-filesystem
   implementations as the spec's actual target architecture.

Both new docs are linked from `docs/README.md`. No code paths changed in this entry — pure
documentation plus new backup/restore tooling, all exercised against real containers rather than
written speculatively.

## 2026-08-20 session — FR-032 audit closure, WebSocket idle-timeout root cause found and fixed

User asked to continue toward the spec; a scoped audit (FR-032's specific checklist plus a
duplicate-dependency/TODO/FIXME sweep) turned up only three small, previously-undocumented items,
all closed this session:

1. **`DataResource.uploadBlob`/`downloadBlob` heap-buffering FIXME** — reviewed and deliberately
   left as documented debt rather than rushed. Upload size is already bounded
   (`maxBlobUploadBytes`/`maxTenantBlobBytes`, checked before any buffering), so the real risk is
   inefficiency under concurrent large transfers, not unbounded memory exhaustion. A true fix
   (streaming multipart + streaming download) is a large enough change to deserve its own focused
   session rather than a rushed side-fix here.
2. **Frontend `Alerts.tsx` stale-toast-timer FIXME — fixed.** `showToast` now tracks its pending
   `setTimeout` in a `useRef` and clears the previous one before scheduling a new one (also cleared
   on unmount). Verified via `npm run typecheck` and `npm run build`, both clean.
3. **`vite-env.d.ts` missing `VITE_PUBLIC_API_URL` type — fixed.** Declared as optional on
   `ImportMetaEnv`, matching how `api.ts` actually reads it (may be unset).

Then asked to "perform the microservice extraction" (FR-029) mid-turn; explained via
AskUserQuestion that a full 11-service extraction with a real API Gateway and workload-identity
enforcement is a multi-month rewrite this session can't responsibly attempt as one pass without
real risk to the working system, and offered three concrete scopes (one real service end-to-end,
gateway+workload-identity first, or a written extraction plan). User chose to defer entirely
("Finish everything else") — FR-029 remains open and unattempted; see
`docs/operations/support-matrix.md` for its current honest status. Do not re-attempt a full
extraction without the user opting back into one of those three scopes explicitly.

**Root-caused and fixed the WebSocket idle-timeout mystery** that every prior session's entry on
this topic left open. This was the most significant technical finding of the session:

- Decompiled `quarkus-http-websocket-core:5.4.0`'s `UndertowSession.setMaxIdleTimeout(long)` (the
  real JSR-356 `Session` implementation backing this Quarkus version's WebSocket support) and found
  its method body is a single unconditional `return` — **a complete no-op**. Calling
  `session.maxIdleTimeout = ...` (as `WebSocketEndpoint.configureAuthorizedSession` already did)
  has never had any effect on this stack. This finally explains why every previous session's
  attempts to make the configured value control close timing failed with no discoverable pattern.
- Confirmed directly (not inferred) that there was also no other server-side idle enforcement:
  instrumented `WebSocketIdleTimeoutE2ETest`'s client with `@OnError` logging and a polling loop on
  `session.isOpen()`, and observed a genuinely idle session sit open for 150+ seconds with
  `isOpen() == true` and neither `@OnClose` nor `@OnError` ever firing. The "server closes it
  eventually" behavior earlier sessions observed (at ~10s/~20s/~45s/~90s, with no pattern) was
  actually the *test's own* `finally { session.close() }` block firing after each run's assertion
  timeout — a self-inflicted artifact of the test, not real server behavior. There was never a
  working idle-timeout mechanism in production, only in the test's own scaffolding.
- **Fixed for real** in `WebSocketIngestResource.kt`: added a `lastActivity: ConcurrentHashMap<Session, Long>`
  updated on registration and on every inbound message, and a `@Scheduled` reaper
  (`reapIdleSessions`, interval configurable via the new
  `dataturntable.ingest.websocket.idle-reap-interval`, default 10s) that closes any session whose
  last activity is older than the existing `idle-timeout-ms` setting. `WebSocketEndpoint.kt`'s
  now-inert `session.maxIdleTimeout = idleTimeoutMs` line is kept (harmless, correct per the
  JSR-356 contract, and free if a future Quarkus version ever implements it for real) with a
  comment explaining it currently does nothing and pointing at the real mechanism.
- Rewrote `WebSocketIdleTimeoutE2ETest` to verify the real mechanism directly against the server's
  own session-tracking state (`WebSocketIngestResource.sessionCount`) instead of the WebSocket test
  client's close-callback delivery, since that client was independently proven unreliable in this
  environment. **The test now passes** — verified end-to-end: a session registered at
  `idle-timeout-ms=1500`/`idle-reap-interval=500ms` was closed roughly 2.5s after its last activity
  in a real run, matching the configured timeout plus reaper granularity almost exactly. Full
  backend suite (296 tests) passes clean with zero `[ERROR]` lines and zero skipped-due-to-
  known-failure entries — this is the first session where that test is not on the "intentionally
  left failing" list.

## 2026-08-20 session (continued) — PipelineConsumerDlqIT root cause found and fixed

Continuing to work through the remaining "pre-existing-flaky" test carried in every prior session's
exclusion list. Ran it in isolation and it failed reproducibly (not flaky-intermittent — failed
every time), with the consumer's own `"Received pipeline message"` log line never appearing at all
across a 45-second, 9-attempt retry window. That ruled out a timing race and pointed at the message
never reaching `PipelineConsumer.consume()` at all.

Root cause: the `%test` profile block in `application.yaml` sets both
`dataturntable.runtime.background-enabled: false` and
`mp.messaging.incoming.pipeline-input.enabled: false` — correct defaults for the rest of the suite,
which doesn't want the pipeline Kafka consumer wired up during ordinary unit tests. But
`PipelineConsumer.consume()` short-circuits to a no-op `"skipped"` result whenever
`RuntimeRoleConfig.ownsPipelineRuntime()` is false (`backgroundEnabled && isPipelineExecutor()`),
which it always was under those test-profile defaults. `PipelineConsumerDlqIT`'s own
`KafkaTestResource` config overrides never touched either setting, so the app's own consumer method
was never actually invoked in any run — the "dead-letter never appears" failure was 100%
reproducible, not flaky, and had nothing to do with poison-message handling itself, which was never
reached.

Fixed with a dedicated `PipelineConsumerDlqTestProfile` (`QuarkusTestProfile`) that explicitly
re-enables both settings for this one test class, leaving every other test's defaults untouched.
Also hardened the test itself while investigating: pre-creates the Kafka topic via `AdminClient`
(matching the working pattern already used in `EventOutboxReplayE2EIT`) and retries the send in a
loop rather than a single fire-and-forget produce, as cheap insurance against the separate,
legitimate consumer-group-join race that a fresh Testcontainers Kafka broker can still have
regardless of this fix.

Verified: 4 consecutive isolated runs all pass (previously 0/1 passed). Full backend suite (now 296
tests, all of them — no exclusions) passes clean, zero `[ERROR]` lines. **This is the first session
where the backend suite has zero known-failing or excluded tests of any kind** — both
`WebSocketIdleTimeoutE2ETest` and `PipelineConsumerDlqIT`, the two tests every prior session's
"Full backend suite (excluding...)" sentence had to carve out, are now genuinely fixed and included.

## 2026-08-20 session (continued) — connector two-replica lease fencing verified under real concurrency

User invoked the repo's `.github/agents/spec-implementer.agent.md` persona to continue implementing
the spec (excluding the full microservice extraction, which stays deferred per the prior session's
explicit user decision) without stopping, using this file for progress tracking. That agent
definition's own suggested status path (`.agent-status/progress.md`) and commit/branch policy were
not adopted — this file is the established, much more detailed tracker for this project, and no
commits/branches/pushes happen without being asked, consistent with the whole session's standing
behavior; only the "keep working autonomously without frequent check-ins" spirit of the persona was
followed.

FR-018 ("two replicas produce one valid owner... a stale owner cannot overwrite current
authoritative state") was covered by `ConnectorLeaseServiceTest`, but only via two
`ConnectorLeaseService` instances sharing one `EntityManager`/one JTA transaction — real correctness
here depends on Postgres's actual row-level locking during the atomic
`INSERT ... ON CONFLICT ... DO UPDATE ... WHERE` upsert in `ConnectorLeaseService.acquireOrRenew`,
which a shared-transaction unit test cannot exercise at all (there's no real concurrent access to
race). Added `ConnectorLeaseConcurrencyPostgresIT`, run against real PostgreSQL via
`PostgresTestProfile`/Dev Services:

- 8 threads, each with its own real `EntityManager`/database connection/transaction (via
  `EntityManagerFactory.createEntityManager()`, not CDI injection — `@Transactional` interception
  doesn't apply to manually-constructed beans, so each call runs inside an explicit resource-local
  `EntityTransaction` instead), synchronized to fire `acquireOrRenew` at the same instant via a
  `CyclicBarrier` against the same connector lease row.
- A second scenario: an initial owner acquires the lease, its expiry is forced via a genuinely
  separate connection, then 5 challenger threads race the takeover concurrently; verifies exactly
  one wins, the fencing token strictly increases, and the original (now-stale) owner's `owns()`
  check correctly returns false against the old token.
- Iterated through three real bugs while building this (documented in the test/commit history, not
  repeated here): (1) manually-constructed `ConnectorLeaseService` instances need explicit
  `EntityTransaction` management since they bypass CDI's `@Transactional` interceptor; (2) real
  Postgres enforces `connector_runtime_leases.connector_id`'s FK to `connectors(id)` — unlike the H2
  test schema — so the test must persist a real `Tenant`/`Connector` row first; (3)
  `ConcurrentHashMap` rejects null values, so a losing racer's `null` claim had to be wrapped in
  `Optional`.
- **Both tests pass against real PostgreSQL**: exactly one winner in the 8-way concurrent
  acquisition race every run, and exactly one winner with a correctly-incremented fencing token in
  the takeover race. This is now a genuine, executed proof of FR-018's core safety property under
  real concurrent transactions — not just a simulated single-transaction unit test.

Full backend suite (296 tests, default H2 profile) re-verified green after this addition; the new
`*PostgresIT` test requires `-Dquarkus.test.profile=postgres-it` and a Docker daemon, matching the
existing `PostgresMigrationBehaviorTest` convention, so it does not run as part of the default `mvn
test` and does not affect that count.

## 2026-08-20 session (continued) — CI image build/push to GHCR wired into the digest transformer

Closed the concrete, non-cluster-dependent gap flagged in the prior entry's "Next steps": no CI job
pushed an image to any registry, so `docker/k8s/kustomization.yaml`'s `images:` transformer had
nothing real to consume. Added push-to-GHCR steps to the existing `container-build-scan-sbom` job
in `.github/workflows/ci.yml`:

- Pushes both images to GitHub Container Registry (`ghcr.io/<repo>/backend`,
  `ghcr.io/<repo>/frontend`) using the workflow's own built-in `GITHUB_TOKEN` — no external
  registry credential needs to be configured for this, since GHCR is native to GitHub Actions.
- **Gated to `push` events on `main` only, never on `pull_request`** — a fork-originated pull
  request must never receive a token capable of pushing packages to this repository's registry.
- Captures the real pushed digest from `docker/build-push-action@v6`'s own `digest` output and
  writes a downloadable artifact (`image-digests-<sha>`) containing the exact
  `kustomize edit set image` command a real deploy step would run to populate
  `docker/k8s/kustomization.yaml`'s `images:` transformer with the real digest instead of the
  `:latest` placeholder — closing the loop that transformer's own comment described as missing.
- Added `packages: write` to the workflow's top-level `permissions` block (previously only
  `contents: read`), required for any GHCR push.

Verified what could be verified without a live GitHub Actions runner: both Dockerfiles
(`backend/Dockerfile`, `docker/frontend.Dockerfile`) build successfully locally via real `docker
build` (`BUILD SUCCESS` for the Maven/Quarkus backend build, clean Vite frontend build), confirming
the images this job builds are not broken. Also found and used a real YAML parser this session
(`js-yaml`, present as a transitive dependency under `frontend/node_modules` — no global Python/
PowerShell YAML cmdlet was available, so this was previously unverified beyond manual reading):
`node -e "require('./frontend/node_modules/js-yaml').load(...)"` confirms `.github/workflows/ci.yml`
parses as valid YAML with all six expected jobs present. The push/digest-capture steps themselves
still cannot be executed in this sandboxed environment (no GitHub Actions runner, no real GHCR
credential), so they remain unverified beyond structural correctness — this is the same honest
caveat as the rest of this file's CI-related entries.

**Future sessions**: `frontend/node_modules/js-yaml` (or an equivalent, if that dependency is ever
removed/reinstalled without it) is a genuine capability for validating any future `.yml`/`.yaml`
edit in this repo — use it instead of manual-review-only when editing CI/Kubernetes YAML.

## 2026-08-20 session (continued) — NFR-020 OpenTelemetry scaffolding, disabled by default

Asked the user before touching this one specifically because it's not test/doc-only like the rest
of this session's work — adding a new runtime dependency changes behavior in every environment,
and this sandbox has no OTLP collector to actually verify a real trace against. User chose to add
it disabled-by-default rather than skip it entirely.

Added `quarkus-opentelemetry` to `backend/pom.xml`, and in `application.yaml`:
`quarkus.otel.sdk.disabled` defaults to `true` (via `DATATURNTABLE_OTEL_DISABLED`, default `true`),
with `quarkus.otel.exporter.otlp.endpoint` configurable via `DATATURNTABLE_OTEL_ENDPOINT`. With the
SDK disabled, Quarkus's OpenTelemetry extension does not attempt any OTLP connection at all — this
is genuinely zero-behavior-change until a deployment explicitly opts in, not merely "off by
default but still trying to phone home." Verified this directly: the app boots and the full test
suite passes with the dependency present and disabled, including a real online `mvn test` run
(needed once to fetch the `quarkus-opentelemetry-deployment` artifact — `mvn dependency:go-offline`
alone does not pull deployment-scope-only artifacts) followed by a fully offline `mvn -o test` re-run,
both clean.

This closes the "no dependency exists at all" half of the NFR-020 gap. What remains open: Quarkus's
OpenTelemetry extension auto-instruments incoming/outgoing HTTP by default, but this project's
request/correlation/run/execution ID propagation (already implemented via `RequestIdFilter` and the
Kafka `EventEnvelope`, per earlier sessions) has not been explicitly wired into span attributes —
today, enabling the SDK would produce real traces with default HTTP/JDBC spans, but not necessarily
ones that carry this platform's own correlation IDs as span attributes for cross-system searchability.
That wiring, plus an actual collector to verify against, needs a real deployment/observability-stack
session, not more code written blind in this sandbox.

## 2026-08-20 session (continued) — secret-leak log-redaction audit, two real gaps closed

Re-checked the status doc's own stale note ("broader application-log redaction beyond connector
runtime call sites... remain outstanding, e.g. Grafana provisioning, auth"). Verified `AuthService.kt`
has no logging at all (nothing to redact — the note was stale for that file) and audited every
remaining un-redacted `log.*` call site for realistic secret-leak risk. Found and fixed two genuine
gaps:

1. **`AlertDispatcher.kt:139,498` — uncovered, now fixed.** `dispatchAlert`/`dispatchAlertToChannel`
   wrap dispatch to user-configured WEBHOOK/REST/SLACK channels and logged `e.message` directly on
   failure, with zero `SensitiveDataRedactor` usage anywhere in the file. A webhook URL commonly
   carries an API key as a query parameter (`?api_key=...`) rather than URL userinfo, and a
   connection/timeout exception's message frequently includes the full request URL — a real path
   for a channel's API key to reach application logs unredacted. Injected `SensitiveDataRedactor`
   and wrapped both `e.message` log sites. Added
   `SensitiveDataRedactorTest."redacts API keys embedded in webhook or alert-channel URL query
   strings"` proving the existing generic `keyedValue` regex already correctly matches this shape
   (no regex change needed, just wiring the redactor into this file).
2. **`GrafanaProvisioningService.kt:317-319` — uncovered, now fixed, with a real API-exposure
   path.** `ensureGrafanaUser`'s failure branch built a thrown message including
   `createResponse.body().take(300)` verbatim. The POST payload to `/api/admin/users` includes a
   freshly generated provisioning password (`generateProvisioningPassword()`); a validation-error
   response from Grafana echoing submitted fields back (common for 400s) would embed that password
   in the thrown message. That message then flows into `state.lastError` (persisted) and is
   returned unredacted by two `GrafanaSyncResource` JSON endpoints (list-states and mark-failed) —
   a real, authenticated-but-broader-than-necessary exposure path, not just a log line. Fixed by
   redacting at the point the message is constructed (so it's already safe by the time it's thrown,
   logged, or persisted) and, as defense-in-depth, added `redactor.text(...)` at
   `GrafanaSyncResource`'s two `lastError` response sites (matching the existing pattern in
   `EventDlqResource`, which already redacted its equivalent field — this file was the
   inconsistent one). Added a regression test,
   `GrafanaProvisioningServiceTest."a failed user-creation response redacts an echoed provisioning
   password before it reaches the thrown message"`, using a real embedded `HttpServer` that
   deliberately echoes the submitted password back in a 400 response, asserting the thrown
   message's text never contains the password's known prefix.

Full backend suite (296 tests) re-verified green after these changes, zero `[ERROR]` lines. Frontend
`npm run typecheck` unaffected (no frontend changes this batch).

## 2026-08-20 session (continued) — final gap sweep: OpenAPI CI validation, support-matrix NFR coverage, dead code removed

Ran one more full audit against the spec (all 32 FRs, all ~20 NFRs) cross-checked against real code,
not just prior claims, specifically hunting for anything genuinely still open and locally achievable
before concluding this session's "do everything except full microservice extraction" pass. Result:
every FR/NFR has real implementation work behind it; the only large gaps remaining are the
already-acknowledged ones (FR-029 deferred, FR-022 S3/MinIO, live-Kubernetes-dependent verification).
Found two small, genuine, locally-fixable gaps and closed both:

1. **FR-028's "OpenAPI validation runs in CI" claim didn't hold up.** `ApiErrorContractTest` only
   asserted `GET /openapi` returns 200 — a liveness check, not validation that the generated
   document is itself well-formed. No CI job referenced `openapi`/`swagger` at all. Fixed by
   enabling `quarkus.smallrye-openapi.store-schema-directory` (writes a real static
   `openapi.yaml`/`.json` at build time — confirmed this is produced by an ordinary `mvn test` run,
   since any `@QuarkusTest` triggers the same augmentation phase, not just `mvn package`) and adding
   a new `openapi-contract-lint` CI job that generates it via one fast test class, then lints it
   with `@redocly/cli` using a deliberately permissive `minimal` ruleset (`.redocly.yaml`, with an
   inline comment explaining why not `recommended` — several endpoints here are intentionally
   unauthenticated by design — health, ingest via API-key header not JWT, auth bootstrap, public
   monitoring per NFR-025 — which the stricter default `security-defined` rule doesn't understand
   and would false-positive on all of them). `minimal` still catches the failures that actually
   matter: broken `$ref`s, malformed schemas, duplicate operations. **Verified the entire pipeline
   locally end-to-end** (not just YAML-parsed): ran the exact generate-then-lint command sequence
   CI will run, confirmed a real 3776-line spec is produced and lints clean (0 errors under
   `minimal`, only style warnings like missing `operationId` — correctness, not completeness, was
   the bar).
2. **Dead code found and removed while auditing the generated spec.** The lint run's default
   (`recommended`) pass surfaced `/hello` → `HelloResource.kt`, a leftover Quarkus tutorial scaffold
   endpoint (`GET /hello` returning `"Hello World"`) with zero references anywhere else in the
   codebase. Deleted it — not a security issue, just genuine unused surface area found as a side
   effect of generating a real spec for the first time.

Also closed a documentation-completeness gap in `docs/operations/support-matrix.md`: it previously
cited only 6 of the spec's 20 NFR IDs by name, even though most of the underlying work existed
elsewhere on the page — which defeated NFR-019's own purpose (classify *every* feature). Added a
dedicated "Non-functional requirements" table covering all 20 NFR IDs, cross-referencing existing
rows rather than duplicating them, and correctly marking NFR-012/NFR-013 as "Not applicable yet"
(no service extraction has been attempted; no live production data exists per this repo's own
pre-production policy) rather than forcing them into Implemented/Experimental/Planned when neither
honestly fits yet.

Full backend suite re-verified green after removing `HelloResource.kt`.

## 2026-08-20 session (continued) — global log-redaction filter (NFR-005 defense-in-depth)

Revisited the deferred "global JBoss LogManager filter" item now that call-site
`SensitiveDataRedactor` usage has grown to 13 files — the threshold the prior entry said would
justify building it. Added `GlobalLogRedactionFilter`/`GlobalLogRedactionFilterInstaller`
(`backend/src/main/kotlin/com/dataturntable/security/GlobalLogRedactionFilter.kt`): a
`java.util.logging.Filter` that rewrites a log record's already-formatted message (via JBoss
LogManager's `ExtLogRecord.setMessage`, which — unlike plain `java.util.logging.LogRecord` — allows
mutating the rendered text after parameter substitution) through the same `SensitiveDataRedactor`
already used at individual call sites, installed at CDI `StartupEvent` time.

**Got the installation mechanism wrong on the first attempt and caught it before shipping.**
Initially set the filter via `Logger.setFilter()` on the root logger. Verified via `javap` against
the real `jboss-logmanager` jar that this is incorrect: a logger-level filter only runs for records
logged directly against that specific logger instance, not for records from child loggers merely
propagating up to it — which is how nearly all application logging actually reaches the root
logger. The correct interception point is every **Handler** on the root logger (the console
handler, in this app's default setup), since handlers do receive every propagated record
regardless of origin. Fixed before any test was written against the wrong version, so this cost no
wasted verification effort, just a corrected design.

Added two levels of test coverage:
- `GlobalLogRedactionFilterTest` — unit tests constructing real `ExtLogRecord`s directly (including
  one that deliberately caught a second real mistake: JBoss LogManager's 3-arg `ExtLogRecord`
  constructor defaults to `MESSAGE_FORMAT` style, not the `PRINTF` style application code actually
  uses via `LOG.errorf(...)`/`LOG.warnf(...)` — the first version of this test silently passed for
  the wrong reason, since nothing was substituted at all; fixed by constructing the record with the
  explicit style real call sites use).
- `GlobalLogRedactionFilterInstallerIT` — a real `@QuarkusTest` booting the full application and
  asserting every handler on the actual root logger has the filter installed after startup, not
  just that the filter class works in isolation.

Full backend suite (301 tests now) re-verified green with the global filter active; normal log
output is unaffected for non-sensitive messages (spot-checked "DataTurntable application started"
still prints verbatim across multiple test-app boots in the suite log).

This concludes the "implement everything from the spec except full microservice extraction" pass
requested this session — remaining open items are the ones explicitly tracked in "Next steps"
below, each with a concrete reason it isn't done (live cluster required, user-deferred architectural
decision, or needs a real external system this sandbox doesn't have).

## 2026-08-20 session (continued further still) — real cross-test pollution bug and a sibling BCrypt robustness bug found and fixed

User invoked `.github/agents/spec-implementer.agent.md` again to continue implementing the spec
(excluding full microservice extraction) until finished, using this file as the persistent status
record. Before trusting the prior entry's "everything achievable locally is done" claim, re-ran the
full offline backend suite from a clean state as a skeptical check rather than accepting the log at
face value — this caught two real, previously-undocumented regressions the prior sessions' claimed
296-311-test-green figures had missed:

1. **Real production robustness bug — `AuthService.login()` crashed with an uncaught 500 instead of
   a clean 401 on a malformed stored password hash.** `AuthService.kt`'s legacy-BCrypt login branch
   called `BCrypt.checkpw(candidate, user.passwordHash)` directly; `org.mindrot.jbcrypt.BCrypt`
   throws `IllegalArgumentException("Invalid salt version")` for any stored value that isn't a
   well-formed BCrypt hash (including `null`, empty, or garbage values), and nothing caught it. Any
   `User` row whose `passwordHash` isn't Argon2id or valid BCrypt (a real possibility for hand-seeded
   fixtures, bad data, or future migration edge cases) turned every login attempt against that email
   into an unhandled 500. Fixed with a `safeBCryptCheck()` wrapper in `AuthService.kt` that catches
   `IllegalArgumentException` and treats it as "no match" (clean 401), not a crash. Found and fixed
   the identical bug in its sibling call site, `UserService.changePassword()` (`UserService.kt:157`),
   which had the same unguarded `BCrypt.checkpw()` call on the same code path shape.
2. **Real, previously-undocumented test-suite-wide isolation bug.** Numerous `@QuarkusTest` classes
   persist ad-hoc `User` rows (via direct `em.persist()`, bypassing `AuthService.setup()`) with no
   `@AfterEach` cleanup — and since Hibernate's H2 test schema is drop-and-created once per JVM boot,
   not per test class, every `@QuarkusTest` class in one `mvn test` run shares the same database.
   `AuthService.setup()` refuses to create the bootstrap admin account if **any** `User` row already
   exists (`SELECT count(u) FROM User u`, throws if `>0`) — a legitimate single-use production
   invariant. A leaked row from an earlier-alphabetically-ordered test class silently defeats it:
   `TestAuthHelpers.createAdminToken()` (used by ~21 test files) sees "already completed" from setup
   and assumes some earlier test correctly created `admin@example.com`, then fails to log in because
   that specific email was never actually created. This reproduced as real, deterministic (not
   flaky) failures in `EventOutboxReplayIT`/`EventOutboxReplayE2EIT` (both alphabetically late,
   package `events`) once `com.dataturntable.auth.UserTest` (zero cleanup, five leaking test methods)
   and stray leaks in `com.dataturntable.api.CrossTenantIsolationTest`/`AuthorizationResourceTest`
   ran first in the same suite. Root-caused (not just patched around the two visibly-failing tests)
   and fixed at two levels:
   - **Contained the failure mode structurally**: `TestAuthHelpers.createAdminToken()` now checks via
     direct JPA whether `admin@example.com` actually exists after a failed setup; if not (meaning
     some *other* leaked row tripped the guard), it deletes stray non-admin `User`/`Membership` rows
     and retries setup once, rather than assuming the account already exists.
   - **Closed every confirmed leak site** rather than relying solely on the helper's self-heal:
     `UserTest.kt` (added full `@AfterEach` cleanup; also renamed one fixture off the literal
     `admin@example.com` to remove a second, unrelated collision risk), `CrossTenantIsolationTest.kt`
     and `AuthorizationResourceTest.kt` (added `@AfterEach` deleting their specific ad-hoc email
     patterns), `AuthServiceTest.kt` and `TenantResourceTest.kt` (both had `@BeforeEach`-only cleanup
     that left their *last* test's fixtures leaking past the class boundary — added matching
     `@AfterEach`), and `AlertResourceTest.kt`/`ConnectorResourceTest.kt`/`DataResourceTest.kt`/
     `ModuleResourceTest.kt`/`PipelineResourceTest.kt` (each persists one ad-hoc non-member `User`
     per test with no cleanup at all — added matching `@AfterEach` to each, scoped to their specific
     email pattern so no unrelated fixture is touched).

Verified via a real explore-agent audit (not just the two directly-failing tests) that these were
the complete set of `User`-persisting `@QuarkusTest` classes lacking adequate cleanup; also spot-
checked for any other production single-use/count-based guard invariant that ad-hoc test fixtures
could similarly violate elsewhere in the codebase — found none beyond `AuthService.setup()`.

After the pollution fixes, ran a second skeptical audit specifically for the same bug *shape*
(unguarded exception from a crypto/parsing library on attacker/client-controlled input) elsewhere in
the codebase, since finding one real instance made the category worth checking systematically rather
than assuming it was isolated. Found and fixed two more real, genuinely reachable instances:

3. **`AuthService.verifyArgon2`/`UserService.changePassword`'s Argon2 branch — same unguarded-parse
   bug shape as the BCrypt fix, lower likelihood but same consequence.** `de.mkammerer.argon2`'s
   native-backed `.verify(encodedHash, password)` parses the stored encoded hash before comparing;
   a truncated/corrupted stored value that still happens to start with the `$argon2id$` prefix (the
   guard both call sites already check before taking this branch) would still throw uncaught. Added
   `AuthService.safeVerifyArgon2()` (mirroring the existing `safeBCryptCheck()` in the same file) and
   wrapped the equivalent inline call in `UserService.changePassword()`, both catching the broader
   `Exception` (not just `IllegalArgumentException`) since the native JNI binding's failure mode for
   malformed input isn't guaranteed to be a specific Java exception type.
4. **`PipelineResource.update()` (`PATCH /api/internal/v1/pipelines/{id}`) — real, high-confidence,
   directly reachable bug.** The three module-version-id body fields
   (`importModuleVersionId`/`processingModuleVersionIds`/`exportModuleVersionIds`) called
   `UUID.fromString(...)` directly on caller-supplied JSON values with no try/catch, even though this
   same file already has a `parseUuid(value, field)` helper used correctly everywhere else for
   exactly this purpose (including the `{id}` path param two lines above in the same method) — this
   was a clear miss, not a deliberate choice. A malformed UUID string or wrong JSON value type in any
   of those three fields crashed the PATCH with an uncaught `IllegalArgumentException`/
   `ClassCastException`, producing a raw 500 instead of a clean 400, from any authenticated caller
   with `pipeline:write` on a real pipeline they're allowed to edit. Fixed by routing all three
   through the existing `parseUuid()` helper inside a try/catch that returns its `WebApplicationException`'s
   response (or a 400 for a wrong JSON type via `ClassCastException`), matching the pattern already
   used for the path param. Added `testUpdatePipelineRejectsMalformedModuleVersionIdWithCleanBadRequest`
   to `PipelineResourceTest.kt`, which failed with an unhandled 500 before the fix and now correctly
   asserts 400 for both a malformed `importModuleVersionId` and a malformed entry inside
   `processingModuleVersionIds`.

**Final full offline backend suite (H2 profile, excluding the three Postgres/Docker-`*IT`/
`*PostgresIT` classes that need Dev Services): 306 tests, 0 failures, 0 errors, 11 skipped —
confirmed via a genuine `mvn -o test` run, not inferred.** Also re-ran `PostgresMigrationBehaviorTest`
(19s, real PostgreSQL 18 via Dev Services) and `ConnectorLeaseConcurrencyPostgresIT` (16s, real
concurrent transactions) directly against real PostgreSQL after the `AuthService`/`UserService`
changes — both pass clean. `npm run build` (frontend) passes clean, 306 modules, unaffected by this
session's backend-only changes.

**Lesson for future sessions**: a status doc claiming "N tests, 0 failures" from a *previous*
session's run is not evidence the suite is green *now* — test-order-dependent pollution bugs are
exactly the kind of thing that can silently start failing (or silently starts passing again by luck
of alphabetical class ordering shifting) between sessions as new test classes are added. Always
re-run the full suite fresh at the start of a continuation session before trusting the prior
session's final test count, rather than treating the status doc's last recorded number as
still-current fact. Separately: once one instance of an unguarded-library-exception bug is found, it
is worth a deliberate, scoped sweep for the same *shape* of bug elsewhere (same library, same "parse
untrusted/possibly-corrupted stored or request value" pattern) rather than assuming it was isolated
— two more real instances (Argon2 sibling, `PipelineResource.update()`) were found this way and would
otherwise still be open.

## 2026-09-27 session - pull-input connector UI added; unstructured blob storage removed end-to-end

**1. Pull-input connector types implemented in the UI (FILE, FTP, SFTP, EMAIL).** These four
connector types are now creatable and editable in `frontend/src/pages/Connectors.tsx`, with a
polling interval, operator-visible last/next run, and an inbox ledger for pulled items so a retry
cannot double-apply. Sensitive fields (`password`, `privateKey`, `passphrase`) go through the
secrets API and are stored as an opaque reference; the backend rejects inline credentials, and a
blank secret field on edit means "keep the stored secret" rather than "clear it", so a user can
change a host or path without re-entering a password they cannot see. 5 new frontend tests.

**2. Unstructured blob storage removed (STR-08) — the platform stores records, not payloads.**
This is a deliberate product decision, not a gap: the original FR-022 asked for authenticated
blob upload/download plus migration to S3/MinIO, and it has been dropped. Files, mails, FTP/SFTP
items, attachments and binary protocol frames are all parsed at the ingest boundary, and only the
parsed records are persisted; the source item stays where the remote system put it. Removed:

- `DataBlobRecord.kt` (deleted) and the whole blob block in `DataResource.kt` — configuration,
  endpoints, SQL, helpers and now-unused `File`/`ByteBuffer` imports.
- The `data_blobs` table, its primary key, indexes and foreign keys from `V1__baseline.sql`.
  Per the project policy at the top of this file, no forward `DROP TABLE` migration is needed:
  there is no production data and no live instance to protect.
- `dataturntable.data.blob-dir` and the rest of `data.blob` from `application.yaml`.
- Blob tests/routes in `DataResourceTest.kt` and `CrossTenantIsolationTest.kt`.
- `DataBlob`, the blob API methods and the whole Files tab from `frontend/src/types/index.ts`,
  `frontend/src/services/api.ts` and `frontend/src/pages/Data.tsx`.
- The blob archive/restore steps from `scripts/backup/backup.sh` / `restore.sh` — the scripts are
  now database-only, which is also why `docs/operations/backup-restore.md` no longer has a file-tree
  check to run and "two databases" is the entirety of the durable state.
- CI's schema check now asserts `data_table_registry` and `connectors` instead of `data_blobs`.
- Documentation: `README.md`, `docs/operations/backup-restore.md`, and the FR-022 row in
  `docs/operations/support-matrix.md` (now "Implemented — deliberately out of scope"). S3-compatible
  object storage is still used, but only for lakehouse data files. `source-function-reference.md`
  was regenerated via its generator rather than hand-edited.
- Both specifications updated: `requirements-specification.md` (scope, STR-08/09, IMP-10–13,
  CON-01/13–16, new §4.15 `MET-01`–`MET-10`, NFR-R7–R9, M5, traceability) and
  `functional-specification.md` (§3.1–3.4, §4.9 fire-and-forget, §7.2/7.5, §8 structured-only,
  §13.4 metadata enrichment, §13.5 §14a as an **example only**, §17.1 Compose-not-redundant).

**3. Verification.** Per the "re-verify, don't trust the prior session's number" lesson below, this
session re-ran everything fresh rather than citing the earlier count: full backend suite in
`maven:3.9.16-eclipse-temurin-25` = **318 tests, 0 failures, 0 errors, 22 skipped** (the skips are
Docker-only tests, which have no daemon inside the Maven container); targeted
`CrossTenantIsolationTest` + `DataResourceTest` = 23 tests green. `npx tsc --noEmit -p .` clean,
`npx vitest run` = 8 files / 38 tests green, `npx eslint src` = 0 errors / 21 pre-existing
warnings. Note the local `JAVA_HOME` is JDK 17 and PATH Java is 21 while the project targets Java
25, so host-Maven runs are not representative; the Docker JDK 25 image is the reliable runner.

**Discrepancy found and now fixed:** `.github/workflows/ci.yml` configured `java-version: '21'`
while `backend/pom.xml` sets `maven.compiler.release=25`. All three `setup-java` steps (backend,
openapi, postgresql jobs) now use 25. The workflow also had a pre-existing YAML indentation defect
in the migration check, and the check itself used a column that does not exist and asserted
`data_blobs` **exists**; it now checks real connector columns and fails if `data_blobs` ever
returns. Verified by a real `maven:3.9.16-eclipse-temurin-25` run: **321 tests, 0 failures, 0 errors,
22 Docker-only skips**, plus a real PostgreSQL migration run.

**Live Compose smoke test - three further defects found, all fixed.** Bringing the stack up for real
(exactly the step the previous notes left as the remaining verification) paid for itself:

1. *The backend could not start in any container.* `quarkus.datasource.jdbc.url` was defined only
   under `%dev`, and no deployed profile includes `dev` - Compose uses `<role>`, the k8s manifests
   use `prod,<role>`. Every role failed with `Failed to load config value ...
   quarkus.datasource.jdbc.url`. The URL moved to the root `quarkus.datasource` block; `%prod` still
   forces mandatory credentials and `%test` still uses H2. This also fixed one of the two
   Kubernetes blockers, though the k8s topology remains unbootable for want of JWT keys.
2. *JWT signing keys were provided by nothing*, in Compose or k8s, so no token could be verified.
   `scripts/dev/generate-dev-keys.sh` generates a throwaway pair into the gitignored
   `secrets/dev-keys/`; Compose mounts it read-only into all four roles. `control-api` and
   `public-api` now both reach `healthy` on role-only profiles, and setup/login/`/me` succeed.
   The k8s manifests still need the equivalent Secret mount.
3. *Inline-credential bypass.* A connector or alert config sent as a JSON **string** (a serialized
   object inside a string field) skipped secret validation entirely and persisted a plaintext
   password, because the tree walk treated a scalar root as nothing to inspect. Both resources now
   require an object root; nested scalars (`"port": 22`) stay legal. Confirmed live: connector create
   and PATCH and alert-channel create all return 400 "must be a JSON object" for a string config,
   while a valid `secretRefs` object still returns 201/200.

The three new regression tests were checked for vacuity by deleting the guard and confirming all
three fail (200/201 rather than 400).

`README.md` also documented an unusable start command (`cd docker && docker-compose -f
docker-compose.dev.yml up -d`: wrong path from the repo root plus deprecated v1 syntax); it now
generates keys first and runs `docker compose -f docker/docker-compose.dev.yml up -d`.

Still outstanding: a browser-level Vite/Playwright pass (the API was exercised over HTTP instead),
and the Kubernetes JWT key wiring described above.

## Next steps (recommended order)

1. Add hostile-module integration coverage for the Kubernetes execution backend (needs a local
   cluster/Dev-Services equivalent — larger infra lift, no existing precedent in this repo). The
   same "needs a live cluster" blocker applies to actually running the backup/restore drill
   (`docs/operations/backup-restore.md`) against `docker/k8s`/`docker/k8s-ha` — the Compose-topology
   drill is done and verified, the Kubernetes one is not. Note the drill's own record predates the
   blob removal: the backup is now two database dumps only, and the file-tree step it described no
   longer exists.
2. FR-029 (microservice/API Gateway split) is the single largest remaining architectural gap —
   user explicitly deferred a real extraction attempt on 2026-08-20 (see that session entry); do
   not start one without the user opting into a specific scope first. FR-022 is **no longer a gap**:
   the blob/S3-MinIO storage requirement was dropped outright on 2026-09-27, so neither the storage
   nor the migration to it is outstanding. See `docs/operations/support-matrix.md` for the honest
   current state of every FR/NFR.
3. FR-010 graph-schema migration functions for old fixtures remain unwritten — reasonable to defer
   since there's only ever been schema version 1 in this project's history (no live data yet); write
   the migration-function framework when a real version 2 is actually introduced, not speculatively.
4. OpenTelemetry tracing (NFR-020) — the dependency now exists (added 2026-08-20, disabled by
   default), but span attributes don't yet carry this platform's own request/correlation/run/
   execution IDs, and there's no real collector to verify actual traces against. Needs a real
   deployment/observability-stack session, not more blind code.
5. Wire the JWT signing/verification keys into `docker/k8s/backend/deployment.yaml` (a
   `dataturntable-jwt-keys` Secret mounted at `/keys` in all four deployments, matching the
   existing out-of-band `app-db-credentials` pattern). Without this the Kubernetes topology still
   cannot boot even now that the JDBC URL is fixed, and it has never been deployed to a live
   cluster, so the wiring is unverifiable in this environment. Also update
   `docs/production-ready-spec.json` — it is a static record of the original review and still
   describes blob services, `BlobMetadata` and an S3/MinIO migration phase, so it now contradicts
   the rescoped product decision.

Resolved this session, no longer open: WebSocket idle-timeout root cause, `PipelineConsumerDlqIT`
root cause, connector two-replica fencing under real concurrent Postgres transactions
(`ConnectorLeaseConcurrencyPostgresIT`), CI image build+push to GHCR feeding the kustomize digest
transformer, two real secret-leak log-redaction gaps (AlertDispatcher, GrafanaProvisioningService/
GrafanaSyncResource), CI OpenAPI contract generation+lint, and a global JBoss LogManager
log-redaction filter as defense-in-depth on top of call-site redaction (all 2026-08-20, see the
dated entries above).
