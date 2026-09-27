# Feature and deployment support matrix (NFR-019)

NFR-019 requires that documentation classify every feature and deployment target as
**Implemented**, **Experimental**, **Deprecated**, or **Planned**, rather than presenting
untested or in-progress behavior as production-ready. This page is that classification for
DataTurntable, kept alongside — not duplicating — the deployment-target table in the top-level
[README.md § Deployment / Support status](../../README.md#support-status), which this page links
to rather than restates.

Definitions used below:

- **Implemented** — built, covered by passing automated tests, and (where the feature has an
  operational dimension — deployment, backup, scaling) actually exercised against a real
  environment during development, not just unit-tested in isolation.
- **Experimental** — built and covered by tests, but not yet operationally proven (no live-cluster
  run, no failover drill, no production-scale validation) or has a known, documented functional
  gap.
- **Planned** — described in the requirements spec but not yet built, or scaffolded
  (configuration, manifests, CI jobs) without the infrastructure available to execute/verify it.
- **Deprecated** — superseded by a newer mechanism and scheduled for removal; none currently.

This matrix reflects the state recorded in
[docs/production-ready-v1-status.md](../production-ready-v1-status.md) as of the date at the
bottom of this page. That status tracker is the authoritative, continuously-updated source — if
the two disagree, trust the status tracker and treat this page as due for a refresh.

## Core platform features

| Feature | Status | Notes |
|---|---|---|
| Authentication (Argon2id, legacy BCrypt migration) | Implemented | FR-003. Passing tests, real login-and-rehash path exercised. |
| Exact-token authorization model (Ability/RoleAbility/MembershipAbility) | Implemented | FR-001. |
| Platform-admin vs. root-tenant-admin separation + permission explanation | Implemented | FR-002. |
| Short-lived access tokens + session/authorization invalidation | Implemented | FR-004. |
| JWT signing key externalization/rotation, no default prod credentials | Implemented | FR-005. |
| Tenant/resource-scoped ingest credentials (HTTP + WebSocket) | Implemented | FR-006. |
| Ingest payload/rate/connection/idle protections, thread-safe WS sessions | Implemented | FR-007. Idle-session enforcement was root-caused and fixed 2026-08-20: `Session.setMaxIdleTimeout` is a confirmed no-op on this Quarkus version (decompiled), so a `@Scheduled` reaper in `WebSocketIngestResource` now enforces the timeout directly. `WebSocketIdleTimeoutE2ETest` passes. |
| Immutable ModuleVersion content + digest identification | Implemented | FR-008. |
| Immutable PipelineVersion as sole authoritative execution graph | Implemented | FR-009. |
| Graph schema versioning and validation | Implemented (v1 only) | FR-010. `schemaVersion` is validated (missing defaults to 1, unsupported values rejected); no schema-migration-function framework exists yet because there has only ever been version 1 — see status tracker "Next steps." |
| Run/node state machine (PENDING…TIMED_OUT) | Implemented | FR-011. |
| Execution Service contracts (ExecutionRequest/Result/Environment/Limits/Backend) | Implemented | FR-012. |
| DockerExecutionBackend | Implemented | FR-013. Covered by tests against a real local Docker daemon, including hostile-module cases. |
| KubernetesJobExecutionBackend | Experimental | FR-013. Hardened Job spec (non-root, dropped capabilities, seccomp, resource limits, no Docker socket) is implemented and unit-tested, but has never run a hostile-module workload against a real Kubernetes cluster — no cluster is available in this development environment. |
| Execution resource quotas (CPU/memory/PID/disk/timeout/log/network/concurrency) | Implemented | FR-014. |
| Versioned Kafka event envelope with correlation/causation IDs | Implemented | FR-015. |
| Kafka idempotency, outbox, inbox/dedup, bounded retry, DLQ | Implemented | FR-016. `PipelineConsumerDlqIT`'s long-standing "flaky" label was root-caused and fixed 2026-08-20: the `%test` profile's `background-enabled: false`/`pipeline-input.enabled: false` defaults meant the app's own Kafka consumer was never wired up in this test at all (100% reproducible, not actually flaky). A dedicated test profile now re-enables both; verified passing across 4 consecutive isolated runs plus the full suite. |
| ConnectorRevision + separated Connector Runtime | Implemented | FR-017. |
| PostgreSQL connector leases with heartbeat/fencing tokens | Implemented | FR-018. Lease diagnostic endpoint added this project. Two-replica fencing verified 2026-08-20 against real concurrent PostgreSQL transactions (`ConnectorLeaseConcurrencyPostgresIT`, 8-way concurrent acquisition + expiry/takeover races), not just a single shared-transaction unit test. |
| Encrypted Secret/SecretVersion storage, secret references replacing embedded credentials | Implemented | FR-019. Inline connector credentials are now rejected at the API boundary. |
| JSONB migration for document-shaped data | Implemented | FR-020. |
| Data Service failure propagation, parameterized SQL, identifier validation, tenant isolation | Implemented | FR-021. |
| Blob upload/download/delete with authenticated access | Implemented (local filesystem backend) | FR-022. The S3/MinIO-compatible object storage target in the requirement is **Planned** — blobs are currently stored on local container filesystem (`dataturntable.data.blob-dir`), which is why backup/restore in [backup-restore.md](backup-restore.md) uses `tar` rather than an object-store replication mechanism. |
| Alert rules (nested AND/OR, typed operators, actions, test mode, cooldown) | Implemented | FR-023. Severity/message are now real typed columns (previously implicit/derived). |
| Append-only Audit Service | Implemented | FR-024. |
| Structured logs, metrics, Prometheus/Loki/Grafana, monitoring contracts | Implemented | FR-025. |
| OpenTelemetry tracing | Experimental | FR-025/NFR-020. `quarkus-opentelemetry` was added 2026-08-20, disabled by default (`quarkus.otel.sdk.disabled=true` unless `DATATURNTABLE_OTEL_DISABLED=false` and a real OTLP endpoint are configured) — verified the app boots and the full test suite passes with it present and inert. Auto-instrumented HTTP/JDBC spans would work if enabled, but this platform's own request/correlation/run/execution IDs are not yet wired into span attributes, and no real OTLP collector has verified an actual trace. |
| SPA reorganization (Overview/Build/Connect/Data/Operate/Admin) + lazy-loaded heavy routes | Implemented | FR-026. Monaco/React Flow confirmed lazy via CI's manifest assertion. |
| Admin permission-debugging interface | Implemented | FR-027. |
| Standardized API error envelopes + generated/validated OpenAPI contracts | Implemented | FR-028. Error envelopes were already implemented. "OpenAPI validation runs in CI" previously only meant `/openapi` returns 200 (a liveness check) — as of 2026-08-20, CI's `openapi-contract-lint` job actually generates the static contract at build time (`quarkus.smallrye-openapi.store-schema-directory`) and lints it with `@redocly/cli` (`.redocly.yaml`, `minimal` ruleset — real structural validation, not an opinionated house-style gate), verified locally end-to-end. |
| Retention policies (runs, node logs, alert history, audit events, readings) | Implemented | FR-031. Configurable per-category via `dataturntable.retention.*-days`; every category defaults to indefinite retention (`-1`) until a deployment opts in, matching the pre-existing blob-retention precedent. |
| Domain-oriented microservices behind a stable API Gateway | Planned | FR-029. The backend currently splits into independently deployable **runtime roles** within one codebase/image (`control-api`, `public-api`, `ingest-edge`, `pipeline-executor` — see `docker-compose.dev.yml`), which gives real process-level isolation and independent scaling, but there is no separate API Gateway service, no per-domain service boundary (Identity, Module Registry, Pipeline, etc. as the spec names them), and no enforced workload-identity check between internal calls. This is one of the largest remaining gaps against the spec. |
| Docker Compose (dev) | Implemented | FR-030. See README support-status table. |
| Docker Swarm | Experimental | FR-030. See README support-status table — deployment assets exist, failover/backup/scanning gates unproven. |
| Kubernetes HA reference topology | Experimental | FR-030. Manifests exist (`docker/k8s`, `docker/k8s-ha`) and render successfully via `kubectl kustomize` in CI; no live-cluster deployment has been performed in this environment. |
| Immutable image digests in production manifests | Experimental | FR-030/FR-032/NFR-018. Third-party images (`timescale/timescaledb`, `grafana/alloy`) are pinned to real digests. The project's own images (`dataturntable/backend`/`frontend`/`docs`) have a kustomize `images:` transformer wired to receive a digest; CI now pushes `backend`/`frontend` to GHCR on `main` and captures the real digest into a downloadable artifact with the exact `kustomize edit set image` command to apply it (2026-08-20) — not yet run in real CI (no runner available here), and no deploy step actually runs that command yet, so the manifests still ship with the `:latest` placeholder until one does. The `docs` image is not yet built/pushed by any CI job. |

## Non-functional requirements

This section was added 2026-08-20 to close a real gap: earlier revisions of this page only cited
6 of the spec's 20 NFR IDs by name (NFR-006, 017–020), even though most of the underlying work was
done and covered elsewhere on this page or in the status tracker — NFR-019 itself requires *every*
feature be classified, so silently omitting most NFRs defeated the page's own purpose.

| NFR | Requirement | Status | Notes |
|---|---|---|---|
| NFR-001 | Tenant isolation is a mandatory security invariant | Implemented | Covered by `CrossTenantIsolationTest` and the tenant-scoped query/identifier-validation work under FR-001/FR-021. |
| NFR-002 | User-defined Python is treated as hostile by default | Implemented | `DockerExecutionBackend`/`KubernetesJobExecutionBackend` apply non-root, dropped capabilities, seccomp, resource limits, network restriction — see FR-013/FR-014 rows above; `DockerServiceHostileModuleTest` exercises this against a real Docker daemon. |
| NFR-003 | Production must never silently fall back to insecure secrets/defaults | Implemented | FR-005's default-credential rejection and mandatory production encryption/signing material. |
| NFR-004 | Configurable connector/execution egress must mitigate SSRF | Implemented | `ConnectorResource.validateExternalDestination()` blocks loopback/private/link-local/metadata targets for REST/WEBSOCKET connectors. |
| NFR-005 | Secrets must not leak through API/logs/monitoring/audit/errors/execution metadata | Implemented | `SensitiveDataRedactor`, wired into 13 call sites as of 2026-08-20 (that session added `AlertDispatcher` and `GrafanaProvisioningService`/`GrafanaSyncResource` — see that session's status-tracker entry for the two real gaps found and fixed). As of 2026-08-20 (continued), also backed by `GlobalLogRedactionFilter` — installed on every root-logger handler at startup, rewriting any formatted log message through the same redactor as a last-line-of-defense on top of call-site opt-in, verified via a real `@QuarkusTest` asserting the filter is actually installed after boot. |
| NFR-006 | Browser auth/session design must minimize XSS credential impact | Implemented | See the Operational capabilities table below (security response headers) plus short-lived access tokens (FR-004). |
| NFR-007 | Kafka processing must remain correct under at-least-once delivery | Implemented | FR-016's idempotency/outbox/inbox/dedup row. |
| NFR-008 | Pipeline/execution state must stay deterministic under crashes/duplicates/timeouts/races | Implemented | FR-011's state machine + FR-016's retry/DLQ semantics. |
| NFR-009 | Persistent connectors must fail over without split brain | Implemented | FR-018's row — verified 2026-08-20 under real concurrent PostgreSQL transactions (`ConnectorLeaseConcurrencyPostgresIT`). |
| NFR-010 | Ingest/connector/orchestration/execution/APIs must be independently horizontally scalable | Implemented (runtime-role split) | The 4-way runtime-role split (`control-api`/`public-api`/`ingest-edge`/`pipeline-executor`) gives real independent scaling today; full per-domain microservice scaling is the FR-029 gap, not this NFR specifically. |
| NFR-011 | Microservices must stay in one monorepo with explicit versioned contracts | Experimental | True today by construction (everything is one monorepo), but "explicit versioned contracts" between domains doesn't really apply yet since FR-029's domain boundaries don't exist. Revisit when/if FR-029 is attempted. |
| NFR-012 | Service extraction must preserve supported behavior during migration | Not applicable yet | No service extraction (FR-029) has been attempted — nothing to have broken compatibility with. |
| NFR-013 | Historical IDs/records must be preserved through migration | Not applicable yet | Per this repo's own documented pre-production policy (see the top of the status tracker) — no live production data exists, so this describes target-architecture discipline to apply once real data exists, not a current constraint. |
| NFR-014 | Production persistence migrations must be tested on PostgreSQL/TimescaleDB | Implemented | `PostgresMigrationBehaviorTest` boots the full app against real Postgres with Flyway migrations + Hibernate `validate` mode; CI's `postgresql-migrations` job independently applies every migration in order. |
| NFR-015 | Heavy editor dependencies must not inflate initial SPA load | Implemented | Monaco/React Flow lazy-loaded; CI asserts this via a manifest check (FR-026). |
| NFR-016 | A reference HA deployment must tolerate loss of one stateless replica | Experimental | `docker/k8s-ha` manifests describe this topology and render successfully in CI; never verified against a live cluster. |
| NFR-017 | Backup and restore must be operationally proven | Implemented (Compose topology only) | See the Operational capabilities table below. |
| NFR-018 | Production artifacts/config must be immutable, validated, traceable, scanned | Experimental | See the "Immutable image digests" row above and the CI scanning rows below. |
| NFR-019 | Documentation must not present planned/untested behavior as implemented | Implemented | This page. |
| NFR-020 | Operational telemetry must permit following one request/event/run across boundaries | Experimental | See the "OpenTelemetry tracing" row above — logs/metrics already carry correlation IDs; distributed trace propagation exists as inert infrastructure, not yet wired to this platform's own IDs. |

## Operational capabilities

| Capability | Status | Notes |
|---|---|---|
| Backup | Implemented (Compose topology only) | NFR-017. See [backup-restore.md](backup-restore.md) — verified by an actual backup/restore drill this session against real TimescaleDB containers, including a real bug found and fixed (`pg_restore --clean` corrupting TimescaleDB catalog state). |
| Restore | Implemented (Compose topology only) | NFR-017. Same as above. Not run against Kubernetes; no automated schedule. |
| Security response headers (CSP, HSTS, X-Frame-Options, etc.) | Implemented | NFR-006. |
| CI: backend/frontend test suites | Implemented | Runs on every PR/push to main. |
| CI: Kubernetes manifest rendering | Implemented | `kubectl kustomize` against both `docker/k8s` and `docker/k8s-ha`. |
| CI: container image build, vulnerability scan, SBOM generation | Experimental | NFR-018. Jobs added to `.github/workflows/ci.yml` this session (Trivy scan, CycloneDX SBOM via `anchore/sbom-action`) and the backend Docker build was verified locally against this exact Dockerfile — real `BUILD SUCCESS`. The jobs themselves have not executed in real GitHub Actions (no runner available in this environment), so treat as structurally ready, not yet proven green in CI. |
| CI: dependency vulnerability scanning (OWASP Dependency-Check, npm audit) | Experimental | NFR-018. Added this session; requires an `DC_NVD_API_KEY` repository secret that does not exist yet, and has not executed in real CI. |
| Secrets never committed to the repository | Implemented | `docker/k8s/storage/secrets.yaml` ships an empty `Secret` template with documentation, not real values; local dev credentials in `docker-compose.dev.yml` are explicitly dev-only. |

## How to keep this page honest

Update this page in the same session/PR that changes the underlying feature's status — do not let
it drift into aspirational documentation. When in doubt about a feature's real status, prefer the
more conservative classification (Experimental over Implemented) until there is a passing
automated test *and*, for anything with an operational dimension, an actual run against a real
environment to point to.

---
Last reviewed: 2026-08-20, alongside the sessions recorded in
[docs/production-ready-v1-status.md](../production-ready-v1-status.md).
