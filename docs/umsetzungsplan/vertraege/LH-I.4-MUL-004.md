# LH-I.4-MUL-004 – Fahrzeugrollen mit Gültigkeitsintervallen

- **Verantwortlicher:** Rolle Plattform (Modul `fleet-safety`, Stammteil)
- **Arbeitspaket:** WP-M1-34
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Ein Fahrzeug kann einen Eigentümer, Betreiber, Einsatzleister und Abrechnungspartner besitzen.

**Originalabnahme (unverändert):** Eigentümerwechsel verändert nicht ungewollt historisch abgerechnete Fahrzeugfahrten.

## Vertrag

- **Tabellen / DDL:** `vehicle` (V1: `tenant_id`, `id`, `owner_org_ref`, `capacity_config`, `active_block`, `safety_revision`) bleibt Stamm; die vier Rollen werden zeitlich gültig in `vehicle_assignment` geführt (Kap. 04 §4.4 DRV/FLT „Eigentums-/Nutzungsintervalle"), `vehicle_lease` für Nutzungsüberlassung. Beide Tabellen mit `PRIMARY KEY(tenant_id,id)`, `FOREIGN KEY(tenant_id,vehicle_id) REFERENCES vehicle(tenant_id,id)`, Organisation per `FOREIGN KEY(tenant_id,organization_id) REFERENCES organization(tenant_id,id)` (damit keine Organisation eines fremden Mandanten), RLS wie V1. Vorschlag Spalten `vehicle_assignment`: `role` (`OWNER|OPERATOR|DISPATCH_PROVIDER|BILLING_PARTNER`, CHECK), `valid_from timestamptz NOT NULL`, `valid_to timestamptz` (halboffen `[valid_from, valid_to)`), `CHECK(valid_to IS NULL OR valid_to>valid_from)`, `created_by`, `created_at`. Überlappungsverbot je `(tenant_id, vehicle_id, role)` per Exclusion Constraint auf `tstzrange(valid_from, valid_to)` (WP-M1-34 AK 4; Kap. 04 §4.5 nennt Exclusion Constraints nur für Tarif-/Policy-/Dienstversionen, „Noch umzusetzen"; für Fahrzeugrollen Vorschlag, OP-26). `vehicle.owner_org_ref` bleibt Referenz des Stammteils und wird nicht zur Rollenquelle (Abstimmung mit OP-26). Intervalle abgeschlossener Zeiträume sind unveränderlich (Trigger analog `reject_mutation()`); ein Wechsel schließt das laufende Intervall (`valid_to` = Stichtag) und legt ein neues an.
- **API:** Rollenzuordnung lesen/ändern unter `/v1/operator/vehicles` (Kap. 05 §5.7), z. B. `GET /v1/operator/vehicles/{id}/roles?as_of=<ts>` und `PUT /v1/operator/vehicles/{id}/roles/{role}` mit `valid_from`, `organization_id`, `If-Match`/`Idempotency-Key`; Berechtigung nur für berechtigte Rollen (Rechtematrix WP-M1-07), Änderung auditiert (WP-M1-46); Fehler: 404 für fremden Mandanten/Fahrzeug, 403 ohne Recht, 409 bei Überlappung, 422 bei rückwirkender Änderung in abgeschlossenem Intervall (`application/problem+json`). Exakte Pfade sind im Pflichtenheft nicht ausformuliert und werden mit dem Bau in `api/openapi.yaml` ergänzt.
- **Snapshot-Vertrag:** Funktion `vehicleRolesAt(tenant, vehicleId, instant)` liefert die vier Rollen samt Intervall-IDs; abrechnende Module (`vehicle_run`, Kostenereignis, M2/M3/M4) speichern das Ergebnis zum Leistungszeitpunkt als unveränderlichen Snapshot und lesen später nie live.
- **Zustandsautomat / JSON-Schema:** keine. **Ereignisse:** `vehicle.role_assignment_changed` (genau eines je Änderung, Outbox); Audit über WP-M1-46.
- **Parameter und Kennzahlen:** keine. **Oberfläche:** keine eigene in P1 (Pflege über API/Operatoroberfläche mit M3).
- **Entscheidungen:** D-01 (Rollen je Dienst) – blockiert nur Produktivschaltung.
- **Offene Punkte:** OP-26: Spalten von `vehicle_lease`/`vehicle_assignment` und Abbildung der vier Rollen auf „Eigentum, Einsatzbetreiber, Abrechnungspartner" (Kap. 02 §2) sind nicht festgelegt; obige Feldliste ist Vorschlag und vor Codebeginn zu bestätigen (blockiert den Bau nicht, solange die Bestätigung vor Migration erfolgt). Nachweis an abgerechneten Fahrzeugfahrten erst mit WP-M2-16, WP-M3-03, WP-M4-33.

## Tests

- **Positiv:** Fahrzeug mit vier verschiedenen Organisationen in den vier Rollen; jede Rolle einzeln änderbar; Eigentümerwechsel zum Stichtag: As-of vor Stichtag liefert alten Eigentümer, nach Stichtag den neuen; vorher erzeugter Rollensnapshot bleibt unverändert (Abnahme).
- **Positiv (Fixture):** Nachweis in M1 an Fixture-Fahrzeugen des Stammteils (`vehicle`), nicht an abgerechneten Fahrten (WP-M1-34 AK 3); jede Änderung erzeugt genau ein Outbox-Ereignis `vehicle.role_assignment_changed` und einen Auditeintrag.
- **Negativ:** Organisation eines fremden Mandanten (FK/RLS, auch per API: 404); überlappendes Intervall derselben Rolle; rückwirkende Änderung in abgeschlossenem Intervall; Änderung durch Rolle ohne Recht; UPDATE/DELETE eines abgeschlossenen Intervalls unter Runtime-Rolle.
- **Konkurrierend (Geld):** zwei parallele Eigentümerwechsel zum selben Stichtag – genau einer erfolgreich, der andere 409; Snapshot-Erzeugung parallel zu Wechsel liefert konsistent alten oder neuen Stand, nie gemischt.
- **Referenztests:** keine Katalog-ID im WP genannt; Nachweis an abgerechneten Fahrten mit M2/M3/M4.
- Testfälle: geplant:db/tests/test_vehicle_assignment.py::test_ueberlappung_gleiche_rolle_abgewiesen; geplant:db/tests/test_vehicle_assignment.py::test_organisation_fremder_mandant_abgewiesen; geplant:db/tests/test_vehicle_assignment.py::test_abgeschlossenes_intervall_unveraenderlich_unter_runtime_rolle; geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/VehicleRoleAssignmentTest.kt::vier_rollen_je_einzeln_aenderbar_und_as_of_aufloesung; geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/VehicleRoleAssignmentTest.kt::rueckwirkende_aenderung_in_abgeschlossenem_intervall_422; geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/VehicleRoleAssignmentTest.kt::aenderung_ohne_recht_403_und_genau_ein_outbox_ereignis; geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/VehicleRoleAssignmentTest.kt::eigentuemerwechsel_laesst_historischen_snapshot_unveraendert; geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/VehicleRoleAssignmentTest.kt::paralleler_eigentuemerwechsel_gleicher_stichtag

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Geld: Rollenzuordnung bestimmt Abrechnungspartner).
