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
- **Zustandsautomat:** [boarding_code.md](zustaende/boarding_code.md) (erstellt mit WP-M3-30): ausgestellt, entwertet, gesperrt, ungültig.
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
- Testfälle: backend/app/src/test/kotlin/de/taktovia/app/driver/BoardingCodeCheckInTest.kt::gueltiger_code_ergibt_checkin_und_entwertet; backend/app/src/test/kotlin/de/taktovia/app/driver/BoardingCodeCheckInTest.kt::falscher_code_abgewiesen; backend/app/src/test/kotlin/de/taktovia/app/driver/BoardingCodeCheckInTest.kt::verwendeter_code_abgewiesen; backend/app/src/test/kotlin/de/taktovia/app/driver/BoardingCodeCheckInTest.kt::code_fremder_buchung_anderer_stopp_oder_fahrzeug_abgewiesen; backend/app/src/test/kotlin/de/taktovia/app/driver/BoardingCodeCheckInTest.kt::fehlversuchsgrenze_sperrt_und_meldet_leitstelle; backend/app/src/test/kotlin/de/taktovia/app/driver/BoardingCodeCheckInTest.kt::code_stornierter_oder_abgelaufener_buchung_ungueltig; backend/app/src/test/kotlin/de/taktovia/app/driver/BoardingCodeCheckInTest.kt::fahrgastliste_nur_eigene_schicht_ohne_kontakt_und_zahlungsdaten; backend/app/src/test/kotlin/de/taktovia/app/driver/BoardingCodeCheckInTest.kt::offline_erfasster_code_gilt_erst_nach_serverpruefung; backend/app/src/test/kotlin/de/taktovia/app/driver/BoardingCodeCheckInTest.kt::offline_doppelt_verwendeter_code_ein_checkin_ein_klaerfall; backend/app/src/test/kotlin/de/taktovia/app/driver/BoardingCodeCheckInTest.kt::derselbe_code_zwei_geraete_ein_checkin; backend/app/src/test/kotlin/de/taktovia/app/driver/BoardingCodeCheckInTest.kt::storno_parallel_zu_checkin; backend/app/src/test/kotlin/de/taktovia/app/driver/BoardingCodeCheckInTest.kt::code_nie_im_klartext; backend/app/src/test/kotlin/de/taktovia/app/driver/BoardingCodeCheckInTest.kt::verbraucher_des_bestaetigungsereignisses_stellt_genau_einen_code_aus; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/BoardingCodeHashTest.kt::hash_ist_gesalzen_ueberpruefbar_und_enthaelt_den_code_nicht; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/BoardingCodeHashTest.kt::neue_codes_haben_sechs_ziffern; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/BoardingCodeHashTest.kt::code_steht_weder_in_tostring_noch_im_fingerabdruck_des_ereignisses

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.

## Umsetzung WP-M3-30 (Stand dieses Clusters)

- **Entscheidung API:** Erweiterung von `POST /v1/driver/events` um das optionale Feld `boarding_code` (nur `CHECK_IN`, `writeOnly`); kein eigener Prüfaufruf. Fahrgastliste als `GET /v1/driver/shifts/{shift_id}/passengers` (`listExpectedPassengers`, Scope `driver:run`), weil der ETag des Runs die Planrevision ist und sich die Liste mit den Ereignissen ändert.
- **Planungsannahmen (OP-18, nicht freigegeben):** 6 Ziffern, 24 Stunden gültig, 5 Fehlversuche je Buchung (`StafftimeSettings`, kein Katalogparameter), optionaler Pfeffer aus `TAKTOVIA_STAFFTIME_BOARDING_CODE_PEPPER`. **Abweichung vom Vertrag:** Hash PBKDF2-HMAC-SHA256 (JDK) statt Argon2id, weil keine Argon2-Bibliothek in den gesperrten Abhängigkeiten liegt. Ein Check-in ohne Code bleibt möglich (Regelprozess offen, OP-18 c).
- **Offline:** online ist ein falscher, verwendeter oder gesperrter Code 422 (`BOARDING_CODE_INVALID`, `_USED`, `_LOCKED`) ohne Check-in und ohne Journalzeile, der Fehlversuch bleibt gezählt; offline erfasst wird derselbe Fall `REVIEW_REQUIRED` (`CODE_INVALID`, `CODE_ALREADY_USED`, `CODE_LOCKED`). Der Code wird erst entwertet, wenn der Check-in tatsächlich wirkt; Codes fremder Buchungen (nicht im Plan der eigenen Schicht) sind 404 und zählen keinen Fehlversuch.
- **Idempotenzregister:** Der Fingerabdruck der Anfrage ersetzt `boarding_code` durch eine Marke (`RequestFingerprint.SECRET_FIELDS`), sonst wäre der SHA-256 über den Body mit 10^6 Versuchen umkehrbar.
- **Nicht gebaut:** Übergabeweg des Codes an den Fahrgast (WP-M7a-14, OP-18); HTTP-Endpunkt der Leitstelle zum Ausstellen (Modul-API `BoardingCodes.issue` mit Rolle `dispatcher` ist vorhanden); Warteschlangenanbindung des Verbrauchers `booking.confirmed` (Handler `eventConsumer` ist gebaut und getestet, die Bean liegt ohne Abonnement); Flutter-Oberfläche UI-10 (nicht ausgeführt).
- **Testaufteilung:** Die zuvor „geplant“ genannten Tests liegen im Anwendungsmodul (`backend/app`), weil sie echte Buchungen, Stopps und HTTP brauchen; `staff-time` kennt kein anderes Modul.
