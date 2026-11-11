"""WP-M1-38: Befund-Gate der CI. Ein kritischer Befund (Mangelklasse A) lässt die Pipeline scheitern; jeder Befund braucht eine dokumentierte Entscheidung."""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
GATE = REPO / 'tools/findings_gate.py'
LINT = REPO / 'tools/lint_sql.py'
TODAY = '2026-11-11'
HEADER = 'tool;rule;location;entscheidung;begruendung;verantwortlich;bis\n'


def sarif(tool, results, rules=None):
    return {'version': '2.1.0', 'runs': [{'tool': {'driver': {'name': tool, 'rules': rules or []}}, 'results': results}]}


def result(rule, uri, level='warning', text='Befund'):
    return {'ruleId': rule, 'level': level, 'message': {'text': text}, 'locations': [{'physicalLocation': {'artifactLocation': {'uri': uri}, 'region': {'startLine': 7}}}]}


class Gate:
    def __init__(self, test):
        self.d = Path(tempfile.mkdtemp())
        test.addCleanup(lambda: __import__('shutil').rmtree(self.d, ignore_errors=True))

    def run(self, reports, decisions=''):
        files = []
        for i, doc in enumerate(reports):
            f = self.d / f'r{i}.json'
            f.write_text(json.dumps(doc))
            files.append(str(f))
        dec = self.d / 'befunde.csv'
        dec.write_text(HEADER + decisions)
        r = subprocess.run([sys.executable, str(GATE), '--decisions', str(dec), '--today', TODAY, '--report', str(self.d / 'bericht.md'), *files], capture_output=True, text=True)
        return r.returncode, r.stdout + r.stderr


CRITICAL_RULES = [{'id': 'java/sql-injection', 'properties': {'security-severity': '9.8'}}]
MEDIUM_RULES = [{'id': 'java/weak-hash', 'properties': {'security-severity': '5.3'}}]


class CiGate(unittest.TestCase):
    def test_klasse_a_befund_laesst_pipeline_scheitern(self):
        g = Gate(self)
        # absichtlich eingebauter kritischer Befund (CVSS 9.8): ohne Entscheidung, mit Akzeptanz oder Terminzusage bleibt das Gate rot
        crit = sarif('CodeQL', [result('java/sql-injection', 'backend/app/Repo.kt', 'error', 'SQL-Injektion')], CRITICAL_RULES)
        rc, out = g.run([crit])
        self.assertEqual(rc, 1, out); self.assertIn('Klasse A', out); self.assertIn('keine dokumentierte Entscheidung', out)
        for kind in ('AKZEPTIERT', 'WIRD_BEHOBEN'):
            rc, out = g.run([crit], f'CodeQL;java/sql-injection;*;{kind};Termin vereinbart;Sicherheit;2026-12-31\n')
            self.assertEqual(rc, 1, f'{kind}: {out}'); self.assertIn('Klasse A blockiert die Produktion', out)
        # nur eine begründete Falschmeldung mit Verantwortlichem entlastet
        rc, out = g.run([crit], 'CodeQL;java/sql-injection;backend/app/Repo.kt;FALSCHMELDUNG;Wert stammt aus fester Liste, geprüft im Review 4711;Sicherheit;\n')
        self.assertEqual(rc, 0, out)
        rc, out = g.run([crit], 'CodeQL;java/sql-injection;*;FALSCHMELDUNG;;Sicherheit;\n')
        self.assertEqual(rc, 1, out); self.assertIn('Begründung', out)
        # der Bericht (Scan-Bericht für den Penetrationstest in M5) wird geschrieben
        self.assertIn('| A | KRITISCH | CodeQL | java/sql-injection |', (g.d / 'bericht.md').read_text())

    def test_kritischer_befund_aus_dem_eigenen_query_lint_bricht_die_kette(self):
        # absichtlich eingebaute SQL-Injektion im Quelltext -> lint_sql (KRITISCH, Klasse A) -> Gate rot
        g = Gate(self)
        src = g.d / 'backend/m/src/main/Repo.kt'
        src.parent.mkdir(parents=True)
        src.write_text('package x\nclass R(private val jdbc: org.springframework.jdbc.core.JdbcOperations) {\n fun a(id: String) = jdbc.update("DELETE FROM t WHERE id = \'$id\'")\n}\n')
        lint = subprocess.run([sys.executable, str(LINT), '--format', 'json', str(g.d)], capture_output=True, text=True)
        self.assertEqual(lint.returncode, 1)
        report = json.loads(lint.stdout)
        self.assertEqual(report['findings'][0]['severity'], 'KRITISCH')
        rc, out = g.run([report])
        self.assertEqual(rc, 1, out); self.assertIn('Klasse A', out)

    def test_jeder_befund_braucht_eine_entscheidung_klasse_b_einen_termin(self):
        g = Gate(self)
        medium = sarif('CodeQL', [result('java/weak-hash', 'a.kt')], MEDIUM_RULES)
        rc, out = g.run([medium]); self.assertEqual(rc, 1, out); self.assertIn('keine dokumentierte Entscheidung', out)
        rc, out = g.run([medium], 'CodeQL;java/weak-hash;*;AKZEPTIERT;nur Prüfsumme, kein Sicherheitsbezug;Plattform;\n'); self.assertEqual(rc, 0, out)
        high = sarif('OSV', [result('GHSA-xxxx', 'backend/app/gradle.lockfile')], [{'id': 'GHSA-xxxx', 'properties': {'security-severity': '7.5'}}])
        rc, out = g.run([high], 'OSV;GHSA-xxxx;*;AKZEPTIERT;Workaround aktiv;Betrieb;\n'); self.assertEqual(rc, 1, out); self.assertIn('Behebungstermin', out)
        rc, out = g.run([high], 'OSV;GHSA-xxxx;*;WIRD_BEHOBEN;Update 3.1.9 geplant;Betrieb;2026-12-15\n'); self.assertEqual(rc, 0, out)
        rc, out = g.run([high], 'OSV;GHSA-xxxx;*;WIRD_BEHOBEN;Update 3.1.9 geplant;Betrieb;2026-11-01\n'); self.assertEqual(rc, 1, out); self.assertIn('abgelaufen', out)
        # Entscheidung gilt nur für Werkzeug und Regel
        rc, out = g.run([high], 'OSV;GHSA-andere;*;FALSCHMELDUNG;x;y;\n'); self.assertEqual(rc, 1, out)

    def test_sauberer_lauf_und_andere_formate(self):
        g = Gate(self)
        rc, out = g.run([sarif('CodeQL', [])]); self.assertEqual(rc, 0, out); self.assertIn('0 Befunde', out)
        zap = {'site': [{'alerts': [{'pluginid': '40018', 'name': 'SQL Injection', 'riskcode': '3', 'instances': [{'uri': 'https://staging.example/v1/passengers'}]},
                                     {'pluginid': '10038', 'name': 'CSP', 'riskcode': '1', 'instances': [{'uri': 'https://staging.example/'}]}]}]}
        rc, out = g.run([zap]); self.assertEqual(rc, 1, out); self.assertIn('Klasse A', out); self.assertIn('ZAP 40018', out)
        rc, out = g.run([zap], 'ZAP;40018;*;FALSCHMELDUNG;Antwort 404 mit Standardtext, geprüft;Sicherheit;\nZAP;10038;*;AKZEPTIERT;Hinweis, Meta-CSP vorhanden;Web;\n'); self.assertEqual(rc, 0, out)
        own = {'tool': 'angriffskatalog', 'findings': [{'rule': 'ssrf', 'location': 'POST /v1/webhook-subscriptions', 'severity': 'KRITISCH', 'title': 'Ziel nicht abgewiesen'}]}
        rc, out = g.run([own]); self.assertEqual(rc, 1, out)
        bad = subprocess.run([sys.executable, str(GATE), str(g.d / 'gibtsnicht.json')], capture_output=True, text=True)
        self.assertNotEqual(bad.returncode, 0)


if __name__ == '__main__':
    unittest.main()
