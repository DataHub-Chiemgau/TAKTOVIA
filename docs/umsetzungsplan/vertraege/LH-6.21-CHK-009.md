# LH-6.21-CHK-009 – Technikbereitschaft und Technikereignis

- **Verantwortlicher:** Rolle Betrieb (Modul `inspection`, Alarm über `alerting`)
- **Arbeitspaket:** WP-M3-18
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Fahrer erfassen vor Schichtbeginn die Funktionsfähigkeit von Fahrer-App, Mobilfunkverbindung, Navigation und notwendigem dienstlichen Zubehör.

**Originalabnahme (unverändert):** Fehler erzeugen ein Technikereignis und einen für die Leitstelle sichtbaren Hinweis.

## Vertrag

- **Tabellen / DDL:** Kern-DDL V1 (`db/migrations/V1__kernschema.sql`) enthält nur `vehicle` (`active_block`, `safety_revision`, `capacity_config`), `staff_shift` und `resource_release` (`vehicle_id`, `shift_id`, `safety_revision`, `inspection_ref`, `qualification_revision`, `valid_until`, `revoked_at`); die CHK-Tabellen `checklist_template_revision`, `checklist_step`, `inspection_run`, `inspection_answer` (Kap. 04 §4.4) fehlen in V1/V2/V3 und kommen mit einer neuen Migration `V<n>__inspection.sql` (WP-M3-10/-14). Prüfelemente der Gruppe Technik: je ein Punkt für Fahrer-App, Mobilfunkverbindung, Navigation, dienstliches Zubehör (Seed in `checklist_step`). Technikereignis: Alarm vom Typ „Technikereignis“ (Alarmtypen-Katalog P1, WP-M3-52) in `business_alert` (Kap. 04 §4.4; in V1/V2/V3 nicht vorhanden, DDL mit WP-M3-52) mit Bezug auf Prüflauf, Fahrzeug und Schicht; Eindeutigkeit über das Quellereignis aus Prüflauf+Schritt und den Dedupe `(policy,event,recipient)`, damit Wiederholung kein zweites Ereignis erzeugt. `tenant_id` und RLS; Sichtbarkeit nach Dienstzugehörigkeit.
- **API:** Antwortabgabe/`POST /v1/inspections/{id}/submit`: negatives Technikergebnis erzeugt genau ein Technikereignis und einen Alarm niedriger Priorität (über WP-M3-52); `Idempotency-Key` und `If-Match` laut WP-M3-14; wiederholte Übermittlung desselben Prüfschritts erzeugt kein zweites Ereignis. Der Alarm enthält keine Fahrgast-PII und keine Diagnosen. Leitstelle liest den Hinweis über Fahrzeug-/Arbeitslistenansicht (Pfad aus WP-M3-36/-52). 403/404 für nicht berechtigten Dienst/Mandanten.
- **Zustandsautomat:** Prüflauf; optional Ergebnis „technisch ungeklärt“, sofern die Fachseite das Element so einstuft (OP-16).
- **JSON-Schema / Ereignisse:** Ereignis `Technikereignis gemeldet` über Outbox (Schema unter `api/events/` mit dem Bau).
- **Parameter und Kennzahlen:** Alarmpriorität „niedrig“ nach `alert_policy_revision` (WP-M3-52); keine neuen Parameter.
- **Oberfläche:** UI-09: Technikschritte; UI-07: Hinweis am Fahrzeug und in der Arbeitsliste. Zustände: kein Ereignis (leer), Fehler, Rechte nach Dienst.
- **Entscheidungen:** keine
- **Offene Punkte:** Katalog der Technikpunkte und ihre Wirkung auf die Freigabe (WP „Detailverträge vorab“) offen; bis dahin gilt: Technikfehler erzeugt Ereignis und Hinweis, sperrt aber nicht. OP-16 (technisch ungeklärt).

## Tests

- **Positiv:** alle vier Technikpunkte funktionsfähig: kein Technikereignis; Vorlage enthält je einen Punkt für Fahrer-App, Mobilfunk, Navigation, Zubehör.
- **Negativ:** Navigationsfehler erzeugt genau ein Technikereignis mit Prüflauf, Fahrzeug, Schicht und sichtbaren Hinweis am Fahrzeug; Duplikat desselben Schritts erzeugt kein zweites Ereignis; Disponent eines nicht berechtigten Dienstes oder fremden Mandanten sieht den Hinweis nicht (403/404); der Alarm enthält keine Fahrgast-PII und keine Diagnosen; Technikfehler sperrt das Fahrzeug nicht (Annahme laut Offene Punkte).
- **Konkurrierend:** derselbe Prüfschritt wird parallel zweimal übermittelt: genau ein Technikereignis und ein Hinweis.
- **Referenztests:** keine Katalog-ID im WP genannt

Testfälle: geplant:backend/inspection/src/test/kotlin/de/taktovia/inspection/TechReadinessTest.kt::technikfehler_erzeugt_genau_ein_ereignis_und_leitstellenhinweis; geplant:backend/inspection/src/test/kotlin/de/taktovia/inspection/TechReadinessTest.kt::hinweis_nur_fuer_berechtigten_dienst_und_mandanten_sichtbar; geplant:backend/inspection/src/test/kotlin/de/taktovia/inspection/TechReadinessTest.kt::parallele_uebermittlung_desselben_schritts_erzeugt_ein_ereignis; geplant:db/tests/test_inspection_tech_event.py::test_duplikat_erzeugt_kein_zweites_ereignis

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
