# Konnektoren, API-Schluessel und Alarme

## Konnektoren

Ein Konnektor ist eine benannte, wiederverwendbare Verbindung zu einem externen System.
INPUT-Konnektoren holen Daten herein, OUTPUT-Konnektoren liefern sie aus. Pipeline-Knoten verweisen
auf Konnektoren; Zugangsdaten werden nach dem Speichern nie wieder angezeigt.

| Typ | Richtung | Was die Plattform tut |
|---|---|---|
| MQTT | Ein- / Ausgang | Abonniert oder veroeffentlicht Topics (mitgelieferter oder externer Broker) |
| REST | Ein- / Ausgang | Fragt einen Endpunkt ab (Eingang) oder sendet Anfragen (Ausgang) |
| WebSocket | Ein- / Ausgang | Streamt Nachrichten |
| UDP | Ausgang | Sendet Datagramme |
| FILE | Eingang | Liest Dateien aus einem Verzeichnis auf dem Server |
| FTP / FTPS, SFTP | Eingang | Liest Dateien von einem entfernten Server (SFTP braucht den Host-Key-Fingerprint) |
| EMAIL (IMAP) | Eingang | Liest Mails, deren Anhaenge oder Text |

**Abhol-Eingaenge** (FILE, FTP, SFTP, EMAIL) werden von der Plattform abgefragt: Der Absender muss
nur die Datei ablegen oder die Mail senden. Ein *Mindestalter* verhindert, dass halb geschriebene
Dateien gelesen werden; die *Aktion nach dem Lesen* verschiebt, loescht oder behaelt das Element.
Der **Eingang** des Konnektors zeigt jedes gesehene Element mit Status; ein fehlgeschlagenes
Element kann einzeln erneut verarbeitet werden.

**Zugangsdaten** (Passwoerter, Schluessel, Tokens) werden verschluesselt als Secrets gespeichert.
Beim Bearbeiten bleibt ein leeres Secret-Feld beim gespeicherten Wert.

**Interne Netze:** Konnektoren erreichen private oder lokale Adressen nur, wenn der
Systemadministrator das Netz freigegeben hat. Scheitert das Speichern mit "loopback, private,
link-local or metadata network", bitte die Freigabe des Netzes anfragen.

Best Practices: Zugangsdaten pro Umgebung trennen, nach einer Rotation testen, Timeouts explizit
setzen.

## API-Schluessel

Geraete und Systeme, die Daten senden (REST-Ingestion-API, WebSocket), melden sich mit einem
API-Schluessel an. Unter **API-Schluessel** (Berechtigung *secret:manage*) die Pipelines und
Eingangs-Konnektoren waehlen, die der Schluessel beliefern darf, optional ein Ablaufdatum. Der
Schluessel wird **einmal** angezeigt: sofort in das sendende System uebernehmen. Wird ein Geraet
ausgemustert, den Schluessel widerrufen; er ist sofort ungueltig.

Ein Absender muss nur senden: Die Antwort `202` bedeutet, dass der Datensatz sicher angenommen ist.
Mit einem `Idempotency-Key`-Header (z. B. Geraete-ID plus Zeitstempel) wird der Datensatz auch bei
Wiederholung genau einmal verarbeitet.

## Alarme

Alarm**regeln** sind ALERT-Knoten in einer Pipeline: Schwellwert, Aenderungsrate, Zeitfenster,
ausbleibende Daten oder eigene Python-Logik. Die Seite **Alarme** verwaltet die **Kanaele** (MQTT,
Webhook, REST, E-Mail, Slack) und zeigt die **Historie** jeder Zustellung.

Empfehlung:

1. Kanal konfigurieren und testen
2. ALERT-Knoten in die Pipeline setzen und den Kanal als Standard waehlen
3. Cooldown setzen, damit eine Bedingung nicht wiederholt ausloest
4. Zustellhistorie pruefen

Zu beobachtende Zustellzustaende: `PENDING`, `SENT`, `FAILED`, `RETRY`.
