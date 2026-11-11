"""WP-M1-36 (LH-6.18-SEC-002/003): Konfiguration enthält ausschließlich Secret-Referenzen oder Umgebungs-Platzhalter, keine Werte.
Prüft die ausgelieferte Konfiguration statisch; die Datenbankhälfte (keine Secretspalten) steht in db/tests/test_12_keine_secretspalten.py,
das Auflösen und Rotieren in backend/platform/…/secret/*Test.kt."""
import re
import unittest
from pathlib import Path

import yaml

REPO = Path(__file__).resolve().parents[3]
SECRET_KEY = re.compile(r"(?i)(password|passwd|secret|api[_-]?key|token|credential)")


def walk(node, path=""):
    if isinstance(node, dict):
        for k, v in node.items():
            yield from walk(v, f"{path}.{k}" if path else str(k))
    elif isinstance(node, list):
        for i, v in enumerate(node):
            yield from walk(v, f"{path}[{i}]")
    else:
        yield path, node


class Konfiguration(unittest.TestCase):
    def test_keine_secretwerte_in_tabellen_und_konfiguration(self):
        cfg = yaml.safe_load((REPO / "backend/app/src/main/resources/application.yml").read_text(encoding="utf-8"))
        offending = []
        for path, value in walk(cfg):
            if SECRET_KEY.search(path.split(".")[-1]) and isinstance(value, str) and value and not re.fullmatch(r"\$\{[A-Z0-9_]+(:[^}]*)?\}|secret://.+", value):
                offending.append(path)
        self.assertEqual(offending, [], "Geheimnisfelder müssen Platzhalter oder Secret-Referenzen sein")
        self.assertTrue(any(SECRET_KEY.search(p) for p, _ in walk(cfg)), "Konfiguration enthält Geheimnisfelder (Prüfung wirksam)")

    def test_migrationszugang_ist_nicht_in_der_laufzeitkonfiguration(self):
        cfg = yaml.safe_load((REPO / "backend/app/src/main/resources/application.yml").read_text(encoding="utf-8"))
        self.assertEqual([p for p, v in walk(cfg) if "MIGRATOR" in f"{p}={v}".upper()], [])

    def test_secret_katalog_trennt_migration_und_laufzeit_und_kennt_nur_referenzen(self):
        import csv
        rows = list(csv.DictReader((REPO / "infra/deploy/geheimnisse.csv").open(encoding="utf-8"), delimiter=";"))
        by = {r["geheimnis"]: r for r in rows}
        self.assertIn("Migration", by["db_migration_credential"]["schritt"])
        self.assertNotIn("Migration", by["db_runtime_credential"]["schritt"])
        for r in rows:
            self.assertRegex(r["geheimnis"], r"^[a-z][a-z0-9_]+$")
            self.assertTrue(r["speicherort"], r)


if __name__ == "__main__":
    unittest.main()
