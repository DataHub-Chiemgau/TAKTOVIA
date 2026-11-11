# platform

Querschnitt für alle Module: Tenantkontext, RLS-Anbindung, Outbox/Inbox, Idempotenz, Audit, Geld- und Zeittypen, Cache/Rate-Limit.
Pakete (Platzhalter bis M1): `tenant`, `rls`, `outbox`, `idempotency`, `audit`, `money`, `time`, `cache`.

- Darf von allen Modulen und Adaptern verwendet werden, hängt selbst von keinem Modul, Adapter oder von `app` ab.
- Redis-Clients ausschließlich in `cache` (Leitlinie 4).
- Tabellenbesitz: `currency`, `idempotency_record`, `outbox`, `inbox`, `audit_event` (siehe `db/table_owners.csv`).
