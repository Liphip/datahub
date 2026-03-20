# Development Setup

## Prerequisites

- Java 21+ (JDK)
- Maven 3.9+
- Node.js 20+
- Docker & Docker Compose
- Python 3.11+ (for local module testing)

## Tech Stack

| Component | Version |
|-----------|---------|
| Quarkus | 3.32.2 |
| Kotlin | 2.3.0 |
| PostgreSQL/TimescaleDB | 16 |
| Kafka | 7.5.0 |
| Redis | 7 |
| Mosquitto | 2 |

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
- Grafana ships with pre-provisioned dashboards: Status, Backend, PostgreSQL, Redis, Kafka, Edge Services, Infrastructure, Overview, and Logs

### Docker Swarm (multi-node)

Deploy monitoring stack with Swarm service discovery and global log/host collectors:

```bash
docker network create --driver overlay --attachable dataturntable
docker stack deploy -c docker/docker-stack.monitoring.yml dataturntable-monitoring
```

- Uses `dockerswarm_sd_configs` for dynamic multi-replica scraping
- Uses global Grafana Alloy for logs across all nodes/tasks
- Includes global Node Exporter and cAdvisor for host/container monitoring

Deploy the application stack in Swarm as well:

```bash
docker network create --driver overlay --attachable dataturntable
docker stack deploy -c docker/docker-stack.app.yml dataturntable
```

- `docker-stack.app.yml` includes full platform runtime services (postgres, kafka, redis, mosquitto, backend, frontend, nginx)
- stateless services are pre-scaled and can be increased with `docker service scale`

### Kubernetes (multi-replica)

- `docker/k8s/monitoring/*` deploys Prometheus, Loki, Grafana Alloy, Grafana, Node Exporter, and kube-state-metrics
- `docker/k8s/infra/messaging.yaml` deploys Redis, Kafka, and Mosquitto alongside existing app services
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
| REDIS_HOSTS | redis://redis:6379 | Redis host |
