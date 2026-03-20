# Daten-Explorer

## Zweck

Schnelle operative Datenpruefung direkt in der Plattform.

## Funktionen

- Tabellenwahl und Filter
- Zeitfenster und Zeilenlimit
- Kennzahlenkarten (min/max/avg/letzter Wert)
- Trend- und Verteilungsgrafiken
- CSV/JSON-Export

## Vorgehen

1. Mandant und Tabelle waehlen.
2. Zeitbereich und Limit setzen.
3. Abfrage starten und Kennzahlen pruefen.
4. Bei Auffaelligkeiten Export fuer Detailanalyse nutzen.

## Sicherheit

- Loesch-/Erstellaktionen nur mit `data:write`
- Produktion: destruktive Aenderungen nur mit Freigabeprozess
