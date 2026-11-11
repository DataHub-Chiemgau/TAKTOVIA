# LH-6.21-CHK-013 – Prüfabschluss und unveränderlicher Prüfnachweis

- **Verantwortlicher:** Rolle Flottensicherheit (Modul `fleet-safety`)
- **Arbeitspaket:** WP-M3-14
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Der Prüfabschluss enthält Prüfer-ID, Fahrzeug-ID, Schicht-ID, Vorlagenversion, Zeitstempel, Ergebnis und unveränderliche Einzelantworten.

**Originalabnahme (unverändert):** Eine abgeschlossene Prüfung ist später vollständig und unverfälscht rekonstruierbar.

## Vertrag

- **Tabellen / DDL:** `inspection_run` (Prüfer-ID, Fahrzeug-ID, Schicht-ID `staff_shift.id`, Vorlagenrevision als Verweis auf `checklist_template_revision`, Zeitstempel Gerät/Server, Ergebnis; diese Kopfdaten sind nach Abschluss ebenfalls unveränderlich, damit die Prüfung „unverfälscht rekonstruierbar“ ist), `inspection_answer` (verweist auf `checklist_step`-Revision; unveränderlich nach Abschluss). In V1–V3 noch nicht vorhanden, DDL aus WP-M3-01/-02. Invarianten: RLS über `tenant_id`; DB-seitiger Schutz gegen UPDATE/DELETE auf Antworten eines abgeschlossenen Prüflaufs (Trigger nach dem Muster `reject_mutation()` aus V1 (ERRCODE 23514, wie `audit_immutable` auf `audit_event`) oder Rechteentzug in `R__runtime_grants.sql`, Muster `audit_event`: „Kein Überschreiben"); Fremdschlüssel auf unveränderliche Schrittrevisionen, damit spätere Vorlagenänderungen die Historie nicht verändern. Abschluss, Antworten, Ergebnis, `resource_release` (über WP-M3-08) und Outbox in einer Transaktion.
- **API:** `POST /v1/inspections/{id}/submit` (Idempotency-Key, `If-Match`), `GET /v1/inspections/{id}` mit Vorlage und Antworten im Originalstand. Wiederholtes Submit mit gleichem Schlüssel liefert dasselbe Ergebnis ohne zweite Freigabe; anderer Inhalt unter gleichem Schlüssel: `409 IDEMPOTENCY_CONFLICT`; fehlende Pflichtantwort/Wert außerhalb Eingabetyp: 422 mit Elementverweis; fremde Prüfung: 404. fehlender `If-Match` 428, veraltete Revision 412 `REVISION_MISMATCH` (Kap. 05 §5.1); Prüfer-ID aus der Sitzung, Serverzeitstempel serverseitig; Endpunkte `/v1/inspections` sind in `api/*.yaml` noch nicht vorhanden (Kap. 05 §5.7 nennt sie); Clientangaben zu Prüfer, Mandant, Ergebnis werden ignoriert bzw. abgewiesen.
- **Zustandsautomat:** Prüflauf (neu zu schreiben unter `vertraege/zustaende/pruefung.md`): in Bearbeitung -> abgeschlossen mit Ergebnis bestanden | eingeschränkt | gesperrt | technisch ungeklärt (Ergebnisregel als Detailvertrag vorab).
- **JSON-Schema / Ereignisse:** Outbox-Ereignis „Prüfung abgeschlossen" mit Ergebnis, ohne Freitext und Personendetails; Eintrag in `audit_event` (Felder `actor_ref`, `action`, `object_ref`, `occurred_at`, `correlation_id`, `redacted_changes`; ohne Freitext).
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** UI-09 – Abschlussseite zeigt „freigegeben" erst nach Serverantwort; UI-11 – Prüfhistorie je Fahrzeug mit ursprünglichen Fragen und Antworten.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-16 (vierte Antwort „Nicht prüfbar" und Wirkung auf Ergebnis), OP-17 (Status „eingeschränkt" außerhalb Rollstuhlausstattung); die Ergebnisregel ist vor Codebeginn als Detailvertrag festzulegen. Export/Archivfristen sind nicht Teil (AT-53, M5).

## Tests

- **Positiv:** vollständige Prüfung wird abgeschlossen, Nachweis enthält Prüfer-ID, Fahrzeug-ID, Schicht-ID, Vorlagenversion, Zeitstempel, Ergebnis und alle Einzelantworten (AT-27); nach späterer Vorlagenänderung zeigt die Historie ursprüngliche Fragen und Antworten (AT-32).
- **Negativ:** Submit mit fehlender Pflichtantwort bzw. Wert außerhalb des Eingabetyps (422 mit Elementverweis); fehlender `If-Match` (428) bzw. veraltete Revision (412); Submit durch Fahrer einer anderen Schicht oder aus anderem Mandanten (404); Submit fremder Prüfung (404); UPDATE/DELETE auf Antworten nach Abschluss scheitern auf API- und DB-Ebene; Clientwerte für Prüfer/Mandant/Ergebnis werden nicht übernommen; anderer Inhalt unter gleichem Idempotency-Key ergibt 409.
- **Konkurrierend:** zwei gleichzeitige Submits – genau ein Abschluss und eine Freigabe; Submit parallel zu einer neuen Sperre – keine Freigabe; Submit und Wiederholung mit gleichem Idempotency-Key parallel – ein Ergebnis.
- **Referenztests:** AT-27, AT-32 (beide in `e2e.csv` vorhanden).
- Testfälle: AT-27; AT-32; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/InspectionSubmitTest.kt::abschluss_enthaelt_alle_pflichtangaben; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/InspectionSubmitTest.kt::historie_zeigt_urspruengliche_fragen_nach_vorlagenaenderung; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/InspectionSubmitTest.kt::fehlende_pflichtantwort_oder_falscher_eingabetyp_ergibt_422; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/InspectionSubmitTest.kt::fremde_pruefung_ergibt_404; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/InspectionSubmitTest.kt::clientwerte_pruefer_mandant_ergebnis_ignoriert_oder_abgewiesen; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/InspectionSubmitTest.kt::gleicher_schluessel_anderer_inhalt_ergibt_409_idempotency_conflict; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/InspectionSubmitTest.kt::if_match_fehlt_428_veraltet_412; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/InspectionSubmitTest.kt::mutation_nach_abschluss_ueber_api_abgewiesen; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/InspectionSubmitTest.kt::gleichzeitige_submits_genau_ein_abschluss; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/InspectionSubmitTest.kt::submit_parallel_zu_neuer_sperre_keine_freigabe; geplant:db/tests/test_inspection_immutability.py::test_antworten_abgeschlossener_pruefung_unveraenderlich; geplant:db/tests/test_inspection_immutability.py::test_kopfdaten_abgeschlossener_pruefung_unveraenderlich; geplant:web/operator/src/inspection/InspectionHistory.test.tsx::historie_zeigt_ursprungsfragen_und_antworten

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
