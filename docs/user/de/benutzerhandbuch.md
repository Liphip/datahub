# DataTurntable Benutzerhandbuch (Deutsch)

## 1. Zweck

DataTurntable unterstuetzt Teams beim Ingest, der Verarbeitung, dem Routing, Monitoring und Troubleshooting von Datenpipelines in Multi-Tenant-Umgebungen.

Dieses Handbuch richtet sich an:

- Mandantenadministratoren
- Plattformbetreiber
- Modul- und Pipeline-Verantwortliche
- Support- und On-Call-Teams

## 2. Erste Anmeldung und Ersteinrichtung

1. Fuehren Sie die Ersteinrichtung (`/setup`) einmal pro Umgebung aus.
2. Melden Sie sich ueber `/login` an.
3. Pruefen Sie den aktiven Mandantenkontext in der Kopfzeile.
4. Oeffnen Sie das Dashboard und pruefen Sie Gesundheits- und Zuverlaessigkeitswerte.

Best Practices:

- Superadmin-Zugangsdaten in einem Passwortmanager speichern.
- Fuer den Tagesbetrieb rollenbasierte Benutzer nutzen.

## 3. Navigation und globale Steuerung

- Dashboard: Laufzeituebersicht und Schnellaktionen
- Module: Wiederverwendbare Verarbeitungskomponenten
- Pipelines: Orchestrierung und Laufhistorie
- Konnektoren: Externe Integrationsendpunkte
- Daten: Tabellenanalyse, Trends, Exporte
- Alarme: Kanaele, Regeln, Zustellhistorie
- Benutzer/Mandanten: Identitaet und Governance

Globale Steuerung in der Kopfzeile:

- Mandantenwechsel
- Sprachumschaltung
- Theme-Umschaltung

## 4. Mandantenmodell und Berechtigungen

Mandanten trennen Daten und Betriebsgrenzen.

Typisches Muster:

- Stammmandant pro Organisation
- Untermandanten pro Umgebung/Team/Region

Berechtigungsprinzipien:

- `*:read` fuer reine Lesezugriffe
- `*:write` fuer operative Pflege
- `manage:tenant` fuer Mandantenverwaltung
- Wildcard-Berechtigungen nur fuer Admin-/Notfallrollen

## 5. Module

Modultypen:

- IMPORT
- PROCESSING
- ALERT
- EXPORT

Empfohlener Ablauf:

1. Modul mit klarer Absicht und Beschreibung erstellen.
2. In niedriger Umgebung implementieren und testen.
3. Version mit sprechendem Tag speichern (`v1.3.0`, `staging`, `prod`).
4. Ueber Pipeline-Aktualisierungen ausrollen.

Hinweise:

- Abhaengigkeiten sorgfaeltig dokumentieren.
- Jede Verhaltensaenderung versionieren.

## 6. Pipelines und Ausfuehrungen

Pipelines verbinden Module zu ausfuehrbaren Flows.

Bei Aenderungen:

1. Quell-/Zielkompatibilitaet pruefen.
2. Kontrollierten Testlauf ausfuehren.
3. Run-Details und Lineage-Diagnose pruefen.
4. Zuverlaessigkeitsfenster (1h/24h/7d) beobachten.

Bei steigender Fehlerquote:

- Laufzeitpunkte mit Logs und Metriken korrelieren
- Upstream-Konnektoren und Limits pruefen
- Aktuelle Modulversionen vergleichen

## 7. Daten-Explorer

Der Daten-Explorer dient zur Tabellenpruefung und schnellen Analyse.

Funktionen:

- Tabellenliste und Auswahl
- Zeitbereichs- und Zeilenlimit-Filter
- Kennzahlenkarten (min/max/avg/letzter Wert)
- Trend- und Tag-Verteilungsgrafiken
- CSV/JSON-Export

Sicherheitsregeln:

- Tabellenerstellung/-loeschung nur mit `data:write`
- Destruktive Aktionen in Produktion nur mit Change-Prozess

## 8. Konnektoren und Alarme

Konnektoren bilden Integrationen zu externen Systemen.

Alarmablauf:

1. Kanaele konfigurieren (E-Mail/Webhook/etc.)
2. Regeln und Schweregrade definieren
3. Mit Testausloesern validieren
4. Historie pruefen (`PENDING`, `SENT`, `FAILED`, `RETRY`)

Eskalationsverantwortung pro Mandant klar dokumentieren.

## 9. Monitoring, Logs und Diagnose

Der Observability-Stack umfasst Prometheus, Grafana, Loki, Exporter und Probes.

Dashboards fuer:

- Service-Uptime und Latenz
- Backend-Requestraten
- Host-/Container-Ressourcenverbrauch
- Service-uebergreifende Logs

Diagnoseablauf:

1. API-Health pruefen
2. Mandantenkontext und Rechte verifizieren
3. Fehlerhafte Runs analysieren
4. Metriken und Logs im gleichen Zeitfenster korrelieren
5. Diagnose-Snapshot fuer Support uebergeben

## 10. Backup und Wiederherstellung

Kritische Daten sichern:

- PostgreSQL/Timescale-Volumes
- optional Grafana-State (bei UI-angepassten Dashboards)
- exportierte Modul- und Pipeline-Definitionen

Regelmaessige Restore-Tests inklusive RPO/RTO-Nachweis durchfuehren.

## 11. Troubleshooting-Checkliste

- Ist die API erreichbar und gesund?
- Ist der richtige Mandant aktiv?
- Hat der Benutzer die noetigen Rechte?
- Gab es kuerzlich Modul-/Pipeline-Versionsaenderungen?
- Stimmen Logs und Metriken zeitlich ueberein?
- Sind externe Abhaengigkeiten (DB/Kafka/Redis/MQTT) gesund?

## 12. FAQ

### Warum sehe ich keine Tabellendaten?

Hauefige Ursachen: falscher Mandantenkontext, fehlende Berechtigungen oder kein Ingest im gewaehlten Zeitbereich.

### Warum bleibt ein Dashboard-Panel leer, bis ich es bearbeite?

Typischerweise liegt ein Datasource-UID-Mismatch in provisionierten Dashboards vor; feste UIDs verwenden.

### Kann Monitoring getrennt von den App-Services laufen?

Ja. Monitoring kann zentral laufen und Multi-Instance-Deployments in Swarm/Kubernetes per Service Discovery scrapen.
