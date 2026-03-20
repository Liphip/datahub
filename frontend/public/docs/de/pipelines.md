# Pipelines

## Designprinzipien

- klare Verantwortung pro Stage
- explizite Abhaengigkeiten
- moeglichst idempotentes Verhalten

## Betriebsablauf

1. Pipeline aktualisieren.
2. Testlauf ausfuehren.
3. Run-Details und Diagnose pruefen.
4. Zuverlaessigkeit ueber 1h/24h/7d beobachten.

## Bei Fehleranstieg

- ersten fehlerhaften Run nach "last known good" identifizieren
- Modul- und Konnektorversionen vergleichen
- Logs und Metriken zeitlich korrelieren
- bei wachsendem Risiko auf stabile Version zurueckrollen
