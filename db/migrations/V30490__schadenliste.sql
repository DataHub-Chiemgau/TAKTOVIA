-- WP-M3-49 / LH-6.22-DMG-009: Schadenliste der Leitstelle mit Filtern nach Fahrzeug, Priorität, Status, Zeitraum, Depot und Verantwortlichem.
-- 1. Depot (OP-22, Arbeitsannahme dieses Plans): das Fahrzeug trägt optional seinen Betriebshof (`vehicle.depot_id`, Fremdschlüssel auf `depot` desselben Mandanten); das Pflichtenheft
--    legt die Zuordnung nicht fest, die fachliche Bestätigung steht aus. Altfahrzeuge ohne Depot bleiben gültig (nullbar, expand/contract).
-- 2. Listenindizes auf `defect_case` für die sechs Filter und die stabile Sortierung `(Sortierwert, id)`; der Index `(tenant_id, status, updated_at)` besteht seit V3020 (`defect_case_work`).
SET search_path = taktovia, public;

ALTER TABLE vehicle ADD COLUMN depot_id uuid;
ALTER TABLE vehicle ADD CONSTRAINT vehicle_depot_fk FOREIGN KEY (tenant_id, depot_id) REFERENCES depot(tenant_id, id);
CREATE INDEX vehicle_by_depot ON vehicle (tenant_id, depot_id) WHERE depot_id IS NOT NULL;

CREATE INDEX defect_case_list_reported  ON defect_case (tenant_id, reported_at, id);
CREATE INDEX defect_case_list_severity  ON defect_case (tenant_id, severity, reported_at, id);
CREATE INDEX defect_case_list_assignee  ON defect_case (tenant_id, assignee_ref, reported_at, id) WHERE assignee_ref IS NOT NULL;
CREATE INDEX defect_case_list_number    ON defect_case (tenant_id, case_number, id);
CREATE INDEX defect_status_history_asof ON defect_status_history (tenant_id, defect_id, created_at DESC, revision DESC);
CREATE INDEX defect_classification_history_asof ON defect_classification_history (tenant_id, defect_id, created_at DESC);
