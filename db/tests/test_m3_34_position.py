"""WP-M3-34: position_sample unter der echten Runtime-Rolle. Messzeit und Empfangszeit getrennt, „letzte Position“ nach jüngster Messzeit (Index), Eindeutigkeit der Messzeit je Schicht
(erneute Sendung erzeugt keine zweite Zeile, auch bei paralleler Wiederholung), Löschen nur für den Löschlauf (DELETE), Ändern bleibt verboten, Mandantentrennung."""
import threading, unittest
import psycopg
from helpers import runtime, set_tenant, tx, uid, seed_tenant

A, B = uid(), uid()
INS = ("INSERT INTO taktovia.position_sample(tenant_id,id,vehicle_id,shift_id,position,accuracy_m,measured_at,received_at,created_by) "
       "VALUES (%s,%s,%s,%s,ST_GeogFromText('POINT(13.74 51.05)'),5,%s::timestamptz,%s::timestamptz,'user:t') ON CONFLICT (tenant_id, shift_id, measured_at) DO NOTHING")


def fixture(tenant):
    veh, shift = uid(), uid()
    with tx(tenant) as c:
        c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'x','{}')", (tenant, veh))
        c.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) "
                  "VALUES (%s,%s,%s,%s,now(),now()+interval '8 hours','Europe/Berlin',0,'PLANNED')", (tenant, shift, uid(), veh))
    return veh, shift


class Positionen(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm334-a'); seed_tenant(B, 'm334-b')
        cls.va, cls.sa = fixture(A); cls.vb, cls.sb = fixture(B)

    def test_messzeit_und_empfangszeit_getrennt_letzte_position_nach_messzeit(self):
        va, sa = fixture(A)
        with tx(A) as c:
            c.execute(INS, (A, uid(), va, sa, '2026-12-01T06:10:00Z', '2026-12-01T06:10:02Z'))
            c.execute(INS, (A, uid(), va, sa, '2026-12-01T06:05:00Z', '2026-12-01T06:30:00Z'))      # später empfangen, aber älter gemessen
            last = c.execute("SELECT to_char(measured_at at time zone 'UTC','HH24:MI'), to_char(received_at at time zone 'UTC','HH24:MI') FROM taktovia.position_sample "
                             "WHERE vehicle_id=%s ORDER BY measured_at DESC LIMIT 1", (va,)).fetchone()
            self.assertEqual(last, ('06:10', '06:10'))
            plan = "\n".join(r[0] for r in c.execute("EXPLAIN SELECT * FROM taktovia.position_sample WHERE tenant_id=%s AND vehicle_id=%s ORDER BY measured_at DESC LIMIT 1", (A, va)))
            self.assertIn('position_sample', plan)

    def test_erneute_sendung_derselben_messzeit_erzeugt_keine_zweite_zeile(self):
        with tx(A) as c:
            for _ in range(3):
                c.execute(INS, (A, uid(), self.va, self.sa, '2026-12-02T06:00:00Z', '2026-12-02T06:00:01Z'))
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.position_sample WHERE shift_id=%s AND measured_at='2026-12-02T06:00:00Z'", (self.sa,)).fetchone()[0], 1)
        # ohne ON CONFLICT scheitert die zweite Zeile an der Eindeutigkeit
        with tx(A) as c:
            c.execute(INS.replace(' ON CONFLICT (tenant_id, shift_id, measured_at) DO NOTHING', ''), (A, uid(), self.va, self.sa, '2026-12-03T06:00:00Z', '2026-12-03T06:00:01Z'))
            with self.assertRaises(psycopg.errors.UniqueViolation):
                c.execute(INS.replace(' ON CONFLICT (tenant_id, shift_id, measured_at) DO NOTHING', ''), (A, uid(), self.va, self.sa, '2026-12-03T06:00:00Z', '2026-12-03T06:00:02Z'))

    def test_parallele_wiederholung_genau_eine_zeile(self):
        barrier = threading.Barrier(8)

        def worker():
            c = runtime()
            try:
                set_tenant(c, A); barrier.wait()
                c.execute(INS, (A, uid(), self.va, self.sa, '2026-12-04T06:00:00Z', '2026-12-04T06:00:01Z')); c.commit()
            finally:
                c.close()
        ts = [threading.Thread(target=worker) for _ in range(8)]
        [t.start() for t in ts]; [t.join(30) for t in ts]
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.position_sample WHERE shift_id=%s AND measured_at='2026-12-04T06:00:00Z'", (self.sa,)).fetchone()[0], 1)

    def test_loeschen_fuer_den_loeschlauf_aendern_bleibt_verboten_und_fremder_mandant_unberuehrt(self):
        pid = uid()
        with tx(B) as c: c.execute(INS, (B, pid, self.vb, self.sb, '2026-12-05T06:00:00Z', '2026-12-05T06:00:01Z'))
        with tx(A) as c:
            c.execute(INS, (A, uid(), self.va, self.sa, '2026-12-05T06:00:00Z', '2026-12-05T06:00:01Z'))
            with self.assertRaises((psycopg.errors.CheckViolation, psycopg.errors.InsufficientPrivilege)): c.execute("UPDATE taktovia.position_sample SET accuracy_m = 1 WHERE shift_id=%s", (self.sa,))
        with tx(A) as c:
            c.execute("DELETE FROM taktovia.position_sample WHERE shift_id=%s AND measured_at='2026-12-05T06:00:00Z'", (self.sa,))       # Löschlauf der Aufbewahrungsfrist
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.position_sample WHERE id=%s", (pid,)).fetchone()[0], 0, 'Zeile des Mandanten B ist für A unsichtbar')
            self.assertEqual(c.execute("DELETE FROM taktovia.position_sample WHERE id=%s", (pid,)).rowcount, 0, 'und nicht löschbar')
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.position_sample WHERE id=%s", (pid,)).fetchone()[0], 1)


if __name__ == '__main__':
    unittest.main()
