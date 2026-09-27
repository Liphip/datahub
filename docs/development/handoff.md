# Handoff: DataTurntable review and merge (state 2026-09-27)

This document lets another assistant or developer continue the work without the chat history.

## Repository and branches

- Repository: `liphip/datahub` ("DataTurntable").
- Working branch: `claude/datahub-lakehouse-review-6tiaf5`. Push only there. `dev` gets changes through a PR.
- The last commit on the working branch merges `origin/dev` (commit `84b2b5f`) into the branch. It is marked **WIP**: the backend suite passes, and some follow-ups are listed below.
- Stack:
  - Backend: Quarkus 3.39 with Kotlin 2.4 on Java 25; SmallRye Reactive Messaging with Kafka.
  - Databases: PostgreSQL 16 (Flyway, one baseline `V1__baseline.sql`); tests use H2.
  - Frontend: React, Vite, Tailwind, i18n (en/de); tests with vitest.
- Tests:
  - Backend: `cd backend && mvn test -Dquarkus.http.test-port=0`. Docker-only tests are skipped without a Docker daemon.
  - Real-PostgreSQL migration and schema check: `DATATURNTABLE_IT_DB_URL=jdbc:postgresql://localhost:5432/<db> DATATURNTABLE_IT_DB_PASSWORD=... mvn test -Dtest=PostgresMigrationBehaviorTest`.
  - Frontend: `cd frontend && npx tsc --noEmit -p . && npx eslint src && npx vitest run`.

## Owner's requirements (standing)

**Product and deployment**
- A full-stack data hub or lakehouse for **structured** data only: measurements, readings, locations, metadata. Unstructured blob storage should be dropped.
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

**Documentation**
- Functional spec §4.7 and §4.9.
- SRS v2.1.
- `docs/development/requirements-review.md`: MVP status and measurements.

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

## Open work (in priority order)

1. **Finish the merge UI**
   - `frontend/src/pages/Connectors.tsx`: add types `FTP`, `SFTP`, `EMAIL`, and add them to `Connector['connectorType']` in `types/index.ts`.
   - Replace the FILE fields with directory inbox fields: `path`, `pattern`, `format`, `afterRead`, `minAgeSeconds`.
   - Add fields per type:
     - FTP: `host`, `port`, `tls`, `username`, `password`, `path`.
     - SFTP: `host`, `port`, `username`, `password`, `privateKey` (textarea), `passphrase`, `hostKeyFingerprint`, `path`.
     - IMAP: `host`, `port`, `tls`, `username`, `password`, `folder`, `content`, `pattern`, `afterRead`.
   - Route every password-type field through `withSecretRefs` when saving.
   - Enable polling for the inbox types.
   - Show the inbox ledger (`GET /connectors/{id}/inbox`, `POST …/inbox/{itemId}/retry`).
   - The backend rejects inline credentials ("Connector credentials must use secretRefs").
2. **Check and land the merge**
   - Run a live smoke test (backend jar with PostgreSQL, Kafka and Vite; Playwright is at `$(npm root -g)/playwright`). Then push and open a PR into `dev`, following its PR template if one exists.
   - Mention in the PR:
     - BCrypt instead of Argon2id; `dev`'s SRS asks for Argon2id, so Bouncy Castle is a possible follow-up.
     - Rule multi-channel actions from `dev` are not carried over.
     - The alert UI supports one channel per ALERT node.
3. **#20 Drop unstructured blob storage**
   - Remove `data_blobs`, the blob endpoints in `DataResource`, the Files tab in `Data.tsx`, the DataBlob types and API, and the `data.blob` config.
   - Update the SRS and functional spec:
     - A new metadata (MET) requirements group.
     - Fire-and-forget for all inputs.
     - Queue redundancy: Kafka RF3 and min ISR 2 plus the spool; Compose cannot be redundant.
     - Unstructured data dropped.
     - §14a as an example only.
4. **#19 §14a example walkthrough**
   - Documented flow: LoRaWAN, then SAP meter data, then internal locations, delivered to the metering system and the grid calculation.
   - Use the `energy` template and example pipelines; verify end to end.
5. **Known gaps**
   - MQTT QoS 0 and UDP cannot be acknowledged.
   - Warm containers keep network access after `pip install`.
   - FTPS needs certificates the JVM truststore accepts.
   - Retention of run rows limits how long idempotency lasts.
   - Measured throughput on one machine was ~306 runs/s. Micro-batching (PIP-15) is needed for the 1B/day target.

## Sources used

- BK6-22-300 Anlage 1: https://www.bundesnetzagentur.de/DE/Beschlusskammern/1_GZ/BK6-GZ/2022/BK6-22-300/Beschluss/BK6-22-300_Beschluss_Anlage1.pdf?__blob=publicationFile&v=1
