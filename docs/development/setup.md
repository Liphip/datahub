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
