"""WP-M1-20 / LH-6.1-SYS-002, LH-I.7-CFG-003: Konfigurationsrevisionen in der Datenbank (Status, Pflichtangaben, Unveränderlichkeit
publizierter Revisionen, Eindeutigkeit der Revisionsnummer). Geprüft wird unter der echten Runtime-Rolle."""
import json
import threading
import unittest

import psycopg
from helpers import admin, runtime, seed_tenant, set_tenant, tx, uid

A, B = uid(), uid()
VALUES = json.dumps({'max_wait_min': 20})


def draft(c, tenant, scope_id, revision=1, values=VALUES, **over):
    rid = uid()
    cols = dict(scope_kind='TENANT', effective_from='2026-12-01T00:00:00Z', created_by='user:alice', reason='Anpassung der Wartezeit')
    cols.update(over)
    c.execute("""INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,effective_to,created_by,reason)
                 VALUES (%s,%s,%s,%s,%s,'1',%s::jsonb,%s,%s,%s,%s)""",
              (tenant, rid, cols['scope_kind'], scope_id, revision, values, cols['effective_from'], cols.get('effective_to'), cols['created_by'], cols['reason']))
    return rid


def publish(tenant, rid):
    """Wie WP-M1-22 es tun wird: Entwurf -> geprüft -> freigegeben (zweite Person) -> publiziert, jeweils ein Übergang."""
    for to, extra in (('VALIDATED', ''), ('APPROVED', ", approved_by = 'user:bob'"), ('PUBLISHED', ', published_at = now()')):
        with tx(tenant) as c:
            c.execute(f"UPDATE taktovia.config_revision SET state = %s{extra} WHERE tenant_id = %s AND id = %s", (to, tenant, rid))


class Revision(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'rev-a'); seed_tenant(B, 'rev-b')

    def test_neue_revision_ist_entwurf_mit_zeitstempel_und_zeilenversion(self):
        with tx(A) as c:
            rid = draft(c, A, A, revision=1)
            row = c.execute("SELECT state, row_version, created_at, reason, created_by FROM taktovia.config_revision WHERE id=%s", (rid,)).fetchone()
        self.assertEqual((row[0], row[1], row[3], row[4]), ('DRAFT', 1, 'Anpassung der Wartezeit', 'user:alice'))
        self.assertIsNotNone(row[2])

    def test_pflichtangaben_grund_wirksamkeit_scope(self):  # SYS-002 AK 1, CFG-003 negativ
        cases = [(psycopg.errors.CheckViolation, dict(reason='')), (psycopg.errors.CheckViolation, dict(reason='ab')),
                 (psycopg.errors.NotNullViolation, dict(reason=None)), (psycopg.errors.NotNullViolation, dict(effective_from=None)),
                 (psycopg.errors.NotNullViolation, dict(created_by=None)),
                 (psycopg.errors.CheckViolation, dict(effective_to='2026-11-30T00:00:00Z')),   # effective_to vor effective_from
                 (psycopg.errors.CheckViolation, dict(effective_to='2026-12-01T00:00:00Z')),   # leeres Intervall (rechts offen)
                 (psycopg.errors.CheckViolation, dict(scope_kind='GALAXIE'))]
        for n, (exc, over) in enumerate(cases):
            with self.assertRaises(exc, msg=str(over)):
                with tx(A) as c:
                    draft(c, A, A, revision=10 + n, **over)
        with tx(A) as c:   # Gegenprobe: gültiges Ende
            draft(c, A, A, revision=30, effective_to='2027-01-01T00:00:00Z')

    def test_zustandsautomat_der_revision_nur_erlaubte_uebergaenge(self):
        with tx(A) as c:
            rid = draft(c, A, uid(), revision=1)
        for bad in ('APPROVED', 'PUBLISHED', 'GEPRUEFT'):
            with self.assertRaises(psycopg.errors.CheckViolation, msg=bad):
                with tx(A) as c:
                    c.execute("UPDATE taktovia.config_revision SET state = %s, approved_by = 'user:bob', published_at = now() WHERE id = %s", (bad, rid))
        with tx(A) as c:
            c.execute("UPDATE taktovia.config_revision SET state = 'VALIDATED' WHERE id = %s", (rid,))
        with self.assertRaises(psycopg.errors.CheckViolation):   # Freigabe verlangt approved_by
            with tx(A) as c:
                c.execute("UPDATE taktovia.config_revision SET state = 'APPROVED' WHERE id = %s", (rid,))

    def test_zeilenversion_steigt_bei_jeder_aenderung_und_zeitstempel_ist_fest(self):
        with tx(A) as c:
            rid = draft(c, A, uid())
            created = c.execute("SELECT created_at FROM taktovia.config_revision WHERE id=%s", (rid,)).fetchone()[0]
        with tx(A) as c:
            c.execute("UPDATE taktovia.config_revision SET reason = 'Neuer Grund' WHERE id = %s", (rid,))
            self.assertEqual(c.execute("SELECT row_version FROM taktovia.config_revision WHERE id=%s", (rid,)).fetchone()[0], 2)
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c:
                c.execute("UPDATE taktovia.config_revision SET created_at = created_at + interval '1 day', created_by = 'user:mallory', revision = 7 WHERE id = %s", (rid,))
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT created_at FROM taktovia.config_revision WHERE id=%s", (rid,)).fetchone()[0], created)

    def test_publizierte_revision_unveraenderlich_unter_runtime_rolle(self):  # SYS-002/CFG-003 AK 5
        scope = uid()
        with tx(A) as c:
            rid = draft(c, A, scope)
        publish(A, rid)
        with tx(A) as c:
            state, published = c.execute("SELECT state, published_at FROM taktovia.config_revision WHERE id=%s", (rid,)).fetchone()
        self.assertEqual(state, 'PUBLISHED'); self.assertIsNotNone(published)
        for sql in ("UPDATE taktovia.config_revision SET values_json = '{\"max_wait_min\": 99}' WHERE id = %s",
                    "UPDATE taktovia.config_revision SET reason = 'Heimlich' WHERE id = %s",
                    "UPDATE taktovia.config_revision SET effective_to = now() + interval '1 year' WHERE id = %s",
                    "UPDATE taktovia.config_revision SET state = 'DRAFT' WHERE id = %s"):
            with self.assertRaises(psycopg.errors.CheckViolation, msg=sql):
                with tx(A) as c:
                    c.execute(sql, (rid,))
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):   # kein DELETE-Recht
            with tx(A) as c:
                c.execute("DELETE FROM taktovia.config_revision WHERE id = %s", (rid,))
        with admin() as c:   # auch Administration kann die Zeile weder ändern noch löschen
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("UPDATE taktovia.config_revision SET values_json = '{}' WHERE id = %s", (rid,))
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("DELETE FROM taktovia.config_revision WHERE id = %s", (rid,))
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT values_json FROM taktovia.config_revision WHERE id=%s", (rid,)).fetchone()[0], {'max_wait_min': 20})

    def test_entwurf_wird_vor_publikation_nicht_gesperrt(self):
        with tx(A) as c:
            rid = draft(c, A, uid())
        with tx(A) as c:
            c.execute("UPDATE taktovia.config_revision SET values_json = '{\"max_wait_min\": 30}' WHERE id = %s", (rid,))
        with self.assertRaises(psycopg.errors.CheckViolation):   # Änderung eines Entwurfs wird wieder gegen das policy_schema geprüft
            with tx(A) as c:
                c.execute("UPDATE taktovia.config_revision SET values_json = '{\"max_wait_min\": 3000}' WHERE id = %s", (rid,))

    def test_fremder_mandant_sieht_und_aendert_nichts(self):
        with tx(A) as c:
            rid = draft(c, A, A, revision=77)
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.config_revision WHERE id=%s", (rid,)).fetchone()[0], 0)
            self.assertEqual(c.execute("UPDATE taktovia.config_revision SET reason='Fremd' WHERE id=%s", (rid,)).rowcount, 0)

    def test_parallele_entwuerfe_gleiche_revisionsnummer_genau_einer(self):  # konkurrierend: UNIQUE(tenant_id,scope_kind,scope_id,revision)
        scope = uid()
        first_inserted, second_started = threading.Event(), threading.Event()
        results = {}

        def run(name, wait_for, signal):
            c = runtime()
            try:
                set_tenant(c, A)
                if wait_for: wait_for.wait(5)
                if name == 'second': second_started.set()
                try:
                    draft(c, A, scope, revision=1)
                    if signal: signal.set(); second_started.wait(5); threading.Event().wait(0.3)   # zweiter Versuch hängt auf dem Index, jetzt erst festschreiben
                    c.commit(); results[name] = 'ok'
                except psycopg.errors.UniqueViolation:
                    c.rollback(); results[name] = 'unique'
            finally:
                c.close()

        t1 = threading.Thread(target=run, args=('first', None, first_inserted))
        t2 = threading.Thread(target=run, args=('second', first_inserted, None))
        t1.start(); t2.start(); t1.join(15); t2.join(15)
        self.assertEqual(sorted(results.values()), ['ok', 'unique'], results)
        with admin() as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.config_revision WHERE scope_id=%s", (scope,)).fetchone()[0], 1)

    def test_gleichzeitige_aenderung_desselben_entwurfs_genau_eine_gewinnt(self):
        with tx(A) as c:
            rid = draft(c, A, uid())
        barrier = threading.Barrier(2)
        wins = []

        def run(n):
            c = runtime()
            try:
                set_tenant(c, A)
                barrier.wait(5)
                # optimistische Sperre wie in der Anwendung: nur bei erwarteter Zeilenversion
                done = c.execute("UPDATE taktovia.config_revision SET reason = %s WHERE id = %s AND row_version = 1", (f'Grund {n}', rid)).rowcount
                c.commit(); wins.append(done)
            finally:
                c.close()

        ts = [threading.Thread(target=run, args=(n,)) for n in range(2)]
        [t.start() for t in ts]; [t.join(15) for t in ts]
        self.assertEqual(sorted(wins), [0, 1])


if __name__ == '__main__':
    unittest.main()
