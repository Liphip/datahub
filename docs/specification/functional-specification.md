# DataTurntable — Functional Specification

**Version:** 2.3
**Date:** 2026-09-28
**Status:** Draft

This document describes how DataTurntable works: the target behaviour specified in the [requirements specification](requirements-specification.md) v2.3 (requirement IDs in brackets). Version 2.3 adds training and applying machine-learning models as a pipeline use case (§5.6, §13.6; owner request 2026-09-28). The requirements specification is the authoritative document: it holds the goals, the requirements, the decisions against alternatives, and — in its Appendix A — the only implementation status. Where the current implementation differs substantially from the behaviour described here, a short *Implementation note* says so; it never replaces Appendix A.

---

## Table of Contents

1. [System Overview](#1-system-overview)
2. [Architecture](#2-architecture)
3. [Inbound Data (Import Layer)](#3-inbound-data-import-layer)
4. [Processing Pipeline Engine](#4-processing-pipeline-engine)
5. [Python Module System](#5-python-module-system)
6. [Outbound Data (Export Layer)](#6-outbound-data-export-layer)
7. [Connections and Connectors](#7-connections-and-connectors)
8. [Storage Layer and Lakehouse](#8-storage-layer-and-lakehouse)
9. [Alerting System](#9-alerting-system)
10. [Multi-Tenancy, Identity & Access](#10-multi-tenancy-identity--access)
11. [REST API](#11-rest-api)
12. [User Interface](#12-user-interface)
13. [Data Flows & Use Cases](#13-data-flows--use-cases)
14. [Technology Stack](#14-technology-stack)
15. [Module Contracts](#15-module-contracts)
16. [Security Model](#16-security-model)
17. [Deployment Topology](#17-deployment-topology)
18. [Audit Trail and Activity](#18-audit-trail-and-activity)
19. [System Administration](#19-system-administration)

---

## 1. System Overview

DataTurntable is a **self-contained data platform** for ingesting, transforming, routing, storing, analysing and alerting on measurement, time-series and structured data from IoT sensors, HTTP endpoints and any other data source. It combines a data hub (pipelines) with a lakehouse (open-format history, SQL). It is not an AI platform — it is a **data integration, processing and storage platform** optimised for high-throughput time-series and event-driven workloads.

Everything runs inside the installation: every component is bundled, deployed and managed by the platform, is FOSS or freeware, and is reached through one UI behind one login.

### 1.1 Core Concept

```text
External Systems (push data in)            DataTurntable                         External Systems (receive / query)
────────────────────────────────           ────────────────────────────────────  ─────────────────────────────────
REST POST → /api/ingest/v1/...   ────────► Import Layer                          ◄──── Grafana / SQL tools (query)
MQTT publish to topic            ────────► (REST, MQTT, WebSocket, UDP)          MQTT publish ─────────────────►
WebSocket frame                  ────────►                                       WebSocket push ───────────────►
UDP datagram                     ────────►                                       HTTP POST webhook ────────────►
                                                │ Kafka (partitioned by tenant/pipeline)
                                           Pipeline Engine (DAG, micro-batches)
                                           ┌──────────────────────────────────┐
                                           │  [IMPORT]                        │
                                           │      │                           │
                                           │  [PROCESSING] ──┬────────────────┤
                                           │                 │                │
                                           │  [PROCESSING] ──┤  [ALERT]       │
                                           │                 │                │
                                           │           [MERGE] (fan-in)       │
                                           │             ┌───┴───┐            │
                                           │         [EXPORT] [DB_SAVE]       │
                                           │         (fan-out)                │
                                           └──────────────────────────────────┘
                                                │
                     ┌──────────────────────────┴───────────────────────────┐
                Hot tier                                                Lakehouse
         TimescaleDB │ PostgreSQL                     Iceberg tables on bundled object storage
      (recent data, low latency)                  (bronze raw history, silver/gold, rollups, SQL)
```

### 1.2 API Paths

| Path | Purpose | Auth |
| ---- | ------- | ---- |
| `/api/internal/v1/` | Management API — tenants, users, roles, modules, pipelines, connectors, connections | Session (browser) or JWT / API key |
| `/api/v1/` | General API — data queries, alerts, monitoring, audit | Session or JWT / API key |
| `/api/ingest/v1/` | Ingestion API — external systems push data into pipelines | API key scoped to pipeline or connector |
| `/api/catalog/v1/` | Iceberg REST catalog for external SQL/analytics tools | JWT / API key |

### 1.3 Primary Use Cases

1. **IoT Sensor Pipeline**: MQTT measurements → PROCESSING (transform) → ALERT (threshold) + DB_SAVE (hot tier) + lakehouse table + EXPORT (MQTT dashboard)
2. **Data Visualisation**: measurements → PROCESSING (normalise) → lakehouse table with rollups → Grafana dashboards built by the department itself
3. **Batch ETL**: structured file upload (CSV/Parquet/JSON) → PROCESSING (parse/normalise) → table → EXPORT (report webhook)
4. **Multi-source Merge**: HTTP feed + MQTT stream → each parsed separately → MERGE (fan-in) → EXPORT
5. **Historical Analysis and Replay**: SQL over months of history in the SQL workspace; reprocess bronze data with a corrected pipeline version
6. **Train and Apply a Model**: a training pipeline fits a model from historical readings and saves it (MOD-15); an inference pipeline loads it and scores, classifies or forecasts each incoming record — entirely inside the platform's own module workers, with no separate ML-serving service (§5.6, §13.6)

---

## 2. Architecture

### 2.1 Component Overview

```text
┌───────────────────────────────────────────────────────────────────────────────────────┐
│                                DataTurntable Installation                              │
│                                                                                        │
│  Reverse proxy (common login for every component)                                      │
│  ┌──────────────┐  ┌──────────────────┐  ┌───────────────┐  ┌───────────────────────┐ │
│  │  Frontend    │  │  Backend roles   │  │  Module       │  │  Grafana (users) +     │ │
│  │  (React/TS)  │  │  (Quarkus/Kotlin)│  │  workers      │  │  Grafana (system)      │ │
│  └──────────────┘  └──────────────────┘  └───────────────┘  └───────────────────────┘ │
│                                                                                        │
│  Backend roles: control-api │ public-api │ ingest-edge │ pipeline-executor │ query     │
│                                                                                        │
│  ┌────────┐ ┌──────────────┐ ┌────────────┐ ┌────────────────┐ ┌───────────────────┐  │
│  │ Kafka  │ │ PostgreSQL / │ │ Object     │ │ MQTT broker    │ │ Prometheus, Loki, │  │
│  │        │ │ TimescaleDB  │ │ storage    │ │                │ │ Alloy             │  │
│  └────────┘ └──────────────┘ └────────────┘ └────────────────┘ └───────────────────┘  │
└───────────────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 Layers

| Layer | Component | Technology | Responsibility |
| ----- | --------- | ---------- | -------------- |
| Presentation | UI | React 19 + TypeScript + Tailwind CSS | Pipeline management, code editing, catalog, SQL, audit, system area; embeds Grafana |
| Access | Reverse proxy | nginx `auth_request` | One login for every component (IAM-09) |
| Management API | Backend (`control-api`, `public-api`) | Quarkus 3.x (Kotlin / Java 25) | REST APIs, auth, tenants, roles, config, audit |
| Ingestion | Backend (`ingest-edge`) | Quarkus (REST + MQTT + WS + UDP) | Protocol endpoints; route incoming data into Kafka |
| Messaging | Internal bus | Apache Kafka (KRaft) | Decouple ingestion and processing; exactly once for identified records (§4.9); replay |
| Execution | Backend (`pipeline-executor`) + module workers | Quarkus; Python 3.14 containers/pods | Run pipeline graphs; run user Python code in isolation |
| Query | Backend (`query`) | Embedded DuckDB, Iceberg | SQL over hot tier and lakehouse; Iceberg REST catalog |
| Storage | Databases, object storage | PostgreSQL, TimescaleDB, S3-compatible store | Metadata, hot tier, lakehouse |
| Observability | Monitoring stack | Prometheus, Loki, Alloy, Grafana | System metrics and logs (no tenant payloads) |

### 2.3 Internal Data Flow

```text
External system pushes to:
  REST   POST /api/ingest/v1/ingest/{pipelineId}          (API key)
  MQTT   topic subscribed through an INPUT connector
  WebSocket connection through an INPUT connector
          │
          ▼
Ingestion layer — authenticates, enriches (_pipelineId, _tenantId, _ingestedAt, _runId)
          │ publishes keyed by tenant/pipeline
          ▼
Kafka: dataturntable.pipelines.input  ──────────────►  Lake sink → bronze table (raw history)
          │
          ▼
Pipeline executor — resolves the DAG, processes micro-batches
          │
          ├─► IMPORT / DB_LOAD node ──► platform fetches via connector or hot tier, module parses
          ├─► PROCESSING node ───────► transforms data, feeds all downstream edges (fan-out)
          ├─► MERGE node ────────────► combines outputs of all upstream nodes (fan-in)
          ├─► ALERT node ────────────► evaluates conditions, alerts dispatched by the platform
          └─► EXPORT / DB_SAVE node ─► module shapes data, platform delivers via connector or writes table
```

*Implementation note:* today the executor consumes one topic with a single blocking consumer and runs one container call per node execution. Partitioned micro-batch processing with autoscaled workers (PIP-12) is the target.

---

## 3. Inbound Data (Import Layer)

### 3.1 Overview

The import layer sits at the boundary between the outside world and the pipeline engine. It has two operational modes:

**Passive (push to us):** The platform exposes protocol endpoints. External systems push data to DataTurntable. No modification to the external system is needed beyond pointing it at the endpoint.

**Active (we pull):** The platform fetches data from an external source through an INPUT connector (REST polling, file watch, FTP/SFTP, mailbox) and hands it to the IMPORT module for parsing.

**Fire-and-forget (every input):** A sender transmits a record and is done. It does not register for a response, does not poll for a result and needs no retry logic, because the senders this platform is built for — meters, PLCs, SCADA components, mail servers, FTP servers — cannot do any of that. A 2xx acknowledgement means the record is durably accepted (written to the bus, or to the local ingest spool if the bus refused it) and will be processed exactly once if it carries an identity (IMP-10, NFR-R9). It does **not** mean the pipeline has finished. This applies to the pushed protocols and to the pull inputs equally: a mailbox or FTP server only has to leave a file where it can be found.

**Pass-through IMPORT:** An IMPORT node without a module forwards each record as received. Connector deliveries are unwrapped to their payload and platform metadata (`_runId`, `_ingestedAt`, …) is removed. Structured measurements (JSON from devices, gateways or other systems) therefore reach built-in rules, sinks and modules without any code. A Python IMPORT module is only needed to parse or reshape other formats. New IMPORT nodes default to pass-through in the pipeline editor.

**Send-as-is EXPORT:** An EXPORT node without a module (and without "Save to metadata") delivers its input records unchanged to the node's OUTPUT connector, with the same `Idempotency-Key` (run and node) as module exports. Records that are already in the shape the receiver expects, such as enriched readings, therefore need no code on the way out.

### 3.2 Passive Inbound Endpoints

| Protocol | Endpoint / Address | Notes |
| -------- | ------------------ | ----- |
| REST (HTTP/HTTPS) | `POST /api/ingest/v1/ingest/{pipelineId}`; `POST /api/ingest/v1/ingest/connector/{connectorId}` | Any JSON body; batch form (array of records) for throughput |
| MQTT | Topics configured on an INPUT connector (bundled or external broker) | v3.1.1 and v5.0 |
| WebSocket | Server endpoint configured on an INPUT connector | Each frame is one message |
| UDP | Configurable port per connector (V2) | Raw datagrams; no acknowledgement is possible (IMP-10 exception) |

All passive endpoints authenticate the caller with an API key scoped to the pipeline or connector (IMP-09). Unauthenticated requests are rejected.

Ingest API keys are created on the API keys page (`/api-keys`, permission `secret:manage`): each key is granted exact pipelines and INPUT connectors, is shown once, stored as a SHA-256 digest, can expire and can be revoked. Keys are cached by digest so ingestion keeps working while the metadata database is unavailable (NFR-R6); a revocation takes effect at once on the instance that handled it and within 30 s on the others.

### 3.3 Active Pull (IMPORT Module)

The platform performs the external I/O through the connector (credentials never enter the container, CON-05) and passes the raw payload to the IMPORT module, which parses and validates it:

```python
class ImportModule:
    def parse(self, input_data: dict, connector_meta: dict = None) -> dict:
        ...
```

Polling is configured on the connector (CON-13).

#### 3.3.1 Pull input types

| Type | Source | Typical configuration |
| ---- | ------ | --------------------- |
| FILE | A directory watched by the platform | path, filename pattern, record format, post-read action (keep/move/delete), `minAge` |
| FTP | FTP or FTPS server | host, port, TLS mode (none/implicit/explicit), username, password, path, filename pattern, record format, post-read action, `minAge` |
| SFTP | SFTP server | host, port, username, password **or** private key (+ passphrase), host key fingerprint, path, pattern, format, post-read action, `minAge` |
| EMAIL | IMAP mailbox | host, port, TLS mode, username, password, folder, content selection (body/attachments), pattern, post-read action (keep/move/delete), `minAge` |
| REST | HTTP endpoint | URL, method, headers, response mapping |

#### 3.3.2 Inbox ledger

Every pull input keeps a per-connector ledger of the items it has seen, so an operator can see what arrived, why an item failed, and requeue it:

- `GET /api/internal/v1/connectors/{id}/inbox?limit=50` — recent items with their state (`SEEN`, `PROCESSED`, `FAILED`, …) and, for failures, the error.
- `POST /api/internal/v1/connectors/{id}/inbox/{itemId}/retry` — requeue a single failed item.

Because the ledger, not the source system, is the record of truth, a failed item can be retried without waiting for the next poll and without depending on the remote system remembering anything.

#### 3.3.3 Guarding against half-written items

`minAge` is the mechanism for a file or mail that is still being written by the remote system. An item whose age is below the threshold is not read yet and is picked up on a later poll. Combined with the post-read action (keep/move/delete for files, keep/move/delete for mails), a re-run can neither duplicate nor silently skip an item (CON-15, CON-16).

#### 3.3.4 No unstructured storage

A pulled file, mail attachment or FTP file is parsed into structured records at ingest time. The platform stores the records, not the payload: there is no blob store, no upload endpoint and no Files tab (STR-08, IMP-13). The source item remains where the remote system put it, and the ledger records that the platform has consumed it.

### 3.4 No Schema Enforcement

Inbound data is forwarded as-is into the pipeline and retained unmodified in the tenant's bronze table (LAK-03). No parsing, validation, or transformation is applied at the ingestion boundary. All format handling is the responsibility of the IMPORT module or the first PROCESSING module. For a pull input, the connector's own record format setting is what tells the platform how to read the item; the item's bytes themselves are never stored (STR-08).

---

## 4. Processing Pipeline Engine

### 4.1 Pipeline as a DAG

A pipeline is a directed acyclic graph (DAG) where:

- **Nodes** are module instances (each referencing a specific module version)
- **Edges** are directed data connections between nodes

| Node Type | Can have upstream nodes | Can have downstream nodes | Forwards data |
| --------- | ----------------------- | ------------------------- | ------------- |
| IMPORT / DB_LOAD | No (entry point) | Yes | Yes |
| PROCESSING | Yes (exactly 1) | Yes (1 or more) | Yes |
| MERGE | Yes (2 or more) | Yes (1 or more) | Yes |
| ALERT | Yes | No (terminal) | No |
| EXPORT / DB_SAVE | Yes | No (terminal) | No |

### 4.2 Fan-out

One node's output is routed to multiple downstream nodes simultaneously; each receives its own copy.

```text
[PROCESSING: Normalise]
         │
    ┌────┴────────────┐
    ▼                 ▼
[ALERT: Threshold]  [DB_SAVE: measurements]
```

### 4.3 Fan-in (MERGE)

A MERGE node receives the list of outputs of all its upstream nodes and combines them.

```text
[IMPORT: MQTT sensor A] ──► [PROCESSING: Parse A] ──┐
                                                      ├──► [MERGE: Combine] ──► [EXPORT: REST]
[IMPORT: HTTP feed B]   ──► [PROCESSING: Parse B] ──┘
```

### 4.4 Pipeline Definition (JSON)

The graph is stored in `pipelines.graph_definition`. IMPORT and EXPORT nodes reference a connector; ALERT nodes may name a default alert channel.

```json
{
  "nodes": [
    { "id": "n1", "type": "IMPORT",     "moduleVersionId": "uuid-import-v1",    "connectorId": "uuid-mqtt-in", "mqttTopic": "sensors/#" },
    { "id": "n2", "type": "PROCESSING", "moduleVersionId": "uuid-normalise-v1" },
    { "id": "n3", "type": "ALERT",      "moduleVersionId": "uuid-threshold-v1", "alertChannel": "uuid-ops-channel" },
    { "id": "n4", "type": "DB_SAVE",    "moduleVersionId": "uuid-save-v1" },
    { "id": "n5", "type": "EXPORT",     "moduleVersionId": "uuid-mqtt-v1",      "connectorId": "uuid-mqtt-out", "mqttTopic": "dashboard/temp" }
  ],
  "edges": [
    { "source": "n1", "target": "n2" },
    { "source": "n2", "target": "n3" },
    { "source": "n2", "target": "n4" },
    { "source": "n2", "target": "n5" }
  ]
}
```

### 4.5 Kafka Topics

| Topic | Purpose |
| ----- | ------- |
| `dataturntable.pipelines.input` | Ingestion → pipeline executor; keyed by pipeline (plus the optional `X-Partition-Key`, e.g. a device id) so that partitions scale processing while each key keeps its order (PIP-12) |
| `dataturntable.pipelines.input.dlq` | Records the executors could not process after retrying infrastructure failures (PIP-13) |
| `dataturntable.lake.commits` | Data-file metadata from lake writers to the table committer (§8.4) |

### 4.6 Error Handling

- A module error fails the node; the run is marked failed with the node's stdout/stderr in the run log. Instantiation errors are reported, never swallowed (MOD-13).
- Failed messages are routed to the DLQ topic, retained for 7 days (configurable), and can be requeued from the UI.
- Consecutive failures above a configurable threshold raise a platform alert and pause the pipeline node until it is resumed manually.

### 4.7 Deduplication

- **System duplicates** (redelivery after a failover, a client resending after a lost response): processed once, see §4.9.
- **User data duplicates** (the same measurement genuinely arriving twice without an identity): preserved. Records are only treated as the same when the sender says so — an `Idempotency-Key` header or the `deduplicateBy` fields of the IMPORT node (e.g. `device`, `ts`).

*Implementation note:* one dead-letter topic for all tenants; requeueing from the UI is not built yet. A module or pipeline error does not use the DLQ: the run is marked failed and keeps its input, and **Retry** in the run view replays that input.

### 4.8 Scaling

Ingestion pods and pipeline workers are stateless and scale automatically: ingestion by request rate, workers by Kafka consumer lag (e.g. KEDA). Workers are long-running and process micro-batches; the module contract already accepts DataFrames (MOD-08). Single-node deployments run one instance of each.

### 4.9 Delivery Guarantees and Failover

**Exactly once for identified records.** Strict exactly-once delivery is impossible between independent systems (an acknowledgement can always be lost after the work was done). The platform therefore combines at-least-once transport with idempotent processing: every record that carries an identity is processed, stored and alerted on exactly once, whatever fails and however often it is resent. Records without an identity are processed at least once; they can repeat only when a sender resends after a lost acknowledgement.

**Record identity.** `Idempotency-Key` header (HTTP ingest), `_idempotencyKey` (WebSocket ingest), or the IMPORT node's `deduplicateBy` fields (any source, including MQTT). The run id is derived from pipeline and key, so every copy of a record maps to the same run.

| Stage | Guarantee |
| ----- | --------- |
| HTTP / WebSocket ingest | The record is written to Kafka (`acks=all`, idempotent producer, 3 replicas, `min.insync.replicas=2` on Kubernetes HA) before the client gets `202` (WebSocket: `{"status":"accepted"}`). Ingest does not use PostgreSQL, so a database outage does not refuse data. Only when Kafka has lost its write quorum does the client get `503` + `Retry-After` (WebSocket: `"retry": true`). |
| MQTT connectors | Persistent session (`clean_session=false`, stable client id, QoS 1). The PUBACK is sent only after the record is in Kafka; on failure the connection is dropped and the broker redelivers. On Kubernetes HA the session queues are RabbitMQ quorum queues. When the ingest replica holding the connector lease dies, another replica takes the lease and resumes the session. A redelivered message is recognised through `deduplicateBy`. |
| Polling connectors | A poll whose records could not be queued is recorded as failed in the inbox ledger and fetched again on the next cycle, or retried immediately by an operator (`POST …/inbox/{itemId}/retry`). The connector's item ledger — not the remote system — is the record of what was consumed, so a retry cannot duplicate or skip an item. |
| Pull inputs (FILE/FTP/SFTP/EMAIL) | The remote system only has to leave an item where it can be found; the platform owns the transfer, the `minAge` guard and the post-read action. The sender is never asked to confirm anything (IMP-10). |
| Executors | The executor creates the run when it claims the record and records the Kafka partition and offset. Offsets are committed only after the run finished. A redelivered record of a finished run is skipped; a second copy of a run (resend) is skipped; the record of a run whose executor died is run again. Lost database connections (e.g. a PostgreSQL failover) are retried for up to 10 minutes (`dataturntable.pipeline.max-infra-retry`) while the partition waits; only then the record goes to the DLQ. |
| Built-in alert rules | Rule state lives in PostgreSQL with row locks and remembers the run that last changed it, so a run executed again does not change the state twice (§9.2). |
| Alerts | Transactional outbox: alerts are stored in the same transaction as the rule state or claim that raised them, keyed by run, node and record, and delivered afterwards. Failed deliveries are retried (10 s … 1 h, 12 attempts); an alert claimed by a replica that died while sending is delivered by another. Webhook and REST channels receive the alert id as `Idempotency-Key`. |
| DB_SAVE | Appended rows carry `_dt_record_key` (run, node, row) under a unique index; a run executed again does not add rows. Upsert mode is idempotent through its key columns. |
| REST exports | Sent with `Idempotency-Key` (run and node); transient errors are retried, a final failure fails the run (its input is kept for Retry). |
| Runs started from the UI | Created as PENDING right away. If the instance dies before Kafka acknowledged the run, it is marked failed as "never queued" after 30 minutes (`dataturntable.pipeline.max-pending-age`); should its record still arrive, it is processed normally. |

**Where exactly-once ends.** Channels and sinks outside the platform can see a repeat when the platform crashed after they accepted something but before it recorded that: an alert or REST export (they carry an `Idempotency-Key` receivers can use to drop it), e-mail and Slack messages, and MQTT, WebSocket, UDP and file exports (no key possible). The window is the time between the receiver's acknowledgement and the platform's commit — milliseconds.

**Queue redundancy (NFR-R7).** Fire-and-forget is only safe if an acknowledgement means "durably accepted". Two things provide that:

- **Replicated bus.** On the Kubernetes HA deployment Kafka runs with replication factor 3 and `min.insync.replicas=2`, so losing one broker node neither loses an acknowledged record nor costs the write quorum. Combined with `acks=all`, a `503` is only returned when the quorum itself is gone.
- **Ingest spool.** When the bus refuses a record, the ingest node writes it to a local, fsync'd spool first and only then answers. The spool drains into the bus when it is available again. On Kubernetes the ingest edge is a StatefulSet with a persistent volume claim for the spool, so the records survive the pod, and the node, being restarted.

**Compose is not redundant (NFR-R8).** The single-node Docker Compose reference stack runs one broker and one database. It cannot satisfy the failover or redundancy requirements, and it is documented as a development and evaluation topology that accepts losing in-flight data with the node. Only the Kubernetes deployment is offered as meeting NFR-R5 and NFR-R7.

**Verified** on a local 3-broker Kafka cluster with two application instances under continuous ingest (16 parallel clients, every record with an `Idempotency-Key`): `kill -9` of a broker and of an application instance — 6,000 records acknowledged, 6,000 runs, no duplicates, none lost; PostgreSQL stopped for 15 s — 16,000 of 16,000 accepted with no client error during the outage, all processed once after the database returned. Built-in rule alerts with the receiver down: retried and delivered once each when it came back.

---

## 5. Python Module System

### 5.1 Module Types

| Type | Role in pipeline | Forwards data | Fires alerts |
| ---- | ---------------- | ------------- | ------------ |
| `IMPORT` | Entry node — parses data fetched by the platform | Yes | No |
| `DB_LOAD` | Entry node — loads rows from a platform table | Yes | No |
| `PROCESSING` | Intermediate node — transforms/filters/enriches | Yes | No |
| `MERGE` | Intermediate node — combines several upstream outputs | Yes | No |
| `ALERT` | Terminal node — evaluates conditions | No | Yes |
| `EXPORT` | Terminal node — shapes data the platform delivers via a connector | No | No |
| `DB_SAVE` | Terminal node — prepares rows the platform writes to a table | No | No |

### 5.2 Module Lifecycle

```text
SAVED (source code and config stored as a new module version)
    │
    ▼
VALIDATED (syntax check, import test)
    │
    ▼
REFERENCED by pipeline nodes (specific version)
    │
    ▼
EXECUTED by workers (container per worker; code and config loaded per version)
    │
    ▼
UPDATED — a new version is picked up by the next execution; old versions stay auditable
```

### 5.3 Container Sandbox

| Property | Value |
| -------- | ----- |
| Base image | `python:3.14.x-slim` with pandas, numpy, scikit-learn pre-installed (configurable) |
| User | Non-root (UID 1000) |
| Filesystem | Read-only root; `/tmp` writable (tmpfs) |
| Network | None by default; explicit grant per module (MOD-05) |
| CPU limit | Configurable (default: 1 core) |
| Memory limit | Configurable (default: 512 MB) |
| Optional libs | torch, xgboost (per module requirements, installed from a platform wheel cache without network access for the module) |
| Inputs | `INPUT_DATA`, `MODULE_CONFIG`, `CONNECTOR_META` (non-sensitive) |
| Output | One `__RESULT__:<json>` line; all other stdout/stderr is the user's run log |

*Implementation note:* modules with pip requirements currently get bridge network access to reach PyPI, and the base image is plain `python:3.14.x-slim`.

### 5.4 System API Injected into Modules

```python
def alert_fn(severity='warning', message='', payload=None, title=None,
             channel=None, cooldown_seconds=None):
    """Raise an alert (ALERT modules). Alerts are returned with the node result
    and dispatched by the platform (see §9)."""

def query_fn(query: dict) -> list[dict]:
    """Tenant-scoped read query against platform tables (hot tier and lakehouse)."""

def save_model_fn(name: str, model) -> None:
    """Persist a fitted model as a new version under `name`, scoped to the tenant and the
    calling module (MOD-15). `model` is pickled (or the framework's own serializer, e.g.
    `torch.save`) by the platform; the module never touches a filesystem path."""

def load_model_fn(name: str, version: int | None = None):
    """Load the current (or a specific past) version of a model saved under `name` by this
    module in this tenant. Returns None if none has been saved yet."""
```

*Implementation note:* `query_fn` currently returns an empty result. `save_model_fn`/`load_model_fn`
are implemented (MOD-15, MOD-17); `load_model_fn(name, version=...)` with an explicit version
raises inside the module — only the version preloaded at the start of the execution is available
(MOD-16).

### 5.5 Module Code Storage

Module source code, requirements and config are stored in the `module_versions` PostgreSQL table. Each save creates a new version record. The source is editable in the Monaco-based UI code editor. File-system storage for GitOps workflows is a future option.

### 5.6 Model Artifacts (MOD-15 – MOD-16)

The platform runs machine learning the same way it runs any other transformation — as a PROCESSING
module — rather than through a dedicated model-serving component. What is missing from an ordinary
PROCESSING module for this is **persistence across independent executions**: a training run and
the many later inference runs are different container invocations, on different workers, possibly
weeks apart, and each starts from a fresh filesystem.

| Aspect | Design |
| ------ | ------ |
| What is stored | Whatever `save_model_fn` is given — a scikit-learn estimator, a PyTorch `state_dict`, or any other picklable/serializable Python object. The platform does not inspect or execute it. |
| Scope and key | `(tenant_id, module_id, name)`; a module can save several named artifacts (e.g. one per device class). |
| Versioning | Like `ModuleVersion` and `PipelineVersion`: saving under an existing name creates a new version, never overwrites. `load_model_fn` defaults to the latest; a run can pin an older version to reproduce a past inference exactly. |
| Storage | A `model_artifacts` table (tenant-scoped, encrypted at rest like secrets) holding the serialized bytes and metadata (name, version, size, byte-count, created-by run id, framework hint). Not the object store, and not reachable through any file/blob API (DAT-10). |
| Loading cost | *Design target:* loaded once per worker process and cached, not once per record. *Current implementation:* re-fetched from the database before every execution — correct and hot-swaps automatically (MOD-19, verified), but not yet cached per warm-container process (MOD-18 performance follow-up). |
| Why this is not STR-08 | STR-08 excludes storing a **user's unstructured payload** (a file someone uploads or sends). A model artifact is never that: it is state the platform itself creates from a module's own fit call and reads back only for that same module's own inference call. It has no independent existence a user could upload, download or browse. |
| `save_model_fn` scope | Only takes effect when `process()` returns a `dict` (the `_savedModels` marker is merged into it and stripped by the platform before the result reaches the next node). A module returning a list, scalar or `DataFrame` gets a clear warning in its run log instead of a silently lost model. |

Training and inference are ordinary pipelines, not a special node type:

```python
class ProcessingModule:                              # training pipeline
    def process(self, data, query_fn=None, save_model_fn=None):
        model = sklearn.ensemble.RandomForestRegressor().fit(data[["x1","x2"]], data["y"])
        save_model_fn("load-forecast", model)
        return {"trained_rows": len(data)}

class ProcessingModule:                               # inference pipeline
    _model = None
    def process(self, data, query_fn=None, load_model_fn=None):
        if self._model is None:
            self._model = load_model_fn("load-forecast")
        data["prediction"] = self._model.predict(data[["x1","x2"]])
        return data
```

---

## 6. Outbound Data (Export Layer)

### 6.1 Overview

EXPORT and DB_SAVE nodes are terminal nodes. The module shapes the data; the platform performs the delivery:

**Active push:** The platform delivers the shaped payload through an OUTPUT connector (REST, MQTT, WebSocket, UDP) or writes rows to a hot-tier or lakehouse table.

**Passive expose:** External systems query the platform: the data API (`/api/v1/data`), the Grafana data source (INT-01) and the Iceberg REST catalog (INT-07). All are permission-checked.

### 6.2 Built-in Outputs

| Output | Mode | Description |
| ------ | ---- | ----------- |
| Hot-tier table (DB_SAVE) | Push | Writes rows to a tenant table in TimescaleDB/PostgreSQL (append or upsert) |
| Lakehouse table | Push | Writes to a tenant Iceberg table via the lake sink |
| MQTT Publisher | Push | Publishes to a topic via an OUTPUT connector |
| REST Client | Push | Calls an external REST endpoint via an OUTPUT connector |
| WebSocket Push | Push | Pushes to connected WebSocket clients |
| UDP | Push | Sends datagrams (V2) |
| Data / SQL API, Grafana data source, Iceberg REST catalog | Expose | Permission-checked pull access |
| ThingsBoard Export | Push | Pushes telemetry to ThingsBoard via its MQTT API (V2) |

### 6.3 Custom EXPORT Module (Python)

```python
class ExportModule:
    def shape(self, data: dict, connector_meta: dict = None) -> dict:
        """Return the payload the platform delivers through the node's connector.
        Do not make network calls here."""
        return data
```

---

## 7. Connections and Connectors

### 7.1 Overview

External systems are reached through **connections**, owned by the system plane: system administrators create them (endpoint, credentials, broker settings), possibly on request of a tenant administrator, and grant them to tenants with a scope and an optional validity period (CON-09 – CON-11). Tenants build **connectors** on granted connections; pipeline nodes reference connectors. Connector secrets are encrypted at rest and never returned in plaintext, and never reach module containers (CON-05).

*Implementation note:* connectors are currently created directly by tenants with their own credentials; connections, grants and the request workflow are to be built.

### 7.2 Supported Connector Types

| Type | Direction | Typical Config Fields |
| ---- | --------- | --------------------- |
| `MQTT` | INPUT / OUTPUT | `broker`, `topic`, `clientId`, `brokerCredentialId`, `qos`, `tls` |
| `REST` | INPUT / OUTPUT | `baseUrl`, `path`, `method`, `headers`, `authType`, `apiKey`, polling interval |
| `WEBSOCKET` | INPUT / OUTPUT | `url`, `path`, `headers`, `tls` |
| `UDP` | INPUT / OUTPUT | `host`, `port`, `bindAddress` |
| `FILE` | INPUT | `path`, `pattern`, `format`, `afterRead` (keep/move/delete), `minAge` |
| `FTP` | INPUT | `host`, `port`, `tls` (none/implicit/explicit), `username`, `password`, `path`, `pattern`, `format`, `afterRead`, `minAge` |
| `SFTP` | INPUT | `host`, `port`, `username`, `password` or `privateKey` (+ `passphrase`), `hostKeyFingerprint`, `path`, `pattern`, `format`, `afterRead`, `minAge` |
| `EMAIL` | INPUT | `host`, `port`, `tls`, `username`, `password`, `folder`, `content` (body/attachments), `pattern`, `afterRead` (keep/move/delete), `minAge` |
| `CUSTOM` | INPUT / OUTPUT | User-defined key/value pairs |

Pull inputs are INPUT-only and expose a polling interval and an operator-visible last/next run (CON-13). Every credential field is stored as a secret reference; the backend rejects inline credentials, and leaving a secret field blank on edit keeps the stored reference (§7.5).

A system used in both directions is used through two connectors on the same connection (CON-02).

### 7.5 Connector Secrets

Sensitive fields (`password`, `privateKey`, `passphrase`, API keys) are never stored in the connector config. The UI writes them through the secrets API, which returns an opaque reference that is stored in the config; the plaintext is never returned by a read. On edit, an empty secret field means "keep the stored secret" rather than "clear it", so a user can change a host or a path without having to re-enter a password they cannot see. This is why the UI marks a field as stored instead of pre-filling it.

### 7.3 Connection and Connector Lifecycle

1. A tenant administrator requests a connection to a new external system in the UI (optional).
2. A system administrator creates the connection and grants it to the tenant (scope, validity).
3. Tenant users create connectors on the granted connection.
4. IMPORT/EXPORT nodes reference a connector by `connectorId`.
5. At execution time the platform resolves the connector config (including broker credentials) platform-side and performs the I/O.
6. Updating a connector takes effect on the next pipeline execution — no restart required.
7. Every step is audited (§18).

### 7.4 Bundled MQTT Broker

Single node: Mosquitto. Kubernetes HA: RabbitMQ with the MQTT plugins. Broker credentials managed by the platform are provisioned on the bundled broker so that anonymous access is disabled (CON-12).

*Implementation note:* the bundled Mosquitto currently accepts anonymous clients because credentials are not yet provisioned; the RabbitMQ cluster has anonymous access disabled.

---

## 8. Storage Layer and Lakehouse

### 8.1 Storage Backends

| Backend | Use Case | Bundled |
| ------- | -------- | ------- |
| PostgreSQL | Platform metadata, module source, roles, alert configuration and history, audit trail, lakehouse catalog | Yes |
| TimescaleDB | Hot tier: recent time-series data (hypertables, compression, continuous aggregates), low-latency lookups (`DB_LOAD`) | Yes |
| S3-compatible object storage (SeaweedFS by default; external S3 optional) | Lakehouse table files, batch files, backups | Yes |

**Structured data only (STR-08).** There is no blob store, no file upload endpoint and no binary retention tier. Everything the platform ingests is reduced to records — readings, locations, metadata — that live in the hot tier or a lakehouse table. A pulled file, a mail with attachments, a binary protocol frame: all are parsed at the ingest boundary and then only the parsed records are stored. This has three consequences that matter operationally: one copy of every value instead of a payload plus its parsed form, no second backup/restore path for a file tree, and no retention question for "how long do we keep the uploaded file". The source item stays where the remote system put it.

### 8.2 Data Schema (Core Tables — PostgreSQL)

```sql
-- Tenant hierarchy (strict tree: one parent per node, root has parent_id = NULL)
tenants(id, name, parent_id, settings, quota, created_at, updated_at)

-- Roles: built-in templates (tenant_id NULL, is_system) and installation-defined roles
roles(id, tenant_id, name, description, abilities, is_default, is_system, created_at)

-- Login identities (installation-wide)
users(id, email UNIQUE, password_hash, first_name, last_name, abilities, is_active, last_login_at, created_at)

-- Role assignments (max 1 per user per tenant); target model adds scope, valid_from,
-- valid_until, granted_by, reason (IAM-03, IAM-04)
memberships(id, auth_user_id, tenant_id, role_id, abilities, created_at,
            UNIQUE(auth_user_id, tenant_id))

-- Connectors (tenant-scoped) and broker credentials
connectors(id, tenant_id, name, connector_type, direction CHECK IN ('INPUT','OUTPUT'),
           config (encrypted), is_active, created_at, UNIQUE(tenant_id, name))
broker_credentials(id, tenant_id, name, username, password_encrypted, allowed_topics, is_active)

-- Code modules and versions
code_modules(id, tenant_id, name,
             module_type CHECK IN ('IMPORT','DB_LOAD','PROCESSING','MERGE','ALERT','EXPORT','DB_SAVE'), ...)
module_versions(id, module_id, version, version_tag, code, requirements, config, created_at, created_by)

-- Pipelines (graph_definition: nodes + edges)
pipelines(id, tenant_id, name, description, graph_definition, kafka_consumer_group, is_active, ...)
pipeline_runs(id, pipeline_id, status, trigger_type, started_at, completed_at, error_message, retry_of_run_id, ...)
pipeline_run_node_logs(id, run_id, node_id, node_type, module_name, success, stdout, stderr, duration_ms, ...)

-- Alerts
alert_channels(id, tenant_id, name, channel_type CHECK IN ('MQTT','WEBHOOK','REST','EMAIL','SLACK'),
               config, is_active, created_at)
alert_history(id, tenant_id, channel_id, pipeline_id, dedup_key, severity, title, message,
              payload, status, sent_at)

-- API keys (hashed), tenant data table registry, Grafana sync state,
-- connector runtime leases (multi-instance ownership)
api_keys(...), data_table_registry(...), grafana_sync_states(...), connector_runtime_leases(...)

-- Target additions: connections, connection_grants, connection_requests, audit_events, audit_notes
```

The schema is created by a single baseline Flyway migration; enum CHECK constraints mirror the Kotlin enums.

### 8.3 Hot Tier (TimescaleDB)

Pipeline data lives in one schema per tenant (`tenant_<uuid>`), accessed through a per-tenant database role so that the database itself enforces isolation (NFR-S8). DB_SAVE creates or extends tables from the first row or an explicit column model; DB_LOAD reads with column selection, equality filters, ordering and limits. Retention and compression policies are configurable per tenant (STR-07).

*Implementation note:* all tenants currently share one database role, and a module config can name any schema; per-tenant roles are planned.

### 8.4 Lakehouse

| Aspect | Design |
| ------ | ------ |
| Table format | Apache Iceberg, via `iceberg-core` inside the backend (LAK-01) |
| Catalog | Iceberg JDBC catalog in PostgreSQL, served by the backend as an Iceberg REST catalog with one namespace per tenant (LAK-02) |
| Writes | A lake-sink consumer group writes Parquet data files per Kafka partition and publishes their metadata to `dataturntable.lake.commits`; one committer per table (elected via the connector lease mechanism) commits every 30–60 s |
| Layers | Bronze: every ingested record unmodified (LAK-03). Silver/Gold: pipeline outputs (LAK-04) |
| Time-series layout | `tenant, series_id, tags, ts, value(s), quality, ingest_ts`; partitioned by `day(ts)` and a `series_id` bucket; sorted by `series_id, ts` (LAK-05) |
| Late data | Event-time partitioning; compaction re-sorts affected files |
| Rollups | 1 min / 1 h / 1 day (min, max, avg, count, last), maintained continuously (LAK-06) |
| Maintenance | Compaction, snapshot expiry, orphan-file cleanup scheduled by the platform (LAK-09) |
| Time travel | Query by snapshot id or timestamp (LAK-08) |
| Isolation | The catalog hands out only the caller's permitted namespaces and file locations (LAK-11) |

### 8.5 Data Query API and SQL Workspace

```text
GET  /api/v1/data/{tenantId}/query?table=...&limit=...      — simple table reads (hot tier)
POST /api/v1/sql                                             — SQL over hot tier and lakehouse (target)
GET  /api/v1/sql/jobs/{id}                                   — asynchronous large queries (target)
```

SQL is executed by an embedded DuckDB engine in the `query` backend role, reading Iceberg tables through the platform catalog. Interactive queries target recent data and rollups (NFR-P6); large scans run as asynchronous jobs. A distributed engine is added only if benchmarks require it (CON-C9).

---

## 9. Alerting System

### 9.1 ALERT Module as a First-Class Pipeline Node

An ALERT node is a terminal node in the pipeline DAG. It receives data from upstream nodes, evaluates conditions, and raises alerts. It does **not** transform or forward data to downstream nodes. A single PROCESSING output can feed both an ALERT node and an EXPORT node simultaneously (fan-out).

### 9.2 Built-in Rule Types

Built-in rules are ALERT node types configured in the UI without code (ALT-02). Stateful rules keep their state in the platform (last values, windows, last-seen times); absence is evaluated by a platform scheduler, not by incoming data.

| Type | Description | Config Parameters |
| ---- | ----------- | ----------------- |
| `threshold` | Fire when a field crosses a limit | `field`, `operator`, `value`, `severity` |
| `rate_of_change` | Fire when value changes by more than N per interval | `field`, `delta`, `intervalSeconds`, `severity` |
| `time_window` | Fire when an aggregated value in a window exceeds a limit | `field`, `aggregation`, `windowSeconds`, `operator`, `value` |
| `absence` | Fire when no data arrives within a timeout | `timeoutSeconds`, optional series key |
| `custom` | Fire based on Python `AlertModule` logic | module version, module config |

Rule state lives in the `alert_rule_state` table (one row per rule node and series), updated under a row lock in its own transaction; late or redelivered records do not move the state backwards. The absence check runs every 15 s on every executor replica and claims each silence with an atomic conditional update, so exactly one replica alerts. A silence is reported once; data arriving again re-arms the rule. Rules without a timestamp field use the arrival time.

### 9.3 Alert Channel Types

| Channel | Config (as stored by the UI) |
| ------- | ---------------------------- |
| MQTT | `connectorId` (an MQTT OUTPUT connector; credentials stay in the connector), optional `topic` |
| HTTP Webhook | `url`, optional `method` (POST/PUT/PATCH), optional `authToken` (Bearer) |
| REST | `baseUrl`, optional `path`, `method`, `authToken` |
| Email (SMTP) | `recipient` (comma-separated), optional `subjectTemplate` (`{severity}`, `{title}`); SMTP server configured once per installation |
| Slack | `webhookUrl`, optional `channel` |

Channel secrets are to be stored encrypted (ALT-12).

### 9.4 Alert Flow

```text
Data arrives at ALERT node (from upstream fan-out)
        │
        ▼
Condition evaluated (built-in rule or AlertModule.evaluate())
        │
   alert_fn(...) called?
        │ YES — alert returned with the node result
        ▼
Channel resolved: channel named in alert_fn → node default channel → first active tenant channel
        │
        ▼
Cooldown check: same alert (pipeline, node, title) sent to this channel within the window?
        │ NO  (window: cooldown_seconds of the call, else installation default 300 s; 0 disables)
        ▼
Record written to alert_history (PENDING) → delivered → status SENT or FAILED
        │
        ▼
Metrics counter incremented; failures visible in the UI (ALT-11)
```

### 9.5 Alert Example

```python
class AlertModule:
    def __init__(self, config=None):
        self.threshold = (config or {}).get('threshold', 80)   # module version config

    def evaluate(self, data, alert_fn=None, query_fn=None):
        if data['value'] > self.threshold:
            alert_fn(severity='critical', title='High temperature',
                     message=f"{data['value']} °C", payload=data, cooldown_seconds=300)
            return True
        return False
```

---

## 10. Multi-Tenancy, Identity & Access

### 10.1 Tenant Hierarchy

Tenants form a **strict tree**: each tenant has exactly one parent, except the single root tenant (parent = NULL). Resources belong to exactly one tenant node. Permissions granted at a parent node apply to all children.

```text
Installation (system scope — IT department)
└── Tenant: AcmeCorp (root)
    ├── Tenant: AcmeCorp/EuropeDiv
    │   ├── Tenant: Berlin
    │   │   ├── Workspace: FactoryFloor  ← pipelines, modules, data here
    │   │   └── Workspace: Logistics
    │   └── Tenant: Paris
    └── Tenant: AcmeCorp/AsiaDiv
        └── Tenant: Tokyo
```

### 10.2 Permission Model

Permissions are product-defined names of the form `resource:action`; each corresponds to an enforcement point (IAM-01). Current vocabulary:

| Permission | Description |
| ---------- | ----------- |
| `pipeline:read`, `pipeline:write`, `pipeline:execute` | View; create/update/enable/disable/delete; trigger runs |
| `module:read`, `module:write`, `module:delete` | Python modules and versions |
| `connector:read`, `connector:write`, `connector:delete` | Connectors |
| `data:read`, `data:write`, `data:export` | Query, write and export stored data |
| `alert:read`, `alert:configure` | Alert history; alert channels |
| `user:read`, `user:write`, `user:delete` | Users |
| `tenant:manage` | Child tenants, memberships, roles |
| `*` | All permissions (built-in Superadmin template) |

Planned additions: `system.config:read`, `system.config:write`, `system.deployment:write`, `system.monitoring:read`, `system.roles:manage`, `connection:*`, `audit:read`, `audit.note:write`, `module.network:grant`.

Roles, their permissions and assignments are defined per installation (IAM-02); built-in templates are immutable, inspectable, clonable and disableable (IAM-05 – IAM-07). Assignments have a scope (system or tenant subtree) and an optional validity period; permissions are evaluated on each request (IAM-04). Nobody can grant permissions they do not hold; the last system administrator cannot be removed (IAM-08).

### 10.3 Identity Model

- One `users` record per login identity, installation-wide (email or IdP subject).
- Access to a tenant requires a role assignment for that tenant or an ancestor, or at system scope.
- At most one membership per (user, tenant).
- No `is_superadmin` column; installation administration comes from system-scope assignments (TEN-05). System scope grants no implicit tenant-data access (TEN-08).

*Implementation note:* installation administration is an explicit `is_platform_admin` flag on the user, separate from root-tenant administration; system-scope role assignments that replace it are not built yet.

### 10.4 Authentication

**Browser users:** One login for all components (IAM-09). The platform issues an HttpOnly, Secure, SameSite session cookie with CSRF protection; the reverse proxy validates it on every request (`auth_request`) and forwards the identity to embedded components (Grafana auth proxy). Components are reachable only through the proxy.

**Identity providers:** The platform is an OIDC client for external identity providers configured at run time (issuer, client, scopes, claim → role-assignment mapping, just-in-time provisioning) (IAM-10). Local password login can be restricted to break-glass accounts (IAM-11). Logout is propagated (RP-initiated and back-channel) and sessions can be revoked (IAM-12).

**Local passwords:** Sent over TLS only; the server stores a BCrypt hash; minimum 8 characters (NFR-S2).

**Machine clients:** JWT Bearer tokens or API keys (hashed at rest, explicit ability set, optional expiry, plaintext shown once).

*Implementation note:* the frontend currently stores a JWT in `localStorage` and sends it as a Bearer header; components other than the API are not behind the login.

---

## 11. REST API

### 11.1 API Paths

| Path | Purpose | Auth |
| ---- | ------- | ---- |
| `/api/internal/v1/` | Management API | Session, JWT or API key |
| `/api/v1/` | Data, alerts, monitoring, audit | Session, JWT or API key |
| `/api/ingest/v1/` | Ingestion | API key |
| `/api/catalog/v1/` | Iceberg REST catalog | JWT or API key |

### 11.2 Management and General API Endpoint Overview

| Method | Path | Description |
| ------ | ---- | ----------- |
| POST | `/api/internal/v1/auth/login` | Authenticate |
| POST | `/api/internal/v1/auth/setup` | First run: create root tenant + first administrator |
| GET | `/api/internal/v1/auth/me` | Current user |
| GET/POST/PATCH/DELETE | `/api/internal/v1/tenants[/{id}]` | Tenants, children, lineage defaults |
| GET/POST/PATCH/DELETE | `/api/internal/v1/auth-users[/{id}]` | Users |
| GET/POST/PATCH/DELETE | `/api/internal/v1/tenants/{id}/memberships[/{mid}]` | Memberships |
| GET/POST/PATCH/DELETE | `/api/internal/v1/roles[/{id}]` | Roles and templates |
| GET/POST/PATCH/DELETE | `/api/internal/v1/connectors[/{id}]` | Connectors, test, usage, impact, broker credentials |
| GET/POST/DELETE | `/api/internal/v1/modules[/{id}]`, `/modules/{id}/versions` | Modules and versions |
| GET/POST/PATCH/DELETE | `/api/internal/v1/pipelines[/{id}]` | Pipelines |
| POST | `/api/internal/v1/pipelines/{id}/run` | Manually trigger a pipeline |
| GET | `/api/internal/v1/pipelines/{id}/runs`, run summaries, lineage | Run history |
| GET/POST/DELETE | `/api/v1/data/{tenantId}/query`, `/insert`, `/tables[/{table}]`, `/summary` | Stored data |
| GET/POST/PATCH/DELETE | `/api/v1/alerts/channels[/{id}]` | Alert channels |
| GET | `/api/v1/alerts/history` | Alert history (`?tenantId=&limit=`) |
| GET | `/api/v1/monitoring/...` | Pipeline and platform monitoring |
| GET | `/api/v1/audit`, `/api/v1/audit/{target}` | Audit trail (target) |
| POST | `/api/v1/audit/{eventId}/notes`, `/api/v1/audit/notes` | Notes (target) |
| GET | `/q/health`, `/q/metrics` | Health and Prometheus metrics |

### 11.3 Ingestion API

| Method | Path | Description |
| ------ | ---- | ----------- |
| POST | `/api/ingest/v1/ingest/{pipelineId}` | Push data into a pipeline (JSON object or array) |
| POST | `/api/ingest/v1/ingest/connector/{connectorId}` | Push data into all pipelines using an INPUT connector |

`202` means the record is stored durably; `503` with `Retry-After` means it is not and must be resent (§4.9). `Idempotency-Key` makes resends safe: the same key yields the same `runId` and one execution. The optional header `X-Partition-Key` spreads one pipeline's load over all executors while keeping the order per key.

### 11.4 Standard Response Envelope

```json
{
  "content": [],
  "totalElements": 100,
  "totalPages": 5,
  "number": 0,
  "size": 20
}
```

Error responses:

```json
{
  "error": "Pipeline with ID 'abc' not found in tenant 'xyz'"
}
```

### 11.5 Rate Limiting

Rate limiting is planned for V2 (API-09); the default will be 100 requests per minute per API key / user.

---

## 12. User Interface

### 12.1 Technology

- **Framework:** React 19 with TypeScript
- **Routing:** React Router 8
- **Build tool:** Vite 8
- **Styling:** Tailwind CSS 4
- **State management:** Zustand
- **HTTP client:** Axios
- **Graph editor:** React Flow (`@xyflow/react`)
- **Code editor:** Monaco Editor
- **Dashboards:** Grafana, embedded behind the common login (UI-16)

### 12.2 Areas and Routes

```text
/login                    — Login (local or identity provider)
/                         — Dashboard (pipeline status, throughput, recent alerts)
/pipelines, /pipelines/:id — Pipeline list; graph editor, runs, DLQ
/modules, /modules/:id     — Modules; code editor with templates, config and versions
/connectors                — Connectors, broker credentials, connection requests
/data                      — Data explorer and charts
/catalog                   — Data catalog (tables, schemas, snapshots, lineage)        [target]
/sql                       — SQL workspace                                             [target]
/dashboards                — Embedded Grafana (tenant dashboards)                      [target]
/alerts                    — Alert channels and history
/tenants, /users           — Tenants, members, role assignments
/audit                     — Audit trail search                                        [target]
/system                    — System area: health, upgrades, backups, connections,
                             role templates, identity providers, system monitoring    [target]
/docs                      — User and administrator documentation                      [target]
```

Every component's configuration page has an **Activity** tab with its audit trail and notes (UI-17).

### 12.3 Pipeline Builder

The pipeline builder is a graph editor (drag-and-drop nodes, connect handles). The side panel configures the selected node: label, module and module version (filtered by compatible types), connector and MQTT topic for IMPORT/EXPORT nodes, default alert channel for ALERT nodes. Runs can be triggered from the editor; the run log shows per-node output.

### 12.4 Module Code Editor

- Python syntax highlighting (Monaco)
- **Boilerplate template** pre-filled for the selected module type
- Module version config (JSON) passed to the module constructor
- Requirements list
- Validate, Save (creates a new version), version history

Example template for a new PROCESSING module:

```python
class ProcessingModule:
    def process(self, data: dict, query_fn=None):
        # Example: add a derived field
        # data["celsius"] = (data["fahrenheit"] - 32) * 5 / 9
        return data
```

### 12.5 Contextual Help & Guided Onboarding

- **Inline example values** on all form fields (e.g. `mqtt://broker:1883`, `sensors/#`)
- **Inline help text / tooltips** on non-obvious settings
- **Empty-state guidance** with a "Create your first X" call-to-action
- **Boilerplate code** in the module editor (§12.4)
- **Example connector configs** next to config forms
- **Consistent visual language**: colour-coded module type badges everywhere modules appear
- **Documentation inside the UI** (UI-19)

### 12.6 Dashboards (Grafana)

Users of every level build their own dashboards in the bundled Grafana, embedded in the UI (UI-16):

- One Grafana organisation per tenant separates dashboards; users with cross-tenant data roles are members of each relevant organisation.
- Data access goes through a bundled data source plugin that queries the platform's query API with the logged-in user's identity, so dashboards obey the same fine-grained, time-bounded permissions (INT-01).
- A second Grafana instance holds system monitoring (Prometheus, Loki), visible only to holders of `system.monitoring:read` (OPS-04).

*Implementation note:* tenant data sources currently connect with one shared database user separated only by `searchPath`, and Grafana users have separate passwords.

### 12.7 Data Visualisation (Built-in)

Intentionally minimal — for quick sanity checks:

- Line chart: metric vs. time, configurable range and aggregation
- Bar chart: aggregated values per tag/dimension
- Table view: raw data with pagination
- CSV / JSON export (audited)

---

## 13. Data Flows & Use Cases

### 13.1 MQTT → Transform → Alert + Store + Dashboard (Fan-out)

```text
1.  Sensor publishes to MQTT broker:
      topic: sensors/room-42/temperature
      payload: {"sensor":"room-42","value":85.3,"ts":1710600000}

2.  The INPUT connector's subscription receives the message; the record is retained in the bronze table.

3.  Message published to Kafka (keyed by tenant/pipeline).

4.  Pipeline executor resolves the DAG:
      n1 (IMPORT) → n2 (PROCESSING: normalise) → n3 (ALERT: threshold)
                                               → n4 (DB_SAVE: measurements)
                                               → n5 (EXPORT: MQTT dashboard)

5.  n2 normalises: 85.3 °F → 29.6 °C and feeds three downstream nodes (fan-out).

6.  n3 (ALERT): value > threshold → alert_fn(severity='critical', ...)
      → dispatched to the node's default channel (e.g. Slack), cooldown applied
      → written to alert_history

7.  n4 (DB_SAVE): row written to the tenant's hot-tier table; the lake sink appends it to the silver table.

8.  n5 (EXPORT): shaped value published to an MQTT topic for a live dashboard.
```

### 13.2 Two Streams Merged (Fan-in)

```text
1. HTTP POST /api/ingest/v1/ingest/pipeline-merge  →  {"source":"A","temp":22.1}
2. MQTT message on sensors/B/temp                  →  {"source":"B","temp":21.9}
3. Both → separate IMPORT nodes → separate PROCESSING (parse) nodes.
4. Both parse nodes feed a MERGE node (fan-in), which combines them.
5. DB_SAVE writes the merged result to a table.
```

### 13.3 Dashboards and SQL

```text
1.  Pipelines write to hot-tier and lakehouse tables; rollups are maintained continuously.
2.  A department user opens Dashboards, builds a Grafana panel on the platform data source.
3.  Grafana queries the platform query API with the user's identity; the platform checks
    data:read for the tenant and the table, reads rollups or recent data, returns series.
4.  An analyst opens the SQL workspace, queries a year of history as an asynchronous job,
    or reads a table as of last month's snapshot (time travel).
```

### 13.4 Metadata Enrichment

```text
1.  A metadata object exists for a device (type Device, attributes: serial, model, location,
    commissionedAt) and for a datapoint (type Datapoint, attributes: unit, min, max, precision).
2.  A reading arrives — pushed over MQTT, or pulled from a mailbox/FTP file (IMP-10).
3.  The pipeline's "Enrich with metadata" PROCESSING node looks up the matching objects by
    deviceId / datapoint key and attaches them to the record (MET-03).
4.  Downstream, an ALERT node can now compare the reading against the datapoint's max with
    the right unit, and a DB_SAVE node can store the enriched record.
5.  A "Save to metadata" EXPORT node can write the meter's current reading back onto the
    metadata object itself (MET-04).
```

Because metadata objects are as-of versioned (MET-05), re-running an old pipeline run enriches it with the metadata that was effective at that time, not with today's.

### 13.5 Worked Example: §14a (Example Only)

§14a EnWG (BK6-22-300) is used as a **worked example** of the platform. Nothing in the product is specific to it; the same building blocks serve any metering or grid-data use case. The complete example, including pipeline definitions that an automated test executes end to end, is in `docs/examples/energy-14a/`.

```text
1.  Master data, in any order:
      SAP (CSV via SFTP/FTP/IMAP inbox)  → meters, their metering location (MeLo) and market location (MaLo)
      Internal register                   → location and grid area per MaLo
      Device registry                     → which LoRaWAN device reads which meter, from when
    Each is a pipeline IMPORT → "Save to metadata" (MET-04), using the `energy` template.
2.  Readings: the LoRaWAN network server pushes readings (MQTT or HTTP, fire-and-forget).
    IMPORT deduplicates by devEUI + time.
3.  "Enrich with metadata" (MET-03) adds meter, MaLo, location and grid area, each as valid
    at the reading's time (a meter exchange changes the meter from its date on).
4.  Two "Send as is" EXPORTs deliver each enriched reading to the metering system and to the
    grid calculation, each with an Idempotency-Key.
5.  A reading from an unknown device fails its run, which keeps the input for Retry.
```

The point of the example is the shape, not the domain: many sources of different kinds, several of them unable to take part in a request/response protocol, converging on structured records that are enriched and delivered to more than one consumer.

The control process of §14a is outside the data hub: grid-state estimation, the control decision and the control channel to devices. See the example's README for the requirements it would have to meet.

### 13.6 Worked Example: Train and Apply a Load-Forecast Model

Two independent pipelines, connected only through a named model artifact — no ML server, no
separate deployment step for the model.

```text
Training pipeline (triggered nightly, or on demand):
1.  DB_LOAD reads the last 90 days of a device class's readings from the hot tier.
2.  PROCESSING fits a scikit-learn regressor on (hour-of-day, weekday, past load) → next-hour load,
    and calls save_model_fn("hourly-load-forecast", model). A new version is created; the previous
    version stays available.

Inference pipeline (one instance per incoming reading, running continuously):
1.  IMPORT receives a reading (MQTT or pull input).
2.  PROCESSING calls load_model_fn("hourly-load-forecast") once per worker (cached after that),
    predicts the next hour's load, and adds it to the record.
3.  ALERT compares the prediction against the metered capacity and fires if it would be exceeded.
4.  DB_SAVE stores the enriched record for the dashboard.
```

If training is re-run with more data, the next inference call to `load_model_fn` on each worker
picks up the new version automatically (MOD-19); nothing about the inference pipeline changes.

---

## 14. Technology Stack

All components are FOSS or freeware (NFR-L1); see `docs/development/licenses.md`.

| Component | Technology | Version | Notes |
| --------- | ---------- | ------- | ----- |
| Backend | Quarkus (Kotlin) | 3.39 / Kotlin 2.4 | Java 25 runtime |
| Internal bus | Apache Kafka | 4.3 | KRaft mode; Strimzi on Kubernetes HA |
| Pipeline engine | Quarkus Kafka consumers + module workers | — | Part of backend |
| UI | React + TypeScript + Vite + Tailwind | 19 / 6.0 / 8 / 4 | React Router 8, Zustand, Axios, React Flow, Monaco |
| Module runtime | Python | 3.14 | `python:3.14.x-slim` based image |
| Hot tier | TimescaleDB | 2.30 on PostgreSQL 18 | TSL community features (freeware) |
| Metadata | PostgreSQL | 18 | CloudNativePG on Kubernetes HA |
| Lakehouse | Apache Iceberg, DuckDB (embedded) | — | Target |
| Object storage | SeaweedFS (default) or external S3 | — | Target |
| MQTT broker | Eclipse Mosquitto / RabbitMQ + MQTT plugin | 2.0 / via Cluster Operator | Single node / Kubernetes HA |
| Reverse proxy | nginx | 1.30 | TLS termination, routing, auth_request |
| Container runtime | Docker / Kubernetes | — | Module isolation |
| DB migrations | Flyway | via Quarkus | Single baseline migration |
| Dashboards | Grafana | 13 | User dashboards and system monitoring |
| Monitoring | Prometheus, Loki, Alloy | 3.14 / 3.7 / 1.19 | System metrics and logs |
| Alert e-mail | Quarkus Mailer | via Quarkus | SMTP configured per installation |
| Documentation | Zensical | 0.0.x | Builds the docs site from `mkdocs.yml` |

---

## 15. Module Contracts

Every module class may define `__init__(self, config)`; it receives the module version's config object (MOD-12). Methods may declare fewer parameters than offered; the platform passes as many as the signature accepts.

### 15.1 IMPORT Module

```python
class ImportModule:
    def parse(self, input_data: dict, connector_meta: dict = None) -> dict:
        """input_data = raw payload fetched by the platform through the connector.
        Parse/validate it; do not make network calls."""
        return input_data
```

### 15.2 DB_LOAD Module

```python
class DbLoadModule:
    def metadata(self) -> dict:
        """Optional: describe the query (table, columns, filters)."""
        return {}

    def load(self, query: dict, context: dict) -> list:
        """Adjust the rows loaded by the platform. context = {metadata, connector_meta}."""
        return []
```

### 15.3 PROCESSING Module

```python
class ProcessingModule:
    def process(self, data: dict | pd.DataFrame, query_fn=None) -> dict | pd.DataFrame | None:
        """Transform data. Return None to drop the message; raise to fail the node (DLQ)."""
        return data
```

### 15.4 MERGE Module

```python
class MergeModule:
    def merge(self, inputs: list) -> dict:
        """inputs = outputs of all upstream nodes, in edge order."""
        return {"combined": inputs}
```

### 15.5 ALERT Module

```python
class AlertModule:
    def evaluate(self, data: dict | pd.DataFrame, alert_fn=None, query_fn=None) -> bool:
        """Call alert_fn(severity, message, payload=None, title=None, channel=None,
        cooldown_seconds=None) to raise alerts. Terminal node; returns True if fired."""
        return False
```

### 15.6 EXPORT Module

```python
class ExportModule:
    def shape(self, data: dict, connector_meta: dict = None) -> dict:
        """Return the payload the platform delivers through the node's connector."""
        return data
```

### 15.7 DB_SAVE Module

```python
class DbSaveModule:
    def metadata(self) -> dict:
        """Optional: table, mode (append/upsert), key columns, column model."""
        return {}

    def prepare(self, input_data: dict, context: dict) -> dict | list:
        """Return the row(s) the platform writes. context = {metadata, connector_meta}."""
        return input_data
```

### 15.8 Module Metadata

Module name, type, description, requirements and config are stored with the module and its versions in the UI; code does not need to declare them.

### 15.9 Worked Examples

The contracts above are minimal signatures. These are complete, realistic modules for common
tasks, in addition to the full worked example in §13.6 and `docs/examples/energy-14a/`.

**PROCESSING — parse a semicolon-separated CSV line into a typed record** (a common shape for
German exports; feeds a "Save to metadata" or DB_SAVE node downstream):

```python
class ProcessingModule:
    def process(self, data: dict, query_fn=None) -> dict | None:
        line = data.get("line", "")
        if not line or line.startswith("#"):
            return None                       # header or blank line: drop, do not fail the run
        cols = line.split(";")
        return {
            "meter_number": cols[0].strip(),
            "reading_kwh": float(cols[1].replace(",", ".")),
            "read_at": cols[2].strip(),
        }
```

**ALERT — absence of data, with a per-series cooldown** (custom logic beyond the built-in
absence rule type, e.g. when the "missing" window depends on the record itself):

```python
class AlertModule:
    def evaluate(self, data: dict, alert_fn=None, query_fn=None) -> bool:
        expected_interval_s = data.get("reportingIntervalSeconds", 900)
        age_s = data.get("secondsSinceLastReading", 0)
        if age_s > expected_interval_s * 3:
            alert_fn(
                severity="warning",
                message=f"No reading from {data.get('deviceId')} for {age_s}s "
                         f"(expected every {expected_interval_s}s)",
                payload=data,
                cooldown_seconds=expected_interval_s,
            )
            return True
        return False
```

**EXPORT — shape a record for a legacy system expecting a fixed field order**:

```python
class ExportModule:
    def shape(self, data: dict, connector_meta: dict = None) -> dict:
        return {
            "DEVICE": data["deviceId"],
            "VALUE": round(data["value"], 2),
            "UNIT": data.get("unit", "kWh"),
            "TS": data["time"],
        }
```

---

## 16. Security Model

### 16.1 Network Security

- All external traffic enters via the nginx reverse proxy (TLS termination in production) and passes the common login (IAM-09).
- Backend services and bundled components communicate over the internal Docker/Kubernetes network only; Kafka, databases, object storage and Grafana are not exposed externally.
- Module containers have no network access by default; explicit network grants require the corresponding permission (MOD-05).

### 16.2 Authentication & Session

- Browser sessions: HttpOnly, Secure, SameSite cookie with CSRF protection; configurable lifetime; revocable.
- Machine clients: JWT (1 hour) or API keys (hashed, optional expiry, plaintext shown once).
- Local passwords: TLS in transit, BCrypt at rest, minimum 8 characters (NFR-S2).
- Failed login attempts are rate-limited (5 attempts per 15 minutes per IP) and audited.
- Signing keys and all credentials come from the installation's secrets, never from the image or repository (NFR-S7).

Signing keys are supplied per installation (`DATATURNTABLE_JWT_SIGNING_KEY_LOCATION`, `DATATURNTABLE_JWT_PUBLIC_KEY_LOCATION`); none is part of the repository or the images (NFR-S7). For development, `scripts/dev/generate-dev-keys.sh` creates a throwaway pair in the git-ignored `secrets/dev-keys/`.

### 16.3 Module Sandboxing

User Python code runs with:

- Non-root user inside the container
- Read-only root filesystem; `/tmp` as tmpfs
- No capability grants (`--cap-drop ALL`)
- CPU and memory limits
- No access to other containers' namespaces or the container runtime socket
- No credentials: connector secrets stay platform-side (CON-05)

### 16.4 Tenant Isolation

- Application checks on every API call (tenant access and permission).
- Per-tenant database roles for pipeline data (NFR-S8).
- Lakehouse catalog namespaces per tenant; the query layer resolves only permitted tables (LAK-11).
- User module output goes to tenant-scoped run logs, never to the system log pipeline (NFR-M2).

### 16.5 Audit Logging

See §18.

---

## 17. Deployment Topology

Supported targets: single node (Docker Compose) and Kubernetes (DEP-01, DEP-02). Docker Swarm stacks are not supported (DEP-08).

### 17.1 Single Node (Docker Compose)

```text
docker/docker-compose.dev.yml
├── postgresql, pipeline-postgresql   (timescale/timescaledb:2.30.1-pg18)
├── monitoring-postgresql, dashboard-postgresql (postgres:18)
├── kafka             (apache/kafka:4.3.1, KRaft)
├── mosquitto         (eclipse-mosquitto:2.0.22, ports 1883 / 9001 WS)
├── control-api, public-api, ingest-edge, pipeline-executor (backend image, one role each)
├── frontend          (nginx serving the built UI)
├── nginx             (reverse proxy)
├── docs              (documentation site)
├── grafana, dashboard-grafana
└── prometheus, loki, alloy, exporters
```

This stack is a **development and evaluation topology, not an HA one** (NFR-R8). It runs one broker and one database, so it cannot meet NFR-R5 or NFR-R7: if the node fails, in-flight data is lost with it. It is offered because it is cheap to run and complete, not because it is production-grade. Anything that depends on redundancy — the replicated bus, the durable ingest spool on a persistent volume, automatic failover — is a Kubernetes property.

### 17.2 Kubernetes

```text
docker/k8s/        — application + single-instance infrastructure (kubectl apply -k docker/k8s)
docker/k8s-ha/     — overlay: CloudNativePG clusters, Strimzi Kafka, RabbitMQ MQTT cluster;
                     the single-node service names stay as DNS aliases (kubectl apply -k docker/k8s-ha)
```

Database credentials are one basic-auth Secret per database, used by both the single-instance PostgreSQL and the CloudNativePG bootstrap. Stateless backend roles and the frontend have HorizontalPodAutoscalers. Module containers run as pods managed by the backend (target; today via the Docker CLI, which requires a Docker daemon).

Target: a single Helm chart installs the platform with its bundled components and operators (DEP-03).

### 17.3 Port Map (Single Node, Development)

| Service | Port | Protocol |
| ------- | ---- | -------- |
| Reverse proxy | 80 | HTTP |
| Frontend (direct) | 3000 | HTTP |
| Monitoring Grafana (direct) | 3001 | HTTP |
| Docs (direct) | 3002 | HTTP |
| Dashboard Grafana (direct) | 3003 | HTTP |
| PostgreSQL / TimescaleDB | 5432 – 5435 | TCP |
| Kafka | 9092 | TCP |
| MQTT | 1883 | TCP |
| MQTT over WebSocket | 9001 | WS |
| Prometheus | 9090 | HTTP |
| Loki | 3100 | HTTP |

---

## 18. Audit Trail and Activity

### 18.1 Coverage

Every action with impact creates an audit event (AUD-01): configuration changes on every entity, runs and run control, security events (login, failed login, logout, permission denied), role and assignment changes including automatic activation/expiry (actor `system`), connection requests and grants, operations (deployments, scaling, upgrades, backups, restores, retention deletions, lake maintenance), data access at system scope (always) and tenant-scope data access/exports (configurable) (AUD-02).

### 18.2 Event Model

```json
{
  "id": "uuid",
  "occurredAt": "2026-09-25T10:22:00Z",
  "actor": { "id": "uuid", "name": "Jane Doe", "type": "user", "authMethod": "oidc",
             "sessionId": "…", "ip": "10.0.0.5", "userAgent": "…" },
  "action": "pipeline:write",
  "target": { "type": "pipeline", "id": "uuid", "name": "Boiler", "parent": "tenant:uuid" },
  "tenantId": "uuid",
  "scope": "tenant",
  "outcome": "success",
  "changes": { "isActive": { "before": false, "after": true } },
  "reason": "Go-live",
  "source": "ui",
  "correlationId": "…",
  "hash": "…", "previousHash": "…"
}
```

Secrets are never stored in `changes` (only the fact that they changed); measurement values are not stored, only identifiers and counts.

### 18.3 Integrity

- Written in the same database transaction as the change; if the audit write fails, the change fails. External operations record an intent before and an outcome after (AUD-04).
- Append-only: the application's database role has no UPDATE/DELETE on audit tables; a trigger rejects modifications. Each event contains the hash of its predecessor; the chain can be verified from the UI (AUD-05).
- Retention configurable with a minimum; older partitions are archived to the lakehouse and stay queryable (AUD-09).

### 18.4 Notes

Users with `audit.note:write` attach notes to events or add standalone notes to a component's timeline (e.g. "vendor maintenance window Friday"). Notes are immutable and audited; corrections are follow-up notes (AUD-06).

### 18.5 UI

- **Activity tab** on every component's configuration page, filtered to the component and its children (a pipeline includes its versions and runs) (UI-17).
- **Audit page**: filters for time range, actor, action, target type/id, tenant, outcome, source, full text; saved filters; CSV/JSON export (audited) (AUD-07). Visibility follows `audit:read` scope (AUD-08).

*Implementation note:* `AuditService` writes `audit_events` rows for configuration changes (pipelines, connectors, tenants, users, memberships, roles, secrets, API keys, alert channels), run control and logins. There is no read API, UI, hash chain or notes yet.

---

## 19. System Administration

The system area of the UI (UI-18) is available to holders of system-scope permissions:

| Function | Description | Requirement |
| -------- | ----------- | ----------- |
| Health and versions | State, version and scaling of every bundled component | OPS-01 |
| Upgrades | Start and follow upgrades; migrations run automatically; built-in role template changes shown and audited | OPS-02, IAM-07 |
| Backups | Schedules and restores to the configured backup destination | OPS-03 |
| System monitoring | Embedded system Grafana (Prometheus, Loki); no tenant payloads | OPS-04 |
| Tenants and quotas | Create tenants, set quotas | OPS-05 |
| Connections | Create connections, grant them to tenants, process connection requests | CON-09 – CON-11 |
| Role templates | Inspect, clone, create, edit custom roles, disable (impact preview, reason) | IAM-05, IAM-06 |
| Identity providers | Configure OIDC providers, claim mappings, break-glass accounts, sessions | IAM-10 – IAM-12 |
| Alert e-mail | SMTP server settings for EMAIL alert channels | ALT-04 |

---

*End of Functional Specification*
