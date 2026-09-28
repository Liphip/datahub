# Module

## Modultypen

| Typ | Rolle | Leitet weiter | Loest Alarme aus |
|---|---|---|---|
| IMPORT | Verarbeitet Daten, die die Plattform ueber einen Konnektor geholt hat | Ja | Nein |
| DB_LOAD | Laedt Zeilen aus einer Plattformtabelle | Ja | Nein |
| PROCESSING | Transformiert, filtert oder reichert an | Ja | Nein |
| MERGE | Fuehrt mehrere Vorgaenger-Zweige zusammen (Fan-in) | Ja | Nein |
| ALERT | Prueft Bedingungen und loest Alarme aus (eingebaute Regeltypen decken die meisten Faelle ohne Code ab) | Nein | Ja |
| EXPORT | Formt den Datensatz, den die Plattform ueber den Konnektor ausliefert (oder "Send as is" ohne Code) | Nein | Nein |
| DB_SAVE | Bereitet die Zeilen vor, die die Plattform in eine Tabelle schreibt | Nein | Nein |

## Ein minimales PROCESSING-Modul

```python
class ProcessingModule:
    def process(self, data: dict, query_fn=None) -> dict | None:
        data["value_c"] = (data["value_f"] - 32) * 5 / 9   # Einheiten normalisieren
        return data                                         # None zurueckgeben, um zu verwerfen
```

Vollstaendigere, realistische Beispiele (CSV-Parsing, ein eigener Abwesenheitsalarm, Formen von
Daten fuer ein Altsystem) stehen im Entwicklerbereich in der funktionalen Spezifikation, §15.9;
ein vollstaendiges Mehr-Pipeline-Beispiel unter *Example: §14a EnWG*.

## Erstellungsablauf

1. Von der letzten stabilen Version ausgehen.
2. Eine logische Aenderung anwenden.
3. In einem Nicht-Produktions-Mandanten testen.
4. Als neue Version mit klarer Beschreibung speichern.
5. Ausrollen; alte Versionen bleiben verfuegbar und nachvollziehbar.

## Qualitaets-Checkliste

- Vorhersehbares Verhalten bei unerwarteter Eingabe: `None` zurueckgeben statt den Run abstuerzen
  zu lassen.
- Konfigurationswerte kommen aus dem `config`-Parameter des Konstruktors, nicht fest im Code.
- Abhaengigkeitsaenderungen sind in den Modul-Requirements dokumentiert.
