# adapter notification

Push, SMS, E-Mail. Paket `de.taktovia.adapters.notification`. Externe Aufrufe nie innerhalb einer offenen SQL-Transaktion (Leitlinie 3).
Capability-Nachweis und Sandbox-Tests je Operation vor Aktivierung (Kap. 08 §8.4).

`SimulatorProvider` (WP-M2-32): Ersatz des realen Providers (D-05) mit providerseitiger Idempotenz über die Nachrichten-ID, Kontaktprüfung je Kanal und steuerbaren Fehlern (`failNext`, `timeoutNext`, `outage`). Die Kanalschnittstelle ist `DeliveryGateway` des Moduls `communication-support`.
