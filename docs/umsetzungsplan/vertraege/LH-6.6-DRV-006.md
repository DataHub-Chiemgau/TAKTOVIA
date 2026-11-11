# LH-6.6-DRV-006 – Check-in mit Codeprüfung und Fahrgastliste

- **Verantwortlicher:** Rolle Fahrer-Backend (Modul `staff-time`, Boarding-Code-Erzeugung als Verbraucher der Buchungsereignisse aus `booking-dispatch`; ein Modul `driver` gibt es nicht, Zuschnitt OP-07)
- **Arbeitspaket:** WP-M3-30
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Check-in unterstützt datensparsame Codeprüfung und Liste der erwarteten Fahrgäste.

**Originalabnahme (unverändert):** Falscher oder bereits verwendeter Code wird abgewiesen.

## Vertrag

- **Tabellen / DDL:** Boarding-Code je Buchung als Zusatztabelle aus WP-M3-02 (Name im Bau; Hash des Codes, Zustand, Verwendungszeitpunkt, Fehlversuchszähler, `booking_id`, `tenant_id`; RLS). Speicherung nur als Hash, nie im Klartext in Datenbank, Logs, Ereignissen oder Metriklabels; Erzeugung je bestätigter Buchung als Verbraucher des Bestätigungsereignisses (WP-M2-23), Ungültigkeit bei Storno (WP-M2-26) oder abgelaufenem Buchungszustand.
- **API:** Erweiterung von `POST /v1/driver/events` (Check-in mit Code) oder eigener Prüfaufruf; Entscheidung vor Codebeginn, Änderung in `api/driver.yaml`. Falscher oder verwendeter Code: Abweisung (422) ohne Check-in; Code einer Buchung an anderem Stopp/Fahrzeug abgewiesen; Fehlversuchsgrenze überschritten: Sperre der Codeeingabe für diese Buchung und Hinweis an die Leitstelle. Fahrgastliste in der Fahrerprojektion (DRV-002): nur Fahrgäste des aktuellen bzw. nächsten Stopps der eigenen Schicht, Felder Anzeigename bzw. Buchungskürzel und Personenzahl, keine Kontakt- oder Zahlungsdaten, kein Code.
- **Zustandsautomat:** `vertraege/zustaende/boarding_code.md` (zu erstellen): ausgestellt, entwertet, gesperrt, ungültig.
- **JSON-Schema / Ereignisse:** Klärfall bei offline doppelt verwendetem Code (beim Sync genau ein Check-in, ein Klärfall; offline erfasste Codes gelten erst nach Serverprüfung); Hinweisereignis an die Leitstelle bei Sperre; Check-in-Ereignis wie DRV-005.
- **Parameter und Kennzahlen:** Die Fehlversuchsgrenze ist kein Katalogparameter in `kataloge.csv` (OP-18).
- **Oberfläche:** UI-10 Liste erwarteter Fahrgäste, Codeeingabe/-scan, Fehlerzustand „falscher Code" (Kap. 06: „Falscher Stopp/Code").
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-18 (Codeformat, Gültigkeit, Erzeugung, Offline-Prüfbarkeit, Regelprozess für Fahrgäste ohne Code, Fehlversuchsgrenze, Übergabeweg des Codes an den Fahrgast vor M7; mit dem Auftraggeber zu klären; blockiert Format und Grenzwert, nicht den Bau der Einmalverwendung). `LH-6.4-BKG-019` (P2) wird von M2 nicht gebaut; in M3 genügen Testfixture und Leitstelle als Codequelle, Vollabnahme mit WP-M7a-14.

## Tests

- **Positiv:** gültiger Code am richtigen Stopp ergibt Check-in und entwertet den Code; Fahrgastliste zeigt nur erwartete Fahrgäste des aktuellen bzw. nächsten Stopps mit Anzeigename bzw. Buchungskürzel und Personenzahl; offline erfasster Code gilt erst nach Serverprüfung als Check-in.
- **Negativ:** falscher Code (kein Check-in), bereits verwendeter Code, Code einer fremden Buchung/eines anderen Stopps/Fahrzeugs, zu viele Fehlversuche (Sperre und Leitstellenhinweis), Code einer stornierten/abgelaufenen Buchung; Suche nach dem Klartext-Code in Datenbank, Logs, Ereignissen, Metriklabels und Fahrerprojektion bleibt ohne Treffer; Fahrgastliste ohne Kontakt-/Zahlungsdaten und ohne Fahrgäste fremder Schichten.
- **Konkurrierend:** derselbe Code gleichzeitig von zwei Geräten ergibt genau einen Check-in; offline doppelt verwendeter Code ergibt beim Sync einen Check-in und einen Klärfall; Storno parallel zum Check-in.
- **Referenztests:** kein Katalogfall im WP genannt.
- Testfälle: geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/BoardingCodeCheckInTest.kt::gueltiger_code_ergibt_checkin_und_entwertet; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/BoardingCodeCheckInTest.kt::falscher_code_abgewiesen; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/BoardingCodeCheckInTest.kt::verwendeter_code_abgewiesen; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/BoardingCodeCheckInTest.kt::code_fremder_buchung_anderer_stopp_oder_fahrzeug_abgewiesen; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/BoardingCodeCheckInTest.kt::fehlversuchsgrenze_sperrt_und_meldet_leitstelle; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/BoardingCodeCheckInTest.kt::code_stornierter_oder_abgelaufener_buchung_ungueltig; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/BoardingCodeCheckInTest.kt::fahrgastliste_nur_eigene_schicht_ohne_kontakt_und_zahlungsdaten; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/BoardingCodeCheckInTest.kt::offline_erfasster_code_gilt_erst_nach_serverpruefung; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/BoardingCodeCheckInTest.kt::offline_doppelt_verwendeter_code_ein_checkin_ein_klaerfall; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/BoardingCodeCheckInTest.kt::derselbe_code_zwei_geraete_ein_checkin; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/BoardingCodeCheckInTest.kt::storno_parallel_zu_checkin; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/BoardingCodeCheckInTest.kt::code_nie_im_klartext

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
