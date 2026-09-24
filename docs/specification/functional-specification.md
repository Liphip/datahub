# DataTurntable — Functional Specification

**Version:** 1.1
**Date:** 2026-03-16
**Status:** Draft

---

## Table of Contents

1. [System Overview](#1-system-overview)
2. [Architecture](#2-architecture)
3. [Inbound Data (Import Layer)](#3-inbound-data-import-layer)
4. [Processing Pipeline Engine](#4-processing-pipeline-engine)
5. [Python Module System](#5-python-module-system)
6. [Outbound Data (Export Layer)](#6-outbound-data-export-layer)
7. [Connectors](#7-connectors)
8. [Storage Layer](#8-storage-layer)
9. [Alerting System](#9-alerting-system)
10. [Multi-Tenancy & RBAC](#10-multi-tenancy--rbac)
11. [REST API](#11-rest-api)
12. [Admin UI](#12-admin-ui)
13. [Data Flows & Use Cases](#13-data-flows--use-cases)
14. [Technology Stack](#14-technology-stack)
15. [Module Contracts](#15-module-contracts)
16. [Security Model](#16-security-model)
17. [Deployment Topology](#17-deployment-topology)

---

## 1. System Overview

DataTurntable is a **modular data hub** for ingesting, transforming, routing, storing, and alerting on data from IoT sensors, HTTP endpoints, and any other data source. It is not an AI platform — it is a **data integration and processing platform** optimised for high-throughput time-series and event-driven workloads.

### 1.1 Core Concept

```text
External Systems (push data in)            DataTurntable                  External Systems (receive data)
────────────────────────────────           ─────────────────────────────  ──────────────────────────────
REST POST → /api/v1/ingest/{id}  ────────► Import Layer                   ◄──── REST GET (Grafana pull)
MQTT publish to topic            ────────► (REST /api/v1/, MQTT,          MQTT publish ─────────────────►
WebSocket frame                  ────────►  WebSocket, UDP)               WebSocket push ───────────────►
UDP datagram                     ────────►                                HTTP POST webhook ────────────►
                                                │ Kafka
                                           Pipeline Engine (DAG)
                                           ┌────────────────────────────┐
                                           │  [IMPORT]                  │
                                           │      │                     │
                                           │  [PROCESSING] ──┬──────────┤
                                           │                 │          │
                                           │  [PROCESSING] ──┤  [ALERT] │
                                           │  (fan-in)       │          │
                                           │             ┌───┴───┐      │
                                           │         [EXPORT] [EXPORT]  │
                                           │         (fan-out)          │
                                           └────────────────────────────┘
                                                │
                                           Storage Layer
                                           TimescaleDB │ PostgreSQL
```
### 1.2 Two API Paths

| Path | Purpose | Auth |
| ---- | ------- | ---- |
| `/api/internal/v1/` | Management API — configure tenants, modules, pipelines, alerts; query stored data | JWT or API key |
| `/api/v1/` | Public ingestion API — external systems push data into pipelines | API key (configurable per pipeline) |

### 1.3 Primary Use Cases

1. **IoT Sensor Pipeline**: MQTT measurements → PROCESSING (transform) → ALERT (threshold) + EXPORT (TimescaleDB) + EXPORT (MQTT dashboard)
2. **Data Visualisation Feed**: MQTT measurements → PROCESSING (normalise) → EXPORT (expose Grafana-compatible pull endpoint)
3. **Batch ETL**: HTTP file upload → PROCESSING (parse/normalise) → EXPORT (PostgreSQL) → EXPORT (report webhook)
4. **Multi-source Merge**: HTTP feed + MQTT stream → each parsed separately → PROCESSING (combine/fan-in) → EXPORT

---

## 2. Architecture

### 2.1 Component Overview

```text
┌────────────────────────────────────────────────────────────────────────────────┐
│                            DataTurntable Platform                               │
│                                                                                 │
│  ┌──────────────┐  ┌────────────────────┐  ┌─────────────┐  ┌──────────────┐  │
│  │   Frontend    │  │   Management API   │  │   Engine    │  │    Docker    │  │
│  │  (React/TS)  │  │  (Quarkus/Kotlin)  │  │   (Kafka)   │  │  Containers  │  │
│  └──────┬───────┘  └────────┬───────────┘  └──────┬──────┘  └──────┬───────┘  │
│         │                   │                      │                 │          │
│         └───────────────────┴──────────────────────┘                │          │
│                             │                                        │          │
│  ┌──────────────────────────▼────────────────────────────────────────▼───────┐ │
│  │  Ingestion Layer   REST /api/v1/ │ MQTT │ WebSocket │ UDP                  │ │
│  └──────────────────────────────────────────────────────────────────────────┘ │
│                                                                                 │
│  ┌────────────┐  ┌────────────────┐  ┌──────────┐  ┌────────────────────────┐ │
│  │   Kafka    │  │  TimescaleDB   │  │PostgreSQL│  │   Module Containers    │ │
│  └────────────┘  └────────────────┘  └──────────┘  └────────────────────────┘ │
└────────────────────────────────────────────────────────────────────────────────┘
```
### 2.2 Layers

| Layer | Component | Technology | Responsibility |
| ----- | --------- | ---------- | -------------- |
| Presentation | Admin UI | React 18 + TypeScript + Tailwind CSS | Visual pipeline management, code editing, monitoring |
| Management API | Backend | Quarkus 3.x (Kotlin / Java 21) | REST management API, auth, tenant management, config |
| Ingestion | Ingestion Layer | Quarkus (REST + MQTT + WS + UDP) | Expose protocol endpoints; route incoming data into Kafka |
| Messaging | Internal Bus | Apache Kafka (KRaft) | Decouple pipeline nodes; at-least-once delivery; replay |
| Execution | Module Containers | Docker (Python 3.11) | Run user Python code in isolation |
| Storage | Databases | TimescaleDB, PostgreSQL | Persist data and metadata |
| Connectivity | Modules | Python / Java | IMPORT, PROCESSING, ALERT, EXPORT implementations |

### 2.3 Internal Data Flow

```text
External system pushes to:
  REST /api/v1/ingest/{pipelineId}
  MQTT topic: dataturntable/ingest/{pipelineId}
  WebSocket: ws://host:8080/api/v1/ws/{pipelineId}
          │
          ▼
Ingestion Layer
          │ publishes raw payload
          ▼
Kafka: dataturntable.pipelines.{id}.input
          │
          ▼
Pipeline Engine — resolves DAG graph
          │
          ├─► IMPORT node (if active-pull module) ──► processes, emits to downstream edge topics
          │
          ├─► PROCESSING node ──────────────────────► transforms data, emits to all downstream edges
          │         (fan-out: emits to N edge topics)
          │         (fan-in: consumes from M edge topics, merges, emits)
          │
          ├─► ALERT node ───────────────────────────► evaluates condition, fires alert (no data forwarding)
          │
          └─► EXPORT node ──────────────────────────► pushes to external system or exposes pull endpoint
```
---

## 3. Inbound Data (Import Layer)

### 3.1 Overview

The import layer sits at the boundary between the outside world and the DataTurntable pipeline engine. It has two operational modes:

**Passive (push to us):** The platform exposes protocol endpoints. External systems push data to DataTurntable. No modification to the external system is needed beyond pointing it at the endpoint.

**Active (we pull):** An IMPORT Python module runs in a container and periodically fetches data from an external source.

### 3.2 Passive Inbound Endpoints

| Protocol | Endpoint / Address | Notes |
| -------- | ------------------ | ----- |
| REST (HTTP/HTTPS) | `POST /api/v1/ingest/{pipelineId}` | Any JSON or binary body; no schema enforced |
| MQTT | Subscribe to `dataturntable/ingest/{pipelineId}/#` on bundled broker (port 1883) | v3.1.1 and v5.0 |
| WebSocket | `ws://host:8080/api/v1/ws/{pipelineId}` | Each frame is one message |
| UDP | Configurable port per pipeline (e.g. 5005) | Raw datagrams |

All passive endpoints are authenticated via an API key scoped to `data:write`. The key is passed as a query parameter or header; pipelines can be configured as open (no auth) for trusted internal networks.

### 3.3 Active Pull (IMPORT Module)

An IMPORT Python module runs persistently in a Docker container. It connects to the external system and calls the platform's internal `emit()` function to inject data into the pipeline:

```python
class ImportModule:
    def connect(self, config: dict): ...
    def fetch(self) -> dict | pd.DataFrame: ...
    def disconnect(self): ...
```
The platform calls `fetch()` on a configurable schedule, or the module can implement its own blocking loop.

### 3.4 No Schema Enforcement

Inbound data is forwarded as-is into the pipeline. No parsing, validation, or transformation is applied at the ingestion boundary. All format handling is the responsibility of the first PROCESSING (or the IMPORT) module in the pipeline.

---

## 4. Processing Pipeline Engine

### 4.1 Pipeline as a DAG

A pipeline is a directed acyclic graph (DAG) where:

- **Nodes** are module instances (each referencing a specific module version)
- **Edges** are directed data connections between nodes
- Each edge corresponds to a Kafka topic

Node types and their roles in the graph:

| Node Type | Can have upstream nodes | Can have downstream nodes | Forwards data |
| --------- | ----------------------- | ------------------------- | ------------- |
| IMPORT | No (entry point) | Yes | Yes |
| PROCESSING | Yes (1 or more) | Yes (1 or more) | Yes |
| ALERT | Yes | No (terminal) | No |
| EXPORT | Yes | No (terminal) | No |

### 4.2 Fan-out

One node's output is routed to multiple downstream nodes simultaneously. Each downstream node receives a copy of the data on its own Kafka topic.

```text
[PROCESSING: Normalise]
         │
    ┌────┴────────────┐
    ▼                 ▼
[ALERT: Threshold]  [EXPORT: TimescaleDB]
```
### 4.3 Fan-in (Combination)

A PROCESSING module can consume from multiple upstream edge topics and combine the streams. This is implemented as a standard PROCESSING module; the platform routes all upstream outputs to the same node's input topic and the module handles merging.

```text
[IMPORT: MQTT sensor A] ──► [PROCESSING: Parse A] ──┐
                                                      ├──► [PROCESSING: Merge] ──► [EXPORT: DB]
[IMPORT: HTTP feed B]   ──► [PROCESSING: Parse B] ──┘
```
### 4.4 Pipeline Definition (JSON)

IMPORT and EXPORT nodes must include a `connectorId` referencing a tenant-level connector definition. The platform resolves the connector and injects its config into the module container at runtime.

```json
{
  "id": "pipeline-001",
  "name": "Temperature Sensor Pipeline",
  "tenantId": "tenant-uuid",
  "nodes": [
    { "id": "n1", "type": "IMPORT",     "moduleVersionId": "uuid-import-v1",    "connectorId": "mqtt-broker-001" },
    { "id": "n2", "type": "PROCESSING", "moduleVersionId": "uuid-normalise-v1" },
    { "id": "n3", "type": "ALERT",      "moduleVersionId": "uuid-threshold-v1" },
    { "id": "n4", "type": "EXPORT",     "moduleVersionId": "uuid-timescale-v1", "connectorId": "timescale-writer-001" },
    { "id": "n5", "type": "EXPORT",     "moduleVersionId": "uuid-mqtt-v1",      "connectorId": "mqtt-dashboard-001" }
  ],
  "edges": [
    { "from": "n1", "to": "n2" },
    { "from": "n2", "to": "n3" },
    { "from": "n2", "to": "n4" },
    { "from": "n2", "to": "n5" }
  ]
}
```

> **MVP note:** The current database schema stores `importModuleVersionId`, `processingModuleVersionIds[]`, and `exportModuleVersionIds[]` for backward compatibility. Full graph definition (`nodes` + `edges`) is stored in the `graph_definition` JSONB column and is the target model.

### 4.5 Kafka Topic Naming

Each directed edge has its own Kafka topic:

| Topic | Purpose |
| ----- | ------- |
| `dataturntable.pipelines.{pipelineId}.input` | Ingestion layer → first node(s) |
| `dataturntable.pipelines.{pipelineId}.nodes.{srcId}.to.{dstId}` | Data on a specific graph edge |
| `dataturntable.alerts.{tenantId}` | Alert events dispatched by ALERT nodes |
| `dataturntable.system.commands` | API → engine commands (start, stop, etc.) |
| `dataturntable.dlq.{pipelineId}` | Dead-letter queue (failed messages) |

### 4.6 Error Handling

- Processing errors in a module container route the message to the DLQ topic.
- DLQ messages are retained for 7 days (configurable).
- Operators can requeue DLQ messages from the UI.
- Consecutive failures above a configurable threshold trigger an ALERT and pause the pipeline node.
- A paused node does not consume new messages until manually resumed.

### 4.7 Deduplication

- **System duplicates** (Kafka at-least-once retries): suppressed using message-ID deduplication within a configurable window (default: 5 minutes).
- **User data duplicates** (same measurement arriving twice): preserved unless the user explicitly adds a deduplication PROCESSING module to the pipeline.

---

## 5. Python Module System

### 5.1 Module Types

| Type | Role in pipeline | Forwards data | Fires alerts |
| ---- | ---------------- | ------------- | ------------ |
| `IMPORT` | Entry node — receives or fetches data | Yes | No |
| `PROCESSING` | Intermediate node — transforms/filters/merges | Yes | No |
| `ALERT` | Terminal node — evaluates conditions | No | Yes |
| `EXPORT` | Terminal node — sends data out | No | No |

### 5.2 Module Lifecycle

```text
SAVED (source code stored in PostgreSQL)
    │
    ▼
VALIDATED (syntax check, import test)
    │
    ▼
BUILT (Docker image assembled with user code + standard libs)
    │
    ▼
DEPLOYED (container started, registered with engine)
    │
    ▼
RUNNING ⇄ PAUSED
    │
    ▼
STOPPED / UPDATED (rolling update: new container drains old)
```
### 5.3 Container Sandbox

Each running module instance is an isolated Docker container:

| Property | Value |
| -------- | ----- |
| Base image | `python:3.14.7-slim-trixie` (configurable via `application.yaml`) |
| User | Non-root (`module-runner`) |
| Filesystem | Read-only root; `/tmp` writable; cleared between batch invocations |
| Network | None by default; explicit allowlist per module (requires TenantAdmin approval) |
| CPU limit | Configurable (default: 0.5 cores) |
| Memory limit | Configurable (default: 512 MB) |
| Pre-installed libs | pandas, numpy, scikit-learn |
| Optional libs | torch, xgboost, transformers (enabled per module config) |
| System API | Injected via environment variables / gRPC sidecar |

### 5.4 System API Injected into Modules

```python
# Available as function arguments or via injected context

def query_timeseries(metric: str, start: str, end: str,
                     filters: dict = None) -> pd.DataFrame:
    """Query TimescaleDB for historical data within this tenant."""

def query_sql(sql: str, params: list = None) -> pd.DataFrame:
    """Execute a read-only SQL query against this tenant's PostgreSQL."""

def alert(severity: str, message: str, payload: dict = None):
    """Dispatch an alert to all channels configured on this pipeline.
    severity: 'info' | 'warning' | 'critical'
    """
```
### 5.5 Module Code Storage

Module source code is stored in the `module_versions` PostgreSQL table. Each save creates a new version record. The plaintext source is editable in the Monaco-based UI code editor. File-system storage for GitOps workflows is a future option.

---

## 6. Outbound Data (Export Layer)

### 6.1 Overview

EXPORT nodes are terminal nodes in the pipeline DAG. They can operate in two modes:

**Active push:** The EXPORT module actively calls an endpoint on an external system, sends data to a message broker, writes to a database, etc.

**Passive expose:** The EXPORT module registers an endpoint on the platform that external systems can query or subscribe to (e.g. Grafana pulling metrics, another system subscribing to a WebSocket feed).

### 6.2 Built-in EXPORT Connectors

| Connector | Mode | Description |
| --------- | ---- | ----------- |
| TimescaleDB Writer | Push | Writes time-series data to the platform's TimescaleDB instance |
| PostgreSQL Writer | Push | Writes relational data to the platform's PostgreSQL instance |
| MQTT Publisher | Push | Publishes to a topic on an external or bundled MQTT broker |
| HTTP Webhook | Push | POSTs data to a configured URL |
| REST Client | Push | Calls any external REST endpoint with the processed data |
| WebSocket Push | Push | Maintains WebSocket connections and pushes data to connected clients |
| Grafana Datasource | Expose | Exposes a Grafana SimpleJSON-compatible pull endpoint |
| ThingsBoard Export | Push | Pushes telemetry to ThingsBoard via its MQTT API |
| Python Custom | Push or Expose | User-written EXPORT module (any destination or exposure pattern) |

### 6.3 Custom EXPORT Module (Python)

```python
class ExportModule:
    def connect(self, config: dict):
        """Open connection to destination."""
        pass

    def send(self, data: dict | pd.DataFrame):
        """Push data to the external system."""
        pass

    def disconnect(self):
        """Clean up."""
        pass
```
For modules that expose an endpoint (passive mode), the `connect()` method registers the endpoint with the platform's ingress layer. The `send()` method buffers or updates the data that the endpoint will serve.

---

## 7. Connectors

### 7.1 Overview

Connectors are named, tenant-scoped entities that describe how to reach a protocol endpoint. They are defined independently of pipelines — a connector can be reused across multiple pipelines.

IMPORT and EXPORT pipeline nodes reference a connector by `connectorId`. At runtime the platform resolves the connector and injects its `config` JSON into the module container, so the module code does not need to hard-code connection parameters.

### 7.2 Supported Connector Types

| Type | Direction | Typical Config Fields |
| ---- | --------- | --------------------- |
| `MQTT` | INPUT / OUTPUT / BOTH | `broker`, `port`, `topic`, `clientId`, `username`, `password`, `qos`, `tls` |
| `REST` | INPUT / OUTPUT / BOTH | `baseUrl`, `method`, `headers`, `authType`, `apiKey` |
| `WEBSOCKET` | INPUT / OUTPUT / BOTH | `url`, `path`, `headers`, `tls` |
| `UDP` | INPUT / OUTPUT / BOTH | `host`, `port`, `bindAddress` |
| `FILE` | INPUT / OUTPUT | `path`, `format` (`csv`, `json`, `parquet`), `watchMode` |
| `CUSTOM` | INPUT / OUTPUT / BOTH | User-defined key/value pairs |

### 7.3 Connector Lifecycle

1. Admin creates a connector via `POST /api/internal/v1/connectors?tenantId=...`
2. The connector appears in the Connector Library for the tenant.
3. When building a pipeline, IMPORT/EXPORT nodes reference a connector from the library by `connectorId`.
4. At pipeline execution time, the platform resolves the connector config and injects it into the module container as the `connector` block of the module config dict.
5. Updating a connector's config takes effect on the next pipeline execution — no pipeline restart required.

### 7.4 Connector Security

Connector configs may contain credentials (passwords, API keys). These are stored encrypted at rest and never returned in plaintext via the management API after creation (same pattern as API keys).

---

## 8. Storage Layer

### 8.1 Storage Backends

| Backend | Use Case | Default |
| ------- | -------- | ------- |
| PostgreSQL | Platform metadata, pipeline config, module source, alert rules/history | Yes |
| TimescaleDB | Time-series sensor/IoT data (hypertables, compression, continuous aggregates) | Yes |
| MongoDB | Unstructured/document data, variable-schema payloads | Optional (V2) |
| MinIO / S3 | Binary files, batch exports, model artefacts | Optional (V2) |

### 8.2 Data Schema (Core Tables — PostgreSQL)

```sql
-- Tenant hierarchy (strict tree: one parent per node, root has parent_id = NULL)
tenants(id, name, parent_id, settings JSONB, quota JSONB, created_at)

-- Roles (tenant-scoped)
roles(id, tenant_id, name, abilities JSONB, is_default, created_at)

-- Login identities (platform-wide)
-- Superadmin = auth_user holding TenantAdmin membership in root tenant
auth_users(id, email UNIQUE, password_hash, is_active, created_at)

-- Per-tenant memberships (max 1 per auth_user per tenant)
tenant_memberships(id, auth_user_id, tenant_id, role_id,
                   abilities JSONB, created_at,
                   UNIQUE(auth_user_id, tenant_id))

-- Protocol connector definitions (tenant-scoped, named interface entities)
-- IMPORT/EXPORT nodes reference a connector by connectorId in graph_definition
connectors(id, tenant_id, name,
           connector_type CHECK IN ('MQTT','REST','WEBSOCKET','UDP','FILE','CUSTOM'),
           direction CHECK IN ('INPUT','OUTPUT','BOTH'),
           config JSONB, enabled, created_at,
           UNIQUE(tenant_id, name))

-- Code modules
code_modules(id, tenant_id, name,
             module_type CHECK IN ('IMPORT','PROCESSING','ALERT','EXPORT'),
             description, created_at, created_by)

-- Module versions
module_versions(id, module_id, version INT, version_tag,
                code TEXT, requirements TEXT, created_at, created_by)

-- Pipelines (IMPORT/EXPORT nodes include connectorId in graph_definition)
pipelines(id, tenant_id, name,
          graph_definition JSONB,              -- DAG: nodes + edges; IMPORT/EXPORT nodes include connectorId
          import_module_version_id UUID,       -- MVP compat
          processing_module_version_ids UUID[],
          export_module_version_ids UUID[],
          kafka_consumer_group, is_active, created_at, created_by)

-- Pipeline runs
pipeline_runs(id, pipeline_id, status, started_at, completed_at, error_message)

-- Alert channels
alert_channels(id, tenant_id, name,
               channel_type CHECK IN ('MQTT','WEBHOOK','REST','EMAIL','SLACK'),
               config JSONB, created_at)

-- Alert rules
alert_rules(id, tenant_id, pipeline_id, channel_id, enabled, created_at)

-- Alert history
alert_history(id, tenant_id, rule_id, severity, message,
              payload JSONB, status, sent_at)
```
### 8.3 Time-Series Data (TimescaleDB)

```sql
CREATE TABLE readings (
    time        TIMESTAMPTZ NOT NULL,
    tenant_id   UUID        NOT NULL,
    pipeline_id UUID        NOT NULL,
    metric      TEXT        NOT NULL,
    value       DOUBLE PRECISION,
    tags        JSONB,
    raw         JSONB
);
SELECT create_hypertable('readings', 'time');
CREATE INDEX ON readings(tenant_id, metric, time DESC);
```
### 8.4 Data Query API

```text
GET /api/internal/v1/data/{tenantId}/query
  ?table=readings
  &start=2026-03-01T00:00:00Z
  &end=2026-03-16T00:00:00Z
  &limit=1000
```
---

## 9. Alerting System

### 9.1 ALERT Module as a First-Class Pipeline Node

An ALERT node is a terminal node in the pipeline DAG. It receives data from upstream nodes, evaluates a condition, and dispatches an alert if the condition is met. It does **not** transform or forward data to downstream nodes.

This separation keeps data processing and alert logic decoupled: a single PROCESSING output can feed both an ALERT node and an EXPORT node simultaneously (fan-out).

### 9.2 Built-in Rule Types

| Type | Description | Config Parameters |
| ---- | ----------- | ----------------- |
| `threshold` | Fire when a field crosses a limit | `field`, `operator`, `value`, `severity` |
| `rate_of_change` | Fire when value changes by more than N per interval | `field`, `delta`, `intervalSeconds`, `severity` |
| `time_window` | Fire when an aggregated value in a window exceeds a limit | `field`, `aggregation`, `windowSeconds`, `operator`, `value` |
| `absence` | Fire when no data arrives within a timeout | `pipelineId`, `timeoutSeconds` |
| `custom` | Fire based on Python `AlertModule` logic | `moduleVersionId`, module-specific config |

### 9.3 Alert Channel Types

| Channel | Required Config |
| ------- | --------------- |
| MQTT | `broker`, `topic`, `qos`, `payload_template` |
| HTTP Webhook | `url`, `method`, `headers`, `body_template` |
| REST | `url`, `method`, `headers`, `body_template` |
| Email (SMTP) | `smtp_host`, `port`, `to`, `subject_template`, `body_template` |
| Slack | `webhookUrl`, `channel`, `message_template` (Block Kit format) |

### 9.4 Alert Flow

```text
Data arrives at ALERT node (from upstream PROCESSING fan-out)
        │
        ▼
Condition evaluated (built-in rule or AlertModule.evaluate())
        │
   condition met?
        │ YES
        ▼
Cooldown check: same rule fired within cooldown window?
        │ NO
        ▼
Alert dispatched to configured channel(s)
        │
        ▼
Record written to alert_history table
        │
        ▼
Metrics counter incremented
```
### 9.5 Alert Configuration Example

```json
{
  "name": "High Temperature Alert",
  "type": "threshold",
  "pipelineId": "pipeline-uuid",
  "channelId": "slack-channel-uuid",
  "condition": {
    "field": "value",
    "operator": ">",
    "value": 80.0,
    "severity": "critical",
    "cooldownSeconds": 300
  }
}
```
---

## 10. Multi-Tenancy & RBAC

### 10.1 Tenant Hierarchy

Tenants form a **strict tree**: each tenant has exactly one parent, except the single root tenant (parent = NULL). Resources belong to exactly one tenant node. Permissions granted at a parent node apply to all children.

```text
Platform (Superadmin)
└── Tenant: AcmeCorp
    ├── Tenant: AcmeCorp/EuropeDiv
    │   ├── Tenant: Berlin
    │   │   ├── Workspace: FactoryFloor  ← pipelines, modules, data here
    │   │   └── Workspace: Logistics
    │   └── Tenant: Paris
    └── Tenant: AcmeCorp/AsiaDiv
        └── Tenant: Tokyo
```
### 10.2 Permission Model

Permissions are `ability:resource` pairs:

| Ability | Resource | Description |
| ------- | -------- | ----------- |
| `read` | `pipeline` | View pipeline definition and status |
| `write` | `pipeline` | Create, update, enable/disable pipelines |
| `execute` | `pipeline` | Manually trigger a pipeline run |
| `read` | `data` | Query stored data |
| `write` | `module` | Create/edit Python modules |
| `deploy` | `module` | Deploy module to engine |
| `configure` | `alert` | Manage alert rules and channels |
| `manage` | `tenant` | Create child tenants, manage users |
| `*` | `*` | Superadmin — all abilities |

### 10.3 Identity Model

Login identity (`auth_users`) is **separate** from per-tenant membership (`tenant_memberships`):

- One `auth_users` record per unique email address, platform-wide.
- Access to a tenant requires a `tenant_memberships` record for that (auth_user, tenant) pair.
- Each auth user may hold **at most one membership per tenant**.
- **Superadmin** = auth user holding a `TenantAdmin` role membership in the root tenant. There is no `is_superadmin` column.
- Root-tenant admins (superadmins) cannot simultaneously hold memberships in sub-tenants.

### 10.4 Authentication

**Users:** Email + password → JWT (1-hour access token). Password is SHA-256-hashed client-side before transmission; server stores BCrypt hash of the SHA-256 value.

**Service Accounts / API Keys:** Static key (BCrypt-hashed at rest) with explicit ability set and optional expiry. Plaintext shown only at creation.

**Future:** LDAP/AD and OAuth2/OIDC IdP integration.

---

## 11. REST API

### 11.1 API Paths

| Path | Purpose | Auth |
| ---- | ------- | ---- |
| `/api/internal/v1/` | Management API | JWT or API key |
| `/api/v1/` | Public data ingestion | API key per pipeline (or open) |

### 11.2 Management API Endpoint Overview

| Method | Path | Description |
| ------ | ---- | ----------- |
| POST | `/auth/login` | Authenticate, receive JWT |
| POST | `/auth/setup` | First-run: create root tenant + admin |
| GET | `/auth/me` | Current authenticated user |
| GET | `/tenants` | List root tenants |
| POST | `/tenants` | Create tenant |
| GET | `/tenants/{id}` | Get tenant details |
| DELETE | `/tenants/{id}` | Delete tenant |
| GET | `/tenants/{id}/children` | Get child tenants |
| GET | `/auth-users` | List auth (login) users |
| GET | `/auth-users/{id}` | Get auth user |
| POST | `/auth-users` | Create auth user |
| PATCH | `/auth-users/{id}` | Update auth user |
| DELETE | `/auth-users/{id}` | Delete auth user |
| GET | `/tenants/{id}/memberships` | List memberships for a tenant |
| POST | `/tenants/{id}/memberships` | Add membership (auth user ↔ tenant) |
| PATCH | `/tenants/{id}/memberships/{mid}` | Update membership role/abilities |
| DELETE | `/tenants/{id}/memberships/{mid}` | Remove membership |
| GET | `/connectors` | List connectors (`?tenantId=&direction=`) |
| GET | `/connectors/{id}` | Get connector |
| POST | `/connectors` | Create connector |
| PATCH | `/connectors/{id}` | Update connector |
| DELETE | `/connectors/{id}` | Delete connector |
| GET | `/modules` | List modules (`?tenantId=&type=`) |
| GET | `/modules/{id}` | Get module |
| POST | `/modules` | Create module |
| DELETE | `/modules/{id}` | Delete module |
| GET | `/modules/{id}/versions` | Version history |
| POST | `/modules/{id}/versions` | Save new version |
| GET | `/pipelines` | List pipelines (`?tenantId=`) |
| GET | `/pipelines/{id}` | Get pipeline |
| POST | `/pipelines` | Create pipeline |
| PATCH | `/pipelines/{id}` | Update pipeline |
| DELETE | `/pipelines/{id}` | Delete pipeline |
| POST | `/pipelines/{id}/run` | Manually trigger pipeline |
| GET | `/pipelines/{id}/runs` | Pipeline run history |
| GET | `/data/{tenantId}/query` | Query stored data |
| POST | `/data/{tenantId}/insert` | Insert data directly |
| GET | `/data/{tenantId}/tables` | List tenant data tables |
| DELETE | `/data/{tenantId}/tables/{table}` | Drop a tenant data table |
| GET | `/alerts/channels` | List alert channels (`?tenantId=`) |
| POST | `/alerts/channels` | Create alert channel |
| GET | `/alerts/rules` | List alert rules (`?tenantId=`) |
| POST | `/alerts/rules` | Create alert rule |
| GET | `/alerts/history` | Alert history (`?tenantId=&limit=`) |
| GET | `/q/health` | Platform health check |
| GET | `/q/metrics` | Prometheus metrics |

### 11.3 Public Ingestion API

| Method | Path | Description |
| ------ | ---- | ----------- |
| POST | `/api/v1/ingest/{pipelineId}` | Push data into a pipeline (any JSON body) |
| WS | `/api/v1/ws/{pipelineId}` | Stream data into a pipeline over WebSocket |

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

Rate limiting is **not yet implemented** (planned for V2). When implemented, the default will be 100 requests per minute per API key / JWT subject.

---

## 12. Admin UI

### 12.1 Technology

- **Framework:** React 18 with TypeScript
- **Build tool:** Vite 5.x
- **Styling:** Tailwind CSS 3.x
- **State management:** Zustand (lightweight global state for auth and shared data)
- **HTTP client:** Axios (with interceptors for token injection)
- **Charts:** Lightweight chart library (uPlot or Chart.js — to be integrated)
- **Code editor:** Monaco Editor 4.x (VS Code engine) for Python module editing

### 12.2 UI Route Structure

```text
/login                    — Login page
/                         — Dashboard (pipeline status, throughput, recent alerts)
/tenants                  — Tenant hierarchy management
/tenants/:id/members      — Tenant membership management (add/remove auth users)
/auth-users               — Platform auth user management
/connectors               — Connector library (define protocol interfaces)
/modules                  — Python module list (type-badged, with empty-state guide)
/modules/:id              — Module editor (Monaco, boilerplate, save/version/deploy)
/pipelines                — Pipeline list
/pipelines/new            — Pipeline builder
/pipelines/:id            — Pipeline detail, run history, DLQ
/data                     — Built-in data viewer (charts + table)
/alerts                   — Alert channel config, rule config, alert history
/settings                 — Platform settings
```
### 12.3 Pipeline Builder (MVP)

The MVP pipeline builder is a form-based ordered step list (no drag-and-drop). It represents the pipeline as:

```text
Import Module:
  [ mqtt-import-v1                         ▼ ]

Processing Steps (in order):
  ┌────────────────────────────────────────────┐
  │ 1. normalise-units v1   [Config] [Remove] │
  │    ↓ (fan-out to all below)               │
  │ 2. anomaly-detect v2    [Config] [Remove] │
  │                                            │
  │ [+ Add Processing Step]                   │
  └────────────────────────────────────────────┘

Alert Modules:
  [ threshold-alert v1                     ▼ ] [Config] [Remove]
  [+ Add Alert Module]

Export Modules:
  [ timescale-writer v1                    ▼ ] [Config] [Remove]
  [ mqtt-dashboard-pub v1                  ▼ ] [Config] [Remove]
  [+ Add Export]
```
Fan-out and fan-in are represented visually as multiple items in the same section. Full graph editing is a V2 feature.

Each node selector dropdown shows the module name, type badge, and a one-line description to help users pick the right module without needing to open the code editor.

### 12.4 Module Code Editor

The Monaco-based editor provides:

- Python syntax highlighting
- **Boilerplate template** pre-filled when a new module is created — the correct class skeleton (with docstrings and example logic) for the selected module type (IMPORT / PROCESSING / ALERT / EXPORT)
- Validate button (dry-run syntax check via backend)
- Save (creates new version), Deploy buttons
- Version history list

Example boilerplate injected for a new PROCESSING module:

```python
class ProcessingModule:
    """
    Transform, filter, or enrich incoming data.

    Return the modified data dict or DataFrame to pass it downstream.
    Return None to drop the message.
    Raise an exception to route the message to the dead-letter queue.
    """

    def process(self, data: dict, query_fn=None):
        # Example: add a derived field
        # data["celsius"] = (data["fahrenheit"] - 32) * 5 / 9
        return data
```

### 12.5 Contextual Help & Guided Onboarding

The UI is designed to be **intuitive for first-time users** without requiring external documentation. This is achieved through:

- **Inline example values** on all form fields (e.g. connector config fields show placeholder values like `mqtt://broker:1883`, `sensors/#`)
- **Inline help text / tooltips** on non-obvious settings (e.g. "Connector Direction: INPUT = this platform receives data; OUTPUT = this platform sends data; BOTH = bidirectional")
- **Empty-state guidance**: when a list (modules, pipelines, connectors) is empty, show a short description of what the entity is, why it's useful, and a "Create your first X" call-to-action with a link to an example
- **Boilerplate code** in the module editor (see §12.4)
- **Example connector configs** shown as collapsible "examples" sections next to config forms (e.g. clicking "MQTT example" fills the form with a commented sample config)
- **Consistent visual language**: module type badges (colour-coded: IMPORT=blue, PROCESSING=purple, ALERT=orange, EXPORT=green) used everywhere modules appear (lists, pipeline builder, node dropdowns)

### 12.7 Data Visualisation (Built-in)

Intentionally minimal — for quick sanity checks only:

- Line chart: metric vs. time, configurable range and aggregation
- Bar chart: aggregated values per tag/dimension
- Table view: raw data with pagination
- CSV / JSON export button

For production dashboards, use the Grafana datasource EXPORT module or the ThingsBoard EXPORT module.

---

## 13. Data Flows & Use Cases

### 13.1 MQTT → Transform → Alert + Store + Dashboard (Fan-out)

```text
1.  Sensor publishes to MQTT broker:
      topic: sensors/room-42/temperature
      payload: {"sensor":"room-42","value":85.3,"ts":1710600000}

2.  Ingestion layer subscribes to topic, receives message.

3.  Message published to Kafka:
      dataturntable.pipelines.pipeline-temp.input

4.  Pipeline engine resolves DAG:
      n1 (IMPORT) → n2 (PROCESSING: normalise) → n3 (ALERT: threshold)
                                               → n4 (EXPORT: TimescaleDB)
                                               → n5 (EXPORT: MQTT dashboard)

5.  n2 normalises: 85.3°F → 29.6°C, emits to three edge topics (fan-out).

6.  n3 (ALERT): 85.3°F > 80°F threshold → alert_fn(severity='critical', ...)
      → Dispatched to Slack channel.
      → Written to alert_history.

7.  n4 (EXPORT): writes (time, tenant, metric=temperature, value=85.3, tags=...)
      to TimescaleDB readings table.

8.  n5 (EXPORT): publishes normalised value to MQTT topic for live dashboard.
```
### 13.2 Two Streams Merged (Fan-in)

```text
1. HTTP POST /api/v1/ingest/pipeline-merge  →  {"source":"A","temp":22.1}
2. MQTT message on sensors/B/temp          →  {"source":"B","temp":21.9}

3. Both → separate IMPORT nodes → separate PROCESSING (parse) nodes.

4. Both parse nodes emit to PROCESSING (merge) node (fan-in).

5. Merge node combines streams into a DataFrame row.

6. EXPORT writes merged result to PostgreSQL.
```
### 13.3 Grafana Pull Integration

```text
1.  Data is written to TimescaleDB by EXPORT node during normal pipeline operation.

2.  Admin adds a second EXPORT node: "Grafana Datasource".
      → Platform registers GET /api/v1/grafana/{pipelineId}/query endpoint.

3.  Grafana is configured with this URL as a SimpleJSON data source.

4.  Grafana polls the endpoint; DataTurntable queries TimescaleDB and returns data.
```
---

## 14. Technology Stack

| Component | Technology | Version | Notes |
| --------- | ---------- | ------- | ----- |
| Backend API | Quarkus (Kotlin) | 3.32.2 / Kotlin 2.3 | Java 21 runtime |
| Internal Bus | Apache Kafka (Confluent) | 7.5.0 | KRaft mode (no ZooKeeper) |
| Pipeline Engine | Quarkus Kafka consumers | — | Graph-based, part of backend |
| Admin UI | React + TypeScript + Vite | 18.2 / 5.3 / 5.1 | Tailwind CSS, Zustand, Axios |
| Module Runtime | Python | 3.14 | `python:3.14.7-slim-trixie` Docker image |
| Default Storage (TS) | TimescaleDB | latest-pg16 | PostgreSQL extension |
| Default Storage (SQL) | PostgreSQL | 16.x | Metadata and config |
| Optional Storage | MongoDB | 7.x | Document store (V2) |
| Object Storage | MinIO | RELEASE.2024+ | S3-compatible (V2) |
| Caching | Redis | 7.x | Session, dedup |
| MQTT Broker | Eclipse Mosquitto | 2.x | Bundled for dev; external in prod |
| Reverse Proxy | Nginx | alpine | TLS termination, routing |
| Container Runtime | Docker | — | Module isolation |
| DB Migrations | Flyway | 10.x | Run on backend startup |
| Monitoring | Prometheus + Grafana | — | Optional sidecar stack |
| Code Editor | Monaco Editor | 4.6+ | In-browser Python editing |

---

## 15. Module Contracts

### 15.1 IMPORT Module

```python
class ImportModule:
    """Receives or fetches data from an external source.
    Entry node in the pipeline DAG.
    """

    def connect(self, config: dict):
        """Initialise connection. Called once on container start."""
        pass

    def fetch(self) -> dict | pd.DataFrame:
        """Fetch one record or batch. Called on schedule or in a loop."""
        pass

    def disconnect(self):
        """Clean up connection. Called on container stop."""
        pass
```
### 15.2 PROCESSING Module

```python
class ProcessingModule:
    """Transforms, filters, or merges data.
    Can receive from one or more upstream nodes (fan-in).
    Emits to one or more downstream nodes (fan-out).
    """

    def __init__(self, config: dict):
        pass

    def process(self, data: dict | pd.DataFrame,
                query_fn=None) -> dict | pd.DataFrame | None:
        """Transform data.

        Return None to drop the message (filter).
        Raise an exception to route to DLQ.
        """
        return data
```
### 15.3 ALERT Module

```python
class AlertModule:
    """Evaluates conditions and dispatches alerts.
    Terminal node — does NOT modify or forward the data stream.
    """

    def __init__(self, config: dict):
        pass

    def evaluate(self, data: dict | pd.DataFrame,
                 alert_fn=None,
                 query_fn=None) -> bool:
        """Evaluate alert condition.

        Call alert_fn(severity, message, payload) to fire an alert.
        Return True if alert fired, False otherwise.

        Example:
            if data['value'] > self.config['threshold']:
                alert_fn(
                    severity='critical',
                    message=f"Value {data['value']} exceeds threshold",
                    payload=data
                )
                return True
            return False
        """
        return False
```
### 15.4 EXPORT Module

```python
class ExportModule:
    """Sends data to an external system or exposes a pull endpoint.
    Terminal node — does not forward data downstream.
    """

    def __init__(self, config: dict):
        pass

    def connect(self, config: dict):
        """Open connection or register endpoint."""
        pass

    def send(self, data: dict | pd.DataFrame):
        """Push data to destination or update the exposed endpoint's buffer."""
        pass

    def disconnect(self):
        """Clean up or deregister endpoint."""
        pass
```
### 15.5 Standard Module Metadata

Every module must declare:

```python
MODULE_METADATA = {
    "name": "normalise-units",
    "version": "1.0.0",
    "type": "PROCESSING",       # IMPORT | PROCESSING | ALERT | EXPORT
    "description": "Converts physical measurement units",
    "author": "admin@example.com",
    "requires_network": False,
    "requires_libs": ["pint"],  # additional pip requirements
}
```
---

## 16. Security Model

### 16.1 Network Security

- All external traffic enters via Nginx reverse proxy (TLS termination in production).
- Backend services communicate over an internal Docker/Kubernetes network only.
- Kafka is not exposed externally.
- Module containers have no network access by default; explicit network grants require TenantAdmin approval.

### 16.2 Authentication & Session

- JWT access tokens expire after 1 hour (3600 s).
- Passwords are SHA-256-hashed client-side before transmission and BCrypt-hashed server-side for storage.
- API keys are stored as BCrypt hashes; plaintext is shown only once at creation.
- Failed login attempts are rate-limited (5 attempts per 15 minutes per IP).

### 16.3 Module Sandboxing

User Python code runs with:

- Non-root user inside the container
- `--read-only` Docker filesystem flag
- No capability grants (`--cap-drop ALL`)
- CPU and memory cgroup limits
- No access to other containers' namespaces
- `/tmp` writable; cleared between batch invocations

### 16.4 Audit Logging

All state-modifying operations are written to a structured audit trail:

```json
{
  "timestamp": "2026-03-16T10:22:00Z",
  "actor": "user:uuid",
  "action": "pipeline.run",
  "resource": "pipeline:uuid",
  "tenantId": "tenant-uuid",
  "ip": "10.0.0.5",
  "result": "success"
}
```
---

## 17. Deployment Topology

### 17.1 Development (Docker Compose)

```text
docker-compose.dev.yml
├── postgresql    (timescale/timescaledb:latest-pg16, port 5432)
├── kafka         (confluentinc/cp-kafka:7.5.0, KRaft, port 9092)
├── redis         (redis:7-alpine, port 6379)
├── mosquitto     (eclipse-mosquitto:2, ports 1883 / 9001 WS)
├── backend       (Quarkus dev mode, port 8080)
├── frontend      (Vite dev server, port 3000)
└── nginx         (port 80 — /api/* → backend, / → frontend)
```
### 17.2 Production (Kubernetes / Kustomize)

```text
k8s/
├── namespace.yaml
├── backend/
│   ├── deployment.yaml      (2+ replicas, rolling update)
│   └── service.yaml
├── frontend/
│   ├── deployment.yaml
│   └── service.yaml
├── kafka/
│   └── (Strimzi operator — 3+ broker cluster)
├── postgres/
│   └── (CloudNativePG or managed RDS / TimescaleDB Cloud)
├── redis/
│   └── deployment.yaml
└── ingress.yaml             (TLS termination)
```
Module containers are spawned by the backend using the Docker API (development) or as Kubernetes Jobs (production).

### 17.3 Port Map (Development)

| Service | Port | Protocol |
| ------- | ---- | -------- |
| Frontend | 3000 | HTTP |
| Backend API | 8080 | HTTP |
| PostgreSQL / TimescaleDB | 5432 | TCP |
| Kafka | 9092 | TCP |
| Redis | 6379 | TCP |
| MQTT | 1883 | TCP |
| MQTT over WebSocket | 9001 | WS |

---

*End of Functional Specification*
