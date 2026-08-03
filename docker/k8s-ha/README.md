# Kubernetes HA Overlay

<!-- TODO: Replace workspace-specific Markdown links
     Details: This absolute Windows path only resolves on its author's machine and breaks in GitHub and published documentation. Use a repository-relative link to ../helm/ha-values. -->
This overlay keeps the existing application manifests and rewires them to the HA infrastructure service names used by the Helm chart values in [../helm/ha-values](A:\Dev\Datahub\dataturntable-git\docker\helm\ha-values).

Apply order:

1. Install the HA charts from [../helm/README.md](A:\Dev\Datahub\dataturntable-git\docker\helm\README.md).
2. Apply the application overlay:

```bash
kubectl apply -k docker/k8s-ha
```

This overlay rewrites:

- app DB host -> `app-db-ha-postgresql-ha-pgpool`
- pipeline DB JDBC host -> `pipeline-db-ha-postgresql-ha-pgpool`
- Kafka bootstrap -> `kafka-ha:9092`
- Redis host -> `redis-ha-master:6379`
- dashboard Grafana datasource host -> `pipeline-db-ha-postgresql-ha-pgpool`
