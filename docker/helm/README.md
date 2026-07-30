# HA Helm Values

This directory contains production-oriented HA values files for the infrastructure plane used by DataTurntable.

Recommended charts:

- `bitnami/postgresql-ha` for app metadata, pipeline data, and monitoring databases
- `bitnami/kafka` for KRaft Kafka
- `bitnami/redis` for Redis replication + Sentinel
- `emqx/emqx` for clustered MQTT

Example installation order:

```bash
helm repo add bitnami https://charts.bitnami.com/bitnami
helm repo add emqx https://repos.emqx.io/charts
helm repo update

helm upgrade --install app-db-ha bitnami/postgresql-ha -f docker/helm/ha-values/app-postgresql-ha.yaml
helm upgrade --install pipeline-db-ha bitnami/postgresql-ha -f docker/helm/ha-values/pipeline-postgresql-ha.yaml
helm upgrade --install monitoring-db-ha bitnami/postgresql-ha -f docker/helm/ha-values/monitoring-postgresql-ha.yaml
helm upgrade --install kafka-ha bitnami/kafka -f docker/helm/ha-values/kafka-ha.yaml
helm upgrade --install redis-ha bitnami/redis -f docker/helm/ha-values/redis-ha.yaml
helm upgrade --install mqtt-ha emqx/emqx -f docker/helm/ha-values/mqtt-ha.yaml
```

Expected service names for the backend role deployments:

- app DB: `app-db-ha-postgresql-ha-pgpool`
- pipeline DB: `pipeline-db-ha-postgresql-ha-pgpool`
- monitoring DB: `monitoring-db-ha-postgresql-ha-pgpool`
- Kafka bootstrap: `kafka-ha:9092`
- Redis service: `redis-ha-node-0.redis-ha-headless:6379` or the Sentinel endpoint exposed by the chart
- MQTT broker: `mqtt-ha:1883`

Update the backend environment in Kubernetes to point at the installed service names when using these HA charts.
