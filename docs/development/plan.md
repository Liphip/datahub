# DataTurntable — Implementation Plan

## Overview

DataTurntable is a high-performance, scalable data hub for IoT/sensor data processing. It provides:

- **Modular connectors** — Hot-pluggable MQTT, HTTP/REST, WebSocket, UDP and custom connectors. The system exposes protocol endpoints for external systems to push data to, and can also actively pull from external sources. On the output side it pushes to external endpoints or exposes pull endpoints.
- **Graph-based processing pipelines** — Non-linear DAG pipelines: fan-out (one module feeds multiple downstream modules) and fan-in (combination module merges multiple streams)
- **Four Python module types** — IMPORT, PROCESSING, ALERT, EXPORT; each with a defined interface contract
- **TimescaleDB** for time-series data storage
- **Multi-tenant** architecture with fine-grained RBAC
- **Alerting system** — Dedicated ALERT modules with built-in rule types and custom Python logic; multiple dispatch channels

### Performance Targets

- **1B readings/day** (~11,574 reads/sec)
- Horizontal scaling with failover
- Kafka-based message persistence

---

## Current Implementation Notes (Users + Connectors)

- Management API now exposes `/auth-users` for platform login identities and `/tenants/{tenantId}/memberships` for tenant access assignments.
- Membership API supports `PATCH` updates and rejects duplicate `(authUserId, tenantId)` assignments.
- Root-tenant admins are prevented from receiving sub-tenant memberships.
- Connector configs remain encrypted at rest and sensitive keys (`password`, `secret`, `token`, `apiKey`, etc.) are masked in list/get responses.
- Pipeline create/update accepts `graphDefinition`; IMPORT/EXPORT nodes are validated to require `connectorId` and compatible connector direction.
- Frontend pipeline editor includes connector pickers for IMPORT/EXPORT nodes and submits connector-aware DAG definitions.

## Architecture

### High-Level System Design

```text
┌────────────────────────────────────────────────────────────────────────────────┐
│                              DataTurntable                                      │
│                                                                                 │
│  ┌─────────────────────────────────────────────────────────────────────────┐   │
│  │                    Web Application (React + TypeScript)                  │   │
│  │   Code Editor │ Pipeline Builder │ Tenant Manager │ Alert Config         │   │
│  └──────────────────────────────────┬──────────────────────────────────────┘   │
│                                     │                                           │
│  ┌──────────────────────────────────▼──────────────────────────────────────┐   │
│  │              Management API (Quarkus/Kotlin - /api/internal/v1/)         │   │
│  │   Auth/RBAC │ Tenant Mgmt │ Module Registry │ Pipeline Mgmt             │   │
│  └──────────────────────────────────┬──────────────────────────────────────┘   │
│                                     │ Kafka                                     │
│  ┌──────────────────────────────────▼──────────────────────────────────────┐   │
│  │                    Ingestion Layer (/api/v1/ + MQTT + WS + UDP)          │   │
│  │   Exposes protocol endpoints; routes incoming data into pipeline topics  │   │
│  └──────────────────────────────────┬──────────────────────────────────────┘   │
│                                     │                                           │
│  ┌──────────────────────────────────▼──────────────────────────────────────┐   │
│  │              Core Engine Cluster (Quarkus Kafka Consumers)               │   │
│  │                                                                          │   │
│  │   Pipeline graph — each node is a module container; edges are topics:    │   │
│  │                                                                          │   │
│  │   [IMPORT] ──► [PROCESSING] ──┬──► [ALERT]                              │   │
│  │                               │                                          │   │
│  │   [IMPORT] ──► [PROCESSING] ──┤    [PROCESSING] (combine/fan-in)        │   │
│  │   (2nd stream) ───────────────┘         │                               │   │
│  │                                         ├──► [EXPORT]                   │   │
│  │                                         └──► [EXPORT]  (fan-out)        │   │
│  └──────────────────────────────────┬──────────────────────────────────────┘   │
│                                     │ Docker API                                │
│  ┌──────────────────────────────────▼──────────────────────────────────────┐   │
│  │              User Python Modules (Docker Containers)                     │   │
│  │   IMPORT Module │ PROCESSING Module │ ALERT Module │ EXPORT Module       │   │
│  └──────────────────────────────────────────────────────────────────────────┘  │
│                                                                                 │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐          │
│  │   Kafka     │  │TimescaleDB  │  │ PostgreSQL  │  │   MQTT      │          │
│  │  Cluster    │  │ (time-series)│  │ (metadata) │  │   Broker    │          │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘          │
└────────────────────────────────────────────────────────────────────────────────┘
```
### Technology Stack

| Component | Technology | Notes |
| --------- | ---------- | ----- |
| **Core API** | Quarkus (Kotlin) | High performance, low memory |
| **Core Engine** | Quarkus + Kafka Consumer API | Graph-based pipeline execution |
| **User Modules** | Python 3.11 in Docker | Isolated execution |
| **Message Queue** | Apache Kafka (KRaft) | Persistent, fault-tolerant |
| **Time-series DB** | TimescaleDB | Primary data storage |
| **Metadata DB** | PostgreSQL | Users, pipelines, module source, config |
| **Cache/State** | Redis | Session, dedup, rate limiting |
| **MQTT** | Eclipse Mosquitto | Inbound (subscribed by platform) + outbound |
| **Frontend** | React + TypeScript | Tailwind CSS, Monaco Editor, Vite |

---

## Connector & Module System

### Import (IMPORT) — Inbound Data

An IMPORT module defines how data enters a pipeline. There are two patterns:

**Passive (push from external):** The platform exposes protocol endpoints; external systems push data in.

- REST: `POST /api/v1/ingest/{pipelineId}` (no schema enforced)
- MQTT: Platform subscribes to a configured topic on the bundled or external broker
- WebSocket: Platform provides a WS endpoint; external clients connect and stream frames
- UDP: Platform opens a UDP socket on a configurable port

**Active (pull by platform):** The IMPORT Python module runs in a container and periodically fetches data from an external source (REST poll, database query, file watch, etc.).

### Processing (PROCESSING) — Transform / Filter / Enrich

A PROCESSING module receives data from any upstream node, transforms it, and emits to one or more downstream nodes. Combination (fan-in) is implemented as a PROCESSING module that receives from multiple upstream topics.

### Alert (ALERT) — Condition Evaluation

A dedicated ALERT module evaluates conditions on the data stream and fires alerts via configured channels. It does not modify or forward the data stream — it is a side-effect node. Both built-in rule types (threshold, rate-of-change, time window, absence) and custom Python logic are supported.

### Export (EXPORT) — Outbound Data

An EXPORT module sends data out of the platform. Two patterns:

**Active (push to external):** Module pushes to an external endpoint.

- REST: POST to external API
- MQTT: Publish to external broker topic
- WebSocket: Push frames to connected clients
- UDP: Send datagrams to configured target

**Passive (expose pull endpoint):** The platform exposes an endpoint that external systems poll (e.g. Grafana SimpleJSON data source, ThingsBoard MQTT pull, query endpoint).

---

## Pipeline Topology (DAG)

Pipelines are directed acyclic graphs. Nodes are module instances; edges are Kafka topics.

### Fan-out Example

```text
[IMPORT: MQTT] ──► [PROCESSING: Normalise] ──┬──► [ALERT: Threshold]
                                              ├──► [EXPORT: TimescaleDB]
                                              └──► [EXPORT: MQTT Dashboard]
```
### Fan-in Example

```text
[IMPORT: MQTT sensor A] ──► [PROCESSING: Parse A] ──┐
                                                      ├──► [PROCESSING: Merge] ──► [EXPORT: DB]
[IMPORT: HTTP feed B]   ──► [PROCESSING: Parse B] ──┘
```
### Kafka Topic Per Edge

Each directed edge in the graph has its own Kafka topic:

```text
dataturntable.pipelines.{pipelineId}.nodes.{sourceNodeId}.to.{targetNodeId}
```
System-level topics:

| Topic | Purpose |
| ----- | ------- |
| `dataturntable.pipelines.{id}.input` | Pipeline entry point (ingestion layer → first import node) |
| `dataturntable.pipelines.{id}.nodes.{src}.to.{dst}` | Edge between two nodes |
| `dataturntable.alerts.{tenantId}` | Alert events |
| `dataturntable.system.commands` | API → engine commands |
| `dataturntable.dlq.{pipelineId}` | Dead-letter queue |

---

## Database Schema

### Core Tables (PostgreSQL)

```sql
-- Tenants (strict tree: each node has exactly one parent, except the root which has parent_id = NULL)
-- Cycles and multiple parents are forbidden; enforced in application logic.
CREATE TABLE tenants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    parent_id UUID REFERENCES tenants(id),   -- NULL only for the single root tenant
    settings JSONB DEFAULT '{}',
    quota JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Roles (tenant-scoped)
CREATE TABLE roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    abilities JSONB NOT NULL DEFAULT '[]',
    is_default BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(tenant_id, name)
);

-- Login identities (platform-wide, one record per unique email)
-- Superadmin = auth_user who holds a TenantAdmin membership in the root tenant.
-- Root-tenant admins cannot simultaneously hold memberships in sub-tenants.
CREATE TABLE auth_users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,   -- BCrypt of SHA-256 pre-hash
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Per-tenant memberships (max 1 per auth_user per tenant)
CREATE TABLE tenant_memberships (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    auth_user_id UUID REFERENCES auth_users(id) ON DELETE CASCADE,
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    role_id UUID REFERENCES roles(id),
    abilities JSONB DEFAULT '[]',          -- per-membership ability overrides
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(auth_user_id, tenant_id)        -- one membership per (user, tenant)
);

-- Protocol Connectors (tenant-scoped, named interface definitions)
-- Import/Export module nodes reference a connector by connectorId in graph_definition.
CREATE TABLE connectors (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    connector_type VARCHAR(50) NOT NULL
        CHECK (connector_type IN ('MQTT', 'REST', 'WEBSOCKET', 'UDP', 'FILE', 'CUSTOM')),
    direction VARCHAR(20) NOT NULL
        CHECK (direction IN ('INPUT', 'OUTPUT', 'BOTH')),
    config JSONB NOT NULL,   -- type-specific: host, port, topic, credentials, etc.
    enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(tenant_id, name)
);

-- Code Modules
CREATE TABLE code_modules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    module_type VARCHAR(50) NOT NULL
        CHECK (module_type IN ('IMPORT', 'PROCESSING', 'ALERT', 'EXPORT')),
    description TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    created_by UUID REFERENCES auth_users(id),
    UNIQUE(tenant_id, name, module_type)
);

-- Module Versions
CREATE TABLE module_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    module_id UUID REFERENCES code_modules(id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    version_tag VARCHAR(100),
    code TEXT NOT NULL,
    requirements TEXT DEFAULT '',
    created_at TIMESTAMPTZ DEFAULT NOW(),
    created_by UUID REFERENCES auth_users(id),
    UNIQUE(module_id, version)
);

-- Pipelines (graph stored as JSON graph definition; arrays kept for MVP compat)
-- graph_definition nodes include connectorId for IMPORT/EXPORT nodes:
--   { "id": "n1", "type": "IMPORT", "moduleVersionId": "uuid", "connectorId": "uuid" }
CREATE TABLE pipelines (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    graph_definition JSONB,              -- full DAG: nodes + edges
    import_module_version_id UUID REFERENCES module_versions(id),
    processing_module_version_ids UUID[] DEFAULT '{}',
    export_module_version_ids UUID[] DEFAULT '{}',
    kafka_consumer_group VARCHAR(255) UNIQUE,
    is_active BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    created_by UUID REFERENCES auth_users(id)
);

-- Pipeline Runs
CREATE TABLE pipeline_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pipeline_id UUID REFERENCES pipelines(id) ON DELETE CASCADE,
    status VARCHAR(50) DEFAULT 'PENDING',  -- PENDING, RUNNING, SUCCESS, FAILED, CANCELLED
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    error_message TEXT,
    UNIQUE(pipeline_id, started_at)
);

-- Alert Channels
CREATE TABLE alert_channels (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    channel_type VARCHAR(50) NOT NULL,  -- MQTT, WEBHOOK, REST, EMAIL, SLACK
    config JSONB NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Alert Rules
CREATE TABLE alert_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    pipeline_id UUID REFERENCES pipelines(id) ON DELETE SET NULL,
    channel_id UUID REFERENCES alert_channels(id),
    enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Alert History
CREATE TABLE alert_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    rule_id UUID REFERENCES alert_rules(id),
    severity VARCHAR(50),       -- info, warning, critical
    message TEXT,
    payload JSONB,
    status VARCHAR(50),         -- PENDING, SENT, FAILED, RETRY
    sent_at TIMESTAMPTZ DEFAULT NOW()
);
```
### Indexes

```sql
CREATE INDEX idx_tenant_parent ON tenants(parent_id);
CREATE INDEX idx_memberships_auth_user ON tenant_memberships(auth_user_id);
CREATE INDEX idx_memberships_tenant ON tenant_memberships(tenant_id);
CREATE INDEX idx_connectors_tenant ON connectors(tenant_id);
CREATE INDEX idx_modules_tenant ON code_modules(tenant_id);
CREATE INDEX idx_pipelines_tenant ON pipelines(tenant_id);
CREATE INDEX idx_pipeline_runs_pipeline ON pipeline_runs(pipeline_id, started_at DESC);
```
### Time-Series Data (TimescaleDB)

```sql
CREATE TABLE readings (
    time        TIMESTAMPTZ NOT NULL,
    tenant_id   UUID NOT NULL,
    pipeline_id UUID NOT NULL,
    metric      TEXT NOT NULL,
    value       DOUBLE PRECISION,
    tags        JSONB,
    raw         JSONB
);
SELECT create_hypertable('readings', 'time');
CREATE INDEX ON readings(tenant_id, metric, time DESC);
```
---

## Module Contracts (Python)

All user modules run inside Docker containers. The platform injects helper functions (`alert_fn`, `query_fn`) via environment/sidecar.

### IMPORT Module

```python
class ImportModule:
    """Fetches or receives data from external sources."""

    def connect(self, config: dict):
        """Initialise connection to the data source."""
        pass

    def fetch(self) -> dict | pd.DataFrame:
        """Fetch one batch of data.

        Returns:
            dict: single record
            pd.DataFrame: batch of records
        """
        pass

    def disconnect(self):
        """Clean up connection."""
        pass
```
### PROCESSING Module

```python
class ProcessingModule:
    """Transforms, filters, or enriches data."""

    def process(self, data: dict | pd.DataFrame,
                query_fn=None) -> dict | pd.DataFrame | None:
        """Transform data.

        Return None to drop the message.
        Raise an exception to route to DLQ.
        """
        return data
```
### ALERT Module

```python
class AlertModule:
    """Evaluates conditions and dispatches alerts.

    Does NOT modify or forward the data stream.
    Runs as a side-effect node in the pipeline graph.
    """

    def evaluate(self, data: dict | pd.DataFrame,
                 alert_fn=None,
                 query_fn=None) -> bool:
        """Evaluate alert condition.

        Call alert_fn(severity, message, payload) to fire an alert.
        Return True if alert fired, False otherwise.

        Example:
            if data['value'] > 80:
                alert_fn(severity='critical',
                         message='Temperature exceeded 80°C',
                         payload=data)
                return True
            return False
        """
        return False
```
### EXPORT Module

```python
class ExportModule:
    """Sends data to external destinations or exposes pull endpoints."""

    def connect(self, config: dict):
        """Initialise connection to the destination."""
        pass

    def send(self, data: dict | pd.DataFrame):
        """Push data to the external system."""
        pass

    def disconnect(self):
        """Clean up connection."""
        pass
```
### Module Config Structure

The platform resolves the referenced connector and injects its config into the module at runtime:

```python
# Injected into module __init__ config parameter
{
    "connector": {
        # Resolved from connectors.config for the referenced connectorId.
        # MQTT:      {"broker": "mqtt://host:1883", "topic": "sensors/#", "clientId": "..."}
        # REST:      {"baseUrl": "https://...", "method": "POST", "headers": {...}}
        # WEBSOCKET: {"url": "wss://...", "path": "/stream"}
        # UDP:       {"host": "...", "port": 5005}
        # FILE:      {"path": "/data/input.csv", "format": "csv"}
        # CUSTOM:    user-defined key/value pairs
    },
    "pipeline_id": "uuid",
    "tenant_id": "uuid",
    "node_id": "uuid"
}
```
---

## Execution Flow

### Pipeline Start

```text
1. External system pushes data to an ingest endpoint:
      - POST /api/v1/ingest/{pipelineId}
      - MQTT topic: dataturntable/ingest/{pipelineId}
      - WebSocket frame / UDP datagram
   OR
   IMPORT module container polls external source and emits data

2. Ingestion layer publishes to Kafka:
      dataturntable.pipelines.{id}.input

3. Pipeline Engine reads message, resolves graph:
      For each outgoing edge from the entry node, publish to edge topic.

4. Each node's Kafka consumer calls its module container:
      - PROCESSING: transforms data, emits to all downstream edge topics
      - ALERT: evaluates condition, fires alert if condition met (does not forward)
      - EXPORT: pushes to external system or stores data

5. On error: message routed to DLQ topic.
6. PipelineRun record updated to SUCCESS / FAILED.
```
### Container Lifecycle

| Mode | Description | Use Case |
| ---- | ----------- | -------- |
| **Ephemeral** | New container per run | Batch imports, one-off processing |
| **Persistent** | Long-running container | Live streaming (MQTT → process → export) |

---

## Permission System

### Ability-Based Access Control (ABAC)

| Ability | Description |
| ------- | ----------- |
| `module:read` | View code modules |
| `module:write` | Create/edit code modules |
| `module:delete` | Delete code modules |
| `pipeline:read` | View pipelines |
| `pipeline:write` | Create/edit pipelines |
| `pipeline:execute` | Run pipelines |
| `data:read` | Query time-series data |
| `data:write` | Insert data |
| `data:export` | Export data |
| `alert:read` | View alerts |
| `alert:configure` | Configure alert channels/rules |
| `tenant:users` | Manage tenant users |
| `tenant:manage` | Manage tenant settings |
| `user:manage` | System user management |

### Default Roles

| Role | Abilities |
| ---- | --------- |
| **Viewer** | `module:read`, `pipeline:read`, `data:read`, `alert:read` |
| **Editor** | Viewer + `module:write`, `pipeline:write`, `pipeline:execute`, `data:write` |
| **TenantAdmin** | Editor + `alert:configure`, `tenant:users`, `tenant:manage` |
| **Superadmin** | All abilities + system management |

### Tenant Hierarchy

```text
Superadmin (platform-wide)
  └── Tenant A (TenantAdmin)
      ├── Sub-Tenant A1 (TenantAdmin)
      │   └── Sub-Tenant A1a
      └── Sub-Tenant A2 (TenantAdmin)
  └── Tenant B (TenantAdmin)
```
Permissions are inherited from parent tenants and can be overridden at any level.

---

## API Endpoints

### Authentication

| Method | Endpoint | Description |
| ------ | -------- | ----------- |
| POST | /auth/login | Login, receive JWT |
| POST | /auth/setup | First-run setup (creates root tenant + admin) |
| GET | /auth/me | Current user info |

### Tenants

| Method | Endpoint | Description |
| ------ | -------- | ----------- |
| GET | /tenants | List root tenants |
| POST | /tenants | Create tenant |
| GET | /tenants/{id} | Get tenant |
| PATCH | /tenants/{id} | Update tenant |
| DELETE | /tenants/{id} | Delete tenant |
| GET | /tenants/{id}/children | List child tenants |

### Users & Roles

| Method | Endpoint | Description |
| ------ | -------- | ----------- |
| GET | /users | List users |
| POST | /users | Create user |
| GET | /users/{id} | Get user |
| PATCH | /users/{id} | Update user |
| DELETE | /users/{id} | Delete user |
| GET | /roles | List roles |

### Modules

| Method | Endpoint | Description |
| ------ | -------- | ----------- |
| GET | /modules | List modules |
| POST | /modules | Create module |
| GET | /modules/{id} | Get module |
| DELETE | /modules/{id} | Delete module |
| GET | /modules/{id}/versions | List versions |
| POST | /modules/{id}/versions | Create version |

### Pipelines

| Method | Endpoint | Description |
| ------ | -------- | ----------- |
| GET | /pipelines | List pipelines |
| POST | /pipelines | Create pipeline |
| GET | /pipelines/{id} | Get pipeline |
| PATCH | /pipelines/{id} | Update pipeline |
| DELETE | /pipelines/{id} | Delete pipeline |
| POST | /pipelines/{id}/run | Trigger pipeline |
| GET | /pipelines/{id}/runs | List runs |

### Data

| Method | Endpoint | Description |
| ------ | -------- | ----------- |
| GET | /data/{tenantId}/query | Query time-series |
| POST | /data/{tenantId}/insert | Insert data |
| GET | /data/{tenantId}/tables | List tables |
| DELETE | /data/{tenantId}/tables/{table} | Drop table |

### Alerts

| Method | Endpoint | Description |
| ------ | -------- | ----------- |
| GET | /alerts/channels | List channels |
| POST | /alerts/channels | Create channel |
| GET | /alerts/rules | List rules |
| POST | /alerts/rules | Create rule |
| GET | /alerts/history | Alert history |

All paths are prefixed with `/api/internal/v1/`.

---

## Deployment

### Development (Docker Compose)

```yaml
services:
  postgresql:
    image: timescale/timescaledb:latest-pg16
    environment:
      POSTGRES_PASSWORD: dataturntable

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1

  redis:
    image: redis:7-alpine

  mosquitto:
    image: eclipse-mosquitto:2
    ports:
      - "1883:1883"

  backend:
    build: ./backend
    ports:
      - "8080:8080"

  frontend:
    build: ./frontend
    ports:
      - "3000:3000"
```
### Production (Kubernetes)

- Kafka cluster (3+ brokers, Strimzi operator)
- TimescaleDB (CloudNativePG or managed)
- PostgreSQL
- Redis cluster
- API deployment (HPA, 2+ replicas)
- Module containers spawned as Kubernetes Jobs
- Ingress with TLS termination

---

## Implementation Phases

| Phase | Component | Priority |
| ----- | --------- | -------- |
| 1 | DB Schema + Migrations | High |
| 2 | Auth + RBAC | High |
| 3 | Tenant Management | High |
| 4 | Module CRUD + Versioning | High |
| 5 | Pipeline CRUD | High |
| 6 | Kafka Consumer Engine | High |
| 7 | Docker Manager (module execution) | High |
| 8 | Alert System (ALERT module type + channels) | Medium |
| 9 | Data API | Medium |
| 10 | Frontend | Medium |
| 11 | Ingestion Layer (REST + MQTT + WS + UDP) | Medium |
| 12 | Full graph pipeline topology (DAG) | Medium |
| 13 | Deployment configs (K8s, Helm) | Medium |

---

## Security Considerations

1. **User code isolation**: All Python modules run in isolated Docker containers (no-root, read-only FS, no network by default)
2. **No fixed data schema**: Modules define their own data format; the platform does not enforce structure
3. **RBAC**: Fine-grained ability-based permissions scoped to tenant hierarchy
4. **Tenant isolation**: Data access restricted to tenant tree
5. **Secrets**: Alert channel credentials stored encrypted in the database
6. **Password hashing**: SHA-256 (client) + BCrypt (server)
7. **Deduplication**: System-generated duplicates (Kafka at-least-once retries) are suppressed; user data duplicates are preserved unless a deduplication PROCESSING module is added to the pipeline

---

## Performance Optimisations

1. **Kafka**: 3+ broker cluster, replication factor 3
2. **TimescaleDB**: Hypertables with proper chunking and compression
3. **Sequential node processing**: 1 consumer per pipeline node (prevents race conditions within a node)
4. **Parallel pipelines**: Each pipeline has its own consumer group; pipelines run independently
5. **Connection pooling**: PostgreSQL and Redis
6. **Batch processing**: DataFrame support for bulk operations

---

## Monitoring & Observability

- **Metrics**: Prometheus (`/q/metrics`) + Grafana
- **Logging**: Structured JSON logs; ELK Stack or Loki
- **Tracing**: Jaeger or Tempo (planned)
- **Health checks**: `/q/health/ready` and `/q/health/live` (Quarkus SmallRye Health)
