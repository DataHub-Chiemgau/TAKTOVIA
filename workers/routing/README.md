# workers/routing

Python-Worker für Routing und Optimierung (OR-Tools, Valhalla-Adapter). Nicht Teil des Gradle-Builds.

- **Verantwortlich:** Buchung/Disposition (Rolle laut D-12, noch keine Personen benannt)
- **Regeln:** [CONTRIBUTING.md](../../CONTRIBUTING.md)
- **Stand:** Gerüst (WP-M0-07) und Spike (b) Solverlaufzeit (WP-M0-27, Bericht unter [docs/spike-b-solver.md](docs/spike-b-solver.md)). Keine Produktivlogik.

## Layout

| Pfad | Inhalt |
|---|---|
| `src/taktovia_routing/port.py` | anbieterneutrale Schnittstelle (WP-M2-10): `RoutingProvider`, Haltepunkt, Fahrzeugprofil, Ergebnisse mit Profil und Kartendatenversion, definierte Fehler |
| `src/taktovia_routing/adapter.py`, `cache.py` | `RoutingAdapter`: Haltepunktbindung (freie Koordinaten abgewiesen), Zeitbudget, mandantengebundener, versionsgeprüfter Cache |
| `src/taktovia_routing/graph_provider.py` | Referenzimplementierung auf dem Fixture-Straßengraphen (kein Anbieter): Sperre, Einbahn, Höhenrestriktion, Fußweg |
| `src/taktovia_routing/valhalla.py` | Valhalla-Adapter (`/route`, `/sources_to_targets`); **nur gegen einen Stub getestet, nicht gegen einen echten Valhalla-Dienst** |
| `src/taktovia_routing/server.py` | synchrone JSON-Schnittstelle für den Kern (`/v1/route`, `/v1/matrix`, `/v1/walk`, `/v1/map-version`); Beispielantwort in `contract/route-response.json` |
| `src/taktovia_routing/selfcheck.py` | OR-Tools-Import und Trivialproblem (4-Knoten-TSP) |
| `src/taktovia_routing/health.py` | leerer Health-Endpunkt `GET /healthz` (Standardbibliothek, Port 8081) |
| `src/taktovia_routing/spike/` | **Spike** (WP-M0-27): Generator, Einfügeheuristik, unabhängiger Prüfer, OR-Tools-Vergleich, Messprotokoll |
| `tests/` | pytest |
| `requirements.lock`, `requirements-dev.lock` | Lockfiles mit Hashes (`uv pip compile --generate-hashes`) |
| `Dockerfile` | Image-Build aus dem Lockfile (**nicht ausgeführt**, kein Docker-Daemon in der Entwicklungsumgebung) |

Festgeschriebene Versionen: Python 3.13, OR-Tools 9.15.6755, NumPy 2.5.4; Werkzeuge pytest 9.2.0, ruff 0.17.4, mypy 2.4.1
(siehe [versionen.md](../../docs/umsetzungsplan/versionen.md)).

## Lokal bauen und testen

```bash
uv venv .venv --python 3.13            # oder ein venv außerhalb des Repositorys
uv pip sync --python .venv/bin/python --require-hashes requirements-dev.lock
uv pip install --python .venv/bin/python --no-deps -e .
.venv/bin/ruff check . && .venv/bin/ruff format --check .   # Lint (Fehler brechen mit Exit 1 ab)
.venv/bin/mypy                                              # Typprüfung (strict)
.venv/bin/pytest -q                                         # Tests
```

Lockfiles erneuern nach Änderung der Abhängigkeiten in `pyproject.toml`:

```bash
uv pip compile pyproject.toml --generate-hashes --python-version 3.13 -o requirements.lock
uv pip compile pyproject.toml --extra dev --generate-hashes --python-version 3.13 -o requirements-dev.lock
```

Spike-Messung wiederholen (Seed fest, Ergebnis hängt von der Hardware ab):

```bash
.venv/bin/python -m taktovia_routing.spike.bench --out docs/spike-b-messwerte.json
.venv/bin/python -m taktovia_routing.spike.ortools_compare --out docs/spike-b-ortools.json
```

## Aufrufschnittstelle Kern <-> Worker (WP-M2-10)

Entscheidung (Annahme, Empfehlung aus Spike (b) übernommen): der Kern ruft den Worker **synchron** (HTTP/JSON, `server.py`) mit hartem Zeitbudget (Standard 0,8 s im Worker, 0,9 s im Kern-Client,
innerhalb der 1 s lokalen Kandidatensuche); die Queue bleibt für spätere Hintergrundläufe offen. Der Haltepunktkatalog des Workers ist in M2 die Fixture der Referenzregion; die Pflege aus den freigegebenen
Haltepunkten (`stop_revision`) ist offen. Bis dahin gilt: Der Worker hat **keinen Datenbankzugriff** (weder Treiber noch Konfiguration); Zustand
(Fahrzeugpläne, Planrevision) wird ihm vom Kern übergeben, Kapazität sichert ausschließlich der Hold in PostgreSQL
(Kap. 01 §1.4 Nr. 5).

## Einfügeheuristik (WP-M2-17 bis -19)

`src/taktovia_routing/insertion.py`: Einfügen einer Buchung in bestehende Umläufe (alle Positionspaare je Kandidatenfahrzeug, Zeitpropagation mit Bedienzeiten, harte Grenzen für Wartezeit, Umweg und Verspätung, Schutz zugesagter Fenster, Pooling-Schalter, hartes Zeitbudget, unabhängiger Prüfer `validate_variant`). Schnittstelle `POST /v1/insert` (Beispiel `contract/insert-request.json`), Festlegungen in [m2-detailvertraege.md §10](../../docs/umsetzungsplan/vertraege/m2-detailvertraege.md). Tests: `tests/test_insertion_heuristic.py`, `tests/test_insertion_limits.py`; Laufzeitmessung `tests/performance/test_insertion_budget.py` (Referenzszenario, hardwareabhängig).

## Telemetrie und Maskierung (WP-M5-03, WP-M5-06)

`src/taktovia_routing/diagnose.py` (identisch im Dokumenten-Worker): Maskierung der Diagnosedaten, W3C-`traceparent`, Metriken (`GET /metrics`, Konvention
[docs/betrieb/telemetrie.md](../../docs/betrieb/telemetrie.md)), Spans per OTLP/HTTP-JSON (best effort), JSON-Logs. `make_server(adapter, telemetry=Telemetry("taktovia-routing", otlp_endpoint))`
aktiviert sie; ohne `telemetry` verhält sich der Server wie bisher. Tests: `tests/test_telemetry.py`.
