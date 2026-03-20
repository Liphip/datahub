# Monitoring und Logs

## Komponenten

- Prometheus (Metriken)
- Grafana (Dashboards)
- Loki (Logs)
- Promtail (Log-Shipping)
- Exporter (Service- und Infrastrukturmetriken)

## Dashboard-Nutzung

### Overview

- Uptime
- Requestrate
- p95-Latenz

### Infrastructure

- Host-CPU und Speicher
- Container-CPU-Trends

### Logs

- Logs nach Service/Container filtern
- Zeitfenster mit Metrik-Spitzen abgleichen

## Multi-Instance Hinweise

- Swarm: Task-Service-Discovery nutzen
- Kubernetes: Pod-/Service-Discovery nutzen
