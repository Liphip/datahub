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

Ja. Monitoring kann zentral laufen und Multi-Instance-Deployments in Kubernetes per Service Discovery scrapen.

## 13. Praktische Tutorials

### Tutorial A: Neue Pipeline erstellen und validieren

1. Mandantenspezifische Module anlegen/waehlen.
2. Pipeline import -> processing -> export erstellen.
3. Manuellen Lauf starten.
4. Ergebnis im Daten-Explorer validieren.
5. Alarmverhalten auf Signal/Rauschen pruefen.

Erfolgskriterien:

- Run erfolgreich
- Latenz im erwarteten Rahmen
- keine unerwartete Alarmflut

### Tutorial B: Performance-Regression untersuchen

1. Startzeitpunkt der Regression im Dashboard bestimmen.
2. In Grafana p95-Latenz vor/nachher vergleichen.
3. In Loki Logs nach Fehler- oder Retry-Spitzen suchen.
4. Letzte Modul-/Pipeline-Versionen pruefen.
5. Bei hohem Risiko auf stabile Version zurueckrollen.

### Tutorial C: Mandantenzugriff validieren

1. Read-only-Testbenutzer anlegen.
2. Rolle in Nicht-Produktion zuweisen.
3. Pruefen: lesen erlaubt, schreiben verboten.
4. Nachweis dokumentieren (Screenshot + Audit-Notiz).

## 14. Operative Tipp-Sammlung

- Release-Notizen mit `was`, `warum`, `risiko`, `rollback` pflegen.
- Bei Vorfaellen immer Zeitzone mitprotokollieren.
- Erst enges Query-Fenster, dann schrittweise erweitern.
- Pro Releasefenster moeglichst nur eine grosse Variable aendern.
- Veraltete Module und ungenutzte Konnektoren regelmaessig aufraeumen.

## 15. Eskalationsvorlage

Bei Eskalation an Plattform-Support mitgeben:

- Mandanten-ID sowie betroffene Pipeline-/Modul-IDs
- erster beobachteter Zeitstempel inklusive Zeitzone
- zuletzt ausgerollte Aenderungen
- Metrik-Panel-Query und Screenshot
- relevante Log-Auszuege
- bereits umgesetzte Sofortmassnahmen

## 16. Dokumentationsseite

Pfad der eigenstaendigen Dokumentationsseite:

- `/docs` (MkDocs-Material Dokumentationsservice)

Quelle der eigenstaendigen Doku:

- `docs-site/`
