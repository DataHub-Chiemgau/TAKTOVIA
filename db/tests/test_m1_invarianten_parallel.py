"""WP-M1-45 (AK 6) / Kap. 04 §4.5: Pflicht-Negativtests je in M1 wirksamer Invariante mit zwei parallelen Transaktionen unter der echten Runtime-Rolle.

Invarianten: (1) Cross-Tenant-Referenz, (2) kein Kontext, keine Zeile, (3) Gegenereignis statt Änderung (`audit_event`), (4) nicht überlappende Policyversion,
(5) Idempotenz. Jede Transaktion läuft in einem eigenen Thread mit eigener Verbindung; ein Barrier erzwingt das gleichzeitige Arbeiten."""
import threading
import unittest

import psycopg
from helpers import admin, runtime, seed_tenant, set_tenant, uid
from test_m1_22_publikation import advance, draft

A, B = uid(), uid()


def run_parallel(*fns, timeout=60):
    """Führt die Funktionen gleichzeitig aus (Barrier) und liefert je Funktion Ergebnis oder Ausnahme."""
    barrier = threading.Barrier(len(fns)); out = [None] * len(fns)

    def wrap(i, fn):
        try:
            out[i] = ('ok', fn(barrier))
        except Exception as e:  # noqa: BLE001
            out[i] = ('error', e)
    ts = [threading.Thread(target=wrap, args=(i, f)) for i, f in enumerate(fns)]
    [t.start() for t in ts]; [t.join(timeout) for t in ts]
    assert not any(t.is_alive() for t in ts), 'Thread hängt'
    return out


class InvariantenParallel(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'inv-a'); seed_tenant(B, 'inv-b')
        cls.le = {}
        with admin() as c:
            for t in (A, B):
                cls.le[t] = uid()
                c.execute("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (%s,%s,'Rechtstraeger','DE')", (t, cls.le[t]))

    # (1) Cross-Tenant-Referenz
    def test_cross_tenant_referenz_in_zwei_parallelen_transaktionen_scheitert(self):
        def attempt(tenant, foreign_le):
            def f(barrier):
                c = runtime()
                try:
                    set_tenant(c, tenant)
                    c.execute("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (%s,%s,'zusatz','DE')", (tenant, uid()))
                    barrier.wait()
                    try:
                        c.execute("INSERT INTO taktovia.organization(tenant_id,id,legal_entity_id,name,federation_ref,roles) VALUES (%s,%s,%s,'fremd',%s,'{BROKER}')",
                                  (tenant, uid(), foreign_le, f'x-{uid()}'))
                        c.commit(); return 'ANGELEGT'
                    except psycopg.errors.ForeignKeyViolation:
                        c.rollback(); return 'FK'
                finally:
                    c.close()
            return f
        res = run_parallel(attempt(A, self.le[B]), attempt(B, self.le[A]))
        self.assertEqual([r[1] for r in res], ['FK', 'FK'], res)
        with admin() as c:   # nichts Halbes: auch die gültigen Vorbereitungszeilen der zurückgerollten Transaktionen fehlen
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.legal_entity WHERE name='zusatz' AND tenant_id IN (%s,%s)", (A, B)).fetchone()[0], 0)
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.organization WHERE name='fremd'").fetchone()[0], 0)

    # (2) kein Kontext, keine Zeile
    def test_ohne_kontext_keine_zeile_waehrend_eine_andere_transaktion_mit_kontext_arbeitet(self):
        def with_context(barrier):
            c = runtime()
            try:
                set_tenant(c, A)
                c.execute("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (%s,%s,'ungebunden','DE')", (A, uid()))
                own = c.execute("SELECT count(*) FROM taktovia.legal_entity").fetchone()[0]
                barrier.wait(); barrier.wait()
                c.commit()
                return own
            finally:
                c.close()

        def without_context(barrier):
            c = runtime()
            try:
                barrier.wait()
                n = c.execute("SELECT count(*) FROM taktovia.legal_entity").fetchone()[0]   # während A hält (uncommitted) und danach
                set_tenant(c, B)
                nb = c.execute("SELECT count(*) FROM taktovia.legal_entity WHERE name='ungebunden'").fetchone()[0]
                insert_ok = True
                try:
                    c.execute("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (%s,%s,'x','DE')", (A, uid()))
                except psycopg.errors.Error:
                    insert_ok = False   # fremde tenant_id: RLS WITH CHECK
                c.rollback()
                barrier.wait()
                return n, nb, insert_ok
            finally:
                c.close()
        res = run_parallel(with_context, without_context)
        self.assertEqual(res[0][0], 'ok'); self.assertGreaterEqual(res[0][1], 1)
        self.assertEqual(res[1], ('ok', (0, 0, False)))
        c = runtime()   # nach dem Commit von A: derselbe Befund ohne Kontext (Pool-Wiederverwendung: kein Kontext wirkt fort)
        try:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.legal_entity").fetchone()[0], 0)
        finally:
            c.close()

    # (3) Gegenereignis statt Änderung
    def test_audit_aenderung_scheitert_parallel_zu_neuen_eintraegen_eintraege_vollstaendig(self):
        first = uid()
        with admin() as c:
            c.execute("INSERT INTO taktovia.audit_event(tenant_id,id,actor_ref,action,object_ref,occurred_at,correlation_id,redacted_changes) VALUES (%s,%s,'system:inv','inv.test.created','invtest:1',now(),%s,'{}'::jsonb)", (A, first, uid()))
            before = c.execute("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=%s", (A,)).fetchone()[0]

        def writer(barrier):
            c = runtime()
            try:
                set_tenant(c, A); barrier.wait()
                for i in range(30):
                    c.execute("INSERT INTO taktovia.audit_event(tenant_id,id,actor_ref,action,object_ref,occurred_at,correlation_id,redacted_changes) VALUES (%s,%s,'system:inv','inv.test.counter','invtest:1',now(),%s,'{}'::jsonb)", (A, uid(), uid()))
                c.commit(); return 30
            finally:
                c.close()

        def tamperer(barrier):
            c = runtime(); denied = 0
            try:
                set_tenant(c, A); barrier.wait()
                for stmt in ("UPDATE taktovia.audit_event SET action='manipuliert' WHERE id=%s", "DELETE FROM taktovia.audit_event WHERE id=%s") * 5:
                    try:
                        c.execute(stmt, (first,)); c.commit()
                    except psycopg.errors.InsufficientPrivilege:
                        denied += 1; c.rollback(); set_tenant(c, A)
                return denied
            finally:
                c.close()
        res = run_parallel(writer, tamperer)
        self.assertEqual(res[0], ('ok', 30)); self.assertEqual(res[1], ('ok', 10))
        with admin() as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=%s", (A,)).fetchone()[0], before + 30)
            self.assertEqual(c.execute("SELECT action FROM taktovia.audit_event WHERE id=%s", (first,)).fetchone()[0], 'inv.test.created')
            with self.assertRaises(psycopg.errors.CheckViolation):   # auch der Administrator: Trigger audit_immutable
                c.execute("UPDATE taktovia.audit_event SET action='x' WHERE id=%s", (first,))

    # (4) nicht überlappende Policyversion
    def test_ueberlappende_publikation_zweier_transaktionen_genau_eine_gewinnt(self):
        scope = uid()
        with runtime() as c:
            set_tenant(c, A)
            r1 = draft(c, A, scope, 1, '2040-01-01T00:00:00Z', '2041-01-01T00:00:00Z')
            r2 = draft(c, A, scope, 2, '2040-06-01T00:00:00Z', '2041-06-01T00:00:00Z')
            c.commit()
        for r in (r1, r2):
            advance(A, r, 'VALIDATED'); advance(A, r, 'APPROVED')

        def publish(rid):
            def f(barrier):
                c = runtime()
                try:
                    set_tenant(c, A); barrier.wait()
                    try:
                        c.execute("UPDATE taktovia.config_revision SET state='PUBLISHED', published_at=now() WHERE tenant_id=%s AND id=%s", (A, rid)); c.commit(); return 'PUBLISHED'
                    except psycopg.errors.ExclusionViolation:
                        c.rollback(); return 'UEBERLAPPUNG'
                finally:
                    c.close()
            return f
        res = run_parallel(publish(r1), publish(r2))
        self.assertEqual(sorted(r[1] for r in res), ['PUBLISHED', 'UEBERLAPPUNG'], res)
        with admin() as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.config_revision WHERE tenant_id=%s AND scope_id=%s AND state='PUBLISHED'", (A, scope)).fetchone()[0], 1)

    # (5) Idempotenz
    def test_gleicher_idempotenzschluessel_in_zwei_parallelen_transaktionen_genau_eine_ausfuehrung(self):
        key = 'invariante-' + uid().hex

        def claim(tenant, sha):
            def f(barrier):
                c = runtime()
                try:
                    set_tenant(c, tenant); barrier.wait()
                    try:
                        c.execute("INSERT INTO taktovia.idempotency_record(tenant_id,principal_ref,command_scope,key,request_sha256,expires_at) VALUES (%s,'user:p','createX',%s,%s,now()+interval '1 hour')", (tenant, key, sha))
                        c.commit(); return 'AUSGEFUEHRT'
                    except psycopg.errors.UniqueViolation:
                        c.rollback(); return 'KOLLISION'
                finally:
                    c.close()
            return f
        same = run_parallel(claim(A, 'a' * 64), claim(A, 'b' * 64))   # derselbe Schlüssel, anderer Request: genau eine Ausführung
        self.assertEqual(sorted(r[1] for r in same), ['AUSGEFUEHRT', 'KOLLISION'], same)
        other = run_parallel(claim(A, 'c' * 64), claim(B, 'c' * 64))   # nur im anderen Mandanten (Schlüssel kollidiert nicht über Mandanten)
        self.assertEqual(other[1], ('ok', 'AUSGEFUEHRT')); self.assertEqual(other[0], ('ok', 'KOLLISION'))


if __name__ == '__main__':
    unittest.main()
