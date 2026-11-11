"""WP-M1-10 / LH-6.2-IAM-009: Sitzungsregister und Konto-Zugangsstatus unter der echten Runtime-Rolle (RLS, Sperre endgültig,
Revision, Rechte, konkurrierende Sperre/Entsperrung)."""
import threading, unittest
import psycopg
from helpers import admin, runtime, set_tenant, tx, uid, seed_tenant

A, B = uid(), uid()
ISS = 'https://idp.test/realms/m110'


def session(c, tenant, subject='u-1', sid=None):
    sid = sid or f'sid-{uid()}'
    i = uid()
    c.execute("INSERT INTO taktovia.session_register(tenant_id,id,issuer,subject,idp_session_id,expires_at) VALUES (%s,%s,%s,%s,%s,now()+interval '1 hour')",
              (tenant, i, ISS, subject, sid))
    return i, sid


class Sitzungsregister(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm110-a'); seed_tenant(B, 'm110-b')

    def test_rls_fremder_mandant_sieht_und_aendert_nichts(self):
        with tx(A) as c: i, _ = session(c, A)
        with tx(B) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.session_register WHERE id=%s', (i,)).fetchone()[0], 0)
            self.assertEqual(c.execute("UPDATE taktovia.session_register SET revoked_at=now(), revoke_reason='ADMIN' WHERE id=%s", (i,)).rowcount, 0)
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):
                session(c, A)   # Einfügen in fremden Mandanten verletzt WITH CHECK
        with tx(None) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.session_register').fetchone()[0], 0, 'ohne Kontext keine Zeile')

    def test_sperre_endgueltig_identitaet_unveraenderlich_revision(self):
        with tx(A) as c: i, sid = session(c, A)
        with tx(A) as c:
            self.assertEqual(c.execute('SELECT revision FROM taktovia.session_register WHERE id=%s', (i,)).fetchone()[0], 1)
            c.execute('UPDATE taktovia.session_register SET last_seen_at=now() WHERE id=%s', (i,))
            self.assertEqual(c.execute('SELECT revision FROM taktovia.session_register WHERE id=%s', (i,)).fetchone()[0], 1, 'last_seen zählt nicht')
            c.execute("UPDATE taktovia.session_register SET revoked_at=now(), revoked_by='user:x', revoke_reason='LOGOUT' WHERE id=%s", (i,))
            self.assertEqual(c.execute('SELECT revision FROM taktovia.session_register WHERE id=%s', (i,)).fetchone()[0], 2)
        for stmt in ("UPDATE taktovia.session_register SET revoked_at=NULL, revoke_reason=NULL WHERE id=%s",
                     "UPDATE taktovia.session_register SET revoke_reason='ADMIN' WHERE id=%s",
                     "UPDATE taktovia.session_register SET subject='fremd' WHERE id=%s",
                     "UPDATE taktovia.session_register SET idp_session_id='sid-andere-1' WHERE id=%s"):
            with self.assertRaises(psycopg.errors.CheckViolation, msg=stmt):
                with tx(A) as c: c.execute(stmt, (i,))
        with self.assertRaises(psycopg.errors.UniqueViolation):   # (Mandant, Issuer, IdP-Sitzung) eindeutig
            with tx(A) as c: session(c, A, sid=sid)
        with self.assertRaises(psycopg.errors.CheckViolation):    # Sperre ohne Grund unzulässig
            with tx(A) as c: c.execute("UPDATE taktovia.session_register SET revoked_at=now() WHERE id=%s", (i,))

    def test_keine_loeschung_unter_runtime_rolle(self):
        with tx(A) as c: i, _ = session(c, A)
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):
            with tx(A) as c: c.execute('DELETE FROM taktovia.session_register WHERE id=%s', (i,))
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):
            with tx(A) as c: c.execute('DELETE FROM taktovia.account_access')

    def test_konto_status_revision_und_abmeldemarke_nie_zurueck(self):
        s = f'u-{uid()}'
        with tx(A) as c:
            c.execute("INSERT INTO taktovia.account_access(tenant_id,issuer,subject) VALUES (%s,%s,%s)", (A, ISS, s))
            c.execute("UPDATE taktovia.account_access SET status='LOCKED', lock_reason='test' WHERE tenant_id=%s AND issuer=%s AND subject=%s", (A, ISS, s))
            c.execute("UPDATE taktovia.account_access SET sessions_revoked_before=now() WHERE tenant_id=%s AND issuer=%s AND subject=%s", (A, ISS, s))
            self.assertEqual(c.execute("SELECT revision FROM taktovia.account_access WHERE subject=%s", (s,)).fetchone()[0], 3)
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c: c.execute("UPDATE taktovia.account_access SET sessions_revoked_before=now()-interval '1 day' WHERE subject=%s", (s,))
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c: c.execute("UPDATE taktovia.account_access SET subject='x' WHERE subject=%s", (s,))
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.account_access WHERE subject=%s", (s,)).fetchone()[0], 0, 'fremder Mandant sieht den Status nicht')

    def test_konkurrierende_sperre_und_entsperrung_serialisieren_auf_der_zeile(self):
        s = f'u-{uid()}'
        with tx(A) as c: c.execute("INSERT INTO taktovia.account_access(tenant_id,issuer,subject) VALUES (%s,%s,%s)", (A, ISS, s))
        barrier = threading.Barrier(2); errors = []

        def flip(status):
            try:
                with tx(A) as c:
                    barrier.wait(10)
                    c.execute("UPDATE taktovia.account_access SET status=%s, lock_reason=%s WHERE subject=%s", (status, 'x' if status == 'LOCKED' else None, s))
            except BaseException as e:  # noqa: BLE001
                errors.append(e)
        ts = [threading.Thread(target=flip, args=(st,)) for st in ('LOCKED', 'ACTIVE')]
        [t.start() for t in ts]; [t.join() for t in ts]
        self.assertEqual(errors, [])
        with tx(A) as c:
            status, reason, rev = c.execute("SELECT status, lock_reason, revision FROM taktovia.account_access WHERE subject=%s", (s,)).fetchone()
        self.assertIn(status, ('LOCKED', 'ACTIVE'))
        # Reihenfolge Entsperren (wirkungslos auf aktives Konto), dann Sperren -> gesperrt, Revision 2; Reihenfolge Sperren, dann Entsperren -> aktiv, Revision 3
        self.assertEqual(rev, 2 if status == 'LOCKED' else 3, 'die Änderungen wurden nacheinander angewandt, keine verloren')
        self.assertEqual(reason is not None, status == 'LOCKED', 'Zustand in sich konsistent')


if __name__ == '__main__':
    unittest.main()
