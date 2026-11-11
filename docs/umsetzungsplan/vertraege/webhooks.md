# Detailvertrag Webhook-Grundlage (WP-M1-16, Enabler)

Code: `backend/platform/.../webhook` (`WebhookSignature`, `InboundWebhookReceiver`, `WebhookSubscriptionService`, `WebhookFanout`, `WebhookDeliveryWorker`, `PinnedHttpTransport`), `backend/app/.../webhook` (`WebhookSubscriptionController`, `InboundWebhookResponder`). Migration `V1160__webhook_grundlage.sql`. Zahlenwerte sind **Planungsdefaults** (OP-17, OP-20).

## Signaturvertrag (ein- und ausgehend)

`Taktovia-Signature: t=<Unix-Sekunden>,kid=<Schlüssel-ID>,v1=<hex>`; `v1` = HMAC-SHA-256 über `"<t>."` plus den **exakten Rohbody** (nicht das geparste JSON). Prüfung konstantzeitlich (`MessageDigest.isEqual`).

- **Eingang:** Reihenfolge Größe (Standard 256 KiB, `413`), Format, Zeitfenster ±300 s (sekundengenau), Schlüssel per `kid` mit Bindung an Quelle und Mandant, HMAC, Inhalt, Inbox-Commit mit Wirkung in einer Transaktion. Unbekannter Schlüssel und falsche Signatur sind nicht unterscheidbar (`401`, keine Details). **Rotation:** alter und neuer Schlüssel stehen nebeneinander, der alte mit `validUntil`. Mandant und Quelle folgen aus dem Schlüssel, nie aus dem Payload.
- **Ausgang:** zusätzlich Header `Taktovia-Event-Id`, `Taktovia-Event-Type`, `Taktovia-Delivery-Attempt`, `User-Agent: Taktovia-Webhooks/1`. `kid` = erste 12 Hex-Zeichen von SHA-256 des Schlüssels (nennt nicht den Wert); der Empfänger ordnet `kid` seinem Schlüssel zu. Rotation: Der Wert hinter der Secret-Referenz wechselt ohne Neustart, jede Zustellung trägt die `kid` des gerade verwendeten Schlüssels.

## Eingangsquittungen (Kap. 05 §5.6)

| Fall | Antwort |
|---|---|
| angenommen (Inbox-Commit erfolgt, Wirkung ausgeführt) | `202` `{"status":"ACCEPTED"}` |
| gleiche Ereignis-ID, gleicher Payload | `200` `{"status":"ALREADY_RECEIVED"}` |
| gleiche Ereignis-ID, anderer Payload | `409 CONFLICT`, Sicherheitsereignis `security.event.payload_hash_mismatch`, Quarantäne (`webhook_quarantine`, nur Hashes) |
| Signatur/Zeitfenster/Schlüssel | `401 UNAUTHENTICATED`, kein Inbox-Eintrag |
| zu groß | `413 PAYLOAD_TOO_LARGE`, formal ungültig `400` |

Ereignisfelder: `event_id` (UUID), `event_type`, `occurred_at`, optional `aggregate_id`, `aggregate_revision`, `data`. Dedupe über `inbox` `(tenant_id, consumer = webhook.<quelle>, event_id)`; Hash = SHA-256 der kanonischen Form des gesamten Bodys. Schlägt die Wirkung fehl, rollt der Inbox-Eintrag zurück (der Absender wiederholt). **Nicht in M1:** Partnerbindung `/v1/federation/partners/{partner_id}/events` (M6), PSP-Signatur (M2); beide nutzen `InboundWebhookResponder`.

## Abonnement (`webhook_subscription`) und API

- DDL: `url` (https, ≤ 2000), `event_types[1..50]`, `secret_ref` (`secret://<domäne>/<name>`, **nie ein Wert**, CHECK), `active`, `revision`, `created_by` (actor_ref), RLS, Besitzer `platform`. Deaktivieren per UPDATE (kein DELETE-Recht).
- API: `POST /v1/webhook-subscriptions` (201, `Location`, `ETag`), `GET` Liste (Cursor) und `GET /{subscription_id}`, `POST /{subscription_id}/deactivate` (`If-Match`, 200, offene Zustellungen `CANCELLED`). Rechtematrix `webhooks:read`/`webhooks:write`: Administrator. Fremdes Abonnement `404`. Anlegen und Deaktivieren auditiert (`webhook.subscription.created|deactivated`) in derselben Transaktion.
- **Ziel-URL (SSRF, SEC-009):** nur `https`, Port 443 (konfigurierbar), öffentliche Adressen; abgewiesen werden Loopback, privat, Link-Local, Metadaten, IPv4-in-IPv6, ungewöhnliche IP-Schreibweisen, interne Namen, Zugangsdaten in der URL (`422 TARGET_NOT_ALLOWED`, Pointer `/url`); beim Anlegen wird aufgelöst und geprüft, **vor jeder Zustellung erneut** (DNS-Rebinding), und es wird nur an die geprüften Adressen verbunden. Weiterleitungen werden nie verfolgt (3xx = Fehlversuch).
- **Ereignistypen in P1 (vorläufig, OP-17):** `membership.granted`, `membership.revoked`, `operation.state_changed`, `promise.changed`. Fachliche Freigabe der Liste offen.
- **Secret:** Optional `secret_ref` (Domäne der Umgebung, `taktovia.secrets.domain`); ohne Angabe vergibt die Plattform `webhook_<id>`, den Wert stellt der Betrieb in der Secret-Verwaltung bereit (D-05 offen). Solange er fehlt, scheitert die Zustellung mit `secret_unavailable` und wird wiederholt (kein Verlust). Kein Klartextsecret im Vertrag (`additionalProperties:false`).

## Zustellung (`webhook_delivery`)

- **Fan-out** (`WebhookFanout`): je aktivem Abonnement und passendem Ereignis der Outbox genau eine Zustellung (`UNIQUE(Abonnement, Ereignis)`), nur Ereignisse, die an den Mandanten selbst adressiert sind (`recipient_tenant_id = tenant_id`) und nach dem Anlegen des Abonnements entstanden.
- **Payload (empfängergebunden, minimiert):** Ereignishülle ohne Nutzdaten (`event_id`, `event_type`, `schema_version`, `aggregate_id`, `aggregate_revision`, `occurred_at`); Details ruft der Empfänger über die autorisierte API ab.
- **Worker:** `FOR UPDATE SKIP LOCKED` mit Lease (60 s), Senden **außerhalb** jeder DB-Transaktion, Zeitlimit 10 s. Retry-Staffel wie Outbox-Relay (1 s, 5 s, 30 s, 2 min, 10 min, dann exponentiell mit Jitter bis 24 h); `429`/`503` mit `Retry-After` verlängern die Wartezeit (höchstens 24 h). Fehlercodes nur technisch (`http_5xx`, `http_4xx`, `http_429`, `connection_failed`, `target_not_allowed`, `secret_unavailable`, `redirect_not_followed`).
- **Dead-Letter:** nach 12 Fehlversuchen `DEAD` und **genau eine** Betreiberwarnung `webhook-delivery/dead_letter` über den Betriebsalarmkanal (WP-M1-47); Attribute nur `event_type`, `attempts`, `reason_code`. Danach keine weiteren Versuche, kein stilles Löschen. Einen Betriebs-Zeitgeber startet die Anwendung mit `taktovia.webhooks.worker-enabled=true`, `taktovia.secrets.dir` und einer `TenantSource` des Betriebs (OP-06/OP-08).
- `PinnedHttpTransport`: HTTP/1.1 mit fester Zieladresse, TLS mit SNI und Hostnamenprüfung gegen den Namen der URL.

## Nicht ausgeführt / Ersatz

Kein Broker, kein TLS-Ziel mit öffentlicher Adresse und kein Partner in dieser Umgebung: Zustellung und Eingang laufen gegen einen echten lokalen HTTP-Empfänger (Sockets) und einen lokalen TLS-Server mit zur Laufzeit erzeugtem Zertifikat (`keytool`); die DNS-Auflösung ersetzt ein Testresolver. Die SSRF-Sperren werden mit dem produktiven Guard geprüft.

## Tests

`backend/platform/src/test/kotlin/de/taktovia/platform/webhook/` (`WebhookInboundTest`, `WebhookDeliveryTest`, `WebhookSubscriptionServiceTest`), `backend/app/src/test/kotlin/de/taktovia/app/WebhookSubscriptionApiTest.kt`, `InboundWebhookHttpTest.kt`, `db/tests/test_m1_16_webhook.py`, `ContractCoverageTest` (Vertragsfälle der vier Operationen).
