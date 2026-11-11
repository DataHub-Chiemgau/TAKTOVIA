"""WP-M1-06 / LH-I.4-MUL-006, LH-6.17-INT-002: Mitgliedschaft (Gültigkeit, Revision, Unveränderlichkeit) und der kontrollierte
Lookup „Mandanten eines Subjects“ vor dem Tenantkontext (OP-06) unter der echten Runtime-Rolle."""
import threading, unittest
import psycopg
from helpers import admin, runtime, set_tenant, tx, uid, seed_tenant

A, B, C = uid(), uid(), uid()
ISS = 'https://idp.test/realms/m106'


def grant(c, tenant, subject, role='dispatcher', **kw):
    mid = uid()
    c.execute("INSERT INTO taktovia.membership(tenant_id,id,issuer,subject,role_code,service_scope) VALUES (%s,%s,%s,%s,%s,%s)",
              (tenant, mid, ISS, subject, role, kw.get('scope')))
    return mid


def lookup(subject, issuer=ISS):
    """Was der Server vor dem Tenantkontext sehen darf: nur mit dem transaktionslokalen Subject-Schlüssel."""
    c = runtime()
    try:
        if subject is not None:
            c.execute("SELECT set_config('app.subject_key', %s, true)", (issuer + '\n' + subject,))
        return sorted(r[0] for r in c.execute('SELECT tenant_id FROM taktovia.membership_subject_index').fetchall())
    finally:
        c.rollback(); c.close()


class Mitgliedschaft(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        for t, n in ((A, 'm106-a'), (B, 'm106-b'), (C, 'm106-c')): seed_tenant(t, n)

    def test_gueltigkeitszeitraum_und_revision(self):
        s = f'user-{uid()}'
        with tx(A) as c:
            mid = grant(c, A, s)
            rev, vf, vt = c.execute('SELECT revision, valid_from, valid_to FROM taktovia.membership WHERE id=%s', (mid,)).fetchone()
            self.assertEqual((rev, vt), (1, None)); self.assertIsNotNone(vf)
            c.execute('UPDATE taktovia.membership SET service_scope=%s WHERE id=%s', ([uid()], mid))
            self.assertEqual(c.execute('SELECT revision FROM taktovia.membership WHERE id=%s', (mid,)).fetchone()[0], 2)
        with self.assertRaises(psycopg.errors.CheckViolation):  # valid_to muss nach valid_from liegen
            with tx(A) as c:
                c.execute("INSERT INTO taktovia.membership(tenant_id,id,issuer,subject,role_code,valid_from,valid_to) VALUES (%s,%s,%s,%s,'dispatcher',now(),now()-interval '1 day')",
                          (A, uid(), ISS, s + 'x'))

    def test_kernfelder_unveraenderlich_und_widerruf_endgueltig(self):
        s = f'user-{uid()}'
        with tx(A) as c: mid = grant(c, A, s)
        for col, val in (('subject', 'anderer'), ('issuer', 'https://x'), ('role_code', 'support')):
            with self.assertRaises(psycopg.errors.CheckViolation, msg=col):
                with tx(A) as c: c.execute(f'UPDATE taktovia.membership SET {col}=%s WHERE id=%s', (val, mid))
        with tx(A) as c:
            c.execute('UPDATE taktovia.membership SET revoked_at=now() WHERE id=%s', (mid,))
            self.assertEqual(c.execute('SELECT revision FROM taktovia.membership WHERE id=%s', (mid,)).fetchone()[0], 2)
        with self.assertRaises(psycopg.errors.CheckViolation):  # Widerruf nicht rücknehmbar, auch nicht für Admin
            with tx(A) as c: c.execute('UPDATE taktovia.membership SET revoked_at=NULL WHERE id=%s', (mid,))
        with self.assertRaises(psycopg.errors.CheckViolation):
            with admin() as c: c.execute('UPDATE taktovia.membership SET revoked_at=NULL WHERE id=%s', (mid,))

    def test_doppelte_mitgliedschaft_wird_abgewiesen(self):
        s = f'user-{uid()}'
        with tx(A) as c: grant(c, A, s)
        with self.assertRaises(psycopg.errors.UniqueViolation):
            with tx(A) as c: grant(c, A, s)
        with tx(B) as c: grant(c, B, s)  # gleiche Person in anderem Mandanten ist zulässig

    def test_paralleles_erteilen_derselben_mitgliedschaft_erzeugt_genau_eine_zeile(self):
        s = f'user-{uid()}'
        barrier, results = threading.Barrier(4), []

        def worker():
            try:
                with tx(A) as c:
                    barrier.wait(timeout=10)
                    grant(c, A, s)
                results.append('ok')
            except psycopg.errors.UniqueViolation:
                results.append('dup')
        ts = [threading.Thread(target=worker) for _ in range(4)]
        [t.start() for t in ts]; [t.join() for t in ts]
        self.assertEqual(sorted(results), ['dup', 'dup', 'dup', 'ok'])
        with tx(A) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.membership WHERE subject=%s', (s,)).fetchone()[0], 1)
        self.assertEqual(lookup(s), [A])


class SubjectLookup(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        for t, n in ((A, 'm106-a'), (B, 'm106-b'), (C, 'm106-c')): seed_tenant(t, n)

    def test_lookup_liefert_nur_mandanten_des_subjects(self):
        s1, s2 = f'user-{uid()}', f'user-{uid()}'
        with tx(A) as c: grant(c, A, s1); grant(c, A, s2)
        with tx(B) as c: grant(c, B, s1)
        with tx(C) as c: grant(c, C, s2)
        self.assertEqual(lookup(s1), sorted([A, B]))
        self.assertEqual(lookup(s2), sorted([A, C]))

    def test_fremdes_oder_unbekanntes_subject_liefert_nichts(self):
        s = f'user-{uid()}'
        with tx(A) as c: grant(c, A, s)
        self.assertEqual(lookup(f'user-{uid()}'), [])
        self.assertEqual(lookup(s, issuer='https://anderer-issuer'), [])   # gleicher subject, anderer Issuer
        self.assertEqual(lookup(s[:-1]), [])                                # Präfix zählt nicht
        self.assertEqual(lookup(None), [])                                  # ohne Schlüssel: deny-by-default

    def test_ohne_schluessel_auch_im_tenantkontext_keine_zeile_kein_aufzaehlen(self):
        s = f'user-{uid()}'
        with tx(A) as c:
            grant(c, A, s)
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.membership_subject_index').fetchone()[0], 0)

    def test_schreiben_nur_im_eigenen_tenantkontext_und_kein_aendern_oder_loeschen(self):
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):  # Index-Eintrag für fremden Mandanten
            with tx(A) as c: c.execute("INSERT INTO taktovia.membership_subject_index(tenant_id,issuer,subject) VALUES (%s,%s,'x')", (B, ISS))
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):
            with tx(None) as c: c.execute("INSERT INTO taktovia.membership_subject_index(tenant_id,issuer,subject) VALUES (%s,%s,'x')", (A, ISS))
        s = f'user-{uid()}'
        with tx(A) as c: grant(c, A, s)
        for sql in ('UPDATE taktovia.membership_subject_index SET subject=subject', 'DELETE FROM taktovia.membership_subject_index'):
            with self.assertRaises(psycopg.errors.InsufficientPrivilege, msg=sql):
                with tx(A) as c: c.execute(sql)

    def test_kein_definer_und_keine_view(self):
        with admin() as c:
            self.assertEqual(c.execute("SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='taktovia' AND p.prosecdef").fetchone()[0], 0)
            self.assertEqual(c.execute("SELECT count(*) FROM pg_views WHERE schemaname='taktovia'").fetchone()[0], 0)

    def test_schluessel_ist_transaktionslokal(self):
        s = f'user-{uid()}'
        with tx(A) as c: grant(c, A, s)
        c = runtime()
        try:
            c.execute("SELECT set_config('app.subject_key', %s, true)", (ISS + '\n' + s,))
            self.assertEqual(len(c.execute('SELECT 1 FROM taktovia.membership_subject_index').fetchall()), 1)
            c.commit()   # Pool-Rückgabe: Schlüssel wirkt nicht fort
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.membership_subject_index').fetchone()[0], 0)
        finally:
            c.rollback(); c.close()


if __name__ == '__main__':
    unittest.main()
