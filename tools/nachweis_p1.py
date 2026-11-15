#!/usr/bin/env python3
"""Nachweisführung P1 und M5-Gate-Bericht (WP-M5-31).

Erzeugt aus `docs/umsetzungsplan/arbeitspakete.csv`, den Evidenzdatensätzen (`tests/acceptance/evidence/**`), `docs/pflichtenheft/e2e.csv`, den Testergebnissen (JUnit, Python-Lauf,
Vitest-JSON) und dem Mängelregister die **Nachweismatrix P1** (je `source_key` Testfall, letzter Lauf, Build, Evidenzverweis, Mangelklasse offener Befunde) und den **Gate-Bericht** mit den
sieben Gates aus Kap. 08 §8.4. Das Werkzeug ENTSCHEIDET NICHTS (die Abnahme trifft der Auftraggeber) und färbt nichts grün: eine Anforderung ohne grünen, ausgeführten Test ist `OHNE_LAUF`,
`KEIN_TEST` oder `ROT`, eine Evidenz eines anderen Builds wird abgewiesen, eine fehlende Evidenz macht die Matrix unvollständig und den Berichtslauf rot.

  python3 tools/nachweis_p1.py lauf [--python DIR ...] [--vitest-json DATEI ...] [--out DATEI]   # Testergebnisse sammeln (Python-Läufe je Verzeichnis)
  python3 tools/nachweis_p1.py bericht [--build ID] [--write] [--lauf DATEI]                      # Matrix und Gate-Bericht, Exit 1 bei Verstößen (Gate nicht bestanden)
  python3 tools/nachweis_p1.py fachmodell [--write]                                              # Review-Protokoll Fachmodell (Anhänge aus den Detailverträgen)"""
import argparse
import csv
import io
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
GREEN = {"Umgesetzt", "Abgenommen"}
KATALOG_ID = re.compile(r"^(AT-\d{2}|E2E-\d{3}|PH-AT-\d{3}|TECH-[A-Z0-9-]+)$")
PFLICHT_AT = ("AT-23", "AT-25", "AT-53", "AT-54", "AT-59", "AT-61")
E2E_ENTRAG = re.compile(r"^(Bestanden|Teilweise bestanden|Fehlgeschlagen)")
GATES = ("Fachmodell", "CI", "Partnerfreigabe", "Sicherheit", "UX", "Betrieb", "Pilot")
SPERRWIRKUNG = {
    "Fachmodell": "Ungeklärte Geld- oder Beförderungsrolle verhindert deren produktive Aktivierung",
    "CI": "Fehlgeschlagene Pflichtprüfung verhindert Release",
    "Partnerfreigabe": "Nicht geprüfte Fähigkeit bleibt in der Capability Registry deaktiviert",
    "Sicherheit": "Datenoffenlegung, Sicherheits-/Verkehrsgefährdung: keine Freigabe",
    "UX": "Blockierte Kernreise vor Freigabe beheben",
    "Betrieb": "Fehlender Wiederanlauf- oder Kernbetriebsnachweis verhindert Pilot",
    "Pilot": "Abbruchkriterien und Rückfallbetrieb vor Pilotbeginn festlegen",
}
MATRIX_SPALTEN = ("source_key", "meilenstein", "arbeitspaket", "status_arbeitspaket", "detailvertrag", "tests_gesamt", "tests_ausfuehrbar", "tests_gruen", "tests_rot", "tests_ohne_lauf",
                  "referenztests", "evidenz", "build", "migrationsstand", "letzter_lauf", "negativ_verlangt", "konkurrierend_verlangt", "mangelklasse_offen", "ergebnis")


def rd(p, delim=";"):
    with Path(p).open(encoding="utf-8-sig", newline="") as f:
        return list(csv.DictReader(f, delimiter=delim))


def jetzt():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


# ------------------------------------------------------------------------------------------------------------------------------- Testergebnisse
def junit_ergebnisse(root):
    """{(Klassenkurzname, Testname): PASSED|FAILED|SKIPPED} aus Gradle-JUnit-XML."""
    out = {}
    for f in (Path(root) / "backend").glob("**/build/test-results/test/TEST-*.xml"):
        try:
            tree = ET.parse(f)
        except ET.ParseError:
            continue
        for tc in tree.getroot().iter("testcase"):
            cls = tc.get("classname", "").rsplit(".", 1)[-1]
            name = re.sub(r"\(\)$", "", tc.get("name", ""))
            st = "FAILED" if tc.find("failure") is not None or tc.find("error") is not None else "SKIPPED" if tc.find("skipped") is not None else "PASSED"
            if out.get((cls, name)) != "FAILED":
                out[(cls, name)] = st
    return out


def vitest_ergebnisse(dateien, root):
    """Liest JSON-Berichte von `vitest --reporter=json`: {(relativer Pfad, Titel): PASSED|FAILED|SKIPPED}."""
    out = {}
    for d in dateien:
        j = json.loads(Path(d).read_text(encoding="utf-8"))
        for fx in j.get("testResults", []):
            try:
                rel = str(Path(fx["name"]).resolve().relative_to(Path(root).resolve()))
            except ValueError:
                continue
            for t in fx.get("assertionResults", []):
                st = {"passed": "PASSED", "failed": "FAILED"}.get(t.get("status"), "SKIPPED")
                for titel in {t.get("title", ""), t.get("fullName", "")}:
                    if titel and out.get((rel, titel)) != "FAILED":
                        out[(rel, titel)] = st
    return out


class Ergebnisse:
    """Alle verfügbaren Ergebnisse. `lauf` ist die JSON-Datei aus `tools/nachweis_p1.py lauf` ({"build":..., "python": {"pfad::name": status}, "vitest": {...}})."""

    def __init__(self, root, lauf_datei=None, vitest_dateien=()):
        self.root = Path(root)
        self.junit = junit_ergebnisse(self.root)
        self.lauf = json.loads(Path(lauf_datei).read_text(encoding="utf-8")) if lauf_datei and Path(lauf_datei).is_file() else {}
        self.python = self.lauf.get("python", {})
        self.vitest = {tuple(k.split("::", 1)): v for k, v in self.lauf.get("vitest", {}).items()}
        self.vitest.update(vitest_ergebnisse(vitest_dateien, self.root))
        self.build = self.lauf.get("build")

    def status(self, token):
        """PASSED, FAILED, SKIPPED oder None (kein Lauf-Ergebnis vorhanden)."""
        path, name = token.split("::", 1)
        name = name.split("[")[0]
        if path.endswith(".kt"):
            return self.junit.get((Path(path).stem, name))
        if path.endswith(".py"):
            return self.python.get(f"{path}::{name}")
        if path.endswith((".ts", ".tsx")):
            if (path, name) in self.vitest:
                return self.vitest[(path, name)]
            treffer = [v for (p, t), v in self.vitest.items() if p == path and name in t]
            if treffer:
                return "FAILED" if "FAILED" in treffer else "PASSED" if "PASSED" in treffer else "SKIPPED"
        return None  # Dart und alles Übrige: nicht ausgeführt


def token_vorhanden(token, root):
    if token.startswith("geplant:") or "::" not in token:
        return False
    path, name = token.split("::", 1)
    p = Path(root) / path
    return p.is_file() and name.split("[")[0] in p.read_text(encoding="utf-8", errors="replace")


# ------------------------------------------------------------------------------------------------------------------------------- Evidenz
PFLICHTFELDER = ("source_key", "test", "software_build", "migration_state", "revisions", "seed", "roles_and_tenants", "soll_ist", "event_trace_ids", "result", "defect_class", "responsible",
                 "acceptance_decision")


def evidenz_laden(root):
    """Alle Evidenzdatensätze: Liste von {pfad, record, fehler}. Pflichtfelder (Kap. 08 §8.4) und Personendatenprüfung je Datensatz."""
    sys.path.insert(0, str(REPO / "tests" / "acceptance"))
    from taktovia_at import evidence as ev
    out = []
    for f in sorted((Path(root) / "tests" / "acceptance" / "evidence").glob("**/*.json")):
        if f.name == "zusammenfassung.json":
            continue
        fehler = []
        try:
            r = json.loads(f.read_text(encoding="utf-8"))
        except ValueError:
            out.append({"pfad": f, "record": None, "fehler": ["kein gültiges JSON"]})
            continue
        for k in PFLICHTFELDER:
            if k not in r:
                fehler.append(f"Pflichtfeld '{k}' fehlt")
        if r.get("result") not in ("PASSED", "FAILED", "NOT_EXECUTED"):
            fehler.append("result ungültig")
        fehler += [f"Personendaten: {x}" for x in ev.check_no_personal_data(r)]
        out.append({"pfad": f, "record": r, "fehler": fehler})
    return out


def evidenz_nach_testid(eintraege):
    """{test_id: neuester gültiger Datensatz}; neuester = größte Datei-Änderungszeit."""
    out = {}
    for e in eintraege:
        r = e["record"]
        if not r or e["fehler"]:
            continue
        tid = r["test"]["id"] if isinstance(r.get("test"), dict) else None
        if tid and (tid not in out or e["pfad"].stat().st_mtime >= out[tid]["pfad"].stat().st_mtime):
            out[tid] = e
    return out


def migrationsstand(r):
    m = r.get("migration_state") or {}
    return str(m.get("version") or m.get("sha256") or m.get("checksum") or m.get("applied") or json.dumps(m, sort_keys=True))[:40]


# ------------------------------------------------------------------------------------------------------------------------------- Mängel
MAENGEL_SPALTEN = ("id", "klasse", "titel", "source_keys", "workaround", "workaround_freigegeben", "termin", "massnahmenplan", "status", "quelle")


def maengel(root):
    p = Path(root) / "docs" / "umsetzungsplan" / "nachweise" / "maengel.csv"
    return rd(p) if p.is_file() else []


def maengel_pruefen(liste):
    """A: darf nicht offen sein; B: freigegebener Workaround und Termin; C: Maßnahmenplan. Rückgabe: Verstöße."""
    v = []
    for m in liste:
        if m["status"] not in ("offen", "behoben"):
            v.append(f"Mangel {m['id']}: Status '{m['status']}' ungültig")
            continue
        if m["status"] == "behoben":
            continue
        k = m["klasse"]
        if k == "A":
            v.append(f"Mangel {m['id']} (Klasse A) ist offen: blockiert die Produktion")
        elif k == "B" and not (m["workaround"].strip() and m["workaround_freigegeben"].strip().lower() == "ja" and m["termin"].strip()):
            v.append(f"Mangel {m['id']} (Klasse B) ohne freigegebenen Workaround oder verbindlichen Termin")
        elif k == "C" and not m["massnahmenplan"].strip():
            v.append(f"Mangel {m['id']} (Klasse C) ohne Maßnahmenplan")
        elif k not in ("A", "B", "C"):
            v.append(f"Mangel {m['id']}: Klasse '{k}' ungültig")
    return v


# ------------------------------------------------------------------------------------------------------------------------------- Matrix
def vertrag_flags(root, pfad):
    """(negativ_verlangt, konkurrierend_verlangt) aus dem Detailvertrag: Zeile `- **Negativ:**` bzw. `- **Konkurrierend:**` ohne „nicht zutreffend“ und nicht leer."""
    p = Path(root) / pfad
    if not pfad or not p.is_file():
        return None, None
    t = p.read_text(encoding="utf-8")

    def flag(name):
        m = re.search(rf"^- \*\*{name}:\*\*\s*(.+)$", t, re.M)
        return None if not m else not re.match(r"(?i)\s*(nicht zutreffend|entfällt|keine)", m.group(1))
    return flag("Negativ"), flag("Konkurrierend")


def p1_zeilen(root):
    return [r for r in rd(Path(root) / "docs/umsetzungsplan/arbeitspakete.csv") if r["phase_original"] == "P1" and r["prioritaet_original"] == "MUSS"]


def matrix(root=REPO, ergebnisse=None, evidenz=None, abnahme_build=None, maengel_liste=None):
    """Nachweismatrix P1. Rückgabe: (Zeilen, Hinweise). Je Zeile `ergebnis`: GRUEN (mindestens ein ausgeführter grüner positiver Test, kein roter), ROT, OHNE_LAUF (Tests vorhanden,
    kein Ergebnis), KEIN_TEST (kein ausführbarer Testfall) oder EVIDENZ_ABGEWIESEN (nur Evidenz eines anderen Builds)."""
    root = Path(root)
    ergebnisse = ergebnisse or Ergebnisse(root)
    eintraege = evidenz if evidenz is not None else evidenz_laden(root)
    nach_id = evidenz_nach_testid(eintraege)
    nach_key = {}
    for e in nach_id.values():
        for k in e["record"].get("source_key", []):
            nach_key.setdefault(k, []).append(e)
    e2e = {r["test_id_original"]: r for r in rd(root / "docs/pflichtenheft/e2e.csv")} if (root / "docs/pflichtenheft/e2e.csv").is_file() else {}
    maengel_liste = maengel(root) if maengel_liste is None else maengel_liste
    zeilen, hinweise = [], []
    for r in p1_zeilen(root):
        tokens = [t.strip() for t in r["testfall"].split(";") if t.strip()]
        datei = [t for t in tokens if "::" in t and not t.startswith("geplant:")]
        ref = [t for t in tokens if KATALOG_ID.match(t)]
        vorhanden = [t for t in datei if token_vorhanden(t, root)]
        stat = [(t, ergebnisse.status(t)) for t in vorhanden]
        gruen = [t for t, s in stat if s == "PASSED"]
        rot = [t for t, s in stat if s == "FAILED"]
        ohne = [t for t, s in stat if s in (None, "SKIPPED")]
        evid = []
        for tid in ref:
            e = nach_id.get(tid)
            if e:
                evid.append((tid, e))
        for e in nach_key.get(r["source_key"], []):
            if e not in [x[1] for x in evid]:
                evid.append((e["record"]["test"]["id"], e))
        gruen_ev = [t for t, e in evid if e["record"]["result"] == "PASSED"]
        rot_ev = [t for t, e in evid if e["record"]["result"] == "FAILED"]
        abgewiesen = [t for t, e in evid if abnahme_build and e["record"]["software_build"].get("build_id") != abnahme_build]
        gruen_ev = [t for t in gruen_ev if t not in abgewiesen]
        builds = sorted({e["record"]["software_build"].get("build_id", "?") for _, e in evid})
        if ergebnisse.build and gruen:
            builds = sorted(set(builds) | {ergebnisse.build})
        if rot or rot_ev:
            ergebnis = "ROT"
        elif gruen or gruen_ev:
            ergebnis = "GRUEN"
        elif abgewiesen:
            ergebnis = "EVIDENZ_ABGEWIESEN"
        elif vorhanden or evid or ref:
            ergebnis = "OHNE_LAUF"
        else:
            ergebnis = "KEIN_TEST"
        neg, konk = vertrag_flags(root, r["detailvertrag"])
        if ergebnis == "GRUEN" and ((neg or konk) and len(gruen) + len(gruen_ev) < 2):
            hinweise.append(f"{r['source_key']}: negativer/konkurrierender Test verlangt, aber nur {len(gruen) + len(gruen_ev)} grüner Test belegt")
        offene = sorted({m["klasse"] for m in maengel_liste if m["status"] == "offen" and r["source_key"] in m["source_keys"].split(",")})
        zeilen.append({
            "source_key": r["source_key"], "meilenstein": r["meilenstein"], "arbeitspaket": r["arbeitspaket"], "status_arbeitspaket": r["status"], "detailvertrag": r["detailvertrag"],
            "tests_gesamt": len(tokens), "tests_ausfuehrbar": len(vorhanden) + len(ref), "tests_gruen": len(gruen) + len(gruen_ev), "tests_rot": len(rot) + len(rot_ev), "tests_ohne_lauf": len(ohne),
            "referenztests": ",".join(ref), "evidenz": ",".join(sorted({str(e["pfad"].relative_to(root)) for _, e in evid})), "build": ",".join(builds),
            "migrationsstand": ",".join(sorted({migrationsstand(e["record"]) for _, e in evid})), "letzter_lauf": ",".join(sorted({e["record"].get("executed_at", "") for _, e in evid})) or (ergebnisse.lauf.get("erstellt", "") if gruen else ""),
            "negativ_verlangt": "" if neg is None else ("ja" if neg else "nein"), "konkurrierend_verlangt": "" if konk is None else ("ja" if konk else "nein"),
            "mangelklasse_offen": ",".join(offene), "ergebnis": ergebnis})
    return zeilen, hinweise


def matrix_csv(zeilen):
    out = io.StringIO()
    w = csv.DictWriter(out, fieldnames=MATRIX_SPALTEN, delimiter=";", lineterminator="\n")
    w.writeheader()
    w.writerows(zeilen)
    return out.getvalue()


# ------------------------------------------------------------------------------------------------------------------------------- e2e.csv
def e2e_pruefen(root, abnahme_build=None, evidenz=None, pflicht_at=PFLICHT_AT):
    """AT-23, AT-25, AT-53, AT-54, AT-59, AT-61 in e2e.csv: Eintrag mit Build, Evidenz und Entscheidung, ausschließlich aus Testläufen (Evidenzdatei existiert, Lauf-ID und Build passen)."""
    root = Path(root)
    verstoesse = []
    p = root / "docs/pflichtenheft/e2e.csv"
    if not p.is_file():
        return ["docs/pflichtenheft/e2e.csv fehlt"]
    zeilen = {r["test_id_original"]: r for r in rd(p)}
    eintraege = evidenz if evidenz is not None else evidenz_laden(root)
    for tid in pflicht_at:
        r = zeilen.get(tid)
        if r is None or not E2E_ENTRAG.match(r["status"]) or not r["build"] or not r["nachweis"] or not r["freigabe"]:
            verstoesse.append(f"{tid}: kein Eintrag mit Status, Build, Evidenz und Entscheidung in e2e.csv ({'Zeile fehlt' if r is None else r['status']})")
    for tid, r in zeilen.items():
        if r["status"] == "Nicht ausgeführt":
            continue
        run = r["nachweis"].split(";")[0].strip()
        passend = [e for e in eintraege if e["record"] and e["record"].get("run_id") == run and e["record"].get("test", {}).get("id") == tid]
        if not passend:
            verstoesse.append(f"{tid}: Eintrag stammt aus keinem Testlauf (Evidenz {run} nicht gefunden)")
        elif abnahme_build and passend[0]["record"]["software_build"].get("build_id") != abnahme_build:
            verstoesse.append(f"{tid}: Evidenz {run} stammt von Build {passend[0]['record']['software_build'].get('build_id')}, nicht vom Abnahme-Build {abnahme_build}")
        elif r["build"] != passend[0]["record"]["software_build"].get("build_id"):
            verstoesse.append(f"{tid}: Build in e2e.csv weicht vom Evidenzdatensatz ab")
    return verstoesse


# ------------------------------------------------------------------------------------------------------------------------------- Gates
def entscheidungen(root):
    """D-01 bis D-12: Titel (Kap. 07), Stand (Beschlussprotokolle unter docs/umsetzungsplan/beschluesse) und Wirkung (Entscheidungszeilen der P1-Arbeitspakete)."""
    root = Path(root)
    p = root / "docs/pflichtenheft/07-entscheidungen.md"
    titel = {}
    if p.is_file():
        for m in re.finditer(r"^## (D-\d\d) – (.+)$", p.read_text(encoding="utf-8"), re.M):
            titel[m.group(1)] = m.group(2).strip()
    beschl = {f.stem for f in (root / "docs/umsetzungsplan/beschluesse").glob("*.md") if not f.name.startswith("_")} if (root / "docs/umsetzungsplan/beschluesse").is_dir() else set()
    wirkung = {d: [] for d in titel}
    for f in sorted((root / "docs/umsetzungsplan/wp").glob("M[1-5]-*.md")) if (root / "docs/umsetzungsplan/wp").is_dir() else []:
        wp = None
        for z in f.read_text(encoding="utf-8").splitlines():
            m = re.match(r"^### (WP-M\d-\d+)", z)
            if m:
                wp = m.group(1)
            if wp and z.startswith("- **Entscheidungen:**"):
                for d in re.findall(r"D-\d\d", z):
                    if d in wirkung and wp not in [w for w, _ in wirkung[d]]:
                        wirkung[d].append((wp, z.replace("- **Entscheidungen:**", "").strip()))
    return [{"id": d, "titel": t, "stand": "beschlossen" if any(b.startswith(d) for b in beschl) else "offen (kein Beschluss protokolliert)",
             "wirkung": [w for w, _ in wirkung[d]][:12]} for d, t in sorted(titel.items())]


def partnerfaehigkeiten(root):
    """Gate Partnerfreigabe in P1 (OP-20, Arbeitsannahme): es gibt keine buchbaren Partner. Nachweis: (1) alle Operationen des Föderationsvertrags stehen in `api/contract-pending.txt`
    (nicht gebaut), (2) kein Hauptcode des Backends schreibt eine Partnerfähigkeit `EXTERNAL_BOOKING` (Registry existiert nicht). Rückgabe: (ok, Belege)."""
    root = Path(root)
    belege, ok = [], True
    fed = root / "api/federation.yaml"
    pend = (root / "api/contract-pending.txt").read_text(encoding="utf-8") if (root / "api/contract-pending.txt").is_file() else ""
    if fed.is_file():
        ops = re.findall(r"operationId:\s*(\w+)", fed.read_text(encoding="utf-8"))
        offen = [o for o in ops if not re.search(rf"^{o}\b", pend, re.M)]
        belege.append(f"{len(ops) - len(offen)} von {len(ops)} Operationen des Föderationsvertrags sind nicht gebaut (api/contract-pending.txt)")
        if offen:
            ok = False
            belege.append("gebaute Föderationsoperationen: " + ", ".join(offen))
    treffer = []
    for f in (root / "backend").glob("**/src/main/**/*.kt"):
        t = f.read_text(encoding="utf-8", errors="replace")
        if re.search(r"INSERT INTO taktovia\.journey_leg.*'EXTERNAL_BOOKING'", t) or re.search(r"capability\s*=\s*\"EXTERNAL_BOOKING\"", t):
            treffer.append(str(f.relative_to(root)))
    belege.append("kein Hauptcode setzt die Fähigkeit EXTERNAL_BOOKING" if not treffer else "EXTERNAL_BOOKING gesetzt in: " + ", ".join(treffer))
    return ok and not treffer, belege


def gate_nachweise(root):
    p = Path(root) / "docs/umsetzungsplan/nachweise/gate-nachweise.json"
    return json.loads(p.read_text(encoding="utf-8")) if p.is_file() else None


def gates_auswerten(root, zeilen, verstoesse_matrix):
    """Je Gate: Liste der Nachweise mit Stand (erbracht, teilweise, fehlt) und die Sperrwirkung. Verweise werden auf Existenz geprüft (fehlender Verweis = Nachweis nicht erbracht)."""
    root = Path(root)
    d = gate_nachweise(root)
    out = []
    for g in GATES:
        eintrag = (d or {}).get("gates", {}).get(g)
        nach = []
        for n in (eintrag or {}).get("nachweise", []):
            fehlt = [v for v in n.get("verweise", []) if not (root / v.split("#")[0]).exists()]
            stand = n.get("stand", "fehlt")
            if fehlt:
                stand = "fehlt"
            nach.append({**n, "stand": stand, "fehlende_verweise": fehlt})
        if g == "Partnerfreigabe":
            ok, belege = partnerfaehigkeiten(root)
            nach.append({"beschreibung": "Keine buchbaren Partner in P1: alle Partnerfähigkeiten sind nicht vorhanden bzw. deaktiviert (OP-20, Arbeitsannahme)", "verweise": ["api/federation.yaml", "api/contract-pending.txt"],
                         "stand": "erbracht" if ok else "fehlt", "grenze": "; ".join(belege)})
        if not nach:
            stand = "fehlt"
        else:
            stände = {n["stand"] for n in nach}
            stand = "erbracht" if stände == {"erbracht"} else "fehlt" if stände == {"fehlt"} else "teilweise"
        out.append({"gate": g, "stand": stand, "nachweise": nach, "sperrwirkung": SPERRWIRKUNG[g], "sperrwirkung_aktiv": stand != "erbracht"})
    return out


def bericht(root=REPO, lauf_datei=None, vitest_dateien=(), abnahme_build=None, maengel_liste=None, erwartet=202, pflicht_at=PFLICHT_AT):
    """Gesamtbericht: Matrix, e2e-Prüfung, Evidenzprüfung (Pflichtfelder, Personendaten, Build), Mängel, Gates, Entscheidungen. `verstoesse` leer = Gate nach den Regeln dieses Werkzeugs bestanden
    (die Abnahmeentscheidung bleibt beim Auftraggeber)."""
    root = Path(root)
    erg = Ergebnisse(root, lauf_datei, vitest_dateien)
    eintraege = evidenz_laden(root)
    abnahme_build = abnahme_build or erg.build
    liste = maengel(root) if maengel_liste is None else maengel_liste
    zeilen, hinweise = matrix(root, erg, eintraege, abnahme_build, liste)
    v = []
    anzahl = len(zeilen)
    if anzahl != erwartet:
        v.append(f"Nachweismatrix enthält {anzahl} statt {erwartet} P1-Anforderungen")
    for z in zeilen:
        if z["ergebnis"] != "GRUEN":
            v.append(f"{z['source_key']}: {z['ergebnis']} (Arbeitspaket {z['arbeitspaket']}, Status {z['status_arbeitspaket']})")
    v += hinweise
    for e in eintraege:
        if e["fehler"]:
            v.append(f"Evidenz {e['pfad'].relative_to(root)}: {'; '.join(e['fehler'])}")
    gebaut = {e["record"]["software_build"].get("build_id") for e in eintraege if e["record"] and not e["fehler"]}
    if abnahme_build is None and len(gebaut) > 1:
        v.append(f"Evidenz stammt von {len(gebaut)} verschiedenen Builds (kein Abnahme-Build festgelegt)")
    if abnahme_build:
        for e in eintraege:
            if e["record"] and not e["fehler"] and e["record"]["software_build"].get("build_id") != abnahme_build:
                v.append(f"Evidenz {e['pfad'].relative_to(root)} stammt von Build {e['record']['software_build'].get('build_id')}, nicht vom Abnahme-Build {abnahme_build}: abgewiesen")
    v += e2e_pruefen(root, abnahme_build, eintraege, pflicht_at)
    v += maengel_pruefen(liste)
    gates = gates_auswerten(root, zeilen, v)
    for g in gates:
        if g["stand"] != "erbracht":
            v.append(f"Gate {g['gate']}: Nachweis {g['stand']} (Sperrwirkung: {g['sperrwirkung']})")
    return {"erstellt": jetzt(), "abnahme_build": abnahme_build, "matrix": zeilen, "gates": gates, "maengel": liste, "entscheidungen": entscheidungen(root), "verstoesse": v,
            "zaehlung": {e: sum(1 for z in zeilen if z["ergebnis"] == e) for e in ("GRUEN", "ROT", "OHNE_LAUF", "KEIN_TEST", "EVIDENZ_ABGEWIESEN")}, "anforderungen": anzahl}


def bericht_markdown(b):
    z = b["zaehlung"]
    L = ["# M5-Gate-Bericht P1", "",
         f"Erzeugt von `tools/nachweis_p1.py` am {b['erstellt']}. Abnahme-Build: `{b['abnahme_build'] or 'nicht festgelegt'}`. Das Werkzeug entscheidet nichts; die **Abnahmeentscheidung trifft der Auftraggeber** (D-12).", "",
         f"**Ergebnis nach den Regeln des Werkzeugs: {'GATE NICHT BESTANDEN' if b['verstoesse'] else 'keine Verstöße'}** ({len(b['verstoesse'])} Verstöße).", "",
         "## 1. Nachweismatrix P1", "",
         f"{b['anforderungen']} P1-MUSS-Anforderungen: {z['GRUEN']} grün, {z['ROT']} rot, {z['OHNE_LAUF']} ohne Lauf, {z['KEIN_TEST']} ohne ausführbaren Test, {z['EVIDENZ_ABGEWIESEN']} mit abgewiesener Evidenz. "
         "Die vollständige Matrix steht in `docs/umsetzungsplan/nachweise/nachweismatrix-p1.csv`. Grün heißt: mindestens ein ausgeführter, grüner Test (JUnit, Python, Vitest) oder eine grüne Evidenz des Abnahme-Builds.", "",
         "| Meilenstein | Anforderungen | grün | nicht grün |", "|---|---:|---:|---:|"]
    for m in sorted({x["meilenstein"] for x in b["matrix"]}):
        zs = [x for x in b["matrix"] if x["meilenstein"] == m]
        g = sum(1 for x in zs if x["ergebnis"] == "GRUEN")
        L.append(f"| {m} | {len(zs)} | {g} | {len(zs) - g} |")
    L += ["", "## 2. Gates nach Kap. 08 §8.4", ""]
    for i, g in enumerate(b["gates"], 1):
        L += [f"### 2.{i} Gate {g['gate']}: {g['stand']}", "", f"**Sperrwirkung:** {g['sperrwirkung']}{' (aktiv)' if g['sperrwirkung_aktiv'] else ''}", ""]
        for n in g["nachweise"]:
            L.append(f"- [{n['stand']}] {n['beschreibung']}" + (f" (Verweise: {', '.join('`' + v + '`' for v in n.get('verweise', []))})" if n.get("verweise") else "") + (f". Grenze: {n['grenze']}" if n.get("grenze") else ""))
            if n.get("fehlende_verweise"):
                L.append(f"  - fehlende Verweise: {', '.join(n['fehlende_verweise'])}")
        L.append("")
    L += ["## 3. Mängelliste", "", "| Nr. | Klasse | Titel | Workaround (freigegeben) | Termin | Maßnahmenplan | Status |", "|---|---|---|---|---|---|---|"]
    for m in b["maengel"]:
        L.append(f"| {m['id']} | {m['klasse']} | {m['titel']} | {m['workaround'] or '–'} ({m['workaround_freigegeben'] or 'nein'}) | {m['termin'] or 'durch Auftraggeber festzulegen'} | {m['massnahmenplan'] or '–'} | {m['status']} |")
    L += ["", "## 4. Entscheidungen D-01 bis D-12", "", "| Nr. | Gegenstand | Stand | Wirkung auf die Produktivschaltung (Arbeitspakete mit Entscheidungsbezug) |", "|---|---|---|---|"]
    for d in b["entscheidungen"]:
        L.append(f"| {d['id']} | {d['titel']} | {d['stand']} | {', '.join(d['wirkung']) or 'kein P1-Arbeitspaket nennt sie'} |")
    L += ["", "## 5. Verstöße gegen die Gate-Regeln", ""]
    gruppen = {}
    for x in b["verstoesse"]:
        gruppen.setdefault(re.split(r"[:( ]", x, maxsplit=1)[0] if re.match(r"^LH-", x) else "Sonstige", []).append(x)
    ohne_anf = [x for x in b["verstoesse"] if not x.startswith("LH-")]
    L.append(f"Anforderungen nicht grün: {sum(1 for x in b['verstoesse'] if x.startswith('LH-'))} (Liste in der Matrix, Spalte `ergebnis`).")
    L.append("")
    for x in ohne_anf:
        L.append(f"- {x}")
    return "\n".join(L) + "\n"


# ------------------------------------------------------------------------------------------------------------------------------- Python-Lauf
RUNNER = r'''
import json, sys, unittest, inspect
from pathlib import Path
root, rel, pattern, out = Path(sys.argv[1]), sys.argv[2], sys.argv[3], sys.argv[4]
sys.path.insert(0, str(Path.cwd()))
class R(unittest.TextTestResult):
    def __init__(self, *a, **k):
        super().__init__(*a, **k); self.s = {}
    def _k(self, t):
        try:
            f = Path(inspect.getsourcefile(type(t))).resolve().relative_to(root.resolve())
        except Exception:
            return None
        return f"{f}::{getattr(t, '_testMethodName', str(t))}"
    def _set(self, t, st):
        k = self._k(t)
        if k and self.s.get(k) != "FAILED":
            self.s[k] = st
    def addSuccess(self, t): super().addSuccess(t); self._set(t, "PASSED")
    def addFailure(self, t, e): super().addFailure(t, e); self._set(t, "FAILED")
    def addError(self, t, e): super().addError(t, e); self._set(t, "FAILED")
    def addSkip(self, t, r): super().addSkip(t, r); self._set(t, "SKIPPED")
suite = unittest.defaultTestLoader.discover(".", pattern=pattern)
res = unittest.TextTestRunner(resultclass=R, verbosity=0, stream=open("/dev/null", "w")).run(suite)
Path(out).write_text(json.dumps(res.s))
'''


def lauf(root, verzeichnisse, vitest_dateien, out, build):
    root = Path(root)
    ergebnis = {"python": {}, "vitest": {}}
    for d in verzeichnisse:
        tmp = Path(out).with_suffix(f".{abs(hash(d))}.tmp")
        t0 = datetime.now()
        r = subprocess.run([sys.executable, "-c", RUNNER, str(root), d, "test_*.py", str(tmp)], cwd=root / d, capture_output=True, text=True)
        if tmp.is_file():
            ergebnis["python"].update(json.loads(tmp.read_text()))
            tmp.unlink()
        print(f"{d}: {sum(1 for v in ergebnis['python'].values())} Ergebnisse gesamt, Exit {r.returncode}, {(datetime.now() - t0).seconds}s", file=sys.stderr)
    for k, v in vitest_ergebnisse(vitest_dateien, root).items():
        ergebnis["vitest"]["::".join(k)] = v
    ergebnis["build"] = build
    ergebnis["erstellt"] = jetzt()
    Path(out).write_text(json.dumps(ergebnis, indent=1, ensure_ascii=False, sort_keys=True), encoding="utf-8")
    return ergebnis


# ------------------------------------------------------------------------------------------------------------------------------- Fachmodell-Review
def fachmodell_anhang(root):
    """Maschinell erzeugter Anhang des Review-Protokolls: Zustandsautomaten und Preis-/Kostenregeln aus den Detailverträgen der P1-Anforderungen mit dem ausführbaren Test, der sie sichert."""
    root = Path(root)
    zeilen = []
    for r in p1_zeilen(root):
        p = root / r["detailvertrag"]
        if not r["detailvertrag"] or not p.is_file():
            continue
        t = p.read_text(encoding="utf-8")
        m = re.search(r"^- \*\*Zustandsautomat[^:\n]*:\*\*\s*(.+)$", t, re.M)
        if not m or re.match(r"(?i)\s*(keine?r?\b|nicht zutreffend|entfällt)", m.group(1)):
            continue
        tests = [x.strip() for x in r["testfall"].split(";") if "::" in x and not x.strip().startswith("geplant:")]
        zust = [x for x in tests if re.search(r"(?i)state|zustand|transition|uebergang|statusautomat|lifecycle|automat", x.split("::", 1)[1] + x.split("::")[0])]
        zeilen.append({"source_key": r["source_key"], "wp": r["arbeitspaket"], "automat": re.sub(r"\s+", " ", m.group(1))[:160], "zustandstests": [x for x in zust if token_vorhanden(x, root)][:3],
                       "status": r["status"]})
    return zeilen


GELD_BEFOERDERUNG = ("D-01", "D-03", "D-04", "D-06", "D-07")
REGEL_TESTS = (
    ("Geld ohne Gleitkomma (bigint in kleinster Einheit, Währung mit Exponent)", "db/tests/test_geld_zeit_spalten.py"),
    ("Preis: Angebotspreis, Inhaltshash und Bindung (fare_quote)", "db/tests/test_fare_quote.py"),
    ("Preis: Tarifrevisionen und Preisregeln", "db/tests/tarif_helpers.py"),
    ("Kosten: Kostenereignis und Journal, Gegenereignis statt Korrektur", "db/tests/test_cost_event_journal.py"),
    ("Kosten: Allokation centgenau", "db/tests/test_allocation.py"),
    ("Kosten: Fiskalperiode und späte Gutschrift", "db/tests/test_fiscal_period.py"),
    ("Zahlung: Zahlungsautomat und unbestimmte Zustände", "db/tests/payment_helpers.py"),
    ("Kapazität: Hold-Zustandsautomat und Zusagefenster", "db/tests/test_capacity_hold_state.py"),
    ("Entitäten: Dateninventar je Tabelle und Spalte", "db/tests/test_07_dateninventar.py"),
    ("Entitäten: Mandantenschlüssel und Zeilenschutz", "db/tests/test_03_rls.py"),
)


def fachmodell_markdown(root=REPO):
    root = Path(root)
    anhang = fachmodell_anhang(root)
    ent = entscheidungen(root)
    L = ["# Review-Protokoll Gate Fachmodell (WP-M5-31)", "",
         f"Erzeugt von `tools/nachweis_p1.py fachmodell --write` am {jetzt()}. Gegenstand nach Kap. 08 §8.4: Review der in M1 bis M4 umgesetzten Zustandsautomaten, Entitäten sowie Preis- und Kostenregeln "
         "gegen die Detailverträge, mit Liste der offenen Geschäftsentscheidungen und der dadurch gesperrten produktiven Aktivierungen.", "",
         "**Grenze (ehrlich):** Dies ist eine **Werkzeug- und Agentenprüfung** der Rückverfolgbarkeit (Vertrag, ausführbarer Test, Entscheidungsbezug), **kein Fachreview** durch benannte Personen: "
         "Reviewer sind nicht benannt (D-12). Das Werkzeug prüft nicht, ob ein Zustandsautomat fachlich richtig ist, sondern dass der Vertrag ihn nennt und ein Test ihn sichert. Das Gate Fachmodell gilt deshalb als **teilweise**.", "",
         "## 1. Zustandsautomaten", "",
         f"{len(anhang)} P1-Anforderungen nennen im Detailvertrag einen Zustandsautomaten oder ein Schema. Je Zeile: Vertrag, Arbeitspaket, Automat (Auszug) und die Tests der Testfallliste, deren Name einen Zustand oder Übergang benennt (existiert in der Datei).", "",
         "| Anforderung | Arbeitspaket | Zustandsautomat (Auszug aus dem Detailvertrag) | Test mit Zustandsbezug | Status |", "|---|---|---|---|---|"]
    for z in anhang:
        L.append(f"| {z['source_key']} | {z['wp']} | {z['automat'].replace('|', '/')} | {'; '.join('`' + x + '`' for x in z['zustandstests']) or '**keiner benannt**'} | {z['status']} |")
    ohne = [z for z in anhang if not z["zustandstests"]]
    L += ["", f"Ohne benannten Zustandstest: {len(ohne)} von {len(anhang)} (kein Befund an sich: der Zustand kann durch Tests ohne Zustandsbezug im Namen gesichert sein, ist aber hier nicht belegt).", "",
          "## 2. Entitäten sowie Preis- und Kostenregeln", "", "Die folgenden Regeln sind durch Datenbank- oder Anwendungstests gesichert; die Tabelle weist die Prüfdatei aus (Existenz vom Werkzeug geprüft).", "",
          "| Regel | Prüfdatei | Vorhanden |", "|---|---|---|"]
    for regel, datei in REGEL_TESTS:
        L.append(f"| {regel} | `{datei}` | {'ja' if (root / datei).is_file() else '**nein**'} |")
    L += ["", "## 3. Offene Geschäftsentscheidungen und gesperrte produktive Aktivierungen", "",
          "Aus den Entscheidungszeilen der P1-Arbeitspakete (`docs/umsetzungsplan/wp/M1` bis `M5`) und den Beschlussprotokollen (`docs/umsetzungsplan/beschluesse`, derzeit keines). "
          "**Jede ungeklärte Geld- oder Beförderungsrolle (D-01, D-03, D-04, D-06, D-07) verhindert ihre produktive Aktivierung:**", "",
          "| Entscheidung | Gegenstand | Stand | Gesperrte produktive Aktivierung | Arbeitspakete mit Bezug |", "|---|---|---|---|---|"]
    sperre = {
        "D-01": "Verkauf, Beförderung und Verantwortung der Reisekette: keine produktive Mehrbetreiber- oder Partnerbuchung, keine Zusage fremder Beförderungsleistung",
        "D-02": "Grad der Mandantenisolation über den Stand der P1-Isolation hinaus: kein Mehrbetreiberbetrieb auf gemeinsamer Plattform",
        "D-03": "Gemeinsamer Checkout und Geldfluss, PSP: keine produktive Zahlung und keine Auszahlung (Simulator statt PSP-Sandbox)",
        "D-04": "Anschlussrisiko und unvollständige Mehrbetreiberbuchung: keine produktive Mehrbetreiberbuchung",
        "D-05": "Pilot, Peaklast, SLA und Betrieb: kein Bau der Produktionsumgebung beim Zielanbieter, keine verbindlichen Lastziele",
        "D-06": "Finanzumfang und ERP-Anbindung: keine produktive Finanzbuchung und kein produktiver ERP-Export",
        "D-07": "Einheitliche Kostenbewertung und interne Verrechnung: keine produktive Kostenumlage zwischen Rechtsträgern",
        "D-08": "Partnerstandards und tatsächliche Buchungsrechte: alle Partnerfähigkeiten bleiben deaktiviert",
        "D-09": "Routing, Karten und Telematik: keine produktive Fußwegberechnung und Routing-Quelle",
        "D-10": "Fahrerdaten, ETA und Mitbestimmung: keine Aktivierung der Geozone und keine fahrerbezogene Auswertung",
        "D-11": "Aufbewahrung, Datenrechte und Notbetrieb: keine Produktivfreigabe personenbezogener Daten, keine fristgesteuerten Löschläufe",
        "D-12": "Reihenfolge der Lieferung und Quellbereinigung: keine Abnahmeentscheidung, keine benannten Reviewer",
    }
    for d in ent:
        L.append(f"| {d['id']} | {d['titel']} | {d['stand']} | {sperre.get(d['id'], 'siehe Entscheidungszeilen der Arbeitspakete')} | {', '.join(d['wirkung']) or '–'} |")
    L += ["", "## 4. Ergebnis", "",
          f"- Gate Fachmodell: **teilweise**. Rückverfolgbarkeit der Zustandsautomaten ({len(anhang) - len(ohne)} von {len(anhang)} mit benanntem Zustandstest) und der Regeln aus Abschnitt 2 ist belegt; ein Fachreview durch benannte Personen steht aus.",
          "- Die produktive Aktivierung von Geld- und Beförderungsfunktionen bleibt gesperrt, bis D-01, D-03, D-04, D-06 und D-07 beschlossen und protokolliert sind (Vorlage `docs/umsetzungsplan/beschluesse/_vorlage.md`).", ""]
    return "\n".join(L)


def fachmodell_pruefen(text, root=REPO):
    """Prüfung des Review-Protokolls (AK 6): vorhanden, jede Geld-/Beförderungsentscheidung mit gesperrter Aktivierung benannt, alle Prüfdateien vorhanden."""
    v = []
    for d in GELD_BEFOERDERUNG:
        m = re.search(rf"^\| {d} \| .* \| (.+) \| .* \|$", text, re.M)
        if not m or not m.group(1).strip() or "siehe Entscheidungszeilen" in m.group(1):
            v.append(f"{d}: gesperrte produktive Aktivierung nicht benannt")
    if "**nein**" in text:
        v.append("Review-Protokoll nennt eine fehlende Prüfdatei")
    return v


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sp = ap.add_subparsers(dest="cmd", required=True)
    a = sp.add_parser("lauf")
    a.add_argument("--python", action="append", default=[])
    a.add_argument("--vitest-json", action="append", default=[])
    a.add_argument("--out", default=str(REPO / "tests/acceptance/evidence/m5/lauf-nachweis.json"))
    a.add_argument("--build", default=None)
    b = sp.add_parser("bericht")
    b.add_argument("--build")
    b.add_argument("--lauf", default=str(REPO / "tests/acceptance/evidence/m5/lauf-nachweis.json"))
    b.add_argument("--vitest-json", action="append", default=[])
    b.add_argument("--write", action="store_true")
    f = sp.add_parser("fachmodell")
    f.add_argument("--write", action="store_true")
    x = ap.parse_args(argv)
    if x.cmd == "lauf":
        build = x.build or subprocess.run(["git", "-C", str(REPO), "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
        lauf(REPO, x.python, x.vitest_json, x.out, build)
        return 0
    if x.cmd == "bericht":
        b = bericht(REPO, x.lauf, x.vitest_json, x.build)
        if x.write:
            n = REPO / "docs/umsetzungsplan/nachweise"
            (n / "nachweismatrix-p1.csv").write_text(matrix_csv(b["matrix"]), encoding="utf-8")
            (REPO / "docs/umsetzungsplan/gate-bericht-m5.md").write_text(bericht_markdown(b), encoding="utf-8")
        print(json.dumps({"abnahme_build": b["abnahme_build"], "zaehlung": b["zaehlung"], "verstoesse": len(b["verstoesse"]), "gates": {g["gate"]: g["stand"] for g in b["gates"]}}, ensure_ascii=False, indent=1))
        return 1 if b["verstoesse"] else 0
    if x.cmd == "fachmodell":
        md = fachmodell_markdown(REPO)
        if x.write:
            (REPO / "docs/umsetzungsplan/nachweise/fachmodell-review.md").write_text(md, encoding="utf-8")
        v = fachmodell_pruefen(md, REPO)
        print(json.dumps({"verstoesse": v, "zustandsautomaten": len(fachmodell_anhang(REPO))}, ensure_ascii=False, indent=1))
        return 1 if v else 0
    return 2


if __name__ == "__main__":
    sys.exit(main())
