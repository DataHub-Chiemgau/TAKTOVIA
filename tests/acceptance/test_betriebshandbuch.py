"""WP-M5-26: Vollständigkeitsabgleich Alarmkatalog <-> Runbooks des Betriebshandbuchs (tools/check_betriebshandbuch.py), positiv und negativ.

Grenze (ehrlich): geprüft wird die Dokumentstruktur, nicht ob eine Person das Runbook ohne Entwicklerhilfe ausführen kann. Die Übung je Schweregrad und die Notbetrieb-Tischübung mit dem Betreiber
sind NICHT ausgeführt (kein Betriebspersonal in dieser Umgebung); sie stehen im Handbuch (Abschnitt 14) als offen."""
import copy
import json
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import yaml

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools"))
import check_betriebshandbuch as cb  # noqa: E402

HANDBUCH = (REPO / "docs" / "betrieb" / "betriebshandbuch.md").read_text(encoding="utf-8")
KATALOG = yaml.safe_load((REPO / "infra" / "alerting" / "katalog.yaml").read_text(encoding="utf-8"))


class Abgleich(unittest.TestCase):
    def test_katalog_und_runbooks_vollstaendig(self):
        self.assertEqual(cb.pruefen(HANDBUCH, KATALOG), [])
        self.assertEqual(sorted(cb.runbooks(HANDBUCH)), sorted(s["id"] for s in KATALOG["szenarien"]))

    def test_werkzeug_exit_code_und_ausgabe(self):
        r = subprocess.run([sys.executable, str(REPO / "tools" / "check_betriebshandbuch.py")], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertEqual(json.loads(r.stdout)["ergebnis"], "OHNE_BEFUND")

    def test_alarm_ohne_runbook_wird_erkannt(self):
        """Übung mit einem Alarm, dessen Runbook fehlt: der Abgleich erkennt ihn, und das Werkzeug endet mit Exit-Code 1."""
        katalog = copy.deepcopy(KATALOG)
        katalog["szenarien"].append({**katalog["szenarien"][0], "id": "NEUER_ALARM_OHNE_RUNBOOK", "titel": "Neuer Alarm"})
        befunde = cb.pruefen(HANDBUCH, katalog)
        self.assertEqual(befunde, ["Alarm NEUER_ALARM_OHNE_RUNBOOK hat kein Runbook im Betriebshandbuch"])
        with tempfile.TemporaryDirectory() as d:
            kat = Path(d) / "katalog.yaml"
            kat.write_text(yaml.safe_dump(katalog, allow_unicode=True), encoding="utf-8")
            r = subprocess.run([sys.executable, str(REPO / "tools" / "check_betriebshandbuch.py"), "--katalog", str(kat)], capture_output=True, text=True)
        self.assertEqual(r.returncode, 1)
        self.assertIn("NEUER_ALARM_OHNE_RUNBOOK", r.stdout)

    def test_geloeschtes_runbook_und_leeres_feld_werden_erkannt(self):
        text = re.sub(r"<a id=\"rb-redis_ausfall\"></a>\n### REDIS_AUSFALL:.*?(?=<a id=\"rb-)", "", HANDBUCH, flags=re.S)
        self.assertEqual(cb.pruefen(text, KATALOG), ["Alarm REDIS_AUSFALL hat kein Runbook im Betriebshandbuch"])
        text = HANDBUCH.replace("- **Nacharbeit:** Datenverlust im Cache ist unkritisch; Ursache und Dauer im Vorfallsprotokoll vermerken.", "- **Nacharbeit:**")
        self.assertEqual(cb.pruefen(text, KATALOG), ["Runbook REDIS_AUSFALL: Feld 'Nacharbeit' fehlt oder ist leer"])

    def test_runbook_ohne_alarm_wird_erkannt(self):
        katalog = copy.deepcopy(KATALOG)
        katalog["szenarien"] = [s for s in katalog["szenarien"] if s["id"] != "SOLVER_TIMEOUTS"]
        self.assertEqual(cb.pruefen(HANDBUCH, katalog), ["Runbook SOLVER_TIMEOUTS gehört zu keinem Alarm des Katalogs"])

    def test_schritt_mit_superuser_oder_sql_wird_erkannt(self):
        text = HANDBUCH.replace("Redis neu starten; Rate-Limit-Zähler bauen sich neu auf.", "Redis neu starten und danach per psql als Superuser UPDATE booking SET state = 'CONFIRMED' ausführen.")
        befunde = cb.pruefen(text, KATALOG)
        self.assertTrue(any("REDIS_AUSFALL" in b and "Superuser- oder direkten Datenbankzugriff" in b for b in befunde), befunde)

    def test_jeder_schritt_nennt_eine_rolle_und_notbetrieb_ist_vollstaendig(self):
        for alarm, text in cb.runbooks(HANDBUCH).items():
            self.assertRegex(text, r"- \*\*Rolle:\*\* \S", alarm)
        for fall in cb.NOTBETRIEB:
            self.assertIn(f" {fall}", HANDBUCH)
        for p in cb.PARAMETER:
            self.assertIn(f"`{p}`", HANDBUCH)
        text = HANDBUCH.replace("### 11.3 Ausfall der Leitstellenoberfläche", "### 11.3 Etwas anderes")
        self.assertIn("Notbetriebsverfahren fehlt: Ausfall der Leitstellenoberfläche", cb.pruefen(text, KATALOG))

    def test_runbook_anker_des_katalogs_existieren_in_beiden_dokumenten(self):
        """Der Katalog verweist je Alarm auf `docs/betrieb/alarm-handlungsschritte.md#<anker>`; dieser Anker bleibt gültig, und das Handbuch führt dasselbe Runbook unter `rb-<anker>`."""
        kurz = (REPO / "docs" / "betrieb" / "alarm-handlungsschritte.md").read_text(encoding="utf-8")
        for s in KATALOG["szenarien"]:
            anker = s["runbook"].split("#")[1]
            self.assertIn(f'<a id="{anker}"></a>', kurz, s["id"])
            self.assertIn(f'<a id="rb-{anker}"></a>', HANDBUCH, s["id"])


if __name__ == "__main__":
    unittest.main()
