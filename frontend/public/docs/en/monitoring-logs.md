# Monitoring and Logs

## Observability Components

- Prometheus: metrics collection
- Grafana: dashboards and analysis
- Loki: log aggregation
- Promtail: log shipping
- Exporters: service and infra telemetry

## Dashboard Usage Guide

### Overview Dashboard

- component uptime
- request rate
- p95 latency

### Infrastructure Dashboard

- host CPU and memory
- container-level CPU trends
- container inventory visibility

### Logs Dashboard

- filter logs by service/container labels
- align time range with metric anomalies

## Multi-Instance Environments

- Swarm: use task discovery metrics
- Kubernetes: use pod/service discovery metrics
- Compare per-instance behavior before global rollback

## Tips

- During incidents, split screens: metrics on one monitor, logs on another.
- Save frequently used queries as Grafana panel query history/bookmarks.
