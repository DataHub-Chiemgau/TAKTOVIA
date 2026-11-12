"""Selbsttest der PromQL-Teilmenge (WP-M5-04): Auswertung gegen von Hand gerechnete Werte. Aufruf aus dem Repository-Wurzelverzeichnis: python3 -m unittest discover -s infra/alerting -p 'test_promql_mini.py'."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import promql_mini as p  # noqa: E402


def store(**series):
    s = p.Store()
    for name, (labels, pts) in series.items():
        for t, v in pts:
            s.add(name, labels, t, v)
    return s


class Auswertung(unittest.TestCase):
    def test_selektor_matcher_und_veraltung(self):
        s = p.Store()
        s.add("up", {"job": "a"}, 100, 1); s.add("up", {"job": "b"}, 100, 0)
        self.assertEqual(p.evaluate('up{job="b"}', s, 120), [({"job": "b"}, 0.0)])
        self.assertEqual(len(p.evaluate('up{job=~"a|b"}', s, 120)), 2)
        self.assertEqual(p.evaluate('up{job!="a"}', s, 120), [({"job": "b"}, 0.0)])
        self.assertEqual(p.evaluate("up", s, 100 + p.STALE + 1), [], "veraltete Reihen verschwinden")

    def test_rate_increase_mit_zaehlerruecksetzer(self):
        s = store(c_total=({}, [(0, 0), (15, 15), (30, 30), (45, 5), (60, 20)]))   # Reset bei 45
        inc = p.evaluate("increase(c_total[5m])", s, 60)[0][1]
        self.assertEqual(inc, 15 + 15 + 5 + 15)
        self.assertAlmostEqual(p.evaluate("rate(c_total[5m])", s, 60)[0][1], inc / 60)
        self.assertEqual(p.evaluate("rate(c_total[5m])", store(c_total=({}, [(0, 1)])), 10), [], "ein Messpunkt: keine Rate")

    def test_aggregation_und_verhaeltnis_je_klasse(self):
        s = p.Store()
        for t in range(0, 600, 15):
            s.add("r_total", {"cls": "a", "st": "5xx"}, t, t * 0.2); s.add("r_total", {"cls": "a", "st": "2xx"}, t, t * 0.8)
            s.add("r_total", {"cls": "b", "st": "2xx"}, t, t * 1.0)
        v = p.evaluate('sum by (cls) (rate(r_total{st="5xx"}[5m])) / sum by (cls) (rate(r_total[5m]))', s, 585)
        self.assertEqual(len(v), 1, "Klasse b hat keine 5xx-Reihe: kein Ergebnis")
        self.assertEqual(v[0][0], {"cls": "a"}); self.assertAlmostEqual(v[0][1], 0.2, places=6)
        self.assertEqual(p.evaluate("max(r_total)", s, 585)[0][1], 585 * 1.0)

    def test_vergleich_filtert_und_mengenoperatoren(self):
        s = store(a=({}, [(0, 5)]), b=({}, [(0, 1)]))
        self.assertEqual(p.evaluate("a > 3", s, 0), [({}, 5.0)]); self.assertEqual(p.evaluate("a > 5", s, 0), [])
        self.assertEqual(p.evaluate("a > 3 and b > 0", s, 0), [({}, 5.0)]); self.assertEqual(p.evaluate("a > 3 and b > 1", s, 0), [])
        self.assertEqual(p.evaluate("a > 9 or b > 0", s, 0), [({}, 1.0)])
        self.assertEqual(p.evaluate("a - 1 < 14 * 86400", s, 0), [({}, 4.0)])
        self.assertEqual(p.evaluate("vector(1)", s, 0), [({}, 1.0)])
        self.assertEqual(p.evaluate("a - time() < 0", s, 10), [({}, -5.0)])

    def test_nicht_unterstuetztes_wird_abgewiesen_nie_still_anders_ausgewertet(self):
        for text in ("histogram_quantile(0.9, x)", "a offset 5m", "a / on(x) b", "sum without (a) (b)", "absent(a)", "a > bool 1", "rate(a)", "a[5m]"):
            with self.assertRaises(p.PromqlError, msg=text):
                p.evaluate(text, p.Store(), 0)

    def test_for_pending_firing_aufloesung(self):
        s = p.Store()
        groups = [{"rules": [{"alert": "X", "expr": "g > 0", "for": "1m", "labels": {"scenario": "S"}, "annotations": {"summary": "x"}}]}]
        ev = p.RuleEvaluator(groups, s)
        for t in (0, 15, 30, 45, 60, 75):
            s.add("g", {}, t, 1)
        firing = [ev.step(t)[0] for t in (0, 15, 30, 45)]
        self.assertTrue(all(not f for f in firing), "vor Ablauf von for nur pending")
        self.assertEqual([a.alertname for a in ev.step(60)[0]], ["X"], "ab for feuernd, genau einmal")
        self.assertEqual(ev.step(75)[0], [])
        s.add("g", {}, 90, 0)
        new, resolved = ev.step(90)
        self.assertEqual([a.alertname for a in resolved], ["X"])
        self.assertEqual(ev.active, {})
        # kurzer Ausreißer unter for: nie feuernd
        ev2 = p.RuleEvaluator(groups, s2 := p.Store())
        for t, v in ((0, 1), (15, 1), (30, 0), (45, 1), (60, 1)):
            s2.add("g", {}, t, v)
        self.assertTrue(all(not ev2.step(t)[0] for t in (0, 15, 30, 45, 60)))


if __name__ == "__main__":
    unittest.main()
