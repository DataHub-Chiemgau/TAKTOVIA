# workers/documents

Python-Worker für OCR-Anbindung, Malware-Scan und KoSIT-Validierung. Nicht Teil des Gradle-Builds.

- **Verantwortlich:** Finanzen/Daten (Rolle laut D-12, noch keine Personen benannt)
- **Regeln:** [CONTRIBUTING.md](../../CONTRIBUTING.md)
- **Stand:** Gerüst (WP-M0-07). Keine Fachlogik; OCR und KoSIT-Validierung folgen in M4. Die Malware-Prüfung (clamd-Client, Quarantäne) läuft seit WP-M4-21 im Backend (`documents-finance`), siehe [m4-cluster-c5.md](../../docs/umsetzungsplan/vertraege/m4-cluster-c5.md); die OCR-Feldextraktion (WP-M4-27) und die Integritätsprüfung der Originale (WP-M4-24) ebenfalls (Adapter `ocr`, Modul `documents-finance`), aus demselben Grund (der Worker hat keinen Datenbankzugriff, die Aufrufschnittstelle Kern/Worker ist offen), siehe [m4-cluster-c10.md](../../docs/umsetzungsplan/vertraege/m4-cluster-c10.md).

## Layout

| Pfad | Inhalt |
|---|---|
| `src/taktovia_documents/ports.py` | Schnittstellen-Platzhalter `OcrAdapter`, `MalwareScanner`, `KositValidator`; `NotConfigured` wirft `NotImplementedError` |
| `src/taktovia_documents/consumer.py` | Queue-Consumer-Skelett (Handler-Registry, unbekannte Aufgabenart wird begründet abgelehnt) |
| `src/taktovia_documents/health.py` | leerer Health-Endpunkt `GET /healthz` (Standardbibliothek, Port 8082) |
| `tests/` | pytest |
| `requirements.lock` | Laufzeit: leer (nur Standardbibliothek); `requirements-dev.lock` mit Hashes für Werkzeuge |
| `Dockerfile` | Image-Build (**nicht ausgeführt**, kein Docker-Daemon in der Entwicklungsumgebung) |

## Lokal bauen und testen

```bash
uv venv .venv --python 3.13
uv pip sync --python .venv/bin/python --require-hashes requirements-dev.lock
uv pip install --python .venv/bin/python --no-deps -e .
.venv/bin/ruff check . && .venv/bin/ruff format --check . && .venv/bin/mypy && .venv/bin/pytest -q
```

## Aufrufschnittstelle Kern <-> Worker: offener Vertragspunkt

Queue über RabbitMQ oder synchroner Aufruf: wird in M4 (Dokumente) entschieden. Der Worker hat **keinen
Datenbankzugriff** (kein Treiber, keine Verbindungskonfiguration); `tests/test_scaffold.py::test_no_database_dependency`
prüft das.

**Offen für M4:** Auswahl OCR-Engine, Malware-Scanner und KoSIT-Validator-Version (siehe [versionen.md](../../docs/umsetzungsplan/versionen.md)).
