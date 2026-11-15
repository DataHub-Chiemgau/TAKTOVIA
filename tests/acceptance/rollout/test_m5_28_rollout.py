"""WP-M5-28: Produktionsumgebung und gestaffeltes Rollout (Enabler).

Geprüft mit echten Läufen: der ECHTE Mechanismus des Release-Gates (signiertes Manifest, HMAC), die ECHTE Umgebungsmatrix, echte PostgreSQL-Datenbanken je Umgebung (Aufbau über `db/db.py`,
Rollen, RLS), die echte Anwendung (Spring-Boot-Jar) mit echtem HTTP-Buchungsverkehr (siehe test_m5_28_rollout_last.py). NICHT ausgeführt: eine Produktionsumgebung bei einem Hostinganbieter
(D-05), Terraform, signierte Container-Images, echter Keycloak/PSP, Alertmanager (Nachbildung), Archivierungsverzug gegen einen Cluster mit WAL-Archiv (siehe README infra/rollout)."""
import base64
import json
import os
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest import mock

import sys as _sys
_sys.path.insert(0, str(Path(__file__).resolve().parent))
import m528_support as S
from m528_support import DbMigrator, HAS_DB, datenbank, domaenen, gate
from taktovia_rollout import bereitstellung, stufen, zugriff
from taktovia_rollout.domaenen import Abgewiesen

import check_rollout  # noqa: E402
REPO = S.REPO


def forged_jwt(claims: dict) -> str:
    b = lambda d: base64.urlsafe_b64encode(json.dumps(d).encode()).rstrip(b"=").decode()  # noqa: E731
    return f"{b({'alg': 'none'})}.{b(claims)}.x"


class FakeZiel:
    """Zielplattform-Attrappe für die Ablauflogik (kein Anspruch auf Plattformverhalten): zeichnet Aufrufe auf, Rauchproben und Metriken sind vorgebbar."""

    def __init__(self, fail=None, metriken=None, raises=None):
        self.fail, self.raises, self.calls, self.metriken_wert = fail or {}, raises or {}, [], metriken or {"fehlerrate_5xx_pct": 0.0, "outbox_alter_s": 1, "unbestimmte_zahlungen": 0}

    def stufe_starten(self, stufe, build_id): self.calls.append(("start", stufe["id"]))
    def stufe_abschliessen(self, stufe): self.calls.append(("fertig", stufe["id"]))
    def stufe_zurueckziehen(self, stufe): self.calls.append(("zurueck", stufe["id"]))
    def metriken(self, stufe): return dict(self.metriken_wert)

    def rauchprobe(self, stufe, name):
        self.calls.append(("probe", stufe["id"], name))
        if (stufe["id"], name) in self.raises:
            raise RuntimeError(self.raises[(stufe["id"], name)])
        return (stufe["id"], name) not in self.fail, "injiziert" if (stufe["id"], name) in self.fail else "ok"


class Artefaktpruefung(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name) / "release"

    def pruefen(self, d=None, cfg=None):
        with mock.patch.dict(os.environ, {gate.KEY_ENV: S.KEY}):
            return bereitstellung.pruefe_artefakt(d or self.dir, cfg or S.synthetic_config())

    def test_signiertes_artefakt_mit_bestandenem_gate_wird_angenommen(self):
        path, why, cfg = S.release_artefakt(self.dir)
        self.assertIsNotNone(path, why)
        m = self.pruefen(cfg=cfg)
        self.assertTrue(m["build_id"] and m["migrationsstand"]["sha256"] and m["modus"] == "ci")
        self.assertEqual({s["status"] for s in m["pflichtpruefungen"]}, {"PASSED"})

    def test_unsigniertes_artefakt_wird_abgewiesen(self):
        S.release_artefakt(self.dir)
        (self.dir / gate.SIGNATURE).unlink()
        with self.assertRaises(Abgewiesen) as cm:
            self.pruefen()
        self.assertEqual(cm.exception.grund, "artefakt_abgewiesen")
        with self.assertRaises(Abgewiesen):
            self.pruefen(Path(self.tmp.name) / "leer")      # gar kein Artefakt

    def test_manipuliertes_manifest_und_falscher_schluessel_werden_abgewiesen(self):
        S.release_artefakt(self.dir)
        mf = self.dir / gate.MANIFEST
        m = json.loads(mf.read_text()); m["build_id"] = "gefaelscht"
        mf.write_text(json.dumps(m, sort_keys=True, ensure_ascii=False, separators=(",", ":")) + "\n")
        with self.assertRaisesRegex(Abgewiesen, "Signatur stimmt nicht"):
            self.pruefen()
        S.release_artefakt(self.dir)
        with mock.patch.dict(os.environ, {gate.KEY_ENV: "x" * 40}), self.assertRaisesRegex(Abgewiesen, "Signatur stimmt nicht"):
            bereitstellung.pruefe_artefakt(self.dir, S.synthetic_config())

    def test_ohne_vollstaendig_bestandenes_release_gate_entsteht_kein_artefakt(self):
        for stufe in [s["id"] for s in gate.load_config()["stufen"]]:
            with self.subTest(rote_stufe=stufe):
                d = Path(self.tmp.name) / f"rot-{stufe}"
                path, why, _ = S.release_artefakt(d, red_stage=stufe)
                self.assertIsNone(path, why)
                with self.assertRaises(Abgewiesen):
                    self.pruefen(d)

    def test_korrekt_signiertes_manifest_mit_nicht_bestandener_stufe_wird_abgewiesen(self):
        """Eine kompromittierte Pipeline signiert ein Manifest mit roter Stufe: die Bereitstellung prüft den Inhalt, nicht nur die Signatur."""
        S.release_artefakt(self.dir)
        mf = self.dir / gate.MANIFEST
        m = json.loads(mf.read_text()); m["pflichtpruefungen"][0]["status"] = "FAILED"
        body = gate._canonical(m)
        mf.write_bytes(body + b"\n")
        import hashlib, hmac
        (self.dir / gate.SIGNATURE).write_text("hmac-sha256:" + hmac.new(S.KEY.encode(), body, hashlib.sha256).hexdigest() + "\n")
        with self.assertRaisesRegex(Abgewiesen, "nicht grün"):
            self.pruefen()

    def test_manifest_eines_probelaufs_wird_abgewiesen(self):
        S.release_artefakt(self.dir)
        mf = self.dir / gate.MANIFEST
        m = json.loads(mf.read_text()); m["modus"] = "probelauf"
        body = gate._canonical(m)
        mf.write_bytes(body + b"\n")
        import hashlib, hmac
        (self.dir / gate.SIGNATURE).write_text("hmac-sha256:" + hmac.new(S.KEY.encode(), body, hashlib.sha256).hexdigest() + "\n")
        with self.assertRaisesRegex(Abgewiesen, "nicht aus einem CI-Lauf"):
            self.pruefen()


class Zugangstrennung(unittest.TestCase):
    M = domaenen.lade_matrix()

    def test_eigene_kennungen_werden_angenommen(self):
        for art, pfad in (("secret", ("secrets", "domaene")), ("db_laufzeit", ("datenbank", "laufzeitzugang")), ("db_migration", ("datenbank", "migrationszugang")),
                          ("psp", ("psp", "domaene")), ("idp_domaene", ("identitaet", "domaene"))):
            for env in ("staging", "production"):
                kennung = self.M[env]
                for p in pfad:
                    kennung = kennung[p]
                domaenen.pruefe_zugang(env, art, kennung, self.M)

    def test_staging_secret_und_zugaenge_in_production_abgewiesen_und_umgekehrt(self):
        for art, pfad in (("secret", ("secrets", "domaene")), ("db_laufzeit", ("datenbank", "laufzeitzugang")), ("db_migration", ("datenbank", "migrationszugang")), ("psp", ("psp", "domaene"))):
            for quelle, ziel in (("staging", "production"), ("production", "staging"), ("performance", "production"), ("development", "production")):
                kennung = self.M[quelle]
                for p in pfad:
                    kennung = kennung[p]
                with self.subTest(art=art, quelle=quelle, ziel=ziel), self.assertRaises(Abgewiesen) as cm:
                    domaenen.pruefe_zugang(ziel, art, kennung, self.M)
                self.assertEqual(cm.exception.grund, "fremde_umgebung")

    def test_unbekannte_kennung_wird_abgewiesen(self):
        with self.assertRaises(Abgewiesen) as cm:
            domaenen.pruefe_zugang("production", "secret", "irgendein-secret", self.M)
        self.assertEqual(cm.exception.grund, "unbekannte_kennung")

    def test_token_aus_staging_wird_in_production_abgewiesen_und_umgekehrt(self):
        st, pr = self.M["staging"]["identitaet"], self.M["production"]["identitaet"]
        tok_st = forged_jwt({"iss": st["issuer"], "aud": [st["audience"]], "sub": "x"})
        tok_pr = forged_jwt({"iss": pr["issuer"], "aud": [pr["audience"]], "sub": "x"})
        self.assertEqual(domaenen.pruefe_token("production", tok_pr, self.M)["iss"], pr["issuer"])
        with self.assertRaises(Abgewiesen) as cm:
            domaenen.pruefe_token("production", tok_st, self.M)
        self.assertEqual(cm.exception.grund, "fremde_umgebung")
        with self.assertRaises(Abgewiesen):
            domaenen.pruefe_token("staging", tok_pr, self.M)
        with self.assertRaises(Abgewiesen):
            domaenen.pruefe_token("production", forged_jwt({"iss": pr["issuer"], "aud": ["fremd"]}), self.M)
        with self.assertRaises(Abgewiesen):
            domaenen.pruefe_token("production", "kein-token", self.M)


class Betriebszugriff(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.log = zugriff.Zugriffsprotokoll(Path(self.tmp.name) / "zugriff.jsonl")

    def ok(self, **kw):
        z = {"konto": "erika.beispiel", "person": "erika.beispiel@example.test", "mfa": True, "zweck": "Rollout-Stufe 1", "umfang": "Lesen Metriken"}
        z.update(kw)
        return z

    def test_personengebundener_zugriff_mit_mfa_wird_protokolliert(self):
        e = zugriff.zugriff_zulassen(self.ok(), self.log)
        self.assertEqual((e["person"], e["art"]), ("erika.beispiel@example.test", "regulaer"))
        self.assertEqual(self.log.pruefen(), [])

    def test_geteilte_konten_ohne_mfa_und_ohne_person_werden_abgewiesen(self):
        for fall, grund in ((self.ok(konto="admin"), "geteiltes_konto"), (self.ok(konto="root"), "geteiltes_konto"), (self.ok(geteilt=True), "geteiltes_konto"),
                            (self.ok(mfa=False), "ohne_mfa"), (self.ok(mfa="ja"), "ohne_mfa"), (self.ok(person=""), "nicht_personengebunden"), (self.ok(zweck=""), "ohne_zweck"),
                            (self.ok(konto="max.mustermann"), "konto_nicht_personengebunden")):
            with self.subTest(fall=fall), self.assertRaises(Abgewiesen) as cm:
                zugriff.zugriff_zulassen(fall, self.log)
            self.assertEqual(cm.exception.grund, grund)
        self.assertFalse(self.log.path.exists(), "abgewiesene Zugriffe erzeugen keinen Zugriffseintrag")

    def test_notfallzugang_braucht_zweite_person_ticket_und_ist_befristet(self):
        nf = {"person": "erika.beispiel@example.test", "freigeber": "hans.beispiel@example.test", "begruendung": "Datenbank nicht erreichbar", "ticket": "INC-1", "dauer_min": 60}
        e = zugriff.zugriff_zulassen(self.ok(notfall=nf), self.log)
        self.assertEqual((e["art"], e["freigeber"], e["ticket"]), ("notfall", "hans.beispiel@example.test", "INC-1"))
        for aend, grund in (({"freigeber": "erika.beispiel@example.test"}, "notfall_keine_vier_augen"), ({"ticket": ""}, "notfall_unvollstaendig"), ({"dauer_min": 999}, "notfall_zu_lang"),
                            ({"person": "hans.beispiel@example.test"}, "notfall_keine_vier_augen")):
            with self.subTest(aend=aend), self.assertRaises(Abgewiesen) as cm:
                zugriff.zugriff_zulassen(self.ok(notfall={**nf, **aend}), self.log)
            self.assertEqual(cm.exception.grund, grund)

    def test_protokollkette_erkennt_nachtraegliche_aenderung(self):
        for i in range(3):
            zugriff.zugriff_zulassen(self.ok(zweck=f"Zweck {i}"), self.log)
        self.assertEqual(self.log.pruefen(), [])
        zeilen = self.log.path.read_text().splitlines()
        e = json.loads(zeilen[1]); e["person"] = "fremd@example.test"
        zeilen[1] = json.dumps(e, ensure_ascii=False)
        self.log.path.write_text("\n".join(zeilen) + "\n")
        self.assertTrue(any("Inhalt verändert" in b for b in self.log.pruefen()))

    def test_gleichzeitige_zugriffe_zweier_personen_bleiben_eine_lueckenlose_kette(self):
        def lauf(person):
            for i in range(15):
                zugriff.zugriff_zulassen(self.ok(person=f"{person}@example.test", konto=person), self.log)
        ts = [threading.Thread(target=lauf, args=(p,)) for p in ("anna", "berta")]
        [t.start() for t in ts]; [t.join() for t in ts]
        zeilen = self.log.path.read_text().splitlines()
        self.assertEqual(len(zeilen), 30)
        self.assertEqual(self.log.pruefen(), [], "keine Verzweigung und keine Lücke in der Kette")


class Stufenlogik(unittest.TestCase):
    PLAN = stufen.lade_plan()

    def test_alle_stufen_bestehen_ergibt_abgeschlossen_ohne_zurueckziehen(self):
        z = FakeZiel()
        p = stufen.gestaffelt_ausrollen(self.PLAN, z, "build-1")
        self.assertEqual(p["ergebnis"], "ABGESCHLOSSEN")
        self.assertEqual([c for c in z.calls if c[0] in ("start", "fertig", "zurueck")],
                         [(a, s["id"]) for s in self.PLAN["stufen"] for a in ("start", "fertig")])
        self.assertTrue(all(len(s["rauchprobe"]) == len(next(x for x in self.PLAN["stufen"] if x["id"] == s["id"])["rauchprobe"]) for s in p["stufen"]))

    def test_fehlgeschlagene_rauchprobe_stoppt_das_rollout_und_spaetere_stufen_starten_nicht(self):
        z = FakeZiel(fail={("teilausrollung", "synthetische_probe"): True})
        p = stufen.gestaffelt_ausrollen(self.PLAN, z, "build-1")
        self.assertEqual(p["ergebnis"], "GESTOPPT")
        self.assertIn("synthetische_probe", p["grund"])
        self.assertIn(("zurueck", "teilausrollung"), z.calls)
        self.assertNotIn(("start", "vollausrollung"), z.calls)
        self.assertEqual(p["stufen"][-1]["ergebnis"], "ZURUECKGEZOGEN")
        self.assertIn("unverändert", p["datenbank"])

    def test_nicht_ausfuehrbare_rauchprobe_zaehlt_als_fehlgeschlagen(self):
        z = FakeZiel(raises={("kanarienvogel", "gesundheit"): "Verbindung abgelehnt"})
        p = stufen.gestaffelt_ausrollen(self.PLAN, z, "b")
        self.assertEqual(p["ergebnis"], "GESTOPPT"); self.assertIn("Verbindung abgelehnt", p["grund"])

    def test_stoppkriterien_fehlerrate_outbox_zahlungen_und_fehlende_messwerte(self):
        for metriken, text in (({"fehlerrate_5xx_pct": 2.5, "outbox_alter_s": 1, "unbestimmte_zahlungen": 0}, "Fehlerrate"),
                               ({"fehlerrate_5xx_pct": 0, "outbox_alter_s": 500, "unbestimmte_zahlungen": 0}, "Outbox"),
                               ({"fehlerrate_5xx_pct": 0, "outbox_alter_s": 1, "unbestimmte_zahlungen": 1}, "Zahlungen"),
                               ({"fehlerrate_5xx_pct": 0, "outbox_alter_s": 1}, "kein Messwert"),
                               ({"fehlerrate_5xx_pct": 0, "outbox_alter_s": 1, "unbestimmte_zahlungen": 0, "verfuegbarkeitsprobe_rot": True}, "Verfügbarkeitsprobe")):
            with self.subTest(text=text):
                p = stufen.gestaffelt_ausrollen(self.PLAN, FakeZiel(metriken=metriken), "b")
                self.assertEqual(p["ergebnis"], "GESTOPPT"); self.assertIn(text, p["grund"])

    def test_knapp_unter_den_schwellen_laeuft_weiter(self):
        k = self.PLAN["stoppkriterien"]
        z = FakeZiel(metriken={"fehlerrate_5xx_pct": k["fehlerrate_5xx_pct_max"], "outbox_alter_s": k["outbox_alter_s_max"], "unbestimmte_zahlungen": 0})
        self.assertEqual(stufen.gestaffelt_ausrollen(self.PLAN, z, "b")["ergebnis"], "ABGESCHLOSSEN")

    def test_ungueltiger_plan_wird_abgelehnt(self):
        with tempfile.TemporaryDirectory() as d:
            for aend in ({"stufen": [self.PLAN["stufen"][-1]]}, {"stufen": list(reversed(self.PLAN["stufen"]))},
                         {"stufen": [{**self.PLAN["stufen"][0], "rauchprobe": []}, self.PLAN["stufen"][-1]]}, {"stufen": [{**self.PLAN["stufen"][0], "rauchprobe": ["gibtsnicht"]}, self.PLAN["stufen"][-1]]}):
                p = Path(d) / "plan.json"; p.write_text(json.dumps({**self.PLAN, **aend}))
                with self.assertRaises(ValueError):
                    stufen.lade_plan(p)

    def test_statische_pruefung_der_produktionsdefinition_ist_gruen_und_erkennt_luecken(self):
        self.assertEqual(check_rollout.check(), [])
        import shutil
        with tempfile.TemporaryDirectory() as d:
            repo = Path(d); shutil.copytree(REPO / "infra", repo / "infra", ignore=shutil.ignore_patterns("__pycache__", "terraform", "compose"))
            (repo / "infra" / "rollout" / "produktion.json").write_text((REPO / "infra/rollout/produktion.json").read_text().replace("infra/alerting/deadman.py", "infra/alerting/gibtsnicht.py"))
            (repo / "tools").mkdir(); (repo / "tools" / "log_stichprobe.py").write_text("")
            fehler = check_rollout.check(repo / "infra", repo)
            self.assertTrue(any("gibtsnicht.py fehlt" in f for f in fehler), fehler)


class Bereitstellungsablauf(unittest.TestCase):
    """Ablauf ohne Datenbank: Abweisungen geschehen VOR jeder Veränderung (Migration wird nicht aufgerufen)."""
    PLAN = stufen.lade_plan()

    class Mig:
        def __init__(self, zugang, rolle="taktovia_migrator", sha="a" * 64):
            self.zugang, self.rolle, self.aufgerufen, self.sha = zugang, rolle, 0, sha
        def migrieren(self): self.aufgerufen += 1
        def migrationsstand(self): return {"sha256": self.sha}

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.mit_schluessel = mock.patch.dict(os.environ, {gate.KEY_ENV: S.KEY}); self.mit_schluessel.start(); self.addCleanup(self.mit_schluessel.stop)
        self.cfg = S.synthetic_config()

    def artefakt(self, **kw):
        path, why, _ = S.release_artefakt(self.root / "rel", **kw)
        return self.root / "rel"

    def deploy(self, env, mig, zugaenge=(), stand=None, art=None, ziel=None, sperre=None):
        return bereitstellung.bereitstellen(env, art or self.artefakt(), plan=self.PLAN, ziel=ziel or FakeZiel(), migrator=mig, zugaenge=list(zugaenge), staging_stand=stand,
                                            sperrdatei=sperre or self.root / "sperre", cfg=self.cfg)

    def test_positiv_signiertes_artefakt_wird_gestaffelt_ausgerollt(self):
        m = domaenen.lade_matrix()["production"]
        mig = self.Mig(m["datenbank"]["migrationszugang"])
        p = self.deploy("production", mig, [("secret", m["secrets"]["domaene"]), ("psp", m["psp"]["domaene"])], stand={"sha256": "a" * 64})
        self.assertEqual((p["ergebnis"], mig.aufgerufen, p["umgebung"]), ("ABGESCHLOSSEN", 1, "production"))

    def test_unsigniertes_artefakt_ruehrt_weder_datenbank_noch_ziel_an(self):
        art = self.artefakt(); (art / gate.SIGNATURE).unlink()
        m = domaenen.lade_matrix()["production"]; mig = self.Mig(m["datenbank"]["migrationszugang"]); z = FakeZiel()
        with self.assertRaises(Abgewiesen) as cm:
            self.deploy("production", mig, art=art, ziel=z)
        self.assertEqual((cm.exception.grund, mig.aufgerufen, z.calls), ("artefakt_abgewiesen", 0, []))

    def test_staging_secret_und_staging_migrationszugang_in_production_abgewiesen(self):
        M = domaenen.lade_matrix()
        mig = self.Mig(M["production"]["datenbank"]["migrationszugang"]); z = FakeZiel()
        with self.assertRaises(Abgewiesen) as cm:
            self.deploy("production", mig, [("secret", M["staging"]["secrets"]["domaene"])], ziel=z)
        self.assertEqual((cm.exception.grund, mig.aufgerufen, z.calls), ("fremde_umgebung", 0, []))
        mig2 = self.Mig(M["staging"]["datenbank"]["migrationszugang"])
        with self.assertRaises(Abgewiesen) as cm:
            self.deploy("production", mig2)
        self.assertEqual((cm.exception.grund, mig2.aufgerufen), ("fremde_umgebung", 0))

    def test_migration_mit_der_runtime_rolle_wird_abgewiesen(self):
        m = domaenen.lade_matrix()["production"]; mig = self.Mig(m["datenbank"]["migrationszugang"], rolle="taktovia_runtime")
        with self.assertRaises(Abgewiesen) as cm:
            self.deploy("production", mig)
        self.assertEqual((cm.exception.grund, mig.aufgerufen), ("migration_mit_laufzeitrolle", 0))

    def test_abweichender_migrationsstand_gegenueber_staging_stoppt_vor_dem_rollout(self):
        m = domaenen.lade_matrix()["production"]; z = FakeZiel()
        with self.assertRaises(Abgewiesen) as cm:
            self.deploy("production", self.Mig(m["datenbank"]["migrationszugang"], sha="b" * 64), stand={"sha256": "a" * 64}, ziel=z)
        self.assertEqual((cm.exception.grund, z.calls), ("migrationsstand_abweichend", []))

    def test_fehlgeschlagene_rauchprobe_stoppt_die_bereitstellung(self):
        m = domaenen.lade_matrix()["production"]; z = FakeZiel(fail={("kanarienvogel", "runtime_rolle"): True})
        p = self.deploy("production", self.Mig(m["datenbank"]["migrationszugang"]), ziel=z)
        self.assertEqual(p["ergebnis"], "GESTOPPT"); self.assertNotIn(("start", "teilausrollung"), z.calls)

    def test_umgebung_ausserhalb_des_plans_wird_abgewiesen(self):
        with self.assertRaises(Abgewiesen):
            self.deploy("development", self.Mig("x"))

    def test_zwei_gleichzeitige_bereitstellungen_derselben_umgebung_werden_serialisiert(self):
        m = domaenen.lade_matrix()["production"]
        art = self.artefakt()
        eintritt, frei = threading.Event(), threading.Event()

        class Blockierend(FakeZiel):
            def stufe_starten(s, stufe, build_id):
                eintritt.set(); frei.wait(10); super().stufe_starten(stufe, build_id)
        ergebnis = {}

        def erste():
            ergebnis["erste"] = self.deploy("production", self.Mig(m["datenbank"]["migrationszugang"]), art=art, ziel=Blockierend())
        t = threading.Thread(target=erste); t.start()
        self.assertTrue(eintritt.wait(10))
        mig2 = self.Mig(m["datenbank"]["migrationszugang"])
        with self.assertRaises(Abgewiesen) as cm:
            self.deploy("production", mig2, art=art)
        self.assertEqual((cm.exception.grund, mig2.aufgerufen), ("sperre_belegt", 0))
        frei.set(); t.join(30)
        self.assertEqual(ergebnis["erste"]["ergebnis"], "ABGESCHLOSSEN")
        self.assertEqual(self.deploy("production", self.Mig(m["datenbank"]["migrationszugang"]), art=art)["ergebnis"], "ABGESCHLOSSEN")   # danach wieder frei


@unittest.skipUnless(HAS_DB, "Datenbank-Zugangsdaten nicht gesetzt")
class Produktionsdatenbank(unittest.TestCase):
    """Echte PostgreSQL: Staging-Datenbank und Production-Datenbank je aus dem Repository aufgebaut; gleicher Migrationsstand; Rollen-, Policy- und Datenprüfung."""
    STG, PRD, DAT = "taktovia_m528_stg", "taktovia_m528_prd", "taktovia_m528_dat"

    @classmethod
    def setUpClass(cls):
        for n, e in ((cls.STG, "staging"), (cls.PRD, "production")):
            S.db_py("reset", n, e)
        cls.stg_stand = DbMigrator(cls.STG, "staging")
        S.db_py("migrate", cls.STG, "staging")

    @classmethod
    def tearDownClass(cls):
        S.drop_databases(cls.STG, cls.PRD)

    def conns(self, name):
        import db as dbtool
        return dbtool.admin(name), dbtool.conn("taktovia_runtime", dbtool.env("TAKTOVIA_DB_RUNTIME_PASSWORD"), name)

    def test_aufbau_der_produktion_ergibt_den_migrationsstand_des_abnahmebuilds_aus_staging(self):
        stg = self.stg_stand.migrationsstand()
        self.assertEqual(stg["umgebungskennung"], "staging")
        mig = DbMigrator(self.PRD, "production")
        tmp = tempfile.TemporaryDirectory(); self.addCleanup(tmp.cleanup)
        with mock.patch.dict(os.environ, {gate.KEY_ENV: S.KEY}):
            path, why, cfg = S.release_artefakt(Path(tmp.name) / "rel")
            p = bereitstellung.bereitstellen("production", Path(tmp.name) / "rel", plan=stufen.lade_plan(), ziel=FakeZiel(), migrator=mig,
                                             zugaenge=[("db_laufzeit", domaenen.lade_matrix()["production"]["datenbank"]["laufzeitzugang"])], staging_stand=stg, sperrdatei=Path(tmp.name) / "s", cfg=cfg)
        self.assertEqual(p["migrationsstand"]["sha256"], stg["sha256"])
        self.assertEqual(p["migrationsstand"]["umgebungskennung"], "production")
        self.assertEqual(mig.aufgerufen, 1)

    def test_migration_unter_der_runtime_rolle_scheitert_in_der_datenbank(self):
        import psycopg
        _, rt = self.conns(self.STG)
        for ddl in ("CREATE TABLE taktovia.runtime_ddl_probe(x int)", "ALTER TABLE taktovia.booking ADD COLUMN probe int", "DROP TABLE taktovia.booking", "CREATE ROLE runtime_probe_rolle"):
            with self.subTest(ddl=ddl), self.assertRaises(psycopg.errors.InsufficientPrivilege):
                rt.execute(ddl)

    def test_runtime_rolle_hat_weder_superuser_noch_bypassrls_noch_eigentum_und_der_admin_faellt_durch(self):
        adm, rt = self.conns(self.PRD)
        self.assertEqual(datenbank.runtime_rolle_befunde(rt), [])
        befunde = datenbank.runtime_rolle_befunde(adm)          # die Prüfung erkennt eine überprivilegierte Verbindung
        self.assertTrue(any("Superuser" in b for b in befunde) or any("BYPASSRLS" in b for b in befunde), befunde)

    def test_waechter_tabelle_ohne_policy_ist_gruen_und_erkennt_eine_absichtlich_ungeschuetzte_tabelle(self):
        adm, _ = self.conns(self.PRD)
        self.assertEqual(datenbank.tabellen_ohne_policy(adm), [])
        mig = __import__("db").conn("taktovia_migrator", __import__("db").env("TAKTOVIA_DB_MIGRATOR_PASSWORD"), self.PRD)
        try:
            mig.execute("CREATE TABLE taktovia.m528_ohne_policy(tenant_id uuid NOT NULL, id uuid NOT NULL)")
            mig.execute("CREATE TABLE taktovia.m528_nur_rls(tenant_id uuid NOT NULL, id uuid NOT NULL)")
            mig.execute("ALTER TABLE taktovia.m528_nur_rls ENABLE ROW LEVEL SECURITY")
            befunde = datenbank.tabellen_ohne_policy(adm)
            self.assertIn("m528_ohne_policy: RLS nicht aktiviert", befunde)
            self.assertIn("m528_ohne_policy: keine Policy auf tenant_id", befunde)
            self.assertIn("m528_nur_rls: RLS nicht erzwungen", befunde)
            self.assertIn("m528_nur_rls: keine Policy auf tenant_id", befunde)
        finally:
            mig.execute("DROP TABLE IF EXISTS taktovia.m528_ohne_policy"); mig.execute("DROP TABLE IF EXISTS taktovia.m528_nur_rls"); mig.close()
        self.assertEqual(datenbank.tabellen_ohne_policy(adm), [])

    def test_keine_seed_oder_testdaten_ausser_dem_gekennzeichneten_probemandanten(self):
        anlegen = S.availability_probemandant.anlegen
        S.db_py("reset", self.DAT, "production"); S.db_py("migrate", self.DAT, "production")      # eigene Datenbank: der Test legt Mandanten an
        self.addCleanup(S.drop_databases, self.DAT)
        adm, _ = self.conns(self.DAT)
        self.addCleanup(adm.close)
        self.assertEqual(datenbank.testdaten_befunde(adm), [])
        t = adm.execute("INSERT INTO taktovia.tenant(id,name,status) VALUES (gen_random_uuid(),'Seed-Mandant','ACTIVE') RETURNING id").fetchone()[0]
        try:
            self.assertTrue(any("nicht als Probemandant" in b for b in datenbank.testdaten_befunde(adm)))
        finally:
            adm.execute("DELETE FROM taktovia.tenant WHERE id=%s", (t,))
        anlegen(adm)
        try:
            self.assertEqual(datenbank.testdaten_befunde(adm), [])      # der gekennzeichnete Probemandant ist erlaubt
            anlegen(adm, name="zweite-probe")
            self.assertTrue(any("Probemandanten, erlaubt 1" in b for b in datenbank.testdaten_befunde(adm)))
        finally:
            pass

    def test_gesamtpruefung_und_umgebungskennung(self):
        adm, rt = self.conns(self.PRD)
        self.assertEqual(datenbank.schema_befunde(adm, rt, "production"), [])
        self.assertTrue(any("Umgebungskennung" in b for b in datenbank.schema_befunde(adm, rt, "staging")))

    def test_migration_der_production_datenbank_mit_staging_kennung_wird_verweigert(self):
        import subprocess, sys
        r = subprocess.run([sys.executable, str(REPO / "db" / "db.py"), "migrate"], cwd=REPO, env=S.env_for(self.PRD, "staging"), capture_output=True, text=True)
        self.assertNotEqual(r.returncode, 0); self.assertIn("Umgebungskennung", r.stderr + r.stdout)


if __name__ == "__main__":
    unittest.main()
