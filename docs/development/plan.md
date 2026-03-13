# DataTurntable - Implementation Plan

## Overview

DataTurntable is a high-performance, scalable data hub for IoT/sensor data processing. It provides:

- **Modular import/export interfaces** (MQTT, HTTP, WebSocket, etc.)
- **Processing layer** with Python user modules
- **TimescaleDB** for time-series data storage
- **Multi-tenant** architecture with fine-grained RBAC
- **Alerting system** with customizable channels

### Performance Targets

- **1B readings/day** (~11,574 reads/sec)
- Horizontal scaling with failover
- Kafka-based message persistence

---

## Architecture

### High-Level System Design

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              DataTurntable                                           │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                         Web Application (React)                              │   │
│  │   Code Editor │ Pipeline Builder │ Tenant Manager │ Alert Config             │   │
│  └───────────────────────────────┬─────────────────────────────────────────────┘   │
│                                  │                                                  │
│  ┌──────────────────────────────▼─────────────────────────────────────────────┐   │
│  │              API Cluster (Quarkus - Multiple Instances)                      │   │
│  │   REST │ Auth/RBAC │ Tenant Mgmt │ Module Registry │ Pipeline Mgmt          │   │
│  └───────────────────────────────┬─────────────────────────────────────────────┘   │
│                                  │ Kafka                                             │
│                                  ▼                                                  │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │              Core Engine Cluster (Quarkus Kafka Consumers)                 │   │
│  │                                                                              │   │
│  │   ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │   │
│  │   │ Import       │──►│ Processing   │──►│ Export       │──►│ Alert        │   │   │
│  │   │ Consumer     │  │ Consumer(s)  │  │ Consumer     │  │ Dispatcher   │   │   │
│  │   │ (sequential) │  │ (sequential)  │  │ (sequential) │  │              │   │   │
│  │   └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘   │   │
│  │                                                                              │   │
│  │   Each pipeline step: 1 consumer max (sequential, no parallel)              │   │
│  │   Multiple pipelines: Each has own consumer group                            │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                  │ Docker/K8s                                        │
│                                  ▼                                                  │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │              User Python Modules (Docker Containers)                         │   │
│  │   Import Module │ Processing Module │ Export Module (user Python)         │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐              │
│  │   Kafka     │  │TimescaleDB │  │ PostgreSQL  │  │   MQTT      │              │
│  │  Cluster    │  │ (time-series)│ │ (metadata) │  │   Broker    │              │
│  │  (3+ nodes) │  │             │  │             │  │             │              │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘              │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### Technology Stack

| Component | Technology | Notes |
|-----------|------------|-------|
| **Core API** | Quarkus (Kotlin) | High performance, low memory |
| **Core Engine** | Quarkus + Kafka Consumer API | Sequential processing |
| **User Modules** | Python in Docker | Isolated execution |
| **Message Queue** | Apache Kafka | Persistent, fault-tolerant |
| **Time-series DB** | TimescaleDB | Primary data storage |
| **Metadata DB** | PostgreSQL | Users, pipelines, code versions |
| **Cache/State** | Redis | Session, cache, rate limiting |
| **MQTT** | EMQX or Mosquitto | Import/export connector |
| **Frontend** | React + TypeScript | Monaco Editor for code |

---

## Project Structure

```
dataturntable/
├── backend/                    # Quarkus (Kotlin) - Core API & Engine
│   ├── src/
│   │   ├── main/kotlin/
│   │   │   └── com/dataturntable/
│   │   │       ├── api/           # REST endpoints
│   │   │       ├── auth/          # Authentication & RBAC
│   │   │       ├── tenant/        # Multi-tenant management
│   │   │       ├── module/        # Code module versioning
│   │   │       ├── pipeline/      # Pipeline management
│   │   │       ├── engine/        # Kafka consumer engine
│   │   │       ├── container/     # Docker management
│   │   │       ├── alerting/      # Alert system
│   │   │       ├── data/          # TimescaleDB data API
│   │   │       └── config/        # Configuration
│   │   └── resources/
│   │       ├── application.yml
│   │       └── db/migration/      # Flyway migrations
│   └── pom.xml
├── frontend/                  # React + TypeScript
│   ├── src/
│   │   ├── components/
│   │   ├── pages/
│   │   ├── hooks/
│   │   ├── services/
│   │   └── stores/
│   └── package.json
├── connectors/                # Pre-built connectors (Python)
├── docker/                    # Docker Compose & K8s configs
└── docs/
    └── development/
        └── plan.md            # This file
```

---

## Database Schema

### Core Tables

```sql
-- Tenants (Recursive Hierarchy)
CREATE TABLE tenants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    parent_id UUID REFERENCES tenants(id),
    settings JSONB DEFAULT '{}',
    quota JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Roles (Tenant-scoped)
CREATE TABLE roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    abilities JSONB NOT NULL DEFAULT '[]',
    is_default BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(tenant_id, name)
);

-- Users
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role_id UUID REFERENCES roles(id),
    abilities JSONB DEFAULT '[]',
    is_superadmin BOOLEAN DEFAULT FALSE,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(tenant_id, email)
);

-- Code Modules (User Python Code)
CREATE TABLE code_modules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    module_type VARCHAR(50) NOT NULL CHECK (module_type IN ('import', 'processing', 'export')),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    created_by UUID REFERENCES users(id),
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
    created_by UUID REFERENCES users(id),
    UNIQUE(module_id, version)
);

-- Pipelines
CREATE TABLE pipelines (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    import_module_version_id UUID REFERENCES module_versions(id),
    processing_module_version_ids UUID[] DEFAULT '{}',
    export_module_version_ids UUID[] DEFAULT '{}',
    kafka_consumer_group VARCHAR(255) UNIQUE,
    is_active BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    created_by UUID REFERENCES users(id)
);

-- Pipeline Runs
CREATE TABLE pipeline_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pipeline_id UUID REFERENCES pipelines(id) ON DELETE CASCADE,
    status VARCHAR(50) DEFAULT 'pending',
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
    channel_type VARCHAR(50) NOT NULL,
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
    severity VARCHAR(50),
    message TEXT,
    payload JSONB,
    sent_at TIMESTAMPTZ DEFAULT NOW(),
    status VARCHAR(50)
);
```

### Indexes

```sql
CREATE INDEX idx_tenant_parent ON tenants(parent_id);
CREATE INDEX idx_users_tenant ON users(tenant_id);
CREATE INDEX idx_modules_tenant ON code_modules(tenant_id);
CREATE INDEX idx_pipelines_tenant ON pipelines(tenant_id);
CREATE INDEX idx_pipeline_runs_pipeline ON pipeline_runs(pipeline_id, started_at DESC);
```

---

## Kafka Topic Design

### Topic Structure

```
dataturntable/
├── pipelines/
│   └── {pipeline_id}/
│       ├── import          # Import module input
│       ├── processing      # Processing module input
│       ├── export          # Export module input
│       └── alerts          # Alert dispatch
├── system/
│   ├── commands            # API commands (trigger pipeline, etc.)
│   └── events              # System events
└── dead-letter/            # Failed messages
```

### Consumer Groups

- Each pipeline gets a dedicated consumer group
- Sequential processing: 1 consumer max per pipeline step
- Prevents parallel execution (user requirement)

---

## Permission System

### Ability-Based Access Control (ABAC)

| Ability | Description |
|---------|-------------|
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
|------|-----------|
| **Viewer** | `module:read`, `pipeline:read`, `data:read`, `alert:read` |
| **Editor** | Viewer + `module:write`, `pipeline:write`, `pipeline:execute`, `data:write` |
| **Tenant Admin** | Editor + `alert:configure`, `tenant:users`, `tenant:manage` |
| **Superadmin** | All abilities + system management |

### Tenant Hierarchy

```
Superadmin (System Admin)
    └── Tenant A (Tenant Admin)
        ├── Sub-Tenant A1 (Tenant Admin)
        │   └── Sub-Tenant A1a
        └── Sub-Tenant A2 (Tenant Admin)
    └── Tenant B (Tenant Admin)
```

- Recursive parent/child relationship
- Users can only access data within their tenant hierarchy
- Permissions inherited or overridden

---

## Module Contract

### Import Module

```python
class ImportModule:
    """Fetches data from external sources."""
    
    def connect(self, config: dict):
        """Initialize connection to data source."""
        pass
    
    def fetch(self) -> dict | pd.DataFrame:
        """Fetch data from source.
        
        Returns:
            dict: Single record
            pd.DataFrame: Batch of records
        """
        pass
    
    def disconnect(self):
        """Cleanup connection."""
        pass
```

### Processing Module

```python
class ProcessingModule:
    """Transforms data and optionally triggers alerts."""
    
    def process(self, data: dict | pd.DataFrame, alert_fn=None) -> dict | pd.DataFrame:
        """Process data.
        
        Args:
            data: Input data (dict or DataFrame)
            alert_fn: Callback function to send alerts
            
        Returns:
            dict or DataFrame: Processed data
        """
        pass

# Alert callback usage:
def process(data, alert_fn):
    if data['value'] > threshold:
        alert_fn(
            severity='critical',
            message='Threshold exceeded',
            payload=data
        )
    return processed_data
```

### Export Module

```python
class ExportModule:
    """Sends data to external destinations."""
    
    def connect(self, config: dict):
        """Initialize connection to destination."""
        pass
    
    def send(self, data: dict | pd.DataFrame):
        """Send data to destination."""
        pass
    
    def disconnect(self):
        """Cleanup connection."""
        pass
```

### Configuration

```python
# Module receives this structure
{
    "source_config": {
        # MQTT: {"broker": "...", "topic": "..."}
        # HTTP: {"url": "..."}
        # Custom: user-defined
    },
    "destination_config": {
        # Similar structure
    },
    "pipeline_id": "uuid",
    "tenant_id": "uuid"
}
```

---

## Execution Flow

### Pipeline Execution

```
1. Data arrives → Kafka topic: pipelines/{id}/import
                  (triggered by MQTT, HTTP, or manual)
                  │
2. Import Consumer (1 instance)
   → Calls user Import Module (Docker)
   → Produces to: pipelines/{id}/processing
   │
3. Processing Consumer (1 instance per pipeline)
   → Calls user Processing Module (Docker)
   → Can trigger alerts → pipelines/{id}/alerts
   → Produces to: pipelines/{id}/export
   │
4. Export Consumer (1 instance)
   → Calls user Export Module (Docker)
   → Marks pipeline run complete
```

### Container Management

| Mode | Description | Use Case |
|------|-------------|----------|
| **Ephemeral** | New container per run | Batch imports, one-off processing |
| **Persistent** | Long-running container | Live streaming (MQTT → process → export) |

---

## API Endpoints

### Authentication

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | /auth/login | Login |
| POST | /auth/register | Register |
| GET | /auth/me | Current user |

### Tenants

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | /tenants | List tenants |
| POST | /tenants | Create tenant |
| GET | /tenants/{id} | Get tenant |
| PATCH | /tenants/{id} | Update tenant |
| DELETE | /tenants/{id} | Delete tenant |

### Users & Roles

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | /users | List users |
| POST | /users | Create user |
| PATCH | /users/{id} | Update user |
| DELETE | /users/{id} | Delete user |
| GET | /roles | List roles |
| POST | /roles | Create role |
| PATCH | /roles/{id} | Update role |

### Modules

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | /modules | List modules |
| POST | /modules | Create module |
| GET | /modules/{id} | Get module |
| DELETE | /modules/{id} | Delete module |
| GET | /modules/{id}/versions | List versions |
| POST | /modules/{id}/versions | Create version |
| POST | /modules/{id}/versions/{v}/tag | Tag version |

### Pipelines

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | /pipelines | List pipelines |
| POST | /pipelines | Create pipeline |
| GET | /pipelines/{id} | Get pipeline |
| PATCH | /pipelines/{id} | Update pipeline |
| DELETE | /pipelines/{id} | Delete pipeline |
| POST | /pipelines/{id}/run | Trigger pipeline |
| GET | /pipelines/{id}/runs | List runs |

### Data

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | /data/{tenant_id}/query | Query time-series |
| POST | /data/{tenant_id}/insert | Insert data |

### Alerts

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | /alerts/channels | List channels |
| POST | /alerts/channels | Create channel |
| GET | /alerts/rules | List rules |
| POST | /alerts/rules | Create rule |
| GET | /alerts/history | Alert history |

---

## Deployment

### Development (Docker Compose)

```yaml
# docker-compose.dev.yml
services:
  postgresql:
    image: timescale/timescaledb:latest-pg16
    environment:
      POSTGRES_PASSWORD: dev
    volumes:
      - pgdata:/var/lib/postgresql/data

  kafka:
    image: confluentinc/cp-kafka:latest
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1

  redis:
    image: redis:alpine

  mosquitto:
    image: eclipse-mosquitto
    ports:
      - "1883:1883"

  backend:
    build: ./backend
    ports:
      - "8080:8080"
    depends_on:
      - postgresql
      - kafka
      - redis

  frontend:
    build: ./frontend
    ports:
      - "3000:3000"
    depends_on:
      - backend
```

### Production (Kubernetes)

- Kafka cluster (3+ brokers)
- TimescaleDB (or Timescale Cloud)
- PostgreSQL
- Redis cluster
- API deployment (HPA)
- Worker deployment (1 replica per pipeline)
- Ingress with OAuth2

---

## Implementation Phases

| Phase | Component | Priority |
|-------|-----------|----------|
| 1 | DB Schema + Migrations | High |
| 2 | Auth + RBAC | High |
| 3 | Tenant Management | High |
| 4 | Module CRUD + Versioning | High |
| 5 | Pipeline CRUD | High |
| 6 | Kafka Consumer Engine | High |
| 7 | Docker Manager | High |
| 8 | Alert System | Medium |
| 9 | Data API | Medium |
| 10 | Frontend | Medium |
| 11 | Deployment configs | Medium |

---

## Security Considerations

1. **User Code Isolation**: All user Python modules run in isolated Docker containers
2. **No Default Data Structures**: Modules define their own data format
3. **RBAC**: Fine-grained ability-based permissions
4. **Tenant Isolation**: Data access restricted to tenant hierarchy
5. **Secrets**: Alert channel credentials encrypted in database

---

## Performance Optimizations

1. **Kafka**: 3+ broker cluster, replication factor 3
2. **TimescaleDB**: Hypertables with proper chunking
3. **Sequential Processing**: 1 consumer per pipeline step (prevents race conditions)
4. **Connection Pooling**: PostgreSQL and Redis
5. **Batch Processing**: DataFrame support for bulk operations

---

## Monitoring & Observability

- **Metrics**: Prometheus + Grafana
- **Logging**: ELK Stack or Loki
- **Tracing**: Jaeger or Tempo
- **Health Checks**: Kubernetes readiness/liveness probes
