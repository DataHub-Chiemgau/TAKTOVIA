-- WP-M3-37 / LH-6.7-OPS-002: Auftragsliste mit Suche (Buchungsnummer, Zeitraum, Fahrgast, Fahrzeug, Status).
-- Nur Indizes (kein Datenmodell): jeder beginnt mit tenant_id, damit auch die Planung der Suche die Mandantengrenze trägt.
--   * booking_search_by_state: Statusfilter je Dienst in Sortierreihenfolge der Liste (created_at, id), damit der Cursor ohne Sortierung läuft
--   * capacity_hold_by_run:  Fahrzeugsuche (Buchung -> Hold -> Umlauf -> Fahrzeug); der Fremdschlüssel (tenant_id, run_id) hatte bisher keinen Index
-- Die Buchungsnummer ist bereits eindeutig indiziert (booking_no_unique, tenant_id, booking_no), der Fahrgast über booking_by_passenger, der Dienst über booking_by_service.
SET search_path = taktovia, public;

CREATE INDEX booking_search_by_state ON booking (tenant_id, service_id, state, created_at, id) WHERE booking_no IS NOT NULL;
CREATE INDEX capacity_hold_by_run ON capacity_hold (tenant_id, run_id);
