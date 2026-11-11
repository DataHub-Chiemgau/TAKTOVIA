# LH-6.23-SCH-004 – Dienstplan-Entwurf getrennt vom veröffentlichten Plan

- **Verantwortlicher:** Rolle Personalplanung (Modul `staff-time`)
- **Arbeitspaket:** WP-M4-04
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Schichtplanung besitzt einen nicht veröffentlichten Entwurf und einen separat freizugebenden veröffentlichten Plan.

**Originalabnahme (unverändert):** Nicht publizierte Änderungen bleiben ausschließlich für berechtigte Planer sichtbar.

## Vertrag

- **Tabellen / DDL:** `roster_revision` (Depot, Zeitraum, Revisionsnummer, Zustand, Autor, Zeitstempel; neu in M4-Migration, Kap. 04 §4.4); `staff_shift.roster_revision` (V1, bigint) verweist darauf; RLS plus Rollenprüfung für Entwurfszeilen. Veröffentlichung ist eine neue Revision, kein Überschreiben der alten. Schnitt eines Dienstplans: je Depot und Woche (Vorschlag dieses Plans).
- **API:** `/v1/rosters` (anlegen, Entwurf lesen/ändern), `GET /v1/rosters/{roster_id}` mit ETag; `POST /v1/rosters/{roster_id}/publish` (`api/rosters.yaml`, Scope `rosters:publish`, Idempotency-Key, If-Match) veröffentlicht separat (Umsetzung WP-M4-14). Lesesichten „Planer (Entwurf + veröffentlicht)“ und „verbindlich (nur veröffentlicht)“. Ohne Planerrecht 403 bzw. 404 nach Kap. 05 §5.1.
- **Zustandsautomat:** `vertraege/zustaende/dienstplan.md` (Entwurf, veröffentlicht, ersetzt; Übergänge, Auslöser, Fehler) mit Sichtbarkeitsmatrix Rolle × Zustand; entsteht in WP-M4-01.
- **JSON-Schema / Ereignisse:** Entwurfsänderungen erzeugen keine Outbox-Ereignisse an Fahrer-App oder Disposition; Veröffentlichung erzeugt Outbox (WP-M4-14).
- **Parameter und Kennzahlen:** keine. **Oberfläche:** UI-12, Entwurf und veröffentlichte Revision prominent getrennt (WP-M4-18).
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-38 (WP-M4): Geltung des M3-Überlappungsverbots nur für verbindliche Schichten und führendes Modul für `staff_shift` sind festzulegen; blockiert diesen Vertrag nicht, aber die Migration von WP-M4-65. Dienstplanschnitt (Depot/Woche) ist Vorschlag und in WP-M4-01 zu bestätigen.

## Tests

- **Positiv:** Entwurf anlegen, ändern, lesen; Änderung im Entwurf ist für Planer mit Recht sichtbar; der veröffentlichte Plan bleibt bei Entwurfsänderungen unverändert lesbar; Veröffentlichung ist separater Schritt und erzeugt eine neue Revision.
- **Negativ:** Fahrerrolle und operative Rollen lesen keinen Entwurf (403/404); Fremdmandant erhält 404; Entwurfsänderung erzeugt keine Outbox-Ereignisse an Fahrer-App oder Disposition; Entwurfszeilen sind auch per Direktabfrage (RLS plus Rollenprüfung) nicht lesbar.
- **Konkurrierend:** zwei Planer ändern denselben Entwurf, der zweite mit altem ETag erhält 412 ohne stilles Überschreiben; Veröffentlichung parallel zu Entwurfsänderung veröffentlicht genau den geprüften Stand.
- **Referenztests:** AT-37 (Grundlage laut WP-M4-04). PH-AT-001 wird vom WP für diese Anforderung nicht genannt.
- Testfälle: AT-37; geplant:backend/staff-time/src/test/kotlin/de/taktovia/stafftime/RosterDraftVisibilityTest.kt::fahrer_sieht_entwurf_nicht; geplant:backend/staff-time/src/test/kotlin/de/taktovia/stafftime/RosterDraftVisibilityTest.kt::fremdmandant_erhaelt_404; geplant:backend/staff-time/src/test/kotlin/de/taktovia/stafftime/RosterDraftVisibilityTest.kt::entwurf_aenderung_laesst_veroeffentlichten_plan_unveraendert; geplant:backend/staff-time/src/test/kotlin/de/taktovia/stafftime/RosterDraftVisibilityTest.kt::entwurf_aenderung_erzeugt_keine_outbox_ereignisse; geplant:backend/staff-time/src/test/kotlin/de/taktovia/stafftime/RosterDraftVisibilityTest.kt::zwei_planer_alter_etag_ergibt_412; geplant:backend/staff-time/src/test/kotlin/de/taktovia/stafftime/RosterDraftVisibilityTest.kt::veroeffentlichung_parallel_zu_aenderung_veroeffentlicht_geprueften_stand; geplant:db/tests/test_roster_revision.py::test_entwurfszeilen_nur_mit_planerrecht

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Datentrennung).
