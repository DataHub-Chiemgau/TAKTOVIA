"""WP-M3-03 / LH-6.8-FLT-001: Fahrzeugstamm in der Datenbank unter der echten Runtime-Rolle. Originalabnahme: ein Fahrzeug mit ungültiger Kapazität wird nicht
speicherbar, auch nicht per SQL (CHECK); die Kennung ist je Mandant eindeutig; die Mandantentrennung gilt auch für den Stamm."""
import json
import threading
import unittest

import psycopg

from helpers import admin, tx, uid, seed_tenant

A, B = uid(), uid()
ACTOR = 'user:11111111-1111-4111-8111-111111111111'


def insert(c, tenant, cfg, label=None, vid=None, vtype='PKW', **extra):
    vid = vid or uid()
    c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,vehicle_label,vehicle_type,created_by) VALUES (%s,%s,'org',%s::jsonb,%s,%s,%s)",
              (tenant, vid, json.dumps(cfg) if not isinstance(cfg, str) else cfg, label, vtype, ACTOR))
    return vid


class Fahrzeugstamm(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm303-a'); seed_tenant(B, 'm303-b')

    def test_capacity_check_und_eindeutige_kennung(self):
        # gültige Kapazität ist speicherbar (auch Altbestand ohne Dimension)
        with tx(A) as c:
            insert(c, A, {'schema_version': '1.0', 'seats': 4, 'wheelchair': 1, 'equipment': []}, 'OK-1')
            insert(c, A, {}, 'OK-2')
        # ungültige Kapazität direkt per SQL: CHECK-Verletzung (SQLSTATE 23514), nichts gespeichert
        bad = [{'seats': -1, 'wheelchair': 0}, {'seats': 4, 'wheelchair': -2}, {'seats': 2.5, 'wheelchair': 0}, {'seats': '4', 'wheelchair': 0}, {'seats': None, 'wheelchair': 0},
               {'seats': 4, 'wheelchair': True}, {'seats': 1e12, 'wheelchair': 0}]
        for i, cfg in enumerate(bad):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.CheckViolation, msg=str(cfg)) as cm:
                    insert(c, A, cfg, f'BAD-{i}')
                self.assertEqual(cm.exception.sqlstate, '23514')
        for not_object in ('[]', '"x"', '3'):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.CheckViolation, msg=not_object):
                    insert(c, A, not_object, f'NOOBJ{len(not_object)}')
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.vehicle WHERE vehicle_label LIKE 'BAD-%'").fetchone()[0], 0)
        # eine Schemaversion verlangt beide Dimensionen
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,capacity_schema_version) VALUES (%s,%s,'o','{\"seats\":4}'::jsonb,'1.0')", (A, uid()))
        # auch ein UPDATE auf einen ungültigen Wert scheitert
        with tx(A) as c:
            v = insert(c, A, {'seats': 3, 'wheelchair': 0}, 'UPD-1')
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("UPDATE taktovia.vehicle SET capacity_config='{\"seats\":-3,\"wheelchair\":0}'::jsonb WHERE id=%s", (v,))
        # Kennung: je Mandant eindeutig, in anderem Mandanten zulässig; Altbestand ohne Kennung darf mehrfach vorkommen
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.UniqueViolation):
                insert(c, A, {'seats': 1, 'wheelchair': 0}, 'OK-1')
        with tx(B) as c:
            insert(c, B, {'seats': 1, 'wheelchair': 0}, 'OK-1')
        with tx(A) as c:
            insert(c, A, {'seats': 1, 'wheelchair': 0}, None); insert(c, A, {'seats': 1, 'wheelchair': 0}, None)
        # Kennungs- und Typformat
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation):
                insert(c, A, {'seats': 1, 'wheelchair': 0}, 'x' * 65)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation):
                insert(c, A, {'seats': 1, 'wheelchair': 0}, 'TYP-1', vtype='kleinbuchstaben')

    def test_neues_fahrzeug_ist_standardmaessig_gesperrt_und_aktiv(self):
        with tx(A) as c:
            v = insert(c, A, {'seats': 2, 'wheelchair': 0}, 'DEF-1')
            self.assertEqual(tuple(c.execute("SELECT active_block, active, master_revision, safety_revision FROM taktovia.vehicle WHERE id=%s", (v,)).fetchone()), (True, True, 1, 1))

    def test_gleichzeitige_anlage_derselben_kennung_genau_eine(self):
        results, barrier = [], threading.Barrier(4)

        def work():
            try:
                with tx(A) as c:
                    barrier.wait(timeout=10)
                    insert(c, A, {'seats': 1, 'wheelchair': 0}, 'CONC-1')
                results.append('ok')
            except psycopg.errors.UniqueViolation:
                results.append('dup')
        ts = [threading.Thread(target=work) for _ in range(4)]
        [t.start() for t in ts]; [t.join() for t in ts]
        self.assertEqual(sorted(results), ['dup', 'dup', 'dup', 'ok'])

    def test_rls_fremder_mandant_und_leerer_tenantkontext(self):
        with tx(A) as c: va = insert(c, A, {'seats': 4, 'wheelchair': 1}, 'RLS-A')
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.vehicle WHERE id=%s", (va,)).fetchone()[0], 0)
            self.assertEqual(c.execute("UPDATE taktovia.vehicle SET active=false WHERE id=%s", (va,)).rowcount, 0)   # fremde Zeile nicht änderbar
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):   # Insert mit fremder tenant_id
                insert(c, A, {'seats': 1, 'wheelchair': 0}, 'RLS-X')
        with tx(None) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.vehicle").fetchone()[0], 0, 'ohne Tenantkontext keine Zeile')
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):
                insert(c, A, {'seats': 1, 'wheelchair': 0}, 'RLS-Y')
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT active FROM taktovia.vehicle WHERE id=%s", (va,)).fetchone()[0], True)

    def test_mandant_ist_nach_dem_anlegen_nicht_aenderbar(self):
        with tx(A) as c: va = insert(c, A, {'seats': 4, 'wheelchair': 1}, 'IMM-A')
        with admin() as c:
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("UPDATE taktovia.vehicle SET tenant_id=%s WHERE id=%s", (B, va))


if __name__ == '__main__':
    unittest.main()
