"""Offline-Tests des Einspielwerkzeugs (WP-M5-25): Geometrie, Quellprüfung, Schwärzung, Idempotenzschlüssel. Ohne Anwendung und Datenbank.
Aufruf: python -m unittest tools/test_stammdaten_import.py (oder discover -s tools). Die Läufe gegen die echte Anwendung stehen in tests/acceptance/test_stammdaten_migration.py."""
import json
import re
import sys
import tempfile
import unittest
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from stammdaten_import import geometrie, protokoll, quelle  # noqa: E402
from stammdaten_import.client import idem_key  # noqa: E402

BASIS = Path(__file__).resolve().parents[1] / "tests" / "acceptance" / "fixtures" / "stammdaten_referenz" / "stammdaten.referenz.json"


def quadrat(x, y, s):
    return {"type": "MultiPolygon", "coordinates": [[[[x, y], [x + s, y], [x + s, y + s], [x, y + s], [x, y]]]]}


def lade(mutieren=None):
    text = BASIS.read_text().replace("__TENANT__", str(uuid.uuid4())).replace("__ISSUER__", "http://idp.invalid/realms/x")
    for n in range(1, 5):
        text = text.replace(f"__SUBJ_{n}__", f"subj-{n}")
    d = json.loads(text)
    if mutieren:
        mutieren(d)
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
        json.dump(d, f)
    return quelle.laden(f.name)


class Geometrie(unittest.TestCase):
    def test_gueltig_und_ungueltig(self):
        self.assertIsNone(geometrie.multipolygon_fehler(quadrat(13.7, 51.0, 0.1)))
        schleife = {"type": "MultiPolygon", "coordinates": [[[[0, 0], [1, 1], [1, 0], [0, 1], [0, 0]]]]}
        self.assertEqual(geometrie.multipolygon_fehler(schleife), "Ring schneidet sich selbst")
        offen = {"type": "MultiPolygon", "coordinates": [[[[0, 0], [1, 0], [0, 0]]]]}
        self.assertIn("vier Punkten", geometrie.multipolygon_fehler(offen))
        nicht_geschlossen = {"type": "MultiPolygon", "coordinates": [[[[0, 0], [1, 0], [1, 1], [0, 1], [0.5, 0.5]]]]}
        self.assertEqual(geometrie.multipolygon_fehler(nicht_geschlossen), "Ring nicht geschlossen")
        ohne_flaeche = {"type": "MultiPolygon", "coordinates": [[[[0, 0], [1, 1], [2, 2], [0, 0]]]]}
        self.assertIsNotNone(geometrie.multipolygon_fehler(ohne_flaeche))
        self.assertIn("Wertebereich", geometrie.multipolygon_fehler(quadrat(13.7, 95, 0.1)))
        self.assertIsNotNone(geometrie.multipolygon_fehler({"type": "Polygon", "coordinates": []}))

    def test_punkt_in_flaeche_mit_loch_und_rand(self):
        g = {"type": "MultiPolygon", "coordinates": [[[[0, 0], [10, 0], [10, 10], [0, 10], [0, 0]], [[4, 4], [6, 4], [6, 6], [4, 6], [4, 4]]]]}
        self.assertIsNone(geometrie.multipolygon_fehler(g))
        self.assertTrue(geometrie.punkt_in_multipolygon(2, 2, g))
        self.assertFalse(geometrie.punkt_in_multipolygon(5, 5, g), "im Loch")
        self.assertTrue(geometrie.punkt_in_multipolygon(0, 5, g), "auf dem Rand")
        self.assertFalse(geometrie.punkt_in_multipolygon(11, 5, g))


class Quellpruefung(unittest.TestCase):
    def test_referenzdatensatz_ist_fehlerfrei(self):
        qu = lade()
        self.assertEqual(qu.fehler, [])
        self.assertEqual(qu.soll["haltepunkte"], 3)
        self.assertEqual(len(qu.sha256), 64)

    def test_negativfaelle_mit_grundcode(self):
        def schlecht(d):
            d["haltepunkte"][0]["point"] = {"latitude": 60.0, "longitude": 13.74}
            d["fahrzeuge"].append({**d["fahrzeuge"][0], "key": "dup", "kennzeichen": "xx tx-101"})
            d["tarife"][0].pop("valid_from")
            d["benutzer"][0].pop("rolle")
            d["fahrer"][0]["benutzer"] = "u-dispo"
            d["fahrzeuge"][1].pop("capacity")
        qu = lade(schlecht)
        gründe = {(f.art, f.key): f.code for f in qu.fehler}
        self.assertEqual(gründe[("haltepunkte", "h-markt")], "HALTEPUNKT_AUSSERHALB_GEBIET")
        self.assertEqual(gründe[("fahrzeuge", "dup")], "KENNZEICHEN_DOPPELT", "Kennzeichen werden normalisiert verglichen")
        self.assertEqual(gründe[("tarife", "t-standard")], "TARIF_OHNE_GUELTIGKEIT")
        self.assertEqual(gründe[("benutzer", "u-dispo")], "BENUTZER_OHNE_ROLLE")
        self.assertEqual(gründe[("fahrer", "p-1")], "ABHAENGIG_ABGELEHNT", "Verweis auf abgelehnten Benutzer")
        self.assertEqual(gründe[("fahrzeuge", "f-102")], "FAHRZEUG_OHNE_RESSOURCENVEKTOR")
        for f in qu.fehler:
            self.assertNotIn("latitude", f.text)
            self.assertNotIn("example.invalid", f.text)

    def test_abhaengigkeit_kaskadiert_ueber_stufen(self):
        qu = lade(lambda d: d["bediengebiete"][0].update(area={"type": "MultiPolygon", "coordinates": [[[[0, 0], [1, 1], [1, 0], [0, 1], [0, 0]]]]}))
        arten = {(f.art, f.code) for f in qu.fehler}
        self.assertIn(("bediengebiete", "GEOMETRIE_UNGUELTIG"), arten)
        self.assertEqual(sum(1 for f in qu.fehler if f.art == "haltepunkte" and f.code == "ABHAENGIG_ABGELEHNT"), 3)

    def test_quelle_als_ganzes_unbrauchbar(self):
        for inhalt in ("kein json", '{"format":"x"}'):
            with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
                f.write(inhalt)
            with self.assertRaises(quelle.QuelleFehler):
                quelle.laden(f.name)


class Protokoll(unittest.TestCase):
    def test_schwaerzen_entfernt_kontaktdaten(self):
        t = protokoll.schwaerzen("Fahrer Max Muster mail max@example.org tel +49 351 5550102 ok", geheim=["Max Muster"])
        self.assertNotIn("Max Muster", t)
        self.assertNotIn("@", t)
        self.assertNotIn("5550102", t)
        self.assertIn("ok", t)

    def test_schwaerzen_laesst_kennungen_und_zeitstempel_unberuehrt(self):
        roh = '{"id": "5d0a8f5e-6c3f-4b73-9a53-2d1f4f1a7c11", "t": "2026-11-13T06:19:03Z", "sha": "' + "a" * 64 + '"}'
        self.assertEqual(protokoll.schwaerzen(roh), roh)


class Idempotenzschluessel(unittest.TestCase):
    def test_deterministisch_ohne_ziffern(self):
        k1, k2 = idem_key("s" * 64, "fahrzeuge", "f-1", "schritt"), idem_key("s" * 64, "fahrzeuge", "f-1", "schritt")
        self.assertEqual(k1, k2)
        self.assertNotEqual(k1, idem_key("t" * 64, "fahrzeuge", "f-1", "schritt"))
        self.assertTrue(re.fullmatch(r"[A-Za-z0-9._:-]{16,128}", k1))
        self.assertIsNone(re.search(r"\d", k1), "kartenartige Ziffernfolgen weist die Anwendung ab (CardDataGuard)")


if __name__ == "__main__":
    unittest.main()
