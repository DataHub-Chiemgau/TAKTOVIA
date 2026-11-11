# Detailvertrag PSP-Adapter und Provider-Ereignisse (WP-M2-29, Enabler)

Code: `backend/adapters/psp` (Port `PspAdapter`, `PspCommandRef`, `PspOutcome`, Abgleich `reconcile*`; HTTP-Adapter in `internal`), `backend/modules/fare-payment` (`ProviderEvents`, `ProviderEventKeys`, Tabelle `payment_event`), `backend/app/.../payment` (`ProviderEventController`) und `PaymentConfiguration`, `backend/platform/.../tenant/ExternalCallGuard`. Migration `V20290__payment_event.sql`. Eingang: `api/payments.yaml` (`receiveProviderEvent`). Zahlenwerte sind **Planungsdefaults**. Ohne Beschluss D-03 (PSP-Auswahl) gibt es nur den Adapter gegen den PSP-Simulator des Testnetzes (`tests/sandbox/psp`); das blockiert die Produktivschaltung, nicht den Bau. Der Zahlungsautomat (`payment_intent`, Übergänge, Capture-Zeitpunkt) ist WP-M2-30.

## Adapterschnittstelle und Fehlerklassen

`authorize`, `capture`, `void`, `refund` (schreibend, je mit Kommandoreferenz) sowie `status`, `statusByMerchantRef`, `statusByCommandRef`. Beträge sind `Long` in kleinster Einheit (nie Gleitkomma). `paymentMethodRef` ist ein vom PSP gelieferter Referenzwert, Kartendaten kennt der Port nicht (SEC-008, [psp-trennung.md](psp-trennung.md)).

| Ergebnis (`PspOutcome`) | Auslöser | Wirkung beim PSP | Aufrufer |
|---|---|---|---|
| `Ok` | 2xx (auch `AUTHORIZATION_FAILED`/`REQUIRES_ACTION` bei Authorize: gültiges Zahlungsergebnis) | ausgeführt | Zahlstatus fortschreiben |
| `Failed(DECLINED)` | 402/422 `DECLINED_BY_PROVIDER` | keine | definitiv abgelehnt |
| `Failed(REJECTED)` | übrige 4xx (Betrag über Autorisierung, unbekannte Referenz, Schlüssel mit anderem Inhalt) | keine | Fehler des Aufrufs, kein Retry |
| `Failed(CONFLICT)` | 409 (Zustand erlaubt die Operation nicht, Operation läuft noch) | keine | Zustand abgleichen |
| `Unavailable` | Verbindung verweigert, 429, 503 (`Retry-After`) | keine | Retry mit **derselben** Referenz |
| `Unknown` | Timeout, Verbindung nach dem Senden abgebrochen, unklarer 5xx, unlesbare Antwort | **offen** | Abgleich, dann ggf. Retry mit derselben Referenz; kein Misserfolg (`CAPTURE_UNKNOWN`/`RECONCILING`) |

**Kommandoreferenz** (`PspCommandRef`): je logischem Aufruf eine stabile, vom Aufrufer vergebene Referenz (`derive(Geschäftsschlüssel, Art, Folgenummer)`, deterministisch, nennt weder Mandant noch Person). Sie wird als `Idempotency-Key` **und** `operation_ref` gesendet; ein Retry (Timeout, verlorene Antwort, 503) verwendet dasselbe Kommando und erzeugt nie einen neuen Charge-Schlüssel. Eine zweite Teilerstattung ist ein neuer Vorgang (neue Folgenummer). **Abgleich** (`reconcileAuthorize`, `reconcile`): `Performed` (PSP hat die Operation mit dieser Referenz ausgeführt), `NotPerformed` (Wiederholung ist sicher), `Undetermined` (Statusabfrage selbst scheiterte; später erneut, nie ein zweiter Charge auf Verdacht).

**Keine Transaktion während eines PSP-Aufrufs (Leitlinie 3):** jeder Aufruf prüft vor dem Senden `ExternalCallGuard.requireNone` und scheitert in einer offenen Spring-Transaktion mit `IllegalStateException`, ohne etwas zu senden. Nachweise: `PspNoOpenTransactionTest` (an der Datenbank per `pg_stat_activity`, mit Gegenprobe des Detektors) und `PspAdapterSimulatorTest::aufruf_innerhalb_einer_offenen_transaktion_scheitert_vor_dem_senden`; die Architekturregel „`@Transactional` ruft keine Adapter“ besteht weiter.

**Protokoll des HTTP-Adapters:** das des Port-Entwurfs unter `tests/sandbox/psp/openapi` (`POST /psp/v1/payments`, `…/{id}/capture|void|refund`, `GET /psp/v1/payments/{id}`, `GET …?merchant_ref=|operation_ref=`), Basis-URL `taktovia.psp.base-url`, Anbieter `taktovia.psp.provider`, Zeitlimit `taktovia.psp.timeout-seconds` (10), Schlüssel optional als Secretreferenz `taktovia.psp.api-key-ref` (nie ein Wert). Ein Adapter für den gewählten PSP (D-03) setzt dieselbe Schnittstelle um; der Capability-Nachweis je Operation vor Aktivierung bleibt Aufgabe der Sandbox (WP-M0-29, Kap. 08 §8.4).

## Eingang `POST /v1/payments/provider-events/{provider}`

- **Authentisierung:** PSP-spezifische Signatur, **kein** Bearer-Token und nicht der Partner-HMAC. `X-Provider-Signature: t=<Unix-Sekunden>,v1=<hex HMAC-SHA256(secret, "<t>." + roher Body)>` und `X-Provider-Timestamp: <t>` (muss dem `t` der Signatur entsprechen). Operationen mit `security: providerSignature` überspringen Ressourcenserver- und Kontextfilter (`ApiOperation.signatureAuthenticated`); die Operation prüft fail-closed selbst.
- **Reihenfolge:** Größe (Standard 64 KiB, `taktovia.payments.max-event-bytes`, `413`), Signaturformat, Zeitfenster ±300 s (`taktovia.payments.timestamp-tolerance-seconds`), konstantzeitliche HMAC-Prüfung über den exakten Rohbody gegen **alle** Schlüssel des Providers, Inhalt (`422`), Speichern in `payment_event` mit Wirkung in einer Transaktion. Unbekannter Provider, fehlender Header, falsche Signatur und abgelaufenes Fenster sind für den Absender nicht unterscheidbar (`401 UNAUTHENTICATED`, keine Details, kein Eintrag).
- **Kartendatenfilter (SEC-008) und Signaturheader:** der Header-Filter nimmt `X-Provider-Signature` (und `Taktovia-Signature`) aus, weil ihre Hexwerte als HMAC-Ausgabe zufällig eine Luhn-gültige Ziffernfolge enthalten können und eine rechtmäßige Nachricht sonst mit 422 scheiterte (`ProviderEventApiTest::signaturheader_mit_luhn_gueltiger_ziffernfolge_wird_nicht_als_kartennummer_abgewiesen`); alle anderen Header und der Body bleiben geprüft.
- **Mandant und Schlüssel:** `taktovia.payments.providers.<provider>[n].tenant` und `.secret-ref` (`secret://<domäne>/<name>`), bei Rotation `.previous-secret-ref` und `.previous-valid-until`. Mandant und Provider folgen **aus dem Schlüssel**, nie aus dem Payload; ein `tenant_id` auf oberster Ebene ist formal ungültig, in `data` ohne Wirkung. Der Wert wird bei jeder Prüfung aus der Secret-Verwaltung gelesen (Rotation ohne Neustart); fehlt die Verwaltung, der Wert, ist die Domäne fremd oder die Angabe ein Klartextwert, gilt der Schlüssel nicht. Secret-Anbieter bis D-05 offen.
- **Deduplikation:** `payment_event` eindeutig je `(tenant_id, provider, event_id)` (Ereignis-ID des PSP ist Text, daher nicht `inbox`, deren Ereignis-ID `uuid` der Plattformhülle ist), mit `payload_sha256` (kanonische Form). Antworten: `202 ACCEPTED` erst nach Commit; gleiche ID, gleicher Hash `200 ALREADY_RECEIVED` (keine zweite Wirkung); gleiche ID, anderer Hash `409 CONFLICT` plus Sicherheitsereignis `security.event.payload_hash_mismatch` (Provider, Typ, Grund; nie Payload), der erste Payload bleibt maßgeblich.
- **Inhalt:** `event_id`, `type` (`payment.*`), `created_at`, `data` Pflicht; `sequence`, `provider` optional (`provider` muss dem Pfad entsprechen). `data.provider_ref` und `data.state` sind Pflicht, Beträge ganzzahlig und nicht negativ, `currency` ISO-Großbuchstaben, `dispute_state` `OPEN|WON|LOST`. Gespeichert werden nur diese Werte, **nie** der Rohpayload; kartennummernähnliche Daten weist der Eingangsfilter mit 422 ab (SEC-008).

## Abbildung PSP-Ereignistyp → interne Art → internes Ereignis

| PSP-Typ | `internal_kind` | | PSP-Typ | `internal_kind` |
|---|---|---|---|---|
| `payment.requires_action` | `REQUIRES_ACTION` | | `payment.refund_pending` | `REFUND_PENDING` |
| `payment.authorized` | `AUTHORIZED` | | `payment.partially_refunded` | `PARTIALLY_REFUNDED` |
| `payment.authorization_failed` | `AUTHORIZATION_FAILED` | | `payment.refunded` | `REFUNDED` |
| `payment.capture_pending` | `CAPTURE_PENDING` | | `payment.dispute.opened` | `DISPUTE_OPENED` |
| `payment.captured` | `CAPTURED` | | `payment.dispute.won` | `DISPUTE_WON` |
| `payment.void_pending` | `VOID_PENDING` | | `payment.dispute.lost` | `DISPUTE_LOST` |
| `payment.voided` | `VOIDED` | | anderer Typ `payment.*` | `UNMAPPED` (quittiert, gespeichert, ohne Wirkung) |

Die fachliche **Wirkung** des Eingangs ist das interne Ereignis `payment.provider_event_received` (Outbox, in derselben Transaktion, je Ereignis-ID genau einmal; [Schema](../../../api/events/payment.provider_event_received.v1.schema.json), [Katalog](m2-ereigniskatalog.md)). Es ändert **keinen** Zahlstatus: der Zahlungsautomat (WP-M2-30) ordnet über `provider_ref` dem `payment_intent` zu und führt Übergänge erst nach Statusabfrage beim PSP aus (Leitlinie 6, [zahlung-ablauf.md](zustaende/zahlung-ablauf.md)). `DISPUTED` bleibt ein getrenntes Feld (M6).

## Nicht ausgeführt / Ersatz

Kein realer PSP und keine Sandbox in dieser Umgebung (D-03 offen, WP-M0-29 liefert den Spike-Bericht): alle Nachweise laufen gegen den **echten PSP-Simulator** (`tests/sandbox/psp/psp_sim.py`, Kindprozess mit echtem HTTP, Fehlermodi `timeout`, `lost_response`, `duplicate`, `unavailable`, `decline`, Webhook-Zustellung `valid`/`bad_signature`/`late`, 100-fach). Es sind Simulator-Ergebnisse, kein Nachweis gegen einen realen PSP. Der Simulator sendet zusätzlich zu `X-PSP-*` die Header `X-Provider-Signature` und `X-Provider-Timestamp` (additiv). Die Secret-Verwaltung läuft nicht: die Schlüsselquelle wird in den HTTP-Tests durch ein Testregister ersetzt, der produktive Pfad (Secretreferenzen, Rotation, fail-closed) hat eigene Tests mit `FileSecretStore`.

## Tests

| Nachweis | Test |
|---|---|
| Tabelle `payment_event` (Eindeutigkeit, RLS, unveränderlich, Wertebereiche, 100 parallele Einfügungen) | `db/tests/test_payment_event.py` |
| Eingang gegen die echte Datenbank (Signatur, Zeitfenster, Dedupe, 100 parallel, 409 mit Sicherheitsereignis, Mandant aus Schlüssel, Rotation, Abbildung, Schema des internen Ereignisses) | `backend/modules/fare-payment/src/test/kotlin/de/taktovia/farepayment/ProviderEventsTest.kt` |
| Eingang über die echte Filterkette mit eingeschaltetem Ressourcenserver ohne Bearer-Token | `backend/app/src/test/kotlin/de/taktovia/app/payment/ProviderEventApiTest.kt`, Vertragsfall `receiveProviderEvent` in `ContractCoverageTest` (`PaymentContractCases`) |
| Webhooks des Simulators (100 identisch, ungültige Signatur, verspätet, parallel zur synchronen Antwort) | `PspSimulatorWebhookTest.kt` |
| Adapter: fünf Operationen, Fehlerklassen, Timeout/verlorene Antwort/Duplikat/503 mit derselben Kommandoreferenz | `backend/adapters/psp/src/test/kotlin/de/taktovia/adapters/psp/PspAdapterSimulatorTest.kt` |
| Keine offene Transaktion während des PSP-Aufrufs | `PspNoOpenTransactionTest.kt` |
| Schlüsselquelle aus Secretreferenzen | `PaymentConfigurationTest.kt` |
