# Backup und Wiederherstellung

## Was gesichert wird

Alle dauerhaften Daten liegen in zwei PostgreSQL-Datenbanken: der Metadaten-Datenbank (Mandanten,
Benutzer, Pipelines, Konnektoren, Secrets, Metadatenobjekte, Audit-Events) und der
Pipeline-Daten-Datenbank (Messwerte und weitere von Pipelines geschriebene Tabellen). Mehr braucht
kein eigenes Backup: Es gibt keinen Dateispeicher (STR-08), und Kafka-/Grafana-Zustand laesst sich
neu aufbauen.

Skripte: `scripts/backup/backup.sh` und `scripts/backup/restore.sh` (zwei `pg_dump`/`pg_restore`-
Aufrufe). Vollstaendiges Verfahren, Containernamen und Umgebungsvariablen: siehe die Seite
*Backup and Restore* im Entwicklerbereich.

## Restore-Drill

1. Beide Dumps in eine frische, isolierte Instanz einspielen.
2. Flyway-Historie und einige Kerntabellen pruefen (`connectors`, `pipelines`, `tenants`).
3. Anmelden und eine Pipeline Ende-zu-Ende ausfuehren.
4. Wiederherstellungsdauer gegen das Recovery-Time-Objective messen und dokumentieren.

## Status

Fuer die Docker-Compose-Einzelknoten-Topologie durch einen echten Restore-Test verifiziert.
**Noch nicht** auf Kubernetes (Einzelinstanz oder HA-Overlay) verifiziert — siehe
Anforderungsspezifikation, Anhang A, NFR-R11.
