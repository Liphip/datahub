# Pipelines

## Lebenszyklus

1. Stages und Abhaengigkeiten planen
2. Quell-/Zielmodule konfigurieren
3. Test-Run starten
4. Run-Details und Metriken pruefen
5. mit Monitoring ausrollen

## Eingebaute Schritte

Nicht jeder Knoten braucht Python. In den Knoteneinstellungen (Auswahl **Step**, bei IMPORT **Processing**, bei ALERT **Evaluation**; die Beschriftungen sind derzeit englisch):

| Knoten | Schritt | Wirkung |
|---|---|---|
| IMPORT | Pass through | Leitet Datensaetze unveraendert weiter; *Deduplicate by* (z. B. `devEUI, time`) macht Wiederholungen unschaedlich |
| PROCESSING | Enrich with metadata | Haengt das Metadatenobjekt an, auf das ein Feld verweist (z. B. den Zaehler eines Geraets), mit dem Stand zum Zeitpunkt des Datensatzes, optional auch verknuepfte Objekte |
| ALERT | Built-in rule | Schwellwert, Aenderungsrate, Zeitfenster oder ausbleibende Daten, mit Cooldown |
| EXPORT | Send as is | Liefert den Datensatz unveraendert ueber den Ausgangs-Konnektor des Knotens aus |
| EXPORT | Save to metadata | Legt aus dem Datensatz ein Metadatenobjekt an oder aktualisiert es (z. B. Geraeteregister) |

Ein vollstaendiges Beispiel mit all diesen Schritten — Messwerte, angereichert mit Zaehler- und
Standortdaten und an zwei Systeme geliefert — steht im Entwicklerbereich (*Example: §14a EnWG*).

## Zuverlaessigkeitstipps

- 1h/24h/7d Trends beobachten
- Latenzdrift frueh analysieren
- Rollback-Ziel bereithalten
