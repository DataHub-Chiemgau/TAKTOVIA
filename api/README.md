# api

Bearbeitbare Quelle der API-Verträge (OpenAPI 3.1), aufgeteilt nach Bereichen. **Führend ist dieses Verzeichnis.** `docs/pflichtenheft/api/openapi.yaml` ist der Ausgangsstand des Pflichtenhefts und wird nur noch aus `api/` erzeugt.

| Datei | Inhalt | Backend-Modul |
|---|---|---|
| `common.yaml` | Metadaten, Sicherheitsschemata `oidc` und `partnerSignature`, gemeinsame Parameter (`IdempotencyKey`, `IfMatch`, `Traceparent`, Pfad-IDs), Antworten (`Problem`, `OperationAccepted`), gemeinsame Schemas (Geldtypen, `Problem`, …) | `platform` |
| `federation.yaml` | Reiseplanung, Angebote, Hold, Bestätigung, Storno, Partnerereignisse (8 Pfade) | `journey-federation` |
| `operations.yaml` | Bearbeitungsstatus asynchroner Operationen | `platform` |
| `finance.yaml` | Kostenumlage, Belegfreigabe | `documents-finance` |
| `config.yaml` | Konfigurationsrevision veröffentlichen | `config` |
| `rosters.yaml` | Dienstplan veröffentlichen | `staff-time` |
| `driver.yaml` | Fahrerereignisse (Offline-Queue) | `booking-dispatch` (Zuordnung vorläufig) |
| `events/` | Ereignisschemas, siehe dortige README | – |

Verweise zwischen Dateien: `common.yaml#/components/...`. Das Bündeln löst sie zu internen Verweisen auf.

```bash
python api/tools/bundle.py --check       # api/ gebündelt == Pflichtenheft-Stand
python api/tools/lint.py                 # Konventionen aus Kap. 05 §5.1
python api/tools/test_api.py             # Selbsttests der Werkzeuge
python api/tools/bundle.py --write-docs  # nur nach bewusster Vertragsänderung: Pflichtenheft-Datei neu erzeugen
```

Abhängigkeiten: `PyYAML`, `jsonschema`, `openapi-spec-validator`.

## Linter-Ausnahmen (namentlich begründet)

- `receivePartnerEvent`: kein `Idempotency-Key`, stattdessen Signaturheader und Inbox-Deduplikation (Kap. 05 §5.6).
- `planJourney`, `recordDriverEvent`: `Idempotency-Key`, aber kein `If-Match`, weil sie Neues anlegen statt eine Revision zu ändern.

Neue Ausnahmen brauchen Review. Neue Endpunkte aus Kap. 05 §5.7 entstehen als Detailverträge der jeweiligen Meilensteine. Codegenerierung für Server und Clients wird in M1 entschieden.
