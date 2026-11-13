"""WP-M3-53 (LH-I.8-AUT-001): Workflow-Regelrevision und Incident unter der echten Runtime-Rolle. Die Regelrevision ist unveränderlich (Update/Delete scheitern, auch für die
Administration), ihre Nummer vergibt die Datenbank lückenlos je (Mandant, Ereignisart) auch bei parallelen Veröffentlichungen; der Incident hat höchstens einen offenen Vorgang je
Dedupe-Schlüssel, einen Zustandsautomaten als zweite Schicht, unveränderliche Identität und höchstens eine Ersatzsuche."""
import threading, unittest
import psycopg
from helpers import admin, runtime, set_tenant, tx, uid, seed_tenant

A, B = uid(), uid()
ACTOR = 'system:workflow-test'
RULE = '{"schema":"workflow-regel.v1","event_kind":"VEHICLE_BLOCKED","actions":[]}'


def rule(tenant, kind='VEHICLE_BLOCKED'):
    rid = uid()
    with tx(tenant) as c:
        c.execute("INSERT INTO taktovia.workflow_rule_revision(tenant_id,id,event_kind,rule,created_by) VALUES (%s,%s,%s,%s,%s)", (tenant, rid, kind, RULE, ACTOR))
    return rid


def incident(tenant, key=None, status='OPEN', operation=None):
    iid = uid()
    with tx(tenant) as c:
        c.execute("INSERT INTO taktovia.incident(tenant_id,id,kind,status,dedupe_key,created_by,replacement_operation_id) VALUES (%s,%s,'VEHICLE_OUTAGE',%s,%s,%s,%s)",
                  (tenant, iid, status, key or f'VEHICLE_OUTAGE:{uid()}', ACTOR, operation))
    return iid


def operation(tenant):
    oid = uid()
    with tx(tenant) as c:
        c.execute("INSERT INTO taktovia.operation(tenant_id,id,kind,status,deadline_at) VALUES (%s,%s,'REPLACEMENT_SEARCH','QUEUED',now()+interval '1 hour')", (tenant, oid))
    return oid


class Regelrevision(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'wf53-a'); seed_tenant(B, 'wf53-b')

    def test_regelrevision_unveraenderlich(self):
        rid = rule(A)
        for sql in ("UPDATE taktovia.workflow_rule_revision SET rule='{}' WHERE id=%s", "DELETE FROM taktovia.workflow_rule_revision WHERE id=%s"):
            with self.assertRaises(psycopg.Error) as cm:
                with tx(A) as c:
                    c.execute(sql, (rid,))
            self.assertEqual(cm.exception.sqlstate, '42501', sql)       # die Runtime-Rolle hat kein UPDATE/DELETE-Recht
            with self.assertRaises(psycopg.Error) as cm, admin() as c:  # die Administration scheitert am Trigger reject_mutation (V3020)
                c.execute(sql, (rid,))
            self.assertEqual(cm.exception.sqlstate, '23514', sql)
        with tx(A) as c:   # der Inhalt ist nach den Versuchen unverändert
            self.assertEqual(c.execute("SELECT rule->>'event_kind' FROM taktovia.workflow_rule_revision WHERE id=%s", (rid,)).fetchone()[0], 'VEHICLE_BLOCKED')

    def test_revisionsnummer_fortlaufend_je_ereignisart_auch_parallel(self):
        kind = 'REJECTION'; barrier = threading.Barrier(8); errors = []

        def worker():
            c = runtime()
            try:
                barrier.wait()
                set_tenant(c, A)
                c.execute("INSERT INTO taktovia.workflow_rule_revision(tenant_id,id,event_kind,rule,created_by) VALUES (%s,%s,%s,%s,%s)", (A, uid(), kind, RULE, ACTOR)); c.commit()
            except Exception as e:  # noqa: BLE001
                errors.append(e)
            finally:
                c.close()
        ts = [threading.Thread(target=worker) for _ in range(8)]
        [t.start() for t in ts]; [t.join(60) for t in ts]
        self.assertEqual(errors, [])
        with tx(A) as c:
            nos = [r[0] for r in c.execute("SELECT revision_no FROM taktovia.workflow_rule_revision WHERE event_kind=%s ORDER BY revision_no", (kind,)).fetchall()]
        self.assertEqual(nos, list(range(1, 9)), 'lückenlos und doppelfrei')
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.workflow_rule_revision WHERE event_kind=%s", (kind,)).fetchone()[0], 0, 'Mandantentrennung')

    def test_ein_offener_incident_je_dedupe_schluessel_nach_abschluss_ein_neuer(self):
        key = f'VEHICLE_OUTAGE:{uid()}'
        first = incident(A, key)
        with self.assertRaises(psycopg.errors.UniqueViolation):
            incident(A, key)
        with tx(A) as c:
            c.execute("UPDATE taktovia.incident SET status='CLOSED_NO_REPLACEMENT', closed_at=now(), revision=revision+1 WHERE id=%s", (first,))
        incident(A, key)   # neuer Ausfall = neuer Incident
        incident(B, key)   # derselbe Schlüssel in einem anderen Mandanten ist unabhängig

    def test_incident_zustandsautomat_und_identitaet_in_der_datenbank(self):
        iid = incident(A)
        def attempt(sql, *args, expect='23514'):
            with self.assertRaises(psycopg.Error) as cm:
                with tx(A) as c:
                    c.execute(sql, args)
            self.assertEqual(cm.exception.sqlstate, expect, sql)
        attempt("UPDATE taktovia.incident SET status='RESOLVED', closed_at=now(), revision=revision+1 WHERE id=%s", iid)         # OPEN -> RESOLVED nicht erlaubt
        attempt("UPDATE taktovia.incident SET dedupe_key='X' WHERE id=%s", iid)                                                   # Identität
        attempt("UPDATE taktovia.incident SET kind='DEFECT' WHERE id=%s", iid)
        attempt("DELETE FROM taktovia.incident WHERE id=%s", iid, expect='42501')                                                 # kein DELETE-Recht
        with self.assertRaises(psycopg.errors.CheckViolation), admin() as c:                                                      # auch die Administration: Wächter
            c.execute("DELETE FROM taktovia.incident WHERE id=%s", (iid,))
        attempt("UPDATE taktovia.incident SET status='REPLACEMENT_SEARCH', revision=revision+1 WHERE id=%s", iid, expect='23514')  # ohne Operation: CHECK incident_search_consistent
        op = operation(A)
        with tx(A) as c:
            c.execute("UPDATE taktovia.incident SET status='REPLACEMENT_SEARCH', replacement_operation_id=%s, revision=revision+1 WHERE id=%s", (op, iid))
            c.execute("UPDATE taktovia.incident SET status='OPEN', revision=revision+1 WHERE id=%s", (iid,))                       # Suche ohne Ergebnis: zurück nach OPEN
        attempt("UPDATE taktovia.incident SET replacement_operation_id=NULL WHERE id=%s", iid)                                     # die Suche startet genau einmal
        with tx(A) as c:
            c.execute("UPDATE taktovia.incident SET status='CLOSED_NO_REPLACEMENT', closed_at=now(), revision=revision+1 WHERE id=%s", (iid,))
        attempt("UPDATE taktovia.incident SET status='OPEN', closed_at=NULL, revision=revision+1 WHERE id=%s", iid)               # Endzustand

    def test_eine_operation_gehoert_hoechstens_einem_incident(self):
        op = operation(A)
        incident(A, status='REPLACEMENT_SEARCH', operation=op)
        with self.assertRaises(psycopg.errors.UniqueViolation):
            incident(A, status='REPLACEMENT_SEARCH', operation=op)


if __name__ == '__main__':
    unittest.main()
