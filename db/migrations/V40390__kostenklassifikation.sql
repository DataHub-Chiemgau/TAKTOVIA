-- WP-M4-39 (LH-I.2-FCO-001): Kostenklassifikation fix/variabel und direkt/umgelegt am Kostenereignis (Modul documents-finance).
-- economic_event bringt cost_behavior (FIXED/VARIABLE/MIXED) und attribution (DIRECT/ALLOCATED) aus dem Kernschema mit; das Ereignis ist unveränderlich (Trigger economic_immutable), die Klassifikation
-- wird daher beim Posting gesetzt und danach nur über Gegenereignis plus Neubuchung geändert. Hier: Kostenartenkatalog `cost_category` (Kostenart mit Standardverhalten, je Mandant), beide Achsen
-- gemeinsam oder gar nicht, Standardindex, Kostenobjektbezug direkt gebuchter Ereignisse und der Ursprungsbezug der Neubuchung (höchstens eine Neubuchung je Ursprung).
SET search_path = taktovia, public;

-- Kostenart mit Standardverhalten (Testkatalog bis zum Beschluss D-07; blockiert nur die Produktivschaltung). `cost_group` ordnet eine Kostenart einer der sechs Fahrzeugkostengruppen (FCO-002) zu.
CREATE TABLE cost_category (
  tenant_id           uuid        NOT NULL REFERENCES tenant(id),
  category            text        NOT NULL CONSTRAINT cost_category_key CHECK (category ~ '^[A-Z][A-Z0-9_]{0,39}$'),
  label               text        NOT NULL CHECK (length(label) BETWEEN 1 AND 200),
  cost_group          text        CONSTRAINT cost_category_group CHECK (cost_group IN ('ACQUISITION_LEASE', 'INSURANCE', 'VEHICLE_TAX', 'ENERGY', 'MAINTENANCE', 'REPAIR')),
  default_behavior    text        CONSTRAINT cost_category_behavior CHECK (default_behavior IN ('FIXED', 'VARIABLE', 'MIXED')),
  default_attribution text        CONSTRAINT cost_category_attribution CHECK (default_attribution IN ('DIRECT', 'ALLOCATED')),
  created_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, category),
  CONSTRAINT cost_category_default_pair CHECK ((default_behavior IS NULL) = (default_attribution IS NULL))
);
ALTER TABLE cost_category ENABLE ROW LEVEL SECURITY;
ALTER TABLE cost_category FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cost_category USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON cost_category FOR EACH ROW EXECUTE FUNCTION taktovia.reject_tenant_change();

-- Seed der Standardkostenarten des aktuellen Mandanten (transaktionslokaler Kontext, RLS): die sechs Fahrzeugkostengruppen und FUEL (Tankbeleg, Gruppe Energie). Wiederholbar; ein Mandant erhält den Katalog beim
-- ersten Zugriff der Anwendung. Die Standardverhalten sind Testannahmen (D-07): Anschaffung/Leasing, Versicherung und Kfz-Steuer fix, Energie, Wartung und Reparatur variabel, alle direkt am Fahrzeug.
CREATE FUNCTION seed_cost_categories() RETURNS integer LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE n integer;
BEGIN
  INSERT INTO cost_category (tenant_id, category, label, cost_group, default_behavior, default_attribution)
  SELECT taktovia.current_tenant(), v.category, v.label, v.cost_group, v.behavior, v.attribution FROM (VALUES
    ('ACQUISITION_LEASE', 'Anschaffung/Leasing', 'ACQUISITION_LEASE', 'FIXED', 'DIRECT'),
    ('INSURANCE', 'Versicherung', 'INSURANCE', 'FIXED', 'DIRECT'),
    ('VEHICLE_TAX', 'Kfz-Steuer', 'VEHICLE_TAX', 'FIXED', 'DIRECT'),
    ('ENERGY', 'Energie', 'ENERGY', 'VARIABLE', 'DIRECT'),
    ('MAINTENANCE', 'Wartung', 'MAINTENANCE', 'VARIABLE', 'DIRECT'),
    ('REPAIR', 'Reparatur', 'REPAIR', 'VARIABLE', 'DIRECT'),
    ('FUEL', 'Kraftstoff (Tankbeleg)', 'ENERGY', 'VARIABLE', 'DIRECT')
  ) AS v(category, label, cost_group, behavior, attribution)
  WHERE taktovia.current_tenant() IS NOT NULL
  ON CONFLICT (tenant_id, category) DO NOTHING;
  GET DIAGNOSTICS n = ROW_COUNT;
  RETURN n;
END $$;

-- Beide Achsen der Klassifikation gemeinsam oder gar nicht: ein Ereignis ist klassifiziert oder „nicht klassifiziert“ (nie halb). Bestehende Ereignisse tragen beide Spalten leer.
ALTER TABLE economic_event ADD CONSTRAINT economic_event_classification_pair CHECK ((cost_behavior IS NULL) = (attribution IS NULL));

-- Standardindex der Auswertungen (Kap. 04 §4.4).
CREATE INDEX economic_event_by_booked ON economic_event (tenant_id, legal_entity_id, booked_at);

-- Kostenobjekt direkt gebuchter Ereignisse (z. B. manuelles Fahrzeugkostenereignis, Neubuchung); bei Belegen folgt das Kostenobjekt der Kostenzuordnung bzw. der Umlage. NULL = kein Direktbezug.
ALTER TABLE economic_event
  ADD COLUMN cost_object_id        uuid,
  ADD COLUMN reclassified_from_id  uuid,
  ADD CONSTRAINT economic_event_cost_object_fk FOREIGN KEY (tenant_id, cost_object_id) REFERENCES cost_object (tenant_id, id),
  ADD CONSTRAINT economic_event_reclassified_fk FOREIGN KEY (tenant_id, reclassified_from_id) REFERENCES economic_event (tenant_id, id),
  ADD CONSTRAINT economic_event_reclassified_not_reversal CHECK (reclassified_from_id IS NULL OR reverses_id IS NULL);
-- Höchstens eine Neubuchung je Ursprung (zwei parallele Umklassifikationen desselben Ereignisses: genau eine wirksame Neubuchung).
CREATE UNIQUE INDEX economic_event_one_reclassification ON economic_event (tenant_id, reclassified_from_id) WHERE reclassified_from_id IS NOT NULL;
CREATE INDEX economic_event_by_cost_object ON economic_event (tenant_id, cost_object_id) WHERE cost_object_id IS NOT NULL;
