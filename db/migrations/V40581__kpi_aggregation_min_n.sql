-- WP-M4-58 / LH-6.14-ANA-003, LH-I.6-KPI-011: Parameter `kpi_aggregation_min_n` (Katalog J.7, Ebene Mandant) im Parameterkatalog (Version '1', nur INSERT).
-- Gruppen eines Berichts unter dieser Größe werden nicht ausgewiesen, auch nicht durch Differenzbildung benachbarter Filter. Mindestens 2 (eine Gruppe aus 1 ist immer ein Einzelfall), Obergrenze und Standardwert
-- sind Planungsannahme (Wert nicht freigegeben, Offener Punkt 33: vor der Produktivschaltung festzulegen; 3 ist ein Testwert).
SET search_path = taktovia, public;

INSERT INTO policy_schema(schema_version, parameter, json_schema) VALUES
  ('1', 'kpi_aggregation_min_n', '{"$schema": "https://json-schema.org/draft/2020-12/schema", "$id": "https://taktovia.example/schemas/kpi_aggregation_min_n.v1.schema.json", "title": "kpi_aggregation_min_n", "description": "Mindestgruppengröße der Berichte: Gruppen darunter werden unterdrückt (auch gegen Differenzbildung benachbarter Filter)", "type": "integer", "minimum": 2, "maximum": 1000, "default": 3, "x-unit": "COUNT", "x-levels": ["TENANT"], "x-security-class": "STANDARD", "x-limit-source": "PLANUNGSANNAHME", "x-catalog": "LH-J.7-kpi_aggregation_min_n", "x-catalog-range": "Datenschutzanforderungen vorrangig; Wert und Grenzen nicht freigegeben (Offener Punkt 33), Testwert 3"}'::jsonb);
