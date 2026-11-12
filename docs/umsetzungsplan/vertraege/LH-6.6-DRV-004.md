# LH-6.6-DRV-004 – Planänderungen anzeigen und kontrolliert übernehmen

- **Verantwortlicher:** Rolle Fahrer-Backend (Modul `staff-time`, Planrevision über `booking-dispatch`) und Fahrer-App (`mobile/driver`); ein Modul `driver` gibt es nicht (OP-07)
- **Arbeitspaket:** WP-M3-27
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Dynamische Planänderungen werden deutlich angezeigt und bestätigt beziehungsweise kontrolliert übernommen.

**Originalabnahme (unverändert):** Im Test wird eine neu eingespielte Route zeitnah sichtbar.

## Vertrag

- **Tabellen / DDL:** Zusatzspeicher für die je Fahrer bestätigte Planrevision (Tabelle aus WP-M3-02, Name im Bau festzulegen; Felder `shift_id`, `driver_ref`, `confirmed_revision`, `confirmed_at`, Gerät), mit `tenant_id`/RLS. Planrevision stammt aus `vehicle_run.revision` bzw. WP-M2-28.
- **API:** `GET /v1/driver/shifts/{id}/run` (in `api/driver.yaml` noch nicht enthalten; bedingter Abruf, `ETag` = Planrevision; 304 bei unverändert). Bestätigung der Revision: Pfad in `api/driver.yaml` noch nicht vorhanden, vor Codebeginn zu ergänzen (idempotent; Bestätigung einer veralteten Revision wird über `If-Match` mit 412 `REVISION_MISMATCH` und aktuellem ETag abgewiesen: Vertragskorrektur gegenüber der ersten Fassung, die 409 nannte; `api/driver.yaml` führt 412). Zustellung: Änderungsstrom aus WP-M2-28 bzw. Live-Kanal WP-M3-35 bei geöffneter App; bei geschlossener App technischer Weckruf über den Push-Kanal (WP-M2-32), Nutzlast ohne Fahrgast- oder Ortsdaten, nur Hinweis auf neue Revision. Ein Fahrerereignis auf nicht bestätigter, veralteter Revision ergibt `REVIEW_REQUIRED` (`STALE_PLAN`, siehe DRV-005).
- **Zustandsautomat:** Revisionsstatus der Fahrersicht (aktuell, neu unbestätigt, bestätigt, vergangen) in `vertraege/zustaende/plan_revision_fahrer.md` (zu erstellen), inklusive Regel, welche Änderungen bestätigt werden müssen und welche kontrolliert automatisch übernommen werden.
- **JSON-Schema / Ereignisse:** Diff-Darstellung zwischen Revisionen (neu, entfallen, verschoben) im Projektionsschema aus DRV-002; Hinweis an die Leitstelle bei ausbleibender Bestätigung.
- **Parameter und Kennzahlen:** Zielwert Fahrerplan p95 ≤ 10 s bei bestehender Verbindung, Messpunkt „Commit bis dargestellte Clientrevision" (Kap. 08, Zielwerttabelle „Fahrerplan“).
- **Oberfläche:** UI-10 Änderungsanzeige (neu, entfallen, verschoben) mit Bestätigung; kein Erfassungs- oder Bestätigungsdialog während der Fahrbewegung, Bestätigung beim nächsten Halt (Kap. 06 §6.7); Zustand „vergangene Revision"; offline letzter Plan mit Stand.
- **Entscheidungen:** keine (D-05 berührt den Push-Provider).
- **Offene Punkte:** OP-29 (Zustellweg bei geschlossener App, Weckruf und Provider; fachlicher Fahrer-Push ist `LH-6.12-COM-007`, P2). Die Regel bestätigungspflichtig vs. automatisch übernommen und die Erkennung der Fahrbewegung sind im Pflichtenheft nicht festgelegt und vor Codebeginn im Zustandsautomaten zu bestimmen; Lasttest unter Referenzlast erst in M5.

## Tests

- **Positiv:** Zeitmessung Commit bis dargestellte Clientrevision über 100 Planänderungen, p95 ≤ 10 s; neu eingespielte Route erscheint gekennzeichnet gegenüber der Vorrevision; Bestätigung wird je Fahrer serverseitig gespeichert.
- **Negativ:** Push geht verloren, Abruf beim nächsten Sync liefert die neue Revision; Bestätigung einer veralteten Revision wird abgewiesen; offline keine vermeintlich aktuelle Route; Push-Nutzlast enthält keine Fahrgast-/Ortsdaten; kein Dialog während der Fahrbewegung; Bestätigung einer fremden Schicht (404); Hinweis an die Leitstelle bei ausbleibender Bestätigung wird erzeugt; Fahrerereignis auf nicht bestätigter, veralteter Revision wird nicht still angewendet (`REVIEW_REQUIRED`, `STALE_PLAN`).
- **Konkurrierend:** zwei Planrevisionen kurz hintereinander, die App endet auf der neuesten und bestätigt keine übersprungene Revision; Bestätigung derselben Revision von zwei Geräten desselben Fahrers ergibt einen Eintrag.
- **Referenztests:** kein Katalogfall im WP genannt.
- Testfälle: backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/PlanRevisionDeliveryTest.kt::neue_route_innerhalb_p95_dargestellt; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/PlanRevisionDeliveryTest.kt::bestaetigung_wird_je_fahrer_gespeichert; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/PlanRevisionDeliveryTest.kt::bestaetigung_veralteter_revision_abgewiesen; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/PlanRevisionDeliveryTest.kt::bestaetigung_fremder_schicht_ergibt_404; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/PlanRevisionDeliveryTest.kt::push_nutzlast_ohne_fahrgast_und_ortsdaten; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/PlanRevisionDeliveryTest.kt::verlorener_push_abruf_liefert_neue_revision; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/PlanRevisionDeliveryTest.kt::ausbleibende_bestaetigung_hinweis_an_leitstelle; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/PlanRevisionDeliveryTest.kt::ereignis_auf_unbestaetigter_veralteter_revision_review_required; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/PlanRevisionDeliveryTest.kt::zwei_revisionen_hintereinander_endet_auf_neuester; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/PlanRevisionDeliveryTest.kt::bestaetigung_zwei_geraete_ein_eintrag; backend/app/src/test/kotlin/de/taktovia/app/driver/PlanRevisionDeliveryHttpTest.kt::neue_route_innerhalb_p95_dargestellt; backend/app/src/test/kotlin/de/taktovia/app/driver/PlanRevisionDeliveryHttpTest.kt::bestaetigung_ueber_http_428_412_200_zwei_geraete_fremde_schicht_und_rolle; backend/app/src/test/kotlin/de/taktovia/app/driver/PlanRevisionDeliveryHttpTest.kt::verlorener_push_abruf_liefert_neue_revision_und_bedingter_abruf; mobile/driver/test/plan_aenderung_test.dart::aenderung_gekennzeichnet_neu_entfallen_verschoben; mobile/driver/test/plan_aenderung_test.dart::kein_bestaetigungsdialog_waehrend_fahrbewegung; mobile/driver/test/plan_aenderung_test.dart::offline_zeigt_letzten_plan_mit_stand

## Festlegungen des Baus (WP-M3-27)

- Regel, Vergleichsbasis und Zustände: `zustaende/plan_revision_fahrer.md` (Planungsannahmen, fachlich zu bestätigen). Live-Kanal und Weckruf: `m3-live-alarm.md` §1 und §2.
- Messpunkt „Commit bis dargestellte Clientrevision“: `PlanRevisionDeliveryHttpTest::neue_route_innerhalb_p95_dargestellt` über 100 echte Planänderungen (Live-Kanal, Abruf mit ETag); der Test gibt p95 und Maximum aus. Lasttest unter Referenzlast bleibt M5.
- **Nicht gebaut:** Zustellung des Weckrufs an ein Gerät (Anbieter und Gerätetoken, OP-29, D-05): `PlanRevisionWakeup` liefert nur die Nutzlast. **Nicht ausgeführt:** Flutter-Anzeige und -Tests (Flutter nicht verfügbar).

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
