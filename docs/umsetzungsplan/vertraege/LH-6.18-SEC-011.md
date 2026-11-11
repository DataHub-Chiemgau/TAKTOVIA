# LH-6.18-SEC-011 – Sicherheitsvorfallprozess und Übung

- **Verantwortlicher:** Rolle Plattform/Sicherheit (Alarmregeln in `backend/platform`), Betriebsdokument durch Rolle Betrieb
- **Arbeitspaket:** WP-M1-41
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Sicherheitsvorfälle besitzen Eskalations-, Untersuchungs- und Meldeprozess.

**Originalabnahme (unverändert):** Übung dokumentiert Identifikation, Eindämmung und Entscheidung über Meldungen.

## Vertrag

- **Tabellen / DDL:** keine neue Tabelle; Quelle sind die Sicherheitsereignisse und der Auditspeicher aus WP-M1-12/WP-M1-46. Eindämmung nutzt vorhandene Zustände: `tenant.status = 'SUSPENDED'` (V1), Sitzungs-/Kontosperre (WP-M1-10, `membership.revoked_at`), Secret-Rotation (WP-M1-36).
- **API:** keine neue fachliche API; Eindämmungsfunktionen nur für berechtigte Rolle (Rechtematrix WP-M1-07), jede Ausführung auditiert. Einstieg in die Untersuchung: Liste der Sicherheitsereignisse (Leseendpunkt aus WP-M1-12).
- **Zustandsautomat:** keiner im Backend; Eskalationsstufen als Prozessbeschreibung im Betriebsdokument.
- **JSON-Schema / Ereignisse:** Alarmereignis über den Betriebsalarmkanal aus WP-M1-47; Auslöser (Alarmregeln): gehäufte Fremdzugriffe, MFA-Fehlschläge, veränderter Payload bei gleicher Ereignis-ID. Deduplikation: gleichartige Ereignisse in kurzer Folge ergeben genau einen Vorfallalarm; kein Ereignis geht verloren (bleibt im Sicherheitsereignisspeicher).
- **Parameter und Kennzahlen:** Schwellwerte der Alarmregeln als Konfiguration; Werte nicht im Pflichtenheft beziffert (siehe Offene Punkte).
- **Oberfläche:** UI-20 Basis – Liste der Sicherheitsereignisse als Einstieg; Zustände leer, Fehler, fehlendes Recht.
- **Betriebsdokument:** „Sicherheitsvorfall“ (Rollen, Eskalationsstufen, Untersuchungsschritte, Entscheidungsvorlage Meldung), Übungsdrehbuch und Protokollvorlage; das Übungsprotokoll hält Zeitpunkt der Identifikation, Eindämmungsmaßnahmen und die dokumentierte Entscheidung über Meldungen fest. Übungsszenario: simulierter Cross-Tenant-Zugriffsversuch mit kompromittiertem Konto.
- **Entscheidungen:** D-11 (Verantwortliche, Supportzugriff) und D-05 (Supportzeiten) – blockieren die Freigabe des Prozesses für den Produktivbetrieb, nicht den Bau.
- **Offene Punkte:** OP-32 – Rollen, Erreichbarkeiten, Meldewege und Fristen sind nicht benannt und vom Auftraggeber rechtlich zu bestätigen; Schweregradschema und Alarmschwellen festlegen; OP-39 – Empfängerkanal je Umgebung (WP-M1-47). Die Freigabe des Prozesses (Kriterium 2) ist organisatorisch und nicht allein durch Software erfüllbar. Betriebshandbuch und Gesamtmonitoring sind M5.

## Tests

- **Positiv:** Übung nach Drehbuch mit simuliertem Cross-Tenant-Zugriffsversuch: Alarm wird aus den Sicherheitsereignissen automatisch erzeugt, erreicht über WP-M1-47 den definierten Empfänger; Protokoll enthält Identifikationszeitpunkt, Eindämmung (Sitzung/Konto sperren, Mandant `SUSPENDED`, Secret rotieren) und Meldeentscheidung.
- **Negativ:** Ereignisanzahl unterhalb des Schwellwerts erzeugt keinen Alarm (je Alarmregel: gehäufte Fremdzugriffe, MFA-Fehlschläge, veränderter Payload bei gleicher Ereignis-ID); Alarm ohne konfigurierten Empfänger im Betriebskanal wird als Zustellfehler sichtbar (WP-M1-47) und nicht verschluckt; Eindämmung (Kontosperre, Mandant `SUSPENDED`, Secret-Rotation) durch unberechtigte Rolle wird abgewiesen (403) und auditiert.
- **Konkurrierend:** 200 gleichartige Sicherheitsereignisse in kurzer Folge erzeugen genau einen Vorfallalarm (Deduplikation); alle 200 Ereignisse bleiben gespeichert.
- **Prozessnachweis (organisatorisch):** Betriebsdokument „Sicherheitsvorfall“ nennt Eskalationsstufen, Verantwortliche und Untersuchungsschritte und trägt einen Freigabevermerk (Kriterium 2); ein Dokumenttest prüft die Pflichtabschnitte, die Freigabe selbst bleibt organisatorisch (OP-32). Übungsprotokoll wird anhand der Protokollvorlage auf die Pflichtfelder Identifikationszeitpunkt, Eindämmungsmaßnahmen, Meldeentscheidung geprüft.
- **Referenztests:** keine Katalog-ID im WP genannt; E2E-091 (Cross-Tenant-Zugriff, Sicherheitsprotokoll) deckt den Auslöser ab, nicht den Prozess.
- Testfälle: E2E-091; backend/platform/src/test/kotlin/de/taktovia/platform/SecurityIncidentAlertTest.kt::cross_tenant_uebung_erzeugt_alarm_an_betriebskanal; backend/platform/src/test/kotlin/de/taktovia/platform/SecurityIncidentAlertTest.kt::zweihundert_ereignisse_ergeben_genau_einen_alarm; backend/platform/src/test/kotlin/de/taktovia/platform/SecurityContainmentTest.kt::eindaemmung_durch_unberechtigte_rolle_wird_abgewiesen; backend/platform/src/test/kotlin/de/taktovia/platform/SecurityIncidentAlertTest.kt::unterhalb_schwellwert_kein_alarm; backend/platform/src/test/kotlin/de/taktovia/platform/SecurityIncidentAlertTest.kt::alarmregeln_mfa_fehlschlag_und_payload_veraenderung_loesen_aus; backend/platform/src/test/kotlin/de/taktovia/platform/SecurityContainmentTest.kt::eindaemmung_berechtigte_rolle_sperrt_konto_mandant_und_rotiert_secret_auditiert; tests/acceptance/test_sicherheitsvorfall_prozess.py::test_betriebsdokument_hat_stufen_verantwortliche_untersuchungsschritte_und_freigabe; tests/acceptance/test_sicherheitsvorfall_prozess.py::test_uebungsprotokoll_enthaelt_identifikation_eindaemmung_meldeentscheidung; backend/modules/identity/src/test/kotlin/de/taktovia/identity/SecurityContainmentAdaptersTest.kt::konto_sperre_und_mandant_suspended_wirken_und_sind_auditiert

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
