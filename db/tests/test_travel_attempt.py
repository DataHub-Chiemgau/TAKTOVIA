"""WP-M4-47 / LH-I.6-KPI-001: Jede Fahrtanfrage bleibt als eigener Versuch erhalten. Unter der echten Runtime-Rolle: kein DELETE, doppelte (Mandant, Versuch) abgewiesen, technischer Retry mit
gleichem Anfrageschlüssel erzeugt keinen zweiten Versuch (Eindeutigkeitsindex), parallele identische Anfragen ergeben genau einen Versuch, Mandantentrennung (RLS)."""
import threading
import unittest
import psycopg
from helpers import tx, uid, seed_tenant, admin

A, B = uid(), uid()
SCOPE = 'ab' * 32


def attempt(c, tenant, aid=None, scope=None, outcome='OFFERED'):
    aid = aid or uid()
    c.execute("INSERT INTO taktovia.travel_attempt(tenant_id,id,created_at,channel,outcome,request_scope,request_fingerprint) VALUES (%s,%s,now(),'WEB',%s,%s,%s)",
              (tenant, aid, outcome, scope, scope))
    return aid


class Versuchseintrag(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm447-a'); seed_tenant(B, 'm447-b')

    def test_versuch_nicht_loeschbar_und_mandantengetrennt(self):
        with tx(A) as c:
            aid = attempt(c, A)
            c.execute("UPDATE taktovia.travel_attempt SET outcome='OFFERED' WHERE id=%s", (aid,))   # ändern bleibt möglich (technischer Retry, Ergebnis)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.InsufficientPrivilege): c.execute('DELETE FROM taktovia.travel_attempt WHERE id=%s', (aid,))
        with tx(A) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.travel_attempt WHERE id=%s', (aid,)).fetchone()[0], 1, 'Versuch ohne Angebot bleibt bestehen')
            with self.assertRaises(psycopg.errors.UniqueViolation): attempt(c, A, aid=aid)   # (tenant_id, id) ist eindeutig
        with tx(B) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.travel_attempt WHERE id=%s', (aid,)).fetchone()[0], 0, 'Versuch eines fremden Mandanten erscheint nicht')
            self.assertEqual(c.execute('UPDATE taktovia.travel_attempt SET channel=%s WHERE id=%s', ('API', aid)).rowcount, 0)
            with self.assertRaises(psycopg.errors.InsufficientPrivilege): c.execute('DELETE FROM taktovia.travel_attempt WHERE id=%s', (aid,))
        with tx(B) as c:
            with self.assertRaises(psycopg.errors.InsufficientPrivilege): attempt(c, A)   # Zeile für fremden Mandanten: RLS WITH CHECK
        with admin() as c:   # Plattformbetrieb sieht beide: der Versuch von A ist unverändert vorhanden
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.travel_attempt WHERE id=%s AND tenant_id=%s', (aid, A)).fetchone()[0], 1)

    def test_technischer_retry_mit_gleichem_schluessel_erzeugt_keinen_zweiten_versuch(self):
        scope = 'cd' * 32
        with tx(A) as c: attempt(c, A, scope=scope, outcome='TECHNICAL_ERROR')
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.UniqueViolation): attempt(c, A, scope=scope)
        with tx(A) as c:   # eine fachlich neue Suche (neuer Schlüssel) ist ein eigener Versuch
            attempt(c, A, scope='ef' * 32)
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.travel_attempt WHERE request_scope IN (%s,%s)", (scope, 'ef' * 32)).fetchone()[0], 2)
        with tx(B) as c:   # derselbe Schlüssel in einem anderen Mandanten ist kein Konflikt
            attempt(c, B, scope=scope)

    def test_parallele_identische_suchanfragen_ergeben_genau_einen_versuch(self):
        scope = '12' * 32
        results = []

        def worker():
            try:
                with tx(A) as c: attempt(c, A, scope=scope); results.append('ok')
            except psycopg.errors.UniqueViolation:
                results.append('dup')
        ts = [threading.Thread(target=worker) for _ in range(8)]
        [t.start() for t in ts]; [t.join() for t in ts]
        self.assertEqual(results.count('ok'), 1); self.assertEqual(results.count('dup'), 7)
        with tx(A) as c: self.assertEqual(c.execute('SELECT count(*) FROM taktovia.travel_attempt WHERE request_scope=%s', (scope,)).fetchone()[0], 1)


if __name__ == '__main__':
    unittest.main()
