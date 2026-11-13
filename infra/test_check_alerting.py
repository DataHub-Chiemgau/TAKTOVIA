"""Selbsttest der statischen Alarmprüfung (WP-M5-04): die Prüfung wird rot, wenn Katalog, Regeln, Runbook-Verweise oder Routing nicht zusammenpassen.
Aufruf aus dem Repository-Wurzelverzeichnis: python3 -m unittest infra.test_check_alerting"""
import pathlib
import shutil
import tempfile
import unittest

import yaml

import infra.check_alerting as ca

REPO = pathlib.Path(__file__).resolve().parents[1]
INFRA = REPO / "infra"


class Alarmpruefung(unittest.TestCase):
    def copy(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        repo = pathlib.Path(tmp.name)
        shutil.copytree(INFRA, repo / "infra", ignore=shutil.ignore_patterns("__pycache__", "terraform", "compose", "keycloak"))
        (repo / "docs" / "betrieb").mkdir(parents=True)
        shutil.copy(REPO / "docs" / "betrieb" / "alarm-handlungsschritte.md", repo / "docs" / "betrieb")
        (repo / ".github").mkdir()
        shutil.copy(REPO / ".github" / "CODEOWNERS", repo / ".github")
        for sub in ("backend", "workers"):
            shutil.copytree(REPO / sub, repo / sub, ignore=shutil.ignore_patterns("build", ".gradle", "__pycache__", "node_modules", ".venv", "test", "tests"))
        return repo

    def run_check(self, repo):
        errs, notes = [], []
        ca.check(repo / "infra", repo, errs, notes)
        return errs

    def test_unveraenderter_stand_ist_gruen(self):
        self.assertEqual(self.run_check(self.copy()), [])

    def test_entfernte_regel_wird_rot(self):
        repo = self.copy()
        f = repo / "infra/observability/alert-rules.d/kritische-szenarien.yml"
        doc = yaml.safe_load(f.read_text(encoding="utf-8"))
        for g in doc["groups"]:
            g["rules"] = [r for r in g["rules"] if r["alert"] != "RedisAusfall"]
        f.write_text(yaml.safe_dump(doc), encoding="utf-8")
        self.assertTrue(any("RedisAusfall" in e for e in self.run_check(repo)))

    def test_fehlender_handlungsschritt_und_vorlagenwert_werden_rot(self):
        repo = self.copy()
        md = repo / "docs/betrieb/alarm-handlungsschritte.md"
        md.write_text(md.read_text(encoding="utf-8").replace('<a id="redis_ausfall"></a>', ""), encoding="utf-8")
        self.assertTrue(any("REDIS_AUSFALL" in e and "Runbook" in e for e in self.run_check(repo)))
        repo = self.copy()
        f = repo / "infra/observability/alert-rules.d/kritische-szenarien.yml"
        f.write_text(f.read_text(encoding="utf-8").replace('summary: "Redis nicht erreichbar', 'summary: "Redis {{ $labels.instance }} nicht erreichbar'), encoding="utf-8")
        self.assertTrue(any("Vorlagenwerte" in e for e in self.run_check(repo)))

    def test_routing_ohne_totmann_und_unbekannte_metrik_werden_rot(self):
        repo = self.copy()
        f = repo / "infra/alerting/alertmanager.yml"
        f.write_text(f.read_text(encoding="utf-8").replace('alertname="Watchdog"', 'alertname="Anderes"'), encoding="utf-8")
        self.assertTrue(any("Watchdog" in e for e in self.run_check(repo)))
        repo = self.copy()
        f = repo / "infra/observability/alert-rules.d/kritische-szenarien.yml"
        f.write_text(f.read_text(encoding="utf-8").replace("taktovia_outbox_depth", "taktovia_gibt_es_nicht"), encoding="utf-8")
        self.assertTrue(any("taktovia_gibt_es_nicht" in e for e in self.run_check(repo)))

    def test_szenario_ohne_regel_und_verfrueht_aktives_szenario_werden_rot(self):
        repo = self.copy()
        f = repo / "infra/alerting/katalog.yaml"
        doc = yaml.safe_load(f.read_text(encoding="utf-8"))
        next(s for s in doc["szenarien"] if s["id"] == "REDIS_AUSFALL")["regeln"] = []
        next(s for s in doc["szenarien"] if s["id"] == "BACKUP_FEHLGESCHLAGEN")["regeln"] = []      # seit WP-M5-14 aktiv: ohne Regel rot
        f.write_text(yaml.safe_dump(doc, allow_unicode=True), encoding="utf-8")
        errs = self.run_check(repo)
        self.assertTrue(any("REDIS_AUSFALL" in e for e in errs)); self.assertTrue(any("BACKUP_FEHLGESCHLAGEN" in e for e in errs))


if __name__ == "__main__":
    unittest.main()
