"""Tests des Query-Lints (WP-M1-38): erkennt nicht parametrisiertes SQL, lässt Platzhalter und Konstanten zu, der echte Code ist sauber."""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / 'tools'))
import lint_sql  # noqa: E402


def kt(code):
    return f'package x\nclass Repo(private val jdbc: org.springframework.jdbc.core.JdbcOperations) {{\n{code}\n}}\n'


def scan_kotlin(code):
    with tempfile.TemporaryDirectory() as d:
        p = Path(d) / 'backend/m/src/main/Repo.kt'
        p.parent.mkdir(parents=True)
        p.write_text(kt(code))
        return lint_sql.scan_kotlin(p)


def scan_python(code):
    with tempfile.TemporaryDirectory() as d:
        p = Path(d) / 'workers/w/src/repo.py'
        p.parent.mkdir(parents=True)
        p.write_text(code)
        return lint_sql.scan_python(p)


class QueryLint(unittest.TestCase):
    def test_parametrisierte_queries_sind_erlaubt(self):
        ok = [
            'fun a(id: String) = jdbc.queryForObject("SELECT name FROM t WHERE id = ?", String::class.java, id)',
            'fun a() = jdbc.update("""UPDATE t SET a = ?, b = ? WHERE id = ?""".trimIndent(), 1, 2, 3)',
            'fun a(i: Int) = jdbc.query("SELECT * FROM t LIMIT ?", { rs, _ -> rs.getInt(1) }, i)',
            'fun a() = jdbc.update("UPDATE t SET price = \\$1")',                         # maskiertes Dollarzeichen (PostgreSQL-Platzhalter)
            'companion object { const val Q = "SELECT 1" }\nfun a() = jdbc.queryForObject(Q, Int::class.java)',
            'fun a() = tx.execute { jdbc.update("DELETE FROM t WHERE id = ?", 1) }',     # Lambda, kein SQL
            'fun a() = jdbc.update("SELECT " + "1")',                                      # Konstanten verkettet
        ]
        for code in ok:
            self.assertEqual(scan_kotlin(code), [], code)

    def test_nicht_parametrisierte_queries_werden_gefunden(self):
        bad = [
            'fun a(id: String) = jdbc.queryForObject("SELECT name FROM t WHERE id = \'$id\'", String::class.java)',
            'fun a(id: String) = jdbc.update("DELETE FROM t WHERE id = ${id}")',
            'fun a(id: String) = jdbc.query("SELECT * FROM t WHERE id = " + id, { rs, _ -> 1 })',
            'fun a(id: String) = jdbc.queryForList(String.format("SELECT %s", id))',
            'fun a(sql: String) = jdbc.update(sql)',
            'fun a(col: String) = jdbc.query("""SELECT $col FROM t""", { rs, _ -> 1 })',
            'fun a(c: java.sql.Connection, n: String) = c.createStatement().executeQuery("SELECT * FROM " + n)',
            'fun a(c: java.sql.Connection, n: String) = c.prepareStatement("SELECT * FROM t WHERE x = \'$n\'")',
        ]
        for code in bad:
            self.assertTrue(scan_kotlin(code), code)

    def test_begruendete_ausnahme_in_derselben_zeile(self):
        self.assertEqual(scan_kotlin('fun a(t: String) = jdbc.update("TRUNCATE $t") // sql-lint: ok Tabellenname aus fester Liste'), [])
        self.assertTrue(scan_kotlin('fun a(t: String) = jdbc.update("TRUNCATE $t") // sql-lint: ok'))

    def test_python(self):
        self.assertEqual(scan_python('cur.execute("SELECT * FROM t WHERE id = %s", (i,))\nQ = "SELECT 1"\ncur.execute(Q)'), [])
        for bad in ['cur.execute(f"SELECT * FROM t WHERE id = {i}")', 'cur.execute("SELECT * FROM t WHERE id = %s" % i)', 'cur.execute("SELECT * FROM t WHERE id = " + i)',
                    'cur.execute("SELECT {}".format(i))', 'cur.execute(sql)']:
            self.assertTrue(scan_python(bad), bad)

    def test_der_echte_code_ist_sauber_und_der_lauf_scheitert_bei_einem_fund(self):
        r = subprocess.run([sys.executable, str(REPO / 'tools/lint_sql.py')], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / 'backend/m/src/main/Repo.kt'
            p.parent.mkdir(parents=True)
            p.write_text(kt('fun a(id: String) = jdbc.update("DELETE FROM t WHERE id = $id")'))
            r = subprocess.run([sys.executable, str(REPO / 'tools/lint_sql.py'), d], capture_output=True, text=True)
            self.assertEqual(r.returncode, 1, r.stdout)
            self.assertIn('Repo.kt', r.stdout)


if __name__ == '__main__':
    unittest.main()
