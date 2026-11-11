# Fixtures der Referenzregion (WP-M2-02)

Deterministische, **synthetische** Referenzregion für M2 (Buchung, Disposition, Routing). Keine echten Personen-, Kontakt- oder Kartendaten; Zeit und Zufall sind fest.

| Datei | Inhalt |
|---|---|
| `region.json` | Bediengebiet (MultiPolygon, SRID 4326), drei Dienste (`nord`, `sued` und der Testdienst `buchung` für Hold- und Bestätigungsläufe), sieben Haltepunkte `H1`–`H7` mit Barrierefreiheit nach `stop-accessibility.v1` |
| `strassengraph.json` | Straßengraph-Ausschnitt (10 Knoten, 13 Kanten): ein gesperrter Abschnitt (`e23`), eine Einbahnstraße (`e56`), eine Höhenrestriktion (`e45`, 2,5 m), eine Fußwegverbindung (`f15`), eine nicht angebundene Insel (`N10`); Kartenstand `referenz-2026-11-01` |
| `erwartete-fahrzeiten.json` | von Hand berechnete Fahrzeiten und Distanzen je Relation und Profil (Fixturebeschreibung), unabhängig von jeder Routingimplementierung |
| `tarif-testrelationen.json` | Relationen für die Tarifprüfung; erwartete Preise fehlen absichtlich (Beistellung des Auftraggebers, M2 OP-11/OP-12) |
| `fahrgaeste.json` | sechs synthetische Fahrgäste (nur transportrelevanter Unterstützungsbedarf) |
| `seed.sql` | DB-Seed (zwei Mandanten A/B): Dienste, Bediengebiet, Haltepunkte, Fahrzeuge, Schichten, Freigaben, Fahrzeugpool |

**Laden.** `seed.sql` läuft unter der Rolle `taktovia_migrator` (kein Superuser, kein `BYPASSRLS`) und setzt je Mandant transaktionslokal den Tenantkontext:
`python -c "from taktovia_at import referenzregion as r; r.load('<datenbank>')"` (aus `tests/acceptance`, Umgebung wie `db/db.py`). Idempotent; die Tests lesen unter der Runtime-Rolle mit Tenantkontext.

**Feste Uhr.** Fixture-Zeit `2026-12-01T05:00:00Z`; Schichten `04:00–14:00Z`; Referenzfenster der Tests `06:00–07:00Z`. Die Datenbankzeit `now()` wird nicht verstellt (siehe `docs/testzeit.md`): der Leseport bekommt Fenster und Uhr als Parameter.

**Fahrzeuge Mandant A (Pool `nord`, Fenster 06:00–07:00Z).** Einsetzbar: `v-std`, `v-wc`. Nicht einsetzbar: `v-blocked` (`active_block`), `v-expired` (Freigabe endet 05:00Z), `v-norelease`, `v-revoked`, `v-stale` (Freigabe gegen `safety_revision` 1, Fahrzeug steht auf 2), `v-noshift` (Schicht am Vortag). Außerhalb des Pools von `nord`: `v-sued`. Mandant B: `v-wc` (einsetzbar), `v-blocked`.

**Grenze.** Die Fixture-Daten ersetzen nicht die führenden Module: Fahrzeugstamm (WP-M3-03), Poolzuordnung (WP-M3-06), `resource_release` (WP-M3-08) und Schichten (WP-M4-03, WP-M4-65). Die Zustandswerte der Schicht (`PUBLISHED`) sind Annahme dieses Plans.
