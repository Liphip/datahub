# Development Setup

## Prerequisites

- Java 25 (JDK)
- Maven 3.9+
- Node.js 22.12+ (24 LTS recommended)
- Docker & Docker Compose
- Python 3.11+ (for local module testing)

## Tech Stack

| Component | Version |
|-----------|---------|
| Java | 25 |
| Quarkus | 3.39 |
| Kotlin | 2.4 |
| PostgreSQL / TimescaleDB | 18 / 2.30 |
| Apache Kafka | 4.3 |
| Mosquitto | 2.0 |
| Node.js (frontend build) | 24 |

## Quick Start

### 1. Start Infrastructure

```bash
cd docker
docker compose -f docker-compose.dev.yml up -d
```

### 2. Build Backend

```bash
cd backend
mvn package -DskipTests
```

### 3. Build Frontend

```bash
cd frontend
npm install
npm run build
```

### 4. Access

| Service | URL |
|---------|-----|
| Frontend | http://localhost:80 |
| Backend API | http://localhost:8080/api/internal/v1 |
| Health | http://localhost:8080/q/health |
| Backend Metrics | http://localhost:8080/q/metrics |
| Prometheus | http://localhost:9090 |
| Grafana (direct) | http://localhost:3001 (admin/admin) |
| Grafana (subdomain via gateway) | http://monitoring.localhost |
| Monitoring fallback route | http://localhost/monitoring |
| User Docs (direct service) | http://localhost:3002 |
| User Docs (subdomain via gateway) | http://docs.localhost |
| User Docs (fallback route) | http://localhost/docs |

## Monitoring Deployment Modes

### Docker Compose (single host)

- Includes Prometheus + Grafana + Loki + Grafana Alloy + service exporters in `docker/docker-compose.dev.yml`
- Prometheus scrapes backend metrics (`/q/metrics`) plus infra exporters and blackbox probes
- Loki receives Docker logs through Grafana Alloy and is pre-wired as a Grafana datasource
- Grafana ships with pre-provisioned dashboards: Status, Backend, PostgreSQL, Kafka, Edge Services, Infrastructure, Overview, and Logs

### Kubernetes (multi-replica)

- `docker/k8s/monitoring/*` deploys Prometheus, Loki, Grafana Alloy, Grafana, Node Exporter, and kube-state-metrics
- `docker/k8s/infra/messaging.yaml` deploys Kafka and Mosquitto alongside existing app services
- `docker/k8s-ha` replaces the single-instance PostgreSQL, Kafka and Mosquitto with CloudNativePG, Strimzi and a RabbitMQ MQTT cluster
- Pod annotations on backend/frontend enable per-pod metrics scraping
- Grafana Alloy DaemonSet collects logs from all pods in `dataturntable` namespace
- Grafana ingress host: `monitoring.dataturntable.example.com`
- Docs ingress host: `docs.dataturntable.example.com`

### System and Docker/Container Insights

- Host hardware metrics: Node Exporter (CPU, memory, disk, network)
- Container runtime metrics: cAdvisor (Docker/containers)
- Application and infrastructure logs: Loki + Grafana Alloy in Grafana Logs

## API Routing

The backend uses two base paths:

- `/api/internal/v1/` - Frontend API (authenticated)
- `/api/v1/` - Public API placeholder (returns 501)

Nginx routes:
- `/api/*` → Backend
- `/` → Frontend

## Development Commands

### Backend

```bash
# Run in dev mode
mvn quarkus:dev

# Run tests
mvn test

# Compile
mvn compile
```

### Frontend

```bash
# Development server
npm run dev

# Build
npm run build

# Test
npm run test
```

## Known Issues

1. **Kotlin sources not compiling**: Ensure `pom.xml` has `src/main/kotlin` as source directory
2. **Quarkus 3.9+ renamed dependencies**:
   - `quarkus-resteasy-reactive` → `quarkus-rest`
   - `quarkus-resteasy-reactive-jackson` → `quarkus-rest-jackson`
3. **Panache entity queries**: Use explicit type parameters for static methods
4. **Kafka metadata**: Use builder pattern for `OutgoingKafkaRecordMetadata`

## Database Migrations

Flyway migrations are in `backend/src/main/resources/db/migration/`

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| DB_HOST | postgresql | Database host |
| DB_PORT | 5432 | Database port |
| DB_NAME | dataturntable | Database name |
| DB_USER | dataturntable | Database user |
| DB_PASSWORD | dataturntable | Database password |
| KAFKA_BOOTSTRAP_SERVERS | kafka:9092 | Kafka servers |
