"""WP-M1-27 / LH-6.1-SYS-005: gebundene Zusage-Snapshots sind in der Datenbank unveränderlich (Trigger analog `quote_immutable`);
eine Änderung ist eine neue Revision (Invariante Kap. 04 §4.5 „Gegenereignis statt Änderung“), die alte bleibt lesbar."""
import json
import unittest

import psycopg
from helpers import admin, runtime, seed_tenant, set_tenant, tx, uid

A, B = uid(), uid()
HASH = 'a' * 64
CONTENT = json.dumps({'gross_minor': 1000})


def config(c, tenant):
    cid = uid()
    c.execute("""INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by)
                 VALUES (%s,%s,'tenant',%s,1,'1.0','{}'::jsonb,now(),'user:x')""", (tenant, cid, uid()))
    return cid


def promise(c, tenant, cfg, rev=1):
    pid = uid()
    c.execute("INSERT INTO taktovia.promise_test_aggregate(tenant_id,id,revision) VALUES (%s,%s,%s)", (tenant, pid, rev))
    snap(c, tenant, pid, 1, cfg)
    return pid


def snap(c, tenant, pid, rev, cfg, content=CONTENT, h=HASH, version='1.0'):
    c.execute("""INSERT INTO taktovia.promise_test_snapshot(tenant_id,promise_id,revision,schema_version,content,content_hash,config_revision_id)
                 VALUES (%s,%s,%s,%s,%s::jsonb,%s,%s)""", (tenant, pid, rev, version, content, h, cfg))


class ZusageSnapshot(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'zusage-a'); seed_tenant(B, 'zusage-b')

    def test_snapshot_update_und_delete_werden_abgewiesen(self):
        with tx(A) as c:
            cfg = config(c, A); pid = promise(c, A, cfg)
        # Runtime-Rolle: kein UPDATE/DELETE-Recht
        for sql in ("UPDATE taktovia.promise_test_snapshot SET content='{}'::jsonb WHERE promise_id=%s", "DELETE FROM taktovia.promise_test_snapshot WHERE promise_id=%s"):
            with self.assertRaises(psycopg.errors.InsufficientPrivilege, msg=sql):
                with tx(A) as c:
                    c.execute(sql, (pid,))
        # Administrator: Trigger greift auch dort (wie quote_immutable)
        with admin() as a:
            for sql in ("UPDATE taktovia.promise_test_snapshot SET content='{}'::jsonb WHERE promise_id=%s", "DELETE FROM taktovia.promise_test_snapshot WHERE promise_id=%s"):
                with self.assertRaises(psycopg.errors.CheckViolation, msg=sql):
                    a.execute(sql, (pid,))
            self.assertEqual(a.execute("SELECT content FROM taktovia.promise_test_snapshot WHERE promise_id=%s", (pid,)).fetchone()[0], {'gross_minor': 1000})

    def test_neue_revision_laesst_die_alte_lesbar_und_unveraendert(self):
        with tx(A) as c:
            cfg = config(c, A); pid = promise(c, A, cfg)
        with tx(A) as c:
            c.execute("SELECT revision FROM taktovia.promise_test_aggregate WHERE id=%s FOR UPDATE", (pid,))
            snap(c, A, pid, 2, cfg, content=json.dumps({'gross_minor': 1200}), h='b' * 64)
            c.execute("UPDATE taktovia.promise_test_aggregate SET revision=2 WHERE tenant_id=%s AND id=%s", (A, pid))
        with tx(A) as c:
            rows = c.execute("SELECT revision, content->>'gross_minor' FROM taktovia.promise_test_snapshot WHERE promise_id=%s ORDER BY revision", (pid,)).fetchall()
        self.assertEqual(rows, [(1, '1000'), (2, '1200')])

    def test_kopf_darf_nur_um_eins_wachsen_und_nicht_geloescht_werden(self):
        with tx(A) as c:
            cfg = config(c, A); pid = promise(c, A, cfg)
        for sql in ("UPDATE taktovia.promise_test_aggregate SET revision=3 WHERE id=%s",    # Revision überspringen
                    "UPDATE taktovia.promise_test_aggregate SET revision=1 WHERE id=%s",    # Rücksprung/gleich
                    "UPDATE taktovia.promise_test_aggregate SET created_at=now() - interval '1 day' WHERE id=%s"):
            with self.assertRaises(psycopg.errors.Error, msg=sql):
                with tx(A) as c:
                    c.execute(sql, (pid,))
        with admin() as a:
            with self.assertRaises(psycopg.errors.CheckViolation):
                a.execute("DELETE FROM taktovia.promise_test_aggregate WHERE id=%s", (pid,))

    def test_kopf_zeigt_immer_auf_vorhandenen_snapshot(self):
        """Revision des Kopfes ohne zugehörigen Snapshot scheitert beim Commit (aufgeschobener Fremdschlüssel)."""
        with tx(A) as c:
            cfg = config(c, A); pid = promise(c, A, cfg)
        with self.assertRaises(psycopg.errors.ForeignKeyViolation):
            with tx(A) as c:
                c.execute("UPDATE taktovia.promise_test_aggregate SET revision=2 WHERE tenant_id=%s AND id=%s", (A, pid))   # ohne Snapshot 2
        with self.assertRaises(psycopg.errors.ForeignKeyViolation):
            with tx(A) as c:
                c.execute("INSERT INTO taktovia.promise_test_aggregate(tenant_id,id,revision) VALUES (%s,%s,1)", (A, uid()))

    def test_formatpruefungen_hash_version_inhalt(self):
        with tx(A) as c:
            cfg = config(c, A)
        for kw in ({'h': 'XYZ'}, {'h': 'A' * 64}, {'h': 'a' * 63}, {'version': 'v1'}, {'version': ''}, {'content': '[]'}, {'content': '"x"'}):
            with self.assertRaises(psycopg.errors.CheckViolation, msg=str(kw)):
                with tx(A) as c:
                    pid = uid()
                    c.execute("INSERT INTO taktovia.promise_test_aggregate(tenant_id,id,revision) VALUES (%s,%s,1)", (A, pid))
                    snap(c, A, pid, 1, cfg, **kw)

    def test_konfigurationsaenderung_veraendert_bestehende_snapshots_nicht(self):
        with tx(A) as c:
            cfg = config(c, A); pid = promise(c, A, cfg)
        with tx(A) as c:   # neue Konfigurationsrevision, auch die alte wird geändert (Gültigkeitsende)
            c.execute("""INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by)
                         VALUES (%s,%s,'tenant',%s,2,'1.0','{"x":1}'::jsonb,now(),'user:x')""", (A, uid(), uid()))
            c.execute("UPDATE taktovia.config_revision SET effective_to = now() + interval '1 day' WHERE tenant_id=%s AND id=%s", (A, cfg))
        with tx(A) as c:
            row = c.execute("SELECT content, content_hash, config_revision_id, schema_version FROM taktovia.promise_test_snapshot WHERE promise_id=%s", (pid,)).fetchone()
        self.assertEqual(row, ({'gross_minor': 1000}, HASH, cfg, '1.0'))

    def test_mandantentrennung_und_fremdschluessel_auf_konfiguration(self):
        with tx(A) as c:
            cfgA = config(c, A); pid = promise(c, A, cfgA)
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.promise_test_snapshot WHERE promise_id=%s", (pid,)).fetchone()[0], 0)
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.promise_test_aggregate WHERE id=%s", (pid,)).fetchone()[0], 0)
        with self.assertRaises(psycopg.errors.ForeignKeyViolation):   # Konfigurationsrevision eines anderen Mandanten
            with tx(B) as c:
                pid_b = uid()
                c.execute("INSERT INTO taktovia.promise_test_aggregate(tenant_id,id,revision) VALUES (%s,%s,1)", (B, pid_b))
                snap(c, B, pid_b, 1, cfgA)


if __name__ == '__main__':
    unittest.main()
