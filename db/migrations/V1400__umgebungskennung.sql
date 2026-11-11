-- WP-M1-40 / LH-6.18-SEC-012: Umgebungskennung der Datenbank.
-- Genau eine Zeile, die sagt, in welcher Umgebung (development, staging, performance, production) diese Datenbank betrieben wird.
-- Sie wird einmalig von `db.py migrate` gesetzt (Quelle: Umgebungsvariable TAKTOVIA_ENVIRONMENT) und ist danach unveraenderlich:
-- UPDATE, DELETE und TRUNCATE scheitern per Trigger fuer jede Rolle, eine zweite Zeile verhindert der Primaerschluessel.
-- Verwendet von (1) der Startpruefung des Backends (Umgebungskennung der Anwendung = der Datenbank), (2) der Importpruefung fuer Dumps
-- (db/dumpguard.py: Produktionsdump nie in Nicht-Produktion). Die Tabelle liegt im Metaschema, nicht im Fachschema (kein Mandantenbezug).
SET search_path = taktovia_meta, public;

CREATE TABLE environment_marker (
  singleton   boolean     PRIMARY KEY DEFAULT true CONSTRAINT environment_marker_singleton CHECK (singleton),
  environment text        NOT NULL CONSTRAINT environment_marker_value CHECK (environment IN ('development', 'staging', 'performance', 'production')),
  marked_at   timestamptz NOT NULL DEFAULT now()
);

CREATE FUNCTION environment_marker_immutable() RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
  RAISE EXCEPTION 'Umgebungskennung ist unveraenderlich' USING ERRCODE = '23514';
END $$;

CREATE TRIGGER environment_marker_immutable_row BEFORE UPDATE OR DELETE ON environment_marker
  FOR EACH ROW EXECUTE FUNCTION environment_marker_immutable();
CREATE TRIGGER environment_marker_immutable_truncate BEFORE TRUNCATE ON environment_marker
  FOR EACH STATEMENT EXECUTE FUNCTION environment_marker_immutable();

SET search_path = taktovia, public;
