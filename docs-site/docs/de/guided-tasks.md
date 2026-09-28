# Schritt-fuer-Schritt Aufgaben (einsteigerfreundlich)

## Aufgabe 1: Erstes Modul erstellen

1. **Module** oeffnen.
2. **Create Module** klicken.
3. Namen und Typ eingeben.
4. Konfiguration/Template ausfuellen.
5. **Save** klicken.

## Aufgabe 2: Erste Pipeline erstellen und starten

1. **Pipelines** oeffnen.
2. **Create Pipeline** klicken.
3. Import-Modul waehlen.
4. Processing/Export-Module hinzufuegen.
5. **Save** klicken.
6. **Run Pipeline** klicken.

## Aufgabe 3: Ergebnisdaten pruefen

1. **Daten** oeffnen.
2. Tabelle auswaehlen.
3. Zeitfenster/Limit setzen.
4. **Search** klicken.
5. Kennzahlen und Charts pruefen.
6. Optional **Export CSV** nutzen.

## Aufgabe 4: Alarmierung aktivieren

1. **Alarme** oeffnen.
2. Kanal erstellen.
3. Regel erstellen und Kanal zuweisen.
4. Regel aktivieren.
5. Test ausloesen.
6. **History** pruefen.

## Aufgabe 5: Fehlerhaften Run analysieren

1. In **Pipelines** auf fehlgeschlagene Runs filtern.
2. Run-Details oeffnen.
3. Lineage laden.
4. Grafana Metriken + Logs vergleichen.
5. Retry oder Rollback entscheiden.

## Aufgabe 6: Benutzer einladen (Admin)

1. **Benutzer** oeffnen.
2. **Invite User** klicken.
3. E-Mail/Name/Temp-Passwort eingeben.
4. Rolle setzen.
5. Eintrag in Liste pruefen.

## Aufgabe 7: Untermandant erstellen (Admin)

1. **Mandanten** oeffnen.
2. Elternmandant waehlen.
3. **Create Sub-Tenant** klicken.
4. Name/Defaults setzen.
5. Speichern und Baumstruktur pruefen.

## Aufgabe 8: Performance-Regression untersuchen

1. Startzeitpunkt der Regression in den Dashboard-Trends bestimmen.
2. In Grafana die p95-Latenz vorher und nachher vergleichen.
3. In Loki nach Fehler- oder Retry-Spitzen suchen.
4. Letzte Modul- und Pipeline-Versionen pruefen.
5. Bei hohem Risiko auf die vorherige Version zurueckrollen.

## Aufgabe 9: Mandantenzugriff validieren (Admin)

1. Read-only-Testbenutzer anlegen.
2. Rolle Viewer in einem Nicht-Produktions-Mandanten zuweisen.
3. Pruefen: lesen erlaubt, aendern verboten.
4. Nachweis dokumentieren (Screenshots und Audit-Notiz).

## Praktische Tipps

- Immer zuerst den aktiven Mandanten pruefen.
- Aenderungen in kleinen Schritten durchfuehren.
- Zeitstempel mit Zeitzone dokumentieren.
- Fuer Produktion immer Rollback-Plan bereithalten.
