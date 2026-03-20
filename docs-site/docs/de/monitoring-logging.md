# Monitoring und Logging

## Komponenten

- Prometheus
- Grafana
- Loki
- Promtail/Exporter

## Mindestsignale

- Service-Uptime
- Requestrate und p95-Latenz
- Host-CPU/Memory
- Container-Auslastung
- Alarmzustellfehler

## Incident-Ablauf

1. betroffenes Zeitfenster bestimmen
2. Metrik-Anomalien pruefen
3. Logs im selben Zeitraum korrelieren
4. Fehlerquelle identifizieren
5. Mitigation/Rollback anwenden
