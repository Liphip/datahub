# Betriebs-Runbook

## Tägliche Pruefungen

- fehlgeschlagene Runs
- Alarmzustellungen
- Service-Health
- Retry-/Backlog-Wachstum

## Wöchentliche Pruefungen

- Rechte-Audits
- veraltete Module aufraeumen
- Backup-Status pruefen
- Dashboard-Qualitaet pruefen

## Release-Checkliste

1. Backup-Snapshot/Export
2. Nicht-Produktion deployen
3. Runs/Alarme validieren
4. Produktion stufenweise deployen
5. Soak-Phase monitoren

## Eskalationspaket

Bei Eskalation an den Plattform-Support mitgeben:

- Mandanten-ID sowie betroffene Pipeline- und Modul-IDs
- erster beobachteter Zeitstempel mit Zeitzone
- zuletzt ausgerollte Aenderungen
- Metrik-Panel-Query und Screenshot
- relevante Log-Auszuege
- bereits umgesetzte Sofortmassnahmen

## Operative Tipps

- Release-Notizen mit *was*, *warum*, *Risiko* und *Rollback* fuehren.
- Bei Vorfaellen Zeitstempel immer mit Zeitzone protokollieren.
- Erst ein enges Zeitfenster abfragen, dann erweitern.
- Pro Releasefenster nur eine grosse Aenderung.
- Veraltete Module und ungenutzte Konnektoren regelmaessig entfernen.
