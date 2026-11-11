"""WP-M1-46: audit_event ist append-only (Trigger audit_immutable, TRUNCATE-Sperre, Runtime nur INSERT/SELECT), Formate der Pflichtangaben,
RLS, und die Invariante §4.5 „Gegenereignis statt Änderung“ mit echten parallelen Transaktionen."""
import threading
import unittest

import psycopg
from helpers import admin, runtime, seed_tenant, set_tenant, tx, uid

A, B = uid(), uid()
INS = """INSERT INTO taktovia.audit_event(tenant_id,id,actor_ref,action,object_ref,occurred_at,correlation_id,redacted_changes)
         VALUES (%s,%s,%s,%s,%s,now(),%s,%s::jsonb)"""


def audit(c, tenant, eid=None, actor='system:test', action='identity.membership.granted', obj=None, changes='{}'):
    c.execute(INS, (tenant, eid or uid(), actor, action, obj or f'membership:{uid()}', uid(), changes))


class AuditEvent(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'audit-a'); seed_tenant(B, 'audit-b')

    def test_runtime_hat_nur_insert_und_select(self):
        with admin() as c:
            privs = {r[0] for r in c.execute("""SELECT privilege_type FROM information_schema.role_table_grants
                     WHERE table_schema='taktovia' AND table_name='audit_event' AND grantee='taktovia_runtime'""")}
        self.assertEqual(privs, {'INSERT', 'SELECT'})

    def test_update_und_delete_scheitern_auch_fuer_administrator_per_trigger(self):
        with tx(A) as c:
            eid = uid(); audit(c, A, eid)
        with admin() as a:
            a.execute("SELECT set_config('app.tenant_id', %s, false)", (str(A),))
            for sql, params in (("UPDATE taktovia.audit_event SET action='x.y' WHERE id=%s", (eid,)),
                                ("DELETE FROM taktovia.audit_event WHERE id=%s", (eid,))):
                with self.assertRaises(psycopg.errors.CheckViolation, msg=sql):
                    a.execute(sql, params)

    def test_runtime_update_und_delete_scheitern_an_rechten(self):
        with tx(A) as c:
            eid = uid(); audit(c, A, eid)
        for sql in ("UPDATE taktovia.audit_event SET action='x.y'", "DELETE FROM taktovia.audit_event", "TRUNCATE taktovia.audit_event"):
            with self.assertRaises(psycopg.errors.InsufficientPrivilege, msg=sql), tx(A) as c:
                c.execute(sql)

    def test_insert_mit_fremder_tenant_id_und_ohne_kontext_scheitert(self):
        with self.assertRaises(psycopg.errors.InsufficientPrivilege), tx(A) as c:   # RLS WITH CHECK
            audit(c, B)
        with self.assertRaises(psycopg.errors.InsufficientPrivilege), tx(None) as c:
            audit(c, A)

    def test_mit_kontext_a_ist_keine_zeile_von_b_lesbar(self):
        with tx(B) as c:
            eid = uid(); audit(c, B, eid)
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.audit_event WHERE id=%s", (eid,)).fetchone()[0], 0)
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=%s", (B,)).fetchone()[0], 0)

    def test_formate_der_pflichtangaben_werden_von_der_datenbank_erzwungen(self):
        bad = (dict(actor='Max Mustermann'), dict(actor='user:a@b.example'), dict(action='Beliebig'), dict(action='nurein'),
               dict(obj='Membership:x'), dict(obj='keinTyp'), dict(changes='[]'), dict(changes='"text"'))
        for kw in bad:
            with self.assertRaises(psycopg.errors.CheckViolation, msg=str(kw)), tx(A) as c:
                audit(c, A, **kw)

    def test_fehlgeschlagene_transaktion_hinterlaesst_keinen_auditeintrag(self):
        eid = uid()
        with self.assertRaises(RuntimeError), tx(A) as c:
            audit(c, A, eid); raise RuntimeError('Fachfehler')
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.audit_event WHERE id=%s", (eid,)).fetchone()[0], 0)

    def test_paragraph_4_5_aenderungsversuch_parallel_zu_neuen_eintraegen(self):
        with tx(A) as c:
            base = uid(); audit(c, A, base, action='config.revision.published')
        errors, done = [], []
        start = threading.Barrier(9)

        def writer():
            try:
                start.wait()
                with tx(A) as c:
                    for _ in range(5):
                        e = uid(); audit(c, A, e); done.append(e)
            except Exception as e:  # noqa: BLE001
                errors.append(('writer', e))

        def tamper():
            start.wait()
            try:
                with tx(A) as c:
                    c.execute("UPDATE taktovia.audit_event SET action='x.hacked' WHERE id=%s", (base,))
                errors.append(('tamper', 'Änderung gelang'))
            except psycopg.errors.InsufficientPrivilege:
                pass

        ts = [threading.Thread(target=writer) for _ in range(8)] + [threading.Thread(target=tamper)]
        [t.start() for t in ts]; [t.join() for t in ts]
        self.assertEqual(errors, [])
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT action FROM taktovia.audit_event WHERE id=%s", (base,)).fetchone()[0], 'config.revision.published')
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.audit_event WHERE id=ANY(%s)", (done,)).fetchone()[0], 40)

    def test_gegenereignis_statt_aenderung(self):
        with tx(A) as c:
            orig = uid(); audit(c, A, orig, action='identity.membership.granted', obj='membership:m-1')
            audit(c, A, action='identity.membership.revoked', obj='membership:m-1', changes='{"reverses": "granted"}')
            rows = c.execute("SELECT action FROM taktovia.audit_event WHERE object_ref='membership:m-1' ORDER BY occurred_at, action").fetchall()
        self.assertEqual([r[0] for r in rows], ['identity.membership.granted', 'identity.membership.revoked'])


if __name__ == '__main__':
    unittest.main()
