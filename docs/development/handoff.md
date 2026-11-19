# Handoff: DataTurntable review and merge (state 2026-11-16)

This document lets another assistant or developer continue the work without the chat history.

> **Read this first.** The newest entry is **"Session 2026-11-16"** near the end; it says how to
> run the tests locally and what was built last. **PR #2 (`claude/datahub-lakehouse-review-6tiaf5` → `dev`) merged.**
> https://github.com/Liphip/datahub/pull/2 — that branch's job is done and it should not receive
> further commits (a later commit landed on it by mistake after the merge; ignore that branch
> entirely going forward). **Work is now happening directly on `dev`** per explicit owner
> instruction ("work directly on dev branch, continue until budget is used, keep committing and
> documenting in between"). Everything below this point that isn't inside a dated session entry may
> describe pre-merge state; trust the newest session entries and `git log` on `dev` over the prose.
> All 17 PR #2 bot review findings were fixed and their threads resolved before merge. CI was fully
> green at merge time except the still-open, not-code-fixable `DC_NVD_API_KEY` job (owner needs to
> add that repo secret). **New finding after the merge:** `ci.yml`'s `push` trigger targeted
> `branches: [main]`, but this repo has no `main` branch (only `master`, protected, and `dev`) — so
> direct pushes to `dev` never ran CI automatically, only `pull_request` events did. Fixed (commit
> `2fb9be5`): push trigger now targets `dev`. See the newest session entries for what's landed
> directly on `dev` since the merge. The full backend suite on H2 was green pre-merge:
> **378 tests, 0 failures, 0 errors, 22 skipped** (all skips are legitimately Postgres/Docker/
> Python-gated — the previous 9 "unexplained" H2 skips are gone, see the relevant session entry
> below). To reproduce the Docker/Python-gated portion on a host you need a Windows JDK 25 **and**
> a real `python3` on `PATH` — the "Tests" section at the top has admin-free download recipes for
> both. Session entries below are in chronological order; the newest two are **"PR #2 — a Copilot
> review, eleven findings, all verified"** (plaintext secrets in audit metadata, an MQTT lease
> released by a replica that never owned it, a stale H2 test-schema load script that's been
> generating spurious "already exists" warnings all session, an unauthenticated dev broker exposed
> to the whole host network, a dropped image digest pin restored via two independently-verified
> lookups, and a documented-not-yet-fixed MQTT backup gap) and, before that, **"PR #2 — CI red
> fixed and a bot's six review findings triaged"** (a stray `PostgresTestProfile` bug plus six
> real, verified Codex findings — two P1s that could break every DB_LOAD/DB_SAVE operation without
> CREATEROLE, a P1 pipeline-version regression on machine-ingest records, a P1 that could silently
> drop spooled fire-and-forget records forever, and two P2 permission gaps). Before that,
> **"OPS-07 — hourly run-history aggregates"**:
> `RetentionCleanupService` now folds `pipeline_runs` rows into `pipeline_run_hourly_stats` before
> deleting them past the retention window, closing OPS-07 from Partial to Done (backend/API only —
> no UI page for it yet, flagged there). Before that, **"the 9 unexplained skips were dead code, not
> a test bug"**: `ModuleService`/`UserService` and most of `TenantService` had zero live callers and
> were removed along with the DTOs only they used, rather than re-enabling tests for code nothing
> calls — that investigation also found and fixed a systemic bug where 15 entities had a `UNIQUE` constraint
> in the Flyway migration that their JPA `@Table` never mirrored, so H2's schema-from-entities test
> database silently never enforced it (two of the 15, `alert_history.delivery_key` and
> `module_versions`' content-fingerprint dedup, were found in a follow-up sweep after the first one
> missed a second SQL syntax the migration uses for unique constraints). All 15 now have a
> dedicated database-level regression test.
> Before that, **"TEN-03/NFR-S8: per-tenant PostgreSQL database roles"** closed the last open M0
> item, verified against a real PostgreSQL 16 instance (cross-tenant access denied at the database
> layer, not just in application code), and corrected **MOD-15 to Partial** (artifacts are
> module-scoped, so the SRS's "or a different one" does not hold).
> Items in "Open work" that are ~~struck through~~ are done — do not redo them.
>
> **All six M0 items are now addressed** (some Partial, none Open): ingest auth, secrets out of the
> repo, per-tenant database roles, CI against PostgreSQL, platform health alerts, run-history
> retention. **Next target if work continues (owner's call, not a blocker):** M1 (micro-batch
> execution, PIP-15/NFR-P7) is the next milestone in the SRS, §11 — deliberately not started on
> this branch/PR; see the "PR #2 opened" session entry for why.

## Repository and branches

- Repository: `liphip/datahub` ("DataTurntable").
- Working branch: `claude/datahub-lakehouse-review-6tiaf5`. Push only there. `dev` gets changes through a PR.
- The last commit on the working branch merges `origin/dev` (commit `84b2b5f`) into the branch. It is marked **WIP**: the backend suite passes, and some follow-ups are listed below.
- **State:** the pull-input UI, blob removal and JWT fixes are committed in `3b7fcd7`. The
  follow-up work in "Session 2026-09-28" below is committed on top of it. Check
  `git log origin/claude/datahub-lakehouse-review-6tiaf5` to see whether it was pushed.
- **Working rule (owner, 2026-09-28):** keep this file current, and commit and push to the working
  branch regularly, so another tool can take over at any time. Pushing to the working branch needs
  no further confirmation. **PR #2 into `dev` is now open** (https://github.com/Liphip/datahub/pull/2)
  — the owner asked for it to be opened once the branch made sense, per the session entry below;
  further pushes to the working branch land on the open PR automatically.
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
- **DLQ:** **corrected — there is no retry API, only a read-only one.** `EventDlqResource` (platform-
  admin-only) has `GET /api/internal/v1/events/dlq` and `GET .../consumer` to list dead-lettered
  messages; there is no `POST .../retry` anywhere in the codebase (confirmed:
  `grep -rn "dlq.*retry" backend/src/main/kotlin` finds nothing) and no frontend page at all
  (confirmed: `grep -ri "dlq" frontend/src` finds nothing). The functional spec is accurate here
  (§4.9's implementation note: "requeueing from the UI is not built yet") — this bullet was the
  stale one, previously implying a retry endpoint existed. **A naive "retry" is actively unsafe,
  not just unbuilt:** `ConsumerDeadLetterService.record()` stores `payload` through
  `SensitiveDataRedactor` before persisting it — the stored copy has sensitive fields masked, so
  republishing it verbatim would resubmit masked/corrupted data as if it were real, and these
  entries are specifically messages that were unparseable or addressed a since-deleted pipeline, so
  replaying the same bytes mostly just fails the same way again. A correct retry design needs either
  the raw pre-redaction payload kept somewhere access-controlled, or to be scoped only to the
  "pipeline was deleted then recreated with the same id" case — real design work, not a quick
  endpoint; deliberately not started blind this session. `.agent-status` planned an end-to-end
  replay test through the broker once a real retry endpoint exists, and a Kafka-backed IT
  workflow exists (`.github/workflows/integration-tests-kafka.yml`) that **had two bugs (wrong JDK,
  nonexistent default test class) that would have failed its first-ever trigger — fixed, but still
  not actually run; see the newest session entry.**
- **OpenTelemetry:** spans do not carry the platform's correlation/run ids yet, and there is no
  collector to verify against (NFR-M7).
- **Graph schema migrations** (PIP-17): only version 1 exists; write the migration framework when a
  version 2 appears, not before.
- ~~**`ON CONFLICT` under JTA.**~~ **Verified done, nothing to do.** Audited `DataResource.kt`'s
  two upserts (readings, `data_table_registry`) against the pattern used in
  `ConnectorLeaseService`/`EventInboxService`/`PipelineDataService`: both already check the dialect
  upfront (`isH2(connection)` / `dbKind == "h2"`) before choosing `MERGE INTO`/manual
  select-then-update vs. `ON CONFLICT`, with a doc comment at `upsertTableRegistryEntry` explicitly
  citing the rollback-only trap this avoids. No caught-`SQLException`-inside-JTA pattern remains
  anywhere in this list.
- **Documentation gaps:**
  - ~~Per-connector/per-alert-channel retry semantics were undocumented near their config tables.~~
    **Partially fixed.** The retry/redelivery behavior itself was *already* fully documented — §4.9's
    guarantee table covers every connector transport and every alert channel type — it just wasn't
    cross-referenced from §7.2 (connector config fields) or §9.3 (alert channel config fields), so a
    reader at either table had no pointer to it. Added one cross-reference sentence under each table.
    **Still missing:** concrete worked JSON config examples per connector/channel type (the field-name
    tables exist; example payloads showing them filled in do not) — that part of the original gap is
    real and still open.
  - `scripts/generate-source-function-reference.ps1` only recognises one-line Kotlin/TypeScript
    declarations; extend it before introducing multi-line declaration styles.
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

## Session 2026-09-28 (continued): the 9 unexplained skips were dead code, not a test bug

An earlier session flagged `ModuleServiceTest` (4 tests) and `TenantServiceTest` (5 tests) as
"still unexplained" `@Disabled` skips. Investigated instead of leaving them as a permanent mystery.

**Finding: the services under test were almost entirely dead code.** `ModuleResource` and
`TenantResource` — like every other resource in this codebase — do their CRUD directly with
`EntityManager`, not through a service layer. `ModuleService` and its DTOs (`dto/ModuleDto.kt`:
`CodeModuleDto`, `CreateModuleRequest`, `ModuleVersionDto`, `CreateModuleVersionRequest`,
`TagVersionRequest`) had **zero callers anywhere** outside their own disabled test. `TenantService`
was live, but only one of its eight methods was ever called: `createDefaultRoles` (from
`TenantResource` and `AuthResource`'s first-run setup). Its other seven methods (`createTenant`,
`getTenant`, `getTenantTree`, `getTenantChildren`, `getTenantWithChildren`, `updateTenant`,
`deleteTenant`, `toDto`) and `dto/TenantDto.kt` (`TenantDto`, `CreateTenantRequest`,
`UpdateTenantRequest`) were dead too — including an injected `userService` field in `TenantService`
that was never once referenced in a method body. That injection was the only thing keeping
`UserService.kt` reachable from a live class; once removed, `UserService` itself (user CRUD,
`changePassword`) turned out to have **zero callers anywhere**, main or test — `AuthUserResource`
does its own user CRUD directly, same pattern. `dto/UserDto.kt` stays: it's genuinely still used
by `AuthDto.kt`, `AuthService.kt` and `JwtAuth.kt`, independent of the dead `UserService`.

**What changed, matching the owner's standing rule (no backward-compatibility code, dead code
removed rather than preserved):**
- Deleted: `service/ModuleService.kt`, `service/UserService.kt`, `dto/ModuleDto.kt`,
  `dto/TenantDto.kt`, `test/service/ModuleServiceTest.kt`.
- `service/TenantService.kt` trimmed to its one live method, `createDefaultRoles`, with the unused
  `userService` injection and `objectMapper` (only used by the removed methods) also removed.
- `test/service/TenantServiceTest.kt` rewritten, `@Disabled` removed: one real test that
  `createDefaultRoles` seeds Viewer (read-only, default), Editor (writes, not tenant-admin) and
  Tenant Admin (the full set, including `tenant:manage`) with the right, *distinct* ability sets —
  not just that three roles exist — and one real test of `Tenant.isDescendantOf`/`getAncestorIds`
  across three hierarchy levels, which never depended on `TenantService` at all (it tests entity
  methods directly) and had no coverage elsewhere.
- `docs/development/source-reference.md` updated (removed the four dead-file rows, added the
  missing `TenantDatabaseRoleService.kt` row the previous session's commit had omitted).
  `docs/development/source-function-reference.md` is generated (no `pwsh` here, same carried-over
  limitation as before) — hand-stripped only the now-nonexistent-file sections, since a dead link in
  a reference document is actively wrong, not just stale; did not attempt to hand-sync the
  surviving files' function lists.

**Verified:** compiles clean; full suite on H2 **348 tests, 0 failures, 0 errors, 20 skipped** (was
355/29: −9 dead-code tests removed [4 `ModuleServiceTest` + 5 old `TenantServiceTest`], +2 new
`TenantServiceTest` tests, both passing for real, not skipped). The skip count now exactly matches
the legitimately Postgres/Docker/Python-gated tests — **no more unexplained skips.**

**Checked, not assumed — and found a real, separate bug this way.** `ModuleService.createModule`
had an explicit duplicate-name check (`CodeModule.findByTenantAndName`) that `ModuleResource`'s own
create endpoint doesn't repeat in application code. `code_modules` does have a real
`UNIQUE (tenant_id, name, module_type)` constraint in the baseline migration, and
`TransientFailureMapper` is a genuinely global exception mapper (bare `@ServerExceptionMapper`
methods on a top-level class, not resource-scoped) that already maps SQLState `23505` to a clean
`409` everywhere — so the reasoning "the database and the global mapper cover it" looked right.
**It wasn't, on H2:** a regression test (`ModuleResourceTest.testCreateModuleRejectsDuplicateNameAndType`)
first returned `201` for the second, duplicate module — because `CodeModule`'s `@Table` annotation
never declared the constraint (only the migration did), so H2's schema-from-entities test database
never created it. This is the exact same class of bug fixed for `Connector` earlier in this
session (`connectors/Connector.kt`, `uniqueConstraints`) — it clearly wasn't a one-off, so it's
worth checking other entities backed by a migration-only unique constraint. Fixed by adding the
matching `uniqueConstraints` to `CodeModule`'s `@Table` annotation; the regression test now passes,
and `PostgresMigrationBehaviorTest` still passes (Hibernate `validate` mode doesn't fail on a
constraint the DB has and the entity didn't declare, so this mismatch was invisible to it — the
only thing that caught it was actually exercising the behavior). No other deleted method's
behavior was found to be missing: `TenantService.createTenant`'s dead code had no duplicate-name
check either, and its unused `userService` field was never called by any of its methods, so
nothing there was lost.

**Swept the whole codebase for the same class of bug.** If `CodeModule` had a migration-only
constraint invisible to H2, it clearly wasn't the only one: cross-checked every `UNIQUE (...)` in
`db/migration/V1__baseline.sql` (24 total) against the owning entity's `@Table`/`@Column`
declaration. Found and fixed 12 more entities with the identical gap — constraint in the
migration, never mirrored on the entity, so H2's schema-from-entities test database silently never
created it:

- `alert/AlertChannel.kt` — `(tenant_id, name)`
- `auth/ApiKey.kt` — `(tenant_id, name)`
- `connectors/BrokerCredential.kt` — `(tenant_id, name)` and `(tenant_id, username)` (two constraints)
- `connectors/ConnectorPipelineMap.kt` — `(connector_id, pipeline_id, node_id)`
- `connectors/ConnectorRevision.kt` — `(connector_id, revision)`
- `connectors/inbox/InboxPoller.kt` (`ConnectorInboxItem`) — `(connector_id, item_key)` — the
  exactly-once pull-connector ledger; this one is functionally significant, not just cosmetic
- `data/DataTableRegistryRecord.kt` — `(tenant_id, table_name)`
- `metadata/MetadataEntities.kt` — four entities in one file: `MetadataType`
  `(tenant_id, type_key)`, `MetadataIdentifier` `(tenant_id, scheme, id_value, valid_from)`,
  `MetadataAttributeVersion` `(object_id, valid_from)`, `MetadataRelation`
  `(from_object_id, relation, to_object_id, valid_from)`
- `module/ModelArtifact.kt` — `(module_id, name, version)` — this one was this codebase's own
  gap from when the entity was first written, not carried over from anywhere else
- `module/ModuleVersion.kt` — `(module_id, version)`
- `tenant/Membership.kt` — `(auth_user_id, tenant_id)`
- `tenant/Role.kt` — `(tenant_id, name)`

Checked and correctly left alone (already covered, no fix needed):
- `pipeline/Pipeline.kt` (`kafkaConsumerGroup`) and `auth/User.kt` (`email`) already use
  `@Column(unique = true)`, the single-column equivalent of a table-level constraint.
- `grafana/GrafanaSyncState.kt` already declares its `uniqueConstraints` correctly — missed on the
  first single-line grep because its `@Table(...)` is a multi-line block; verified directly before
  concluding it needed no change.
- `MetadataObject` (in `MetadataEntities.kt`) has no unique constraint in the migration, so none
  was added.

**Verified after the full sweep:** compiles clean; full H2 suite **349 tests, 0 failures, 0
errors, 20 skipped** (the one net new test is `ModuleResourceTest.testCreateModuleRejectsDuplicateNameAndType`
added above). Ran `PostgresMigrationBehaviorTest` a second time against a fresh real PostgreSQL 16
database (`constraint_sweep_test`) after all 13 entity changes: **1 test, 0 failures, 0 errors** —
Hibernate `validate` mode still accepts every entity's schema as matching the migration exactly,
confirming none of the 13 additions over- or under-declares what's actually in `V1__baseline.sql`.
Database dropped after the run.

**Follow-up: the sweep itself had a gap, and two more real bugs were behind it.** The 13-entity
sweep above only searched for `ALTER TABLE ... ADD CONSTRAINT ... UNIQUE`, because that's how every
table-level constraint in the migration is written — except two, which use
`CREATE UNIQUE INDEX ... ON <table> (...)` instead (same effect, different SQL). Both were missed
the first time and were the identical bug:
- `alert_history_delivery_key` (`alert_history.delivery_key`) — the outbox identity that keeps a
  repeated delivery attempt after a failover from enqueueing the same alert twice
  (`AlertDispatcher.enqueueInternal`'s own comment says as much). `AlertDispatcher` already does an
  application-level `SELECT ... WHERE deliveryKey = :k` before inserting, but that's a check-then-act
  race — the DB constraint is what actually closes it under concurrent replicas, and it wasn't there.
- `idx_module_versions_fingerprint` (`module_versions(module_id, content_fingerprint)`) — a second,
  independent uniqueness rule on top of the `(module_id, version)` one already fixed.

Fixed both (`alert/AlertHistory.kt`, `module/ModuleVersion.kt` now declares two `UniqueConstraint`s).
This time, rather than leave the other 13 untested per the note above, wrote direct-persistence
regression tests for all of them at once: `alert/EntityUniqueConstraintTest.kt`, three tests —
duplicate `delivery_key` rejected, `NULL delivery_key` allowed to repeat (standard SQL: `NULL` is
never equal to `NULL`, so the migration's nullable unique index permits any number of them — a
duplicate-insert test that didn't also check this would be a false negative waiting to happen), and
duplicate `content_fingerprint` rejected. These persist through `EntityManager` directly rather than
through `AlertDispatcher`/`ModuleResource`, so they prove the database constraint itself, independent
of whatever application-level check does or doesn't exist above it.

**Verified after this follow-up:** compiles clean; full H2 suite **352 tests, 0 failures, 0 errors,
20 skipped** (349 + 3 new `EntityUniqueConstraintTest` tests, all passing — confirmed in the run log
that both SQLState `23505` violations actually fire, not just that the test framework didn't
complain). Re-ran `PostgresMigrationBehaviorTest` against a third fresh PostgreSQL 16 database:
**1 test, 0 failures, 0 errors** — Hibernate `validate` mode still matches the migration exactly
with both new constraints declared. Grepped the full migration one more time for every `UNIQUE (`
occurrence (25 table-level `CONSTRAINT ... UNIQUE` + 2 `CREATE UNIQUE INDEX`, no other syntax used)
and cross-checked each against its entity — nothing else is missing now.

**Closed the coverage gap for all 15.** Rather than leave the other 11 fixed entities on just
`PostgresMigrationBehaviorTest`'s validate-mode pass (proven above not to catch this bug class),
added direct-persistence regression tests for every one of them, in the package each entity
already lives in:
- `auth/ApiKeyConstraintTest.kt` — `api_keys(tenant_id, name)`, plus a same-name-different-tenant
  positive case (the constraint must not be tenant-blind).
- `connectors/ConnectorEntityConstraintTest.kt` — `broker_credentials` (both of its two
  constraints, `(tenant_id, name)` and `(tenant_id, username)`), `connector_pipeline_map`,
  `connector_revisions`, and `connector_inbox_items` (the pull-connector exactly-once ledger —
  the one entity in the whole sweep where the missing constraint was a functional bug, not just
  defense in depth).
- `data/DataTableRegistryRecordConstraintTest.kt` — `data_table_registry(tenant_id, table_name)`.
- `metadata/MetadataEntityConstraintTest.kt` — all four metadata entities:
  `MetadataType`, `MetadataIdentifier`, `MetadataAttributeVersion`, `MetadataRelation`.
- `tenant/RoleMembershipConstraintTest.kt` — `roles(tenant_id, name)` (plus the same
  same-name-different-tenant positive case) and `memberships(auth_user_id, tenant_id)`.
- `alert/EntityUniqueConstraintTest.kt` gained two more `AlertChannel` tests alongside the
  `AlertHistory`/`ModuleVersion` ones already there.

Every test persists through `EntityManager` directly, not through the owning resource/service, so
each one proves the database constraint itself fires — independent of whatever (if any)
application-level check sits above it, closing exactly the gap that let the `CodeModule` bug and
the `alert_history`/`module_versions` follow-up bug both ship unnoticed. `CodeModule` itself is
still covered separately by `ModuleResourceTest.testCreateModuleRejectsDuplicateNameAndType`,
the one that goes through the real HTTP endpoint since that's where the original bug surfaced.

**Verified after adding all 17 new tests:** full H2 suite **369 tests, 0 failures, 0 errors, 20
skipped** (352 + 17). Every new test's log line was checked, not just its green checkmark: each
one logs a real SQLState `23505` unique-violation from H2 before the assertion catches it, so this
isn't 17 tests that pass by accident (e.g. a typo that never triggers the `assertThrows` body).
Re-ran `PostgresMigrationBehaviorTest` a fourth time against a fresh PostgreSQL 16 database: **1
test, 0 failures, 0 errors**. All 15 fixed entities now have a database-level regression test; the
sweep is complete — every `UNIQUE` in `V1__baseline.sql` is both declared on its entity and has a
test proving H2 enforces it the same way Postgres does.

## Session 2026-09-28 (continued): OPS-07 — hourly run-history aggregates

With the constraint sweep closed out, picked up the next well-scoped, real gap the SRS traceability
table already flagged: OPS-07 was "Partial" — `RetentionCleanupService` deleted `pipeline_runs`
rows past their retention window, but nothing kept an aggregate behind, so operational history
older than the retention window (default 14 days, though the shipped default is indefinite
retention until a deployment opts in) simply vanished rather than degrading to "counts per
pipeline and hour" as the requirement calls for. Chose this over starting M1 (micro-batch
execution, PIP-15/NFR-P7): the SRS calls PIP-15 "the largest technical risk of the MVP" — a
pipeline-execution architecture change (batched runs instead of one row per record) too large to
land safely in the remaining budget of a single session, whereas OPS-07 was a bounded, self-
contained addition.

**What changed:**
- `V1__baseline.sql`: new `pipeline_run_hourly_stats` table (`tenant_id`, `pipeline_id`,
  `hour_bucket`, `status`, `run_count`, `UNIQUE (pipeline_id, hour_bucket, status)`), placed after
  `tenants`/`pipelines` (both referenced) alongside the other feature-addition tables that use
  inline `REFERENCES`, matching that section's established style.
- `pipeline/PipelineRunHourlyStat.kt`: new entity, `findByPipeline(pipelineId, since)` finder.
- `service/RetentionCleanupService.kt`: `cleanupPipelineRuns` now aggregates the rows it's about to
  delete into `pipeline_run_hourly_stats` (grouped by pipeline/hour/status, hour-truncated) before
  deleting them, via an upsert that adds to any existing bucket's count rather than replacing it —
  a bucket can be added to across many cleanup runs as more of its hour ages past the cutoff.
  Runs with no pipeline (shouldn't happen in practice; the FK is nullable only at the schema level)
  are deleted without an aggregate row, since there's nothing to key one by.
- The aggregation query is JPQL (`PipelineRun` entity, path-navigated to `pipeline.tenant.id`), not
  native SQL: a first attempt used native SQL joining `pipeline_runs`/`pipelines` directly and hit
  H2 returning UUID columns as raw `byte[]` rather than `java.util.UUID` — a native-query-specific
  quirk JPQL doesn't have, since Hibernate's mapped UUID type applies either way. Caught immediately
  by the test, not shipped.
- The upsert itself *is* dialect-branched native SQL (`ON CONFLICT` doesn't exist on H2), following
  the exact pattern `DataResource.upsertTableRegistryEntry` already established for the identical
  problem — checked `dbKind` upfront rather than relying on a caught `SQLGrammarException`, which
  would leave the surrounding JTA transaction rollback-only even though the exception looks handled
  (see that method's comment for why).
- `api/PipelineResource.kt`: new `GET /runs/hourly-stats?tenantId=&pipelineId=&sinceHours=`,
  same auth/tenant-scoping pattern as the neighboring `/runs` and `/runs/summary` endpoints.
  No UI page for it yet — flagged below, not silently skipped.

**Verified:**
- `RetentionCleanupServiceTest` gained a third test: two `SUCCESS` runs and one `FAILED` run in the
  same hour are cleaned up into one row with `run_count=2` and one with `run_count=1`; a second
  batch of old `SUCCESS` runs cleaned up in a *later* call increments the existing row to 3 rather
  than inserting a second row for the same bucket (proves the upsert, not just the insert path).
- Full H2 suite: **370 tests, 0 failures, 0 errors, 20 skipped** (369 + 1).
- `PostgresMigrationBehaviorTest` against a fresh PostgreSQL 16 database: clean — the new table's
  entity mapping matches the migration exactly.
- The `ON CONFLICT` branch (the H2 test profile can't exercise it, since H2 has no `ON CONFLICT`)
  was verified separately: ran the literal upsert SQL twice by hand against a scratch PostgreSQL
  16 database (`psql`, not through the application) and confirmed exactly one row survives with
  `run_count` correctly summed (2 + 3 = 5), not two rows or a naive overwrite.

**Not done, flagged rather than left silent:** no UI page reads `/runs/hourly-stats` yet — this
closes OPS-07's backend/API obligation, but a system administrator has no page to actually look at
the aggregate trend. Whether that's a dashboard chart, a pipeline-detail tab, or something else is
a product decision, not an engineering one, so it's left for the next session/owner rather than
guessed at here. M1 (PIP-15/NFR-P7, micro-batch execution) remains the largest open item and the
SRS's own suggested next milestone.

## Session 2026-09-28 (continued): PR #2 opened into `dev`

The owner asked to keep going and to "create PR when it makes sense to move over to dev branch" —
a standing instruction that supersedes the earlier "ask the owner before opening the PR" rule
recorded throughout this document's history (that rule stood the entire session up to this point;
this entry is the first exception to it, made on an explicit new instruction, not a unilateral
decision).

**Why now:** the branch was 46 commits ahead of `origin/dev` and **0 commits behind** — no merge
conflicts possible — with the full H2 suite green (370/0/0/20) and every change since the last
`dev` merge individually verified (see the session entries above). Opening a PR doesn't merge
anything; it starts CI on GitHub and gives the owner/reviewers a page to read and comment on
without blocking further work, which can keep landing as new commits on the same PR.

**Opened:** https://github.com/Liphip/datahub/pull/2 (`claude/datahub-lakehouse-review-6tiaf5` →
`dev`), with a body summarizing the 46 commits by theme (exactly-once delivery, metadata,
fire-and-forget ingest, HA hardening, model artifacts, per-tenant DB roles, OPS-07, the platform/
dependency work, and this session's hardening pass) plus the explicit known-open-items list (M1
not started, OPS-07 has no UI yet, a few SRS items still `Partial`/`Open`) and a test-plan
checklist. Subscribed this session to the PR's activity (`subscribe_pr_activity`): CI failures and
review comments will now arrive as events and get worked per the standing PR-babysitting rules —
pushed fixes for anything in scope, a comment explaining anything that isn't.

**Not done as part of opening the PR:** did not touch CI configuration, did not pre-emptively fix
anything speculative — the plan is to react to whatever GitHub's own CI run and any human review
actually surface, not to guess at problems that may not exist. `NFR-M6`'s traceability status
("CI... not yet observed green on GitHub Actions itself") is exactly what this PR's own CI run
will finally settle either way.

## Session 2026-09-28 (continued): CI red on PR #2 — trivy-action supply-chain compromise

First CI run on PR #2 came back with one job red: "Build container images, scan for
vulnerabilities, generate SBOMs" failed at the action-resolution step, before any of this PR's
own code ran: `Unable to resolve action aquasecurity/trivy-action@0.29.0, unable to find version
0.29.0`.

**Ruled out first, per the standing rule:** `git diff origin/dev..HEAD -- .github/workflows/ci.yml`
showed this pin unchanged from `dev` — not something any of the 46 commits on this branch touched.
So this genuinely isn't "this PR's" failure in the sense of something the diff broke.

**But a fix exists, so it was ported in rather than left as someone else's problem.** Searched why
a previously-working pin stopped resolving and found this is not an ordinary "the tag moved"
flake: `aquasecurity/trivy-action@0.29.0` was one of **76 of 77 version tags** (covering releases
0.0.1–0.34.2) force-pushed by an attacker with compromised maintainer credentials to a
credential-stealing `entrypoint.sh` in a March 2026 supply-chain compromise
(GHSA-69fq-xp46-6x23) — the tag not resolving at all is presumably GitHub or the maintainers
pulling the compromised tags rather than leaving them live. The advisory's own remediation: pin to
`v0.35.0` or later (released after the compromise, protected by GitHub's immutable-releases
feature so it can't be tag-hijacked the same way) or, better, a full commit SHA rather than any
mutable tag.

**Fixed:** both `trivy-action` steps in `ci.yml` (backend and frontend image scans) now pin
`@v0.36.0` (the latest release at the time), with a comment explaining why, referencing the
advisory. **Did not** pin to a commit SHA as the advisory itself recommends as the stronger fix:
this session's GitHub access is scoped to `liphip/datahub` only, so there was no reliable,
independently-verifiable way to confirm a SHA for a third-party repository through the tools
available (a WebFetch-summarized hex string is not something to trust for a security-sensitive
pin without a second, authoritative source to check it against). Pinning to the named release tag
is a real improvement over the compromised range and is independently checkable by anyone with
normal GitHub access; upgrading to a SHA pin is a reasonable follow-up for whoever has broader
access, not a blocker.

**Worth the owner's attention independent of this PR:** this finding applies to `dev`'s current
`ci.yml` too, not just this PR's copy of it — `dev` has the same vulnerable pin today, unrelated
to whether this PR merges. Flagging this explicitly rather than assuming the PR fix silently
covers it.

**Not yet done:** this fix was pushed but its own CI run had not completed as of this writing —
the next session/wake should check PR #2's status rather than assume green.

**Update (same session, next wake):** a second, distinct check was also red: "Scan Maven and npm
dependencies for known vulnerabilities" (`org.owasp:dependency-check-maven`), failing with
`NvdApiException: Invalid API Key, length of 0`. Investigated and this one has **no code fix at
all**: the job needs the NVD CVE database, `ci.yml`'s own comment (lines 238–241, unchanged from
`dev`) already documents that it requires a `DC_NVD_API_KEY` repository secret that isn't set.
Confirmed this fails identically on a docs-only commit with zero code changes, ruling out anything
in this PR's diff. Posted one comment on the PR (per the standing rule) explaining both failures —
the trivy one now fixed, this one not fixable from a push — and what the owner needs to do: register
a free key at https://nvd.nist.gov/developers/request-an-api-key and add it as `DC_NVD_API_KEY`.
This also means `dev`'s own CI is presumably red on this same job right now, independent of this PR.

## Session 2026-09-28 (continued): PR #2 — CI red fixed and a bot's six review findings triaged

Two more CI failures and a Codex review arrived in the same wake, all on PR #2.

**CI: "Backend tests and cross-tenant regressions" failing.** Root cause:
`PostgresTestProfile.available()` (added in an earlier session on this branch, part of the
"CI runs the real PostgreSQL migration test on PostgreSQL 18" work) treated a present Docker
socket alone as enough to auto-enable `PostgresMigrationBehaviorTest`'s `@EnabledIf`. GitHub
Actions' `ubuntu-latest` runners have Docker running by default, so this was true unconditionally
there — even in this job, which never opted into the `postgres-it` profile.
`application-postgres-it.yaml` (which sets `db-kind: postgresql`) is only loaded when that profile
is actually active at Quarkus's test-augmentation time, which requires the CLI flag this class's
own doc comment documents; without it, the test tried to boot with a postgres-shaped profile
override but the default `%test` H2 URL, and failed before its body ran
(`Driver does not support the provided URL: jdbc:h2:mem:...`). This never showed up locally in
this session's own verification runs, which always passed the CLI flag explicitly, and this
sandbox has no Docker socket at all. Fixed `available()` to check for the actual opt-in signal
(`DATATURNTABLE_IT_DB_URL` or `-Dquarkus.test.profile=postgres-it`) instead of Docker's mere
presence. Verified both directions: full H2 suite unaffected (370/0/0/20 before this session's
other fixes below), and the CLI-flag/env-var path still passes against a fresh PostgreSQL 16.

**Codex (`chatgpt-codex-connector[bot]`) left six review comments, four P1 and two P2 — none
labeled nit/suggestion/style/minor/low/trivial/info, so none were treated as automatically
optional.** All six were verified against the actual code before any fix, not taken on faith; all
six turned out to be real:

1. **P1 — `TenantDatabaseRoleService.ensureRoleAndSchema` didn't roll back a failed provisioning
   attempt.** `PipelineDataService.execute()` sets `connection.autoCommit = false` before calling
   this method. A failed `CREATE ROLE` (the CREATEROLE-missing case the class's own fallback
   exists for) leaves that transaction aborted in PostgreSQL; the caught exception didn't restore
   it, so the very fallback this method exists to enable — continuing on the connection's own
   privileges — immediately failed too with `current transaction is aborted`, taking down every
   DB_LOAD/DB_SAVE operation, not just database-level isolation. Fixed: roll back (only on a
   non-autoCommit connection; rollback on an autoCommit one throws instead) before returning from
   the catch block.
2. **P1 — the same method cached `provisioned += tenantId` before its own transaction committed.**
   Since `provisioned` is a long-lived, in-memory, cross-request cache but the DDL was left part of
   the caller's own uncommitted transaction, a later failure anywhere in that same transaction (the
   DB_LOAD/DB_SAVE work this call is provisioning for) rolled the role/schema changes back while the
   cache kept claiming they existed — every later call for that tenant then skipped provisioning and
   tried `SET ROLE` for a role that was never actually created, broken until the process restarted.
   Fixed: commit the provisioning DDL on its own (again, only on non-autoCommit connections) before
   caching. Both fixes verified with two new regression tests in `TenantDatabaseRoleServiceTest`
   against a real PostgreSQL database, each confirmed to fail without its fix (the first with
   `current transaction is aborted`, the second because the schema didn't survive a deliberate
   rollback) and pass with it.
3. **P1 — machine-ingest records lost their pinned pipeline version.** `PipelineRunQueue.enqueue`
   (`persistRun = false`, the default/machine-ingest path) never touches the database — deliberate,
   it's what lets ingest keep accepting records during a database outage (NFR-R6) — so it can't
   stamp `_pipelineVersionId` into the Kafka message the way the `persistRun = true` path's
   `createRun` does. `PipelineLogWriter.claimRun`'s fallback then picked whichever version was
   *latest at claim time*, not the version active when the record was queued — if a pipeline was
   edited in between, PIP-16 ("editing a pipeline never changes the record of past runs") was
   violated for exactly the queue-wait window. Codex's own suggested fix (stamp the version at
   enqueue time) would have reintroduced a database dependency into the deliberately DB-free ingest
   path, so instead: reconstruct the correct version from the record's own `queuedAt` (already
   carried in the message) against each version's `createdAt`, entirely at claim time — no change to
   the enqueue path at all. New regression test in `PipelineRunQueueTest`: edits a pipeline (new
   `PipelineVersion`, later `createdAt`) after a record queues but before it's claimed, asserts the
   run still references the earlier version; confirmed failing without the fix (picked the new
   version) and passing with it.
4. **P1 — `IngestSpool.replay` under-checkpointed relative to how often it changed `pending`.** The
   durable `.done` progress marker was only written every 500 records, but `pending` (an in-memory
   counter `isEmpty()`/`scheduledReplay()` rely on) was decremented per record. A segment shorter
   than 500 that failed partway through (Kafka accepted a prefix, then refused again) would, on
   every retry, resend and re-decrement the records that already succeeded — for a genuinely
   fire-and-forget sender this is silent, permanent data loss: draining `pending` to zero from
   over-counting makes `isEmpty()` true while real records remain on disk, and `scheduledReplay()`
   never calls `replay()` again once that's true. Fixed: checkpoint every record, not every 500th.
   New regression test in `IngestSpoolTest` reproduced the exact mechanism without the fix —
   `pending` went to **-2** (visible in the shutdown log) and a "second attempt" resent all three
   records instead of only the one that never sent — and passes with it.
5. **P2 — `TenantService`'s default Tenant Admin role couldn't manage ingest API keys.**
   `ApiKeyResource` gates every operation on bare `secret:manage` with no alternate ability (unlike
   `SecretResource`, which also accepts `connector:write`/`alert:configure`). Tenant Admin didn't
   have it, so a delegated tenant administrator couldn't create the credential the authenticated
   ingest endpoints require without a platform superadmin granting it by hand first — blocking the
   ordinary tenant-level ingest setup flow. Fixed: added `secret:manage` to the role's ability list;
   `TenantServiceTest` gained an assertion for it.
6. **P2 — four call sites still checked pre-migration ability strings the default roles no longer
   grant.** `git diff origin/dev` on `TenantService.kt` showed `dev`'s own default roles carried both
   the old style (`read:connector`, `write:connector`, `manage:tenant`) and the new
   (`connector:read`, `connector:write`, `tenant:manage`) as redundant aliases; this branch's roles
   dropped the old style entirely at some earlier point, but `ConnectorResource.kt`'s lease/revision
   endpoints (3 call sites) and `AuthorizationResource.kt`'s `/authorization/explain` (1 call site)
   still checked the old strings verbatim — `hasAbility` does exact matching, no alias mapping — so
   a brand-new tenant's Tenant Admin got 403 on those specific endpoints (existing tenants with
   roles created before the drop were unaffected, since their stored ability strings didn't change).
   The rest of `ConnectorResource.kt` itself, plus `RoleResource.kt`/`TenantResource.kt`/
   `MembershipResource.kt`, already used only the new style throughout, confirming which side was
   the leftover: fixed the 4 call sites to match, rather than re-adding legacy aliases to the role
   template.

**Verified as a whole:** full H2 suite **374 tests, 0 failures, 0 errors, 22 skipped** (370 + 4 new
regression tests, all Postgres-gated ones among them correctly skipped on H2). The 7 Postgres-gated
tests touched by this batch (`PostgresMigrationBehaviorTest` + all 6 `TenantDatabaseRoleServiceTest`
tests) all pass against a fresh PostgreSQL 16 database.

**Not done:** did not scan the rest of the codebase for further stray old-style ability strings
beyond the 3 Codex actually flagged in `TenantService.kt`'s diff against `dev` — the grep after
fixing those 3 came back empty, so there's reasonable confidence none remain, but this wasn't an
exhaustive audit of every ability check in the codebase, only of the specific strings this
finding named.

## Session 2026-09-28 (continued): PR #2 — a Copilot review, eleven findings, all verified

The six Codex fixes above were pushed (`0c324c5`). Before that push's CI even finished, a
second bot — GitHub Copilot's own PR review — posted 11 open findings on the same PR (5 High, 5
Medium, 1 Low, per its own severity badges). None were labeled nit/suggestion/style/minor/low/
trivial/info by the bot itself, so all 11 were investigated the same way as Codex's: verified
against the actual code, `git diff origin/dev..HEAD` to confirm each file was actually touched by
this branch (all 11 were), then fixed or explained. All 11 turned out to need action — nine real
bugs/gaps to fix, one already fixed by this session's own earlier PostgresTestProfile change, one
now understood as a documentation gap rather than a code bug.

1. **High — plaintext secret values in audit metadata.** `SecretResource.create`/`rotate` passed
   `"value" to value` straight into `audit.record`'s metadata map, storing the plaintext secret in
   `audit_events` — directly contradicting the class's own doc comment ("plaintext is write-only").
   Removed `value` from both metadata maps; `name`/`version` still recorded.
2. **High — an MQTT connector lease could be released by a replica that never owned it.**
   `disconnectConnector`'s `releaseLease` default released the shared DB lease unconditionally, but
   `ConnectorResource`'s delete/deactivate callbacks call it directly (a fast path, bypassing
   `refreshConnector`'s `ownsIngestRuntime()` guard) from whatever replica the HTTP request landed
   on. In an HA deployment with control-api and ingest roles split across replica pools, a
   control-api-only replica releasing a lease it never held would let another ingest replica
   acquire a fresh fencing token while the true owner's client is still live and unaware its own
   token is stale — two replicas consuming the same connector concurrently. Traced `refreshAll`'s
   own reconcile loop (which already only runs `if (runtimeRoleConfig.ownsIngestRuntime())`) to
   confirm the owning replica's periodic reconciliation already disconnects+releases correctly on
   its own within `leaseReconcileIntervalSeconds` — so gating the release the same way loses
   nothing, it just stops a non-owning replica from interfering. New test file
   (`MqttConnectionManagerDisconnectTest`, plain instantiation + a Mockito mock of
   `ConnectorLeaseService`, no `@QuarkusTest`/broker needed) confirmed failing without the fix and
   passing with it.
3. **High — H2 test schema tried to re-create two Hibernate-managed tables.** `native-tables.sql`
   (loaded via `sql-load-script`) `CREATE TABLE`'d `connector_pipeline_map` and
   `connector_runtime_leases` "raw," on top of Hibernate's own `drop-and-create` schema generation
   — but both are `@Entity`-mapped now (confirmed by grep), so Hibernate already creates them; the
   load script's own header comment ("tables Hibernate's generated schema does not contain") is
   simply stale. This is the source of the `Table "connector_pipeline_map" already exists` /
   `Table "connector_runtime_leases" already exists` warnings that have appeared in **every single
   test run this entire session** — dismissed earlier as harmless log noise since they're caught
   and logged as warnings, not fatal exceptions (so Copilot's specific claim that this "prevents
   the H2 test application from starting" doesn't match what was actually observed all session —
   but the underlying redundant, obsolete configuration is real). Removed the `sql-load-script`
   line and deleted the now-pointless file. Verified: those two warning lines are gone from a full
   test run's output (`grep -c "already exists"` → 0, was previously dozens per run), and the
   suite is otherwise unaffected (374/0/0/22 before this batch's other additions).
4. **High (duplicate of Codex #6) — `AuthorizationResource.explain` still checked the dropped
   `manage:tenant` string.** Already fixed by the Codex-findings commit just before this one;
   nothing further to do — noted as resolved rather than silently skipped.
5. **Medium — machine-ingest can't validate `rate_of_change` alert rules the backend will reject.**
   `AlertRuleEvaluator` requires `delta >= 0` and a positive `intervalSeconds`
   (`positiveSeconds("intervalSeconds", 60)`); `alertRules.ts`'s `validateAlertRule` only checked
   that `delta` was present, so the editor could submit an invalid rule and surface a raw server
   error instead of the same local validation message every other rule type gets. Added both
   checks, matching the backend exactly (interval optional — it has a default — but must be
   positive if given).
6. **Medium — `PostgresTestProfile.available()`'s Docker check didn't verify reachability, just a
   socket path/env var's existence.** Already fixed by this session's own earlier change (the
   `available()` rewrite replaced the raw Docker-socket check with the actual opt-in signal
   entirely) — confirmed the current file no longer has the flagged code at all; nothing further
   to do.
7. **Low — a generated `tsc` build-info file was committed.** `frontend/tsconfig.tsbuildinfo` is
   `tsc -b`'s own incremental-build cache, not source. Removed it from the repo and added it to
   `.gitignore` alongside the other frontend build artifacts already ignored there.
8. **High — the dev Mosquitto broker's unauthenticated ports were published to every host
   interface, not just the host machine.** `mosquitto.conf`'s own TODO already documents that
   broker credentials aren't yet pushed to the broker and says to "keep the broker ports inside
   the host/cluster network" — but `docker-compose.dev.yml` published `1883`/`9001` as bare
   `"1883:1883"`/`"9001:9001"`, which Docker binds to `0.0.0.0` (every interface), not just
   localhost. Confirmed the backend reaches the broker over the compose network
   (`mqtt://mosquitto:1883`, not the published port) and the documented "external" URL is also
   `localhost:1883`, so binding the published ports to `127.0.0.1` only enforces what the TODO
   already claims, without breaking anything that actually depends on the wider publish.
9. **High — an immutable digest pin was replaced by a bare mutable tag.** `git diff origin/dev`
   showed `grafana/alloy` went from `v1.5.1@sha256:...` to a bare `v1.19.2` somewhere in this
   branch's earlier history (an image-version-bump commit that dropped the digest in the
   process) — the one monitoring-stack image in `docker/k8s/monitoring/` that had been digest-pinned,
   now the one that wasn't. Looked up the real digest for `v1.19.2` two independent ways (Docker
   Hub's public API, and the registry v2 manifest endpoint's `Docker-Content-Digest` response
   header with a fresh anonymous pull token) — both agreed
   (`sha256:b8ec653c44235fbe910879145dac3597d66b0aaecf60bcbbe82580767771a839`) — and pinned it,
   unlike the trivy-action tag earlier this session where no reliable verification path existed
   from this session's scoped access; here direct registry API access was available and used.
10. **High → reclassified as a documentation gap — MQTT broker's durable queue isn't backed up.**
    `backup.sh` only dumps the two PostgreSQL databases; the bundled broker's `persistence true`/
    `queue_qos0_messages true` config (`mosquitto-data` volume) can hold messages already
    acknowledged to a sender for a disconnected platform connector, and a total-loss restore from
    the dumps alone would lose those. `backup-restore.md` already documented the equivalent gap
    for Kafka and the ingest spool ("drain ingestion first") but never mentioned the broker's own
    queue. Rather than attempt an unverified volume-backup implementation (this session has no way
    to test a real disaster-recovery restore of a Mosquitto persistence store), documented the gap
    explicitly in both `backup-restore.md` (the existing paragraph, plus a new "Known gaps" entry)
    and as a runtime note `backup.sh` itself prints after every run — an honest limitation beats a
    backup script whose completeness is silently overstated.
11. **Medium — a call site validated `delta` sign for `threshold`/`time_window` but not
    `rate_of_change`.** Covered by finding 5 above; listed here only because Copilot's summary
    counted it as a separate item from a second angle on the same diff hunk — no separate action.

**Verified as a whole:** full H2 suite **378 tests, 0 failures, 0 errors, 22 skipped** (374 + 4 new
`MqttConnectionManagerDisconnectTest` tests). Frontend `tsc --noEmit` clean. Both changed YAML
files (`alloy.yaml`, `docker-compose.dev.yml`) parse cleanly with `yaml.safe_load`(`_all`) —
`kubectl`/`kustomize` aren't available in this sandbox to render the full overlay, so that's left
for CI's own "Kubernetes overlay rendering" job to confirm.

**Not done:** did not attempt to independently re-verify every OTHER third-party image pin in
`docker/k8s/monitoring/` for the same tag-vs-digest drift — only the one Copilot actually flagged.
Whether any of `grafana/grafana`, `prom/prometheus`, `grafana/loki`, the exporters, etc. have
similar unpinned-by-tag-bump history is unchecked; none of them appear to have ever been
digest-pinned in this branch's history (unlike Alloy), so there may be nothing to find, but this
wasn't confirmed either way.

## Session 2026-09-28 (continued): PR #2 — trivy fix confirmed working, catches a real CVE

After the trivy-action fix landed, its scan step actually ran for the first time (previously it
failed to even resolve the action) — and found a genuine `HIGH` finding: `libexpat 2.8.4-r0` in
the backend image (`eclipse-temurin:...-alpine-3.24` + `apk add docker-cli`'s own dependency
chain), with a fix already published (`2.8.5-r0`). Confirmed directly against Alpine's own v3.24
package index (`dl-cdn.alpinelinux.org`) that `expat 2.8.5-r0` is live in the stable repo — so an
`apk upgrade` at build time gets it. Fixed `backend/Dockerfile`: `apk upgrade --no-cache` before
`apk add --no-cache docker-cli`, so every already-installed package (not just what `docker-cli`
itself needs) gets the patched version available at build time, not just this one CVE. Could not
build+scan the image locally to verify end to end (no Docker daemon in this sandbox); verification
here is the package-index lookup plus the standard, well-understood `apk upgrade` idiom for this
exact class of problem — CI's own image-scan job is the actual confirmation, watch it on the next
push.

## Session 2026-09-28 (continued): PR #2 — libexpat fix missed the frontend image; all 17 review threads resolved

Two follow-ups after the previous entry:

1. **The container-scan job still failed after the `backend/Dockerfile` fix.** The failing image
   was `dataturntable/frontend`, not the backend — `docker/frontend.Dockerfile`'s production stage
   (`nginx:1.30.5-alpine3.24`) is a *separate* Alpine 3.24 base image with the same `libexpat
   2.8.4-r0` gap; the earlier fix only touched the backend's base image. Added the same `apk
   upgrade --no-cache` line to `docker/frontend.Dockerfile`'s production stage. Not yet confirmed
   green on CI as of this write-up — watch the next push's "Build container images..." job.
2. **Resolved all 17 review threads on GitHub** (6 Codex + 11 Copilot) via the GraphQL
   `resolveReviewThread` mutation — each already had a reply from this session citing its fixing
   commit, so this was pure bookkeeping (marking threads that were already substantively closed),
   not new analysis.

## Session 2026-09-28 (continued): PR #2 — the frontend libexpat fix confirmed green, CI is effectively done

Pushed commit `60899b8` (the `docker/frontend.Dockerfile` fix from the previous entry) and watched
the resulting run (`36448680758`) to completion via the PR subscription. Result: **"Build container
images, scan for vulnerabilities, generate SBOMs" now passes** — both images (backend and frontend)
are clean of the `libexpat` finding. Every other job passes too (backend tests, Postgres migration
smoke test, frontend typecheck/build, OpenAPI lint, Kubernetes overlay rendering), except the one
job that was never fixable from this branch: "Scan Maven and npm dependencies for known
vulnerabilities" still fails on the missing `DC_NVD_API_KEY` repository secret — already reported
on the PR in an earlier session entry, reproduced identically on a docs-only commit, and confirmed
to affect `dev` itself the same way. **No further CI work is pending from this session unless a new
failure appears or reviewers leave new comments** — the PR is watched and will keep receiving pushes
for anything new, but as of this write-up there is nothing actionable left.

## Session 2026-09-28 (continued): PR #2 merged; work continues directly on `dev`

PR #2 merged into `dev` shortly after the previous entry (GitHub `pull_request.closed`,
outcome `merged`; this session was auto-unsubscribed). The owner then explicitly asked to
**continue working directly on `dev`** rather than opening further PRs, and to keep committing and
documenting so another session can take over at any point. `claude/datahub-lakehouse-review-6tiaf5`
is retired — do not build on it; one docs-only commit landed there after the merge by accident
(recording the merge itself) and is harmless but orphaned, since that branch is no longer an
ancestor of anything.

Work done directly on `dev` since the merge, each its own commit and push:

1. **`frontend/package.json`/`package-lock.json`: pinned `monaco-editor` to `0.57.0`.**
   `npm audit` on the merged `dev` found 2 findings (1 low, 1 moderate) — four DOMPurify CVEs
   (GHSA-c2j3-45gr-mqc4, GHSA-cmwh-pvxp-8882, GHSA-vxr8-fq34-vvx9, GHSA-55q2-fjhq-7xh7, the last a
   moderate-severity XSS) via `dompurify@3.4.8`, bundled transitively by
   `monaco-editor@0.56.0` (pulled in by `@monaco-editor/react`). Checked `monaco-editor@0.57.0`'s
   own `package.json` dependencies before touching anything: it bundles `dompurify@3.4.15`, clear
   of all four advisories, and `0.57.0` still satisfies `@monaco-editor/react@4.7.0`'s peer range
   (`>= 0.25.0 < 1`). Added it as an explicit `package.json` dependency pinned to that exact
   version (`npm install monaco-editor@0.57.0 --save-exact`) so it's pulled directly rather than
   relying on hoisting. Verified: `npm audit` → 0 vulnerabilities, `tsc && vite build` clean, all
   38 `vitest` tests pass. Confirmed on GitHub's own side too: Dependabot's own PR making the
   identical bump (`dompurify`+`monaco-editor` together) ran CI green independently.
2. **Verified the `ON CONFLICT`/JTA open-work item is already done, retired it from the tracker.**
   Audited `DataResource.kt`'s two upserts (readings, `data_table_registry`) — both already check
   the dialect upfront (`isH2(connection)` / `dbKind == "h2"`) before choosing `MERGE INTO`/manual
   select-then-update vs. `ON CONFLICT`, matching `ConnectorLeaseService`/`EventInboxService`/
   `PipelineDataService`. No code change needed; just crossed the item off so it stops looking like
   open work.
3. **Found and fixed a real CI gap: direct pushes to `dev` never ran CI.** While investigating
   whether `dev`'s CI was green after the merge, discovered `ci.yml`'s `push` trigger was
   `branches: [main]` — and this repo has **no `main` branch** (`git branch -a` /
   `list_branches` show only `master`, protected, and `dev`). `pull_request` events (i.e. every PR,
   including #2) always ran CI correctly; a **direct push straight to `dev`**, like every commit in
   this very session, silently ran **no CI at all** until this fix. Changed the trigger to
   `branches: [dev]` (commit `2fb9be5`) and confirmed live: that exact push produced workflow run
   `36452073337` (event `push`, branch `dev`) — the trigger works now. This means my dependency and
   docs commits immediately before this fix (`b258dec`, `1d53fe9`) were never verified by CI; they
   were verified locally instead (`npm audit`, `tsc`, `vite build`, `vitest`, and — for the
   `DataResource.kt` audit — reading the code directly, no change made). Recommend treating this
   fix itself, and everything pushed after it, as the first `dev` commits with real CI coverage on
   direct push.

**Local test environment note:** this sandbox only has JDK 21 (`/usr/lib/jvm/java-21-openjdk-amd64`)
and Docker's client but no running daemon (`docker info` fails with "no such file or directory" on
the socket) — matching a limitation already documented earlier in this file. `mvn test` fails
immediately with `UnsupportedClassVersionError` (backend compiles to Java 25 class files). No
backend code was changed in this session's `dev` work, so this wasn't a blocker, but it means any
future backend change here still needs either a Windows JDK 25 host or CI itself to verify —
local `mvn test` will not work in this exact sandbox.

4. **Fixed two bugs in `integration-tests-kafka.yml` (the manual Kafka IT workflow) that would have
   made it fail immediately on its first-ever trigger.** This workflow has never been run on GitHub
   (per this doc's own earlier "Carried over" note). Reading it closely found: (a) `java-version:
   '17'`, while `backend/pom.xml`'s `maven.compiler.release` is `25` (matching `ci.yml`'s four other
   JDK setups) — a straight compile failure; (b) its default `-Dtest` pattern,
   `EventOutboxReplayIT`, **does not exist anywhere in the codebase** — confirmed by
   `find backend -iname "*Outbox*"` (nothing) — nor does `EventOutboxReplayE2EIT`, referenced only
   in a doc comment on `PipelineConsumerDlqIT` as the pattern it followed. The only real
   Kafka-gated (`KafkaTestResource`) integration test left in the suite is `PipelineConsumerDlqIT`;
   made it the new default (commit `4ea2ce9`). **Not yet verified by an actual run** — this session
   attempted `mcp__github__actions_run_trigger` (`run_workflow`) on `dev` and got `403 Resource not
   accessible by integration`; this session's GitHub access apparently can't dispatch
   `workflow_dispatch` runs. **Next session or the owner should trigger it manually** (Actions tab →
   "Kafka Integration Tests (manual)" → Run workflow, on `dev`) to get the first real confirmation
   this workflow has ever had.
5. **`ci.yml` had no `paths-ignore`, so the dev-push trigger fix (item 3) meant every docs-only
   commit was about to start running the entire matrix** — full backend test suite, container
   builds, image scans, SBOM generation, Kubernetes render, all ~7 jobs, ~5-10 minutes. Watched it
   happen directly: five docs-only commits in this session each queued a full run before this fix
   landed (commit `191e689`). Added `paths-ignore: ['**.md', 'docs/**']` to both `push` and
   `pull_request` triggers. A commit that touches both code and docs still runs everything —
   `paths-ignore` only skips when *every* changed file matches. **Caveat, not yet a problem:** `dev`
   has no branch protection / required status checks today, so a doc-only push or PR simply getting
   no CI status is harmless. If `dev` ever gets a required "CI" status check, revisit this —
   `paths-ignore` means a doc-only PR would never satisfy it and could get stuck pending forever
   (a well-known GitHub Actions gotcha).

## Session 2026-09-28 (continued): CI/CD work paused; implemented AUD-01's missing retention-cleanup audit event

Owner explicitly asked to pause further CI/CD tinkering and prioritize implementation and tests
instead, and to keep GitHub Actions usage bounded. Switched focus accordingly.

**Investigated implementing PIP-13's DLQ retry endpoint (`query_fn`'s stub, and `POST
.../dlq/{id}/retry`) as real feature work, and deliberately did NOT build either, for reasons worth
recording so a future session doesn't repeat the investigation or build something unsafe:**
- `query_fn` (injected into module sandboxes) would need a live callback channel to the platform
  mid-execution to answer an arbitrary runtime-determined query — this codebase has no such channel
  (the same constraint documented for `load_model_fn`'s version pinning, MOD-16) and modules run
  network-isolated by design (NFR-S3). Real implementation needs either a declared-upfront query
  shape (like DB_LOAD's `metadata()`) or a new callback mechanism — a design task, not a quick fix.
- A DLQ retry endpoint is actively unsafe to build naively: `ConsumerDeadLetterService.record()`
  redacts `payload` (via `SensitiveDataRedactor`) before persisting it, so republishing the stored
  copy would resubmit masked/corrupted data as if real — and these are messages that were
  unparseable or addressed a deleted pipeline in the first place, so replaying the same bytes
  mostly just fails identically anyway. **This also corrected two stale claims** that a retry
  endpoint already existed (`docs/development/handoff.md`'s own earlier "Carried over" bullet, and
  `requirements-specification.md`'s PIP-13 Appendix A entry) — both fixed in the previous commits
  this session.

**Implemented instead: AUD-01's "no deployment/backup/retention events" gap, for the retention
category.** `RetentionCleanupService.runCleanup()` now records one `retention.cleanup` audit event
(via the existing `AuditService`, `actorId = null`/`tenantId = null` — the same system-initiated
convention `AuthResource.login`'s `auth.login.failed` already uses) summarizing which categories
were actually cleaned up and how many rows each removed, but only when at least one category
deleted anything — matching the existing `results.filter { it.deleted > 0 }` logging convention in
`runScheduledCleanup()`, so a no-op run (everything retained indefinitely, or nothing past any
configured cutoff) writes nothing. Added two tests to `RetentionCleanupServiceTest`: one confirming
a cleanup that deletes rows writes exactly one audit event (system actor, metadata naming the
category), one confirming a cleanup that deletes nothing writes none. Both follow the existing
file's established patterns exactly (`QuarkusTransaction.requiringNew().run {}` around every DB
read/write, JPQL entity loads rather than native-query column reads for text/CLOB-typed columns —
`AuditServiceTest` already established that pattern for reading `AuditEvent.metadata` safely across
H2/Postgres). Verified at the time by `mvn compile`/`test-compile` only; since confirmed green by
CI and by a local run (see the 2026-11-16 entry).

**Deployment/backup audit events (the rest of AUD-01's gap) remain open** — `scripts/backup/backup.sh`
and `scripts/backup/restore.sh` are standalone shell scripts outside the JVM/`AuditService`
entirely; wiring them into the audit trail would need a different mechanism (e.g. the script POSTing
to an internal endpoint) and wasn't started this session.

## Session 2026-11-16: local tests work again; batch ingest, audit read side, DLQ view, next-run, ingest limits

**Start here if you take over.** The newest state of `dev` is this entry.

**Local verification is possible again.** The sandbox ships JDK 21 only, but a Temurin 25 JDK can be
downloaded (`https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse`,
unpacked to `/opt/jdk25`; containers are ephemeral, so repeat it). With `JAVA_HOME` pointing there
`mvn test` runs the whole backend suite: **388 tests, 0 failures, 22 skipped** (Docker/PostgreSQL
gated). The frontend: `npm ci`, `npx tsc --noEmit`, `npm test` (41 tests), `npm run lint` (0 errors).
For a browser pass without Docker: start the packaged PostgreSQL 16 (`pg_ctlcluster 16 main start`),
create `dataturntable` and `pipeline_data`, generate keys with `scripts/dev/generate-dev-keys.sh`,
run `java -Dquarkus.profile=dev -jar target/quarkus-app/quarkus-run.jar` with the JWT key and
pipeline-data variables set, and `npx vite`. Kafka is absent in that setup: the UI and management
API work, ingest answers from the spool, nothing is executed.

**The retention audit event of the previous entry is confirmed**: CI run `36454004368` on `a731453`
was green in every job except the known `DC_NVD_API_KEY` one.

**Correction to an alarm raised in chat, not in this file:** `WarmContainerPool.spawn` opens the
bridge network whenever requirements are present and never read `allow-network`. That looked like a
bypass of the hostile-by-default rule, but `spawn(requirements)` has one caller, and it runs only
after a successful ephemeral run — which cannot install requirements without a network under the
default. So no networked warm container exists in the default configuration. The pool now checks
`allow-network` itself (`needsNetwork`, covered by `DockerSandboxPolicyTest`) instead of depending
on that ordering. MOD-05 proper (network for the install only, never for the run) is still open and
needs the packages installed by a separate networked step into a volume the isolated container
mounts, in both `DockerService.runContainer` and `WarmContainerPool.spawn`.

**Built this session** (each with tests, all on `dev`):

| Requirement | What | Where |
|---|---|---|
| IMP-01 | Batch ingest `POST /api/ingest/v1/ingest/{pipelineId}/batch`: one run per record, all-or-resend, per-record identity `deduplicateBy` or `<Idempotency-Key>:<index>` | `IngestResource`, `IngestBatchTest` |
| IMP-14 | WebSocket session limit configurable (`…websocket.max-sessions-per-pipeline`); refusals counted in `dataturntable_ingest_rejected_total{reason}` | `WebSocketIngestResource`, `IngestRejections` |
| AUD-07, AUD-08 | Audit search/filter/paging and CSV/JSON export (audited); new ability `audit:read`; Tenant Admin template carries it | `AuditResource`, `AuditResourceTest` |
| AUD-07, PIP-13 (UI) | `/audit` page: filters, details, export; platform administrators get the installation-wide scope and a read-only dead-letter tab | `pages/Audit.tsx`, `Audit.test.tsx` |
| CON-13 | "Next poll/read" in the connector status, from the scheduler's own delay | `ConnectorPollingService.getPollingInfo` |
| NFR-S3 | Warm pool obeys `allow-network` (see above) | `WarmContainerPool.needsNetwork` |

The Audit page was exercised in a real browser against the local stack described above: login,
list, details, CSV download, dead-letter tab. Not exercised there: the installation-wide scope
switch (covered by the page test only) and a non-admin `audit:read` user (covered by
`AuditResourceTest` on the API side).

**Known limits of what was built — do not read "Done" into these:**
- Existing tenants' Tenant Admin roles do not gain `audit:read` retroactively; only newly created
  tenants get it. Superadmins are unaffected. There is no migration for this.
- The audit export is capped at 10,000 records and loads them in memory.
- The audit text search is a `LIKE` over action, target and metadata — fine for thousands of
  records, not indexed for millions.
- A batch shares the 1 MB request limit, so "1000 records" is only reachable with small records.
- Last-run/next-run state lives in the memory of the instance that owns the connector lease.
- The HTTP-layer `413` for an oversized body is not in the rejection counter (IMP-14 stays Partial).

**What "finish the implementation" still means.** Appendix A of the requirements specification is
the list. The large open blocks are unchanged and none of them fits one session or this sandbox:
the lakehouse (LAK-01–12, STR-03/04/05, EXP-05/06), identity (IAM-03–13, API-08 OIDC, TEN-08),
the rest of audit (AUD-02–06: snapshots, same-transaction writes, hash chain, notes; AUD-09),
operations from the UI and Helm (OPS-01–06, NFR-S1), throughput (PIP-15 micro-batches, MOD-03
Kubernetes workers, TEN-10 quotas, NFR-P1/P2 benchmark), and everything marked "not on a cluster".
Smaller items that can be built and tested here next: IAM-05 (immutable built-in roles, clone,
disable), MOD-16 (record which model version a run read), UI-20 (hard-coded editor labels),
AUD-06 notes, TEN-10 per-tenant ingest quota on top of `IngestRateLimiter`.

## Session 2026-11-16 (continued): CI repaired; role templates, audit chain and notes, tenant quota, model reads

**The push before this one (`2fa00ce`) was red in CI for two real reasons, both fixed:**
- `ApiKeyResourceTest` got `400` instead of `202`. Not a test problem: `PipelineDirectory` answered
  from a cached "inactive" entry for up to one refresh interval after a pipeline was activated, so
  ingest refused records — and a fire-and-forget sender does not resend. An inactive answer is now
  confirmed against the database before it is given (the accept path still never touches it).
  Regression test in `IngestBatchTest`, shown to fail without the fix.
- The image scan found eight HIGH CVEs published since September in jars the Quarkus 3.39.5 BOM
  manages (jackson 2.22.2, guava 33.6.0, lz4-java 1.11.1, snappy-java 1.1.10.8). Pinned to the
  fixed versions ahead of the BOM in `backend/pom.xml`; remove a pin when the platform catches up.
  **This will recur**: a scan that gates on newly published CVEs turns red without any code change.

**Built this session** (all with tests; backend suite 400 tests, 0 failures, 22 skipped):

| Requirement | What | Where |
|---|---|---|
| IAM-05 | Built-in roles marked, immutable, `POST /roles/{id}/clone`, `PATCH {isDisabled}`; UI buttons | `RoleResource`, `Role`, `V2__role_disabled.sql`, `RoleTemplateTest` |
| AUD-05 | Hash chain, `GET /audit/verify`, update refused by a PostgreSQL trigger; UI "Verify chain" | `AuditService`, `V3__audit_chain_and_notes.sql`, `AuditResourceTest` |
| AUD-06 | Immutable notes on records and components, ability `audit.note:write`; UI in the record details | `AuditResource`, `AuditNote` |
| TEN-10 | Per-tenant ingest quota, `429` + `Retry-After`, HTTP/batch/WebSocket | `IngestRateLimiter.tenantRetryAfter`, `PipelineDirectory.Entry.tenantIngestPerMinute` |
| MOD-16 | Which model version a run used | `ModelArtifactService.recordRead`, `DockerService.recordModelReads`, `V4__model_artifact_reads.sql` |

**Migrations V2–V4 were run against PostgreSQL 16 locally** with the CI invocation
(`PostgresMigrationBehaviorTest`, profile `postgres-it`): they apply, Hibernate `validate` passes,
and an `UPDATE` on `audit_events` is refused by the trigger. The shipped major version is 18; CI
runs the same test against it.

**Things to know before relying on these:**
- **Audit writes are now serialised installation-wide.** `AuditService.record` locks the chain head
  row until the caller's transaction ends. A long transaction that audits early holds every other
  audited transaction back. Today's callers audit at the end; keep it that way.
- **The chain proves less than "tamper-proof".** It catches a changed, removed or inserted record.
  It does not stop someone who can drop the trigger and recompute every later hash; that needs the
  head hash anchored outside the database (not built). Retention deleting the oldest records is not
  reported as a break.
- **`MOD-16` records what `load_model_fn` returned**, not what was preloaded. A module that reads
  the `MODEL_ARTIFACTS` environment variable directly bypasses the record.
- **Built-in roles: only tenants created from now on.** Existing tenants' Viewer/Editor/Tenant Admin
  stay ordinary editable roles and do not gain `audit:read` / `audit.note:write`; there is no
  migration, because those roles may have been customised.
- **Tenant quota is per ingest instance** (in-memory counter) and per minute.
- UI for role clone/disable and for audit verify/notes is covered by typecheck, lint and page tests;
  only the audit page of the previous entry was driven in a real browser.

**Still open, unchanged in size:** lakehouse (LAK), identity/OIDC and system-scope roles (IAM-03–13,
API-08, TEN-05/08), AUD-02–04 and AUD-09, operations from the UI and Helm (OPS, NFR-S1), throughput
(PIP-15, MOD-03, NFR-P1/P2), MOD-05 proper, MOD-07 `query_fn`, PIP-13 requeue, and everything marked
"not on a cluster". Next items that fit this sandbox: UI-17 Activity tab on component pages (the
notes API is ready for it), UI-20 hard-coded editor labels, AUD-03 actor/target snapshots, STR-07
per-tenant retention, API-04/05 management API keys.

## Session 2026-11-16 (third part): CI green again, actor snapshots, per-tenant retention

**CI on `4f19edb`:** backend tests (all 400, including the Docker-gated ones that do not run in the
sandbox) and the backend image scan passed. Two jobs were red: the known `DC_NVD_API_KEY` one, and
the **frontend image scan**, on three HIGH nginx CVEs fixed in 1.30.6 — the base image tag is bumped
to `nginx:1.30.6-alpine3.24` in `docker/frontend.Dockerfile`, `docker/docs.Dockerfile` and the dev
Compose file (`apk upgrade` cannot do this: nginx comes from nginx.org's own package pin).

**Built since the previous entry** (backend suite now 402 tests, 0 failures, 22 skipped; V5 migration
applied and validated on PostgreSQL 16):

| Requirement | What | Where |
|---|---|---|
| AUD-03 (partial) | Actor name snapshot, remote address, user agent and source on every audit record; in the hash, the search and the export | `AuditService`, `RequestIdFilter`, `V5__audit_actor_snapshot.sql` |
| STR-07 (partial) | `retentionPipelineRunsDays` / `retentionAlertHistoryDays` in a tenant's quota override the installation default | `RetentionCleanupService.tenantOverrides` |
| UI-20 (partial) | Pipeline editor step choices translated | `PipelineEditor.tsx` |
| AUD-05 hardening | The chain head lock waits at most 10 s, then the audited action fails instead of hanging | `AuditService.lockHead` |

**Know before relying on these:**
- The recorded address is what the HTTP layer reports. Behind a reverse proxy that is the proxy
  unless Quarkus proxy forwarding is enabled for it.
- The hash covers the AUD-03 fields only on records that have one of them; records written between
  V3 and V5 verify with the older field set. This is deliberate, see `AuditService.hashOf`.
- Per-tenant retention lives in the tenant's `quota` JSON next to `ingestRecordsPerMinute`; there is
  no validation of those keys on write and no UI — a typo in a key is silently "no override".


## Session 2026-11-16: desktop OIDC, tenant settings and component activity

Continued from the latest documented next items, prioritizing desktop testing without Kubernetes.
No deployment or environment publication was performed.

- Tenant edit dialogs now expose ingest/concurrency limits and per-tenant run/alert retention.
  Blank removes an override; the PATCH API retains its quota JSON-string contract and now rejects
  unknown keys, coercion, overflow and invalid ranges before changing any tenant state. The typed
  frontend serializes the quota correctly. Individual GET now returns the parsed tenant view.
- Added optional Quarkus OIDC code-flow login (disabled by default), PKCE, nonce and explicit
  audience validation. Accounts are linked by exact issuer/subject, never by matching email. Only
  platform administrators can link identities in Users; unlink revokes pending handoffs and local
  tokens. The V6 migration stores links and hashed, browser-bound, one-use 60-second handoffs.
  Quarkus OIDC's JWT producer overrides SmallRye JWT's injection by default; the explicit
  `AuthenticatedTokenProducer` keeps existing local authentication working alongside OIDC.
- Login has the optional provider button and handoff exchange. Vite's OIDC proxy and nginx's
  internal API preserve the public host/port for callback URIs. Compose forwards provider settings
  to control-api only. New UI strings are available in English and German.
- Tenant, connector and role edit dialogs have lazy Activity tabs, filtered to the exact tenant
  and component, with paged audit history and permanent notes. The API still enforces permissions.
- `scripts/dev/test-desktop.sh` and `docs/development/identity-desktop.md` provide a tested desktop
  smoke workflow using H2 and an in-process loopback OIDC provider; no Docker/Kubernetes required.

Validation: the broad backend run passed 395 tests (0 failures/errors, 7 skipped), excluding
`DockerServiceHostileModuleTest` and `ModelArtifactRoundTripTest`; these Docker module-execution
suites were deliberately not rerun after the earlier onboarding machine stopped responding while
running a hostile module. Subsequent targeted OIDC tests add invalid nonce/audience/issuer/signature
and expiry cases. The desktop script passed 35 backend and 47 frontend tests, typecheck, lint
(0 errors, 26 warnings), and production build. The final frontend suite additionally covers the
OIDC login UI and passes 50 tests. V6 applies and Hibernate validates on PostgreSQL 18;
the real-PostgreSQL handoff behavior check is included in `PostgresMigrationBehaviorTest`.

Limits remain explicit in Appendix A: this is provider login into the existing SPA bearer-token
model, not common HttpOnly component sessions or federated logout. Provider configuration is by
process settings, not the UI; JIT provisioning, claim-to-role mappings, time-bounded/system grants,
break-glass policy, SCIM and session administration remain open. Other component Activity tabs
remain open. The lakehouse/storage milestones, micro-batch execution and cluster/throughput
validation are still unimplemented; none was marked complete. The protocol flow is tested locally,
but no real external identity provider or manual browser UI pass was performed this session.

## Session 2026-11-16: desktop means a runnable deployment

The owner clarified that desktop testing means a test installation on Docker Desktop/Engine,
not an automated test script. `docker/compose.desktop.yml` now provides that installation:
one backend JVM in role `all`, metadata and pipeline PostgreSQL, Kafka, MQTT, restricted Python
containers, frontend, docs, Grafana and a loopback gateway. Initialization generates and preserves
keys/service passwords in named volumes. Only Docker and Compose are required on the desktop.
`compose.desktop-oidc.yml` adds a persistent local Keycloak provider, seeded client and evaluation
user. README now leads with deployment; `docs/operations/desktop-deployment.md` contains the full
workflow, first setup, optional identity links and lifecycle commands. The smoke script remains
an independent automated-check tool.

Actual deployment investigation found two issues fixed here: `.dockerignore` excluded the docs
image's required Markdown/docs sources, and real Keycloak access tokens differ from ID tokens.
Quarkus auto-enables access-token verification when JWTs are injected elsewhere; the code-flow
configuration now explicitly uses/verifies ID tokens for identity and ignores the unused provider
access token. The loopback fixture now returns a distinct opaque access token so this case is
covered. Desktop `.localhost` issuer aliases are allowed for HTTP; lookalike remote domains are
still refused. Keycloak's evaluation user includes required profile fields.

Validation is performed against an isolated `dataturntable-desktop` project, preserving the
pre-existing developer database. The cloud policy blocks `dl-cdn.alpinelinux.org`, so normal backend
and frontend source-image builds cannot complete here. To validate runtime behavior, temporary
cloud-only image tags package the locally built application and frontend/docs output; no TLS or
package-signature verification was disabled, and the normal committed Dockerfiles remain the
source-build path for desktops. Do not confuse this runtime validation with a successful build
of all default images in the restricted cloud network. No cloud draft was changed or published.

The deployment checks cover first-run setup, password login, API-key ingest through the gateway,
Kafka delivery, a restricted Python module transforming 21 to 42, metadata save, key-generation
idempotency, and stop/start persistence with the same account/pipeline/single idempotent run.
The real Keycloak flow passed in Chromium, including provider login, callback, SPA handoff and
an authenticated API request. Gateway response buffers were enlarged for the provider's encrypted
token-state cookies, and Docker DNS resolution follows replaced upstream containers during rebuilds.

A live DB_SAVE check then exposed PostgreSQL-only issues hidden by H2: repeating CREATE SCHEMA
under a restricted tenant role still requires database CREATE, the relational table default used
H2's CURRENT_TIMESTAMP() spelling, and relational queries always ordered/filtered by `time`
instead of `created_at`. Existing schemas are now detected before CREATE (without broadening
privileges), the timestamp spelling is portable, and relational reads use their own timestamp.
Real PostgreSQL migration/storage/handoff tests pass (3 tests), H2 data tests pass (11 tests), and
targeted OIDC/local-authentication checks pass (14 tests). The live deployment's Python → DB_SAVE →
pipeline PostgreSQL → query flow returns the transformed value 42. All running services are healthy. Switching to the base Compose deployment with Keycloak stopped
also passed Chromium password login and an authenticated API call; data and keys remained intact.

Normal source-image builds in this cloud are still blocked by Alpine's package-host policy; the
runtime checks use the documented temporary artifact images. Default desktop source builds must
be verified after that network prerequisite is available. No cloud configuration was published.

## Session 2026-11-17: restricted local login (IAM-11)

Implemented a startup-validated `LocalLoginPolicy`: default `all`, optional `break-glass-only`
with explicit existing account UUIDs. Invalid modes, malformed/short-form UUIDs and an empty
restricted allowlist fail initialization. `AuthService.login` checks the policy after verifying
the password, before updating login state or issuing a token. Rejections use the existing generic
401 and failed-login audit path. Verified external login remains independent and still requires
an active explicitly linked local account.

Desktop and developer Compose forward the two policy variables. Configuration reference and
desktop guide cover bootstrap, restart and recovery requirements. No migration is needed. The
policy affects new password logins; existing issued tokens are not revoked by a configuration
change, and there is no UI policy editor. Shared sessions, federated logout, runtime provider
management, time-bounded grants and lakehouse/storage remain open; do not mark the whole roadmap
complete. Targeted checks cover policy validation, password refusal/allowance, ordinary auth and
the complete OIDC protocol fixture. All 15 targeted tests passed with no skips; Compose
configuration and whitespace checks passed. The desktop smoke command now includes these policy
checks. No cloud configuration was changed or published.

## Session 2026-11-18: tenant membership validity

V7 adds nullable validity bounds and a database interval constraint to memberships. The API validates
bounds before assigning them, supports clearing via null, audits them and increments authorization
version on edits. User permission/tenant evaluation, primary membership and login contexts ignore
future/expired memberships; administrative history remains available. Authorization explanations
exclude inactive membership/role grants. Users has an English/German local-time validity editor. Bounded memberships queue external Grafana
organization-access removal rather than creating a grant that could outlive its bounds; shared
session enforcement remains necessary before bounded Grafana access can be enabled.
Tests cover half-open boundaries, invalid intervals, clearing, revocation, and natural expiry with
an already issued token and unchanged authorization version. System-wide grants, multiple schedules,
shared sessions/embedded component expiry and the other Appendix A gaps are still open. This is
not completion of the whole implementation. Prior uncommitted work and desktop deployment were
preserved. Validation: 17 targeted backend tests, 3 PostgreSQL migration/storage tests, and
51 frontend tests passed; typecheck, lint (26 warnings, no errors), build and whitespace checks
passed. No environment draft or publication was changed.

## Sources used

- BK6-22-300 Anlage 1: https://www.bundesnetzagentur.de/DE/Beschlusskammern/1_GZ/BK6-GZ/2022/BK6-22-300/Beschluss/BK6-22-300_Beschluss_Anlage1.pdf?__blob=publicationFile&v=1


## Session 2026-11-18: desktop deployment feedback

Fixed Alloy's invalid empty logfmt stage, parsed both pipeline and connector event formats, and
included all split backend roles in log enrichment and dashboard job selectors. Desktop Compose now
runs Prometheus, Loki, Alloy and a monitoring Grafana with persistent storage and non-root-readable
configuration prepared by init. Internal proxy exclusions prevent monitoring requests reaching an
outbound proxy. Both gateway configurations expose monitoring and tenant Grafana subpaths and route
/q/metrics and /q/health to APIs. Successful module stdout/stderr is redacted and mirrored into backend
logs, while persisted node logs remain available to the app. Connector activation now says
Enabled/Disabled, separate from actual broker status. Existing docs-context and PostgreSQL schema,
timestamp and relational-query fixes are included in the same source delivery.

Live desktop validation covered healthy services, real Grafana browser logins, provisioned dashboards,
Prometheus/Loki datasource health, actual metrics scraping, and module output in app logs and Loki.
The guide includes the requested complete teardown/rebuild workflow. Desktop exporter-specific
infrastructure graphs still require the full developer Compose exporter stack. Standard source-image
builds remain unverified in this cloud because its network policy blocks the Alpine package mirror;
runtime validation used temporary artifact images. No cloud environment configuration was published.

## Session 2026-11-19: MQTT credential editing regression

Traced `Invalid secret reference for 0` to ConnectorResource masking the entire secretRefs map as
`********`; the frontend then spread that string into numeric properties on edit. Valid reference
metadata is now returned intact, without exposing secret values. Create/update and runtime resolution
validate reference structure, IDs and positive integer versions. The editor refuses malformed stored
metadata unless credentials are re-entered to replace it; save errors now reach the form. Existing
corrupted references cannot recover the original IDs and require re-entered credentials or a clean
test deployment. MQTT diagnostics now include Paho reason codes and a bounded, redacted cause chain;
configuration-resolution errors also update the connector's lastError. Tests cover API create/read/
list/edit reference round trips, malformed writes, editor repair and nested network errors. The
external broker's TLS/port/credential requirements have not been established from the supplied logs.

The same feedback also exposed a separate module-config response mismatch: ModuleVersion stores
JSON text, but versionToMap returned that String while ModuleEditor expected an object and called
JSON.stringify again. Version responses now decode the stored root object (including old extra
encoding layers), new writes require an object, and both initial/history editor loads recognize
older JSON-text responses. Tests exercise full create/read/resave round trips, invalid root types,
legacy serialization and both editor load paths.

Validation: 53 targeted backend tests and all 58 frontend tests passed, including four module editor
round-trip/history cases. Frontend typecheck/build passed; lint has 26 existing warnings and no errors.

## Session 2026-11-19: errors from the production logs (MQTT pipeline p1)

Two real bugs explained the uploaded Loki logs; both are fixed with regression tests.

1. **"Connection refresh failed: null" after every connector/pipeline save.** The after-commit
   lambdas in `ConnectorResource`/`PipelineResource` read `mqttConnectionManager` etc. through the
   resource. The resource is `@Authenticated`; Quarkus intercepts the Kotlin property getters of its
   generated subclass too, and on the refresh thread (no security identity) they throw
   `UnauthorizedException` with a null message. So saving a pipeline never refreshed MQTT
   subscriptions; they only caught up at the periodic reconcile (logs: `subscriptions=0` → `1`).
   Fix: capture the beans in locals before `afterCommit.run { }`. `AfterCommit` now logs the
   exception class and stack trace, counts failures (`dataturntable.connector.refresh.failures`) and
   has `awaitIdle()` for tests. Rule: an after-commit action must never reach through an
   `@Authenticated` bean. Test: `AfterCommitRefreshTest` (fails with 5 failures without the fix).
2. **pip "Temporary failure in name resolution" for every message.** A module with requirements
   ran pip inside its own container, which runs `--network none` (MOD-05). New
   `ModuleDependencyCache`: a separate install container (network `dependency-install.network`,
   default `bridge`; non-root, read-only rootfs, cap-drop ALL; no module code/input/config/secrets;
   `--only-binary=:all:` unless `allow-source-builds`) installs into Docker volume
   `dt-pkgs-<sha256 prefix of normalised requirements>`; completion is an atomic rename to
   `/pkgs/site`. Module runs (`DockerService` and `WarmContainerPool`) mount it read-only at
   `/opt/dt-packages` with `PYTHONPATH=/opt/dt-packages/site`. This also fixes a latent warm-pool
   bug: packages were added only to the runner's `sys.path`, so the per-job child process could not
   import them. The first message after a requirements change waits for the install (pandas: ~12 s);
   an install failure fails the run with pip's stderr. Desktop/production hosts need the install
   network to reach PyPI or set `index-url`/`proxy`. Old volumes are not garbage-collected yet
   (`docker volume prune` or `docker volume rm dt-pkgs-…`). Kubernetes backend: not changed.
   Tests: `ModuleDependencyCacheTest` (argument policy) and Docker-gated
   `DockerServiceHostileModuleTest` (six importable while the module still has no network, volume
   reused, unknown package fails with pip's message; pandas opt-in via `DATATURNTABLE_TEST_PANDAS=1`).

Not bugs: `(no channel)` alert lines / no admin e-mail addresses (alerting unconfigured; the alerts
were caused by the failed runs above); Kafka `UNKNOWN_TOPIC_OR_PARTITION`/`TOPIC_ALREADY_EXISTS` at
startup (topic auto-creation race, transient); SmallRye startup notices.

Running Docker tests in this cloud: start `dockerd`, pull `mirror.gcr.io/library/python:3.14.7-slim-trixie`
and tag it (Docker Hub rate-limits), and build an image adding `/root/.ccr/ca-bundle.crt` as
`PIP_CERT`/`SSL_CERT_FILE` (TLS interception); run with `DATATURNTABLE_DOCKER_MODULE_BASE_IMAGE=<that image>`.

## Session 2026-11-19: desktop Compose stack removed

Owner does not use `docker/compose.desktop.yml`/`compose.desktop-oidc.yml` and tests locally with
`docker/docker-compose.dev.yml`. Removed both files, `docker/desktop/` (init, entrypoint, nginx)
and `docs/operations/desktop-deployment.md`; its walkthrough and the module-requirements note
moved to `docs/development/setup.md` ("Try the product"). `scripts/dev/test-desktop.sh` and
`identity-desktop.md` stay: they are Docker-free automated checks, unrelated to that stack.
Entries above that mention the desktop stack are historical.

## Session 2026-11-19: Keycloak in the developer Compose stack

`docker/docker-compose.dev.yml` now runs `quay.io/keycloak/keycloak:26.6.2` (`start-dev`, realm
imported from `docker/keycloak/dataturntable-realm.json`, loopback port 8180, network alias
`idp.localhost` so browser and backend see the same issuer). `control-api` defaults to OIDC enabled
against it (overridable with the same `DATATURNTABLE_OIDC_*` variables) and waits for it to be
healthy. Fixed dev credentials, documented in setup.md. Verified here: Keycloak healthy, discovery
from inside the Compose network returns issuer `http://idp.localhost:8180/realms/dataturntable`, and a
scripted authorization-code + PKCE login of `dev-user` redirects to
`http://localhost/api/internal/v1/auth/oidc/login` and exchanges for an ID token with the client
secret. Not verified here: the control-api container itself completing the login (image not built
in this cloud).

