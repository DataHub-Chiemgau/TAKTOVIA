-- WP-M3-04 / LH-6.8-FLT-004: Kapazitäts- und Stellplatzkonfiguration je Fahrzeug.
-- `vehicle.capacity_config` bleibt der wirksame Ressourcenvektor (resource-vector.v1, von booking-dispatch gelesen); die Konfiguration mit getrennten, unabhängig
-- änderbaren Teilen (Stellplätze, Ausstattung, Kombinationen, Restriktionen) steht in `capacity_layout` (Schema docs/umsetzungsplan/vertraege/schemas/capacity_config.v1.schema.json).
-- capacity_config wird mit der Konfiguration in derselben Transaktion aus der aktiven Kombination abgeleitet (Anwendungsschicht, Fahrzeugzeilensperre).
-- Nullbar: Fahrzeuge ohne Konfiguration behalten ihre Grundkapazität (expand/contract).
SET search_path = taktovia, public;
ALTER TABLE vehicle
  ADD COLUMN capacity_layout jsonb,
  ADD CONSTRAINT vehicle_capacity_layout_object CHECK (capacity_layout IS NULL OR jsonb_typeof(capacity_layout) = 'object'),
  ADD CONSTRAINT vehicle_capacity_layout_version CHECK (capacity_layout IS NULL OR capacity_layout ->> 'schema_version' = '1.0');
