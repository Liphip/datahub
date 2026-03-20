# Module

## Modultypen

- IMPORT
- PROCESSING
- ALERT
- EXPORT

## Versionierung

- Jede Verhaltensaenderung als neue Version speichern.
- Eindeutige Tags verwenden (`v1.4.2`, `staging`, `prod`).
- Beschreibung mit Aenderungsgrund pflegen.

## Empfohlener Ablauf

1. Letzte stabile Version als Basis nehmen.
2. In Nicht-Produktion aendern und testen.
3. Betroffene Pipelines testweise ausfuehren.
4. Logs/Metriken auf Regressionen pruefen.
5. Erst danach in Produktion ausrollen.
