# Development Setup

How to run, build and test DataTurntable locally. Configuration values are listed in the
[configuration reference](configuration-reference.md); contribution rules are in
[CONTRIBUTING.md](../../CONTRIBUTING.md).

## Prerequisites

| Tool | Version |
|---|---|
| JDK | 25 (the build sets `maven.compiler.release=25`; an older JDK cannot compile it) |
| Maven | 3.9+ |
| Node.js | 22.12+ (24 LTS recommended) |
| Docker with Compose v2 | current |
| Python | 3.12+ (only for testing modules locally; modules run in `python:3.14` containers) |

Main components: Quarkus 3.39, Kotlin 2.4, PostgreSQL 18 with TimescaleDB 2.30 (image), Apache Kafka
4.3 (KRaft), Mosquitto 2.0, React 19, Vite, Tailwind CSS 4.

## Full stack with Docker Compose

```bash
./scripts/dev/generate-dev-keys.sh                 # throwaway JWT keys in secrets/dev-keys/ (git-ignored)
docker compose -f docker/docker-compose.dev.yml up -d
```

The backend does not start without the JWT keys (`DATATURNTABLE_JWT_*_KEY_LOCATION` have no
default). The generated keys are for local use only; `FORCE=1` regenerates them and invalidates
issued tokens.

Compose runs the same service split as Kubernetes on one host (`control-api`, `public-api`,
`ingest-edge`, `pipeline-executor`), with one Kafka broker and one database. It cannot be
redundant (NFR-R8).

| Service | URL |
|---|---|
| Application (via nginx gateway) | http://localhost |
| Frontend container (direct) | http://localhost:3000 |
| Management API | http://localhost/api/internal/v1 |
| Data, alert and monitoring API | http://localhost/api/v1 |
| Ingestion API | http://localhost/api/ingest/v1 |
| Metrics | http://localhost/q/metrics |
| PostgreSQL: metadata / pipeline data / monitoring / dashboard | localhost:5432 / 5433 / 5434 / 5435 |
| Kafka | localhost:9092 |
| MQTT (bundled Mosquitto) | localhost:1883, WebSocket 9001 |
| Prometheus | http://localhost:9090 |
| Monitoring Grafana | http://localhost:3001, http://monitoring.localhost, http://localhost/monitoring (`admin/admin`, development only) |
| Dashboard Grafana | http://localhost:3003, http://dashboard.localhost, http://localhost/dashboard (`admin/admin`, development only) |
| Documentation site | http://localhost:3002, http://docs.localhost, http://localhost/docs |
| Loki API | http://localhost:3100 |

The backend containers are not published directly; everything goes through nginx
(`docker/nginx.conf`: `/api/internal/v1/`, `/api/v1/`, `/api/ingest/v1/`, `/monitoring`,
`/dashboard`, `/docs`, `/`).

## Backend and frontend outside containers

Start the infrastructure (PostgreSQL, Kafka, Mosquitto) with Compose, then:

```bash
cd backend
DATATURNTABLE_JWT_SIGNING_KEY_LOCATION=$PWD/../secrets/dev-keys/signing.pem \
DATATURNTABLE_JWT_PUBLIC_KEY_LOCATION=$PWD/../secrets/dev-keys/public.pem \
mvn quarkus:dev                                    # http://localhost:8080

cd frontend
npm install
npm run dev                                        # http://localhost:5173, proxies /api/ to :8080
```

`API_PROXY_TARGET` points the Vite proxy elsewhere. Connectors to services on your machine or in
the local network need `DATATURNTABLE_CONNECTORS_ALLOWED_NETWORKS` (for example `127.0.0.0/8`),
because private destinations are refused by default.

## Tests

| Scope | Command |
|---|---|
| Backend, full suite (H2) | `cd backend && mvn clean test -Dquarkus.http.test-port=0` |
| Backend, one class | `mvn test -Dquarkus.http.test-port=0 -Dtest=Energy14aExampleTest` |
| Migrations against real PostgreSQL | `DATATURNTABLE_IT_DB_URL=jdbc:postgresql://localhost:5432/<empty db> DATATURNTABLE_IT_DB_PASSWORD=… mvn test -Dquarkus.http.test-port=0 -Dtest=PostgresMigrationBehaviorTest` |
| Frontend | `cd frontend && npx tsc --noEmit -p . && npm run lint && npm test && npm run build` |
| Kubernetes manifests | `kubectl kustomize docker/k8s && kubectl kustomize docker/k8s-ha` |

Pitfalls:
- Always pass `-Dquarkus.http.test-port=0`: a running stack or dev server holds 8080/8081.
- Run `clean` after deleting an entity. A stale class in `target/` passes on H2 (schema generated
  from the entities) but fails the PostgreSQL schema validation.
- Tests that need Docker (Testcontainers, hostile-module execution) are skipped without a Docker
  daemon; the suite reports them as skipped, not passed.
- The migration test needs an **empty** database.

CI (`.github/workflows/ci.yml`) runs the backend and frontend suites, the OpenAPI contract lint,
manifest rendering, the PostgreSQL migration test, image build and scans.

## Monitoring

- **Compose:** Prometheus, Loki, Grafana Alloy, both Grafana instances and exporters (PostgreSQL,
  Kafka, nginx, blackbox, node, cAdvisor). Monitoring Grafana ships dashboards for status, backend,
  PostgreSQL, Kafka, edge services, infrastructure, overview and logs.
- **Kubernetes:** `docker/k8s/monitoring/*` deploys Prometheus, Loki, Alloy (DaemonSet), Grafana,
  Node Exporter and kube-state-metrics; backend and frontend pods carry scrape annotations.
  Ingress hosts: `monitoring.dataturntable.example.com`, `dashboard.dataturntable.example.com`,
  `docs.dataturntable.example.com`.

## Database migrations

One Flyway baseline, `backend/src/main/resources/db/migration/V1__baseline.sql`. Before the first
production installation the baseline is edited directly (no backward compatibility, no data
migrations); after that, every change becomes a new versioned migration.
