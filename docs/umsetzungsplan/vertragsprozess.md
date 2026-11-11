# Detailvertragsprozess (WP-M0-31)

Regelt, wie der Detailvertrag einer Anforderung entsteht, wer ihn prüft und wann mit Code begonnen werden darf (Leitlinie 7, Kap. 08 §8.4).

## Ablage

| Artefakt | Ort | Namensschema |
|---|---|---|
| Detailvertrag je Anforderung | `docs/umsetzungsplan/vertraege/` | `<source_key>.md`, z. B. `LH-6.2-IAM-005.md` (Vorlage: `_vorlage.md`) |
| API-Vertrag | `api/*.yaml` | siehe `api/README.md` |
| Datenbank | `db/migrations/` | `V<n>__beschreibung.sql` |
| Zustandsautomaten | `docs/umsetzungsplan/vertraege/zustaende/` | `<aggregat>.md` mit Zuständen, Übergängen, Auslösern, Fehlern |
| JSON-Schemas (Konfiguration, Payloads) | `docs/umsetzungsplan/vertraege/schemas/` | `<name>.v<N>.schema.json` |
| Ereignisschemas | `api/events/` | `<bereich.ereignis>.v<N>.schema.json` |
| Beschlussprotokoll | `docs/umsetzungsplan/beschluesse/` | `D-<nn>.md` nach `_vorlage.md` |

Die Spalte `detailvertrag` in `arbeitspakete.csv` zeigt auf diese Pfade (mehrere, getrennt durch `;`; optional `#Anker`).

## Rollen

| Rolle | Aufgabe |
|---|---|
| Verantwortlicher | schreibt den Vertrag, hält ihn aktuell; im Vertragskopf benannt |
| Fachanalyse | prüft Treue zu Originaltext und Originalabnahme |
| Architektur | prüft Schnitt, Invarianten, Abhängigkeiten, Leitlinien 1–6 |
| QA | prüft, dass die Tests die Abnahme tatsächlich beweisen, einschließlich negativer und konkurrierender Fälle |
| Zweitprüfung | zusätzlich bei Sicherheit, Geld, Kapazität und Datentrennung: eine weitere benannte Person aus einem anderen Team |

Personen stehen erst nach Beschluss D-12 fest; bis dahin gelten Rollen und die Teams aus `.github/CODEOWNERS`.

## Definition of Ready (Status „Bereit“)

1. Verantwortlicher benannt, Vertrag nach Vorlage vollständig, Originaltext, Phase und Priorität unverändert.
2. Mindestens ein positiver Test mit Testfall-ID in der Spalte `testfall`: Katalog-ID (`AT-18`, `E2E-091`, `PH-AT-004`), vorhandener Test `pfad::testname` oder geplanter Test `geplant:pfad::testname` (Datei und Name werden mit dem Bau angelegt).
3. Bei Sicherheit, Geld, Kapazität und Datentrennung zusätzlich negative und konkurrierende Tests im Vertrag.
4. Reviews aller Rollen vermerkt; Zweitprüfung wo gefordert.
5. Offene Punkte und betroffene Entscheidungen (D-01 bis D-12) benannt. Eine offene Entscheidung blockiert den Bau nur, wenn der Vertrag das so festhält.

Spätestens ab Status „In Umsetzung“ muss mindestens ein Testfall vom Typ `pfad::testname` existieren (Datei vorhanden, Name darin gefunden).

## Statuswerte der Spalte `status` (abschließend)

| Status | Bedeutung | Mindestinhalt |
|---|---|---|
| `Geplant` | einem WP zugeordnet, noch kein Vertrag | – |
| `Vertrag in Arbeit` | Vertrag begonnen | `detailvertrag` |
| `Bereit` | Definition of Ready erfüllt | `detailvertrag`, `testfall` (Katalog-ID oder Pfad) |
| `In Umsetzung` | Codierung läuft | wie `Bereit`, mind. ein `pfad::testname` existiert |
| `Umgesetzt` | alle Tests des Vertrags grün in CI | wie `In Umsetzung` |
| `Abgenommen` | Abnahmeentscheidung erfolgt (Auftraggeber) | wie `Umgesetzt`, Eintrag in `e2e.csv`/Evidenz |
| `Abweichung freigegeben` | SOLL/KANN bewusst nicht oder anders umgesetzt | Beschluss unter `beschluesse/`, Verweis in `detailvertrag` |

MUSS-Anforderungen dürfen nie auf `Abweichung freigegeben` stehen. SOLL-Abweichungen brauchen Begründung und Freigabe des Auftraggebers, KANN-Funktionen die ausdrückliche Aufnahme ins Release (Kap. 08 §8.4). Phasen und Prioritäten ändern sich dadurch nicht.

## Pflege von `arbeitspakete.csv`

- Einziger Erzeuger ist `docs/umsetzungsplan/check_wp.py --write` (Zuordnung Anforderung → WP aus den Dateien unter `wp/`). `build_zuordnung.py` ist stillgelegt.
- Die Spalten `detailvertrag`, `testfall` und `status` werden von Hand bzw. durch den Vertragsprozess gepflegt. `--write` **übernimmt sie** aus der bestehenden Datei (Schlüssel: `source_key`) und setzt neue Zeilen auf `Geplant`.
- Die Spalte `arbeitspaket` entspricht den WP-IDs der Dateien unter `wp/`; `check_tracking.py` prüft das.
- Prüfung: `python docs/umsetzungsplan/check_tracking.py`.

## Abweichungen und Beschlüsse

Entscheidungen nach Kap. 07 werden unter `beschluesse/D-<nn>.md` protokolliert (Vorlage dort). Ohne Beschluss bleibt ein Favorit eine Empfehlung; Produktivfreigabe folgt daraus nie (Kap. 07).
