# DataTurntable — Functional Specification

**Version:** 2.0
**Date:** 2026-09-25
**Status:** Draft

This version describes the target behaviour agreed in [target-architecture.md](../development/target-architecture.md) and specified in the [requirements specification](requirements-specification.md) v2.0 (requirement IDs in brackets). Where the current implementation differs substantially, an *Implementation note* says so; the per-requirement status is in the requirements specification, Appendix A.

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
| Messaging | Internal bus | Apache Kafka (KRaft) | Decouple ingestion and processing; at-least-once delivery; replay |
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

**Active (we pull):** The platform fetches data from an external source through an INPUT connector (e.g. REST polling, file watch, broker subscription) and hands it to the IMPORT module for parsing.

### 3.2 Passive Inbound Endpoints

| Protocol | Endpoint / Address | Notes |
| -------- | ------------------ | ----- |
| REST (HTTP/HTTPS) | `POST /api/ingest/v1/ingest/{pipelineId}`; `POST /api/ingest/v1/ingest/connector/{connectorId}` | Any JSON body; batch form (array of records) for throughput |
| MQTT | Topics configured on an INPUT connector (bundled or external broker) | v3.1.1 and v5.0 |
| WebSocket | Server endpoint configured on an INPUT connector | Each frame is one message |
| UDP | Configurable port per connector (V2) | Raw datagrams |

All passive endpoints authenticate the caller with an API key scoped to the pipeline or connector (IMP-09). Unauthenticated requests are rejected.

*Implementation note:* the ingestion endpoints read but do not yet validate the API key.

### 3.3 Active Pull (IMPORT Module)

The platform performs the external I/O through the connector (credentials never enter the container, CON-05) and passes the raw payload to the IMPORT module, which parses and validates it:

```python
class ImportModule:
    def parse(self, input_data: dict, connector_meta: dict = None) -> dict:
        ...
```

Polling is configured on the connector (REST and FILE connectors).

### 3.4 No Schema Enforcement

Inbound data is forwarded as-is into the pipeline and retained unmodified in the tenant's bronze table (LAK-03). No parsing, validation, or transformation is applied at the ingestion boundary. All format handling is the responsibility of the IMPORT module or the first PROCESSING module.

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
| `dataturntable.pipelines.input` | Ingestion → pipeline executor; keyed by tenant/pipeline so that partitions scale processing (PIP-12) |
| `dataturntable.pipelines.output` | Pipeline results for downstream consumers |
| `dataturntable.dlq.{tenantId}` | Dead-letter queue for failed messages (PIP-13) |
| `dataturntable.lake.commits` | Data-file metadata from lake writers to the table committer (§8.4) |

### 4.6 Error Handling

- A module error fails the node; the run is marked failed with the node's stdout/stderr in the run log. Instantiation errors are reported, never swallowed (MOD-13).
- Failed messages are routed to the DLQ topic, retained for 7 days (configurable), and can be requeued from the UI.
- Consecutive failures above a configurable threshold raise a platform alert and pause the pipeline node until it is resumed manually.

### 4.7 Deduplication

- **System duplicates** (Kafka at-least-once retries): suppressed using message-ID deduplication within a configurable window (default: 5 minutes).
- **User data duplicates** (same measurement arriving twice): preserved unless the user explicitly adds a deduplication PROCESSING module to the pipeline.

### 4.8 Scaling

Ingestion pods and pipeline workers are stateless and scale automatically: ingestion by request rate, workers by Kafka consumer lag (e.g. KEDA). Workers are long-running and process micro-batches; the module contract already accepts DataFrames (MOD-08). Single-node deployments run one instance of each.

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
```

*Implementation note:* `query_fn` currently returns an empty result.

### 5.5 Module Code Storage

Module source code, requirements and config are stored in the `module_versions` PostgreSQL table. Each save creates a new version record. The source is editable in the Monaco-based UI code editor. File-system storage for GitOps workflows is a future option.

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
| `FILE` | INPUT / OUTPUT | `path`, `format` (`csv`, `json`, `parquet`), `watchMode` |
| `CUSTOM` | INPUT / OUTPUT | User-defined key/value pairs |

A system used in both directions is used through two connectors on the same connection (CON-02).

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

-- API keys (hashed), tenant data table registry, uploaded batch files, Grafana sync state,
-- connector runtime leases (multi-instance ownership)
api_keys(...), data_table_registry(...), data_blobs(...), grafana_sync_states(...), connector_runtime_leases(...)

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

*Implementation note:* custom ALERT modules work; the threshold rule is available as the ALERT module template. The built-in stateful rule types are to be built.

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

*Implementation note:* today a Tenant Admin membership in the root tenant acts as superadmin with access to all tenants; system-scope assignments replace this.

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

*Implementation note:* a JWT signing key is currently committed to the repository; ingestion API keys are not validated.

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

*Implementation note:* no audit trail exists yet; the `AuditLogger` class writes to the console only and is not called.

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
