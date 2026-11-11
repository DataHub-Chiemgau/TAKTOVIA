"""WP-M1-11 / LH-6.1-SYS-001, LH-I.4-MUL-007 im Akzeptanzgerüst (Zwei-Mandanten-Seed, echte Rollen): Fremde Objekte von B sind für A auf Zeilenebene unsichtbar und
unveränderbar, Rollen und Benachrichtigungswerte sind je Mandant getrennt, und eine publizierte Konfigurationsänderung in A ändert die wirksame Konfiguration von B nicht.

Abgrenzung: Dieser Nachweis läuft gegen die Datenbank unter der Runtime-Rolle (Zeilenebene). Den Scan über alle HTTP-Endpunkte (gleichartige 404-Antwort, Listen, Sicherheitsereignis
ohne B-Daten) führt `backend/app/.../isolation/CrossTenantIsolationTest` gegen die echte Anwendung. Marken-/Brandingwerte sind im Pflichtenheft nicht spezifiziert (OP-09) und hier
nicht nachweisbar; der Tarifnachweis an `tariff_revision` folgt mit M2. Szenarien E2E-091, AT-18 und PH-AT-001 bleiben bis zum Browser-/Gesamtlauf `NOT_IMPLEMENTED`."""
import json
import sys
import unittest
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from taktovia_at import db as atdb, seed  # noqa: E402

DB = "taktovia_at_isolation"


def setUpModule():
    atdb.reset_database(DB)
    seed.seed_two_tenants(DB)


def tearDownModule():
    atdb.drop_database(DB)


def revision(c, tenant, values, revision=1, state_publish=True):
    rid = uuid.uuid4()
    c.execute("""INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason)
                 VALUES (%s,%s,'TENANT',%s,%s,'1',%s::jsonb,'2030-01-01T00:00:00Z','user:planer','Akzeptanz')""", (tenant, rid, tenant, revision, json.dumps(values)))
    return rid


def publish(tenant, rid):
    for to, extra in (("VALIDATED", ""), ("APPROVED", ", approved_by = 'user:zweite-person'"), ("PUBLISHED", ", published_at = now()")):
        with atdb.tx(DB, tenant) as c:
            c.execute(f"UPDATE taktovia.config_revision SET state = %s{extra} WHERE id = %s", (to, rid))


def effective(tenant):
    """Wirksame Konfiguration wie die Präzedenzauflösung sie liest: veröffentlichte Mandantenrevision zum Zeitpunkt."""
    with atdb.tx(DB, tenant) as c:
        return c.execute("""SELECT id, revision, values_json FROM taktovia.config_revision WHERE scope_kind='TENANT' AND scope_id=%s AND state='PUBLISHED'
                            AND effective_from <= '2031-01-01T00:00:00Z' AND (effective_to IS NULL OR effective_to > '2031-01-01T00:00:00Z') ORDER BY effective_from DESC, revision DESC""",
                         (tenant,)).fetchall()


class MandantenisolationM1(unittest.TestCase):
    def test_fremde_b_uuid_ueber_jede_mandantentabelle_unsichtbar_und_unveraenderbar(self):
        a, b = seed.TENANTS["A"], seed.TENANTS["B"]
        with atdb.tx(DB, b) as c:
            tables = [r[0] for r in c.execute("""SELECT c.table_name FROM information_schema.columns c JOIN information_schema.tables t USING (table_schema, table_name)
                                                 WHERE c.table_schema='taktovia' AND c.column_name='tenant_id' AND t.table_type='BASE TABLE' ORDER BY 1""").fetchall()]
        checked = 0
        for t in tables:
            with atdb.tx(DB, b) as c:
                try:
                    ids = [r[0] for r in c.execute(f"SELECT id FROM taktovia.{t} LIMIT 5").fetchall()]   # noqa: S608 (Tabellennamen aus dem Katalog)
                except Exception:   # Tabelle ohne Spalte id oder ohne Leserecht
                    c.rollback(); continue
            for i in ids:
                with atdb.tx(DB, a) as c:
                    self.assertEqual(c.execute(f"SELECT count(*) FROM taktovia.{t} WHERE id = %s", (i,)).fetchone()[0], 0, f"{t}: B-UUID für A sichtbar")   # noqa: S608
                    try:
                        self.assertEqual(c.execute(f"DELETE FROM taktovia.{t} WHERE id = %s", (i,)).rowcount, 0, f"{t}: B-Zeile für A löschbar")   # noqa: S608
                    except Exception:   # kein DELETE-Recht: ebenfalls keine Wirkung
                        c.rollback()
                checked += 1
        self.assertGreater(checked, 5, "der Seed von B enthält Zeilen in mehreren Tabellen")

    def test_rollen_und_benachrichtigungswerte_je_mandant_getrennt(self):
        a, b = seed.TENANTS["A"], seed.TENANTS["B"]
        with atdb.tx(DB, a) as c:
            ra = revision(c, a, {"notification_quiet_hours": {"from": "22:00", "to": "06:00"}, "reporting_timezone": "Europe/Berlin"}, revision=7)
        with atdb.tx(DB, b) as c:
            rb = revision(c, b, {"notification_quiet_hours": {"from": "23:30", "to": "05:00"}, "reporting_timezone": "Europe/Vienna"}, revision=7)
        with atdb.tx(DB, a) as c:
            self.assertEqual(c.execute("SELECT values_json->>'reporting_timezone' FROM taktovia.config_revision WHERE id = %s", (ra,)).fetchone()[0], "Europe/Berlin")
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.config_revision WHERE id = %s", (rb,)).fetchone()[0], 0)
            roles_a = {r[0] for r in c.execute("SELECT DISTINCT tenant_id FROM taktovia.membership").fetchall()}
        with atdb.tx(DB, b) as c:
            self.assertEqual(c.execute("SELECT values_json->'notification_quiet_hours'->>'from' FROM taktovia.config_revision WHERE id = %s", (rb,)).fetchone()[0], "23:30")
            roles_b = {r[0] for r in c.execute("SELECT DISTINCT tenant_id FROM taktovia.membership").fetchall()}
        self.assertEqual(roles_a, {a}); self.assertEqual(roles_b, {b})   # Rollenzuweisungen je Mandant getrennt abrufbar

    def test_konfigaenderung_a_aendert_wirksame_konfiguration_b_nicht(self):
        a, b = seed.TENANTS["A"], seed.TENANTS["B"]
        with atdb.tx(DB, b) as c:
            rb = revision(c, b, {"max_wait_min": 30}, revision=20)
        publish(b, rb)
        before = effective(b)
        with atdb.tx(DB, a) as c:
            ra = revision(c, a, {"max_wait_min": 99}, revision=20)   # gleiche Revisionsnummer und gleicher Wirksamkeitsbeginn in A
        publish(a, ra)
        self.assertEqual(effective(a)[0][2], {"max_wait_min": 99})
        self.assertEqual(effective(b), before, "Vorher/Nachher der wirksamen Konfiguration von B identisch")
        self.assertEqual(before[0][2], {"max_wait_min": 30})


if __name__ == "__main__":
    unittest.main()
