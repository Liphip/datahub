# Configuration Reference

This reference is derived from `backend/src/main/resources/application.yaml` and
the development compose stack. Environment variables override the defaults shown
below. Development defaults are deliberately convenient; they are not production
secrets.

## Core services

| Variable | Default | Used by | Meaning |
|---|---|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME` | `localhost`, `5432`, `dataturntable` | Quarkus datasource | Application metadata database location |
| `DB_USER`, `DB_PASSWORD` | `dataturntable` | Quarkus datasource | Application metadata database credentials |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | reactive messaging | Kafka bootstrap address for pipeline topics |
| `REDIS_HOSTS` | `redis://localhost:6379` | connector/runtime coordination | Redis client address |
| `DATATURNTABLE_PIPELINE_DATA_JDBC_URL` | empty | pipeline data service | Separate JDBC URL for tenant pipeline data |
| `DATATURNTABLE_PIPELINE_DATA_JDBC_USERNAME`, `DATATURNTABLE_PIPELINE_DATA_JDBC_PASSWORD` | empty | pipeline data service | Credentials for the pipeline-data database |

## Runtime roles and connector leases

| Variable | Default | Meaning |
|---|---|---|
| `DATATURNTABLE_RUNTIME_ROLE` | `all` | Selects `all`, `control-api`, `public-api`, `ingest-edge`, or `pipeline-executor` responsibilities |
| `DATATURNTABLE_RUNTIME_INSTANCE_ID` | empty | Stable identity used by the runtime where configured |
| `DATATURNTABLE_CONNECTOR_LEASE_TTL_SECONDS` | `45` | Redis connector-lease lifetime |
| `DATATURNTABLE_CONNECTOR_LEASE_RECONCILE_INTERVAL_SECONDS` | `20` | Frequency of connector ownership reconciliation |

## Grafana integration

`DATATURNTABLE_GRAFANA_DASHBOARD_*` configures the dashboard Grafana target and
`DATATURNTABLE_GRAFANA_MONITORING_*` configures the monitoring Grafana target.
Both targets have an `ENABLED`, `BASE_URL`, `ADMIN_USER`, and `ADMIN_PASSWORD`
setting. Dashboard provisioning also uses `DATATURNTABLE_GRAFANA_DASHBOARD_DATASOURCE_`
variables for host, port, database, user, password, and SSL mode.

The checked-in `admin/admin` defaults are only suitable for the local compose
environment. Set explicit secret values in each deployment and do not expose a
Grafana admin account through general application configuration.

## Module execution and MQTT defaults

| Variable/key | Default | Meaning |
|---|---|---|
| `dataturntable.docker.module.base-image` | `python:3.11-slim` | Base image used for user module containers |
| `dataturntable.docker.module.network` | `dataturntable` | Docker network connected to module containers |
| `dataturntable.docker.resources.cpu-limit` | `1.0` | CPU limit passed to module containers |
| `dataturntable.docker.resources.memory-limit` | `512m` | Memory limit passed to module containers |
| `MQTT_INTEGRATED_INTERNAL_BROKER_URL` | `mqtt://mosquitto:1883` | UI default reachable from the stack |
| `MQTT_INTEGRATED_EXTERNAL_BROKER_URL` | `mqtt://localhost:1883` | UI default reachable from a developer machine |
| `MQTT_INTEGRATED_WEBSOCKET_URL` | `ws://localhost:9001` | UI MQTT-over-WebSocket default |
| `MQTT_INTEGRATED_DEFAULT_TOPIC` | `dataturntable/events` | Initial topic suggested in the UI |

## Profiles and operational rules

- `%test` uses an in-memory H2 database, disables Flyway migration and inbound
  pipeline consumption, and disables background runtime work.
- `%dev` and `%prod` override datasource/Kafka/Redis connection values; `%prod`
  also establishes datasource pool bounds.
- The role profiles turn off inbound pipeline consumption everywhere except the
  executor profile.
- Flyway runs on normal application startup. Treat migrations as a deployment
  concern and back up the application database before release.

## Secrets

Never commit production values for database passwords, Grafana credentials, JWT
private keys, connector credentials, or external alert tokens. Inject them using
the deployment platform's secret mechanism. The current JWT key and insecure
Grafana defaults are called out in [documentation gaps](documentation-gaps.md)
because the repository cannot safely invent a production key-management policy.
