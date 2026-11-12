# M4 Referenzfixtures und Leseports (WP-M4-02)

**Status:** Bau-Detailvertrag, Planungsannahmen nicht fachlich freigegeben (D-05 Pilotbetreiber, D-06 Test-Kontenrahmen). WP ohne Anforderung (Enabler). Ergänzt [m4-cluster-c5.md](m4-cluster-c5.md) und [m4-modulzugaenge.md](m4-modulzugaenge.md).

## Leseports (Schnittstellen)

Paket `de.taktovia.platform.readports` (`backend/platform`), Schnittstellen und schmale Sichten ohne Fremdtabellenzugriff. Der **Eigentümer der Daten** implementiert den Port in seinem Modul; der Stub
`FixtureReadPorts` (Paket `…readports.stub`) ersetzt ihn, solange M2/M3 die Quelle nicht liefern.

| Port | Sicht | Echte Implementierung | Stub bis |
|---|---|---|---|
| `TripPlanReadPort` | aktuelle Planrevision eines Umlaufs: Stoppfolge, Planzeiten, Fahrzeit und Strecke je Abschnitt, ein-/aussteigende Buchungen | `booking-dispatch` (`BookingdispatchApi.tripPlans`, `vehicle_run`/`route_stop`, WP-M2-16) | echte Quelle vorhanden |
| `DriverReadPort` | Fahrer (ID, Status) mit freigegebenen Qualifikationsrevisionen; **kein Klarname, kein Kontakt, kein Nachweisverweis** | `staff-time` (`StafftimeApi.driverReadPort`, WP-M3-03/-05) | echte Quelle vorhanden |
| `FleetReadPort` | Fahrzeugstamm (Sitze, Rollstuhlplätze, Ausstattung, Aktivstatus) und Rollen mit Gültigkeit | `fleet-safety` (`FleetsafetyApi.fleetReadPort`, WP-M3-03, WP-M1-34) | echte Quelle vorhanden |
| `BookingReadPort` | Buchung mit Zusagefenstern (Snapshot), ohne Fahrgastbezug | **keine** | WP-M2-23 |
| `DriverEventReadPort` | Fahrerereignisse Ist-Abholung (`CHECK_IN`), Ist-Ankunft (`ARRIVAL`), `NO_SHOW` je Schicht | **keine** | WP-M3-29 |
| `PositionReadPort` | jüngste Position je Fahrzeug | **keine** | WP-M3-34 |

**Verträge aller Ports.** (1) Nur Daten des Mandanten von `RequestContext.tenant`; die Kennung eines fremden Mandanten ergibt `null` bzw. eine leere Liste (nie einen Fehler, der die Existenz verrät). (2) Nur lesend.
(3) Listen stabil nach ID in Textform sortiert (entspricht der Byte-Reihenfolge von PostgreSQL; `UUID.compareTo` ist vorzeichenbehaftet und taugt dafür nicht). (4) Ohne Datenbestand des Kontexts (anderer, leerer Mandant) keine Zeile.
Ohne Tenantkontext sieht die Runtime-Rolle in der Datenbank keine Zeile (RLS, Test `ohne_tenantkontext_liefert_die_datenbank_keine_zeile`).

**Vertragstest.** `ReadPortContractTest` (abstrakt, `backend/app`) läuft als `StubReadPortContractTest` und als `RealReadPortContractTest` (Seed unter der Migrator-Rolle geladen, Lesen unter der Runtime-Rolle) mit denselben Prüfungen;
`stub_und_echte_implementierung_liefern_dieselben_sichten` vergleicht beide auf demselben Datenstand. Die Prüfung der Ports ohne echte Quelle (Zusage, Fahrerereignisse, Positionen) läuft nur gegen den Stub und wird
mit WP-M2-23, WP-M3-29 und WP-M3-34 auf die echten Implementierungen ausgedehnt.

## Fixturekatalog (`tests/acceptance/fixtures/m4_referenz`)

Zwei Mandanten A (`…0a4`) und B (`…0b4`); IDs `md5('m4ref/<mandant>/<schlüssel>')::uuid`. Je Mandant: Rechtsträger X (Eigentümer) und Y (Betreiber), zwei Depots, zwei Kostenstellen (`KST-100`, `KST-200`), zwei Fahrzeuge
(Rollen Eigentümer X, Betreiber Y; `veh-2` mit Rollstuhlplatz), zwei Fahrer (`drv-2` mit Personenbeförderung nur bis 31.12.2026) und drei Schichten (Tag 01.12.2026; Nacht über die Herbstumstellung 25.10.2026, 9 h real;
Nacht über die Frühjahrsumstellung 28.03.2027, 7 h real). Mandant A zusätzlich: Dienst `svc-1`, drei Haltepunkte, Fahrtenplan `run-1` (Depot 06:00Z, Abholung 06:15Z, Ausstieg 06:36Z), bestätigte Buchung `bkg-1` mit
Zusage (Abholfenster 06:10-06:25Z, Ankunftsfenster 06:30-06:45Z), zwei Positionsproben und zehn Anfrageversuche.

| Referenzfall | Sollwert | Quelle |
|---|---|---|
| Musterfahrt | 12 400 m (4 200 m Anfahrt, 8 200 m besetzt), 2 160 s | `musterfahrt.json`, Seed `route_stop`, Port-Vertragstest |
| PH-AT-012 | brutto 11 900, netto 10 000, Vorsteuer 1 900 (Cent); ein Kostenereignis, Verbindlichkeit 11 900 | `sollwerte.json`, `steuerregel.json`, OCR-Szenarien |
| PH-AT-013 | 10 001 Cent in drei Anteilen (Largest Remainder, Reihenfolge A/B/C): 3 334 / 3 334 / 3 333 | `sollwerte.json` |
| PH-AT-015 | September 10 000, Oktober -2 000, Plan 9 000 und Ist 10 000 nie zu 19 000 addiert | `sollwerte.json` |
| PH-AT-021 | zehn Versuche, drei Ablehnungen (`OUTSIDE_AREA`, `NO_CAPACITY`, `OUTSIDE_HOURS`), zwei mit Nebengründen | Seed `travel_attempt`, Test `test_5_ph_at_021_…` |
| DST | 25.10.2026 = 25 h, 28.03.2027 = 23 h in `Europe/Berlin`; doppelte Ortszeit 02:30 = 00:30Z/01:30Z | `dst-stichtage.json` |

Test-Kontenrahmen und Test-Steuerregel (19 %, voll abzugsfähig) sind **Testdaten** (`kontenrahmen.json`, `steuerregel.json`) und werden nicht in Tabellen geladen (die Tabellen entstehen mit WP-M4-30 ff.).

## Simulatoren (`tests/sandbox/m4`)

OCR-Simulator mit steuerbaren Szenarien (`tankbeleg_ok`, `falscher_betrag`, `niedrige_konfidenz`, `aehnlicher_beleg`, `nicht_lesbar`), ERP-Empfangssimulator (Ausgleich je Rechtsträger und Währung, Idempotenz je Batch, Fehlermodi) und die Malware-Testdatei
(EICAR, zur Laufzeit zusammengesetzt, nie eingecheckt). Nur Testinfrastruktur; Ergebnisse sind Simulator-Ergebnisse.

## Abweichungen und Grenzen

- Der Seed wird (wie die Referenzregion M2) unter der **Migrator-Rolle** geladen, weil er Tabellen mehrerer Module beschreibt und Mandanten anlegt; gelesen wird ausschließlich unter der Runtime-Rolle. Kriterium 1 („unter der
  Runtime-Rolle“) ist damit für das Lesen, nicht für das Laden erfüllt.
- Fahrerereignisse und die Zusage stehen nur im Katalog und im Stub (kein Gerät und keine Buchungsquelle im Seed); Positionsproben sind zusätzlich als `position_sample` im Seed.
- Die Vollabnahme für die Ports Zusage, Fahrerereignisse und Positionen folgt mit WP-M2-23, WP-M3-29 und WP-M3-34.

- Testfälle: tests/acceptance/test_m4_referenz.py::test_1_laedt_in_leere_datenbank_unter_migrator_ohne_superuser_und_ist_idempotent; tests/acceptance/test_m4_referenz.py::test_2_reproduzierbar_gleicher_datenstand_und_gleiche_ids_auf_zweiter_datenbank; tests/acceptance/test_m4_referenz.py::test_3_runtime_sieht_nur_den_eigenen_mandanten_und_ohne_kontext_nichts; tests/acceptance/test_m4_referenz.py::test_evidenz_enthaelt_keine_echten_personen_oder_finanzdaten; backend/app/src/test/kotlin/de/taktovia/app/readports/ReadPortContractTest.kt::fahrtenplan_liefert_planzeiten_und_dokumentierte_kilometer; backend/app/src/test/kotlin/de/taktovia/app/readports/ReadPortContractTest.kt::fahrtenplan_fremder_mandant_und_unbekannte_kennung_ergeben_nichts; backend/app/src/test/kotlin/de/taktovia/app/readports/ReadPortContractTest.kt::stub_und_echte_implementierung_liefern_dieselben_sichten; tests/sandbox/m4/test_m4_sim.py::test_ok_falscher_betrag_und_niedrige_konfidenz_sind_steuerbar_und_deterministisch
