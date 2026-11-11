# tests/

Testwerkzeuge und Akzeptanzgerüst von M0 (WP-M0-22 bis -26, -29, -30). Alles hier ist Testinfrastruktur, nicht Teil des Produktivcodes, und in keiner Produktionskonfiguration referenziert.

| Verzeichnis | Inhalt | WP |
|---|---|---|
| [`acceptance/`](acceptance/README.md) | Szenario-Gerüst, Zwei-Mandanten-Seed, Evidenzformat, Katalog der 106 + 22 Szenarien, Rauchtest | WP-M0-22 |
| [`sandbox/psp/`](sandbox/psp/README.md) | PSP-Simulator mit steuerbaren Fehlern | WP-M0-23 |
| [`sandbox/partner/`](sandbox/partner/README.md) | Sandbox-Partner A/B und nur auskunftsfähiger Partner C | WP-M0-24 |
| [`performance/`](performance/README.md) | Lastprofil, Runner, Berichtsformat, Restore-Drehbuch-Vorlage | WP-M0-25 |
| [`performance/spikes/`](performance/spikes/README.md) | Spike (a) Hold/Confirm auf PostGIS, (d) PSP-Sandbox, (e) Routingvergleich | WP-M0-26, -29, -30 |
| [`lib/`](lib) | gemeinsame Bibliothek: Testzeit-Protokoll, JSON-HTTP, Referenzprüfer für Partnerereignisse | WP-M0-22 bis -24 |
| [`contract/`](contract) | semantische Contract-Fälle (nicht Teil dieses Pakets) | WP-M0-13 |

- **Voraussetzungen:** Python 3.13, `pip install -r tests/requirements.txt`; Simulator und Sandbox laufen nur mit der Standardbibliothek. Datenbanksuiten brauchen PostgreSQL mit PostGIS und die Umgebungsvariablen aus [`db/db.py`](../db/db.py).
- **Alle Suiten:** `python tests/run_all.py` (ohne Datenbank: `--no-db`).
- **Nicht verdrahtet:** Der Aufruf in `.github/workflows/ci.yml` ist nicht Teil dieses Pakets (Eigentum außerhalb); die Befehle stehen in den READMEs.
- **Regeln:** [CONTRIBUTING.md](../CONTRIBUTING.md). Keine Secrets im Repository; Testschlüssel werden zur Laufzeit abgeleitet.
