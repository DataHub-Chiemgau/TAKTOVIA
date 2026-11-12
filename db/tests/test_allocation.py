"""WP-M4-35 (DOC-011): Belegaufteilung auf Datenbankebene unter der Runtime-Rolle. Umlage centgenau und final unveränderlich (Summe = Basis, gebucht = unveränderlich), Zielobjekte nur des
Rechtsträgers des Ereignisses, genau eine aktive Umlagerevision je Ereignis (auch bei Parallelität) und der Beleg-Split vor dem Posting."""
import threading
import unittest
import psycopg
from helpers import admin, tx, uid, seed_tenant
from m4_belege_helpers import legal_entity, stored_document

A, B = uid(), uid()


def cost_object(tenant, le, kind='COST_CENTER', ref=None, active=True):
    oid = uid()
    with admin() as c:
        c.execute("INSERT INTO taktovia.cost_object(tenant_id,id,legal_entity_id,kind,business_ref) VALUES (%s,%s,%s,%s,%s)", (tenant, oid, le, kind, ref or str(oid)))
        if not active:
            c.execute("UPDATE taktovia.cost_object SET active=false WHERE tenant_id=%s AND id=%s", (tenant, oid))
    return oid


def event(c, tenant, le, gross=10000, tax=0, recoverable=0):
    eid = uid()
    c.execute("INSERT INTO taktovia.economic_event(tenant_id,id,legal_entity_id,source_system,source_ref,event_type,scenario,category,currency,net_minor,tax_minor,gross_minor,recoverable_tax_minor,tax_rule_revision,occurred_at,booked_at) "
              "VALUES (%s,%s,%s,'EXPENSE',%s,'EXPENSE_COST','ACTUAL','FUEL','EUR',%s,%s,%s,%s,'T',now(),now())", (tenant, eid, le, str(eid), gross - tax, tax, gross, recoverable))
    return eid


def new_set(c, tenant, le, eid, revision=1, basis=10000):
    sid = uid()
    c.execute("INSERT INTO taktovia.allocation_set(tenant_id,id,event_id,revision,legal_entity_id,currency,basis_minor,algorithm_version,driver_snapshot,state) VALUES (%s,%s,%s,%s,%s,'EUR',%s,'LRM-1','{}','DRAFT')",
              (tenant, sid, eid, revision, le, basis))
    return sid


def add_line(c, tenant, sid, obj, amount, weight=0):
    c.execute("INSERT INTO taktovia.allocation_line(tenant_id,id,allocation_id,cost_object_id,amount_minor,weight) VALUES (%s,%s,%s,%s,%s,%s)", (tenant, uid(), sid, obj, amount, weight))


def post(c, sid):
    c.execute("UPDATE taktovia.allocation_set SET state='POSTED' WHERE id=%s", (sid,))


class Belegaufteilung(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm435-a'); seed_tenant(B, 'm435-b')
        cls.le, cls.le2, cls.le_b = legal_entity(A), legal_entity(A), legal_entity(B)
        cls.o1, cls.o2, cls.o3 = (cost_object(A, cls.le) for _ in range(3))

    def posted_set(self, eid=None, revision=1, split=(3334, 3333, 3333)):
        with tx(A) as c:
            eid = eid or event(c, A, self.le)
            s = new_set(c, A, self.le, eid, revision, sum(split))
            for o, a in zip((self.o1, self.o2, self.o3), split): add_line(c, A, s, o, a)
            post(c, s)
        return eid, s

    def test_posted_allokation_update_delete_scheitert_und_summe_ungleich_basis_scheitert(self):  # AK 1, 7
        eid, s = self.posted_set()
        for stmt in ("UPDATE taktovia.allocation_set SET basis_minor=basis_minor+1 WHERE id=%s", "UPDATE taktovia.allocation_set SET state='DRAFT' WHERE id=%s", "DELETE FROM taktovia.allocation_set WHERE id=%s",
                     "UPDATE taktovia.allocation_line SET amount_minor=amount_minor+1 WHERE allocation_id=%s", "DELETE FROM taktovia.allocation_line WHERE allocation_id=%s"):
            with tx(A) as c:
                with self.assertRaises((psycopg.errors.CheckViolation, psycopg.errors.InsufficientPrivilege), msg=stmt): c.execute(stmt, (s,))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): add_line(c, A, s, cost_object(A, self.le), 1)   # keine Zeile an einer gebuchten Umlage
        # Summe ungleich Basis scheitert beim Buchen, auch um einen Cent
        for split in ((3334, 3333, 3332), (3334, 3333, 3334), (0, 0, 0)):
            with tx(A) as c:
                e = event(c, A, self.le); sid = new_set(c, A, self.le, e, 1, 10000)
                for o, a in zip((self.o1, self.o2, self.o3), split): add_line(c, A, sid, o, a)
                with self.assertRaises(psycopg.errors.CheckViolation, msg=str(split)): post(c, sid)
        with tx(A) as c:   # ohne Zeile
            sid = new_set(c, A, self.le, event(c, A, self.le), 1, 10000)
            with self.assertRaises(psycopg.errors.CheckViolation): post(c, sid)
        with tx(A) as c:   # direkt als POSTED anlegen ist nicht möglich; Basis muss der Kostenbasis des Ereignisses entsprechen
            e = event(c, A, self.le, gross=11900, tax=1900, recoverable=1900)
            with self.assertRaises(psycopg.errors.CheckViolation): new_set(c, A, self.le, e, 1, 11900)
        with tx(A) as c:
            new_set(c, A, self.le, event(c, A, self.le, gross=11900, tax=1900, recoverable=1900), 1, 10000)   # Kostenbasis = brutto - abziehbare Steuer
        with tx(A) as c:
            e = event(c, A, self.le)
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("INSERT INTO taktovia.allocation_set(tenant_id,id,event_id,revision,legal_entity_id,currency,basis_minor,algorithm_version,driver_snapshot,state) VALUES (%s,%s,%s,1,%s,'EUR',10000,'LRM-1','{}','POSTED')", (A, uid(), e, self.le))
        with tx(A) as c:   # Revisionen lückenlos
            e = event(c, A, self.le)
            with self.assertRaises(psycopg.errors.CheckViolation): new_set(c, A, self.le, e, 2, 10000)

    def test_fremdes_kostenobjekt_und_zweite_aktive_revision_scheitern(self):  # AK 4, konkurrierend
        other_entity = cost_object(A, self.le2); foreign = cost_object(B, self.le_b); inactive = cost_object(A, self.le, active=False)
        with tx(A) as c:
            e = event(c, A, self.le); s = new_set(c, A, self.le, e)
            with self.assertRaises(psycopg.errors.CheckViolation): add_line(c, A, s, other_entity, 100)   # anderer Rechtsträger
        with tx(A) as c:
            s = new_set(c, A, self.le, event(c, A, self.le))
            with self.assertRaises(psycopg.errors.CheckViolation): add_line(c, A, s, inactive, 100)
        with tx(A) as c:
            s = new_set(c, A, self.le, event(c, A, self.le))
            with self.assertRaises(psycopg.errors.ForeignKeyViolation): add_line(c, A, s, foreign, 100)   # Kostenobjekt eines anderen Mandanten (unter RLS nicht sichtbar)
        # Aktivierung: genau eine je Ereignis
        eid, s1 = self.posted_set()
        _, s2 = self.posted_set(eid=eid, revision=2)
        with tx(A) as c:
            c.execute("INSERT INTO taktovia.allocation_activation(tenant_id,event_id,allocation_id,revision,activated_by) VALUES (%s,%s,%s,1,'u')", (A, eid, s1))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.UniqueViolation): c.execute("INSERT INTO taktovia.allocation_activation(tenant_id,event_id,allocation_id,revision,activated_by) VALUES (%s,%s,%s,2,'u')", (A, eid, s2))
        with tx(A) as c:   # Wechsel auf die neuere Revision ist ein UPDATE; zurück und Löschen sind verboten
            c.execute("UPDATE taktovia.allocation_activation SET allocation_id=%s, revision=2 WHERE event_id=%s", (s2, eid))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.allocation_activation SET allocation_id=%s, revision=1 WHERE event_id=%s", (s1, eid))
        with tx(A) as c:
            with self.assertRaises((psycopg.errors.CheckViolation, psycopg.errors.InsufficientPrivilege)): c.execute("DELETE FROM taktovia.allocation_activation WHERE event_id=%s", (eid,))
        with tx(A) as c:   # nur gebuchte Revisionen werden aktiv; Revision und Ereignis müssen zum Satz passen
            e = event(c, A, self.le); draft = new_set(c, A, self.le, e)
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("INSERT INTO taktovia.allocation_activation(tenant_id,event_id,allocation_id,revision,activated_by) VALUES (%s,%s,%s,1,'u')", (A, e, draft))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.ForeignKeyViolation): c.execute("INSERT INTO taktovia.allocation_activation(tenant_id,event_id,allocation_id,revision,activated_by) VALUES (%s,%s,%s,1,'u')", (A, uid(), s1))

    def test_parallele_aktivierung_genau_eine_aktive_revision(self):  # konkurrierend (Geld)
        eid, s1 = self.posted_set(); _, s2 = self.posted_set(eid=eid, revision=2)
        barrier = threading.Barrier(2); out = []

        def run(sid, rev):
            with tx(A) as c:
                barrier.wait()
                try:
                    c.execute("INSERT INTO taktovia.allocation_activation(tenant_id,event_id,allocation_id,revision,activated_by) VALUES (%s,%s,%s,%s,'u')", (A, eid, sid, rev)); out.append('ok')
                except psycopg.errors.UniqueViolation:
                    out.append('dupl')
        ts = [threading.Thread(target=run, args=a) for a in ((s1, 1), (s2, 2))]; [t.start() for t in ts]; [t.join(30) for t in ts]
        self.assertEqual(sorted(out), ['dupl', 'ok'])
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.allocation_activation WHERE event_id=%s", (eid,)).fetchone()[0], 1)

    def test_split_plan_nur_fuer_zugeordnete_kostenobjekte_und_nur_im_entwurf(self):
        le = legal_entity(A); d = stored_document(A, le); o1, o2, o3 = (cost_object(A, le) for _ in range(3))
        with tx(A) as c:
            for o in (o1, o2):
                c.execute("INSERT INTO taktovia.expense_cost_assignment(tenant_id,document_id,cost_object_id,legal_entity_id,cost_object_kind,assigned_by) VALUES (%s,%s,%s,%s,'COST_CENTER','u')", (A, d, o, le))
            c.execute("INSERT INTO taktovia.expense_split(tenant_id,document_id,mode,policy_revision,reason,set_by) VALUES (%s,%s,'PERCENT','p-1','Aufteilung','u')", (A, d))
            for o in (o1, o2):
                c.execute("INSERT INTO taktovia.expense_split_line(tenant_id,document_id,cost_object_id,percent) VALUES (%s,%s,%s,50)", (A, d, o))
        with tx(A) as c:   # o3 ist dem Beleg nicht zugeordnet
            with self.assertRaises(psycopg.errors.ForeignKeyViolation): c.execute("INSERT INTO taktovia.expense_split_line(tenant_id,document_id,cost_object_id,percent) VALUES (%s,%s,%s,50)", (A, d, o3))
        with tx(A) as c:   # genau ein Treiber je Zeile
            c.execute("DELETE FROM taktovia.expense_split_line WHERE document_id=%s AND cost_object_id=%s", (d, o2))
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("INSERT INTO taktovia.expense_split_line(tenant_id,document_id,cost_object_id,percent,amount_minor) VALUES (%s,%s,%s,50,100)", (A, d, o2))
        with admin() as c:
            c.execute("UPDATE taktovia.expense_document SET state='IN_REVIEW' WHERE id=%s", (d,))
        with tx(A) as c:   # außerhalb des Entwurfs nicht mehr änderbar
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("DELETE FROM taktovia.expense_split_line WHERE document_id=%s", (d,))


if __name__ == '__main__':
    unittest.main()
