"""WP-M1-22 / LH-I.7-CFG-006: Vier-Augen-Regel, Überlappungsverbot der publizierten Revisionen eines Scopes (Kap. 04 §4.5) und die einzige
zulässige Änderung einer publizierten Zeile (Vorgängerintervall schließen). Geprüft unter der echten Runtime-Rolle, auch mit parallelen Transaktionen."""
import json
import threading
import unittest

import psycopg
from helpers import admin, runtime, seed_tenant, set_tenant, tx, uid

A, B = uid(), uid()
VALUES = json.dumps({'max_wait_min': 20})


def draft(c, tenant, scope, revision, start, end=None, kind='REGION', created_by='user:alice', rolled_back_from=None):
    rid = uid()
    c.execute("""INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,effective_to,created_by,reason,rolled_back_from)
                 VALUES (%s,%s,%s,%s,%s,'1',%s::jsonb,%s,%s,%s,'Testaufbau',%s)""", (tenant, rid, kind, scope, revision, VALUES, start, end, created_by, rolled_back_from))
    return rid


def advance(tenant, rid, to, approver='user:bob'):
    extra = {'APPROVED': ", approved_by = %s", 'PUBLISHED': ', published_at = now()'}.get(to, '')
    with tx(tenant) as c:
        c.execute(f"UPDATE taktovia.config_revision SET state = %s{extra} WHERE tenant_id = %s AND id = %s",
                  (to, tenant, rid) if to != 'APPROVED' else (to, approver, tenant, rid))


def publish(tenant, rid):
    for to in ('VALIDATED', 'APPROVED', 'PUBLISHED'):
        advance(tenant, rid, to)


class Publikation(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'pub-a'); seed_tenant(B, 'pub-b')

    def new_draft(self, scope, rev, start, end=None, tenant=A, **kw):
        with tx(tenant) as c:
            return draft(c, tenant, scope, rev, start, end, **kw)

    def test_vier_augen_freigabe_durch_den_ersteller_ist_in_der_datenbank_ausgeschlossen(self):
        rid = self.new_draft(uid(), 1, '2030-01-01T00:00:00Z')
        advance(A, rid, 'VALIDATED')
        with self.assertRaises(psycopg.errors.CheckViolation):
            advance(A, rid, 'APPROVED', approver='user:alice')    # Ersteller = Freigeber
        advance(A, rid, 'APPROVED', approver='user:bob')           # zweite Person
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT approved_by FROM taktovia.config_revision WHERE id=%s", (rid,)).fetchone()[0], 'user:bob')

    def test_ueberlappende_publizierte_revisionen_desselben_scopes_sind_ausgeschlossen(self):
        scope = uid()
        r1 = self.new_draft(scope, 1, '2030-01-01T00:00:00Z', '2030-06-01T00:00:00Z')
        r2 = self.new_draft(scope, 2, '2030-03-01T00:00:00Z')
        r3 = self.new_draft(scope, 3, '2030-06-01T00:00:00Z')     # grenzt an r1 an: zulässig (halboffen)
        publish(A, r1)
        for to in ('VALIDATED', 'APPROVED'):
            advance(A, r2, to)
        with self.assertRaises(psycopg.errors.ExclusionViolation):
            advance(A, r2, 'PUBLISHED')
        publish(A, r3)
        # anderer Scope und anderer Mandant sind unabhängig
        other = self.new_draft(uid(), 1, '2030-03-01T00:00:00Z'); publish(A, other)
        with tx(B) as c:
            publish_b = draft(c, B, scope, 1, '2030-03-01T00:00:00Z')
        publish(B, publish_b)

    def test_vorgaengerintervall_schliessen_ist_die_einzige_aenderung_einer_publizierten_zeile(self):
        scope = uid()
        r1 = self.new_draft(scope, 1, '2031-01-01T00:00:00Z'); publish(A, r1)
        r2 = self.new_draft(scope, 2, '2031-02-01T00:00:00Z')
        close = "UPDATE taktovia.config_revision SET effective_to = '2031-02-01T00:00:00Z' WHERE id = %s"
        with self.assertRaises(psycopg.errors.CheckViolation):    # ohne freigegebene Nachfolgerrevision
            with tx(A) as c: c.execute(close, (r1,))
        for to in ('VALIDATED', 'APPROVED'): advance(A, r2, to)
        with self.assertRaises(psycopg.errors.CheckViolation):    # nicht auf den Beginn des Nachfolgers
            with tx(A) as c: c.execute("UPDATE taktovia.config_revision SET effective_to = '2031-03-01T00:00:00Z' WHERE id = %s", (r1,))
        with self.assertRaises(psycopg.errors.CheckViolation):    # weitere Spalten sind fest
            with tx(A) as c: c.execute("UPDATE taktovia.config_revision SET effective_to = '2031-02-01T00:00:00Z', reason = 'Heimlich' WHERE id = %s", (r1,))
        with tx(A) as c:
            before = c.execute("SELECT row_version FROM taktovia.config_revision WHERE id=%s", (r1,)).fetchone()[0]
            c.execute(close, (r1,))
            self.assertEqual(c.execute("SELECT row_version FROM taktovia.config_revision WHERE id=%s", (r1,)).fetchone()[0], before + 1)
        advance(A, r2, 'PUBLISHED')
        with self.assertRaises(psycopg.errors.CheckViolation):    # das Ende darf nicht wieder verlängert werden
            with tx(A) as c: c.execute("UPDATE taktovia.config_revision SET effective_to = NULL WHERE id = %s", (r1,))
        with admin() as c:                                         # auch Administration nicht
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("UPDATE taktovia.config_revision SET values_json = '{}' WHERE id = %s", (r1,))

    def test_parallele_publikation_ueberlappender_intervalle_genau_eine(self):  # Pflicht-Negativtest zu §4.5
        scope = uid()
        r1 = self.new_draft(scope, 1, '2032-01-01T00:00:00Z')
        r2 = self.new_draft(scope, 2, '2032-02-01T00:00:00Z')
        for r in (r1, r2):
            for to in ('VALIDATED', 'APPROVED'): advance(A, r, to)
        barrier = threading.Barrier(2); results = {}

        def run(name, rid):
            c = runtime()
            try:
                set_tenant(c, A)
                barrier.wait(10)
                try:
                    c.execute("UPDATE taktovia.config_revision SET state='PUBLISHED', published_at=now() WHERE id=%s", (rid,))
                    threading.Event().wait(0.4)                    # festschreiben erst nach dem Versuch der anderen Transaktion
                    c.commit(); results[name] = 'ok'
                except psycopg.errors.ExclusionViolation:
                    c.rollback(); results[name] = 'exclusion'
            finally:
                c.close()
        ts = [threading.Thread(target=run, args=(n, r)) for n, r in (('a', r1), ('b', r2))]
        [t.start() for t in ts]; [t.join(30) for t in ts]
        self.assertEqual(sorted(results.values()), ['exclusion', 'ok'], results)
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.config_revision WHERE scope_id=%s AND state='PUBLISHED'", (scope,)).fetchone()[0], 1)

    def test_gleichzeitige_publikation_in_a_und_b_mit_identischer_scope_id_ohne_gegenseitige_wirkung(self):
        scope = uid()
        with tx(A) as c: ra = draft(c, A, scope, 1, '2033-01-01T00:00:00Z')
        with tx(B) as c: rb = draft(c, B, scope, 1, '2033-01-01T00:00:00Z')
        barrier = threading.Barrier(2); results = {}

        def run(name, tenant, rid):
            try:
                barrier.wait(10); publish(tenant, rid); results[name] = 'ok'
            except Exception as e:  # noqa: BLE001
                results[name] = repr(e)
        ts = [threading.Thread(target=run, args=a) for a in (('a', A, ra), ('b', B, rb))]
        [t.start() for t in ts]; [t.join(30) for t in ts]
        self.assertEqual(results, {'a': 'ok', 'b': 'ok'})

    def test_rollback_verweis_ist_fest_und_gehoert_zum_selben_mandanten(self):
        scope = uid()
        r1 = self.new_draft(scope, 1, '2034-01-01T00:00:00Z')
        with tx(A) as c:
            r2 = draft(c, A, scope, 2, '2034-02-01T00:00:00Z', rolled_back_from=r1)
        with self.assertRaises(psycopg.errors.CheckViolation):    # Herkunft nachträglich ändern
            with tx(A) as c: c.execute("UPDATE taktovia.config_revision SET rolled_back_from = NULL WHERE id = %s", (r2,))
        with self.assertRaises(psycopg.errors.ForeignKeyViolation):   # Revision eines anderen Mandanten
            with tx(B) as c:
                draft(c, B, uid(), 1, '2034-01-01T00:00:00Z', rolled_back_from=r1)


if __name__ == '__main__':
    unittest.main()
