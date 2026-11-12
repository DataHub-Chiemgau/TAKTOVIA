# LH-I.1-DOC-009 – Dublettenerkennung und Evidenzverknüpfung

- **Verantwortlicher:** Rolle Finanzen/Belege (Modul `documents-finance`)
- **Arbeitspaket:** WP-M4-32
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Belegduplikate werden anhand von Händler, Datum, Betrag, Belegnummer und Dateifingerabdruck erkannt.

**Originalabnahme (unverändert):** Zweimal importierter Tankbeleg erzeugt einen Duplikathinweis und keine doppelte Kostenbuchung.

## Vertrag

- **Tabellen / DDL:** Migration `evidence_link` (`tenant_id, id`, Beleg/Transaktion, wirtschaftliches Ereignis (FK `economic_event`), Rolle der Evidenz, Entscheidung, Entscheider, Zeitpunkt); Index `expense_hash(tenant_id, original_sha256)` (V1); Invariante „Genau eine Kostenwirkung“ (Kap. 04 §4.5) über `economic_event` UNIQUE `(tenant_id, legal_entity_id, source_system, source_ref, event_type, scenario)` (V1). Hashvergleich nur innerhalb `tenant_id`. RLS.
- **API:** Dublettenkandidaten in `GET /v1/expenses/{id}`; Entscheidung „gleiche Evidenz“ oder „eigener Vorgang“; `POST /v1/expenses/{expense_id}/approve` mit offenem Kandidaten ergibt `409 DUPLICATE_REVIEW_REQUIRED` (api/finance.yaml). `api/finance.yaml` enthält bisher nur `POST /v1/expenses/{expense_id}/approve`; die Pfade `/v1/expenses/receipts` und `/v1/expenses/{id}` (Kap. 05 §5.7) sind mit dem Bau in `api/finance.yaml` zu ergänzen (Vertrag vor Code).
- **Zustandsautomat:** Kandidat `OFFEN -> GLEICHE_EVIDENZ | EIGENER_VORGANG`; offener Kandidat blockiert die Freigabe. Sicherer Treffer wird als weitere Evidenz desselben Ereignisses verknüpft, unsicherer zur Prüfung markiert, nie automatisch gelöscht oder zusammengeführt (Kap. 03 §3 Nr. 3).
- **JSON-Schema / Ereignisse:** Ereignis „Dublettenhinweis erzeugt“ (Outbox), Entscheidung im `audit_event`.
- **Parameter und Kennzahlen:** `receipt_duplicate_threshold` (Regel, Mandant; keine automatische Vernichtung).
- **Oberfläche:** UI-14: Dublettenhinweis, Gegenüberstellung der Kandidaten, Entscheidung.
- **Entscheidungen:** keine.
- **Offene Punkte:** Trefferregeln und Schwelle (Händler, Belegnummer, Datum, Währung, Betrag, Karten-/Kassentransaktion) im Bau festzulegen; minimale Abbildung einer Tankkartentransaktion als zweite Evidenz für das Gate über den Importkanal, Bestätigung ausstehend (WP-M4 OP 5); Eindeutigkeit von `economic_event` enthält in V1 `scenario` statt Revision (OP 15). Blockiert den Bau nicht.

## Tests

- **Positiv:** identische Datei zweimal importiert ergibt Duplikathinweis und keine zweite Kostenbuchung; gleiche Fachdaten bei anderer Datei ergibt Treffer; Foto und identische Tankkartentransaktion sind Evidenzen desselben Ereignisses, genau ein Kostenereignis (E2E-065).
- **Negativ:** ähnlicher Beleg mit anderem Betrag wird als möglicher Treffer markiert, nicht gelöscht oder zusammengeführt; gleicher Hash in zweitem Mandanten ergibt keinen Treffer und keine Auskunft; Freigabe mit offenem Kandidaten ergibt 409 `DUPLICATE_REVIEW_REQUIRED`.
- **Konkurrierend (Geld):** zwei identische Belege werden parallel freigegeben: genau ein Kostenereignis.
- **Referenztests:** E2E-065, PH-AT-012.
- Testfälle: E2E-065; PH-AT-012; backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/DuplicateDetectionTest.kt::doppelter_import_ergibt_hinweis_und_ein_kostenereignis; backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/DuplicateDetectionTest.kt::foto_und_tankkartentransaktion_sind_evidenzen_desselben_tankereignisses; backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/DuplicateDetectionTest.kt::gleiche_fachdaten_bei_anderer_datei_ergeben_einen_treffer; backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/DuplicateDetectionTest.kt::aehnlicher_beleg_anderer_betrag_ist_moeglicher_treffer_nicht_geloescht_nicht_zusammengefuehrt; backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/DuplicateDetectionTest.kt::gleicher_hash_in_zweitem_mandanten_ohne_treffer_und_ohne_auskunft; backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/DuplicateDetectionTest.kt::parallele_freigabe_identischer_belege_genau_ein_kostenereignis; backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/DuplicateDetectionTest.kt::parallele_pruefungen_erzeugen_je_paar_genau_einen_treffer; backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/DuplicateDetectionTest.kt::entscheidung_recht_begruendung_revision_wiederholung_und_audit; db/tests/test_evidence_link.py::Dubletten::test_hashvergleich_ist_mandantengetrennt; backend/app/src/test/kotlin/de/taktovia/app/documents/DuplicateApiTest.kt::moeglicher_treffer_ist_einsehbar_blockiert_und_wird_mit_begruendung_entschieden; backend/app/src/test/kotlin/de/taktovia/app/documents/DuplicateApiTest.kt::ohne_recht_403_fremder_mandant_404_und_kein_treffer_ueber_mandanten

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
