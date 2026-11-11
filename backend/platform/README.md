# platform

Querschnitt für alle Module: Tenantkontext, RLS-Anbindung, Outbox/Inbox, Idempotenz, Audit, Geld- und Zeittypen, Cache/Rate-Limit.
Pakete (Platzhalter bis M1): `tenant`, `rls`, `outbox`, `idempotency`, `audit`, `money`, `time`, `cache`.

- Darf von allen Modulen und Adaptern verwendet werden, hängt selbst von keinem Modul, Adapter oder von `app` ab.
- Redis-Clients ausschließlich in `cache` (Leitlinie 4).
- Tabellenbesitz: `currency`, `idempotency_record`, `outbox`, `inbox`, `audit_event` (siehe `db/table_owners.d/`).

## M1-Bausteine (Cluster platform-basis: WP-M1-14, -36, -46, -47)

| Paket | Inhalt |
|---|---|
| `secret` | `SecretRef` (`secret://<domäne>/<name>`), `SecretResolver` mit Umgebungstrennung, `FileSecretStore`, `SecretBackedDataSource` (Rotation ohne Neustart) |
| `transport` | `TransportPolicy`: Klartext-Konfiguration je Komponente wird abgewiesen (TLS 1.2/1.3) |
| `alert` | Betreiberwarnung: `OperatorAlertPort`, Zulassungsregel ohne Personendaten, Zusammenfassung zu einem offenen Alarm, Metriken, Logzeile, `HttpsWebhookChannel` |
| `audit` | `AuditPort`/`SecurityEventPort` in der Fachtransaktion, `AuditRedactor`, `@AuditRequired` (Architekturregel) |
| `outbox` | `Outbox.append`, `OutboxRelay` (Lease, Retry-Staffel, Dead-Letter), `InboxProcessor` (Dedupe, Revision, Snapshot), `EventEnvelope`, RabbitMQ-Anbindung (nicht ausgeführt) |

Verträge: `docs/umsetzungsplan/vertraege/{audit-katalog,betriebsalarmkanal,event-topologie}.md`.
