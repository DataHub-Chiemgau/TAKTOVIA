"""WP-M4-32 (DOC-009): Dublettentreffer und Evidenzverknüpfung. Der Hashvergleich gilt nur innerhalb des Mandanten (RLS), Treffer werden nur einmal entschieden, Evidenzverknüpfungen sind append-only,
ein Beleg ist Evidenz höchstens eines Ereignisses und ein Ereignis hat genau einen PRIMARY. Alles unter der echten Runtime-Rolle."""
import json
import unittest
import psycopg
from helpers import admin, tx, uid, seed_tenant
from m4_belege_helpers import legal_entity, stored_document, SHA

A, B = uid(), uid()


def candidate(c, tenant, newer, older, certainty='POSSIBLE', state='OFFEN', kind='FACTS'):
    cid = uid()
    decided = state != 'OFFEN'
    c.execute("INSERT INTO taktovia.duplicate_candidate(tenant_id,id,document_id,other_document_id,match_kind,certainty,score,basis,state,decided_by,decided_at,decision_reason) "
              "VALUES (%s,%s,%s,%s,%s,%s,80,%s::jsonb,%s,%s,CASE WHEN %s THEN now() END,%s)",
              (tenant, cid, newer, older, kind, certainty, json.dumps({'rule': 'FACTS'}), state, 'system' if decided else None, decided, 'Begründung' if decided else None))
    return cid


def event(tenant, le, doc=None):
    eid = uid()
    with admin() as c:
        c.execute("INSERT INTO taktovia.economic_event(tenant_id,id,legal_entity_id,source_system,source_ref,event_type,scenario,category,currency,net_minor,tax_minor,gross_minor,recoverable_tax_minor,tax_rule_revision,occurred_at,booked_at,document_id) "
                  "VALUES (%s,%s,%s,'EXPENSE',%s,'EXPENSE_COST','ACTUAL','FUEL','EUR',100,0,100,0,'T',now(),now(),%s)", (tenant, eid, le, str(eid), doc))
    return eid


def link(c, tenant, ev, doc, role='ADDITIONAL', decision='AUTO_SAFE'):
    c.execute("INSERT INTO taktovia.evidence_link(tenant_id,id,event_id,document_id,role,decision,decided_by) VALUES (%s,%s,%s,%s,%s,%s,'system')", (tenant, uid(), ev, doc, role, decision))


class Dubletten(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm432-a'); seed_tenant(B, 'm432-b')
        cls.le_a, cls.le_b = legal_entity(A), legal_entity(B)

    def test_hashvergleich_ist_mandantengetrennt(self):
        da1 = stored_document(A, self.le_a); da2 = stored_document(A, self.le_a); db1 = stored_document(B, self.le_b)
        with tx(A) as c:   # gleicher Hash: im eigenen Mandanten sichtbar, der Beleg des anderen Mandanten nicht
            ids = {r[0] for r in c.execute('SELECT id FROM taktovia.expense_document WHERE original_sha256=%s', (SHA,)).fetchall()}
        self.assertTrue({da1, da2} <= ids); self.assertNotIn(db1, ids)
        with tx(B) as c:
            ids = {r[0] for r in c.execute('SELECT id FROM taktovia.expense_document WHERE original_sha256=%s', (SHA,)).fetchall()}
            self.assertNotIn(da1, ids); self.assertIn(db1, ids)
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.duplicate_candidate').fetchone()[0], 0)
        # ein Treffer über die Mandantengrenze ist weder anlegbar noch lesbar (zusammengesetzte Fremdschlüssel)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.ForeignKeyViolation): candidate(c, A, da2, db1)
        with tx(B) as c:
            with self.assertRaises((psycopg.errors.ForeignKeyViolation, psycopg.errors.InsufficientPrivilege)): candidate(c, B, db1, da1)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.InsufficientPrivilege): candidate(c, B, db1, db1)   # Mandantenschlüssel gefälscht

    def test_treffer_je_paar_einmal_und_nur_einmal_entscheidbar(self):
        d1 = stored_document(A, self.le_a); d2 = stored_document(A, self.le_a)
        with tx(A) as c: cid = candidate(c, A, d2, d1)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.UniqueViolation): candidate(c, A, d2, d1)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): candidate(c, A, d2, d2)   # nie ein Beleg mit sich selbst
        with tx(A) as c:   # entscheiden: OFFEN -> EIGENER_VORGANG
            c.execute("UPDATE taktovia.duplicate_candidate SET state='EIGENER_VORGANG', decided_by='user:x', decided_at=now(), decision_reason='anderer Vorgang' WHERE id=%s", (cid,))
        with tx(A) as c:   # entschieden bleibt entschieden (auch Punkte und Grundlage); nie löschen
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.duplicate_candidate SET state='GLEICHE_EVIDENZ' WHERE id=%s", (cid,))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.duplicate_candidate SET score=1 WHERE id=%s", (cid,))
        d3 = stored_document(A, self.le_a)
        with tx(A) as c: cid2 = candidate(c, A, d3, d1)
        with tx(A) as c:   # offen: Neubewertung mit neuen Fachdaten ist zulässig, das Paar bleibt unveränderlich
            c.execute("UPDATE taktovia.duplicate_candidate SET score=95 WHERE id=%s", (cid2,))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.duplicate_candidate SET other_document_id=%s WHERE id=%s", (d2, cid2))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.InsufficientPrivilege): c.execute("DELETE FROM taktovia.duplicate_candidate WHERE id=%s", (cid2,))
        # entschieden ohne Entscheider, offen mit Entscheider und sicher ohne Evidenz sind unmöglich
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.duplicate_candidate SET state='GLEICHE_EVIDENZ' WHERE id=%s", (cid2,))
        d4 = stored_document(A, self.le_a)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): candidate(c, A, d4, d1, certainty='SAFE', state='OFFEN')

    def test_evidenz_append_only_genau_ein_primary_und_ein_beleg_ein_ereignis(self):
        d1 = stored_document(A, self.le_a); d2 = stored_document(A, self.le_a); d3 = stored_document(A, self.le_a)
        e1 = event(A, self.le_a, d1); e2 = event(A, self.le_a, d3)
        with tx(A) as c:
            link(c, A, e1, d1, 'PRIMARY', 'POSTING'); link(c, A, e1, d2)
        with tx(A) as c:   # zweiter PRIMARY je Ereignis
            with self.assertRaises(psycopg.errors.UniqueViolation): link(c, A, e1, d3, 'PRIMARY', 'POSTING')
        with tx(A) as c:   # ein Beleg ist Evidenz höchstens eines Ereignisses (genau eine Kostenwirkung)
            with self.assertRaises(psycopg.errors.UniqueViolation): link(c, A, e2, d2)
        with tx(A) as c:   # PRIMARY nur durch das Posting, Zusatzevidenz nie durch das Posting
            with self.assertRaises(psycopg.errors.CheckViolation): link(c, A, e2, d3, 'PRIMARY', 'AUTO_SAFE')
        for stmt in ("UPDATE taktovia.evidence_link SET decided_by='x' WHERE document_id=%s", "DELETE FROM taktovia.evidence_link WHERE document_id=%s"):
            with tx(A) as c:
                with self.assertRaises((psycopg.errors.InsufficientPrivilege, psycopg.errors.CheckViolation)): c.execute(stmt, (d2,))
        # Mandantentrennung: fremdes Ereignis und fremder Beleg sind nicht verknüpfbar, nichts lesbar
        db1 = stored_document(B, self.le_b)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.ForeignKeyViolation): link(c, A, e2, db1)
        with tx(B) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.evidence_link').fetchone()[0], 0)
            with self.assertRaises((psycopg.errors.ForeignKeyViolation, psycopg.errors.InsufficientPrivilege)): link(c, B, e1, db1)

    def test_schwelle_ist_begrenzt(self):
        with tx(A) as c:
            c.execute("INSERT INTO taktovia.finance_policy(tenant_id,updated_by) VALUES (%s,'u') ON CONFLICT DO NOTHING", (A,))
        for bad in (0, 101):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.finance_policy SET receipt_duplicate_threshold=%s WHERE tenant_id=%s", (bad, A))
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT receipt_duplicate_threshold FROM taktovia.finance_policy").fetchone()[0], 60)


if __name__ == '__main__':
    unittest.main()
