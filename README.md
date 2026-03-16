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
    - Backend API (management): http://localhost:8080/api/internal/v1
    - Backend API (public ingestion): http://localhost:8080/api/v1
    - PostgreSQL/TimescaleDB: localhost:5432
    - Kafka: localhost:9092
    - MQTT Broker: localhost:1883

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
| `/api/internal/v1/` | Management API (authenticated, JWT or API key) |
| `/api/v1/` | Public data ingestion API (for external systems pushing data) |

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
| GET /tenants | List tenants |
| GET /modules | List code modules |
| GET /pipelines | List pipelines |
| POST /pipelines/{id}/run | Trigger pipeline |
| GET /data/{tenantId}/query | Query time-series data |
| GET /alerts/history | Alert history |

Notes:
- Connector configs are encrypted at rest and sensitive fields are masked in API responses.
- Frontend auth uses SHA-256 (Base64) client hashing before transmission; server stores BCrypt of that hash.
- Pipelines support a `graphDefinition` payload; IMPORT/EXPORT nodes must reference compatible connectors by `connectorId`.

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
| DATATURNTABLE_DOCKER_ALLOW_NETWORK | Whether module containers can access network (true/false) | false |
| CONNECTOR_CONFIG_KEY | Base64-encoded 32-byte AES key used to encrypt connector configs at rest. Set in production to protect secrets; if unset, configs may be stored unencrypted for local development. | (none) |

## Deployment

### Docker Compose

```bash
cd docker
docker-compose -f docker-compose.dev.yml up -d
```
### Kubernetes

Using Kustomize:
```bash
kubectl apply -k docker/k8s/
```
Using Helm (coming soon):
```bash
helm install dataturntable ./docker/helm/dataturntable
```
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
- [API Reference](docs/api/README.md)
- [Requirements Specification](docs/specification/requirements-specification.md)
- [Functional Specification](docs/specification/functional-specification.md)

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Contact

For questions or suggestions, contact: contact@dataturntable.io
