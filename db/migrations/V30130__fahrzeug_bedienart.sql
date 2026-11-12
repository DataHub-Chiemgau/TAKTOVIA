-- WP-M3-13 (LH-6.21-CHK-002) unter Planungsannahme PA-08 (M3 OP-14): die Auswahl der Prüfvorlage braucht Fahrzeugklasse und Bedienart.
-- Fahrzeugklasse = vehicle.vehicle_type (V3020, Katalog PKW, KLEINBUS, BUS, RAMPE je Annahme), Bedienart = neue Spalte vehicle.operation_kind
-- (Katalog STANDARD, ASSISTENZ je Annahme; Vorgabe STANDARD). Eine Vorlagenrevision wählt über das Paar (vehicle_class, service_mode) = (vehicle_type, operation_kind).
-- Das Format ist wie bei den Vorlagen offen (Großbuchstaben, Ziffern, Unterstrich), weil der Katalog eine versionierte Konfiguration ist und keine feste Aufzählung.
SET search_path = taktovia, public;

ALTER TABLE vehicle
  ADD COLUMN operation_kind text NOT NULL DEFAULT 'STANDARD',
  ADD CONSTRAINT vehicle_operation_kind_format CHECK (operation_kind ~ '^[A-Z][A-Z0-9_]{1,39}$');

-- Vorlagenwahl: genau eine freigegebene Revision je (Mandant, Fahrzeugklasse, Bedienart); der Index beschleunigt die Suche und beweist nichts (Mehrdeutigkeit prüft die Anwendung).
CREATE INDEX checklist_published_by_pair ON checklist_template_revision (tenant_id, vehicle_class, service_mode) WHERE state = 'PUBLISHED';
