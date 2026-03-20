# Backup und Wiederherstellung

## Kritische Assets

- PostgreSQL/Timescale-Daten
- Modul- und Pipeline-Exporte
- optionaler Grafana-Status

## Beispiel-Policy

- Produktion: haeufige Inkremente + taegliches Vollbackup
- Staging: taegliches Vollbackup
- Konfig-Exporte: bei jedem Release

## Restore-Drill

1. in isolierte Umgebung wiederherstellen
2. Schema/Kerntabellen pruefen
3. eine Pipeline Ende-zu-Ende testen
4. Dauer gegen RTO bewerten
5. Ergebnis dokumentieren
