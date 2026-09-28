# Handoff: DataTurntable review and merge (state 2026-09-28)

This document lets another assistant or developer continue the work without the chat history.

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
  - Databases: PostgreSQL 16 (Flyway, one baseline `V1__baseline.sql`); tests use H2.
  - Frontend: React, Vite, Tailwind, i18n (en/de); tests with vitest.
- Tests:
  - Backend: `cd backend && mvn test -Dquarkus.http.test-port=0`. Docker-only tests are skipped without a Docker daemon.
  - **The project targets Java 25 (`backend/pom.xml`: `maven.compiler.release=25`), so a host Maven run is not representative** — this machine's `JAVA_HOME` is JDK 17 and PATH Java is 21. Use the Maven image instead: `docker run --rm -v "${PWD}\backend:/app" -v "${env:USERPROFILE}\.m2:/root/.m2" -w /app maven:3.9.16-eclipse-temurin-25 mvn --batch-mode test '-Dquarkus.http.test-port=0'`.
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

## In progress: documentation consolidation (owner request 2026-09-28)

Goal: every document tells the same story, with fewer files. Owner decisions:
- **The SRS (`docs/specification/requirements-specification.md`) is the main document and wins every
  conflict.** Requirements from `production-ready-spec.json` that still make sense move into it,
  expanding it.
- **FR-029 (split into domain microservices behind a gateway) is dropped.** The runtime roles
  (control-api, public-api, ingest-edge, pipeline-executor) are enough; record it as a rejected
  alternative.

Target structure:

| Kept file | Absorbs |
|---|---|
| `README.md` | Overview and quick start only; links to the rest |
| `docs/specification/requirements-specification.md` | Goals from `target-architecture.md` §1; still-valid FR/NFR items and rejected alternatives from `production-ready-spec.json`; the status column of `docs/operations/support-matrix.md`; findings and measurements of `docs/development/requirements-review.md` |
| `docs/specification/functional-specification.md` | Runtime topology and ownership from `docs/development/architecture.md`; design detail from `target-architecture.md` §2–6 |
| `docs/development/handoff.md` | The only progress log and next-steps list: `.agent-status/progress.md`, the still-relevant parts of `docs/production-ready-v1-status.md` (project policy, pitfalls), `docs/development/documentation-gaps.md` (open items), `docs/development/plan.md` (nothing current; obsolete) |
| Reference docs, unchanged in role | `setup.md`, `configuration-reference.md`, `source-reference.md`, `source-function-reference.md` (generated), `licenses.md`, `docs/api/README.md`, `docs/operations/backup-restore.md`, the user guides |

Conflicts found (fixed as the files are merged):
- Password hashing: the README, the support matrix, FR-003 and the v1 tracker say Argon2id; the code
  (`AuthService`, `BcryptUtil`) and the SRS (NFR-S2) say BCrypt.
- Swarm is still "optional" in FR-030 and "Experimental" in the support matrix, but was removed
  (SRS DEP-08).
- MinIO (spec JSON tech stack) is excluded by licence; Redis appears in `architecture.md`, the
  configuration reference, backup-restore and the user guides, but the code has no Redis (leases
  are in PostgreSQL).
- "Unauthenticated ingest" / "no API keys" / "no audit trail" / "committed `privateKey.pem`"
  (requirements review, documentation gaps, SRS Appendix A IMP-09): ingest API keys, `AuditService`
  and externalised JWT keys exist.
- At-least-once (plan, support matrix NFR-007) against exactly-once (SRS PIP-14).
- `plan.md` still describes client-side SHA-256 hashing, a Kafka topic per edge, Redis, Helm and
  Promtail/ELK.
- README: "1B+ readings/day" is stated as a fact, while the review measured roughly 300 runs/s per
  node and the target applies to multi-node Kubernetes only. "TimescaleDB-optimised" is also
  stated, but the baseline creates no hypertables.
- `docs-site/docs/developer/*.md` are older copies of `docs/development/*` that diverged (all five
  differ).
- `production-ready-spec.json` tech stack is stale (React 18, Kotlin 2.3, Quarkus 3.32, Python 3.11).

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
     (items 3 and 4 below) — none of them were visible from the unit tests. A browser-level
     Vite/Playwright pass is still outstanding (item 7).
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
5. **Update `docs/production-ready-spec.json`.** It is a static record of the original review and
   still describes blob services, a `BlobMetadata` entity, an S3/MinIO migration phase and blob
   acceptance criteria, so it now contradicts the rescoped product decision. Left alone because it
   is a large, largely historical artifact.
6. **#19 §14a example walkthrough** — the flow is now documented (functional spec §13.5, using the
   `energy` template). Still open: building the example pipelines and verifying the walkthrough end
   to end. (Verified this session that the template frames §14a as an example, not as product
   behavior; the pipelines themselves were not built.)
7. **Vite/Playwright UI smoke** — still not run. The frontend is verified by typecheck, 38 vitest
   tests, ESLint and a 370-module production build, and the API was exercised over HTTP against a
   real stack, but no browser-level check has been performed.
8. **Known gaps**
   - MQTT QoS 0 and UDP cannot be acknowledged.
   - Warm containers keep network access after `pip install`.
   - FTPS needs certificates the JVM truststore accepts.
   - Retention of run rows limits how long idempotency lasts.
   - Measured throughput on one machine was ~306 runs/s. Micro-batching (PIP-15) is needed for the 1B/day target.

## Sources used

- BK6-22-300 Anlage 1: https://www.bundesnetzagentur.de/DE/Beschlusskammern/1_GZ/BK6-GZ/2022/BK6-22-300/Beschluss/BK6-22-300_Beschluss_Anlage1.pdf?__blob=publicationFile&v=1
