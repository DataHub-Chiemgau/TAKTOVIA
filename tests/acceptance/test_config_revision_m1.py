"""WP-M1-20 / LH-I.7-CFG-003, LH-6.1-SYS-002 im Akzeptanzgerüst (Zwei-Mandanten-Seed, echte Rollen): Eine Konfigurationsänderung ohne Grund oder ohne
Gültigkeitsbeginn wird abgewiesen, eine vollständige hat Ersteller, Zeitstempel und Wirksamkeit und ist für den anderen Mandanten unsichtbar.

Abgrenzung: Der Nachweis läuft gegen die Datenbank unter der Runtime-Rolle (Zeilenebene). Die Anfrageebene (422 mit Feldverletzung über die API) prüft
`backend/app/.../config/ConfigApiTest`; die Rekonstruktion an einem echten Dispositionsfall folgt mit M2 (WP-M2-04, -07, -21, -23). Szenarien E2E-096/-098
und PH-AT-018 bleiben `NOT_IMPLEMENTED`, bis Publikation und Rollback (WP-M1-22) vorliegen."""
import json
import sys
import unittest
import uuid
from pathlib import Path

import psycopg

sys.path.insert(0, str(Path(__file__).resolve().parent))
from taktovia_at import db as atdb, seed  # noqa: E402

DB = "taktovia_at_cfgrev"


def setUpModule():
    atdb.reset_database(DB)
    seed.seed_two_tenants(DB)


def tearDownModule():
    atdb.drop_database(DB)


def insert(c, tenant, scope_id, revision, reason="Wartezeit angepasst", effective_from="2026-12-01T00:00:00Z", created_by="user:planer", values=None):
    rid = uuid.uuid4()
    c.execute("""INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason)
                 VALUES (%s,%s,'REGION',%s,%s,'1',%s::jsonb,%s,%s,%s)""",
              (tenant, rid, scope_id, revision, json.dumps(values or {"max_wait_min": 20}), effective_from, created_by, reason))
    return rid


class KonfigurationsaenderungM1(unittest.TestCase):
    def test_aenderung_ohne_grund_oder_effective_from_abgewiesen(self):
        a = seed.TENANTS["A"]; region = uuid.uuid4()
        for kwargs, exc in ((dict(reason=None), psycopg.errors.NotNullViolation), (dict(reason=""), psycopg.errors.CheckViolation),
                            (dict(effective_from=None), psycopg.errors.NotNullViolation), (dict(created_by=None), psycopg.errors.NotNullViolation)):
            with self.assertRaises(exc, msg=str(kwargs)):
                with atdb.tx(DB, a) as c:
                    insert(c, a, region, 1, **kwargs)
        with atdb.tx(DB, a) as c:
            n = c.execute("SELECT count(*) FROM taktovia.config_revision WHERE scope_id = %s", (region,)).fetchone()[0]
        self.assertEqual(n, 0, "keine abgewiesene Änderung hinterlässt eine Zeile")

    def test_vollstaendige_aenderung_hat_ersteller_zeitstempel_und_wirksamkeit_und_ist_mandantenlokal(self):
        a, b = seed.TENANTS["A"], seed.TENANTS["B"]; region = uuid.uuid4()
        with atdb.tx(DB, a) as c:
            rid = insert(c, a, region, 1)
            row = c.execute("SELECT created_by, created_at, effective_from, effective_to, reason, state FROM taktovia.config_revision WHERE id = %s", (rid,)).fetchone()
        self.assertEqual(row[0], "user:planer"); self.assertIsNotNone(row[1]); self.assertEqual(row[2].isoformat(), "2026-12-01T00:00:00+00:00")
        self.assertIsNone(row[3]); self.assertEqual((row[4], row[5]), ("Wartezeit angepasst", "DRAFT"))
        with atdb.tx(DB, b) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.config_revision WHERE id = %s", (rid,)).fetchone()[0], 0)


if __name__ == "__main__":
    unittest.main()
