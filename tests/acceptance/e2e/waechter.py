"""Wächter „keine manuelle DB-Korrektur“ (WP-M5-23, LH-6.20-QAT-003): Datenbank-Auditvergleich der Schreibzugriffe zwischen Start und Ende eines Szenarios.

Verfahren: vor dem Szenario legt der Wächter (Administratorzugang, nur im Testbetrieb) ein Protokollschema `e2e_waechter` und je Tabelle des Fachschemas einen Anweisungs-Trigger an, der
jede schreibende Anweisung (INSERT, UPDATE, DELETE, TRUNCATE) mit `session_user` und `application_name` festhält. `ende()` wertet das Protokoll aus: Schreibzugriffe der Runtime-Rolle
(`taktovia_runtime`, die Anwendung) sind erlaubt; Schreibzugriffe des Testaufbaus (Administratorverbindung mit `application_name = 'e2e-testaufbau'`: Planbindung, Fahrzeugfreigabe, Umlauf,
Planstopps, Fahrgastprofil als Ersatz für nicht verdrahtete Funktionen) werden getrennt AUSGEWIESEN, nicht verschwiegen; jeder andere Schreibzugriff ist ein Befund (manuelle Korrektur).
Das Protokoll liegt außerhalb des Fachschemas und wird in `entfernen()` samt Triggern gelöscht. Grenze: Der Wächter sieht Anweisungen auf Tabellen des Schemas `taktovia`; Änderungen am
Schema selbst (DDL) oder in anderen Schemas sieht er nicht."""
import contextlib

APP_AUFBAU = "e2e-testaufbau"
RUNTIME = "taktovia_runtime"


class Waechter:
    def __init__(self, welt):
        self.welt = welt
        self.installiert = False
        self.start_id = 0

    def installieren(self):
        with self.welt.admin() as c:
            c.execute("CREATE SCHEMA IF NOT EXISTS e2e_waechter")
            c.execute("""CREATE TABLE IF NOT EXISTS e2e_waechter.schreibzugriff (
                id bigserial PRIMARY KEY, at timestamptz NOT NULL DEFAULT clock_timestamp(), session_user_name text NOT NULL, application_name text, tabelle text NOT NULL, operation text NOT NULL)""")
            c.execute("""CREATE OR REPLACE FUNCTION e2e_waechter.protokoll() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = e2e_waechter, pg_catalog AS $$
                BEGIN INSERT INTO e2e_waechter.schreibzugriff(session_user_name, application_name, tabelle, operation) VALUES (session_user, current_setting('application_name', true), TG_TABLE_NAME, TG_OP); RETURN NULL; END $$""")
            c.execute("GRANT USAGE ON SCHEMA e2e_waechter TO PUBLIC")
            tabellen = [r[0] for r in c.execute("SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'taktovia' AND c.relkind IN ('r','p') AND NOT c.relispartition").fetchall()]
            for t in tabellen:
                c.execute(f'DROP TRIGGER IF EXISTS e2e_waechter ON taktovia."{t}"')   # sql-lint: ok Tabellenname aus pg_class, nur Testbetrieb
                c.execute(f'CREATE TRIGGER e2e_waechter AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON taktovia."{t}" FOR EACH STATEMENT EXECUTE FUNCTION e2e_waechter.protokoll()')   # sql-lint: ok Tabellenname aus pg_class, nur Testbetrieb
        self.installiert = True
        self.tabellen = len(tabellen)

    def start(self):
        with self.welt.admin() as c:
            self.start_id = c.execute("SELECT coalesce(max(id), 0) FROM e2e_waechter.schreibzugriff").fetchone()[0]
        return self.start_id

    def ende(self, ab=None):
        """Auswertung der Schreibzugriffe seit `start()`: Runtime (erlaubt), Testaufbau (ausgewiesen), Befunde (alle übrigen)."""
        ab = self.start_id if ab is None else ab
        with self.welt.admin() as c:
            rows = c.execute("SELECT session_user_name, coalesce(application_name,''), tabelle, operation, count(*) FROM e2e_waechter.schreibzugriff WHERE id > %s GROUP BY 1,2,3,4 ORDER BY 1,2,3,4", (ab,)).fetchall()
        rep = {"runtime": [], "testaufbau": [], "befunde": []}
        for user, app, tab, op, n in rows:
            eintrag = {"tabelle": tab, "operation": op, "anweisungen": n, "rolle": user}
            if user == RUNTIME:
                rep["runtime"].append(eintrag)
            elif app == APP_AUFBAU:
                rep["testaufbau"].append(eintrag)
            else:
                rep["befunde"].append(eintrag | {"application_name": app})
        rep["ok"] = not rep["befunde"]
        return rep

    def entfernen(self):
        if not self.installiert:
            return
        with contextlib.suppress(Exception), self.welt.admin() as c:
            for (t,) in c.execute("SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'taktovia' AND c.relkind IN ('r','p') AND NOT c.relispartition").fetchall():
                c.execute(f'DROP TRIGGER IF EXISTS e2e_waechter ON taktovia."{t}"')   # sql-lint: ok Tabellenname aus pg_class, nur Testbetrieb
            c.execute("DROP SCHEMA IF EXISTS e2e_waechter CASCADE")
        self.installiert = False
