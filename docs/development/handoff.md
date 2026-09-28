# Handoff: DataTurntable review and merge (state 2026-09-28)

This document lets another assistant or developer continue the work without the chat history.

> **Read this first.** Everything is committed and pushed to `claude/datahub-lakehouse-review-6tiaf5`
> and the working tree is clean. **The only thing left before landing is asking the owner to open the
> PR into `dev`** — nothing technical is blocked. The full backend suite on H2 is green: **355 tests,
> 0 failures, 0 errors, 29 skipped** (was 351/10 before this session added 4 tests, all Postgres-gated
> so they add to the skip count on H2). To reproduce the Docker/Python-gated portion on a host you need
> a Windows JDK 25 **and** a real `python3` on `PATH` — the "Tests" section at the top has
> admin-free download recipes for both. Session entries below are in chronological order; the newest
> is **"TEN-03/NFR-S8: per-tenant PostgreSQL database roles"**, the last open M0 item, verified against
> a real PostgreSQL 16 instance (cross-tenant access denied at the database layer, not just in
> application code). It also corrects **MOD-15 to Partial** from the previous session (artifacts are
> module-scoped, so the SRS's "or a different one" does not hold).
> Items in "Open work" that are ~~struck through~~ are done — do not redo them.
>
> **All six M0 items are now addressed** (some Partial, none Open): ingest auth, secrets out of the
> repo, per-tenant database roles, CI against PostgreSQL, platform health alerts, run-history
> retention. **Next target if work continues (owner's call, not a blocker):** M1 (micro-batch
> execution, PIP-15/NFR-P7) is the next milestone in the SRS, §11 — or `ModuleServiceTest`/
> `TenantServiceTest`'s 9 still-unexplained skips, which are quick to investigate and predate this
> session.

## Repository and branches

- Repository: `liphip/datahub` ("DataTurntable").
- Working branch: `claude/datahub-lakehouse-review-6tiaf5`. Push only there. `dev` gets changes through a PR.
- The last commit on the working branch merges `origin/dev` (commit `84b2b5f`) into the branch. It is marked **WIP**: the backend suite passes, and some follow-ups are listed below.
- **State:** the pull-input UI, blob removal and JWT fixes are committed in `3b7fcd7`. The
  follow-up work in "Session 2026-09-28" below is committed on top of it. Check
  `git log origin/claude/datahub-lakehouse-review-6tiaf5` to see whether it was pushed.
- **Working rule (owner, 2026-09-28):** keep this file current, and commit and push to the working
  branch regularly, so another tool can take over at any time. Pushing to the working branch needs
  no further confirmation. Opening the PR into `dev` still does.
- **Never `git add -A` here.** `secrets/dev-keys/signing.pem` is a private key; it is correctly
  ignored, but stage by path and re-check `git status` before committing.
- Stack:
  - Backend: Quarkus 3.39 with Kotlin 2.4 on Java 25; SmallRye Reactive Messaging with Kafka.
  - Databases: PostgreSQL 18 with the TimescaleDB image (Flyway, one baseline `V1__baseline.sql`); tests use H2.
  - Frontend: React, Vite, Tailwind, i18n (en/de); tests with vitest.
- Tests:
  - Backend: `cd backend && mvn test -Dquarkus.http.test-port=0`. Docker-only tests are skipped without a Docker daemon.
  - **The project targets Java 25 (`backend/pom.xml`: `maven.compiler.release=25`), so a host Maven run is not representative** — this machine's `JAVA_HOME` is JDK 17 and PATH Java is 21. Use the Maven image instead: `docker run --rm -v "${PWD}\backend:/app" -v "${env:USERPROFILE}\.m2:/root/.m2" -w /app maven:3.9.16-eclipse-temurin-25 mvn --batch-mode test '-Dquarkus.http.test-port=0'`.
  - **The Docker-gated tests cannot be run from the Maven container** (see below); they need a host
    JVM with a real `docker` CLI. Un-gated as of 2026-09-28:
    ```powershell
    $env:JAVA_HOME = '<a Windows JDK 25>'
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
    cd backend; mvn --batch-mode test '-Dtest=ModelArtifactRoundTripTest,DockerServiceHostileModuleTest' '-Dquarkus.http.test-port=0'
    ```
  - **Why the container route does not work for those tests:** `DockerService` shells out to the
    `docker` CLI (`ProcessBuilder`) and bind-mounts **host paths** into each module container. A JVM
    inside a Linux container would hand Linux paths (`/app/...`) to the Windows daemon, so the mounts
    would be wrong — and the container would have no `docker` CLI anyway. Use a host JDK 25.
    This machine has none by default (host `JAVA_HOME` is JDK 17, PATH Java is 21); fetch one with
    `curl.exe -sS -L -o jdk25.zip 'https://api.adoptium.net/v3/binary/latest/25/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk'`
    then `tar -xf jdk25.zip -C jdk25` (bsdtar reads the zip). Host `mvn` 3.9.14 from Chocolatey is fine.
  - **`ModuleScriptTest` additionally needs a real host `python3`** (see below). Without one it
    skips, which is correct.
  - Real-PostgreSQL migration and schema check: `DATATURNTABLE_IT_DB_URL=jdbc:postgresql://localhost:5432/<db> DATATURNTABLE_IT_DB_PASSWORD=... mvn test -Dtest=PostgresMigrationBehaviorTest`.
  - Frontend: `cd frontend && npx tsc --noEmit -p . && npx eslint src && npx vitest run`.

## Owner's requirements (standing)

**Product and deployment**
- A full-stack data hub or lakehouse for **structured** data only: measurements, readings, locations, metadata. Unstructured blob storage should be dropped. **Done — see "Unstructured blob storage removed" below.**
- One self-contained UI; no external services that users must manage.
- Everything FOSS, or at least freeware; licences must be checked.
- Deployment targets: single-node Docker Compose and Kubernetes (including HA). No Swarm.
- Throughput target: 1 billion records per day on multi-node autoscaling Kubernetes. The target does not apply to single node.

**Users and access**
- Personas: system admins (IT), tenant admins (key users), end users.
- Fine-grained roles, including temporary (time-bounded) assignments.
- Role templates: predefined ones are immutable, but can be cloned, disabled and inspected.
- An audit log with notes for every impactful action.
- An auth proxy for all components, and OIDC client features.

**Delivery guarantees**
- HA without data loss on failover. Alert rules must work.
- Delivery must be **exactly once**. Senders must not need to register responses: fire-and-forget must work for every input where possible (API, MQTT, mail, FTP, …).
- The queue must be redundant for HA.

**Metadata**
- A metadata component per device, datapoint, endpoint and so on, which enriches pipelines.
- Example flow: LoRaWAN readings, plus meter data from SAP, plus the internal location per market location. The result goes to a metering system and a grid-calculation system.
- §14a EnWG is **only an example use case**.

**Engineering rules**
- Pre-deployment: no backwards compatibility, dependencies up to date.
- Specs must keep the old ideas when rewritten.
- The UI must be good.

**How the owner wants answers**
- Ask 1–2 clarifying questions when something is unclear, then proceed.
- Show reasoning and trade-offs.
- Give the smallest useful answer unless told "just do it".
- Disagree openly and state uncertainty.

## What is done (on the branch)

**Exactly-once delivery**
- Deterministic run ids come from an `Idempotency-Key` header or from the `deduplicateBy` fields on IMPORT nodes.
- `claimRun` in `PipelineLogWriter` records the Kafka partition and offset.
- Alerts use a transactional outbox (`AlertDispatcher`: retry and backoff, a unique `delivery_key`).
- DB_SAVE appends are idempotent, and REST exports send an `Idempotency-Key`.
- A chaos test gave 0 lost and 0 duplicate records.

**Database-free ingest**
- `PipelineDirectory` is an in-memory cache of pipelines.
- `IngestSpool` is an fsync'd local spool used when Kafka refuses a record. On Kubernetes, ingest-edge is a StatefulSet with a spool PVC.

**Metadata component**
- Backend in `metadata/*` and `MetadataResource`:
  - Types, objects, identifiers per source system, versioned attributes and relations, all with validity periods.
  - Templates `energy` and `iot`.
- Pipeline steps: PROCESSING "Enrich with metadata" and EXPORT "Save to metadata".
- UI: page `/metadata` (list, as-of detail with history, edit, CSV import, types and templates) and panels in the pipeline editor.
- Attribute values are converted to the types in the schema.

**Fire-and-forget pull inputs** (`connectors/inbox/*`)
- `InboxPoller` keeps a ledger in the table `connector_inbox_items`.
- Sources:
  - FILE: a directory, confined to `dataturntable.connectors.file.base-dir`.
  - FTP/FTPS (commons-net).
  - SFTP (MINA SSHD; the host key must be pinned).
  - IMAP (Angus Mail; reads with PEEK).
- REST polls deduplicate each record by `deduplicateBy`.
- The source item is acknowledged only after Kafka has the data. There are 11 tests with embedded servers.

**Connector UI for the pull inputs**
- `frontend/src/pages/Connectors.tsx` now creates and edits `FTP`, `SFTP`, `EMAIL` and inbox-`FILE`
  (types added to `Connector['connectorType']` in `types/index.ts`), with directory inbox fields
  `path`, `pattern`, `format`, `afterRead`, `minAgeSeconds` replacing the old FILE fields.
- Per-type fields: FTP `host`/`port`/`tls`/`username`/`password`/`path`; SFTP adds `privateKey`,
  `passphrase` and `hostKeyFingerprint`; IMAP adds `folder`, `content`, `pattern`, `afterRead`.
- Every password-type field goes through `withSecretRefs` (`lib/secrets.ts`) when saving. The
  backend rejects inline credentials ("Connector credentials must use secretRefs"), and a blank
  secret field on edit means "keep the stored secret", so a user can change a host or path without
  re-entering a password they cannot see.
- Polling is enabled for the inbox types, and the inbox ledger is surfaced
  (`GET /connectors/{id}/inbox`, `POST …/inbox/{itemId}/retry`).
- 5 new tests in `frontend/src/__tests__/pages/Connectors.test.tsx`.

**Unstructured blob storage removed** — the platform stores records, not payloads.
Files, mails, FTP/SFTP items, attachments and binary frames are parsed at the ingest boundary and
only the parsed records are persisted; the source item stays where the remote system put it.
- Deleted `DataBlobRecord.kt` and the whole blob block in `DataResource.kt` (config, endpoints, SQL,
  helpers, and the now-unused `File`/`ByteBuffer` imports).
- Removed the `data_blobs` table, its primary key, indexes and foreign keys from `V1__baseline.sql`.
  No forward `DROP TABLE` migration is needed: per the project policy in
  `docs/production-ready-v1-status.md` there is no production data and no live instance.
- Removed `data.blob` / `dataturntable.data.blob-dir` from `application.yaml`.
- Removed blob tests and routes from `DataResourceTest.kt` and `CrossTenantIsolationTest.kt`.
- Removed `DataBlob`, the blob API methods and the whole Files tab from `frontend/src/types/index.ts`,
  `frontend/src/services/api.ts` and `frontend/src/pages/Data.tsx`.
- Removed the blob archive/restore steps from `scripts/backup/backup.sh` / `restore.sh`; the backup
  is now two database dumps only.
- CI's schema check now asserts `data_table_registry` and `connectors` instead of `data_blobs`.
- S3-compatible object storage is still used, but only for lakehouse data files.

**Dev JWT key generation** — the backend sets
`DATATURNTABLE_JWT_SIGNING_KEY_LOCATION` / `..._PUBLIC_KEY_LOCATION` with no default, and nothing in
Compose provided them, so no backend could ever verify a token.
- `scripts/dev/generate-dev-keys.sh` generates a throwaway RSA pair into `secrets/dev-keys/`
  (`PKCS#8` private, `SPKI` public — the forms smallrye expects). Idempotent; `FORCE=1` regenerates;
  prints a warning that regenerating invalidates issued tokens. Verified: `bash -n` clean, and
  `public.pem` genuinely derives from `signing.pem`.
- **Two `.gitignore` traps to be aware of:**
  - `/secrets/` was added to keep the private key out of the repo. It is **anchored to the
    repository root on purpose** — an unanchored `secrets/` also swallowed the pre-existing empty
    `docker/secrets/` directory. Git never tracks empty directories, so nothing was hidden, but the
    narrower pattern is the correct one.
  - `.gitignore` ignores `scripts/*` with an allow-list, so the new script would have been silently
    uncommitted. `!scripts/dev/` and `!scripts/dev/generate-dev-keys.sh` were added for that reason.
- `docker/docker-compose.dev.yml` mounts `../secrets/dev-keys:/keys:ro` in all four backend roles.
  The `../` is required: Compose resolves relative bind paths against the **project directory**
  (here `docker/`), so `./secrets/...` silently mounted an empty directory and produced
  `SRJWT02003: 'mp.jwt.verify.publickey.location' is invalid`. `public-api` had no `volumes:` block
  at all and needed one.
- `README.md` documents generating the keys before `docker compose up`.

**Documentation**
- Functional spec: §3.1–3.4 (pull inputs, fire-and-forget, no unstructured storage), §4.9
  (Kafka RF3 + min ISR 2, ingest spool, fire-and-forget semantics), §7.2/§7.5 (connector types and
  secret references), §8 (structured-only), §13.4 (metadata enrichment), §13.5 (§14a as an
  **example only**), §17.1 (Compose is not redundant).
- SRS: scope, product functions, assumptions, `IMP-10`–`IMP-13`, `CON-01`/`CON-13`–`CON-16`,
  `STR-08`/`STR-09`, new §4.15 `MET-01`–`MET-10`, `NFR-R7`–`NFR-R9`, MVP additions, `M5`, traceability.
- `docs/operations/backup-restore.md`: two databases, no file tree to copy.
- `docs/operations/support-matrix.md`: FR-022 is now "Implemented — deliberately out of scope".
- `docs/development/source-function-reference.md`: regenerated with
  `pwsh -File scripts/generate-source-function-reference.ps1` (do not hand-edit; it was stale and
  the regeneration also picked up ~570 declarations and many line numbers that had drifted).
- `docs/production-ready-v1-status.md`: new 2026-09-27 session entry.
- `docs/development/requirements-review.md`: MVP status and measurements.

**Verification (re-run fresh, not carried over from an earlier session)**
- Full backend suite in `maven:3.9.16-eclipse-temurin-25`: **321 tests, 0 failures, 0 errors,
  22 skipped**. The skips are the Docker-only tests, which have no daemon inside the Maven
  container; the host has Docker if the live smoke test needs it. (Was 318 before the three
  security regression tests below were added; all three pass.)
- Targeted `CrossTenantIsolationTest` + `DataResourceTest`: 23 tests green.
- `npx tsc --noEmit -p .` clean; `npx vitest run` 8 files / 38 tests green; `npx eslint src`
  0 errors / 21 pre-existing warnings (hooks deps and similar); `npm run build` 370 modules.
- Live Compose smoke (PostgreSQL + Kafka + `control-api` + `public-api`, throwaway project):
  health UP, Flyway applied V1, setup/login/`/me` work, SFTP connector create, inbox endpoint,
  blob routes 404, `information_schema` confirms no `data_blobs` table. This found three real
  defects the unit tests could not (items 3 and 4).
- The three new security regression tests were checked for **vacuity**: with the guard removed all
  three fail (200/201 where 400 is expected); restored, all pass.

**Smoke-test environment note.** Host port 5432 is held by an unrelated `twitch_log_db` container.
Do not stop or reconfigure it. Use a throwaway project and an override file that unbinds the
published port: `postgresql: { ports: !override [] }` (needs Compose ≥ v2.24; local is v2.31).

## Merge of `dev`: decisions taken (owner chose "combine best per area", land via PR)

**Taken from this branch**
- Ingest, consumer and delivery (run claims, spool, DB-free ingest).
- Alerts evaluated in pipeline graphs, with the outbox.
- Warm-container module execution on the pipeline hot path. `dev`'s ExecutionService, which writes a DB row per run or starts a Kubernetes Job, is too slow for per-record throughput. It remains for the ad-hoc execution API.
- The HA overlay with CNPG, Kafka RF3 and MQTT. `dev`'s pgpool and Redis patches were dropped.
- BCrypt via Elytron. `dev`'s argon2-jvm, jbcrypt and spotify docker-client were not taken.

**Taken from `dev`**
- Normalized authorization tables and the `is_platform_admin` flag.
- API keys for ingest, versioned secrets with `secretRefs`, audit events.
- Connector revisions, lease fencing tokens, pipeline versions, retention cleanup.
- Log redaction, the global error format `{code,message,details,error}`, CI workflows, backup scripts, cross-tenant tests, sandbox flags for Docker modules.

**Removed from `dev`**
- Standalone alert-rule entities (AlertRule, AlertRuleAction, AlertRuleCooldown), which the in-graph rules replace.
- Event envelope, inbox and outbox, and PipelineRunResolver, which duplicate the run claims.
- Redis config.

**Adapted during the merge**
- `IngestAuthorizationService` caches API keys by SHA-256, so ingest needs no DB call per record. `last_used_at` is flushed every 30 s.
- `IngestRateLimiter` is off by default (`dataturntable.ingest.rate-limit-per-minute`), because fire-and-forget senders cannot retry a 429.
- Runs are pinned to the pipeline version current when the run is claimed or created. The consumer executes that version's graph.
- Unparseable pipeline messages go to `consumer_dead_letters` (`GET /api/internal/v1/events/dlq`).
- `dev`'s migrations V7–V24 are folded into `V1__baseline.sql`. It was verified on PostgreSQL 16, and Hibernate validates the schema.
- `secret:manage` was added to the Tenant Admin role. Users with `connector:write` or `alert:configure` may create and rotate write-only secrets.
- Frontend:
  - `lib/secrets.ts` (`withSecretRefs`) stores credentials from forms as secrets. The Alerts page uses it.
  - Alert channels and history are paginated (`{items,…}`).
  - `ApiError.details.missing`.

## Session 2026-09-28

These changes were verified against a live stack: the jar with PostgreSQL 16 and Kafka, plus
Vite and Playwright.

**§14a example as a real use of the product** (`docs/examples/energy-14a/`, `Energy14aExampleTest`):
- Four pipeline definitions: SAP meters, internal locations, LoRaWAN devices and LoRaWAN readings.
- Readings are enriched with meter, market location, location and grid area, using the metadata
  that was valid at the reading's time. The meter exchange on 1 June is covered.
- The enriched readings are delivered to two REST systems.
- A resend with the same `Idempotency-Key` produces no second delivery.
- A reading from an unknown device fails its run.
- §13.5 of the functional specification describes the example as it is built.
- It was also run live through the ingest API with an API key.

**EXPORT "Send as is"**
- An EXPORT node without a module delivers the record unchanged through its OUTPUT connector.
- The delivery key is `<runId>:<nodeId>`.
- Validation requires an OUTPUT connector of the same tenant.
- The editor offers three options: Python module, Send as is, and Save to metadata.

**Metadata merge by other identifier**
- When `match` finds nothing, `upsert` resolves the other identifiers of the request (same type
  only) and adds the match identifier to that object.
- This keeps a placeholder, for example a meter created from the device registry, from being
  duplicated when SAP later delivers the full record.

**`DestinationPolicy` (SSRF guard)**
- Connectors cannot reach loopback, private, link-local, multicast or ULA addresses unless they are
  inside `dataturntable.connectors.allowed-networks` (`DATATURNTABLE_CONNECTORS_ALLOWED_NETWORKS`,
  CIDR list).
- Cloud metadata endpoints are always blocked.
- The check runs when a connector is saved and again on every connect, for REST, WebSocket,
  FTP/SFTP/IMAP and outbound delivery.
- Tests allow `127.0.0.0/8`.

**Ingest API keys** (`ApiKeyResource`, `/api/internal/v1/api-keys`, needs `secret:manage`)
- A key is granted exact pipelines and INPUT connectors.
- The key is shown once and stored as SHA-256.
- Keys can have an expiry and are audited (`apikey.created` / `apikey.revoked`).
- Revoking a key drops it from the local cache at once; other instances drop it within 30 s.
- The UI page is `/api-keys`, visible with `secret:manage`.

**Verification (2026-09-28, after all changes above)**
- Backend `mvn clean test -Dquarkus.http.test-port=0`: 327 tests, 0 failures, 18 Docker skips.
- `PostgresMigrationBehaviorTest` passes against an empty PostgreSQL 16 database.
- Frontend: `tsc` clean, eslint shows 0 errors (warnings only; hook-dependency warnings predate this
  work), vitest 38/38, `npm run build` passes.
- Pitfalls:
  - Always pass `-Dquarkus.http.test-port=0`; a running stack holds 8081.
  - Run `clean` after deleting entities. A stale `DataBlobRecord.class` in `target/` made the
    PostgreSQL IT fail schema validation ("missing table data_blobs"). H2 does not notice this
    because it creates its schema from the entities.
- The `connectors(tenant_id, name)` unique constraint was already in the PostgreSQL baseline. The
  entity now declares it too, so H2 matches. Three tests that reused fixed connector names were
  fixed.

**Fixes found by the browser pass**
- Tailwind 4 dropped `bg-opacity-*`, so modal overlays were opaque. Modals also could not scroll, so
  the SFTP form was unreachable. All modals now use `bg-black/50` and `max-h-[90vh] overflow-y-auto`.
- The Vite proxy key `/api` also captured `/api-keys`; it is now `/api/`.
- Choosing an inbox connector type enables polling.
- A duplicate connector name returned 500. `TransientFailureMapper` now maps SQLState 23505/23503 to
  409 and 23502/23514 to 400, and `connectors(tenant_id, name)` has a unique constraint.
- `RetentionCleanupService` runs only on the control API with background jobs enabled. It had raced
  tests in the full suite.

## Session 2026-09-28 (continued): CI PostgreSQL version + ML use case

**CI fix:** `postgresql-migrations` tested against `postgres:16-alpine`, applying the Flyway SQL
files by hand with `psql` — a much weaker check than what this session ran manually (Hibernate
`validate` mode against the app's own Flyway startup), and against the wrong major version (we
ship PostgreSQL 18: `postgres:18.6-alpine3.24` / `timescale/timescaledb:2.30.1-pg18`). The job now
runs `PostgresMigrationBehaviorTest` for real via Maven against `postgres:18.6-alpine3.24`, then
keeps the `psql` table-existence and STR-08 (`data_blobs` must not exist) checks as a cheap second
opinion. Verified locally: the exact `mvn --batch-mode test -Dtest=PostgresMigrationBehaviorTest
-Dquarkus.test.profile=postgres-it` invocation with `DATATURNTABLE_IT_DB_URL/_USER/_PASSWORD` env
vars passes against a fresh database (only PostgreSQL 16 was available in this sandbox — no Docker
daemon here, so the exact 18.6-alpine3.24 image itself has not been pulled and run; the mechanism,
env vars and Maven invocation are proven, the image tag is not). **Confirm the first real CI run
on GitHub actually pulls and passes against postgres:18.**

**New use case: train and apply ML models inside the platform (owner request 2026-09-28).**
No dedicated ML-serving component: a PROCESSING module fits a model and persists it; another
(or the same, in a later pipeline) loads and applies it. This needed one real design decision,
since nothing like it existed:
- New requirements MOD-15 – MOD-19, DAT-10 in the SRS (v2.3), status **Open** — design only at the
  time this entry was written. **Implemented in the next section; MOD-15/17/18/19 and DAT-10 are now
  Done in Appendix A** (MOD-16 Partial). Read that section, not this one, for the current state.
- New `docs/specification/functional-specification.md` §5.6 "Model Artifacts" and §13.6 worked
  example (load-forecast: nightly training pipeline, continuous inference pipeline, hot-swappable
  model version).
- Two new injected functions specified: `save_model_fn(name, model)`, `load_model_fn(name,
  version=None)`. Not implemented — `DockerService.kt`'s Python harness only injects `alert_fn` and
  `query_fn` today.
- Storage: a new `model_artifacts` table (tenant + module + name, versioned, encrypted at rest like
  secrets) is specified but not created — no Flyway migration, no entity.
- Explicitly reconciled with STR-08 (DAT-10): a model artifact is platform-internal state a module
  writes and reads back for itself, never a user-facing file/blob, so it doesn't reopen the
  unstructured-storage decision.
- README feature list updated.

**To implement next** (in dependency order) — *this was the original plan, kept for the record; it
was carried out in the next section:* `model_artifacts` migration + entity + a
`ModelArtifactService` (save/list-versions/load, tenant-scoped, encrypted like `SecretService`) →
wire `save_model_fn`/`load_model_fn` into the module execution harness in `DockerService.kt`
(same pattern as `alert_fn`/`query_fn`: a Python shim that calls back into the JVM host, likely via
a small local socket/file protocol already used for `__RESULT__`) → a
`ModelArtifactResource` for the UI (list versions per module, size, created-by-run) → tests:
train-then-infer across two independent module executions, version pinning, hot-swap on a new
save. No UI page is strictly required for MVP (the Python API is enough); a simple read-only list
under the module's detail view is a reasonable first UI step.

## Session 2026-09-28 (continued): model artifacts implemented (MOD-15 – MOD-19)

Followed up the design from the previous entry with a real implementation, since remaining
session budget allowed it (owner: implement when budget allows, more examples only when it
doesn't).

**What was built:**
- `model_artifacts` table (`V1__baseline.sql`, one row per version — same pattern as
  `connector_revisions`), `ModelArtifact` entity, `ModelArtifactService` (save/loadLatest/
  loadVersion/preloadLatestForModule/listArtifacts). Values are base64 then encrypted with
  `EncryptionUtils` exactly like a secret version.
- `DockerService.kt`: `save_model_fn`/`load_model_fn` added to the Python harness (alongside
  `alert_fn`/`query_fn`). Design constraint that shaped the implementation: module execution is
  one-shot (a script prints one `__RESULT__:<json>` line and exits) with **no live channel** back
  to the host while it runs, so:
  - `load_model_fn(name)` reads from a dict of every named artifact's **latest** version,
    preloaded into the `MODEL_ARTIFACTS` env var before the container starts.
  - `load_model_fn(name, version=<int>)` (an explicit historical version) raises
    `NotImplementedError` inside the module — pinning an old version this way is not supported;
    documented honestly rather than silently returning the wrong thing. A test asserts it fails
    loudly (`ModuleExecutionException`).
  - `save_model_fn(name, model)` pickles+base64s the model into `_DT_SAVED_MODELS`; if
    `process()` returns a `dict`, the platform merges in a `_savedModels` key, reads it back after
    the container exits, persists each entry via `ModelArtifactService.save`, and strips the key
    before the result reaches the next pipeline node. If `process()` doesn't return a `dict`, the
    module gets a clear stderr warning instead of a silently lost model.
- `ModelArtifactRoundTripTest` (Docker-gated like `DockerServiceHostileModuleTest`, skipped here —
  no daemon in this sandbox): train in one execution, infer in a completely independent second
  execution and get the right answer; retrain (new version) and confirm the next inference picks
  it up automatically (MOD-19 hot-swap); loading a name never saved returns `None`, not an error;
  pinning an explicit version fails loudly.

**Verified:**
- Full backend suite: 331 tests, 0 failures, 22 skipped (Docker-only, including the 4 new ones).
- `ModelArtifact` matches the migration exactly under Hibernate `validate` mode against real
  PostgreSQL (`PostgresMigrationBehaviorTest`).
- The 4 new Docker-gated tests could not be run for real in this sandbox (no daemon here, same
  limitation as the existing `DockerServiceHostileModuleTest`) — **run them for real before
  relying on this feature**, the way `DockerServiceHostileModuleTest` already is elsewhere.

**Follow-up in the same session:** added the caching MOD-18 needed (`ModelArtifactService`
preloads per module for `dataturntable.execution.model-artifact-cache-seconds` (default 60s); a
save invalidates its own module's cache entry at once, so the training instance's very next
execution sees it immediately, matching how `IngestAuthorizationService` already handles ingest
API keys). `ModelArtifactServiceTest` (no Docker needed) covers versioning, the encryption
round-trip and that a save is reflected immediately without waiting for the TTL. Full suite:
**335 tests, 0 failures, 22 Docker-only skips.** MOD-18 is now Done in Appendix A, not Partial.

**Handed off 2026-09-28** at the owner's request, mid-session, with budget still available.
Everything above is committed and pushed to `claude/datahub-lakehouse-review-6tiaf5`. Nothing is
half-done or uncommitted. Remaining before landing:
1. **Run the 5 Docker-gated model-artifact tests for real** (`ModelArtifactRoundTripTest`, 4 tests;
   plus re-confirm `DockerServiceHostileModuleTest` still passes) on a machine with a Docker
   daemon — this sandbox has none. Same caveat as every other Docker-gated test in this repo.
2. **Ask the owner before opening the PR into `dev`** — everything else in "Open work" below still
   applies; nothing new blocks it.
3. If continuing documentation-vs-implementation triage: the SRS Appendix A is current as of this
   commit; the next reasonable target is the remaining M0 item, **TEN-03/NFR-S8 per-tenant database
   roles**, per the milestone table in the SRS, §11. ALT-13 is now Done (see the last session
   section); it is the tractable half of the M0 pair, and TEN-03 is the larger one.

**Honestly scoped, not overclaimed (Appendix A updated accordingly):**
- MOD-15, MOD-17, MOD-19, DAT-10: Done. (MOD-15 was later corrected to **Partial** once the
  model-forecast example showed the module-scoping gap against the requirement's wording — see the
  last session section. The entry below reflects the state at the time of that commit.)
- MOD-16: Partial — a save records its own version; a run does **not** log which version it
  *read*, only which it created. Would need a `_loadedModels`-style marker (same trick as
  `_savedModels`) surfaced into the run's node log.
- MOD-18: Partial — functionally correct (including hot-swap), but the artifact is re-fetched
  from the database on **every** execution rather than cached once per warm-container process.
  A real but modest cost (one indexed lookup per named artifact per record); the fix is caching
  in `WarmContainerPool` keyed by module id, invalidated on a version bump.

**Not done, deliberately out of scope for now:** no UI page to browse/list model artifacts (the
  Python API is enough for MVP per the earlier design note); no example under `docs/examples/`
  comparable to §14a (would need Docker to verify end-to-end here, unlike the SQL-only §14a test).

## Session 2026-09-28 (continued): the Docker-gated tests finally run for real

**Remaining item 1 of the previous handoff is closed.** That entry said the 5 Docker-gated tests
"could not be run for real … this sandbox has none". This machine *does* have Docker 27.4.0 — the
earlier sessions simply never tried from a host with a daemon, because the project targets Java 25
and the host had only JDK 17/21, so every run used the Maven container, which has no daemon.

**Unblocking it needed two things:**
1. A Windows JDK 25 (Temurin 25.0.4.1) — see the "Tests" section at the top for the download.
2. Running the tests on the **host**, not in the Maven container. This is the non-obvious part:
   `DockerService` invokes the `docker` CLI via `ProcessBuilder` and bind-mounts host paths into the
   module container. From inside a Linux Maven container the JVM would pass Linux paths to the
   Windows daemon (and there is no `docker` CLI in the image), so these tests are structurally
   impossible in the container — they were never "just skipped for lack of a daemon".
   Host `mvn` 3.9.14 with `JAVA_HOME` pointed at JDK 25 is the working combination.

**Result — `mvn test -Dtest=ModelArtifactRoundTripTest,DockerServiceHostileModuleTest
-Dquarkus.http.test-port=0` on the host:**
- `DockerServiceHostileModuleTest`: **7 tests, 0 failures, 0 errors, 0 skipped.**
- `ModelArtifactRoundTripTest`: **4 tests, 0 failures, 0 errors, 0 skipped.**
- `BUILD SUCCESS` in 1:43. So train → save → independent execution → load → apply, the retrain
  hot-swap (MOD-19), the unknown-name `None`, and the loud `ModuleExecutionException` on version
  pinning are all verified against a real daemon, not just designed.
- The `ERROR … Container exited with code 1` lines in the log are **expected**: they are the
  hostile-module tests and the version-pinning test deliberately failing. Do not read them as a
  problem when scanning the output.
- `python:3.14.7-slim-trixie` (the module base image) was pulled first, so the first run is not a
  silent 10-minute image download.

**What this changes:**
- The `ModelArtifactRoundTripTest` caveat in the previous two entries is resolved: the feature is
  verified, not merely implemented. MOD-15/17/19 and DAT-10 stay Done on that basis.
- The reason a `docs/examples/` model-artifact example was skipped ("would need Docker to verify
  end-to-end here") no longer holds — Docker is available, so that example is now a realistic
  follow-up rather than a blocked one.
- **Still open before landing:** ask the owner before opening the PR into `dev` (unchanged).

## Session 2026-09-28 (continued): the whole suite finally runs for real — three test defects found

Went past "run the Docker-gated tests" and asked what else had never actually executed. Three
defects, all in tests, none in the product. **Full suite on the host: 335 tests, 0 failures,
0 errors, 10 skipped** — versus the "335 tests, 0 failures, 22 skipped" recorded earlier, where 12
of those skips were the Docker-gated tests and 4 more were a class that had *never run anywhere*.
(Those older skip counts are unreliable; this run is the trustworthy one.)

**1. `ModuleScriptTest` had never run anywhere — and failed on Windows for a silly reason.**
- The `maven:3.9.16-eclipse-temurin-25` image has **no `python` and no `python3` at all** (checked
  directly), so this class was skipped in every container-based run. It only ever runs where the
  platform has a real interpreter, i.e. GitHub's `ubuntu-latest`.
- On Windows the Microsoft Store app-execution alias provides a `python3.exe` stub that prints
  `Python wurde nicht gefunden; …` **and still exits 0**. The old `pythonAvailable()` checked only
  the exit code, so it concluded Python existed, the four tests ran against the stub and failed.
  Fixed to require a real `Python \d+\.\d+` banner. Note the trap: a `startsWith("Python")` check is
  **not** sufficient, because the stub's own message begins with that word — I wrote that version
  first and it still failed. A version regex is required.
- Second defect in the same class: it passed the generated script as `python3 -c <script>`. On
  Windows the JVM has to rebuild a command line, and the script's double quotes are mangled in
  transit, so an f-string arrived as `fModule` and Python reported a bogus
  `SyntaxError: '(' was never closed`. It now writes the script to a temp file and runs that. This is
  not papering over a product bug: `DockerService` never uses `-c` in production (the Docker path
  passes the script base64-encoded in `__DT_BOOT__` and `exec`s it in the container), and the
  Docker-gated tests exercise the same generated script successfully.
- Verified both directions: **4/4 pass** against a real Python 3.13.7, and they skip cleanly when no
  real interpreter is present.

**2. `PipelineRunQueueTest` was a latent flake that would redden CI.**
`passThroughImportFeedsBuiltInRuleWithoutAnyModule` asserted the webhook deliveries immediately,
but alerts go out through the transactional outbox on a *scheduled* dispatcher, so the requests can
land after `consume()` returns. It failed once in the full suite with an empty `received` list while
passing in isolation (verified twice). A 15 s bounded poll was not enough and it failed again on
2026-09-28 with 1 of 2 webhooks, still passing 3/3 in isolation. Root cause of the second failure:
the outbox's first retry is backed off **10 s** (`AlertDispatcher.backoff`), so a run that has to be
retried can never fit inside a poll that only sleeps. The poll now calls `alertDispatcher.deliverDue()`
each iteration instead of sleeping on the 5 s scheduler, so delivery no longer depends on the
scheduler ticking. Not caused by change 1 — the class shares no state with it.

**3. PostgreSQL 18.6 is now verified for real, not just configured.**
Started a throwaway `postgres:18.6-alpine3.24` (the exact CI tag) and ran the exact CI command from
`.github/workflows/ci.yml` — `mvn --batch-mode test -Dtest=PostgresMigrationBehaviorTest
-Dquarkus.test.profile=postgres-it` with the `DATATURNTABLE_IT_DB_*` env vars: **Flyway applied
`V1__baseline` and Hibernate `validate` matched, 1 test, 0 failures.** This closes the carried-over
"PostgreSQL 18 is unverified" item. Port 55432 was used because the unrelated `twitch_log_db` holds
5432; the port is irrelevant to what is being verified.

**Making this environment reproducible (all admin-free, all in `%TEMP%\opencode`):**
- Windows JDK 25: `curl.exe -sS -L -o jdk25.zip 'https://api.adoptium.net/v3/binary/latest/25/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk'` then `tar -xf jdk25.zip -C jdk25`. (bsdtar reads the zip; `Expand-Archive` also works.) Chocolatey **cannot** install anything here — no admin, `lib-bad` access denied — and the python.org MSI-style installer hangs, so don't retry either.
- Python 3.13.7: the **embeddable zip** needs no admin and no install —
  `https://www.python.org/ftp/python/3.13.7/python-3.13.7-embed-amd64.zip`, unzipped, then
  **copy `python.exe` to `python3.exe`** (the embeddable package ships no `python3.exe`, and the
  tests invoke `python3`). Its stdlib works for these tests.
- Then a host run needs both on `PATH` plus the real `docker` CLI, and everything un-skips except
  the 10 listed below.

**The 10 remaining skips, and why they are legitimate:**
- `PostgresMigrationBehaviorTest` (1) — needs a real PostgreSQL; run it as above, it passes.
- `ModuleServiceTest` (4) and `TenantServiceTest` (5) — gated for another reason; not investigated,
  they are not Docker-gated and were skipped in the earlier runs too.

**Still open before landing:** ask the owner before opening the PR into `dev`. Nothing technical is
blocked. The only genuinely unverifiable items left need a Kubernetes cluster (k8s render-only
wiring, hostile-module tests for the Kubernetes execution backend, the backup/restore drill, the
exactly-once failover test).

## Session 2026-09-28 (continued): model-forecast example, ALT-13 platform health alerts

**Full suite on the host: 351 tests, 0 failures, 0 errors, 10 skipped** (was 335: +12 health
monitor, +3 model example, +1 API mapping).

**A second documentation example** (`docs/examples/model-forecast/`, executed end to end by
`ModelForecastExampleTest` against a real Docker daemon): a nightly pipeline fits a line to a day's
hourly readings and `save_model`s it, an inference pipeline `load_model`s it and forecasts the next
hour, and a retrain is picked up with no restart. Plain-Python OLS keeps the arithmetic checkable,
so the test asserts exact coefficients (slope 10, intercept 100 → 160 for hour 6) rather than a fuzzy
model output. The README documents the limits honestly instead of only the happy path.

**MOD-15 corrected to Partial in Appendix A.** The example exposed a real gap between SRS and code:
MOD-15 says a model can be loaded "of the same module or a different one", but artifacts are keyed
by module id, so a *different* module cannot load them. Both roles therefore run from **one** module
version. Closing it means scoping lookups by tenant and name while keeping the producing module for
provenance — an isolation decision, not a bug fix.

**ALT-13 implemented** (`PlatformHealthMonitor`, 12 tests): the four conditions no ALERT node can
see — repeated run failures, processing lag, dead-letter records, undelivered alerts — raised to the
affected tenant's default channel and to `dataturntable.health.admin-emails`. It reuses the existing
`alert_history` outbox (`AlertDispatcher.enqueuePlatformAlert` / `defaultChannel`), so retry with
backoff and the FAILED state that the undelivered-alert check then reports all work unchanged. Runs
on the control API only, like retention cleanup, and `delivery_key` is problem + time bucket, so a
condition that persists is reported once per `dedupe-window-seconds` rather than on every scan.
Two design points worth keeping: an administrator email is recorded as a `platform-admin:`-prefixed
audit row with no channel, so a broken mail server is not itself reported as an undelivered alert
(which would keep generating mail for as long as it stayed broken); and a platform-wide finding goes
to administrators only, because telling a tenant that its alerts are not arriving, through the
channel that is failing to deliver them, would not help. Documented in functional spec §9.6.
Administrators are notified by email over the installation's SMTP settings — there is no separate
admin channel or UI yet.

**One API defect found by building the example:** a description longer than its 255-character column
came back as **500**, so a client input error looked like an outage worth retrying. SQLState `22001`
now maps to 400 in `TransientFailureMapper`. `PipelineResourceTest` asserts the message, using an
empty graph so the length is the only thing that can be wrong — otherwise the test would also pass
for the wrong reason.

## Documentation map (consolidated 2026-09-28)


The owner asked for one consistent story in fewer files. **The SRS is the main document and wins
every conflict**; FR-029 (microservices) was dropped. Where each fact lives:

| File | Holds |
|---|---|
| `README.md` | What the product is, quick start, pointers |
| `docs/specification/requirements-specification.md` (SRS v2.2) | Goals (1.5), all requirements, decisions against alternatives (§12), **the only implementation status** (Appendix A), measurements (Appendix B), old FR/NFR-ID mapping (Appendix C) |
| `docs/specification/functional-specification.md` | How the system works (architecture, runtime roles, data flow, design) |
| `docs/development/handoff.md` (this file) | Work log, decisions in flight, open work, pitfalls |
| `docs/development/setup.md` | Run, build, test; service URLs |
| `docs/development/configuration-reference.md` | Every environment variable (regenerated from `application.yaml`) |
| `CONTRIBUTING.md` | Workflow, code rules, which document to update for which change |
| Reference | `docs/api/README.md`, `source-reference.md`, `source-function-reference.md` (generated), `licenses.md`, `docs/operations/backup-restore.md`, `docs/docker/README.md`, `docs/examples/energy-14a/`, `docs/examples/model-forecast/` |
| User guides | `docs-site/docs/{en,de}` (served at `/docs`); the developer section of the site is copied from `docs/` at image build time |

Retired (content merged into the files above; history in git): `docs/development/plan.md`
(obsolete), `target-architecture.md` (→ SRS 1.5, functional spec), `requirements-review.md`
(→ SRS Appendix B), `architecture.md` (→ functional spec §2, CONTRIBUTING), `documentation-gaps.md`
(→ open work below), `docs/production-ready-spec.json` (→ SRS, Appendix C),
`docs/production-ready-v1-status.md` and `.agent-status/progress.md` (→ this file),
`docs/operations/support-matrix.md` (→ SRS Appendix A), `docs/user/` (→ `docs-site/docs`),
`docs-site/docs/developer/` (diverged copies; now built from `docs/`).

Verified: the documentation site builds with Zensical (`docs/` copied to `docs-site/docs/reference`)
with 8 warnings. Five are table-of-contents anchors that GitHub slugs differently (`&` → `--`), and
three are links from `docs/` to files outside it (`CONTRIBUTING.md`, `docker/k8s-ha/README.md`),
which work on GitHub but not in the site. `source-function-reference.md` is generated by a
PowerShell script and was **not** regenerated (no `pwsh` here); `source-reference.md` was updated
by hand and now covers every production source file.

Conflicts that were resolved in the process (the code was checked each time):
- **Password hashing:** Argon2id in README, FR-003, support matrix, v1 tracker; **BCrypt** in the code
  and SRS NFR-S2. BCrypt stays; Argon2id is recorded as "not adopted for now" (SRS §12).
- **Swarm** still listed as optional/experimental; removed (DEP-08).
- **Redis** in `architecture.md`, configuration reference, backup-restore, user guides: the code has
  none; leases and cooldowns are in PostgreSQL.
- **At-least-once** in plan, support matrix, functional spec layer table; exactly once for identified
  records (PIP-14).
- **Stale "open" statuses:** ingest API keys, audit trail, signing key in the repository, CI. Now
  Done/Partial per the code; the audit trail is **Partial** (writes, but no read API, UI, hash chain
  or notes), not "Implemented" as the v1 tracker claimed.
- **TimescaleDB:** the image is shipped but no hypertables are created → STR-01 Partial; the
  README no longer calls the storage "TimescaleDB-optimised".
- **Throughput:** README claimed "1B+ readings/day" as a fact; measured ~300 runs/s on one machine
  (SRS Appendix B). The target stays for multi-node Kubernetes only.
- **PostgreSQL version:** 18 (image `timescale/timescaledb:2.30.1-pg18`), not 16.
- **Service URLs:** `setup.md`, README and CONTRIBUTING disagreed (frontend on :80, :3000 or
  :8080; `/api/v1` "placeholder returning 501"). Checked against Compose and nginx.
- **Setup instructions:** CONTRIBUTING said Java 21 and Node 18 and linked a missing
  `CODE_OF_CONDUCT.md`.

## Open work (in priority order)

> ### Kubernetes JWT wiring — done and render-validated
>
> This was mid-flight and briefly left `docker/k8s/backend/deployment.yaml` unrenderable. It is
> **fixed now**; `kubectl kustomize docker/k8s` exits 0 and all four backend workloads were checked
> in the rendered output.
>
> Two things worth knowing if this is ever redone by hand:
> - `dataturntable-ingest-edge` is a **StatefulSet that already had a `volumeMounts:` block** (its
>   `spool`, backed by a `volumeClaimTemplate`). A second `volumeMounts:` key in the same container
>   is a hard YAML error, so the `jwt-keys` mount has to be **appended to the existing list**,
>   not added as a new key. The same goes for pod-level `volumes:`.
> - The `spool` entry appears as `- name: spool` at 12-space indent, which is **textually
>   indistinguishable from a 12-space `- name:` env entry**. An automated patch keyed on "last env
>   entry" therefore picked it up, put the env vars inside the mount list, and left the workload
>   with no `DATATURNTABLE_JWT_*` env at all. Env lists and mount lists must be located by their
>   parent key, not by indentation.
>
> Verified per workload in the rendered output: the two
> `DATATURNTABLE_JWT_{PUBLIC,SIGNING}_KEY_LOCATION` env vars, a read-only `jwt-keys` mount at
> `/keys`, and a pod-level `jwt-keys` secret volume pointing at `dataturntable-jwt-keys`.
>
> **This is render-validated only — it has never been run against a cluster.** The Secret also
> ships empty, so a real deploy still has to populate it.

1. **Land the merge.** Everything is committed and pushed, and the browser pass (item 7) is done
   (see "Session 2026-09-28"). What remains is opening the PR into `dev`.
   - The live smoke test has been run against a real Compose stack (PostgreSQL + Kafka + both API
     roles): health, Flyway migration, setup/login/`/me`, SFTP connector create, inbox, blob routes
     404, and no `data_blobs` table. Three real defects were found and fixed by doing this
     (items 3 and 4 below) — none of them were visible from the unit tests. The browser-level
     Vite/Playwright pass is **also done** (see "Session 2026-09-28", "Fixes found by the browser
     pass") — it found four further real defects.
   - Open a PR into `dev`, following its PR template if one exists. **Ask the owner before opening
     the PR.**
   - Mention in the PR:
     - BCrypt instead of Argon2id; `dev`'s SRS asks for Argon2id, so Bouncy Castle is a possible follow-up.
     - Rule multi-channel actions from `dev` are not carried over.
     - The alert UI supports one channel per ALERT node.
      - The blob/S3-MinIO storage requirement (FR-022) was dropped, not deferred
        (`production-ready-spec.json` marks it `dropped`).
      - New: connector destination allow-list (`DATATURNTABLE_CONNECTORS_ALLOWED_NETWORKS`) — an
        upgrade that talks to internal hosts must set it. Also new: ingest API key management.
      - The datasource/JWT startup fixes also unblock the Kubernetes topology, which still needs a
        populated `dataturntable-jwt-keys` Secret and a live-cluster run to be trustworthy — see
        item 3. Say "render-validated only", not "working".

2. ~~**Reconcile the CI Java version before landing.**~~ **Fixed.** All three `java-version: '21'`
   occurrences in `.github/workflows/ci.yml` are now `25`, matching `backend/pom.xml`. The
   workflow file also had a pre-existing YAML indentation defect in the migration check that is
   fixed, and `actionlint`/`yamllint` parse it (7 jobs). Verified by a real
   `maven:3.9.16-eclipse-temurin-25` run of the full backend suite: 321 tests, 0 failures/errors,
   22 Docker-only skips.
3. ~~**Startup defect that blocked every container and every Kubernetes pod.**~~ **Fixed for Docker
   Compose and wired (render-validated) for Kubernetes.** Found by
   bringing the Compose stack up for real rather than trusting the docs:
   - `quarkus.datasource.jdbc.url` existed **only** under `%dev`, and no deployed profile includes
     `dev` (Compose uses `<role>`; the k8s manifests use `prod,<role>`), so the backend refused to
     start with `Failed to load config value ... quarkus.datasource.jdbc.url`. The URL (plus
     dev-friendly credential defaults) now lives in the root `quarkus.datasource` block; `%prod`
     still overrides username/password to mandatory values and `%test` still switches to H2.
   - The required JWT key locations were provided by nothing, in Compose *or* in the k8s manifests,
     so no backend could ever verify a token. `scripts/dev/generate-dev-keys.sh` now generates a
     throwaway pair into the gitignored `secrets/dev-keys/`, and Compose mounts it read-only into all
     four roles. Verified live: `control-api` and `public-api` both reach `healthy` on their
     role-only profiles, and setup/login/`/me` succeed.
   - `README.md` documented `cd docker && docker-compose -f docker-compose.dev.yml up -d`, which
     fails immediately (wrong path from the repo root, and deprecated v1 syntax). Now
     `docker compose -f docker/docker-compose.dev.yml up -d`, preceded by the key-generation step.
    - **Kubernetes: wired and render-validated, but not run — see the note at the top of this
      section.** The JDBC-URL change already fixed one of the two k8s blockers, so the topology now
      fails later (on keys) rather than earlier (on URL). The JWT side is now in place:
      - `docker/k8s/storage/secrets.yaml` gained a `dataturntable-jwt-keys` Secret with empty
        `signing.pem` / `public.pem` placeholders and a documented
        `kubectl create secret generic … --from-file=… --dry-run=client -o yaml | kubectl apply -f -`
        command. This follows the existing convention in that file: the repository never ships
        credentials, and the operator fills them in. Empty keys are still a broken config — the pods
        start and migrate, but `/q/health/ready` never goes green (smallrye `SRJWT02003`).
      - `docker/k8s/backend/deployment.yaml` now gives all four backend workloads
        (3 Deployments + the `ingest-edge` StatefulSet) the two env vars, a pod-level `jwt-keys`
        secret volume and a read-only `/keys` mount. `kubectl kustomize docker/k8s` exits 0.
      - **Nothing here can be run, only rendered.** `kubectl kustomize` is the strongest available
        check, because the support matrix records this topology as never deployed to a live
        cluster. Do not describe it as working; say "validated by kustomize render only".

4. ~~**Inline-credential bypass, found by the live smoke test.**~~ **Fixed and verified.**
   `validateConnectorConfig` / the alert equivalent walked the config tree, and a config sent as a
   JSON *string* (a serialized object inside a string field) matched the `else -> null` branch and
   skipped validation entirely — so a plaintext password was persisted and returned. Both resources
   now reject a non-object config root, while scalars nested inside a config (`"port": 22`) remain
   legal. Regression tests were added to `ConnectorResourceTest` and `AlertResourceTest` and
   confirmed non-vacuous by removing the guard and watching all three fail (200/201 instead of 400).
   Verified live over HTTP: connector create and PATCH, and alert channel create, all reject a
   string config with 400 "must be a JSON object" while a valid `secretRefs` object still returns
   201/200.
5. ~~**Update `docs/production-ready-spec.json`.**~~ **No action — retired.** This entry is stale: the
   documentation consolidation above already retired that file (content merged into the SRS, Appendix
   C). Do not reopen it, and do not spend time rewriting a deliberately historical artifact.
6. ~~**#19 §14a example walkthrough.**~~ **Done.** `docs/examples/energy-14a/` plus
   `Energy14aExampleTest` build the four pipelines (SAP meters, internal locations, LoRaWAN devices,
   LoRaWAN readings), enrich readings with the metadata valid at the reading's time (meter, market
   location, location, grid area, including the 1 June meter exchange), deliver to two REST systems,
   prove an `Idempotency-Key` resend does not double-deliver, and prove a reading from an unknown
   device fails its run. Functional spec §13.5 describes the example as built; it was also driven
   live through the ingest API with an API key.
7. ~~**Vite/Playwright UI smoke.**~~ **Done.** Run against a live stack (Vite + Playwright). It found
   four real defects, all fixed: Tailwind 4 dropped `bg-opacity-*` so modal overlays were opaque;
   modals could not scroll, making the SFTP form unreachable; the Vite proxy key `/api` swallowed
   `/api-keys`; and a duplicate connector name returned 500 (now `TransientFailureMapper` maps
   SQLState 23505/23503 → 409 and 23502/23514 → 400).
8. **Known gaps**
   - MQTT QoS 0 and UDP cannot be acknowledged.
   - Warm containers keep network access after `pip install`.
   - FTPS needs certificates the JVM truststore accepts.
   - Retention of run rows limits how long idempotency lasts.
   - Measured throughput on one machine was ~306 runs/s. Micro-batching (PIP-15) is needed for the 1B/day target.


### Carried over from the retired trackers (2026-09-28)

- **PostgreSQL 18 — verified, done.** The images ship PostgreSQL 18
  (`timescale/timescaledb:2.30.1-pg18`) and earlier sessions only ever exercised the migration
  mechanism against a local PostgreSQL 16. Both gaps are now closed: CI's `postgresql-migrations` job
  runs `PostgresMigrationBehaviorTest` for real against `postgres:18.6-alpine3.24` (the `psql`
  table-existence and STR-08 checks are kept as a cheap second opinion), **and the exact 18.6 image
  was run locally with the exact CI invocation — Flyway applied `V1__baseline` and Hibernate
  `validate` matched.** See the newest session entry.
- **Dependabot:** GitHub reports 6 vulnerabilities on the default branch (2 critical, 1 high,
  3 moderate). Check which apply to this branch after the merge into `dev`.
- **Kubernetes-only verification is still missing:** hostile-module tests for the Kubernetes
  execution backend (NFR-S3), the backup/restore drill on `docker/k8s`/`k8s-ha` (NFR-R11), and the
  exactly-once failover test on a cluster (NFR-R2).
- **DLQ:** there is a retry API but no inspection UI (PIP-13). `.agent-status` planned an end-to-end
  replay test through the broker (`/api/internal/v1/events/dlq/{id}/retry`), and a Kafka-backed IT
  workflow exists (`.github/workflows/integration-tests-kafka.yml`) that has never run on GitHub.
- **OpenTelemetry:** spans do not carry the platform's correlation/run ids yet, and there is no
  collector to verify against (NFR-M7).
- **Graph schema migrations** (PIP-17): only version 1 exists; write the migration framework when a
  version 2 appears, not before.
- **`ON CONFLICT` under JTA:** a caught SQL exception inside a JTA transaction still marks it
  rollback-only. `ConnectorLeaseService`, `EventInboxService`, `PipelineResource` and
  `PipelineDataService` branch on the dialect first; audit `DataResource.kt` (readings upsert,
  `data_table_registry` upsert) the same way.
- **Documentation gaps:** per-connector and per-alert-channel configuration examples with retry
  semantics are missing. `scripts/generate-source-function-reference.ps1` only recognises one-line
  Kotlin/TypeScript declarations; extend it before introducing multi-line declaration styles.
- **Grafana `admin/admin`** exists only in the Compose dev stack. The backend itself has no default
  Grafana credentials (NFR-S11).
- Pitfall (fixed, keep in mind): on this Quarkus version `Session.setMaxIdleTimeout` is a no-op
  for WebSockets, so idle sessions are closed by a scheduled reaper instead.

## Session 2026-09-28 (continued): TEN-03/NFR-S8 — per-tenant PostgreSQL database roles

The last open M0 item. Before this, `resolveSchema`/`tenantSchemaName` computed a `tenant_<uuid>`
schema name in application code, but nothing in the database stopped a query from reaching a
*different* schema if that code was wrong or a module config lied about it (`config["schema"]` was
taken as given in `PipelineDataService`). NFR-S8 asks for isolation "enforced by the data layer
itself... not only by application checks" — this closes exactly that gap for the hot tier.

**What was built** (`TenantDatabaseRoleService`, new):
- Each tenant gets its own PostgreSQL role (`tenant_<uuid>`), created lazily on first use, that
  owns the tenant's `tenant_<uuid>` schema (`ALTER SCHEMA ... OWNER TO`, so this also fixes
  schemas created under the old shared-role model, not just new ones).
- The connecting app user is granted membership in the tenant role (`GRANT tenant_x TO
  CURRENT_USER`) so it can `SET ROLE tenant_x` for the duration of a connection, then `RESET ROLE`.
- Wired into `PipelineDataService.execute()` (the automatic, unattended DB_LOAD/DB_SAVE path — the
  highest-risk one, since it runs per pipeline record with no human oversight) and into all six
  real call sites in `DataResource.kt` that touch pipeline data: `queryData`, `summarizeData`,
  `insertData`, `listTables`, `deleteTable`, `registerTable` (via one new helper,
  `pipelineConnectionForTenant`; a 7th, `ensureTenantSchemaExists(tenantId: String)`, is dead code
  with no caller and was left alone).
- **No-op on H2** (tests) — `isH2(connection)` gates it, exactly like the codebase's existing
  Postgres-only-feature guards elsewhere.
- **Deliberate, logged fallback, not a silent one:** provisioning needs the connecting database
  user to hold `CREATEROLE`. If it doesn't, `ensureRoleAndSchema` catches the exception, logs a
  `WARN` once per tenant naming exactly what's missing, and the caller proceeds on the shared
  connection's own privileges — the old behaviour, not a crash. **A production installation must
  confirm the connecting user actually holds `CREATEROLE`, or this protection is silently absent**
  (documented in the configuration reference and the functional spec's implementation note).

**Verified against real PostgreSQL 16** (only 16 was available in this sandbox, same caveat as
every other Postgres-gated test in this repo — the shipped major version is 18):
- `TenantDatabaseRoleServiceTest` (4 tests, Postgres-gated like `PostgresMigrationBehaviorTest`):
  a role is provisioned and owns its schema; **a tenant's role is refused by PostgreSQL itself when
  asked to read another tenant's schema by name, even though the SQL text explicitly names it** —
  this is the actual security property NFR-S8 asks for, not just that the happy path works; a
  tenant can read and write its own schema under its own role; re-provisioning is idempotent.
- **The fallback path was checked by hand, not by the automated tests**: revoked `CREATEROLE` from
  the test database role and re-ran the suite. The four tests above legitimately failed — they
  assume `ensureRoleAndSchema` created the schema, which is *only* true in the happy path; they do
  not exercise `PipelineDataService`/`DataResource`'s own independent schema creation inside the
  fallback branch. That the real product paths still work without a role is instead evidenced by
  every H2 test in the full suite passing (H2 always takes the identical "no role" branch as the
  fallback does), plus reading the code: `ensureSchemaExists`/`ensureTenantSchemaExists` run
  *inside* `withTenantRole`'s block, which runs unconditionally when no role is available. This is
  a structural argument, not a black-box test against a real Postgres instance with `CREATEROLE`
  revoked — if that distinction matters, add a fifth `TenantDatabaseRoleServiceTest` case that
  calls into `PipelineDataService` directly with a role-less connection to close it for real.
- Full suite on H2: **355 tests, 0 failures, 0 errors, 29 skipped** (was 351/10; +4 new
  Postgres-gated tests).

**SRS updated:** TEN-03 and NFR-S8 move from Open/Partial to Partial with a precise note (database
level done for the hot tier; storage-level/lakehouse namespaces do not apply yet, the lakehouse
isn't built; the CREATEROLE dependency and its fallback are named explicitly rather than implied).
Not marked Done, on purpose: a requirement that silently degrades to its old behaviour when an
operational precondition isn't met is not fully met.

**Not done, deliberately out of scope for now:** the Data Query API's remaining read-only paths
that don't touch `pipelineConnectionForTenant` (none currently — all six were wired); a
`ModelArtifact`-style migration to move tenant-role provisioning to tenant-creation time instead of
lazy first-use (lazy is simpler and self-healing for tenants created before this feature, so it was
kept); Kubernetes and catalog-namespace isolation for the lakehouse, which doesn't exist yet.

## Sources used

- BK6-22-300 Anlage 1: https://www.bundesnetzagentur.de/DE/Beschlusskammern/1_GZ/BK6-GZ/2022/BK6-22-300/Beschluss/BK6-22-300_Beschluss_Anlage1.pdf?__blob=publicationFile&v=1
