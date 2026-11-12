-- WP-M4-17 / LH-6.23-SCH-007: Verknüpfung Schicht <-> Fahrzeug <-> geplanter Betrieb (Dienst). `staff_shift.service_id` ist der Dienstbezug (OP-10: Eigentum bleibt bei staff-time;
-- `resource_release` bleibt im Modul fleet-safety und verweist unverändert auf `shift_id`). Nullbar: Altzeilen (M2/M3-Fixtures) und Schichten ohne Dienstzuordnung bleiben gültig (expand/contract).
-- Der Index bedient die Wirkungsanalyse (veröffentlichte Schichten je Dienst) und den Leseport „Schichtbesetzung“ (Fahrzeug und Zeitraum).
SET search_path = taktovia, public;

ALTER TABLE staff_shift
  ADD COLUMN service_id uuid,
  ADD CONSTRAINT staff_shift_service_fk FOREIGN KEY (tenant_id, service_id) REFERENCES service (tenant_id, id);

CREATE INDEX staff_shift_by_service ON staff_shift (tenant_id, service_id, starts_at) WHERE service_id IS NOT NULL;
CREATE INDEX staff_shift_staffing ON staff_shift (tenant_id, vehicle_id, starts_at, ends_at) WHERE vehicle_id IS NOT NULL;
