# Einsteiger-Guide (ohne Vorwissen)

Dieser Guide richtet sich an Nutzer ohne tiefe Plattformkenntnisse.

## Was ist DataTurntable vereinfacht?

Stellen Sie sich eine Datenfabrik vor:

- **Konnektoren**: Ein- und Ausgaenge
- **Module**: Verarbeitungsschritte
- **Pipelines**: Ablaufkette
- **Alarme**: Warnsystem
- **Daten-Explorer**: Sicht auf Ergebnisse

## Plan fuer den ersten Tag

### Schritt 1: Anmelden und Mandant pruefen

Der haeufigste Fehler ist ein falscher Mandantenkontext.

### Schritt 2: Dashboard pruefen

Auf folgende Signale achten:

- Fehlerspitzen
- haengende/fehlgeschlagene Pipelines
- API-Warnhinweise

### Schritt 3: Sicheren Testablauf erstellen

1. Testmodul anlegen
2. Testpipeline anlegen
3. Manuellen Lauf starten
4. Ergebnis im Daten-Explorer pruefen

### Schritt 4: Bei Fehlern Logs nutzen

In Grafana Logs nach Service und Zeitfenster filtern.

## Sichere Gewohnheiten

- zuerst in `dev` arbeiten
- vor Verhaltensaenderungen versionieren
- Aenderungen mit Grund dokumentieren
- vor Produktion immer Review einholen
