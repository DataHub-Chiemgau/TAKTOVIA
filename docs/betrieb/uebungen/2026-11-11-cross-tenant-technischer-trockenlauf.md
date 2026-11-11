# Übungsprotokoll: technischer Trockenlauf Cross-Tenant-Zugriffsversuch

| Feld | Wert |
|---|---|
| Übung (Datum, Szenario) | 2026-11-11, simulierter Cross-Tenant-Zugriffsversuch mit kompromittiertem Konto nach [Drehbuch](../uebungsdrehbuch-sicherheitsvorfall.md), Schritte 1 bis 6 |
| Art | **technischer Trockenlauf durch automatisierte Tests**, keine Übung mit Menschen; Schritte 3, 7 und 8 des Drehbuchs (menschliche Untersuchung, rechtliche Entscheidung, Nachbereitung) sind damit nicht belegt |
| Teilnehmende (Rollen) | keine (Testcode) |
| Umgebung | lokale Entwicklung, PostgreSQL mit Runtime-Rolle, Alarmempfänger = aufzeichnender Testempfänger; IdP und Betriebskanal-Webhook nicht ausgeführt |

## Identifikationszeitpunkt

2026-11-11T14:48:25Z (Start der Testklasse `SecurityIncidentAlertTest` im Lauf des Gradle-Builds; Quelle: `timestamp` im Testbericht). Im Test löste das dritte
Ereignis `security.tenant.mismatch` desselben Kontos in kurzer Folge den Alarm `cross_tenant_access` (kritisch) aus; der Alarm erreichte den Empfänger genau einmal.
In einer echten Übung ist der Zeitpunkt aus `operator_alert_delivered` (`triggered_at`, `delivered_at`) zu übernehmen.

## Eindämmungsmaßnahmen

| Zeitpunkt | Maßnahme | Ergebnis |
|---|---|---|
| im selben Testlauf (`SecurityContainmentAdaptersTest`) | Konto sperren | Sitzung des Kontos danach `ACCOUNT_LOCKED`; wiederholtes Sperren `ALREADY_IN_STATE`; Audit `security.containment.executed` |
| im selben Testlauf | Mandant `SUSPENDED` | Administrator des Mandanten abgewiesen (403, auditiert), Betreiberkonto erfolgreich, Status `SUSPENDED` |
| im selben Testlauf (`SecurityContainmentTest`) | Secret rotieren | Datei atomar ersetzt, neuer Wert ohne Neustart lesbar, kein Wert im Audit |
| im selben Testlauf | Eindämmung ohne Scope `security:contain` | abgewiesen (403) und als `security.access.denied` auditiert, keine Wirkung |

## Meldeentscheidung

Entscheidung: keine Meldung. Begründung: Simulation mit Testmandanten und Testdaten, keine Verletzung des Schutzes echter personenbezogener Daten, daher keine Meldepflicht
ausgelöst. Entscheidende Person: keine (automatisierter Lauf, Rolle Datenschutzverantwortliche nicht beteiligt). Zeitpunkt: 2026-11-11 (Testlauf).
Die Entscheidungsvorlage des Betriebsdokuments wurde nicht von einer verantwortlichen Person durchlaufen; die rechtliche Bestätigung von Meldewegen und Fristen steht aus (OP-32).

## Erkenntnisse und Folgemaßnahmen

- Eine Übung mit den benannten Rollen (Bereitschaft, Sicherheitsverantwortliche, Datenschutzverantwortliche) steht aus, sobald diese benannt sind (OP-32, D-11).
- Alarmschwellen sind Planungswerte und mit dem Betreiber abzustimmen.
- Der Empfängerkanal je Umgebung (OP-39) ist vor Produktivbetrieb zu bestätigen.
