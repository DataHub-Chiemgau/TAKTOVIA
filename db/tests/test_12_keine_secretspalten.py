"""WP-M1-36 (LH-6.18-SEC-003, SEC-002): keine Datenbankspalte hält einen Secretwert; Tabellen halten nur Secret-Referenzen (`*_ref`).

Namensmuster (Vorschlag aus dem Detailvertrag, mit dem Bau bestätigt): Spaltennamen mit password, passwd, secret, api_key,
private_key oder token_value ohne Suffix `_ref` scheitern. Die Regel ist als Funktion `secret_column_violations` auch auf neue DDL anwendbar.
"""
import re
import unittest

from helpers import admin

PATTERN = re.compile(r'(?i)(password|passwd|secret|api_?key|private_?key|token_value)')


def secret_column_violations(columns):
    """columns: Iterable[(tabelle, spalte)] -> Verstöße (Spalte mit Secretnamen, die keine Referenz ist)."""
    return [(t, c) for t, c in columns if PATTERN.search(c) and not c.lower().endswith('_ref')]


def ddl_columns(ddl):
    """Grobe Spaltenextraktion aus CREATE TABLE/ALTER TABLE ADD COLUMN für den Test der Regel an Beispiel-DDL."""
    cols = []
    for m in re.finditer(r'(?is)create\s+table\s+(\w+)\s*\((.*?)\);', ddl):
        for line in m.group(2).split(','):
            w = line.strip().split()
            if w and w[0].lower() not in ('primary', 'unique', 'foreign', 'check', 'constraint'):
                cols.append((m.group(1), w[0]))
    for m in re.finditer(r'(?i)alter\s+table\s+(\w+)\s+add\s+column\s+(\w+)', ddl):
        cols.append((m.group(1), m.group(2)))
    return cols


class KeineSecretspalten(unittest.TestCase):
    def test_keine_spalte_mit_secretwert(self):
        with admin() as c:
            cols = c.execute("""SELECT table_name, column_name FROM information_schema.columns WHERE table_schema='taktovia'""").fetchall()
        self.assertGreater(len(cols), 200)
        self.assertEqual(secret_column_violations(cols), [])

    def test_migration_mit_spalte_fuer_secretwert_scheitert_am_lint(self):
        bad = "CREATE TABLE webhook_subscription (tenant_id uuid, id uuid, url text, signing_secret text);"
        self.assertEqual(secret_column_violations(ddl_columns(bad)), [('webhook_subscription', 'signing_secret')])
        ok = "CREATE TABLE webhook_subscription (tenant_id uuid, id uuid, url text, signing_secret_ref text);"
        self.assertEqual(secret_column_violations(ddl_columns(ok)), [])
        self.assertEqual(secret_column_violations(ddl_columns("ALTER TABLE x ADD COLUMN api_key text")), [('x', 'api_key')])

    def test_alle_migrationen_im_repository_bestehen_den_lint(self):
        from pathlib import Path
        bad = []
        for f in sorted((Path(__file__).resolve().parents[1] / 'migrations').glob('V*__*.sql')):
            bad += [(f.name, *v) for v in secret_column_violations(ddl_columns(f.read_text(encoding='utf-8')))]
        self.assertEqual(bad, [])


if __name__ == '__main__':
    unittest.main()
