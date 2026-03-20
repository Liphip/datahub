# Backup und Wiederherstellung

## Was muss gesichert werden?

- PostgreSQL/Timescale-Volumes
- exportierte Modul- und Pipeline-Definitionen
- optional Grafana-State (bei UI-angepassten Dashboards)

## Empfohlener Zeitplan

- Produktion: haeufige Inkremente + taegliches Vollbackup
- Staging: taeglich
- Konfig-Exporte: bei jedem Release

## Restore-Drill-Vorlage

1. Letztes Backup in isolierter Umgebung wiederherstellen.
2. Schema und Kerntabellen pruefen.
3. Eine Pipeline Ende-zu-Ende validieren.
4. Dauer messen und mit RTO vergleichen.
5. Ergebnis dokumentieren.
