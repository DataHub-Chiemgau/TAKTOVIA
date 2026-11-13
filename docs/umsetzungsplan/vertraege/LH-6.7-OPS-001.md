# LH-6.7-OPS-001 – Leitstelle Livebetrieb: Fahrzeuge, Position, Verfügbarkeit

- **Verantwortlicher:** Rolle Leitstelle/Dispatch (BFF `/v1/operator/vehicles`, Web `web/operator`)
- **Arbeitspaket:** WP-M3-36
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Echtzeit-Leitstelle zeigt aktive Fahrzeuge, letzte Position, Verfügbarkeit und Aufträge.

**Originalabnahme (unverändert):** Filterbarer Testbetrieb zeigt nur Fahrzeuge im berechtigten Dienst.

## Vertrag

- **Tabellen / DDL:** Lesemodell auf `vehicle` (`active_block`, `safety_revision`), `vehicle_run`, `route_stop`, `booking`, `staff_shift` und der M3-Tabelle `position_sample` (WP-M3-34; Index `(tenant_id,vehicle_id,measured_at DESC)`). Mandantenisolation RLS (V1); Dienstscope zusätzlich in der Anwendung über `membership.service_scope` und den Port `canRead(actor, objectRef, purpose)` (WP-M1-08). Dienstzugehörigkeit des Fahrzeugs über Lauf/Zuordnung ist offen (siehe Offene Punkte). Keine neue Migration durch diesen Vertrag.
- **API:** `GET /v1/operator/vehicles` – Liste mit Filtern (Dienst, Status, offene Störungen), Antwort `{items,next_cursor,as_of}`; `limit` > 100 (zulässig 1–100, Kap. 05 §5.1) und manipulierter Cursor werden abgewiesen (`application/problem+json`, Statuscode laut OpenAPI-Entwurf); Detail `GET /v1/operator/vehicles/{id}`. Fremder Mandant oder nicht berechtigter Dienst: einheitlich 404 `NOT_FOUND` (Kap. 05 §5.4), nie 403 mit Existenzhinweis. Ein Filter darf die Berechtigung nur weiter einschränken (Schnittmenge mit dem Dienstscope). Je Fahrzeug: Status, Freigabe/Sperre mit Grund, letzte Position mit `measured_at` und Standortalter, Veraltet-Kennzeichen, laufende und nächste Aufträge. Position wird nie extrapoliert (AT-10 mit WP-M3-34).
- **Zustandsautomat:** keiner neu; Anzeigezustände Sperre, veralteter Standort, keine Fremdrechte, Leerzustand, Ladefehler mit Datenstand.
- **JSON-Schema / Ereignisse:** Antwortschema `operator_vehicle_list` (Arbeitsname, in OpenAPI festzulegen); Aktualisierung über den Live-Kanal WP-M3-35 (nur Referenzen und Statusfelder der berechtigten Projektion).
- **Parameter und Kennzahlen:** Veraltet-Schwelle Standortalter (Planungsdefault 30 s, Kap. 01 §1.8/Kap. 08) – kein Katalogparameter (OP-04).
- **Oberfläche:** UI-07 „Operator Livebetrieb": Kopf „Mandant / Region · Datenstand", Fahrzeugliste und Karte gleichwertig (Liste ohne Karte vollständig per Tastatur/Screenreader bedienbar), Detailpanel; Zustände Sperre (mit Grund), veralteter Standort (mit letztem Stand), keine Fremdrechte (Fremdfahrzeuge nicht vorhanden statt ausgegraut), Leerzustand, Ladefehler mit Datenstand. Navigationsrahmen aus WP-M1-43.
- **Entscheidungen:** D-09 (Karten) – blockiert nur die Produktivschaltung der Kartenquelle.
- **Offene Punkte:** OP-04 (Schwelle Standortalter ohne Katalogparameter; Positionsendpunkt fehlt in Kap. 05 §5.7 – liegt in WP-M3-34), OP-01 (Sperrgründe im Schema: `vehicle.active_block` allein bildet mehrere Gründe nicht ab). Wie ein Fahrzeug einem Dienst zugeordnet ist (für den Dienstfilter) legt das Pflichtenheft nicht eindeutig fest (OP-25 berührt die Poolzuordnung); der Vertrag nutzt die Dienstzuordnung über den Lauf bzw. die Zuordnung, Feinschnitt im OpenAPI-Entwurf. Blockiert den Bau nicht, da gegen Fixtures entwickelt wird.

## Tests

- **Positiv:** Testbetrieb mit zwei Diensten: ein nur für Dienst A berechtigter Disponent sieht ausschließlich Fahrzeuge in Dienst A; Filter nach Dienst und Status liefert die Schnittmenge; je Fahrzeug erscheinen letzte Position mit Alter, Verfügbarkeit und Aufträge.
- **Negativ:** Zugriff auf Fahrzeug eines nicht berechtigten Dienstes (404); Fahrzeug eines fremden Mandanten (404, erscheint nie in der Liste); manipulierter Filter auf fremden Dienst ergibt leere Liste; `limit` > 100 und manipulierter Cursor werden abgewiesen; Positionsstrom unterbrochen: „veraltet" mit letztem Stand, keine fiktive Liveposition (AT-10, gemeinsam mit WP-M3-34); Liste ohne Karte bedienbar; Zustände Sperre/veraltet/keine Fremdrechte/Leerzustand/Ladefehler prüfbar.
- **Konkurrierend:** Dem Disponenten wird das Dienstrecht entzogen, während Liste und Live-Kanal offen sind – ab dem Entzug erscheinen keine Fahrzeuge dieses Dienstes mehr; parallele Abrufe zweier Mandanten über wiederverwendete Poolverbindungen bleiben getrennt.
- **Referenztests:** AT-10, Baustein für PH-AT-001 (Datentrennung, nicht beansprucht).
- Testfälle: AT-10; backend/app/src/test/kotlin/de/taktovia/app/operator/OperatorVehicleListTest.kt::disponent_dienst_a_sieht_nur_fahrzeuge_dienst_a; backend/app/src/test/kotlin/de/taktovia/app/operator/OperatorVehicleListTest.kt::dienstrecht_entzug_bei_offener_liste_entfernt_fahrzeuge; web/operator/src/vehicles/VehicleList.test.tsx::filter_schraenkt_nur_ein_und_zustaende_sind_sichtbar; web/operator/src/vehicles/VehicleList.test.tsx::liste_ohne_karte_per_tastatur_und_screenreader_bedienbar; backend/app/src/test/kotlin/de/taktovia/app/operator/OperatorVehicleListTest.kt::fremder_mandant_und_fremder_dienst_404_und_manipulierter_filter_leer; backend/app/src/test/kotlin/de/taktovia/app/operator/OperatorVehicleListTest.kt::limit_ueber_100_und_manipulierter_cursor_abgewiesen; backend/app/src/test/kotlin/de/taktovia/app/operator/OperatorVehicleListTest.kt::unterbrochener_positionsstrom_zeigt_veraltet_ohne_extrapolation; backend/app/src/test/kotlin/de/taktovia/app/operator/OperatorVehicleListTest.kt::parallele_abrufe_zweier_mandanten_bleiben_getrennt

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
