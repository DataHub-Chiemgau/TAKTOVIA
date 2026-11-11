# LH-I.6-KPI-001 – Eindeutiger Versuchseintrag je Fahrtanfrage

- **Verantwortlicher:** Rolle Analytics (Modul `analytics`; Erfassung durch `booking-dispatch`, WP-M2-11)
- **Arbeitspaket:** WP-M4-47
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Jede Fahrtanfrage besitzt einen eindeutigen Versuchseintrag unabhängig davon, ob ein Angebot zustande kommt.

**Originalabnahme (unverändert):** Zehn Anfragen mit drei Ablehnungen bleiben als zehn Nachfrageereignisse erhalten.

## Vertrag

- **Tabellen / DDL:** `travel_attempt` (V1): `tenant_id`, `id` (= `attempt_id`), `created_at`, `channel`, `primary_rejection`, `secondary_rejections text[] NOT NULL DEFAULT '{}'`, `coarse_origin`, `coarse_destination`, PK `(tenant_id,id)` (erzwingt Eindeutigkeit je Mandant), RLS wie V1. Regel „eine `attempt_id` je fachlich neuer Suche; technische Retries zählen nicht neu“ (Kap. 04 §4.3): derselbe Idempotenzschlüssel (`idempotency_record`) ergibt denselben Datensatz. Ein Versuch ohne Angebot bleibt bestehen und ist nicht löschbar: `R__runtime_grants.sql` gewährt der Runtime-Rolle auf `travel_attempt` nur SELECT, INSERT, UPDATE, kein DELETE (Prüfung gegen die Grant-Datei). Übernahme in die Faktenbasis (WP-M4-45, Fakten ohne Fahrgast-Klartext) mit Abgleichprüfung Versuche gegen Ereignisprotokoll.
- **API:** `GET /v1/analytics/metrics` (Kennzahlen `Anfragen`, `Technische Anfragefehler`; Wert, Nenner, `definition_version`, `as_of`). 404 für Versuche fremder Mandanten.
- **Zustandsautomat / Schema / Ereignisse:** keine neuen; gelesen wird das Ereignis Anfrage aus M2 (WP-M2-11).
- **Parameter und Kennzahlen:** `Anfragen` (Anzahl eindeutiger gültiger Such-/Buchungsversuche; wiederholte neue Versuche zählen separat), `Technische Anfragefehler` (Anfragen mit technischem Fehler vor Angebot, separat von operativen Ablehnungen; Nenner gültige Anfrageversuche; Katalog K.1).
- **Oberfläche:** keine eigene (Funnel in WP-M4-59/-60).
- **Entscheidungen:** keine.
- **Offene Punkte:** Definition „gültiger Anfrageversuch“, Abgrenzung technischer Retries und Ausschluss von Testanfragen gemeinsam mit M2 festzulegen (WP-M4-47, Detailverträge vorab; Vorschlag: Retry = gleicher Idempotenzschlüssel); OP-27 (Funnelereignisse). Blockiert den Bau nicht.

## Tests

- **Positiv:** Fixture mit zehn Anfragen und drei Ablehnungen ergibt zehn Nachfrageereignisse in `travel_attempt` und `Anfragen` = 10 (PH-AT-021); ein Versuch ohne Angebot ist in der Statistik vorhanden; eine fachlich neue Suche desselben Fahrgasts (neuer Idempotenzschlüssel) zählt als eigener Versuch; `Technische Anfragefehler` wird getrennt von Ablehnungen ausgewiesen.
- **Negativ:** technischer Retry mit gleichem Idempotenzschlüssel erzeugt keinen zweiten Versuch; Versuch eines fremden Mandanten erscheint nicht (RLS/404); Löschen eines Versuchs ist für die Runtime-Rolle nicht möglich; doppelte `(tenant_id,id)` wird abgewiesen; Testanfragen sind ausgeschlossen (Abgrenzung offen, s. o.); Abgleich Versuche gegen Ereignisprotokoll meldet eine Abweichung.
- **Konkurrierend (Datentrennung):** parallele identische Suchanfragen (gleicher Idempotenzschlüssel) ergeben genau einen Versuch; parallele Anfragen von Mandant A und B vermischen sich nicht.
- **Referenztests:** PH-AT-021.

Testfälle: PH-AT-021; geplant:backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/AttemptCountTest.kt::zehn_anfragen_drei_ablehnungen_ergeben_zehn_nachfrageereignisse; geplant:backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/AttemptCountTest.kt::versuch_ohne_angebot_ist_in_statistik_vorhanden; geplant:backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/AttemptCountTest.kt::neue_suche_desselben_fahrgasts_zaehlt_separat; geplant:backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/AttemptCountTest.kt::technischer_retry_zaehlt_nicht_doppelt; geplant:backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/AttemptCountTest.kt::technische_anfragefehler_getrennt_von_ablehnungen; geplant:backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/AttemptCountTest.kt::testanfragen_sind_ausgeschlossen; geplant:backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/AttemptCountTest.kt::abgleich_versuche_gegen_ereignisprotokoll_meldet_abweichung; geplant:backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/AttemptCountTest.kt::parallele_identische_suchanfragen_ein_versuch; geplant:backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/AttemptCountTest.kt::parallele_anfragen_zweier_mandanten_vermischen_sich_nicht; geplant:db/tests/test_travel_attempt.py::test_versuch_nicht_loeschbar_und_mandantengetrennt

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Datentrennung).
