# LH-6.5-OPT-011 – Standardisierte Ablehnungsgründe

- **Verantwortlicher:** Rolle Disposition (Modul `booking-dispatch`)
- **Arbeitspaket:** WP-M2-13
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Für nicht erfüllbare Anfragen liefert die Engine einen standardisierten Ablehnungsgrund.

**Originalabnahme (unverändert):** Fehlende Kapazität wird von Regelverbot oder außerhalb Gebiet unterschieden.

## Vertrag

- **Tabellen / DDL:** Neue Katalogtabelle `rejection_reason` (neue Migration nach V3, nicht in V1–V3 vorhanden; Kap. 04 §4.4); `travel_attempt.primary_rejection` (text) und `travel_attempt.secondary_rejections` (text[], DEFAULT '{}') aus V1. Unbekannter Code wird beim Schreiben abgewiesen (Fremdschlüssel bzw. Prüfung gegen `rejection_reason`). Genau ein Primärgrund je Ablehnung; weitere Ursachen nur in `secondary_rejections`.
- **API:** Feld für den normierten Ablehnungsgrund in der Antwort von `POST /v1/ride-offers` (200 mit leerer `offers`-Liste plus Grund) und in `problem+json`-Codes; 422 bei fehlerhafter Eingabe.
- **Zustandsautomat:** keiner.
- **JSON-Schema / Ereignisse:** Aufzählung/Codeliste der Gründe im Modul `booking-dispatch`; Ereignis Anfrage abgelehnt mit `attempt_id`, Kanal, Primärgrund ohne exakte Orte.
- **Parameter und Kennzahlen:** Datenbasis für Kennzahlen: `Kapazitätsablehnungen`, `Personalmangelablehnungen`, `Ausstattungsablehnungen`, `Gebietsablehnungen`, `Zeitfensterablehnungen`, `Angebotsquote` (Berechnung in M4, WP-M4-46/-51).
- **Oberfläche:** UI-01: Textbausteine je Grund mit nächster Handlung (Darstellung in WP-M2-37).
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-14 des WP-Dokuments: verbindlicher Katalog fehlt. Vorschlag des Plans: Kapazität, Personal, Ausstattung, Gebiet, Zeitfenster/Frist (getrennt Betrieb geschlossen und Frist), Regelverbot, technischer Fehler; Priorität für die Primärgrundwahl und Abgrenzung "Regelverbot" sind festzulegen. OP-38 (Routing-Timeout als technischer Fehler). Blockiert den Bau nicht, solange die Codeliste als Vorschlag mit deterministischer Priorität angelegt wird.

## Tests

- **Positiv:** Drei Testanfragen (kein freier Platz, Relation per Regel verboten, Ziel außerhalb Gebiet) liefern drei verschiedene standardisierte Gründe; dieselbe Anfrage auf demselben Datenstand ergibt denselben Primärgrund; jeder abgelehnte Versuch trägt Primärgrund und Nebengründe in `travel_attempt`.
- **Negativ:** Unbekannter Ablehnungscode wird beim Schreiben abgewiesen; technischer Fehler (z. B. Routing-Timeout) wird nicht als fachliche Ablehnung gezählt; zweiter Primärgrund je Versuch nicht möglich (weitere nur in `secondary_rejections`).
- **Konkurrierend:** Dieselbe Anfrage zweimal parallel (technischer Retry, gleiche `attempt_id`): ein Versuch, ein Primärgrund, keine Doppelzählung; mehrere gleichzeitige Ablehnungsursachen liefern deterministisch denselben Primärgrund.
- **Referenztests:** Vorbereitung PH-AT-021 (zehn Versuche, drei Ablehnungen; Auswertung in M4).
- Testfälle: PH-AT-021; web/passenger/src/booking/RejectionMessage.test.tsx::textbaustein_mit_naechster_handlung_je_grund; db/tests/test_rejection_reason.py::test_unbekannter_code_wird_abgewiesen; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/RejectionReasonTest.kt::kapazitaet_regelverbot_gebiet_ergeben_drei_gruende; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/RejectionReasonTest.kt::primaergrund_deterministisch_und_technischer_fehler_zaehlt_nicht; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/RejectionReasonTest.kt::parallele_wiederholung_einer_ablehnung_ein_versuch_ein_primaergrund; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/RejectionReasonTest.kt::katalog_in_der_datenbank_entspricht_der_codeliste; db/tests/test_rejection_reason.py::test_abfrage_jeder_abgelehnte_versuch_traegt_primaer_und_nebengruende; db/tests/test_rejection_reason.py::test_technischer_fehler_ist_keine_fachliche_ablehnung

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
