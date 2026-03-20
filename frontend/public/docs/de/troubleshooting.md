# Troubleshooting

## Schneller Triage-Ablauf

1. Benutzerkontext pruefen (Mandant + Rolle).
2. Plattform-Health-Endpunkte pruefen.
3. Letzte Aenderungen (Deploy/Config) vergleichen.
4. Metriken und Logs zeitlich korrelieren.
5. Bei steigender Auswirkung Rollback/Mitigation umsetzen.

## Haeufige Probleme

### Keine Daten in der UI

- falscher Mandant aktiv
- fehlende Leserechte
- im gewaehlten Zeitbereich keine Daten

### Pipeline startet, faellt aber sofort aus

- Konnektor-Authentifizierung abgelaufen
- Modulabhängigkeiten inkompatibel
- Upstream-Timeout/Limit geaendert

### Leere Dashboard-Panels

- Datasource-UID passt nicht
- Query-Labels in Laufzeitdaten nicht vorhanden
- Zeitbereich ausserhalb der Datenhaltung
