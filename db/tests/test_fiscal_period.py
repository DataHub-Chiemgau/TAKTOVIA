"""WP-M4-36: Fiskalperiode und Gegenereignis auf Datenbankebene unter der Runtime-Rolle: Überlappungsverbot je Rechtsträger (auch parallel), geschlossene Periode unveränderlich, Gegenereignis nur mit
Gegenvorzeichen, nie über den Ursprung hinaus, nie ein Gegenereignis eines Gegenereignisses; die Journalzeile kennt ihre Rolle."""
import datetime
import threading
import unittest
import psycopg
from helpers import admin, tx, uid, seed_tenant
from m4_belege_helpers import legal_entity

A = uid()
D = datetime.date


def period(c, tenant, le, starts, ends, closed=False):
    pid = uid()
    c.execute("INSERT INTO taktovia.fiscal_period(tenant_id,id,legal_entity_id,starts_on,ends_on,closed_at,created_by) VALUES (%s,%s,%s,%s,%s,%s,'user:t')", (tenant, pid, le, starts, ends, datetime.datetime.now(datetime.timezone.utc) if closed else None))
    return pid


def event(c, tenant, le, ref=None, net=10000, tax=1900, recoverable=1900, reverses=None, cur='EUR', scenario='ACTUAL'):
    eid = uid()
    c.execute("INSERT INTO taktovia.economic_event(tenant_id,id,legal_entity_id,source_system,source_ref,event_type,scenario,category,currency,net_minor,tax_minor,gross_minor,recoverable_tax_minor,tax_rule_revision,occurred_at,booked_at,reverses_id) "
              "VALUES (%s,%s,%s,'EXPENSE',%s,'EXPENSE_COST',%s,'FUEL',%s,%s,%s,%s,%s,'T',now(),now(),%s)", (tenant, eid, le, ref or str(eid), scenario, cur, net, tax, net + tax, recoverable, reverses))
    return eid


class Fiskalperiode(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm436-a')
        cls.le, cls.le2 = legal_entity(A), legal_entity(A)

    def test_ueberlappende_perioden_werden_abgewiesen_benachbarte_nicht(self):
        le = legal_entity(A)
        with tx(A) as c:
            period(c, A, le, D(2026, 9, 1), D(2026, 10, 1)); period(c, A, le, D(2026, 10, 1), D(2026, 11, 1))   # benachbart (Ende exklusiv)
        for starts, ends in ((D(2026, 9, 15), D(2026, 10, 15)), (D(2026, 9, 1), D(2026, 10, 1)), (D(2026, 8, 1), D(2026, 12, 1)), (D(2026, 9, 30), D(2026, 10, 2))):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.ExclusionViolation, msg=f'{starts}-{ends}'): period(c, A, le, starts, ends)
        with tx(A) as c:   # anderer Rechtsträger: gleiche Zeiträume sind erlaubt
            period(c, A, self.le2, D(2026, 9, 1), D(2026, 10, 1))

    def test_parallele_ueberlappende_anlage_genau_eine(self):
        le = legal_entity(A); barrier = threading.Barrier(2); out = []

        def run(starts, ends):
            with tx(A) as c:
                barrier.wait()
                try:
                    period(c, A, le, starts, ends); out.append('ok')
                except psycopg.errors.ExclusionViolation:
                    out.append('overlap')
        ts = [threading.Thread(target=run, args=a) for a in ((D(2027, 1, 1), D(2027, 2, 1)), (D(2027, 1, 15), D(2027, 2, 15)))]; [t.start() for t in ts]; [t.join(30) for t in ts]
        self.assertEqual(sorted(out), ['ok', 'overlap'])

    def test_abschluss_ist_endgueltig_und_traegt_den_abschliessenden(self):
        le = legal_entity(A)
        with tx(A) as c: pid = period(c, A, le, D(2026, 3, 1), D(2026, 4, 1))
        with tx(A) as c:
            self.assertEqual(c.execute("UPDATE taktovia.fiscal_period SET closed_at=clock_timestamp(), closed_by='user:fin' WHERE id=%s AND closed_at IS NULL", (pid,)).rowcount, 1)
        for stmt in ("UPDATE taktovia.fiscal_period SET closed_at=NULL WHERE id=%s", "UPDATE taktovia.fiscal_period SET closed_by='user:x' WHERE id=%s", "DELETE FROM taktovia.fiscal_period WHERE id=%s"):
            with tx(A) as c:
                with self.assertRaises((psycopg.errors.RaiseException, psycopg.errors.InsufficientPrivilege), msg=stmt): c.execute(stmt, (pid,))

    def test_gegenereignis_regeln(self):
        le = self.le
        with tx(A) as c: orig = event(c, A, le)       # 119,00 brutto, 100,00 Kostenbasis
        with tx(A) as c: part = event(c, A, le, net=-2000, tax=-380, recoverable=-380, reverses=orig)   # Gutschrift 23,80 brutto
        with tx(A) as c: event(c, A, le, net=-8000, tax=-1520, recoverable=-1520, reverses=orig)         # der Rest: Summe = Ursprung, genau aufgehoben
        cases = {
            'noch ein Cent mehr': dict(net=-1, tax=0, recoverable=0, reverses=orig),
            'gleiches Vorzeichen': dict(net=100, tax=0, recoverable=0, reverses=orig),
            'Gegenereignis eines Gegenereignisses': dict(net=-100, tax=0, recoverable=0, reverses=part),
            'Steuer mit falschem Vorzeichen': dict(net=-3000, tax=500, recoverable=0, reverses=orig),
        }
        for name, kw in cases.items():
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.CheckViolation, msg=name): event(c, A, le, **kw)
        with tx(A) as c:   # anderer Rechtsträger, andere Währung
            with self.assertRaises(psycopg.errors.CheckViolation): event(c, A, self.le2, net=-100, tax=0, recoverable=0, reverses=orig)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): event(c, A, le, net=-100, tax=0, recoverable=0, reverses=orig, cur='USD')
        with tx(A) as c:   # das Gegenereignis ist selbst unveränderlich
            with self.assertRaises((psycopg.errors.CheckViolation, psycopg.errors.InsufficientPrivilege)): c.execute("UPDATE taktovia.economic_event SET category='X' WHERE id=%s", (part,))
        with tx(A) as c:
            with self.assertRaises((psycopg.errors.CheckViolation, psycopg.errors.InsufficientPrivilege)): c.execute("DELETE FROM taktovia.economic_event WHERE id=%s", (part,))

    def test_parallele_gegenereignisse_ueberschreiten_den_ursprung_nie(self):
        le = self.le
        with tx(A) as c: orig = event(c, A, le, net=10000, tax=0, recoverable=0)
        barrier = threading.Barrier(4); out = []

        def run():
            with tx(A) as c:
                barrier.wait()
                try:
                    event(c, A, le, net=-4000, tax=0, recoverable=0, reverses=orig); out.append('ok')
                except psycopg.errors.CheckViolation:
                    out.append('over')
        ts = [threading.Thread(target=run) for _ in range(4)]; [t.start() for t in ts]; [t.join(30) for t in ts]
        self.assertEqual(sorted(out), ['ok', 'ok', 'over', 'over'], 'zweimal 40,00 passen in 100,00, ein drittes und viertes nicht')
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT sum(gross_minor) FROM taktovia.economic_event WHERE reverses_id=%s", (orig,)).fetchone()[0], -8000)

    def test_journalzeile_rolle_hat_festen_wertebereich(self):
        with tx(A) as c:
            jid = uid()
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("INSERT INTO taktovia.journal_line(tenant_id,id,journal_id,account_code,side,amount_minor,account_role) VALUES (%s,%s,%s,'6600','D',1,'UNBEKANNT')", (A, uid(), jid))


if __name__ == '__main__':
    unittest.main()
