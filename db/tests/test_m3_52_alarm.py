"""WP-M3-52: Alarm-Grundlage unter der echten Runtime-Rolle. Dedupe über (Regel, Quellereignis, Empfänger) in jedem Zustand und bei hundert parallelen Zustellungen, höchstens ein offener
Alarm je Bedingungsschlüssel, Zustandsautomat und Unveränderlichkeit als zweite Schicht, genau eine Quittierung bei zwei gleichzeitigen, Mandantentrennung."""
import threading, unittest
import psycopg
from helpers import runtime, set_tenant, tx, uid, seed_tenant

A, B = uid(), uid()
INS = ("INSERT INTO taktovia.business_alert(tenant_id,id,alert_kind,priority,dedupe_key,created_by,source_event_id,recipient_role,policy_revision_id) "
       "VALUES (%s,%s,%s,%s,%s,'system:alarm',%s,%s,%s) ON CONFLICT DO NOTHING")


def policy(tenant):
    pid = uid()
    with tx(tenant) as c:
        c.execute("INSERT INTO taktovia.alert_policy_revision(tenant_id,id,revision_no,rules,created_by) VALUES (%s,%s,(SELECT coalesce(max(revision_no),0)+1 FROM taktovia.alert_policy_revision),'{\"rules\":[]}','system:t')", (tenant, pid))
    return pid


class Alarm(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm352-a'); seed_tenant(B, 'm352-b')
        cls.pa = policy(A)

    def alert(self, tenant=A, kind='VEHICLE_BLOCK_CRITICAL', source=None, state_sql=None, recipient='dispatcher'):
        aid, source = uid(), source or uid()
        with tx(tenant) as c:
            c.execute(INS, (tenant, aid, kind, 'HIGH', f'{kind}|{source}|{recipient}', source, recipient, self.pa))
        return aid, source

    def test_dasselbe_quellereignis_erzeugt_je_regel_und_empfaenger_genau_einen_alarm_auch_nach_quittierung(self):
        aid, source = self.alert()
        with tx(A) as c:
            c.execute("UPDATE taktovia.business_alert SET state='ACKNOWLEDGED', revision=revision+1 WHERE id=%s", (aid,))
        again, _ = self.alert(source=source)       # erneute Zustellung nach Quittierung: kein zweiter Alarm
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.business_alert WHERE source_event_id=%s", (source,)).fetchone()[0], 1)
        # andere Regel oder anderer Empfänger desselben Ereignisses: eigener Alarm
        self.alert(kind='TECHNICAL_EVENT', source=source); self.alert(source=source, recipient='tenant_admin')
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.business_alert WHERE source_event_id=%s", (source,)).fetchone()[0], 3)

    def test_hundert_parallele_zustellungen_ein_alarm(self):
        source = uid(); barrier = threading.Barrier(20)

        def worker():
            c = runtime()
            try:
                barrier.wait()
                for _ in range(5):
                    set_tenant(c, A)
                    c.execute(INS, (A, uid(), 'VEHICLE_BLOCK_CRITICAL', 'CRITICAL', f'VEHICLE_BLOCK_CRITICAL|{source}|dispatcher', source, 'dispatcher', self.pa)); c.commit()
            finally:
                c.close()
        ts = [threading.Thread(target=worker) for _ in range(20)]
        [t.start() for t in ts]; [t.join(60) for t in ts]
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.business_alert WHERE source_event_id=%s", (source,)).fetchone()[0], 1)

    def test_bedingungsalarm_hoechstens_ein_offener_je_schluessel_nach_loesung_neuer_alarm(self):
        key = 'OUTBOX_BACKLOG-' + str(uid())
        def raise_():
            with tx(A) as c:
                c.execute("INSERT INTO taktovia.business_alert(tenant_id,id,alert_kind,priority,dedupe_key,created_by) VALUES (%s,%s,'TECH_OUTBOX_BACKLOG','HIGH',%s,'system:t') ON CONFLICT DO NOTHING", (A, uid(), key))
                return c.execute("SELECT id, state FROM taktovia.business_alert WHERE dedupe_key=%s ORDER BY created_at", (key,)).fetchall()
        self.assertEqual(len(raise_()), 1); self.assertEqual(len(raise_()), 1, 'anhaltender Fehler: weiterhin ein offener Alarm')
        with tx(A) as c:
            c.execute("UPDATE taktovia.business_alert SET state='RESOLVED', resolved_at=now(), revision=revision+1 WHERE dedupe_key=%s", (key,))
        rows = raise_()
        self.assertEqual([r[1] for r in rows], ['RESOLVED', 'OPEN'], 'nach Behebung entsteht bei erneutem Auftreten ein neuer Alarm')

    def test_zustandsautomat_und_unveraenderlichkeit(self):
        aid, _ = self.alert()
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("UPDATE taktovia.business_alert SET priority='NORMAL' WHERE id=%s", (aid,))
        for field, value in (("alert_kind", "'OTHER'"), ("dedupe_key", "'x'"), ("recipient_role", "'support'"), ("service_id", "gen_random_uuid()"), ("source_event_id", "gen_random_uuid()")):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.CheckViolation, msg=field):
                    c.execute(f"UPDATE taktovia.business_alert SET {field}={value} WHERE id=%s", (aid,))
        with tx(A) as c:
            c.execute("UPDATE taktovia.business_alert SET state='ACKNOWLEDGED', revision=revision+1 WHERE id=%s", (aid,))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("UPDATE taktovia.business_alert SET state='OPEN', revision=revision+1 WHERE id=%s", (aid,))
        with tx(A) as c:
            c.execute("UPDATE taktovia.business_alert SET state='RESOLVED', resolved_at=now(), revision=revision+1 WHERE id=%s", (aid,))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("UPDATE taktovia.business_alert SET state='ACKNOWLEDGED', resolved_at=NULL, revision=revision+1 WHERE id=%s", (aid,))
        # Eskalationsstufe sinkt nie; Revision steigt nur um eins
        bid, _ = self.alert()
        with tx(A) as c:
            c.execute("UPDATE taktovia.business_alert SET escalation_level=1, escalated_at=now(), revision=revision+1 WHERE id=%s", (bid,))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("UPDATE taktovia.business_alert SET escalation_level=0, revision=revision+1 WHERE id=%s", (bid,))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("UPDATE taktovia.business_alert SET revision=revision+5 WHERE id=%s", (bid,))

    def test_zwei_gleichzeitige_quittierungen_eine_wirksame(self):
        aid, _ = self.alert(); barrier = threading.Barrier(2); results = []

        def worker(n):
            c = runtime()
            try:
                set_tenant(c, A); barrier.wait()
                try:
                    c.execute("INSERT INTO taktovia.alert_acknowledgement(tenant_id,id,alert_id,acknowledged_by,cause_code) VALUES (%s,%s,%s,%s,'HANDLED')", (A, uid(), aid, f'user:{n}'))
                    c.commit(); results.append('ok')
                except psycopg.errors.UniqueViolation:
                    c.rollback(); results.append('dup')
            finally:
                c.close()
        ts = [threading.Thread(target=worker, args=(i,)) for i in range(2)]
        [t.start() for t in ts]; [t.join(30) for t in ts]
        self.assertEqual(sorted(results), ['dup', 'ok'])

    def test_mandantentrennung(self):
        aid, _ = self.alert()
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.business_alert WHERE id=%s", (aid,)).fetchone()[0], 0)
            self.assertEqual(c.execute("UPDATE taktovia.business_alert SET state='ACKNOWLEDGED' WHERE id=%s", (aid,)).rowcount, 0)

    def test_quittierung_unveraenderlich(self):
        aid, _ = self.alert(); ack = uid()
        with tx(A) as c:
            c.execute("INSERT INTO taktovia.alert_acknowledgement(tenant_id,id,alert_id,acknowledged_by,cause_code,note) VALUES (%s,%s,%s,'user:x','FALSE_ALARM','war eine Probe')", (A, ack, aid))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.Error):
                c.execute("UPDATE taktovia.alert_acknowledgement SET cause_code='HANDLED' WHERE id=%s", (ack,))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("INSERT INTO taktovia.alert_acknowledgement(tenant_id,id,alert_id,acknowledged_by,cause_code) VALUES (%s,%s,%s,'user:y','BOGUS')", (A, uid(), self.alert()[0]))


if __name__ == '__main__':
    unittest.main()
