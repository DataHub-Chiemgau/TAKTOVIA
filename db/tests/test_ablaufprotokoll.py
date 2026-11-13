"""WP-M3-55 (LH-I.8-AUT-002): Ablaufprotokoll automatisch ausgeführter Vorgänge unter der echten Runtime-Rolle. Append-only (UPDATE/DELETE scheitern für die Runtime-Rolle am fehlenden Recht, für die
Administration am Trigger), je Incident lückenlose und eindeutige Ordnungsnummer auch bei parallelen Schreibern, Mandantentrennung, Minimierung (nur Codes, kein Freitext)."""
import threading
import unittest

import psycopg

from helpers import admin, runtime, seed_tenant, set_tenant, tx, uid

A, B = uid(), uid()
ACTOR = 'system:workflow'


def incident(tenant):
    iid = uid()
    with tx(tenant) as c:
        c.execute("INSERT INTO taktovia.incident(tenant_id,id,kind,dedupe_key,created_by) VALUES (%s,%s,'VEHICLE_OUTAGE',%s,%s)", (tenant, iid, f'VEHICLE_OUTAGE:{iid}', ACTOR))
    return iid


LOG = ("INSERT INTO taktovia.workflow_run_log(tenant_id,id,incident_id,event_id,action,outcome,created_by,event_type,decision,correlation_id) "
       "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s) RETURNING seq")


def log(c, tenant, inc, action='OPEN_INCIDENT', outcome='CREATED', event=None, actor=ACTOR):
    return c.execute(LOG, (tenant, uid(), inc, event or uid(), action, outcome, actor, 'VEHICLE_BLOCKED', 'RULE_MATCHED', uid())).fetchone()[0]


class Ablaufprotokoll(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm355-a'); seed_tenant(B, 'm355-b')

    def test_ablaufprotokoll_append_only(self):
        inc = incident(A)
        with tx(A) as c:
            log(c, A, inc)
            rid = c.execute("SELECT id FROM taktovia.workflow_run_log WHERE incident_id=%s", (inc,)).fetchone()[0]
        for sql in ("UPDATE taktovia.workflow_run_log SET outcome='X' WHERE id=%s", "DELETE FROM taktovia.workflow_run_log WHERE id=%s", "UPDATE taktovia.workflow_run_log SET seq=99 WHERE id=%s"):
            with self.assertRaises(psycopg.Error) as cm, tx(A) as c:       # Runtime-Rolle: kein UPDATE/DELETE-Recht
                c.execute(sql, (rid,))
            self.assertEqual(cm.exception.sqlstate, '42501', sql)
            with self.assertRaises(psycopg.Error) as cm, admin() as c:      # Administration: Trigger reject_mutation
                c.execute(sql, (rid,))
            self.assertEqual(cm.exception.sqlstate, '23514', sql)
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT outcome, seq FROM taktovia.workflow_run_log WHERE id=%s", (rid,)).fetchone(), ('CREATED', 1))

    def test_ordnungsnummer_je_incident_lueckenlos_und_eindeutig_auch_parallel(self):
        inc = incident(A); other = incident(A); barrier = threading.Barrier(16); errors = []

        def worker(target):
            c = runtime()
            try:
                barrier.wait()
                for _ in range(5):
                    set_tenant(c, A)       # der Mandantenkontext gilt nur bis zum COMMIT
                    log(c, A, target)
                    c.commit()
            except Exception as e:  # noqa: BLE001
                errors.append(e)
            finally:
                c.close()
        ts = [threading.Thread(target=worker, args=(inc if i % 4 else other,)) for i in range(16)]
        [t.start() for t in ts]; [t.join(120) for t in ts]
        self.assertEqual(errors, [])
        with tx(A) as c:
            for target, n in ((inc, 12 * 5), (other, 4 * 5)):
                seqs = [r[0] for r in c.execute("SELECT seq FROM taktovia.workflow_run_log WHERE incident_id=%s ORDER BY seq", (target,)).fetchall()]
                self.assertEqual(seqs, list(range(1, n + 1)), 'alle Einträge erhalten, lückenlos und doppelfrei')

    def test_rollback_nimmt_die_ordnungsnummer_zurueck(self):
        inc = incident(A)
        c = runtime()
        try:
            set_tenant(c, A); self.assertEqual(log(c, A, inc), 1); c.rollback()
        finally:
            c.close()
        with tx(A) as c:
            self.assertEqual(log(c, A, inc), 1, 'kein verbrauchter Platz nach Rollback')

    def test_eintrag_ohne_incident_hat_keine_ordnungsnummer_und_nur_codes(self):
        with tx(A) as c:
            self.assertIsNone(log(c, A, None, action='EVALUATE_RULE', outcome='NO_RULE'))
        for action, outcome in (('Max Mustermann', 'OK'), ('OPEN_INCIDENT', 'Fahrer Max Mustermann hat angerufen'), ('open', 'OK')):    # Freitext und Namen scheitern an den Zeichenklassen
            with self.assertRaises(psycopg.errors.CheckViolation), tx(A) as c:
                log(c, A, None, action=action, outcome=outcome)
        inc = incident(A)
        with tx(A) as c:           # eine Ordnungsnummer vom Client gibt es nicht: ohne Incident keine, mit Incident immer die der Datenbank
            self.assertIsNone(c.execute("INSERT INTO taktovia.workflow_run_log(tenant_id,id,event_id,action,outcome,created_by,seq) VALUES (%s,%s,%s,'NO_ACTION','NO_ACTION',%s,7) RETURNING seq", (A, uid(), uid(), ACTOR)).fetchone()[0])
            self.assertEqual(c.execute("INSERT INTO taktovia.workflow_run_log(tenant_id,id,incident_id,event_id,action,outcome,created_by,seq) VALUES (%s,%s,%s,%s,'NO_ACTION','NO_ACTION',%s,7) RETURNING seq", (A, uid(), inc, uid(), ACTOR)).fetchone()[0], 1)

    def test_mandantentrennung(self):
        inc = incident(A)
        with tx(A) as c:
            log(c, A, inc)
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.workflow_run_log WHERE incident_id=%s", (inc,)).fetchone()[0], 0)
            with self.assertRaises(psycopg.Error):
                log(c, B, inc)       # Incident eines anderen Mandanten (Fremdschlüssel/Zeilenschutz)


if __name__ == '__main__':
    unittest.main()
