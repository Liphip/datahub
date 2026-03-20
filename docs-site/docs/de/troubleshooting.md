# Troubleshooting

## Schneller Triage-Ablauf

1. Mandant pruefen
2. Rechte pruefen
3. Health-Endpunkte pruefen
4. Run-Details analysieren
5. Metriken und Logs korrelieren

## Haeufige Probleme

| Symptom | Haeufige Ursache | Erster Schritt |
|---|---|---|
| Leerer Daten-Explorer | falscher Mandant/Zeitraum/Rechte | Mandant und Filter pruefen |
| Pipeline faellt sofort aus | Konnektor-Auth/Config | ersten fehlerhaften Stage-Log pruefen |
| Dashboard ohne Daten | Datasource/Label-Mismatch | Datasource und Labels pruefen |
| Runs langsam | Ressourcenengpass | CPU/Memory/Backlog pruefen |
