# Mandanten und Rollen

## Warum Mandanten wichtig sind

Mandanten trennen Daten, Rechte und Betriebsgrenzen. Vor jeder Aenderung den aktiven Mandanten pruefen.

## Empfohlenes Modell

- Stammmandant auf Organisationsebene
- Untermandanten fuer `dev`, `staging`, `prod`
- optionale Bereichsmandanten (`iot`, `finance`, `ops`)

## Rollenstrategie

- Viewer: lesen
- Maintainer: operative Aenderungen
- Tenant-Admin: Nutzer-/Rollenverwaltung
- Platform-Admin: hochprivilegierte Sonderfaelle
