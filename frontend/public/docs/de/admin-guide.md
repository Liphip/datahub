# Admin-Guide

Dieser Guide fokussiert auf sicheren und wartbaren Plattformbetrieb.

## 1. Verantwortungen

- Mandanten- und Rechteverwaltung
- sichere Releases und Rollback-Faehigkeit
- Monitoring- und Logging-Basis
- Backup-/Restore-Qualitaet

## 2. Rechte-Governance

### Neue Benutzer

1. Benutzerkonto erstellen
2. minimale Rolle im richtigen Mandanten zuweisen
3. effektive Rechte mit Testlogin pruefen
4. Freigabe dokumentieren

### Regelmaessige Reviews

- woechentlich: privilegierte Konten
- monatlich: alle Rollen/Mitgliedschaften
- sofort: nach Offboarding/Incident

## 3. Change Management

Vor Produktion:

- Rollback-Version verifizieren
- Monitoring-Baselines pruefen
- Wartungsfenster kommunizieren

Nach Produktion:

- Soak-Phase beobachten
- Fehler-/Latenztrends vergleichen

## 4. Incident-Checkliste

1. Severity und Owner festlegen
2. nicht-kritische Aenderungen stoppen
3. Auswirkung/Blast Radius bestimmen
4. Mitigation (Rollback/Disable) anwenden
5. RCA-Evidenz sammeln

## 5. Swarm/Kubernetes Hinweise

- Monitoring getrennt von App-Stack moeglich
- Service Discovery fuer Multi-Instanzen nutzen
- Node-/Pod-Signale ueber DaemonSets/Global-Collector erfassen
