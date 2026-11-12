"""WP-M4-39 (FCO-001): Kostenklassifikation am Kostenereignis. Die Klassifikation (cost_behavior, attribution) wird beim Posting gesetzt und ist danach unveränderlich; Umklassifikation nur als
Gegenereignis plus Neubuchung (höchstens eine Neubuchung je Ursprung). Alles unter der echten Runtime-Rolle."""
import unittest
import psycopg
from helpers import admin, tx, uid, seed_tenant
from m4_belege_helpers import legal_entity

A = uid()


def event(c, tenant, le, behavior=None, attribution=None, category='ENERGY', gross=10000, reverses=None, reclassified_from=None, source_ref=None, event_type='MANUAL_COST'):
    eid = uid()
    c.execute("INSERT INTO taktovia.economic_event(tenant_id,id,legal_entity_id,source_system,source_ref,event_type,scenario,cost_behavior,attribution,category,currency,net_minor,tax_minor,gross_minor,"
              "tax_rule_revision,occurred_at,booked_at,reverses_id,reclassified_from_id) VALUES (%s,%s,%s,'MANUAL',%s,%s,'ACTUAL',%s,%s,%s,'EUR',%s,0,%s,'T',now(),now(),%s,%s)",
              (tenant, eid, le, source_ref or str(eid), event_type, behavior, attribution, category, gross, gross, reverses, reclassified_from))
    return eid


class Klassifikation(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm439-a')
        cls.le = legal_entity(A)

    def test_klassifikation_nur_per_gegenereignis_aenderbar(self):
        with tx(A) as c: e = event(c, A, self.le, 'VARIABLE', 'DIRECT')
        # direkte Änderung der Klassifikation eines gebuchten Ereignisses scheitert (Unveränderlichkeit), auch Löschen
        for stmt in ("UPDATE taktovia.economic_event SET cost_behavior='FIXED' WHERE id=%s", "UPDATE taktovia.economic_event SET attribution='ALLOCATED' WHERE id=%s", "DELETE FROM taktovia.economic_event WHERE id=%s"):
            with tx(A) as c:
                with self.assertRaises((psycopg.errors.InsufficientPrivilege, psycopg.errors.CheckViolation, psycopg.errors.RaiseException)): c.execute(stmt, (e,))
        # Umklassifikation: Gegenereignis (reverses_id, negative Beträge) und Neubuchung mit neuer Klassifikation
        with tx(A) as c:
            rev = event(c, A, self.le, 'VARIABLE', 'DIRECT', gross=-10000, reverses=e, event_type='REVERSAL')
            new = event(c, A, self.le, 'FIXED', 'DIRECT', reclassified_from=e, event_type='RECLASSIFICATION')
        with tx(A) as c:
            total = c.execute("SELECT sum(cost_basis_minor) FROM taktovia.economic_event WHERE id IN (%s,%s,%s)", (e, rev, new)).fetchone()[0]
            self.assertEqual(total, 10000, 'Original und Gegenereignis heben sich auf, wirksam bleibt die Neubuchung')
        # höchstens eine Neubuchung je Ursprung (konkurrierende Umklassifikation endet an der Datenbank)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.UniqueViolation): event(c, A, self.le, 'MIXED', 'DIRECT', reclassified_from=e, event_type='RECLASSIFICATION')

    def test_klassifikation_achsen_gemeinsam_oder_gar_nicht(self):
        for b, a in (('FIXED', None), (None, 'DIRECT')):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.CheckViolation): event(c, A, self.le, b, a)
        for b, a in (('SEMI', 'DIRECT'), ('FIXED', 'SPLIT')):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.CheckViolation): event(c, A, self.le, b, a)
        with tx(A) as c: event(c, A, self.le)   # nicht klassifiziert bleibt zulässig (wird getrennt ausgewiesen)
        with tx(A) as c: event(c, A, self.le, 'MIXED', 'ALLOCATED')

    def test_standardindex_und_kostenobjektbezug(self):
        with admin() as c:
            idx = c.execute("SELECT indexdef FROM pg_indexes WHERE schemaname='taktovia' AND tablename='economic_event'").fetchall()
        self.assertTrue(any('(tenant_id, legal_entity_id, booked_at)' in d[0] for d in idx), idx)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.ForeignKeyViolation):
                c.execute("INSERT INTO taktovia.economic_event(tenant_id,id,legal_entity_id,source_system,source_ref,event_type,scenario,category,currency,net_minor,tax_minor,gross_minor,tax_rule_revision,occurred_at,booked_at,cost_object_id) "
                          "VALUES (%s,%s,%s,'MANUAL','x','MANUAL_COST','ACTUAL','ENERGY','EUR',1,0,1,'T',now(),now(),%s)", (A, uid(), self.le, uid()))


if __name__ == '__main__':
    unittest.main()
