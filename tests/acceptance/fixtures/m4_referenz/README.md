# Referenzdaten M4 (WP-M4-02)

Deterministische, **synthetische** Referenzdaten für M4 (Personal, Belege, Kosten, Kennzahlen). Keine echten Personen-, Finanz-, Kontakt- oder Kartendaten; Zeit und Zufall sind fest.
Dienen M4, bis M2 und M3 ihre Quellen liefern (Leseports mit Stub, `backend/platform/.../readports`), und danach als Gegenprobe der echten Implementierung.

| Datei | Inhalt |
|---|---|
| `seed.sql` | DB-Seed (zwei Mandanten A/B, …a4/…b4): je zwei Rechtsträger (X Eigentümer, Y Betreiber), zwei Depots, zwei Kostenstellen, zwei Fahrzeuge mit Rollen, zwei Fahrer mit Qualifikationen, drei Schichten (Tag, Herbst- und Frühjahrsumstellung); Mandant A zusätzlich Musterfahrt (Dienst, Haltepunkte, Fahrtenplan, bestätigte Buchung mit Zusage, zwei Positionsproben) und zehn Anfrageversuche (PH-AT-021) |
| `katalog.json` | Katalog der Seed-Objekte als einzige Quelle der Stub-Implementierungen; der Vertragstest vergleicht Stub und echte Implementierung auf demselben Datenstand |
| `kontenrahmen.json`, `steuerregel.json` | Test-Kontenrahmen und Test-Steuerregel (19 %, voll abzugsfähig) **als Testdaten**, nicht freigegeben (D-06/D-07) |
| `musterfahrt.json` | Musterfahrt mit dokumentierten Kilometern (12 400 m, davon 8 200 m besetzt) |
| `dst-stichtage.json` | 25.10.2026 (Herbst, 25 Stunden) und 28.03.2027 (Frühjahr, 23 Stunden) in `Europe/Berlin` |
| `sollwerte.json` | erwartete Werte der Referenzfälle PH-AT-012, -013, -015, -021 und der Musterfahrt |
| `ocr-szenarien.json` | steuerbare Ergebnisse des OCR-Simulators (`tests/sandbox/ocr`) |

**IDs.** `md5('m4ref/<mandant>/<schlüssel>')::uuid`, in Python `taktovia_at.m4_referenz.fixture_id`, in Kotlin `fixtureId` der Testhilfen.

**Laden.** Wie die Referenzregion M2: `python -c "from taktovia_at import m4_referenz as r; r.load('<datenbank>')"` (aus `tests/acceptance`, Umgebung wie `db/db.py`). Der Seed läuft unter der Rolle
`taktovia_migrator` (kein Superuser, kein `BYPASSRLS`), setzt je Mandant transaktionslokal den Tenantkontext und ist idempotent. Gelesen wird unter der Runtime-Rolle mit Tenantkontext;
ohne Kontext liefert jede Tabelle keine Zeile.

**Grenzen.** (1) Fahrerereignisse (Ist-Abholung, Ist-Ankunft, No-Show) stehen nur im Katalog und im Stub (die Quelle liefert WP-M3-29; der DB-Seed enthält kein Gerät). (2) Die Buchung mit Zusagesnapshot trägt das
Format der Zusage (`pickup_earliest` …); die verbindliche Quelle ist WP-M2-23. (3) Der Kontenrahmen und die Steuerregel sind Testdaten und werden nicht in Tabellen geladen (die Tabellen entstehen mit WP-M4-30 ff.).
