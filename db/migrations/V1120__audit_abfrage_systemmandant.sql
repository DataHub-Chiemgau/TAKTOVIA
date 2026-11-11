-- WP-M1-12: Abfrageindizes für Akteur und Aktion; reservierter Systemmandant für Ereignisse ohne Mandantenkontext (OP-14, Vorschlag).
-- Append-only bleibt unverändert (V1: Trigger audit_immutable; Runtime nur SELECT/INSERT). Der Index (tenant_id, occurred_at) besteht aus V1460.
SET search_path = taktovia, public;
CREATE INDEX audit_event_by_actor  ON audit_event (tenant_id, actor_ref, occurred_at DESC);
CREATE INDEX audit_event_by_action ON audit_event (tenant_id, action, occurred_at DESC);

-- Der reservierte Systemmandant (Kennung 00000000-0000-0000-0000-000000000001, Status SUSPENDED) für Ereignisse ohne Mandantenkontext wird
-- bewusst NICHT per Migration angelegt (Mandanten legt der Plattformbetrieb an; Seed-, Dump- und Zählprüfungen erwarten leere Mandantentabellen)
-- und die Runtime-Rolle darf keine Mandanten anlegen (kein SECURITY DEFINER im Schema): `python db/db.py provision-system-tenant`.
