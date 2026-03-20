# Monitoring and Logging

## Components

- Prometheus (metrics)
- Grafana (dashboards)
- Loki (logs)
- Grafana Alloy/exporters (collection)

## What to monitor minimum

- service uptime
- request throughput and p95 latency
- host CPU and memory
- container resource pressure
- alert delivery failures

## Incident analysis flow

1. Identify impact window.
2. Check metrics anomalies.
3. Correlate logs in same time range.
4. Determine failing component and dependency.
5. Apply mitigation/rollback.

## Multi-instance guidance

- compare per-instance behavior before global action
- watch for noisy-neighbor effects
- verify discovery labels are present for all replicas
