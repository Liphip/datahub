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
- Java 21+ (for backend development)
- Node.js 20+ (for frontend development)
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
    - Monitoring Grafana (direct): http://localhost:3001 (admin/admin)
    - Monitoring Grafana (via gateway): http://monitoring.localhost
    - Monitoring fallback route: http://localhost/monitoring
    - Dashboard Grafana (direct): http://localhost:3003 (admin/admin)
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
| GET /alerts/history | Alert history |

Notes:
- Connector configs are encrypted at rest and sensitive fields are masked in API responses.
- Connectors can be managed from the Connectors page with explicit Connect/Disconnect/Test actions.
- MQTT connector forms include quick-fill for the integrated Mosquitto broker (`mqtt://mosquitto:1883`) and localhost (`mqtt://localhost:1883`), while still allowing custom brokers.
- Integrated broker credentials are tenant-scoped entities (separate from app auth users) with explicit topic ACL lists; only tenant admins/superadmins can manage them.
- MQTT connectors can bind a `brokerCredentialId` so topic usage is validated against the credential ACL at create/update time.
- Frontend auth uses SHA-256 (Base64) client hashing before transmission; server stores BCrypt of that hash.
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
| REDIS_HOSTS | Redis hosts | redis://redis:6379 |
| DATATURNTABLE_GRAFANA_DASHBOARD_ENABLED | Enable dashboard Grafana reconciliation | false |
| DATATURNTABLE_GRAFANA_DASHBOARD_BASE_URL | Dashboard Grafana base URL for admin API calls | http://dashboard-grafana:3000 |
| DATATURNTABLE_GRAFANA_DASHBOARD_ADMIN_USER | Dashboard Grafana admin username | admin |
| DATATURNTABLE_GRAFANA_DASHBOARD_ADMIN_PASSWORD | Dashboard Grafana admin password | admin |
| DATATURNTABLE_GRAFANA_MONITORING_ENABLED | Enable monitoring Grafana reconciliation | false |
| DATATURNTABLE_GRAFANA_MONITORING_BASE_URL | Monitoring Grafana base URL for admin API calls | http://grafana:3000 |
| DATATURNTABLE_GRAFANA_MONITORING_ADMIN_USER | Monitoring Grafana admin username | admin |
| DATATURNTABLE_GRAFANA_MONITORING_ADMIN_PASSWORD | Monitoring Grafana admin password | admin |
| DATATURNTABLE_PIPELINE_DATA_JDBC_URL | Optional dedicated JDBC URL for pipeline data DB | (falls back to app datasource) |
| DATATURNTABLE_PIPELINE_DATA_JDBC_USERNAME | Optional dedicated JDBC username for pipeline data DB | (falls back to app datasource) |
| DATATURNTABLE_PIPELINE_DATA_JDBC_PASSWORD | Optional dedicated JDBC password for pipeline data DB | (falls back to app datasource) |
| DATATURNTABLE_DOCKER_ALLOW_NETWORK | Whether module containers can access network (true/false) | false |
| CONNECTOR_CONFIG_KEY | Base64-encoded 32-byte AES key used to encrypt connector configs at rest. Set in production to protect secrets; if unset, configs may be stored unencrypted for local development. | (none) |

## Deployment

### Docker Compose

Compose is the functional/dev profile. It follows the same service split as production, but it does not attempt to provide quorum-based HA on a single host.

```bash
cd docker
docker-compose -f docker-compose.dev.yml up -d
```

### Docker Swarm Monitoring

```bash
docker network create --driver overlay --attachable dataturntable
docker stack deploy -c docker/docker-stack.monitoring.yml dataturntable-monitoring
```

- Prometheus uses Swarm task discovery for multi-replica services
- Grafana Alloy runs globally and ships service logs to Loki
- Grafana includes both Prometheus and Loki datasources by default

### Docker Swarm Application Stack (multi-instance)

```bash
docker network create --driver overlay --attachable dataturntable
docker stack deploy -c docker/docker-stack.app.yml dataturntable
```

- Deploys full platform services in Swarm: app PostgreSQL, pipeline PostgreSQL/Timescale, monitoring PostgreSQL, Kafka, Redis, Mosquitto, `control-api`, `public-api`, `ingest-edge`, `pipeline-executor`, Frontend, Nginx, monitoring Grafana, and dashboard Grafana
- Includes dedicated docs service (`dataturntable/docs:latest`) for user/admin documentation
- Scales stateless services by default (`control-api=2`, `public-api=2`, `ingest-edge=2`, `pipeline-executor=2`, `frontend=3`, `nginx=2`)
- Works with the monitoring stack on the shared `dataturntable` overlay network

### Docker Swarm HA Stack

For a quorum-oriented Swarm topology with replicated PostgreSQL, KRaft Kafka, clustered EMQX, and the split backend roles, use:

```bash
docker network create --driver overlay --attachable dataturntable
docker stack deploy -c docker/docker-stack.ha.yml dataturntable-ha
```

The HA stack file is in [docker/docker-stack.ha.yml](docker/docker-stack.ha.yml). The Kubernetes Helm values below are the stronger reference for the full HA infra plane, including Redis replication/Sentinel.

### Kubernetes

Using Kustomize:
```bash
kubectl apply -k docker/k8s/
```

Monitoring endpoint host for Kubernetes ingress:
- `monitoring.dataturntable.example.com` → Monitoring Grafana
- `dashboard.dataturntable.example.com` → Dashboard Grafana
- `docs.dataturntable.example.com` → Documentation portal

Kubernetes bundle now includes full application + monitoring resources:
- App services: app PostgreSQL, pipeline PostgreSQL/Timescale, monitoring PostgreSQL, Kafka, Redis, Mosquitto, `control-api`, `public-api`, `ingest-edge`, `pipeline-executor`, Frontend
- Monitoring services: Prometheus, Loki, Grafana Alloy, monitoring Grafana, dashboard Grafana, node-exporter, kube-state-metrics, DB/broker exporters

System/hardware visibility in Grafana:
- Host metrics via Node Exporter (CPU, memory, filesystem, network)
- Docker/container metrics via cAdvisor (container CPU/memory/restarts)
- Service logs via Loki + Grafana Alloy
Using Helm (coming soon):
```bash
helm install dataturntable ./docker/helm/dataturntable
```

Production HA infrastructure Helm values are included in:

- [docker/helm/ha-values/app-postgresql-ha.yaml](docker/helm/ha-values/app-postgresql-ha.yaml)
- [docker/helm/ha-values/pipeline-postgresql-ha.yaml](docker/helm/ha-values/pipeline-postgresql-ha.yaml)
- [docker/helm/ha-values/monitoring-postgresql-ha.yaml](docker/helm/ha-values/monitoring-postgresql-ha.yaml)
- [docker/helm/ha-values/kafka-ha.yaml](docker/helm/ha-values/kafka-ha.yaml)
- [docker/helm/ha-values/redis-ha.yaml](docker/helm/ha-values/redis-ha.yaml)
- [docker/helm/ha-values/mqtt-ha.yaml](docker/helm/ha-values/mqtt-ha.yaml)

Installation notes and expected service names are documented in [docker/helm/README.md](docker/helm/README.md).

A Kubernetes application overlay that points the backend roles at those HA service names is included in:

- [docker/k8s-ha/kustomization.yaml](docker/k8s-ha/kustomization.yaml)
- [docker/k8s-ha/backend-ha-env.yaml](docker/k8s-ha/backend-ha-env.yaml)
- [docker/k8s-ha/README.md](docker/k8s-ha/README.md)
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
