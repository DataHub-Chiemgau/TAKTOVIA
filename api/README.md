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
| `identity.yaml` | `/v1/me`, Mandantenwechsel, Mitgliedschaften, Rechtsträger, Organisationen (WP-M1-05), Plattformverwaltung (`/v1/platform/…`, WP-M1-05), Regionen, Betriebshöfe, Kostenstellen, Sitzungen (WP-M1-03) | `identity` |
| `sessions.yaml` | eigene Sitzungen, „alle Geräte abmelden“, Kontosperre, Wiederherstellung (WP-M1-10; die Admin-Sitzungsoperationen stehen in `identity.yaml`) | `identity` |
| `registry.yaml` | wirksame Konfiguration, Revisionen anlegen/lesen/prüfen, Wirkungsanalyse (Veröffentlichen steht in `config.yaml`) | `config` |
| `passengers.yaml`, `webhooks.yaml`, `services.yaml` | Fahrgäste, Webhook-Abonnements, Dienstkopf | `communication-support`, `service-geo` |
| `compliance.yaml` | Auditabfrage, Unternehmensprofil (WP-M1-24, Feldliste in `docs/umsetzungsplan/vertraege/schemas/tenant-profile.v1.schema.json`), Aufbewahrungsregeln | `platform`/`config` |
| `fleet.yaml` | Fahrzeugrollen (WP-M1-34), Fahrerfreigaben | `fleet-safety` |
| `driver-releases.yaml` | zeitlich begrenzte Fahrerfreigabe und minimierte Projektion (WP-M1-35, LH-I.4-MUL-005; Feldliste in `docs/umsetzungsplan/vertraege/schemas/driver-release-projection.v1.schema.json`) | `identity` |
| `booking.yaml`, `geo.yaml`, `payments.yaml`, `notifications.yaml` | Einbetreiberbuchung (`/v1/ride-offers`, `/v1/bookings`), Bediengebiete und Haltepunkte, PSP-Ereignisse, Nachrichten (WP-M2-01; Detailverträge in `docs/umsetzungsplan/vertraege/m2-detailvertraege.md`) | `booking-dispatch`, `service-geo`, `fare-payment`, `communication-support` |
| `contract-pending.txt` | dokumentierte, noch nicht gebaute Operationen; kann nur schrumpfen (siehe unten) | – |
| `events/` | Ereignisschemas, siehe dortige README | – |

Verweise zwischen Dateien: `common.yaml#/components/...`. Das Bündeln löst sie zu internen Verweisen auf.

```bash
python api/tools/bundle.py --check       # api/ erweitert den Pflichtenheft-Stand nur kompatibel
python api/tools/lint.py                 # Konventionen aus Kap. 05 §5.1
python api/tools/test_api.py             # Selbsttests der Werkzeuge
python api/tools/bundle.py --write-docs  # nur nach bewusster Vertragsänderung: Pflichtenheft-Datei neu erzeugen
python api/tools/gen_docs.py --write     # docs/api/index.html (Entwicklerportal-Seite: Version, Scopes, Fehlercodes je Endpunkt)
python api/tools/check_routes.py backend/app/build/contract/routes.json   # CI: Route ohne operationId bricht den Build
```

Weitere `api/<modul>.yaml` werden ohne Änderung der Werkzeuge mitgebündelt (alphabetisch nach den festen Dateien).

## Vertragspipeline (WP-M1-03)

- **Route ↔ Vertrag:** `ContractCoverageTest` (Backend) liest die tatsächlich registrierten Routen, schreibt `backend/app/build/contract/routes.json` und verlangt genau eine `operationId` je Route; `check_routes.py` wiederholt den Abgleich unabhängig in der CI.
- **Pending-Liste:** `contract-pending.txt` nennt dokumentierte, noch nicht gebaute Operationen. Wer einen Endpunkt baut, streicht ihn dort und trägt in `M1ContractCases` (Backend-Tests) einen Vertragsfall mit positivem Fall und Fehlerfall ein; der Runner prüft Status und Antwort gegen das Schema. Eine neue, noch nicht gebaute Operation muss bewusst in die Liste.
- **Anfragevalidierung zur Laufzeit:** die Anwendung validiert schreibende Requests gegen dieselben Dateien (`additionalProperties: false`, Geld- und Zeitprüfung über `x-money`/`x-time`).

Abhängigkeiten: `PyYAML`, `jsonschema`, `openapi-spec-validator`.

## Linter-Ausnahmen (namentlich begründet)

- `receivePartnerEvent`: kein `Idempotency-Key`, stattdessen Signaturheader und Inbox-Deduplikation (Kap. 05 §5.6).
- `planJourney`, `recordDriverEvent`: `Idempotency-Key`, aber kein `If-Match`, weil sie Neues anlegen statt eine Revision zu ändern.
- Allgemein: mutierende Operationen auf einem Pfad **ohne Pfadvariable** (Sammelpfade wie `POST /v1/regions`, `POST /v1/me/tenant-switch`) tragen kein `If-Match`; es gibt keine Revision, auf die es sich beziehen könnte. Auf Pfaden mit Variable ist `If-Match` Pflicht.

Neue Ausnahmen brauchen Review. Neue Endpunkte aus Kap. 05 §5.7 entstehen als Detailverträge der jeweiligen Meilensteine. Codegenerierung für Server und Clients wird in M1 entschieden.
