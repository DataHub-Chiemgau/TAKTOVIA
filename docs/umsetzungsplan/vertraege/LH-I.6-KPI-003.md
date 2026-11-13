# LH-I.6-KPI-003 – Ablehnungscodes für Kapazität, Fahrzeug, Fahrer, Schichtende, Ausstattung

- **Verantwortlicher:** Rolle Analytics (Modul `analytics`; Zuordnungsregel mit `booking-dispatch`, WP-M2-15)
- **Arbeitspaket:** WP-M4-49
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Ablehnungsgründe umfassen insbesondere keine Kapazität, kein Fahrzeug, kein Fahrer, Schichtende und falsche Ausstattung.

**Originalabnahme (unverändert):** Die fünf Testfälle liefern unterschiedliche Codes und gleiche Berichtsspalte.

## Vertrag

- **Tabellen / DDL:** `rejection_reason` (Seed der fünf Codes, Gruppen und Texte; Tabelle aus WP-M2-13), `travel_attempt.primary_rejection`/`secondary_rejections`. Fünf Codes (Vorschlag, Benennung im Katalog zu bestätigen): keine Kapazität, kein Fahrzeug, kein Fahrer, Schichtende, falsche Ausstattung; je ein eigener Code, alle in derselben Berichtsspalte „Ablehnungsgrund“. Zuordnungsregel aus dem Ergebnis des Kandidatenfilters (WP-M2-15) inkl. Schichtbesetzung aus dem Leseport von WP-M4-17; bei mehreren zutreffenden Gründen ein Primär- und ein Sekundärgrund.
- **API:** `GET /v1/analytics/metrics` (Ablehnungen nach Grund; Spalte „Ablehnungsgrund“ identisch für alle fünf Fälle).
- **Zustandsautomat / Schema / Ereignisse:** keine.
- **Parameter und Kennzahlen:** `Kapazitätsablehnungen`, `Personalmangelablehnungen`, `Ausstattungsablehnungen` (Katalog K.1); Zuordnung (Vorschlag, in WP-M4-49 festzuschreiben): keine Kapazität/kein Fahrzeug zu Kapazität, kein Fahrer/Schichtende zu Personalmangel, falsche Ausstattung zu Ausstattung. Alarm ab Ablehnungszahl (`capacity_reject_alert_count`) ist nicht Teil (KPI-021, M8).
- **Oberfläche:** Ablehnungsstatistik im Kennzahlenteil (WP-M4-59/-60): eine Spalte „Ablehnungsgrund“ mit den fünf Codes; Leerzustand ohne Ablehnungen.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-25 (Abgrenzung „keine Kapazität“ gegen „kein Fahrzeug“ und Rangfolge bei mehreren Gründen; Katalog K.1 kennt nur Kapazität/Personal/Ausstattung); OP-26 betrifft Partnerausfall, nicht diese Anforderung. Bau mit deterministischer Rangfolge als Vorschlag nicht blockiert; E2E-080/E2E-081 brauchen die M2-Angebotsbildung, E2E-081 mit Schichtbesetzung ist erst nach Anbindung des Ports an die Disposition (WP-M4-65) vollständig nachweisbar.

## Tests

- **Positiv:** fünf Testfälle (keine Kapazität, kein Fahrzeug, kein Fahrer, Schichtende, falsche Ausstattung) liefern fünf unterschiedliche Codes in derselben Berichtsspalte; die Codes werden den Katalogkennzahlen laut Zuordnung zugerechnet; E2E-080: alle Fahrzeuge im Gebiet ausgelastet, Anfrage und Normgrund erscheinen in der Statistik.
- **Negativ:** Kombination zweier Gründe ergibt einen Primär- und einen Sekundärgrund, keine zweite Ablehnung; E2E-081: freies Fahrzeug ohne einsatzbereiten Fahrer ergibt Primärgrund Personalmangel und keine Kapazitätszusage; unbekannter Code wird abgewiesen.
- **Konkurrierend (Kapazität):** letzter freier Platz gleichzeitig von zwei Anfragen: eine Zusage, eine Ablehnung mit genau einem Primärgrund; Schichtende während der Angebotsbildung ergibt deterministisch denselben Code bei Wiederholung auf gleichem Datenstand.
- **Referenztests:** E2E-080, E2E-081 (mit M2-Angebotsbildung).

Testfälle: E2E-080; E2E-081; backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/RejectionCodesTest.kt::fuenf_faelle_ergeben_fuenf_codes_in_gleicher_berichtsspalte; backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/RejectionCodesTest.kt::codes_werden_katalogkennzahlen_zugeordnet; backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/RejectionCodesTest.kt::zwei_gruende_ergeben_primaer_und_sekundaergrund; backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/RejectionCodesTest.kt::unbekannter_code_wird_abgewiesen; backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/RejectionCodesTest.kt::letzter_platz_parallel_genau_eine_zusage; backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/RejectionCodesTest.kt::schichtende_waehrend_angebotsbildung_gleicher_code_auf_gleichem_datenstand; geplant:web/operator/src/kennzahlen/RejectionStats.test.tsx::eine_spalte_ablehnungsgrund_mit_fuenf_codes; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/RejectionResourceCodesTest.kt::fuenf_ressourcenfaelle_liefern_fuenf_unterschiedliche_primaergruende; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/RejectionResourceCodesTest.kt::freies_fahrzeug_ohne_einsatzbereiten_fahrer_ist_personalmangel_ohne_zusage

Umsetzung (wave13-c6, WP-M4-49): neue Codes `NO_VEHICLE` (leerer Pool) und `SHIFT_ENDED` (Schicht mit Fahrer begann vor dem Fenster und endet darin), Rangfolge nach Priorität: kein Fahrzeug (85), Ausstattung (90), kein Fahrer (100), Schichtende (105), keine Kapazität (110); Zuordnung wie im Vorschlag (Kapazität: keine Kapazität/kein Fahrzeug, Personal: kein Fahrer/Schichtende, Ausstattung). Der UI-Test `RejectionStats.test.tsx` gehört zu WP-M4-59; E2E-080/-081 über HTTP erst nach Anbindung des Kandidatentors an die Angebotsbildung (heute `PASS_THROUGH`).

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Kapazität).
