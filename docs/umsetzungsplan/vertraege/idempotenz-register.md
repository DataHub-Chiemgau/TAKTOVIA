# Detailvertrag Idempotenzregister (WP-M1-13, ergänzt LH-6.17-INT-004)

Dieser Vertrag legt fest, was der Detailvertrag [LH-6.17-INT-004](LH-6.17-INT-004.md) als „Detailvertrag vorab“ offen ließ: Kanonisierungsregel, relevante Header, Cachehaltung und Verhalten bei laufender Erstverarbeitung. Zahlenwerte sind **Planungsdefaults** (OP-20), keine Freigabe.

## Scope und Eintrag

- Scope `(principal_ref, tenant_id, command_scope, key)` = Primärschlüssel von `idempotency_record` (RLS je Mandant).
  - `principal_ref` = `<issuer>|<subject>` des **geprüften** Tokens, nie aus dem Request; `tenant_id` = aktiver Mandant des `RequestContext`; `command_scope` = `operationId` des API-Vertrags; `key` = Header `Idempotency-Key` (`^[A-Za-z0-9._:-]{16,128}$`, sonst `422 VALIDATION_FAILED` mit Pointer `/$header/Idempotency-Key`; fehlender Header an einer schreibenden Operation ebenso mit `REQUIRED`).
- Zustände: `IN_PROGRESS` (Erstverarbeitung läuft, `locked_until` = Lease, Standard 120 s) und `COMPLETED` (angenommenes Ergebnis: `http_status` 2xx, `response_json`, `response_headers` mit `Location`, `ETag`, `Content-Type`, `Retry-After`, `operation_id`).
- Anlage atomar per `INSERT … ON CONFLICT DO NOTHING`, Bewertung unter `FOR UPDATE`: die Datenbank entscheidet, wer ausführt (auch bei 100 gleichzeitigen Requests).

## Kanonischer Request-Fingerprint (Version 1)

`SHA-256` (Hex) über, in dieser Reihenfolge, durch Zeilenumbruch getrennt: `v1`, HTTP-Methode (groß), `operationId`, Pfad (konkreter Pfad inkl. Ressourcen-ID, ohne abschließenden `/`), Query (dekodiert, nach Name und Wert sortiert, neu kodiert), fachlich relevante Header, Body.

- **Fachlich relevante Header:** `If-Match` (die erwartete Ressourcenrevision; Name case-insensitiv, fehlend = `-`). Nicht eingehen: `Idempotency-Key`, `traceparent`, `Authorization`, `X-Taktovia-Context`, Content-Type und Transportheader. Mandant und Akteur stehen im Scope, nicht im Fingerprint.
- **Body:** gültiges JSON in kanonischer Form (Objektschlüssel sortiert, kompakt, Zahlen ohne Schreibweisenunterschied: `1`, `1.0`, `1e0` sind gleich, Arrayreihenfolge zählt); kein JSON: Hash der Rohbytes; leerer Body: `-`.
- Gleicher Schlüssel, anderer Fingerprint (anderer Body, Pfad, Query oder `If-Match`) → `409 IDEMPOTENCY_CONFLICT`, keine Ausführung, kein Teil des fremden Ergebnisses in der Antwort.

## Ablauf (Filter nach Authentisierung, Kontext, Autorisierung und Eingabeprüfung; vor dem Controller)

1. Jede Anfrage durchläuft zuerst Authentisierung, Mandantenkontext und Rechtematrix. Wiederholungen werden dadurch **neu authentisiert und neu autorisiert**; nach Rechteentzug (`403`) oder ohne gültiges Token (`401`) gibt es das gespeicherte Ergebnis nie.
2. `begin`: neuer Eintrag → ausführen; vorhandener Eintrag mit gleichem Fingerprint und `COMPLETED` → gespeichertes Ergebnis wiederholen (`Idempotent-Replayed: true`); `IN_PROGRESS` mit gültiger Lease → bis `in-progress-wait-millis` (Standard 10 s) auf das Ergebnis warten, danach `409 OPERATION_IN_PROGRESS` mit `Retry-After: 1`; `IN_PROGRESS` mit abgelaufener Lease (Prozess abgebrochen) → Übernahme, erneut ausführen.
3. Der Controller läuft **außerhalb** jeder Registertransaktion. Ein **2xx-Ergebnis** wird gespeichert (bevor der Client es sieht), jedes andere Ergebnis (4xx/5xx) und jede Ausnahme geben den Schlüssel frei; die Wirkung ist dann nicht eingetreten, ein Retry wird neu ausgeführt und neu geprüft (z. B. `412` mit aktueller Revision).
4. Die Wiederholung führt die fachliche Prüfung nicht erneut aus: ein angenommenes Kommando erscheint nie nachträglich als abgelaufen und eine veraltete `If-Match`-Revision der Wiederholung löst keinen `412` aus.
5. `Idempotency-Key` gilt je Scope; gleicher Schlüssel bei anderem Mandanten, Akteur oder Operationstyp kollidiert nicht.

## Cachehaltung und Bereinigung

- Antwortcache 72 h (`taktovia.idempotency.response-ttl-hours`, Entwurfswert des Pflichtenhefts, Planungsdefault). Nach `expires_at` gilt der Schlüssel als neu; eine zweite fachliche Wirkung verhindern dann **fachliche Unique-Schlüssel** (Outbox `(tenant_id, aggregate_id, aggregate_revision, event_type)`, Mitgliedschaft, später Buchung/Zahlung in M2/M4). Das Register ersetzt sie nicht.
- Bereinigung `IdempotencyRegistry.purgeExpired` / `IdempotencyCleanup`: entfernt abgelaufene Ergebnisse und abgebrochene Verarbeitungen (Lease abgelaufen), **nie** einen laufenden Befehl (Lease gültig), höchstens 1000 Zeilen je Mandant und Lauf. Weil RLS die Mandantenaufzählung verhindert, braucht der Lauf eine `TenantSource` des Betriebs (OP-06/OP-08, wie der Outbox-Relay); die Anwendung startet den Job (`taktovia.idempotency.cleanup-minutes`, 10) sobald eine solche Quelle als Bean vorliegt.

## Grenzen und offene Punkte

- Stirbt der Prozess nach dem Commit der Fachwirkung und vor dem Speichern des Ergebnisses, führt die Übernahme nach Leaseablauf den Befehl erneut aus. Die Einmaligkeit trägt dann der fachliche Unique-Schlüssel. Ein Befehl darf die Lease (120 s) nicht überschreiten (Anfrage-Zeitlimit kleiner als die Lease).
- `operation_id` ist im Register nullable; der Fremdschlüssel auf `operation` wird mit WP-M1-15 nutzbar (`operation.journey_id` nullable, OP-15). Controller melden die Operation über das Anfrageattribut `taktovia.idempotency.operationId`.
- Nachweis in M1 am Testbefehl `createProbeCommand`, am Stellvertreter `publishProbeCommand` (If-Match-geschützt, Revision +1, Ereignis) und an `POST /v1/memberships`; `publishConfig` mit WP-M1-22, Buchungs- und Zahlungsbefehle mit WP-M2-24/-30 über denselben Filter.

## Tests

`backend/platform/src/test/kotlin/de/taktovia/platform/idempotency/` (`IdempotencyFilterTest`, `IdempotencyCleanupTest`), `backend/app/src/test/kotlin/de/taktovia/app/PublishConfigIdempotencyTest.kt`, `backend/app/src/test/kotlin/de/taktovia/app/IdempotencyApiTest.kt`, `db/tests/test_04_trigger.py`.
