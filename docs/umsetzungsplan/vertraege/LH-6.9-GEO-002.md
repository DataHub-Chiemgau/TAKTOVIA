# LH-6.9-GEO-002 – Haltepunktrevisionen mit Aktivstatus

- **Verantwortlicher:** Rolle Fachmodul `service-geo`
- **Arbeitspaket:** WP-M2-05
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Haltestellen besitzen Koordinate, Identifikator, Aktivstatus und zulässige Verkehrsprodukte.

**Originalabnahme (unverändert):** Inaktive Haltestelle wird nicht als buchbar angeboten.

## Vertrag

- **Tabellen / DDL:** `stop_revision` (V1): `tenant_id`, `id`, `stop_id` (stabiler Identifikator), `revision`, `point geometry(Point,4326) NOT NULL`, `name`, `accessibility jsonb NOT NULL`, `approved_by text NOT NULL`, `valid_from`, `valid_to`, `UNIQUE(tenant_id,stop_id,revision)`, Index `stop_geo` (gist), RLS. Neue Migration: Aktivstatus (Spalte `active boolean NOT NULL`, Name im Bau) und Zuordnung zulässiger Verkehrsprodukte Haltepunkt ↔ `service` (im Kern-DDL nicht vorhanden, OP-7; wie ein „Verkehrsprodukt“ am `service` modelliert wird, ist offen, OP-6: eigene Tabellen oder Werte in `config_revision.values_json`); mindestens ein zulässiges Verkehrsprodukt je Haltestelle, in der Datenbank abgesichert. Die Wertebereichsprüfung der Koordinate hat V1 nicht (`geometry(Point,4326)` erzwingt den Wertebereich nicht): Prüfung in der Migration (CHECK) und vor Persistenz im Dienst. Wirksame Revision zum Zeitpunkt t: `valid_from <= t < valid_to` (links geschlossen, rechts offen), Aktivstatus dieser Revision maßgeblich. Koordinate im Wertebereich WGS84 (Lat -90..90, Lon -180..180). Deaktivierung ist eine neue Revision, keine Änderung der alten. `accessibility`: JSON-Schema mit Mindestfeld Rollstuhl-Ein-/Ausstieg `geeignet | ungeeignet | ungeprueft` und gespeicherter Schema-Version (OP-41).
- **API:** `/v1/stops`: `GET` Liste mit Filtern Dienst/Umkreis/aktiv, `GET /{id}` mit `ETag`, `POST` neue Revision (`If-Match`, veraltet → 412 `REVISION_MISMATCH`, fehlend → 428 `PRECONDITION_REQUIRED`), Deaktivierung als Revision. Ungültige Koordinate, Schemaverletzung `accessibility` oder fehlendes Verkehrsprodukt → 422 (`application/problem+json`, `violations[].pointer`), nichts gespeichert. `/v1/stops` steht nur im Endpunktkatalog (Kap. 05 §5.7), nicht in `openapi.yaml`; OpenAPI entsteht in WP-M2-01/WP-M2-05. Fremder Mandant → 404 `NOT_FOUND`, ohne Stammdatenrecht → 403, jede Änderung auditiert, `approved_by` gesetzt. Fahrgastsuche und Angebotsbildung liefern nur aktive, zum Zeitpunkt wirksame Haltepunkte, deren Verkehrsprodukt für den Dienst zugelassen ist (Fachoperation „buchbare Haltepunkte für Dienst, Zeitpunkt, Umkreis“).
- **Zustandsautomat:** keiner.
- **JSON-Schema / Ereignisse:** JSON-Schema `accessibility` unter `docs/umsetzungsplan/vertraege/schemas/accessibility.v1.schema.json`; Outbox-Ereignisse „Haltepunktrevision wirksam“ und „deaktiviert“ (`api/events/`).
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** UI-17: Haltepunktliste und -formular, Karte mit gleichwertiger Tabelle; Zustände leer/Fehler (Schemaverletzung, 412) und Rechte (nur lesend ohne Stammdatenrecht).
- **Entscheidungen:** D-05 (reale Haltepunktdaten) blockiert nur Produktivdaten.
- **Offene Punkte:** OP-7 (Aktivstatus und Verkehrsprodukte fehlen im Kern-DDL; Migration ist Bauteil dieses Vertrags), OP-8 (bestehende Zusagen bei Deaktivierung: Annahme neue Angebote betroffen, bestehende Zusagen behalten ihren Haltepunktsnapshot; Folgebehandlung offen), OP-9 (Freigabe in P1; `approved_by` Pflichtfeld), OP-41 (ob „ungeprueft“ für Rollstuhlanfragen ausgeschlossen wird); nicht festgelegt, ob mehrere zeitlich überlappende Revisionen derselben `stop_id` zulässig sind (Annahme: höchstens eine wirksame Revision je `stop_id` und Zeitpunkt, Absicherung im Bau). Nichts davon blockiert den Bau.

## Tests

- **Positiv:** aktive, wirksame Haltestelle mit zugelassenem Verkehrsprodukt wird in Suche (Filter Dienst/Umkreis/aktiv) und Angebot angeboten; Haltestelle hat Koordinate, `stop_id`, Aktivstatus und mindestens ein Verkehrsprodukt.
- **Negativ:** zweite Revision mit bereits vergebener `(stop_id, revision)` verletzt `UNIQUE(tenant_id,stop_id,revision)`; inaktive Haltestelle erscheint weder in der Haltepunktsuche noch als Abhol-/Zielpunkt im Angebot; außerhalb `valid_from/valid_to`; falsches Verkehrsprodukt; Koordinate außerhalb Wertebereich; `accessibility` verletzt das JSON-Schema; Haltestelle ohne Verkehrsprodukt abgewiesen; fremder Mandant → 404 (auch unter RLS in der Datenbank nicht lesbar); ohne Stammdatenrecht → 403; Änderung ohne Audit oder ohne `approved_by` nicht möglich; Deaktivierung wirkt nicht auf bestehende Zusagen (Haltepunktsnapshot der Zusage bleibt unverändert, WP-M2-05 Kriterium 4).
- **Konkurrierend:** Deaktivierung parallel zur Angebotsbildung: das Angebot enthält entweder die alte wirksame Revision oder den Haltepunkt nicht, nie eine halbe Revision; zwei parallele Revisionen → genau eine, die andere 412.
- **Referenztests:** AT-04 (Teil: Minimalfeld Rollstuhl-Eignung des Stopps; Vollabnahme mit WP-M2-12/-15), sonst keine Katalog-ID im WP genannt.
- Testfälle: AT-04; geplant:db/tests/test_geo_stop_revision.py::test_inaktive_haltestelle_nicht_buchbar; geplant:db/tests/test_geo_stop_revision.py::test_koordinate_ausserhalb_wertebereich_und_doppelte_revision_abgewiesen; geplant:backend/modules/service-geo/src/test/kotlin/de/taktovia/servicegeo/StopRevisionTest.kt::inaktive_haltestelle_wird_nicht_angeboten; geplant:backend/modules/service-geo/src/test/kotlin/de/taktovia/servicegeo/StopRevisionTest.kt::deaktivierung_parallel_zur_angebotsbildung_keine_halbe_revision; geplant:backend/modules/service-geo/src/test/kotlin/de/taktovia/servicegeo/StopRevisionTest.kt::koordinate_ausserhalb_wertebereich_und_schemaverletzung_ergeben_422; geplant:backend/modules/service-geo/src/test/kotlin/de/taktovia/servicegeo/StopRevisionTest.kt::paralleler_revisionsschreibversuch_genau_einer_412; geplant:backend/modules/service-geo/src/test/kotlin/de/taktovia/servicegeo/StopRevisionTest.kt::fremder_mandant_404_ohne_recht_403_audit_approved_by

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
