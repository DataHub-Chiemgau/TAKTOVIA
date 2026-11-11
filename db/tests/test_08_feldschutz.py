"""WP-M1-31 / LH-6.18-SEC-005: Spaltenrechte für Unterstützungs- und Kontaktmerkmale.

Die allgemeine Runtime-Rolle darf `support_ciphertext`/`contact_ciphertext` weder lesen noch schreiben; nur der Modulzugang
`taktovia_fields` (Feldschutzbaustein) darf die Spalten lesen und ändern. RLS bleibt auch für den Modulzugang wirksam.
"""
import csv
import re
import unittest
from pathlib import Path

import psycopg

from helpers import admin, fields, runtime, set_tenant, uid, seed_tenant

SPALTEN_DIR = Path(__file__).resolve().parents[1] / 'role_matrix_spalten.d'
A, B = uid(), uid()
CIPHER = b'\x01TFtest-nicht-klartext'

# Spalten, die Mobilitäts-/Unterstützungsmerkmale oder Gesundheitsbezug tragen könnten; erlaubt nur im getrennten PII-Bereich.
SENSITIVE = re.compile(r'(?i)(support_need|support_ciphertext|mobility_need|diagnos|disabilit|handicap|medical|health|impairment)')
SENSITIVE_HOME = {('passenger_private', 'support_ciphertext')}


def sensitive_column_violations(columns):
    """columns: Iterable[(tabelle, spalte)] -> Spalten mit sensiblem Namen außerhalb des getrennten Bereichs (Schema-Lint)."""
    return [(t, c) for t, c in columns if SENSITIVE.search(c) and (t, c) not in SENSITIVE_HOME]


def column_matrix():
    out = {}
    for p in sorted(SPALTEN_DIR.glob('*.csv')):
        with p.open(encoding='utf-8', newline='') as f:
            for r in csv.DictReader(f, delimiter=';'):
                out[(r['role'], r['table'], r['column'])] = set(filter(None, r['privileges'].split(',')))
    return out


def seed(tenant, passenger):
    seed_tenant(tenant, 'feldschutz-' + str(tenant)[:8])
    c = runtime()
    try:
        set_tenant(c, tenant)
        c.execute("INSERT INTO taktovia.passenger_private(tenant_id,id,subject_token,retention_until) VALUES (%s,%s,%s,now() + interval '1 year')",
                  (tenant, passenger, 'tok-' + str(passenger)))
        c.commit()
    finally:
        c.close()
    f = fields()
    try:
        set_tenant(f, tenant)
        f.execute("UPDATE taktovia.passenger_private SET support_ciphertext=%s, contact_ciphertext=%s WHERE tenant_id=%s AND id=%s",
                  (CIPHER, CIPHER + b'k', tenant, passenger))
        f.commit()
    finally:
        f.close()


class Feldschutz(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.pa, cls.pb = uid(), uid()
        seed(A, cls.pa)
        seed(B, cls.pb)

    def _denied(self, c, tenant, stmt, params=()):
        set_tenant(c, tenant)
        with self.assertRaises(psycopg.errors.InsufficientPrivilege, msg=stmt):
            c.execute(stmt, params)
        c.rollback()

    def test_runtime_kann_support_ciphertext_nicht_lesen(self):
        c = runtime()
        try:
            for col in ('support_ciphertext', 'contact_ciphertext'):
                self._denied(c, A, f'SELECT {col} FROM taktovia.passenger_private')
                self._denied(c, A, f'SELECT count(*) FROM taktovia.passenger_private WHERE {col} IS NOT NULL')   # auch als Filter/Seitenkanal
                self._denied(c, A, f'UPDATE taktovia.passenger_private SET {col}=%s', (b'x',))
                self._denied(c, A, f'INSERT INTO taktovia.passenger_private(tenant_id,id,subject_token,retention_until,{col}) VALUES (%s,%s,%s,now(),%s)',
                             (A, uid(), 'x' + str(uid()), b'x'))
            self._denied(c, A, 'SELECT * FROM taktovia.passenger_private')   # SELECT * umfasst die gesperrten Spalten
            self._denied(c, A, 'SELECT p FROM taktovia.passenger_private p')  # Zeilenverweis ebenso
        finally:
            c.close()

    def test_runtime_liest_weiterhin_die_allgemeinen_spalten(self):
        c = runtime()
        try:
            set_tenant(c, A)
            rows = c.execute('SELECT id, subject_token FROM taktovia.passenger_private').fetchall()
            self.assertEqual([r[0] for r in rows], [self.pa])
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.passenger_private').fetchone()[0], 1)
        finally:
            c.rollback(); c.close()

    def test_modulzugang_liest_ciphertext_nur_im_eigenen_mandanten(self):
        f = fields()
        try:
            set_tenant(f, A)
            raw = f.execute('SELECT support_ciphertext FROM taktovia.passenger_private WHERE id=%s', (self.pa,)).fetchone()[0]
            self.assertEqual(bytes(raw), CIPHER)
            self.assertEqual(f.execute('SELECT count(*) FROM taktovia.passenger_private WHERE id=%s', (self.pb,)).fetchone()[0], 0)   # RLS
            self.assertEqual(f.execute('SELECT count(*) FROM taktovia.passenger_private').fetchone()[0], 1)
        finally:
            f.rollback(); f.close()

    def test_modulzugang_ohne_tenantkontext_sieht_nichts_und_aendert_keinen_fremden_mandanten(self):
        f = fields()
        try:
            self.assertEqual(f.execute('SELECT count(*) FROM taktovia.passenger_private').fetchone()[0], 0)   # fail-closed
            set_tenant(f, A)
            n = f.execute('UPDATE taktovia.passenger_private SET support_ciphertext=%s WHERE id=%s', (b'x', self.pb)).rowcount
            self.assertEqual(n, 0)
        finally:
            f.rollback(); f.close()

    def test_modulzugang_hat_nur_die_vorgesehenen_rechte(self):
        f = fields()
        try:
            self._denied(f, A, 'SELECT subject_token FROM taktovia.passenger_private')
            self._denied(f, A, 'SELECT * FROM taktovia.passenger_private')
            self._denied(f, A, 'INSERT INTO taktovia.passenger_private(tenant_id,id,subject_token,retention_until) VALUES (%s,%s,%s,now())', (A, uid(), 'y' + str(uid())))
            self._denied(f, A, 'DELETE FROM taktovia.passenger_private')
            self._denied(f, A, 'SELECT count(*) FROM taktovia.membership')
            self._denied(f, A, 'SELECT count(*) FROM taktovia.audit_event')
            self._denied(f, A, 'INSERT INTO taktovia.audit_event(tenant_id,id,actor_ref,action,object_ref,occurred_at,correlation_id,redacted_changes) '
                               "VALUES (%s,%s,'system:x','a.b','c:d',now(),%s,'{}'::jsonb)", (A, uid(), uid()))
            self._denied(f, A, 'CREATE TABLE taktovia.x(a int)')
        finally:
            f.close()

    def test_modulzugang_ist_kein_superuser_und_ohne_bypassrls_oder_eigentum(self):
        with admin() as c:
            r = c.execute("SELECT rolsuper, rolbypassrls, rolcreaterole, rolcreatedb, rolcanlogin FROM pg_roles WHERE rolname='taktovia_fields'").fetchone()
            self.assertEqual(r, (False, False, False, False, True))
            owned = c.execute("""SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_roles o ON o.oid=c.relowner
                                 WHERE n.nspname IN ('taktovia','taktovia_meta') AND o.rolname='taktovia_fields'""").fetchone()[0]
            self.assertEqual(owned, 0)
            self.assertEqual(c.execute("""SELECT count(*) FROM pg_auth_members am JOIN pg_roles u ON u.oid=am.member
                                          WHERE u.rolname='taktovia_fields'""").fetchone()[0], 0)

    def test_spaltenrechte_entsprechen_der_matrix(self):
        expected = column_matrix()
        with admin() as c:
            actual = {}
            for role, tab, col, priv in c.execute("""SELECT grantee, table_name, column_name, privilege_type FROM information_schema.column_privileges
                                                     WHERE table_schema='taktovia' AND table_name='passenger_private'
                                                       AND grantee IN ('taktovia_runtime','taktovia_fields')"""):
                actual.setdefault((role, tab, col), set()).add(priv)
            # Tabellenweite Rechte (die information_schema.column_privileges mit ausweist) gibt es für diese Tabelle nicht.
            tablewide = c.execute("""SELECT grantee, privilege_type FROM information_schema.role_table_grants
                                     WHERE table_schema='taktovia' AND table_name='passenger_private'
                                       AND grantee IN ('taktovia_runtime','taktovia_fields','PUBLIC')""").fetchall()
            self.assertEqual(tablewide, [])
        for key, privs in expected.items():
            self.assertEqual(actual.get(key, set()), privs, key)
        self.assertEqual(set(actual) - set(expected), set(), 'Spaltenrecht ohne Eintrag in role_matrix_spalten.d')

    def test_keine_sensible_spalte_in_allgemeinen_aggregattabellen(self):
        with admin() as c:
            cols = c.execute("SELECT table_name, column_name FROM information_schema.columns WHERE table_schema='taktovia'").fetchall()
        self.assertGreater(len(cols), 200)
        self.assertEqual(sensitive_column_violations(cols), [])
        self.assertIn(('passenger_private', 'support_ciphertext'), cols)

    def test_schema_lint_erkennt_sensible_spalte_in_aggregattabelle(self):
        self.assertEqual(sensitive_column_violations([('booking', 'support_needs'), ('journey', 'diagnosis_code'), ('x', 'state')]),
                         [('booking', 'support_needs'), ('journey', 'diagnosis_code')])
        self.assertEqual(sensitive_column_violations([('passenger_private', 'support_ciphertext')]), [])

    def test_alle_migrationen_im_repository_bestehen_den_lint(self):
        from test_12_keine_secretspalten import ddl_columns  # gleiche grobe DDL-Spaltenextraktion
        bad = []
        for f in sorted((Path(__file__).resolve().parents[1] / 'migrations').glob('V*__*.sql')):
            bad += [(f.name, *v) for v in sensitive_column_violations(ddl_columns(f.read_text(encoding='utf-8')))]
        # V1 legt support_ciphertext in passenger_private an (erlaubt); alles andere wäre ein Verstoß
        self.assertEqual([b for b in bad if b[2] != 'support_ciphertext'], [])


if __name__ == '__main__':
    unittest.main()
