# Kubernetes High-Availability Overlay

Deploys the application manifests from [../k8s](../k8s) with the single-node infrastructure replaced by operator-managed clusters. All operators are FOSS:

| Component | Operator | Licence | Resources |
|---|---|---|---|
| PostgreSQL (app, pipeline data, both Grafana databases) | [CloudNativePG](https://cloudnative-pg.io) | Apache-2.0 | [postgres.yaml](postgres.yaml) |
| Kafka (KRaft) | [Strimzi](https://strimzi.io) | Apache-2.0 | [kafka.yaml](kafka.yaml) |
| MQTT broker | [RabbitMQ Cluster Operator](https://github.com/rabbitmq/cluster-operator) with the MQTT plugins | MPL-2.0 | [mqtt.yaml](mqtt.yaml) |

The applications keep using the single-node service names (`postgresql`, `pipeline-postgresql`, `monitoring-postgresql`, `dashboard-postgresql`, `kafka`, `mosquitto`); the overlay turns them into DNS aliases (`ExternalName`) of the operator services. The aliases assume the namespace `dataturntable` and the cluster domain `cluster.local`.

## Install

1. Install the operators (versions validated with these manifests):

   ```bash
   helm repo add cnpg https://cloudnative-pg.github.io/charts
   helm upgrade --install cnpg cnpg/cloudnative-pg --namespace cnpg-system --create-namespace

   helm repo add strimzi https://strimzi.io/charts/
   helm upgrade --install strimzi strimzi/strimzi-kafka-operator --namespace strimzi --create-namespace \
     --set watchAnyNamespace=true

   kubectl apply -f https://github.com/rabbitmq/cluster-operator/releases/download/v2.23.0/cluster-operator.yml
   ```

2. Replace the placeholder passwords in [../k8s/storage/secrets.yaml](../k8s/storage/secrets.yaml) (or supply the four `*-db-credentials` Secrets from your secret manager). The PostgreSQL clusters bootstrap their database owners from these Secrets.

3. Apply the overlay:

   ```bash
   kubectl apply -k docker/k8s-ha
   ```

4. MQTT: anonymous access is disabled on the RabbitMQ cluster. Read the credentials from the Secret `mqtt-default-user` (keys `username`, `password`) and register them as a broker credential in the platform for connectors that use the bundled broker.

## Known limitations

- The pipeline-data cluster uses the CloudNativePG PostgreSQL image, which does not contain the TimescaleDB extension. The platform does not currently require it; once TimescaleDB features are used, the cluster needs an image that bundles the extension.
- The operators themselves are installed separately (step 1); the single Helm chart planned in the target architecture will bundle them.
