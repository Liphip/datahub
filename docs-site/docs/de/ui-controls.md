# UI-Bedienreferenz (Menues und Buttons)

Diese Referenz erklaert, was die wichtigsten Menues und Schaltflaechen tun.

## Globales Navigationsmenue

| Menue | Zweck | Typischer Nutzer |
|---|---|---|
| Dashboard | Gesundheitsuebersicht, Aktivitaet, Schnellaktionen | alle |
| Module | Modul-Logik erstellen und versionieren | Maintainer, Admins |
| Pipelines | Pipelines bauen/starten, Runs analysieren | Maintainer, Operatoren |
| Konnektoren | Externe Integrationen/Broker-User verwalten | Operatoren, Admins |
| Daten | Tabellenabfrage, Trends, Exporte | Analysten, Operatoren |
| Alarme | Kanaele/Regeln und Historie verwalten | Operatoren, Admins |
| Benutzer | Benutzer einladen und Rollen pflegen | Tenant-Admins |
| Mandanten | Mandantenstruktur und Defaults verwalten | Tenant-/Plattform-Admins |

## Header-Steuerung

| Steuerung | Funktion | Hinweis |
|---|---|---|
| Mandantenauswahl | aktiven Mandanten wechseln | Bei "fehlenden Daten" zuerst pruefen |
| Sprachauswahl | UI-Sprache wechseln | Aendert keine Daten |
| Theme-Umschalter | Hell/Dunkel wechseln | persoenliche Einstellung |
| Logout | Sitzung beenden | auf geteilten Geraeten nutzen |

## Dashboard-Buttons

| Button/Aktion | Funktion |
|---|---|
| Create Module | Modul-Erstellung oeffnen |
| Create Pipeline | Pipeline-Erstellung oeffnen |
| Add Connector | Konnektor-Erstellung oeffnen |
| Open Problem View | Fehlerhafte/laufende Pipelines filtern |
| Zeitfenster (`1h/24h/7d`) | Trendfenster wechseln |

## Module-Seite

| Button/Aktion | Funktion |
|---|---|
| Add/Create Module | Neues Modul anlegen |
| Edit Module | Modul bearbeiten |
| Delete | Modul loeschen |
| Typauswahl | IMPORT/PROCESSING/ALERT/EXPORT waehlen |
| Version History | Fruehere Versionen laden |
| Save (Editor) | Neue Version speichern |

## Pipelines-Seite und Editor

| Button/Steuerung | Funktion |
|---|---|
| Create Pipeline | Pipeline-Konfiguration erstellen |
| Run Pipeline | Pipeline ausfuehren |
| Retry run | Fehlgeschlagenen Run erneut starten |
| Cancel run | Aktiven Run abbrechen |
| Load lineage | Lineage-Ansicht laden |
| Refresh now / Refresh run | Daten neu laden |
| Filter/Sortierung | Run-Ansicht eingrenzen |
| Save preset | Triage-Filterprofil speichern |
| + Add Module (Editor) | Stage-Modul hinzufuegen |
| Remove (Editor) | Stage-Modul entfernen |

## Konnektoren-Seite

| Button/Steuerung | Funktion |
|---|---|
| Create Connector | Konnektor anlegen |
| Edit Connector | Konnektor bearbeiten |
| Clone Connector | Konnektor kopieren |
| Connect / Disconnect | Konnektor aktiv/inaktiv |
| Filter (Typ/Richtung/Status) | Liste filtern |
| Connector Usage | Referenzen anzeigen |
| Connector Impact | betroffene Pipelines/Risiko anzeigen |
| Create MQTT Broker User | mandantenbezogene MQTT-Credentials anlegen |
| Rotate | Passwort rotieren |

## Daten-Explorer

| Button/Steuerung | Funktion |
|---|---|
| Refresh | Tabellen neu laden |
| Create Table | Tabelle erzeugen |
| Delete Table | Tabelle loeschen |
| Search | Abfrage starten |
| Metric field / Tag field | Chart-Felder setzen |
| Top tags | Anzahl Verteilungswerte steuern |
| Export CSV / Export JSON | Abfrageergebnis exportieren |

## Alarme

| Button/Steuerung | Funktion |
|---|---|
| Add/Edit Channel | Alarmkanal verwalten |
| Add/Edit Rule | Alarmregel verwalten |
| Delete Channel/Rule | Konfiguration entfernen |
| Any Of / All Of | Bedingungslogik bauen |
| Enabled/Disabled | Regel aktivieren/deaktivieren |
| Channels/Rules/History Tabs | Ansicht wechseln |

## Benutzer

| Button/Steuerung | Funktion |
|---|---|
| Invite User | Benutzer einladen |
| Rollen-Dropdown | Rolle aktualisieren |
| Activate/Deactivate | Benutzerstatus aendern |
| Remove membership | Mandantenzugriff entfernen |

## Mandanten

| Button/Steuerung | Funktion |
|---|---|
| Create Root Tenant | Stammmandant anlegen |
| Create Sub-Tenant | Untermandant anlegen |
| Edit Tenant | Mandant bearbeiten |
| Save lineage defaults | Lineage-Defaults speichern |
| Restore from history | Historischen Stand wiederherstellen |
| Copy diagnostics | Diagnose-Snapshot kopieren |
| Reset diagnostics | Diagnosezaehler zuruecksetzen |
| Use latest / Use mine / Merge and save | Konfliktaufloesung |

## Rechtebedingtes UI-Verhalten

- Buttons koennen je nach Rolle fehlen oder deaktiviert sein.
- "Access denied"/"permission" bedeutet meist Rechte- oder Mandantenkontextproblem.
- Bei fehlenden Steuerelementen: aktiven Mandanten und Rolle pruefen.
