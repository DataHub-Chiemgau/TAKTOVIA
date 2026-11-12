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

## Feldschutz und Zahlungsdatentrennung (WP-M1-31, WP-M1-37)

| Paket | Inhalt |
|---|---|
| `fieldprotection` | `FieldRights` (Rollen×Feld-Matrix `fieldprotection/feldrechte.json`, fail-closed, Feldscope + Rolle + Zweck), `EnvelopeCipher` (AES-256-GCM-Envelope, KEK aus der Secret-Verwaltung, AAD an Mandant/Spalte/Zeile), `ProtectedColumnStore`, `FieldAccess` (einziger Lesepfad: Audit mit Akteur und Zweck in der Fachtransaktion, Lesen über den Modulzugang `taktovia_fields`), `SupportNeeds` (Katalog, keine Diagnosen), `FieldAccessRoleGuard`. Vertrag: `docs/umsetzungsplan/vertraege/feldschutz.md` |
| `carddata` | `CardData` (Luhn + Netzwerkpräfix, Maskierung), `CardDataFilter` (Query, Header, Body; `requireNoCardData` für Ereignis-Payloads, aufgerufen von `Outbox.append`), `CardDataIncidents` (asynchrone Meldung als Log und Sicherheitsereignis). Nachweis: `docs/umsetzungsplan/vertraege/psp-trennung.md` |

- **Isolation außerhalb der Datenbank (WP-M1-11):** `cache.CacheKey`/`TenantCache` (Mandant, Dienst, Ressourcen-ID, Policyrevision im Schlüssel; In-Memory und Redis), `storage.ObjectKey`/`TenantObjectStore`/`SignedUrls` (Mandantenpräfix, signierte URLs höchstens 60 s nach Objekt-/Zweckprüfung; S3/MinIO-Adapter nicht gebaut), `outbox.EnvelopeAddressing` (Eigentümer und Empfänger Pflicht), `isolation.TenantListing` (erzwungener Tenantfilter). Architekturregeln: `IsolationRules` in `backend/app` (Tests). Vertrag: `docs/umsetzungsplan/vertraege/LH-6.1-SYS-001.md`.

## Telemetrie und Maskierung (WP-M5-03, WP-M5-06)

| Paket | Inhalt |
|---|---|
| `logging` | `Maskierung` (zentrale Regeln für Logausgabe, Trace-Attribute, `problem+json`-`detail`, Alarmtexte, Ausnahmebeschreibung), `Positivliste` (protokollierbare Felder je Modul), `LogZeile`/`LogKontext` (strukturierte maskierte Zeile mit Mandanten- und Korrelationsbezug). Regeln: `docs/betrieb/maskierung.md` |
| `telemetry` | `MetricConvention`/`LabelPolicyFilter` (Namens- und Labelkonvention, verwirft Reihen mit Personen-, Kontakt-, Such- oder Zahlungsbezug), `PlatformMetrics` (HTTP-Latenz je Endpunktklasse, Sperrwartezeit, Relay-Verzögerung), `QueueMetrics` (Outbox, Inbox, Operationen, `payment_intent` je Mandant), `SafeTelemetry` (Telemetrie stört nie das Geschäft). Konvention und Betrieb: `docs/betrieb/telemetrie.md` |
