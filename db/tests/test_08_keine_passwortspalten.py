"""WP-M1-04 (LH-6.2-IAM-002): Das System speichert selbst keine Passwörter und keine Zugriffstoken (Passwörter liegen nur im IdP).

Schema-Lint: kein Spaltenname `password*`, `passwd`, `pwd`, `passphrase`, `*_token` für Anmeldetoken (`access_token`, `refresh_token`,
`id_token`, `session_token`, `bearer*`) in irgendeinem Schema der Datenbank und in keiner Migration (`V*__*.sql`). Außerdem verwendet keine
Migration `crypt(`, `md5(`, `digest(` oder `sha1(` zum Ablegen von Passwort-Hashes. Pseudonyme wie `subject_token` (Fahrgastpseudonym)
sind keine Anmeldetoken und bleiben erlaubt.
"""
import re
import unittest
from pathlib import Path

from helpers import admin
from test_12_keine_secretspalten import ddl_columns

FORBIDDEN = re.compile(r'(?i)^(password.*|passwd.*|pwd|passphrase|(access|refresh|id|session)_token|bearer.*)$')
WEAK_HASH_CALL = re.compile(r'(?i)\b(crypt|md5|sha1|digest)\s*\(')
MIGRATIONS = Path(__file__).resolve().parents[1] / 'migrations'


def password_column_violations(columns):
    """columns: Iterable[(tabelle, spalte)] -> Verstöße."""
    return [(t, c) for t, c in columns if FORBIDDEN.match(c)]


class KeinePasswortspalten(unittest.TestCase):
    def test_schema_ohne_password_spalten(self):
        with admin() as c:
            cols = c.execute("""SELECT table_schema || '.' || table_name, column_name FROM information_schema.columns
                                WHERE table_schema NOT IN ('pg_catalog','information_schema','tiger','tiger_data','topology')""").fetchall()
        self.assertGreater(len(cols), 200)
        self.assertEqual(password_column_violations(cols), [])

    def test_alle_migrationen_ohne_passwortspalten_und_schwache_hashes(self):
        files = sorted(MIGRATIONS.glob('V*__*.sql'))
        self.assertGreaterEqual(len(files), 5)
        bad, weak = [], []
        for f in files:
            text = f.read_text(encoding='utf-8')
            bad += [(f.name, *v) for v in password_column_violations(ddl_columns(text))]
            weak += [f.name for _ in WEAK_HASH_CALL.finditer(text)]
        self.assertEqual(bad, [])
        self.assertEqual(weak, [])

    def test_lint_schlaegt_bei_passwortspalte_an(self):
        # Negativ: die Regel selbst erkennt verbotene Spalten (DDL-Beispiele und Katalogzeilen) und lässt Erlaubtes durch.
        ddl = "CREATE TABLE app_user (tenant_id uuid, id uuid, login text, password_hash text);"
        self.assertEqual(password_column_violations(ddl_columns(ddl)), [('app_user', 'password_hash')])
        self.assertEqual(password_column_violations(ddl_columns("ALTER TABLE x ADD COLUMN Password text")), [('x', 'Password')])
        self.assertEqual(password_column_violations(ddl_columns("ALTER TABLE x ADD COLUMN refresh_token text")), [('x', 'refresh_token')])
        self.assertEqual(password_column_violations([('passenger_private', 'subject_token'), ('membership', 'subject')]), [])
        self.assertTrue(WEAK_HASH_CALL.search("UPDATE u SET h = md5(p)"))
        self.assertTrue(WEAK_HASH_CALL.search("SELECT crypt('x', gen_salt('bf'))"))
        self.assertFalse(WEAK_HASH_CALL.search("SELECT digest_count FROM t"))


if __name__ == '__main__':
    unittest.main()
