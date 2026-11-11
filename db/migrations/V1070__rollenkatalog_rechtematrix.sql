-- WP-M1-07 / LH-6.2-IAM-004: Rollenkatalog, Prüfung von membership.role_code, Protokoll des Ausrollens der Rechtematrix.
SET search_path = taktovia, public;

-- Globaler Katalog (kein tenant_id, nur per Migration gepflegt, Runtime nur lesend). Die sieben Pflichtrollen aus IAM-004 und Platzhalter
-- für weitere Fachrollen aus Kap. 06 (Finanzen, Personalplanung, Werkstatt/Freigabe; Bedeutung für M3/M4, OP-10). Die Werteliste ist fachlich
-- nicht freigegeben (OP-10). Kotlin (`RoleCatalog`), Rechtematrix und diese Tabelle werden per Test auf Gleichheit geprüft.
CREATE TABLE role_catalog (
  role_code   text PRIMARY KEY CHECK (role_code ~ '^[a-z][a-z0-9_]{1,63}$'),
  label_de    text NOT NULL,
  placeholder boolean NOT NULL DEFAULT false
);
INSERT INTO role_catalog (role_code, label_de, placeholder) VALUES
  ('passenger',     'Fahrgast',                 false),
  ('driver',        'Fahrer',                   false),
  ('callcenter',    'Callcenter',               false),
  ('support',       'Support',                  false),
  ('dispatcher',    'Disponent',                false),
  ('planner',       'Verkehrsplaner',           false),
  ('tenant_admin',  'Administrator',            false),
  ('finance',       'Finanzen',                 true),
  ('staff_planning','Personalplanung',          true),
  ('workshop',      'Werkstatt/Freigabe',       true);

-- Referenz als CHECK über die Werteliste (ein Fremdschlüssel auf die globale Tabelle widerspräche der Konvention „Fremdschlüssel beginnen mit
-- tenant_id“, Kap. 04 §4.2). Neue Rollen entstehen durch Vorwärtsmigration von Katalog und CHECK zugleich (Test hält beide synchron).
ALTER TABLE membership ADD CONSTRAINT membership_role_code_catalog CHECK (role_code IN (
  'passenger','driver','callcenter','support','dispatcher','planner','tenant_admin','finance','staff_planning','workshop'));

-- Ausrollen der Rechtematrix (IAM-004 AK 4): Die Anwendung schreibt beim Start genau dann eine Zeile, wenn die mitgelieferte Matrix
-- (Version + SHA-256 der Matrixdatei) von der zuletzt protokollierten abweicht. Nur anhängen, nie ändern (Trigger, auch für die Administration).
CREATE TABLE rights_matrix_rollout (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  matrix_version  integer     NOT NULL CHECK (matrix_version >= 1),
  matrix_sha256   char(64)    NOT NULL CHECK (matrix_sha256 ~ '^[0-9a-f]{64}$'),
  previous_sha256 char(64),
  role_count      integer     NOT NULL CHECK (role_count >= 1),
  operation_count integer     NOT NULL CHECK (operation_count >= 1),
  rolled_out_at   timestamptz NOT NULL DEFAULT now(),
  rolled_out_by   text        NOT NULL CHECK (rolled_out_by ~ '^(service|system):[A-Za-z0-9._:-]{1,120}$')
);
CREATE INDEX rights_matrix_rollout_by_time ON rights_matrix_rollout (rolled_out_at DESC, id DESC);
CREATE TRIGGER rights_matrix_rollout_immutable BEFORE UPDATE OR DELETE ON rights_matrix_rollout FOR EACH ROW EXECUTE FUNCTION reject_mutation();
