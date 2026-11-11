"""WP-M1-12: Audit append-only im Abnahmelauf (Wiederholung des Nachweises aus WP-M1-46), Abfrageindizes und reservierter Systemmandant
für Ereignisse ohne Mandantenkontext (OP-14-Vorschlag, V1120)."""
import unittest

import psycopg

from helpers import admin, dbtool, seed_tenant, tx, uid

SYSTEM = '00000000-0000-0000-0000-000000000001'
A, B = uid(), uid()
INS = """INSERT INTO taktovia.audit_event(tenant_id,id,actor_ref,action,object_ref,occurred_at,correlation_id,redacted_changes)
         VALUES (%s,%s,%s,%s,%s,now(),%s,'{}'::jsonb)"""


def audit(c, tenant, eid=None, actor='user:u1', action='security.auth.login_failed'):
    c.execute(INS, (tenant, eid or uid(), actor, action, f'login:{uid()}', uid()))


class AuditAbnahme(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'audit12-a'); seed_tenant(B, 'audit12-b')

    def test_update_delete_auf_audit_event_scheitern(self):
        """SEC-006 AK 2: UPDATE und DELETE scheitern, auch unter der Runtime-Rolle (Rechte) und als Administrator (Trigger)."""
        with tx(A) as c:
            eid = uid(); audit(c, A, eid)
        for sql in ("UPDATE taktovia.audit_event SET action='x.y'", "DELETE FROM taktovia.audit_event"):
            with self.assertRaises(psycopg.errors.InsufficientPrivilege, msg=sql), tx(A) as c:
                c.execute(sql)
        with admin() as a:
            a.execute("SELECT set_config('app.tenant_id', %s, false)", (str(A),))
            for sql in ("UPDATE taktovia.audit_event SET action='x.y' WHERE id=%s", "DELETE FROM taktovia.audit_event WHERE id=%s"):
                with self.assertRaises(psycopg.errors.CheckViolation, msg=sql):
                    a.execute(sql, (eid,))
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT action FROM taktovia.audit_event WHERE id=%s", (eid,)).fetchone()[0], 'security.auth.login_failed')

    def test_runtime_kann_keine_mandanten_anlegen_auch_nicht_den_systemmandanten(self):
        for tenant in (uid(), SYSTEM):
            with self.assertRaises(psycopg.errors.InsufficientPrivilege), tx(None) as c:
                c.execute("INSERT INTO taktovia.tenant(id,name,status) VALUES (%s,'x','ACTIVE')", (tenant,))

    def test_abfrageindizes_fuer_zeitraum_akteur_aktion_und_objekt_existieren(self):
        with admin() as c:
            names = {r[0] for r in c.execute("SELECT indexname FROM pg_indexes WHERE schemaname='taktovia' AND tablename='audit_event'")}
        self.assertTrue({'audit_event_by_time', 'audit_event_by_object', 'audit_event_by_actor', 'audit_event_by_action'} <= names, names)

    @staticmethod
    def provision():
        dbtool.provision_system_tenant()   # wie der Plattformbetrieb; idempotent

    def test_systemmandant_ist_reserviert_gesperrt_und_nimmt_mandantenlose_ereignisse_auf(self):
        self.provision(); self.provision()   # idempotent
        with admin() as c:
            row = c.execute("SELECT status FROM taktovia.tenant WHERE id=%s", (SYSTEM,)).fetchone()
        self.assertEqual(row[0], 'SUSPENDED', 'Systemmandant fehlt oder ist nicht gesperrt (nie als Fachmandant nutzbar)')
        with tx(SYSTEM) as c:
            eid = uid(); audit(c, SYSTEM, eid)
        with tx(SYSTEM) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.audit_event WHERE id=%s", (eid,)).fetchone()[0], 1)

    def test_systemmandant_ereignisse_sind_fuer_fachmandanten_unsichtbar(self):
        self.provision()
        with tx(SYSTEM) as c:
            eid = uid(); audit(c, SYSTEM, eid)
        for t in (A, B):
            with tx(t) as c:
                self.assertEqual(c.execute("SELECT count(*) FROM taktovia.audit_event WHERE id=%s", (eid,)).fetchone()[0], 0)


if __name__ == '__main__':
    unittest.main()
