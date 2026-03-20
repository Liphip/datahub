# Mandanten und Rollen

## Mandantenaufbau

Empfohlen:

- Stammmandant fuer Organisationssteuerung
- Untermandanten pro Umgebung (`dev`, `staging`, `prod`)
- optionale Unterebenen pro Team oder Fachdomäne

## Rollenmodell

- Viewer: lesender Zugriff
- Maintainer: operative Aenderungen
- Tenant Admin: Mandanten- und Benutzerverwaltung
- Platform Admin: hochprivilegierte Ausnahmen

## Least-Privilege Checkliste

- `data:write` nur bei echtem Bedarf
- `manage:tenant` nur fuer kleinen Admin-Kreis
- Wildcard-Rechte aus Standardrollen entfernen
- Nach Rollenwechsel effektive Rechte pruefen
