# DataTurntable

A high-performance, scalable data hub for IoT/sensor data processing with modular import/export connectors, a graph-based processing pipeline, Python user modules, and multi-tenant architecture.

## Features

- **Modular Connectors**: Hot-pluggable connectors for MQTT, HTTP/REST, WebSocket, UDP and more — both inbound (expose endpoints for other systems to push to) and outbound (push to or expose endpoints for other systems to pull from)
- **Graph Pipeline**: Non-linear processing pipelines — fan-in (combine multiple streams), fan-out (one module feeds multiple downstream modules), and any DAG topology
- **Processing Layer**: User-defined Python modules for data transformation, filtering, and enrichment
- **ML Integration**: Run Python ML models (PyTorch, scikit-learn, etc.) in processing modules
- **Alert Modules**: Dedicated alert module type with custom Python logic or built-in rules (threshold, rate-of-change, time window, absence)
- **TimescaleDB**: Optimised for time-series data storage
- **Multi-Tenant**: Hierarchical tenant model with fine-grained RBAC
- **Alert Channels**: Configurable alerts via MQTT, webhook, REST, email, Slack
- **High Performance**: Handles 1B+ readings/day (~11,574 reads/sec)
- **Horizontal Scaling**: Kubernetes-ready with auto-scaling

## Architecture

```text
External Systems                  DataTurntable                        External Systems
────────────────                  ─────────────────────────────────    ────────────────
                                  ┌──────────────────────────────┐
Push to REST endpoint ──────────► │  Import Layer                │
Push to MQTT topic    ──────────► │  (exposes REST, MQTT, WS,    │
Push to WebSocket     ──────────► │   UDP endpoints;             │
                                  │   or pulls from sources)     │
                      ◄────────── │  /api/v1/ ingestion path     │
                                  └──────────────┬───────────────┘
                                                 │ Kafka
                                  ┌──────────────▼───────────────┐
                                  │  Pipeline Engine              │
                                  │                               │
                                  │  [Import] ──► [Process] ──┬──► [Alert]
                                  │                 │          │
                                  │                 ▼          └──► [Export] ──► Push to REST
                                  │           [Process]                          Push to MQTT
                                  │                 │                            Push to WS/UDP
                                  │        ┌────────┴────────┐    or exposes
                                  │        ▼                 ▼    pull endpoint
                                  │    [Export]          [Export]
                                  └──────────────────────────────┘
                                  ┌──────────────────────────────┐
                                  │  Storage                     │
                                  │  TimescaleDB │ PostgreSQL     │
                                  └──────────────────────────────┘
                                  ┌──────────────────────────────┐
                                  │  Admin UI (React/TypeScript) │
                                  │  Pipeline Builder │ Code Ed. │
                                  └──────────────────────────────┘
```

## Quick Start

### Prerequisites

- Docker & Docker Compose
- Java 25 (for backend development)
- Node.js 22.12+ (for frontend development)
- Maven 3.9+ (for backend build)

### Development Setup

1. Clone the repository:

    ```bash
    git clone https://github.com/your-repo/dataturntable.git
    cd dataturntable
    ```

2. Start the development environment:

    ```bash
    cd docker
    docker-compose -f docker-compose.dev.yml up -d
    ```

3. Access the services:

    - Frontend: http://localhost:3000
    - Internal control API: http://localhost/api/internal/v1
    - General-purpose API: http://localhost/api/v1
    - Ingestion API: http://localhost/api/ingest/v1
    - PostgreSQL/TimescaleDB: localhost:5432
    - Kafka: localhost:9092
    - MQTT Broker: localhost:1883
    - Public API metrics: http://localhost/q/metrics
    - Prometheus: http://localhost:9090
    - Monitoring Grafana (direct): http://localhost:3001 (development credentials are configured by Compose only)
    - Monitoring Grafana (via gateway): http://monitoring.localhost
    - Monitoring fallback route: http://localhost/monitoring
    - Dashboard Grafana (direct): http://localhost:3003 (development credentials are configured by Compose only)
    - Dashboard Grafana (via gateway): http://dashboard.localhost
    - Dashboard fallback route: http://localhost/dashboard
    - Docs (direct): http://localhost:3002
    - Docs (via gateway): http://docs.localhost
    - Docs fallback route: http://localhost/docs
    - Loki API: http://localhost:3100

### Building from Source

Backend:

```bash
cd backend
mvn package
```

Frontend:

```bash
cd frontend
npm install
npm run build
```

## API Paths

| Path | Purpose |
|------|---------|
| `/api/internal/v1/` | Frontend/internal control API for auth, tenants, modules, connectors, and pipeline administration |
| `/api/v1/` | General-purpose API for alerts, monitoring, data access, and automation clients |
| `/api/ingest/v1/` | Ingestion API for external systems pushing data into pipelines |

## Module Contract

Users write Python modules for each stage. The platform passes data between modules via Kafka. Pipelines are graphs — one module's output can feed multiple downstream modules, and multiple streams can be combined.

### Processing Module (transform/filter/enrich)
```python
class ProcessingModule:
    def process(self, data: dict | pd.DataFrame,
                query_fn=None) -> dict | pd.DataFrame:
        # Transform data; return None to drop message
        return data
```
### Alert Module (dedicated alert evaluation)
```python
class AlertModule:
    def evaluate(self, data: dict | pd.DataFrame,
                 alert_fn=None, query_fn=None) -> bool:
        # Call alert_fn(severity, message, payload) to fire alert
        if data['value'] > threshold:
            alert_fn(severity='critical',
                     message='Threshold exceeded', payload=data)
        return True
```
### Import Module (custom data source)
```python
class ImportModule:
    def connect(self, config: dict): ...
    def fetch(self) -> dict | pd.DataFrame: ...
    def disconnect(self): ...
```
### Export Module (custom data sink)
```python
class ExportModule:
    def connect(self, config: dict): ...
    def send(self, data: dict | pd.DataFrame): ...
    def disconnect(self): ...
```
### DB Load Module (pipeline-native table reader)
```python
class DbLoadModule:
    def metadata(self) -> dict:
        return {"filters": [], "limit": 1000}

    def load(self, query: dict, context: dict) -> list[dict] | pd.DataFrame:
        return []
```
### DB Save Module (pipeline-native table writer)
```python
class DbSaveModule:
    def metadata(self) -> dict:
        return {"mode": "append"}

    def prepare(self, data: dict | pd.DataFrame, context: dict):
        return data

    def save(self, rows, context: dict) -> dict:
        return {"written": len(rows)}
```

DB modules are executed by the platform, not by arbitrary module containers:
- `DB_LOAD` reads from tenant-scoped schemas.
- `DB_SAVE` writes to tenant-scoped schemas.
- Tables are created implicitly on first write if missing.
- The default schema naming model is `tenant_<tenant_uuid_with_underscores>`.
- If `DATATURNTABLE_PIPELINE_DATA_JDBC_*` is not configured, DB modules fall back to the main app datasource.
- The greenfield schema now includes `data_table_registry`, `data_blobs`, and `pipeline_run_node_logs` so table registration, blob workflows, and per-node run logs are persisted from first boot.

## Module Versioning

- Module versions are created only when versioned content changes.
- Versioned content includes code, dependency manifest, config payload, and version tag.
- Editing display metadata does not force a new version.
- Pipeline nodes select the module first and the module version second.
- IMPORT nodes can use `IMPORT` or `DB_LOAD` modules.
- EXPORT nodes can use `EXPORT` or `DB_SAVE` modules.
## Pipeline Topology

Pipelines are directed graphs (DAGs), not just linear chains:

```text
[MQTT Import] ──► [Normalise] ──► [Anomaly Detect] ──┬──► [Alert: High Temp]
                                                       ├──► [Export: TimescaleDB]
[HTTP Import] ──► [Parse CSV] ──► [Merge Streams] ────┘    [Export: MQTT Dashboard]
```
## API Endpoints (Management)

| Endpoint | Description |
|----------|-------------|
| POST /auth/login | User authentication |
| POST /auth/setup | First-run platform setup |
| GET/POST/PATCH/DELETE /auth-users | Platform auth user management |
| GET/POST/PATCH/DELETE /tenants/{id}/memberships | Tenant membership management |
| GET/POST/PATCH/DELETE /connectors | Tenant connector library |
| GET /connectors/mqtt/defaults | Integrated MQTT broker connection defaults |
| GET/POST /connectors/mqtt/credentials | List/create tenant-scoped broker users |
| PATCH/DELETE /connectors/mqtt/credentials/{id} | Update/delete broker user |
| POST /connectors/mqtt/credentials/{id}/rotate-password | Rotate broker user password |
| POST /connectors/{id}/connect | Mark connector connected/enabled |
| POST /connectors/{id}/disconnect | Mark connector disconnected/disabled |
| POST /connectors/{id}/test | Validate connector config |
| GET /tenants | List tenants |
| GET /modules | List code modules |
| GET /pipelines | List pipelines |
| POST /pipelines/{id}/run | Trigger pipeline |
| GET /grafana/sync-states | Inspect queued dashboard/monitoring Grafana sync state |
| POST /grafana/reconcile | Rebuild desired Grafana tenant/user/membership sync state |
| POST /grafana/apply-pending | Apply pending Grafana sync state to configured dashboard/monitoring Grafana instances |
| GET /data/{tenantId}/query | Query time-series data |
| POST /data/{tenantId}/blobs/recover | Retry failed blob cleanup and tombstone stale failed metadata after the configured retention window |
| GET /alerts/history | Alert history |

Notes:
- Connector configs are encrypted at rest and sensitive fields are masked in API responses.
- Connectors can be managed from the Connectors page with explicit Connect/Disconnect/Test actions.
- MQTT connector forms include quick-fill for the integrated Mosquitto broker (`mqtt://mosquitto:1883`) and localhost (`mqtt://localhost:1883`), while still allowing custom brokers.
- Integrated broker credentials are tenant-scoped entities (separate from app auth users) with explicit topic ACL lists; only tenant admins/superadmins can manage them.
- MQTT connectors can bind a `brokerCredentialId` so topic usage is validated against the credential ACL at create/update time.
- Frontend authentication sends the raw password over TLS; the server stores new credentials with Argon2id and transparently migrates supported legacy BCrypt(SHA-256) credentials at login.
- Pipelines support a `graphDefinition` payload; IMPORT/EXPORT nodes must reference compatible connectors by `connectorId`.
- The pipeline editor now captures connector selections for IMPORT/EXPORT nodes and sends a DAG `graphDefinition` with connector-aware validation.
- Dashboard Grafana tenancy is mirrored through persisted Grafana sync-state records; superadmins are marked as dashboard Grafana admins and as monitoring Grafana users.
- The Grafana reconciler provisions dashboard and monitoring users from app users, mirrors tenant org membership, provisions tenant-scoped dashboard datasources, and removes stale user/org assignments when users, memberships, or tenants are deleted.

See [API Documentation](docs/api/README.md) for full details.

## Configuration

Environment variables for the backend:

| Variable | Description | Default |
|----------|-------------|---------|
| DB_HOST | PostgreSQL host | postgresql |
| DB_PORT | PostgreSQL port | 5432 |
| DB_NAME | Database name | dataturntable |
| KAFKA_BOOTSTRAP_SERVERS | Kafka servers | kafka:9092 |
| DATATURNTABLE_GRAFANA_DASHBOARD_ENABLED | Enable dashboard Grafana reconciliation | false |
| DATATURNTABLE_GRAFANA_DASHBOARD_BASE_URL | Dashboard Grafana base URL for admin API calls | http://dashboard-grafana:3000 |
| DATATURNTABLE_GRAFANA_DASHBOARD_ADMIN_USER | Dashboard Grafana admin username (required when reconciliation is enabled) | (none) |
| DATATURNTABLE_GRAFANA_DASHBOARD_ADMIN_PASSWORD | Dashboard Grafana admin password (required when reconciliation is enabled) | (none) |
| DATATURNTABLE_GRAFANA_MONITORING_ENABLED | Enable monitoring Grafana reconciliation | false |
| DATATURNTABLE_GRAFANA_MONITORING_BASE_URL | Monitoring Grafana base URL for admin API calls | http://grafana:3000 |
| DATATURNTABLE_GRAFANA_MONITORING_ADMIN_USER | Monitoring Grafana admin username (required when reconciliation is enabled) | (none) |
| DATATURNTABLE_GRAFANA_MONITORING_ADMIN_PASSWORD | Monitoring Grafana admin password (required when reconciliation is enabled) | (none) |
| DATATURNTABLE_PIPELINE_DATA_JDBC_URL | Optional dedicated JDBC URL for pipeline data DB | (falls back to app datasource) |
| DATATURNTABLE_PIPELINE_DATA_JDBC_USERNAME | Optional dedicated JDBC username for pipeline data DB | (falls back to app datasource) |
| DATATURNTABLE_PIPELINE_DATA_JDBC_PASSWORD | Optional dedicated JDBC password for pipeline data DB | (falls back to app datasource) |
| BLOB_FAILED_RETENTION_DAYS | Days to retain `MISSING`/`DELETE_FAILED` blob metadata before the tenant recovery endpoint may tombstone it | 30 |
| DATATURNTABLE_DOCKER_ALLOW_NETWORK | Whether module containers can access network (true/false) | false |
| CONNECTOR_CONFIG_KEY | Base64-encoded 32-byte AES key used to encrypt connector configs at rest. Set in production to protect secrets; if unset, configs may be stored unencrypted for local development. | (none) |

## Deployment

Two deployment targets are supported: a single node with Docker Compose, and Kubernetes (single-instance infrastructure or the high-availability overlay). All bundled components are free and open-source software; see [docs/development/licenses.md](docs/development/licenses.md).

### Support status

| Target | Status | Scope and caveats |
|---|---|---|
| Docker Compose | Implemented, single node | One host; no redundancy of Kafka, PostgreSQL or MQTT. Ingest survives a Kafka outage through the local spool. |
| Kubernetes | Implemented, verification ongoing | Role split (control-api, public-api, ingest-edge, pipeline-executor) with autoscaling; failover of Kafka and the application verified locally (no loss, no duplicates). A full-cluster failover and restore test on real infrastructure is still open. |
| Kubernetes HA overlay (`docker/k8s-ha`) | Implemented, verification ongoing | CloudNativePG, Strimzi Kafka (RF 3, min ISR 2) and clustered MQTT via their operators. |

The production readiness record is [docs/production-ready-v1-status.md](docs/production-ready-v1-status.md); requirements and their status are in [docs/development/requirements-review.md](docs/development/requirements-review.md).

### Single node (Docker Compose)

```bash
cd docker
docker compose -f docker-compose.dev.yml up -d
```

Compose runs the same service split as Kubernetes on one host, without quorum-based HA.

### Kubernetes

Single-instance infrastructure (PostgreSQL, Kafka and Mosquitto as plain deployments):

```bash
kubectl apply -k docker/k8s/
```

High availability: [docker/k8s-ha](docker/k8s-ha/README.md) replaces the single-instance infrastructure with CloudNativePG (PostgreSQL), Strimzi (Kafka) and a RabbitMQ cluster with the MQTT plugin, all FOSS operators:

```bash
kubectl apply -k docker/k8s-ha/
```

Ingress hosts:
- `monitoring.dataturntable.example.com` → Monitoring Grafana
- `dashboard.dataturntable.example.com` → Dashboard Grafana
- `docs.dataturntable.example.com` → Documentation portal

The Kubernetes bundle includes:
- App services: app PostgreSQL, pipeline PostgreSQL/Timescale, monitoring PostgreSQL, Kafka, Mosquitto, `control-api`, `public-api`, `ingest-edge`, `pipeline-executor`, Frontend
- Monitoring services: Prometheus, Loki, Grafana Alloy, monitoring Grafana, dashboard Grafana, node-exporter, kube-state-metrics, DB/broker exporters

System/hardware visibility in Grafana:
- Host metrics via Node Exporter (CPU, memory, filesystem, network)
- Container metrics via cAdvisor (container CPU/memory/restarts)
- Service logs via Loki + Grafana Alloy

## Development

### Running Tests

Backend:
```bash
cd backend
mvn test
```
Frontend:
```bash
cd frontend
npm test
```
### Code Style

- Backend: Follow Quarkus/Kotlin conventions
- Frontend: ESLint + Prettier (run `npm run lint`)

## Documentation

- [Architecture & Implementation Plan](docs/development/plan.md)
- [Development Setup](docs/development/setup.md)
- [Architecture and Runtime Guide](docs/development/architecture.md)
- [Source-code Reference](docs/development/source-reference.md)
- [Source Function Reference](docs/development/source-function-reference.md)
- [Configuration Reference](docs/development/configuration-reference.md)
- [Documentation Gaps and FIXMEs](docs/development/documentation-gaps.md)
- [API Reference](docs/api/README.md)
- [Requirements Specification](docs/specification/requirements-specification.md)
- [Functional Specification](docs/specification/functional-specification.md)

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Contact

For questions or suggestions, contact: contact@dataturntable.io
