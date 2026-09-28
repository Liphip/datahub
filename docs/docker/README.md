# Containers and Deployment

Supported targets: a single node with Docker Compose, and Kubernetes (single-instance
infrastructure or the high-availability overlay). Docker Swarm is not supported (DEP-08). Status
per target: requirements specification, Appendix A (DEP-01, DEP-02, NFR-R1 – NFR-R11).

| Target | What it is | Redundancy |
|---|---|---|
| Docker Compose (`docker/docker-compose.dev.yml`) | Development, evaluation and small single-node installations. Same service split as Kubernetes. | None: one broker, one database. Ingest survives a Kafka outage through the local spool, but not the loss of the node (NFR-R8). |
| Kubernetes (`docker/k8s`) | Runtime roles as Deployments/StatefulSet with autoscaling; PostgreSQL, Kafka and Mosquitto as single instances. | Stateless roles scale and restart; infrastructure is single-instance. |
| Kubernetes HA (`docker/k8s-ha`) | Overlay replacing the infrastructure with CloudNativePG, Strimzi Kafka (RF 3, min ISR 2) and a RabbitMQ MQTT cluster. See [its README](../../docker/k8s-ha/README.md). | Designed for no loss on single-node failure (NFR-R2, NFR-R7). Render-validated in CI; never run on a real cluster. |

## Images

| Image | Built from | Content |
|---|---|---|
| `dataturntable/backend` | `backend/Dockerfile` | Quarkus application; the role is chosen with `DATATURNTABLE_RUNTIME_ROLE` |
| `dataturntable/frontend` | `docker/frontend.Dockerfile` | React build served by nginx |
| `dataturntable/docs` | `docker/docs.Dockerfile` (context: repository root) | Documentation site: `docs-site/` plus the `docs/` tree copied in at build time |
| Third-party | pinned tags in the Compose file and manifests | `timescale/timescaledb:2.30.1-pg18`, `apache/kafka:4.3.1`, `eclipse-mosquitto:2.0.22`, `nginx`, Prometheus, Loki, Alloy, Grafana, exporters |

Licences of every bundled image: [licenses.md](../development/licenses.md).

## Docker Compose

```bash
./scripts/dev/generate-dev-keys.sh
docker compose -f docker/docker-compose.dev.yml up -d
docker compose -f docker/docker-compose.dev.yml ps          # wait for healthy
```

Services: `postgresql` (metadata), `pipeline-postgresql` (tenant data), `monitoring-postgresql`,
`dashboard-postgresql`, `kafka`, `mosquitto`, the four backend roles (`control-api`,
`public-api`, `ingest-edge`, `pipeline-executor`), `frontend`, `nginx` (gateway on port 80),
`docs`, and the monitoring stack (`prometheus`, `loki`, `alloy`, `grafana`, `dashboard-grafana`,
exporters, `cadvisor`). URLs and ports: [setup guide](../development/setup.md#full-stack-with-docker-compose).

The backend roles mount `../secrets/dev-keys` at `/keys`. Compose resolves relative bind paths
against the project directory (`docker/`), hence the `../`.

Troubleshooting:

```bash
docker compose -f docker/docker-compose.dev.yml logs -f control-api   # one service
docker compose -f docker/docker-compose.dev.yml up -d --build         # rebuild
docker compose -f docker/docker-compose.dev.yml down -v               # reset all data
```

## Kubernetes

```bash
kubectl apply -k docker/k8s        # single-instance infrastructure
kubectl apply -k docker/k8s-ha     # HA overlay (install the operators first, see its README)
```

Before applying:
- Populate `docker/k8s/storage/secrets.yaml` (database credentials) and the
  `dataturntable-jwt-keys` Secret (keys `signing.pem`, `public.pem`); both ship empty. Every
  backend workload mounts the JWT keys read-only at `/keys`.
- Set `CONNECTOR_CONFIG_KEY` and, if connectors must reach internal systems,
  `DATATURNTABLE_CONNECTORS_ALLOWED_NETWORKS` ([configuration reference](../development/configuration-reference.md)).
- Replace the image placeholders with digests. CI publishes them with a ready
  `kustomize edit set image` command (NFR-M8).

Layout: `backend/` (four role workloads with autoscalers, PodDisruptionBudgets, execution RBAC
and network policy; `ingest-edge` is a StatefulSet with a persistent volume for the spool),
`frontend/`, `docs/`, `infra/messaging.yaml` (Kafka, Mosquitto), `storage/` (PostgreSQL,
Secrets), `monitoring/`, `ingress/` (hosts `monitoring.`, `dashboard.`, `docs.dataturntable.example.com`).

Modules on Kubernetes run as pods through the Kubernetes execution backend, never through a Docker
socket (NFR-S3); that backend has not been run against a real cluster yet.
