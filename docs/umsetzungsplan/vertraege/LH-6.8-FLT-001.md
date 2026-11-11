# LH-6.8-FLT-001 – Fahrzeugstamm

- **Verantwortlicher:** Rolle Flotte/Sicherheit (Modul `fleet-safety`)
- **Arbeitspaket:** WP-M3-03
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Fahrzeuge besitzen Kennung, Betreiber, Typ, Kapazitäten, Ausstattung und Aktivstatus.

**Originalabnahme (unverändert):** Fahrzeug mit ungültiger Kapazität wird nicht speicherbar.

## Vertrag

- **Tabellen / DDL:** `vehicle` (V1: `tenant_id`, `id`, `owner_org_ref text NOT NULL`, `capacity_config jsonb NOT NULL`, `active_block boolean NOT NULL DEFAULT true`, `safety_revision bigint NOT NULL DEFAULT 1`, `PRIMARY KEY(tenant_id,id)`) wird additiv (expand/contract) durch die Migration aus WP-M3-02/WP-M3-03 um die Pflichtfelder Kennung, Typ, Ausstattung, Aktivstatus und eine Stammdaten-Revision (für ETag/`If-Match`, getrennt von `safety_revision`) erweitert; Spaltennamen legt das DDL aus WP-M3-01 fest. Kennung eindeutig je Mandant (`UNIQUE(tenant_id, <kennung>)`). `vehicle_lease` und `vehicle_assignment` bestehen seit WP-M1-34 und werden nur additiv ergänzt, nicht neu angelegt (WP-M3-02). CHECK in der Datenbank: jede Kapazitätsdimension in `capacity_config` ist vorhanden, ganzzahlig und >= 0; die Datenbank weist dieselben Werte ab wie die API. RLS mandantenbezogen wie V1 (`ENABLE` und `FORCE ROW LEVEL SECURITY`, Policy `tenant_isolation` mit USING und WITH CHECK). Betreiber: siehe Offene Punkte (Abbildung auf `owner_org_ref` bzw. Rollen aus WP-M1-34).
- **API:** `/v1/operator/vehicles` (Liste, Detail mit starkem ETag, Anlegen, Ändern mit `If-Match`). Ungültige, fehlende, negative oder nicht ganzzahlige Kapazität: 422 mit Feldverweis, nichts gespeichert. Änderung ohne `If-Match`: 428 `PRECONDITION_REQUIRED`; veraltete Revision: 412 `REVISION_MISMATCH`; Fahrzeug eines anderen Mandanten: 404 `NOT_FOUND` (keine Existenzauskunft); doppelte Kennung: Code 409 oder 422 mit Feldverweis (im OpenAPI-Vertrag festzulegen, in Kap. 05 §5.4 nicht belegt); fehlendes Pflichtfeld (Kennung, Betreiber, Typ, Kapazität, Ausstattung, Aktivstatus): 422 mit Feldverweis. Der Mandant stammt ausschließlich aus der Sitzung, nie aus dem Body.
- **Zustandsautomat:** keiner eigener; Aktivstatus aktiv/deaktiviert (nicht zu verwechseln mit `active_block`, der Sperre/Einsatzfreigabe). Ein deaktiviertes Fahrzeug wird nicht neu disponiert (Kandidatenfilter, Leseport `ResourceAvailability`), bleibt in Historie und Nachweisen lesbar.
- **JSON-Schema / Ereignisse:** JSON-Schema `capacity_config` (Grundform; Details WP-M3-04, `vertraege/schemas/capacity_config.v<N>.schema.json`); Outbox-Ereignisse Fahrzeug angelegt / geändert / deaktiviert (Schemas unter `api/events/`, noch anzulegen). Audit: Anlegen, Ändern, Deaktivieren mit Akteur und Zeitpunkt im `audit_event`.
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** UI-11 Flotte/Werkstatt: Fahrzeugliste und Stammdatenformular mit Feldfehlern; Leerzustand, Fehlerzustand, Rechtezustand (Bereich für Unberechtigte nicht vorhanden); Kopf mit Mandant und Datenstand.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-14 (Wertekatalog Fahrzeugtyp und Ausstattung fehlt; bis dahin Platzhalter im OpenAPI-Entwurf, Katalog vor Release festzulegen; blockiert den Bau der Kapazitätsvalidierung nicht). OP-01 (Bedeutung des Schema-Defaults `active_block = true`: neues Fahrzeug zunächst gesperrt/nicht disponierbar bis Einsatzfreigabe; Annahme laut WP-M3-03 Kriterium 6, zu bestätigen; blockiert nicht). Abbildung „Betreiber" im Fahrzeugstamm: Das Schema kennt nur `owner_org_ref` (dem Namen nach Eigentümer), WP-M1-34 führt Eigentümer, Betreiber, Einsatzleister und Abrechnungspartner als zeitlich gültige Rollen (OP-26 des M1-Dokuments); der Vertrag legt nicht fest, dass Betreiber gleich `owner_org_ref` ist. Bis zur Klärung gilt: Betreiber ist Pflichtangabe, verweist nur auf Organisationen des eigenen Mandanten (WP-M1-05); blockiert nicht.

## Tests

- **Positiv:** Fahrzeug mit Kennung, Betreiber, Typ, Kapazitäten, Ausstattung, Aktivstatus anlegen und lesen (alle Felder unverändert); mit `If-Match` ändern; Deaktivieren schreibt `audit_event` mit Akteur und Zeitpunkt, Fahrzeug bleibt in Historie lesbar; neu angelegtes Fahrzeug ist ohne Einsatzfreigabe nicht disponierbar (WP-M3-03 Kriterium 6, OP-01).
- **Negativ:** Kapazität negativ, fehlend, nicht ganzzahlig: 422 mit Feldverweis und nichts gespeichert (Originalabnahme); dieselben Werte direkt per SQL: CHECK-Verletzung; doppelte Kennung im selben Mandanten abgewiesen, gleiche Kennung in anderem Mandanten zulässig; je fehlendes Pflichtfeld 422; Betreiber aus fremdem Mandanten nicht speicherbar; Fahrzeug fremder Mandant: 404; Änderung ohne `If-Match`: 428; veraltete Revision: 412; Insert mit fremder `tenant_id` oder leerer Tenantkontext (Runtime-Rolle): null Zeilen bzw. Fehler (Datentrennung); deaktiviertes Fahrzeug erscheint nicht als Kandidat neuer Fahrten.
- **Konkurrierend:** zwei gleichzeitige Änderungen derselben Revision: genau eine gewinnt, die andere 412; zwei gleichzeitige Anlagen derselben Kennung: genau eine, die andere abgewiesen; Deaktivieren parallel zu einem Hold: keine Zusage nach der Deaktivierung (Abgleich mit WP-M3-08).
- **Referenztests:** keine Katalog-ID im WP für FLT-001 genannt (die Mandantentrennung wird über die geplanten DB-Tests nachgewiesen, nicht über AT-18).
- Testfälle: geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/VehicleMasterTest.kt::ungueltige_kapazitaet_wird_nicht_gespeichert; geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/VehicleMasterTest.kt::gleichzeitige_aenderung_genau_eine_gewinnt; geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/VehicleMasterTest.kt::gleichzeitige_anlage_gleicher_kennung_genau_eine; geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/VehicleMasterTest.kt::fremder_mandant_ergibt_404; geplant:db/tests/test_fahrzeugstamm.py::test_capacity_check_und_eindeutige_kennung; geplant:db/tests/test_fahrzeugstamm.py::test_rls_fremder_mandant_und_leerer_tenantkontext

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
