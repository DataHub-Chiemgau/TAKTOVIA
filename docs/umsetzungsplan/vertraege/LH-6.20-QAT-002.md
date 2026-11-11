# LH-6.20-QAT-002 – Release-Gate in CI: Pflichtprüfungen für Kernbuchung und Disposition

- **Verantwortlicher:** Rolle QA/Build (Release-Pipeline)
- **Arbeitspaket:** WP-M5-22
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Automatisierte Unit-, Integrations- und Contract-Tests decken Kernbuchung und Disposition ab.

**Originalabnahme (unverändert):** CI-Pipeline verweigert Release bei fehlschlagenden Pflichtprüfungen.

## Vertrag

- **Tabellen / DDL:** keine; Migration „frisch“ und „aus freigegebenem Vorstand“ (db/migrations V1/V2) ist Pflichtstufe; Datenbankintegrationstests laufen unter der Runtime-Rolle.
- **API:** (Endpunkte `/v1/ride-offers`, `/v1/bookings`, `/v1/runs/{id}/revisions` stehen noch nicht in `api/*.yaml`; sie entstehen in M2/M3 und sind Vorbedingung der Contract-Stufe.) Contract-Tests gegen `api/*.yaml` für Kernbuchung und Disposition: `/v1/ride-offers`, `/v1/bookings`, `/v1/runs/{id}/revisions`; Modul `booking-dispatch`, `workers/routing`.
- **Zustandsautomat:** keine neuen; Release-Artefakt entsteht nur bei vollständig grünem Lauf.
- **JSON-Schema / Ereignisse:** Liste der Pflichtprüfungen und Zuordnung zu Stufen: Unit, Datenbankintegration (Runtime-Rolle), API-Contract, Migration frisch/aus freigegebenem Vorstand, Architekturtest Modulgrenzen, Isolationssuite, semantische Contract-Fälle Kap. 05 §5.8, Lieferkettenprüfung. Release-Artefakt wird signiert und trägt Build-ID, Migrationsstand und die Liste der bestandenen Pflichtprüfungen (Kap. 08 §8.4: fehlgeschlagene Pflichtprüfung verhindert Release).
- **Abdeckung:** je Fachfunktion (Angebot, Hold, Confirm, Ablauf, Storno, Planrevision, Einfügeheuristik, Constraint-Validator) mindestens je ein Unit-, Integrations- und Contract-Test; Zuordnung Funktion -> Test maschinell prüfbar. Invariantentests Kap. 04 §4.5 für Kapazität mit mindestens zwei parallelen Transaktionen als Pflichtstufe. Semantische Fälle Kap. 05 §5.8: abgelaufene Holds, SCA länger als Hold, Confirm-Timeout mit spät positivem Status, 100 identische Webhooks, Manipulation `tenant_id`, unzulässige Fremd-ID, Offline-Check-in auf alter Planrevision; zusätzlich PH-AT-011.
- **Ausnahmeverfahren:** protokollierte Ausnahme nennt Freigeber, Grund und Ablauf; ohne diese drei Angaben bleibt das Release gesperrt; kein Überspringen einer Pflichtstufe ohne Ausnahme.
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** keine.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-17 (Liste der „Pflichtprüfungen“ und Abdeckungsmaß für „decken ab“ im Pflichtenheft nicht festgelegt; hier aus WP-M5-22 und Kap. 08 §8.4 abgeleitet, im Detailvertrag WP-M5-01 zu bestätigen).

## Tests

- **Positiv:** grüner Release-Lauf erzeugt signiertes Artefakt mit Build-ID, Migrationsstand und Liste bestandener Pflichtprüfungen; Funktions-Test-Zuordnung vollständig.
- **Negativ:** je ein absichtlich roter Unit-, Integrations-, Contract-, Migrations- und Isolationstest verhindert Erzeugung und Signatur des Artefakts; Versuch, eine Stufe zu überspringen, scheitert; Ausnahme ohne Freigeber, Grund oder Ablauf lässt das Release gesperrt.
- **Konkurrierend (Kapazität, Idempotenz):** PH-AT-004 (letzter Platz unter Konkurrenz) und PH-AT-011 (gleicher Idempotenzschlüssel, abweichender Request) als Pflichtstufe mit mindestens zwei parallelen Transaktionen.
- **Referenztests:** PH-AT-004, PH-AT-011.
- Testfälle: PH-AT-004; PH-AT-011; geplant:tests/acceptance/release/test_release_gate.py::test_gruener_lauf_artefakt_mit_build_id_migrationsstand_pruefliste; geplant:tests/acceptance/release/test_release_gate.py::test_je_stufe_roter_test_verhindert_signatur; geplant:tests/acceptance/release/test_release_gate.py::test_ueberspringen_einer_pflichtstufe_ohne_ausnahme_scheitert; geplant:tests/acceptance/release/test_release_gate.py::test_semantische_contract_faelle_sind_pflichtstufe; geplant:tests/acceptance/release/test_release_gate.py::test_kapazitaetsinvarianten_mit_zwei_parallelen_transaktionen_pflichtstufe; geplant:tests/acceptance/release/test_release_gate.py::test_roter_pflichttest_verhindert_release_artefakt; geplant:tests/acceptance/release/test_release_gate.py::test_ausnahme_ohne_freigeber_grund_ablauf_gesperrt; geplant:tests/acceptance/release/test_release_gate.py::test_funktion_zu_test_zuordnung_vollstaendig

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
