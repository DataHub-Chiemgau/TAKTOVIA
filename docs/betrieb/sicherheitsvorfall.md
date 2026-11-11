# Betriebsdokument: Sicherheitsvorfall

Arbeitspaket WP-M1-41, Anforderung LH-6.18-SEC-011 („Sicherheitsvorfälle besitzen Eskalations-, Untersuchungs- und Meldeprozess“).
Entwurf aus der Umsetzung; Rollen, Erreichbarkeiten, Meldewege und Fristen sind **nicht benannt** und vom Auftraggeber rechtlich zu bestätigen (OP-32).
Das Betriebshandbuch und das Gesamtmonitoring des Betriebs sind M5; dieses Dokument deckt den Vorfallprozess der Phase 1 ab.

## Freigabevermerk

| Feld | Wert |
|---|---|
| Version | 0.1 (Entwurf) |
| Freigabestatus | **ausstehend** – organisatorisch durch Auftraggeber und Betreiber (Entscheidungen D-11, D-05; OP-32) |
| Freigegeben von / am | – |
| Gültig für Produktivbetrieb | nein, bis zur Freigabe |

Die Software kann die Freigabe nicht ersetzen; ohne Freigabevermerk ist der Prozess nicht für den Produktivbetrieb freigegeben.

## Rollen und Verantwortliche

Namen und Erreichbarkeiten trägt der Betreiber je Umgebung ein (nicht Teil des Repositorys, keine Personendaten hier). Rollenbezeichnungen sind Planungsannahmen (OP-32).

| Rolle | Aufgabe im Vorfall | Besetzung / Erreichbarkeit |
|---|---|---|
| Bereitschaft Betrieb (Stufe 1) | nimmt den Alarm aus dem Betriebsalarmkanal entgegen, bestätigt, führt Erstbewertung durch | offen (OP-32) |
| Sicherheitsverantwortliche (Stufe 2) | leitet die Untersuchung, ordnet Eindämmung an, hält das Protokoll | offen (OP-32) |
| Plattformbetreiberkonto | einzige Rolle, die einen Mandanten auf `SUSPENDED` setzen darf (zweiter Faktor Pflicht) | offen (D-11) |
| Datenschutzverantwortliche (Auftraggeber/Betreiber) | bewertet die Meldepflicht gegenüber Aufsichtsbehörde und Betroffenen | offen (OP-32) |
| Geschäftsleitung / Auftraggeber (Stufe 3) | entscheidet bei schwerem Vorfall über Meldungen und Kommunikation | offen (OP-32) |

## Auslöser und Schweregrad

Der Alarm entsteht automatisch aus den Sicherheitsereignissen (Aktionen `security.*` im Auditspeicher, WP-M1-12) und wird über den Betriebsalarmkanal
(WP-M1-47) an den je Umgebung konfigurierten Empfänger zugestellt. Gleichartige Ereignisse ergeben je Regel und Mandant genau einen offenen Alarm; kein
Ereignis geht verloren, alle bleiben im Speicher. Die Alarmwarnung enthält nur Regel, Mandanten-ID, Ereignisart und Zähler, nie Inhalte.

| Regel (`reason_code`) | Ereignisse | Standardschwelle (Planungswert) | Schweregrad |
|---|---|---|---|
| `cross_tenant_access` | `security.tenant.mismatch`, `security.event.recipient_mismatch` | 3 in 10 Minuten | kritisch |
| `mfa_failures` | `security.auth.mfa_failed` | 5 in 10 Minuten | Warnung |
| `payload_changed` | `security.event.payload_hash_mismatch` | 1 in 60 Minuten | kritisch |

Schwellen und Fenster sind Konfiguration (`taktovia.security.incident.*`); das Pflichtenheft beziffert sie nicht (OP-32).

## Eskalationsstufen

| Stufe | Auslöser | Frist zur Reaktion (Planungswert, OP-32) | Maßnahme |
|---|---|---|---|
| 1 Erstbewertung | Alarm Schweregrad Warnung oder kritisch | 30 Minuten (Betreuungszeiten D-05) | Alarm bestätigen, Ereignisse prüfen (siehe Untersuchung), Fehlalarm oder Vorfall festhalten |
| 2 Untersuchung und Eindämmung | bestätigter Vorfall oder jeder kritische Alarm | unverzüglich nach Stufe 1 | Sicherheitsverantwortliche übernimmt, Eindämmung anordnen, Protokoll beginnen |
| 3 Entscheidung über Meldungen | Verdacht auf Verletzung des Schutzes personenbezogener Daten oder Fremdzugriff mit Datenabfluss | Fristen nach rechtlicher Bestätigung (OP-32) | Datenschutzverantwortliche und Auftraggeber entscheiden mit der Entscheidungsvorlage |

## Untersuchungsschritte

1. Alarm bestätigen; Zeitpunkt der Identifikation im Protokoll festhalten (Zeitpunkt der Zustellung steht in der strukturierten Logzeile `operator_alert_delivered`).
2. Einstieg: Liste der Sicherheitsereignisse (Oberfläche UI-20, Auditabfrage mit Zweck „Sicherheitsvorfall“; `GET /v1/audit-events`, Filter Aktion und Akteur).
3. Betroffene Mandanten, Konten und Zeitraum eingrenzen; Korrelations-IDs der Ereignisse verfolgen (Logs und Audit).
4. Prüfen, ob fremde Mandantendaten tatsächlich gelesen wurden (Ereignisse enthalten keine Fremdinhalte; Zugriffe über Audit des Zielmandanten nachvollziehen).
5. Eindämmung anordnen (nächster Abschnitt); jede Maßnahme erzeugt `security.containment.executed` im Audit.
6. Ursache und Wirkung dokumentieren, Entscheidung über Meldungen treffen, Maßnahmen gegen Wiederholung festlegen.

## Eindämmung

Alle Maßnahmen verlangen den Funktionsscope `security:contain`; ohne ihn wird mit 403 abgewiesen und als `security.access.denied` auditiert.

| Maßnahme | Wirkung | Baustein |
|---|---|---|
| Konto sperren | Konto im Mandanten gesperrt, alle Sitzungen beendet, IdP-Sitzungen beendet | Sitzungsverwaltung (WP-M1-10) |
| Mandant `SUSPENDED` | Mandant gesperrt; nur Plattformbetreiberkonto mit zweitem Faktor | Plattformverwaltung (WP-M1-05) |
| Secret rotieren | neuer Wert, Referenz bleibt, Leser lesen neu; Datenbankzugänge nach Wechselnutzer-Verfahren | Secret-Verwaltung (WP-M1-36) |

## Entscheidungsvorlage Meldung

Die rechtliche Bewertung trifft die Datenschutzverantwortliche des Auftraggebers; die Vorlage hält sie nachvollziehbar fest (Meldewege und Fristen: OP-32).

| Frage | Antwort |
|---|---|
| Sind personenbezogene Daten betroffen (Art, Umfang, Mandanten)? | |
| Wurde Vertraulichkeit, Integrität oder Verfügbarkeit verletzt (nachgewiesen / Verdacht / ausgeschlossen)? | |
| Besteht voraussichtlich ein Risiko für Betroffene? | |
| Meldung an Aufsichtsbehörde nötig? Frist und Zeitpunkt | |
| Benachrichtigung Betroffener nötig? | |
| Information des Auftraggebers / betroffener Mandanten | |
| Entscheidung (melden / nicht melden), Begründung, Entscheidende Person und Zeitpunkt | |

## Übung

Drehbuch: [uebungsdrehbuch-sicherheitsvorfall.md](uebungsdrehbuch-sicherheitsvorfall.md). Protokollvorlage: [uebungsprotokoll-vorlage.md](uebungsprotokoll-vorlage.md).
Durchgeführte Übungen stehen unter `uebungen/`.
