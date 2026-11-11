# Übungsdrehbuch: simulierter Cross-Tenant-Zugriffsversuch mit kompromittiertem Konto

Szenario (WP-M1-41): Ein kompromittiertes Konto im Mandanten A versucht wiederholt, Daten des Mandanten B abzurufen. Die Übung ist ausschließlich simuliert
(Testmandanten, keine Produktivdaten).

## Vorbereitung

- Umgebung Entwicklung oder Staging, Betriebsalarmkanal mit Testempfänger konfiguriert (`taktovia.alert.channel-credential`).
- Zwei Testmandanten A und B, ein Testkonto in A; Rolle Sicherheitsverantwortliche mit Scope `security:contain`, ein Plattformbetreiberkonto.
- Protokollführung nach [uebungsprotokoll-vorlage.md](uebungsprotokoll-vorlage.md).

## Ablauf

| Schritt | Aktion | Erwartung |
|---|---|---|
| 1 | Das Testkonto fordert dreimal innerhalb von 10 Minuten Daten des Mandanten B an | je Anfrage Sicherheitsereignis `security.tenant.mismatch`, Anfrage abgewiesen |
| 2 | Warten auf den Alarm | genau ein Alarm `cross_tenant_access` (kritisch) erreicht den Testempfänger; Zeitpunkt der Identifikation notieren |
| 3 | Untersuchung nach [sicherheitsvorfall.md](sicherheitsvorfall.md): Liste der Sicherheitsereignisse (UI-20), Konto und Zeitraum eingrenzen | Ereignisse ohne Fremdinhalte sichtbar |
| 4 | Eindämmung: Konto sperren | Konto gesperrt, Sitzungen beendet, `security.containment.executed` |
| 5 | Eindämmung: Mandant A auf `SUSPENDED` (Plattformbetreiberkonto) | Status `SUSPENDED`, Audit |
| 6 | Eindämmung: Secret rotieren (Signaturschlüssel des Mandanten) | neuer Wert ohne Neustart wirksam |
| 7 | Entscheidung über Meldungen mit der Entscheidungsvorlage | Entscheidung, Begründung und Zeitpunkt im Protokoll |
| 8 | Nachbereitung | Mandant wieder `ACTIVE`, Konto entsperren, Erkenntnisse und Maßnahmen im Protokoll |

## Technische Entsprechung

Der automatisierbare Teil (Schritte 1 bis 6) ist als Test hinterlegt: `SecurityIncidentAlertTest` (Alarmerzeugung und Zustellung),
`SecurityContainmentTest` und `SecurityContainmentAdaptersTest` (Eindämmung mit Berechtigung und Audit). Sie ersetzen die Übung mit Menschen
(Schritte 3, 7, 8) nicht.
