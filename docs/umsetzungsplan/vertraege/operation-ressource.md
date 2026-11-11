# Detailvertrag `operation`-Ressource mit Lease/Fencing (WP-M1-15, Enabler)

- **Code:** `backend/platform/.../operation` (`OperationService`, `OperationRunner`, `OperationStore`, `OperationHandler`), API `GET /v1/operations/{operation_id}` (`getOperation`, Scope `operations:read`) in `backend/app/.../operations`.
- **Tabelle:** `operation` (Besitzer ab WP-M1-15 `platform`, bisher `journey-federation`; `saga_step` bleibt bei `journey-federation` und ist ohne Partnerlogik nur als Struktur vorhanden). Migration `V1150__operation_lease.sql`.
- **Zustandsautomat:** [zustaende/operation.md](zustaende/operation.md) (`QUEUED`, `RUNNING`, `RECONCILING`, `SUCCEEDED`, `FAILED`, `MANUAL_REVIEW`; Übergänge seit `V1170`). Der Runner prüft jeden Übergang vor dem Schreiben gegen die Tabelle, die Datenbank (`enforce_state_transition`) zusätzlich.

## Entscheidungen zu OP-15 (journey_id, kind)

- `operation.journey_id` ist **nullable** (Fremdschlüssel bleibt): nicht reisebezogene M1-Befehle (Konfigurationspublikation, Wirkungsanalyse, Aufbewahrungslauf) brauchen keine Reise. Im API-Schema ist `journey_id` bereits nullable.
- `kind`: `HOLD`, `CONFIRM`, `CANCEL`, `RECONCILE` (Kap. 02 §7.3) plus `PUBLISH_CONFIG`, `IMPACT_ANALYSIS`, `RETENTION_RUN`, `TEST_COMMAND`. Das Enum steht in `OperationKind` und im Schema `Operation.kind` (`api/common.yaml`); die Spalte bleibt Text (spätere Meilensteine ergänzen Arten ohne Migration). **Fachliche Bestätigung der M1-Arten offen** (OP-15).

## Lease und Fencing

| Spalte | Bedeutung |
|---|---|
| `lease_until`, `lease_owner` | Wer die Operation bearbeitet, und bis wann. Abgelaufen = Verarbeitung gilt als abgebrochen. |
| `fencing_token` | Steigt bei **jeder** Aufnahme um 1 (monoton, der DB-Trigger `operation_guard` verbietet Absenken). Jeder Schreibzugriff eines Workers trägt seinen Token und läuft unter `FOR UPDATE`; stimmt er nicht mehr, wird nichts geschrieben (`LeaseLostException`). |
| `next_attempt_at` | Frühester nächster Versuch bzw. nächster Abgleich. |
| `deadline_at` | Danach läuft kein Handler mehr (siehe Deadline). |
| `revision` | Steigt nur bei Zustandswechsel; je Wechsel genau ein Outbox-Ereignis `operation.state_changed` ([Schema](../../../api/events/platform.statechanged.v1.schema.json)), Revisionen lückenlos. |

- **Aufnahme (`claimNext`):** `QUEUED` fällig → `RUNNING`; `RECONCILING` fällig mit freier Lease → bleibt `RECONCILING`; `RUNNING` mit abgelaufener Lease → bleibt `RUNNING` (Übernahme, neuer Token, kein Zustandswechsel). `FOR UPDATE SKIP LOCKED`: konkurrierende Worker bekommen verschiedene oder keine Operationen.
- **Wirkung genau einmal:** Lokale Wirkung über `OperationContext.commit { jdbc -> … }`: gefencter Abschluss und Wirkung in **einer** Transaktion; ein Worker mit verlorener Lease rollt beides zurück. Externe Wirkung (Partner, PSP) trägt `operationKey` (`op-<tenant>-<id>`, über alle Versuche und Worker gleich) als Idempotenzschlüssel bzw. Abgleichsreferenz; Absturz zwischen Nebenwirkung und Speicherung führt nach Leaseablauf zur erneuten Aufnahme, der Partner dedupliziert.
- **Lease-Dauer:** 60 s (`taktovia.operations.lease-seconds`), Verlängerung per `OperationContext.renew()`. **Backoff:** `RetrySchedule` des Outbox-Relays (1 s, 5 s, 30 s, 2 min, 10 min, dann exponentiell mit Jitter bis 24 h); Abgleich ohne Angabe nach 5 s. Alle Werte Planungsdefaults (OP-20).

## Ausgang eines Handlerschritts

| Ergebnis | Wirkung |
|---|---|
| `Succeeded(code)` / `commit` | → `SUCCEEDED`, Lease frei |
| `Failed(code)` | → `FAILED`, **nur** nach bestimmtem Ergebnis |
| `Unknown(code, retryAfter)` | `RUNNING` → `RECONCILING` (bzw. Verbleib mit neuem `next_attempt_at`); unbestimmt wird nie zu Erfolg oder Fehler verkürzt |
| unerwartete Ausnahme | wie `Unknown("UNKNOWN_OUTCOME")`, nie `FAILED` |
| `OperationRetryException` (vor jeder Wirkung) | `RUNNING` → `QUEUED` mit Backoff |
| `ManualReview(code)` | → `MANUAL_REVIEW` |

- **Deadline überschritten** (vor Handleraufruf): nie gestartet (`QUEUED`) → `FAILED(DEADLINE_EXCEEDED)` (bestimmt); gestartet (`RUNNING`) → `RECONCILING(DEADLINE_EXCEEDED)`; bereits `RECONCILING` → `MANUAL_REVIEW(DEADLINE_EXCEEDED)`.
- Fehlt ein Handler für die Art: `MANUAL_REVIEW(NO_HANDLER)`.

## API

- `GET /v1/operations/{operation_id}`: 200 `Operation` mit `retry_after_seconds` (1 bis 300; bis `next_attempt_at`, sonst 3; bei Endzuständen 1, der Status genügt); fremde oder unbekannte Operation 404 (RLS); ohne Scope `operations:read` 403 (Rechtematrix); ohne Anmeldung 401.
- `OperationAccepted` (`202`, `Location: /v1/operations/{id}`, `Retry-After`, Body `Operation`): `OperationResponses.accepted(view)`. Controller legen die Operation in der Fachtransaktion an (`OperationService.enqueue`) und melden sie dem Idempotenzfilter (`IdempotencyFilter.OPERATION_ID_ATTRIBUTE`); eine Wiederholung liefert dieselbe Operation.

## Offen / nicht Teil

Journey-Saga und Partnerabgleich (M6), Hold/Confirm im Betreiberkern (M2). Ein Betriebs-Worker braucht eine `TenantSource` des Betriebs (OP-06/OP-08); ohne sie startet kein Runner-Zeitgeber (`taktovia.operations.worker-enabled`). Fachliche Bestätigung der Übergangstabelle steht in [zustaende/operation.md](zustaende/operation.md).

## Tests

`backend/platform/src/test/kotlin/de/taktovia/platform/operation/OperationRunnerTest.kt`, `backend/app/src/test/kotlin/de/taktovia/app/OperationApiTest.kt`, `db/tests/test_m1_15_operation.py`, `ContractCoverageTest` (Vertragsfall `getOperation`).
