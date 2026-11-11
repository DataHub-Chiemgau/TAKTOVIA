"""WP-M1-17 / LH-6.1-SYS-007: Zustandsspalten sind in der Datenbank gehärtet (CHECK über die Wertemenge plus Übergangsregeltabelle).

Nachweis in M1 an `operation.status` und `tenant.status`; das Muster (`harden_state_column`) wird an Hilfstabellen in einem
eigenen Schema für die Spalten angewendet, die M2/M3 übernehmen (`booking.state`, `payment_intent.state`, `vehicle_run.state`, `capacity_hold.state`)."""
import threading
import unittest

import psycopg
from helpers import admin, runtime, seed_tenant, set_tenant, tx, uid

A = uid()
OPERATION = {
    'QUEUED': {'RUNNING'}, 'RUNNING': {'SUCCEEDED', 'FAILED', 'RECONCILING', 'MANUAL_REVIEW', 'QUEUED'},
    'RECONCILING': {'SUCCEEDED', 'FAILED', 'MANUAL_REVIEW'}, 'MANUAL_REVIEW': {'SUCCEEDED', 'FAILED', 'QUEUED', 'RECONCILING'},
    'SUCCEEDED': set(), 'FAILED': set(),
}


def seed_operation(c, tenant, status):
    """Operation ohne Journey-Kette einfügen (Administrator, Fremdschlüsselprüfung für diese Sitzung aus)."""
    oid = uid()
    c.execute("SET session_replication_role = replica")
    c.execute("INSERT INTO taktovia.operation(tenant_id,id,journey_id,kind,status,deadline_at) VALUES (%s,%s,%s,'probe',%s,now())",
              (tenant, oid, uid(), status))
    c.execute("SET session_replication_role = DEFAULT")
    return oid


class Zustandshaertung(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'zustand-a')

    def test_unzulaessiger_zustandswert_scheitert_am_check(self):
        with admin() as c:
            oid = seed_operation(c, A, 'QUEUED')
        for bad in ('DONE', 'queued', '', 'UNKNOWN'):
            with self.assertRaises(psycopg.errors.CheckViolation, msg=bad):
                with tx(A) as r:
                    r.execute("UPDATE taktovia.operation SET status=%s WHERE tenant_id=%s AND id=%s", (bad, A, oid))
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as r:
                r.execute("INSERT INTO taktovia.operation(tenant_id,id,journey_id,kind,status,deadline_at) VALUES (%s,%s,%s,'x','DONE',now())",
                          (A, uid(), uid()))
        with admin() as c:   # auch der Administrator unterliegt dem CHECK
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("UPDATE taktovia.operation SET status='DONE' WHERE id=%s", (oid,))
            self.assertEqual(c.execute("SELECT status FROM taktovia.operation WHERE id=%s", (oid,)).fetchone()[0], 'QUEUED')
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("UPDATE taktovia.tenant SET status='DELETED' WHERE id=%s", (A,))

    def test_uebergangsregeln_der_operation_gelten_fuer_jede_rolle(self):
        for frm, allowed in OPERATION.items():
            for to in OPERATION:
                if to == frm:
                    continue
                with admin() as c:
                    oid = seed_operation(c, A, frm)
                try:
                    with tx(A) as r:
                        r.execute("UPDATE taktovia.operation SET status=%s, revision=revision+1 WHERE tenant_id=%s AND id=%s", (to, A, oid))
                    ok = True
                except psycopg.errors.CheckViolation:
                    ok = False
                self.assertEqual(ok, to in allowed, f'{frm} -> {to}')

    def test_gleicher_zustand_und_andere_spalten_bleiben_moeglich(self):
        with admin() as c:
            oid = seed_operation(c, A, 'RUNNING')
        with tx(A) as r:   # eine Anweisung: ein zweites UPDATE derselben Zeile würde den (absichtlich übergangenen) Fremdschlüssel des Seeds prüfen
            r.execute("UPDATE taktovia.operation SET status='RUNNING', last_error_code='x' WHERE tenant_id=%s AND id=%s", (A, oid))
        with admin() as c:
            self.assertEqual(c.execute("SELECT status, last_error_code FROM taktovia.operation WHERE id=%s", (oid,)).fetchone(), ('RUNNING', 'x'))

    def test_tenant_status_uebergaenge(self):
        allowed = {('ACTIVE', 'SUSPENDED'), ('SUSPENDED', 'ACTIVE'), ('ACTIVE', 'CLOSED'), ('SUSPENDED', 'CLOSED')}
        states = ('ACTIVE', 'SUSPENDED', 'CLOSED')
        for frm in states:
            for to in states:
                if frm == to:
                    continue
                t = uid()
                with admin() as c:
                    c.execute("INSERT INTO taktovia.tenant(id,name,status) VALUES (%s,%s,%s)", (t, 'z', frm))
                    try:
                        c.execute("UPDATE taktovia.tenant SET status=%s WHERE id=%s", (to, t))
                        ok = True
                    except psycopg.errors.CheckViolation:
                        ok = False
                self.assertEqual(ok, (frm, to) in allowed, f'{frm} -> {to}')

    def test_runtime_darf_regeln_nur_lesen(self):
        c = runtime()
        try:
            self.assertGreater(c.execute("SELECT count(*) FROM taktovia.state_transition_rule").fetchone()[0], 20)
            for stmt in ("INSERT INTO taktovia.state_transition_rule VALUES ('operation',1,'FAILED','QUEUED')",
                         "UPDATE taktovia.state_transition_rule SET to_state='QUEUED'", "DELETE FROM taktovia.state_transition_rule"):
                with self.assertRaises(psycopg.errors.InsufficientPrivilege, msg=stmt):
                    c.execute(stmt)
                c.rollback()
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):
                c.execute("SELECT taktovia.harden_state_column('taktovia.operation','status','operation')")
        finally:
            c.rollback(); c.close()

    def test_muster_fuer_m2_m3_spalten_an_hilfstabellen(self):
        """Das Muster wirkt auf jede Zustandsspalte: Regelmenge der Maschine 'booking'/'payment' liegt bereits vor."""
        with admin() as c:
            c.execute("DROP SCHEMA IF EXISTS zustandsmuster CASCADE"); c.execute("CREATE SCHEMA zustandsmuster")
            try:
                for machine, tbl, col in (('booking', 'booking', 'state'), ('payment', 'payment_intent', 'state')):
                    c.execute(f"CREATE TABLE zustandsmuster.{tbl}(id int PRIMARY KEY, {col} text NOT NULL)")
                    c.execute("SELECT taktovia.harden_state_column(%s::regclass, %s, %s)", (f'zustandsmuster.{tbl}', col, machine))
                    c.execute(f"INSERT INTO zustandsmuster.{tbl} VALUES (1, %s)", ('HOLDING' if machine == 'booking' else 'CREATED',))
                    with self.assertRaises(psycopg.errors.CheckViolation):
                        c.execute(f"INSERT INTO zustandsmuster.{tbl} VALUES (2, 'BOGUS')")
                with self.assertRaises(psycopg.errors.CheckViolation):
                    c.execute("UPDATE zustandsmuster.booking SET state='CONFIRMED' WHERE id=1")   # HOLDING -> CONFIRMED nicht vorgesehen
                c.execute("UPDATE zustandsmuster.booking SET state='HELD' WHERE id=1")
                c.execute("UPDATE zustandsmuster.payment_intent SET state='REQUIRES_ACTION' WHERE id=1")
                with self.assertRaises(psycopg.errors.CheckViolation):
                    c.execute("UPDATE zustandsmuster.payment_intent SET state='CAPTURED' WHERE id=1")   # nie ohne Autorisierung
                with self.assertRaises(psycopg.errors.RaiseException):
                    c.execute("SELECT taktovia.harden_state_column('zustandsmuster.booking'::regclass, 'state', 'gibt_es_nicht')")
            finally:
                c.execute("DROP SCHEMA zustandsmuster CASCADE")

    def test_zwei_gleichzeitige_widerspruechliche_updates_serialisieren(self):
        """Echte parallele Transaktionen auf derselben Zeile: der zweite sieht nach dem Warten den neuen Zustand und scheitert an der Regel."""
        with admin() as c:
            oid = seed_operation(c, A, 'RUNNING')
        results = []

        def worker(to):
            try:
                with tx(A) as r:
                    r.execute("SELECT status FROM taktovia.operation WHERE tenant_id=%s AND id=%s FOR UPDATE", (A, oid))
                    r.execute("UPDATE taktovia.operation SET status=%s WHERE tenant_id=%s AND id=%s", (to, A, oid))
                results.append((to, True))
            except psycopg.errors.CheckViolation:
                results.append((to, False))
        ts = [threading.Thread(target=worker, args=(to,)) for to in ('SUCCEEDED', 'QUEUED')]   # beide aus RUNNING erlaubt; keiner der beiden Folgezustände erlaubt den anderen
        [t.start() for t in ts]; [t.join() for t in ts]
        with admin() as c:
            final = c.execute("SELECT status FROM taktovia.operation WHERE id=%s", (oid,)).fetchone()[0]
        winners = [r for r in results if r[1]]
        # SUCCEEDED ist endgültig und QUEUED -> SUCCEEDED ist nicht vorgesehen: genau einer gewinnt, der andere scheitert an der Regel.
        self.assertEqual(len(winners), 1, results)
        self.assertEqual(final, winners[0][0])


if __name__ == '__main__':
    unittest.main()
