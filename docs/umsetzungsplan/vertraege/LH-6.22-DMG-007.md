# LH-6.22-DMG-007 – Statusautomat der Schadenakte

- **Verantwortlicher:** Rolle Fleet/Safety (Modul `fleet-safety`)
- **Arbeitspaket:** WP-M3-47
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Eine Schadenakte besitzt eindeutige Statusübergänge von neu über geprüft, in Bearbeitung und Reparatur bis abgeschlossen oder verworfen.

**Originalabnahme (unverändert):** Unzulässige Statussprünge werden serverseitig verhindert.

## Vertrag

- **Tabellen / DDL:** `defect_case.state` mit versioniertem CHECK auf die Werte `neu`, `geprüft`, `in Bearbeitung`, `Reparatur`, `abgeschlossen`, `verworfen` (Schreibweise der Enum-Werte im Bau festzulegen); `defect_case` führt eine Revision für `If-Match`; Statushistorie append-only (Akteur, Zeit, alter/neuer Status, Grund) mit Trigger gegen UPDATE/DELETE; Eintrag in `audit_event`. RLS wie V1.
- **API:** Statusübergänge unterhalb `/v1/defects/{id}` (Pfade im Detailvertrag festzulegen, Arbeitsname `POST /v1/defects/{id}/transitions`) mit `If-Match`. Fehler: 409 bei unzulässigem Sprung (auch bei direktem API-Aufruf), 428 ohne `If-Match`, 412 bei veralteter Revision, 404 bei Akte eines anderen Mandanten oder nicht berechtigten Dienstes, 403 ohne Rolle.
- **Zustandsautomat:** `docs/umsetzungsplan/vertraege/zustaende/defect_case.md` (neu anzulegen, im Rahmen aus WP-M1-17). Zulässige Hauptfolge: `neu` → `geprüft` → `in Bearbeitung` → `Reparatur` → `abgeschlossen`; Endzustände `abgeschlossen` und `verworfen`. Welche Abkürzungen (z. B. `neu` → `verworfen`, `in Bearbeitung` → `abgeschlossen` ohne Reparatur) und Rücksprünge zulässig sind, legt das Pflichtenheft nicht fest; die vollständige Übergangstabelle mit Rollen und Vorbedingungen ist Detailvertrag vorab. Vorgaben aus dem WP: `verworfen` nur mit Begründung; `abgeschlossen` bei sicherheitskritisch nur mit Freigabeentscheidung; aus Endzustand kein Übergang ohne dokumentierte Wiedereröffnung. `abgeschlossen`/`verworfen` heben eine bestehende Sperre nicht auf (nur WP-M3-51).
- **JSON-Schema / Ereignisse:** Statuswechsel als Ereignis über Outbox (Name Arbeitsname `defect.state_changed.v1`).
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** UI-11: nur zulässige Folgeaktionen werden angeboten; Fehlerzustand bei 409/412; fehlende Rechte.
- **Entscheidungen:** keine.
- **Offene Punkte:** vollständige Übergangstabelle mit Rollen/Vorbedingungen und Wiedereröffnungsregel sind nicht im Pflichtenheft festgelegt und vor Codebeginn mit dem Auftraggeber zu klären (OP-34 Rollen, D-11 Freigaberollen); der Rahmen und die Hauptfolge sind baubar. Fristüberwachung je Status in M7 nicht im Umfang.

## Tests

- **Positiv:** vollständiger Durchlauf beider Endzustände (`abgeschlossen`, `verworfen`); jeder zulässige Übergang in der Historie mit Akteur und Zeit.
- **Negativ:** tabellengetrieben jeder nicht in der Übergangstabelle enthaltene Übergang (z. B. `neu` → `abgeschlossen`) ergibt 409, auch über direkten API-Aufruf; Übergang ohne Rolle; `verworfen` ohne Begründung; Übergang aus Endzustand ohne Wiedereröffnung; ohne `If-Match` 428, veraltete Revision 412; Akte anderen Mandanten 404; Abschluss hebt Sperre nicht auf.
- **Konkurrierend:** zwei gleichzeitige Übergänge, genau einer gelingt, der andere 412/409.
- **Referenztests:** keine Katalog-ID im WP genannt.
- Testfälle: backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectStateMachineTest.kt::unzulaessige_uebergaenge_ergeben_409; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectStateMachineTest.kt::jeder_zulaessige_uebergang_ist_ausfuehrbar_und_in_der_historie_mit_akteur_und_zeit; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectStateMachineTest.kt::vollstaendiger_durchlauf_beider_endzustaende; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectStateMachineTest.kt::zwei_gleichzeitige_uebergaenge_genau_einer; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectStateMachineTest.kt::verworfen_ohne_grund_und_abschluss_sicherheitskritisch_ohne_freigabe_abgewiesen; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectStateMachineTest.kt::abschluss_hebt_sperre_nicht_auf; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectStateMachineTest.kt::uebergang_ohne_rolle_403_veraltet_412_fremder_mandant_404; backend/app/src/test/kotlin/de/taktovia/app/fleet/DefectStateApiTest.kt::ohne_if_match_428_veraltet_412_unzulaessig_409_ohne_rolle_403_fremder_mandant_404; backend/app/src/test/kotlin/de/taktovia/app/fleet/DefectStateApiTest.kt::uebergaenge_mit_if_match_etag_und_historie; backend/app/src/test/kotlin/de/taktovia/app/fleet/DefectStateApiTest.kt::verwerfen_mit_begruendung_und_abschluss_hebt_sperre_nicht_auf; db/tests/test_defect_state.py::test_state_check_und_historie_append_only; db/tests/test_defect_state.py::test_nur_tabellenuebergaenge_sind_speicherbar; db/tests/test_defect_state.py::test_abschluss_sicherheitskritisch_braucht_freigabeentscheidung; web/operator/src/flotte/Schadenakte.test.tsx::bietet nur zulässige Folgeaktionen an und führt den Übergang mit der gelesenen Revision aus; web/operator/src/flotte/Schadenakte.test.tsx::zeigt unzulässigen Übergang (409) und veraltete Revision (412) verständlich; Wiedereröffnung verlangt Begründung

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).

| Rolle | Person | Datum | Ergebnis |
|---|---|---|---|
| Fachanalyse | | | |
| Architektur | | | |
| QA | | | |
| Zweitprüfung (falls gefordert) | | | |
