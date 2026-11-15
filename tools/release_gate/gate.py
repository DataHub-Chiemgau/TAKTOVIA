#!/usr/bin/env python3
"""Release-Gate der CI (WP-M5-22, LH-6.20-QAT-002; Kap. 08 §8.4 "Fehlgeschlagene Pflichtprüfung verhindert Release").

  python tools/release_gate/gate.py run   [--ergebnisse DIR] [--out DIR] [--ueberspringen stufe,...] [--ausnahmen DATEI] [--stufen id,...] [--ci]
  python tools/release_gate/gate.py abdeckung [--strikt]      Zuordnung Fachfunktion -> Unit-/Integrations-/Contract-Test (maschinell geprüft)
  python tools/release_gate/gate.py verify <release-verzeichnis>   Signatur und Inhalt eines Release-Artefakts prüfen

Ein Release-Artefakt (`release-manifest.json` plus `release-manifest.sig`) entsteht nur, wenn jede Pflichtstufe aus
`pflichtpruefungen.json` tatsächlich ausgeführt und grün war. Eine Stufe darf nur mit einer gültigen, protokollierten Ausnahme
(Freigeber, Grund, Ablauf; `ausnahmen.json`) übersprungen werden; eine ausgeführte, rote Stufe ist nie ausnehmbar. Das Manifest trägt
Build-ID, Migrationsstand und die Liste der bestandenen Prüfungen und wird mit HMAC-SHA256 signiert (Schlüssel aus der Umgebung
`TAKTOVIA_RELEASE_SIGNING_KEY`, nie im Repository). Eine asymmetrische Signatur (Lieferkettensignatur, z. B. cosign) ist NICHT ausgeführt.

Ohne `--ci` (lokaler Probelauf) zählen extern nachzuweisende Prüfungen (Gitleaks, OSV-Scanner: eigene CI-Jobs) als "nicht ausgeführt"; das Ergebnis
ist dann nie freigabefähig und wird nicht signiert. Mit `--ci` liefert der Workflow ihr Ergebnis über `TAKTOVIA_GATE_EXTERN_<NAME>=success`.

Die JUnit-Ergebnisse des Backend-Builds (`backend/**/build/test-results`) und der Python-Läufe (`--ergebnisse`) sind Eingabe der JUnit-Prüfungen:
sie müssen aus demselben Lauf stammen (in CI als Artefakt des Backend-Jobs). Nur Standardbibliothek.
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import hashlib
import hmac
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
CONFIG = HERE / "pflichtpruefungen.json"
COVERAGE = HERE / "funktionsabdeckung.csv"
SEMANTIC = HERE / "semantische_faelle.csv"
CAPACITY = HERE / "kapazitaetsinvarianten.csv"
EXCEPTIONS = HERE / "ausnahmen.json"
MANIFEST = "release-manifest.json"
SIGNATURE = "release-manifest.sig"
KEY_ENV = "TAKTOVIA_RELEASE_SIGNING_KEY"
MAX_EXCEPTION_DAYS = 30            # Planungswert (OP-17): längste Laufzeit einer Ausnahme ab Erfassung
CONTRACT_RUNNER = ("ContractCoverageTest", "alle_m1_endpunkte_positiv_und_fehlerfall")
DB_TOKENS = re.compile(r"Fixture|TestDb|DataSource|\.admin\(|ProbeServer|IdentityEnv|HttpSupport|HttpWorld|psycopg|TestRestTemplate")
CONCURRENCY_TOKENS = re.compile(r"Executors\.new|CompletableFuture|Thread\(|thread\(|ThreadPoolExecutor|threading\.Thread")


# ----------------------------------------------------------------------------------------------------------------------------------
# Eingaben

def load_config(path: Path = CONFIG) -> dict:
    cfg = json.loads(path.read_text(encoding="utf-8"))
    ids = [s["id"] for s in cfg["stufen"]]
    if len(ids) != len(set(ids)):
        raise ValueError("Stufen-IDs nicht eindeutig")
    for s in cfg["stufen"]:
        if not s.get("pruefungen"):
            raise ValueError(f"Stufe {s['id']} ohne Prüfungen")
    return cfg


def read_csv(path: Path) -> list[dict]:
    with path.open(encoding="utf-8", newline="") as fh:
        return [r for r in csv.DictReader(fh, delimiter=";") if r and not (r.get(next(iter(r))) or "").startswith("#")]


Results = dict  # {(Klasse, Test): "PASSED"|"FAILED"|"SKIPPED"}


def load_junit(dirs: list[Path] | None = None, repo: Path = REPO) -> Results:
    """Liest alle JUnit-XML-Dateien (Gradle: `backend/**/build/test-results/test`, pytest: `--junitxml`). Schlüssel: (Klassenkurzname bzw. Dateiname ohne Pfad, Testname)."""
    files = list((repo / "backend").glob("**/build/test-results/test/TEST-*.xml"))
    for d in dirs or []:
        files += list(Path(d).glob("*.xml"))
    out: Results = {}
    for f in files:
        try:
            root = ET.parse(f).getroot()
        except ET.ParseError:
            continue
        for tc in root.iter("testcase"):
            cls = tc.get("classname", "").rsplit(".", 1)[-1]
            name = re.sub(r"\(\)$", "", tc.get("name", ""))
            bad = tc.find("failure") is not None or tc.find("error") is not None
            status = "FAILED" if bad else "SKIPPED" if tc.find("skipped") is not None else "PASSED"
            if out.get((cls, name)) != "FAILED":
                out[(cls, name)] = status
    return out


# ----------------------------------------------------------------------------------------------------------------------------------
# Prüfergebnisse

@dataclass
class CheckResult:
    id: str
    art: str
    status: str                 # PASSED | FAILED | NOT_RUN
    detail: str = ""
    dauer_s: float = 0.0

    @property
    def ok(self) -> bool:
        return self.status == "PASSED"


@dataclass
class StageResult:
    id: str
    name: str
    status: str                 # PASSED | FAILED | SKIPPED_WITH_EXCEPTION | SKIPPED_BLOCKED | NOT_RUN
    checks: list[CheckResult] = field(default_factory=list)
    ausnahme: dict | None = None
    detail: str = ""


@dataclass
class GateResult:
    stages: list[StageResult]
    problems: list[str]
    build_id: str
    migration_state: dict
    modus: str

    @property
    def passed(self) -> bool:
        """Alle ausgeführten Prüfungen grün, keine Stufe ohne gültige Ausnahme übersprungen. `NOT_RUN` gibt es nur für extern nachzuweisende Prüfungen im Probelauf; ein solcher Lauf ist nie freigabefähig."""
        return not self.problems and all(s.status in ("PASSED", "SKIPPED_WITH_EXCEPTION", "NOT_RUN") for s in self.stages)

    @property
    def release_faehig(self) -> bool:
        """Nur ein CI-Lauf ohne nicht ausgeführte Prüfung ist freigabefähig; ein Probelauf nie."""
        return self.passed and self.modus == "ci" and all(c.status != "NOT_RUN" for s in self.stages for c in s.checks)


# ----------------------------------------------------------------------------------------------------------------------------------
# Zuordnung Fachfunktion -> Test (maschinell prüfbar)

def _kotlin_defines(path: Path, name: str) -> bool:
    return bool(re.search(r"fun\s+`?" + re.escape(name) + r"`?\s*\(", path.read_text(encoding="utf-8")))


def _python_defines(path: Path, name: str) -> bool:
    return bool(re.search(r"^\s*(?:async\s+)?def\s+" + re.escape(name) + r"\s*\(", path.read_text(encoding="utf-8"), re.M))


def _operation_block(op: str, repo: Path) -> str | None:
    for f in sorted((repo / "api").glob("*.yaml")):
        text = f.read_text(encoding="utf-8")
        m = re.search(r"operationId:\s*" + re.escape(op) + r"\s*$", text, re.M)
        if m:
            nxt = re.search(r"operationId:", text[m.end():])
            return text[m.start(): m.end() + (nxt.start() if nxt else len(text))]
    return None


def _pending(repo: Path) -> set[str]:
    p = repo / "api" / "contract-pending.txt"
    return {l.split("#")[0].strip() for l in p.read_text(encoding="utf-8").splitlines() if l.split("#")[0].strip()}


def _contract_case_files(repo: Path) -> str:
    return "\n".join(f.read_text(encoding="utf-8") for f in (repo / "backend" / "app" / "src" / "test").glob("**/contract/*.kt"))


def _result_key(row: dict) -> tuple[str, str]:
    stem = Path(row["ort"]).stem
    return stem, row["name"]


def check_test_row(row: dict, repo: Path, results: Results | None, funde: list[str], who: str) -> None:
    """Prüft eine Zeile der Zuordnung: Test existiert, ist richtig eingeordnet und (mit `results`) grün gelaufen."""
    art, ort, name = row["art"], row.get("ort", ""), row["name"]
    if art == "contract":
        block = _operation_block(name, repo)
        if block is None:
            funde.append(f"{who}: Operation {name} steht in keiner api/*.yaml")
            return
        if name in _pending(repo):
            funde.append(f"{who}: Operation {name} ist noch in api/contract-pending.txt (nicht gebaut)")
        if f'ContractCase("{name}"' not in _contract_case_files(repo):
            funde.append(f"{who}: kein ContractCase für {name}")
        token = (row.get("token") or "").strip()
        if token and token not in block:
            funde.append(f"{who}: Vertrag von {name} dokumentiert {token} nicht")
        if results is not None and results.get(CONTRACT_RUNNER) != "PASSED":
            funde.append(f"{who}: Vertragslauf {CONTRACT_RUNNER[0]}.{CONTRACT_RUNNER[1]} nicht grün ({results.get(CONTRACT_RUNNER, 'kein Ergebnis')})")
        return
    path = repo / ort
    if not path.is_file():
        funde.append(f"{who}: Testdatei {ort} fehlt")
        return
    defined = _kotlin_defines(path, name) if art == "kotlin" else _python_defines(path, name) if art == "python" else None
    if defined is None:
        funde.append(f"{who}: unbekannte Art {art}")
        return
    if not defined:
        funde.append(f"{who}: Test {name} steht nicht in {ort}")
        return
    if row.get("stufe") == "unit" and DB_TOKENS.search(path.read_text(encoding="utf-8")):
        funde.append(f"{who}: {ort} nutzt Datenbank/HTTP-Fixtures und ist kein Unit-Test")
    if row.get("stufe") == "integration" and art == "kotlin" and not DB_TOKENS.search(path.read_text(encoding="utf-8")):
        funde.append(f"{who}: {ort} nutzt weder Datenbank noch HTTP und ist kein Integrationstest")
    if results is not None:
        st = results.get(_result_key(row))
        if st != "PASSED":
            funde.append(f"{who}: Test {Path(ort).stem}.{name} nicht grün ({st or 'kein Ergebnis'})")


def check_coverage(cfg: dict, results: Results | None = None, repo: Path = REPO, path: Path = COVERAGE) -> list[str]:
    """Jede Fachfunktion hat je Stufe (unit, integration, contract) mindestens einen vorhandenen Test. Leere Liste = vollständig."""
    funde: list[str] = []
    rows = read_csv(path)
    stufen = cfg["abdeckung"]["stufen"]
    for fn in cfg["abdeckung"]["funktionen"]:
        for stufe in stufen:
            sel = [r for r in rows if r["funktion"] == fn and r["stufe"] == stufe]
            if not sel:
                funde.append(f"{fn}: kein {stufe}-Test zugeordnet")
            for r in sel:
                check_test_row(r, repo, results, funde, f"{fn}/{stufe}")
    for r in rows:
        if r["funktion"] not in cfg["abdeckung"]["funktionen"]:
            funde.append(f"{r['funktion']}: Funktion nicht im Katalog der Kernfunktionen")
        if r["stufe"] not in stufen:
            funde.append(f"{r['funktion']}: unbekannte Stufe {r['stufe']}")
    return funde


def check_semantic(cfg: dict, results: Results | None = None, repo: Path = REPO, path: Path = SEMANTIC) -> list[str]:
    funde: list[str] = []
    rows = read_csv(path)
    for fall in cfg["semantische_faelle"]:
        sel = [r for r in rows if r["fall"] == fall]
        if not sel:
            funde.append(f"{fall}: kein Test zugeordnet")
        for r in sel:
            check_test_row({**r, "stufe": "sonstige"}, repo, results, funde, fall)
    for r in rows:
        if r["fall"] not in cfg["semantische_faelle"]:
            funde.append(f"{r['fall']}: Fall nicht im Katalog der Pflichtfälle")
    return funde


def check_capacity(cfg: dict, results: Results | None = None, repo: Path = REPO, path: Path = CAPACITY) -> list[str]:
    """Kapazitätsinvarianten (Kap. 04 §4.5) als konkurrierende Tests: Testdatei trägt Parallelitätsmittel und Startbarriere, der Test lief grün."""
    funde: list[str] = []
    rows = read_csv(path)
    have = {r["invariante"] for r in rows}
    for inv in cfg["kapazitaetsinvarianten"]:
        if inv not in have:
            funde.append(f"{inv}: kein konkurrierender Test zugeordnet")
    for r in rows:
        who = r["invariante"]
        check_test_row({**r, "stufe": "sonstige"}, repo, results, funde, who)
        p = repo / r["ort"]
        if p.is_file():
            text = p.read_text(encoding="utf-8")
            if not CONCURRENCY_TOKENS.search(text):
                funde.append(f"{who}: {r['ort']} enthält keine Threads/Executor (keine parallelen Transaktionen)")
            if int(r["min_parallel"]) < 2:
                funde.append(f"{who}: min_parallel {r['min_parallel']} < 2")
    return funde


# ----------------------------------------------------------------------------------------------------------------------------------
# Ausnahmen

def load_exceptions(path: Path = EXCEPTIONS) -> list[dict]:
    if not path.is_file():
        return []
    return json.loads(path.read_text(encoding="utf-8")).get("ausnahmen", [])


def valid_exception(stufe: str, ausnahmen: list[dict], now: dt.date) -> tuple[dict | None, str]:
    """Gültige Ausnahme für die Stufe oder der Grund, warum keine gilt. Freigeber, Grund und Ablauf sind alle drei Pflicht."""
    mine = [a for a in ausnahmen if a.get("stufe") == stufe]
    if not mine:
        return None, "keine Ausnahme protokolliert"
    why = []
    for a in mine:
        miss = [k for k in ("freigeber", "grund", "ablauf") if not str(a.get(k, "")).strip()]
        if miss:
            why.append("Angabe fehlt: " + ", ".join(miss))
            continue
        try:
            ablauf = dt.date.fromisoformat(str(a["ablauf"]))
            erfasst = dt.date.fromisoformat(str(a.get("erfasst", "")))
        except ValueError:
            why.append("Ablauf/Erfassung kein ISO-Datum (JJJJ-MM-TT)")
            continue
        if ablauf < now:
            why.append(f"abgelaufen am {ablauf}")
        elif (ablauf - erfasst).days > MAX_EXCEPTION_DAYS:
            why.append(f"Laufzeit über {MAX_EXCEPTION_DAYS} Tage")
        else:
            return a, ""
    return None, "; ".join(why)


# ----------------------------------------------------------------------------------------------------------------------------------
# Ausführung

def _fmt(argv: list[str], ctx: dict) -> list[str]:
    return [a.replace("{python}", sys.executable).replace("{ergebnisse}", str(ctx["ergebnisse"])) for a in argv]


def run_check(check: dict, cfg: dict, ctx: dict) -> CheckResult:
    art = check["art"]
    cid = check.get("id") or check.get("name") or art
    t = time.time()
    try:
        if art == "befehl":
            Path(ctx["ergebnisse"]).mkdir(parents=True, exist_ok=True)
            p = subprocess.run(_fmt(check["argv"], ctx), cwd=ctx["repo"] / check.get("cwd", "."), capture_output=True, text=True, timeout=check.get("timeout_s", 1800))
            tail = "\n".join((p.stdout + p.stderr).strip().splitlines()[-6:])
            return CheckResult(cid, art, "PASSED" if p.returncode == 0 else "FAILED", f"Exit {p.returncode}\n{tail}", time.time() - t)
        if art == "junit":
            res = ctx["junit"]()
            if not res:
                return CheckResult(cid, art, "FAILED", "keine JUnit-Ergebnisse vorhanden (Backend-Build zuerst ausführen)", time.time() - t)
            bad = []
            for klasse in check.get("klassen", []):
                cases = {k: v for k, v in res.items() if k[0] == klasse}
                if len(cases) < check.get("mindestens", 1):
                    bad.append(f"{klasse}: {len(cases)} Testfälle (erwartet mindestens {check.get('mindestens', 1)})")
                bad += [f"{k[0]}.{k[1]}: {v}" for k, v in sorted(cases.items()) if v != "PASSED"]
            for klasse, name in check.get("tests", []):
                if res.get((klasse, name)) != "PASSED":
                    bad.append(f"{klasse}.{name}: {res.get((klasse, name), 'kein Ergebnis')}")
            return CheckResult(cid, art, "FAILED" if bad else "PASSED", "; ".join(bad[:8]), time.time() - t)
        if art in ("funktionsabdeckung", "semantisch", "kapazitaet"):
            res = ctx["junit"]()
            if not res:
                return CheckResult(cid, art, "FAILED", "keine JUnit-Ergebnisse vorhanden", time.time() - t)
            funde = {"funktionsabdeckung": check_coverage, "semantisch": check_semantic, "kapazitaet": check_capacity}[art](cfg, res, ctx["repo"])
            return CheckResult(cid, art, "FAILED" if funde else "PASSED", "; ".join(funde[:8]), time.time() - t)
        if art == "extern":
            val = os.environ.get(f"TAKTOVIA_GATE_EXTERN_{check['name'].upper()}", "")
            if ctx["modus"] != "ci":
                return CheckResult(cid, art, "NOT_RUN", f"{check.get('beschreibung', '')} (läuft als eigener CI-Job, im lokalen Probelauf nicht ausgeführt)", time.time() - t)
            return CheckResult(cid, art, "PASSED" if val == "success" else "FAILED", f"CI-Ergebnis: {val or 'nicht übergeben'}", time.time() - t)
        return CheckResult(cid, art, "FAILED", f"unbekannte Prüfungsart {art}", time.time() - t)
    except subprocess.TimeoutExpired:
        return CheckResult(cid, art, "FAILED", "Zeitüberschreitung", time.time() - t)
    except Exception as e:  # noqa: BLE001 - jede Störung der Prüfung ist rot, nie grün
        return CheckResult(cid, art, "FAILED", f"{type(e).__name__}: {e}", time.time() - t)


def migration_state(repo: Path = REPO) -> dict:
    """Stand der Migrationsdateien: höchste Version, Anzahl, Prüfsumme über Namen und Inhalt (reproduzierbar je Build)."""
    files = sorted((repo / "db" / "migrations").glob("*.sql"))
    h = hashlib.sha256()
    versions = []
    for f in files:
        h.update(f.name.encode() + b"\0" + f.read_bytes() + b"\0")
        m = re.match(r"V(\d+)__", f.name)
        if m:
            versions.append(int(m.group(1)))
    return {"hoechste_version": max(versions) if versions else None, "anzahl_dateien": len(files), "sha256": h.hexdigest()}


def build_id(repo: Path = REPO) -> str:
    env = os.environ.get("TAKTOVIA_BUILD_ID")
    if env:
        return env

    def git(*a: str) -> str:
        return subprocess.run(["git", "-C", str(repo), *a], capture_output=True, text=True).stdout.strip()
    return (git("rev-parse", "HEAD") or "unversioniert") + ("+lokal" if git("status", "--porcelain", "--untracked-files=no") else "")


def run_gate(cfg: dict | None = None, *, repo: Path = REPO, ergebnisse: Path | None = None, ueberspringen: set[str] = frozenset(), ausnahmen: list[dict] | None = None,
             nur: set[str] | None = None, modus: str = "probelauf", now: dt.date | None = None, junit_dirs: list[Path] | None = None) -> GateResult:
    cfg = cfg or load_config()
    now = now or dt.date.today()
    ergebnisse = ergebnisse or Path(os.environ.get("TMPDIR", "/tmp")) / "taktovia-release-gate"
    ausnahmen = load_exceptions() if ausnahmen is None else ausnahmen
    cache: dict = {}

    def junit() -> Results:
        if "r" not in cache:
            cache["r"] = load_junit([ergebnisse, *(junit_dirs or [])], repo)
        return cache["r"]
    ctx = {"repo": repo, "ergebnisse": ergebnisse, "modus": modus, "junit": junit}
    problems: list[str] = []
    stages: list[StageResult] = []
    known = {s["id"] for s in cfg["stufen"]}
    for u in sorted(ueberspringen - known):
        problems.append(f"unbekannte Stufe zum Überspringen: {u}")
    for stufe in cfg["stufen"]:
        sid = stufe["id"]
        if nur is not None and sid not in nur:
            # Nicht angeforderte Stufen zählen wie übersprungene: ohne Ausnahme bleibt das Release gesperrt
            ueberspringen = set(ueberspringen) | {sid}
        if sid in ueberspringen:
            ex, why = valid_exception(sid, ausnahmen, now)
            if ex:
                stages.append(StageResult(sid, stufe["name"], "SKIPPED_WITH_EXCEPTION", ausnahme=ex, detail=f"Ausnahme von {ex['freigeber']} bis {ex['ablauf']}: {ex['grund']}"))
            else:
                stages.append(StageResult(sid, stufe["name"], "SKIPPED_BLOCKED", detail=f"Stufe übersprungen ohne gültige Ausnahme ({why}); Release gesperrt"))
            continue
        checks = [run_check(c, cfg, ctx) for c in stufe["pruefungen"]]
        status = "PASSED" if all(c.ok for c in checks) else ("NOT_RUN" if all(c.status in ("PASSED", "NOT_RUN") for c in checks) else "FAILED")
        stages.append(StageResult(sid, stufe["name"], status, checks))
    return GateResult(stages, problems, build_id(repo), migration_state(repo), modus)


# ----------------------------------------------------------------------------------------------------------------------------------
# Release-Artefakt

def _canonical(d: dict) -> bytes:
    return json.dumps(d, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def _key() -> bytes | None:
    k = os.environ.get(KEY_ENV, "")
    return k.encode() if len(k) >= 32 else None


def jar_digests(repo: Path = REPO) -> list[dict]:
    out = []
    for j in sorted((repo / "backend" / "app" / "build" / "libs").glob("*.jar")) if (repo / "backend" / "app" / "build" / "libs").is_dir() else []:
        if "plain" not in j.name:
            out.append({"datei": j.name, "sha256": hashlib.sha256(j.read_bytes()).hexdigest()})
    return out


def manifest_of(r: GateResult, repo: Path = REPO) -> dict:
    return {
        "schema": "taktovia-release-manifest/1",
        "build_id": r.build_id,
        "migrationsstand": r.migration_state,
        "modus": r.modus,
        "pflichtpruefungen": [{"stufe": s.id, "name": s.name, "status": s.status, "pruefungen": [c.id for c in s.checks if c.ok]} for s in r.stages],
        "bestandene_pruefungen": [f"{s.id}/{c.id}" for s in r.stages for c in s.checks if c.ok],
        "ausnahmen": [s.ausnahme for s in r.stages if s.ausnahme],
        "artefakte": jar_digests(repo),
        "erzeugt": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }


def write_release(r: GateResult, out: Path, repo: Path = REPO) -> tuple[Path | None, str]:
    """Erzeugt und signiert das Release-Artefakt – nur bei vollständig grünem, freigabefähigem Lauf. Rückgabe: (Manifest oder None, Begründung)."""
    if not r.passed:
        return None, "Pflichtprüfung nicht grün oder Stufe ohne gültige Ausnahme übersprungen: kein Release-Artefakt"
    if not r.release_faehig:
        return None, "Probelauf oder nicht ausgeführte Prüfungen (extern): nicht freigabefähig, kein signiertes Release-Artefakt"
    key = _key()
    if key is None:
        return None, f"Signierschlüssel {KEY_ENV} fehlt oder ist kürzer als 32 Zeichen: Release nicht signierbar"
    out.mkdir(parents=True, exist_ok=True)
    body = _canonical(manifest_of(r, repo))
    (out / MANIFEST).write_bytes(body + b"\n")
    (out / SIGNATURE).write_text("hmac-sha256:" + hmac.new(key, body, hashlib.sha256).hexdigest() + "\n", encoding="utf-8")
    return out / MANIFEST, "Release-Artefakt erzeugt und signiert"


def verify_release(directory: Path, cfg: dict | None = None) -> list[str]:
    """Prüft Signatur, Pflichtfelder und Vollständigkeit der Pflichtstufen eines Release-Artefakts. Leere Liste = in Ordnung."""
    cfg = cfg or load_config()
    funde: list[str] = []
    mp, sp = directory / MANIFEST, directory / SIGNATURE
    if not mp.is_file() or not sp.is_file():
        return ["Manifest oder Signatur fehlt"]
    raw = mp.read_bytes().rstrip(b"\n")
    key = _key()
    if key is None:
        funde.append(f"Signierschlüssel {KEY_ENV} fehlt: Signatur nicht prüfbar")
    else:
        want = "hmac-sha256:" + hmac.new(key, raw, hashlib.sha256).hexdigest()
        if not hmac.compare_digest(want, sp.read_text(encoding="utf-8").strip()):
            funde.append("Signatur stimmt nicht")
    m = json.loads(raw)
    for k in ("build_id", "migrationsstand", "pflichtpruefungen", "bestandene_pruefungen"):
        if not m.get(k):
            funde.append(f"Pflichtfeld {k} fehlt")
    got = {s["stufe"]: s["status"] for s in m.get("pflichtpruefungen", [])}
    for s in cfg["stufen"]:
        if got.get(s["id"]) not in ("PASSED", "SKIPPED_WITH_EXCEPTION"):
            funde.append(f"Stufe {s['id']} nicht grün oder fehlt ({got.get(s['id'])})")
    if m.get("modus") != "ci":
        funde.append("Manifest stammt nicht aus einem CI-Lauf")
    return funde


# ----------------------------------------------------------------------------------------------------------------------------------

def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    r.add_argument("--ergebnisse", type=Path, default=None, help="Ablage der Python-JUnit-Dateien")
    r.add_argument("--out", type=Path, default=REPO / "release-out")
    r.add_argument("--ueberspringen", default="", help="Stufen-IDs, kommagetrennt (nur mit gültiger Ausnahme)")
    r.add_argument("--stufen", default="", help="nur diese Stufen ausführen, kommagetrennt (alle anderen gelten als übersprungen)")
    r.add_argument("--ausnahmen", type=Path, default=EXCEPTIONS)
    r.add_argument("--ohne-artefakt", action="store_true", help="nur das Urteil (Exit-Code), kein Release-Artefakt (Push/Pull-Request ohne Versions-Tag)")
    r.add_argument("--ci", action="store_true", help="Release-Lauf (extern nachgewiesene Prüfungen kommen aus der Umgebung)")
    a = sub.add_parser("abdeckung")
    a.add_argument("--ergebnisse", type=Path, default=None, help="Ablage der Python-JUnit-Dateien (mit --strikt)")
    a.add_argument("--strikt", action="store_true", help="zusätzlich: jeder zugeordnete Test muss in den JUnit-Ergebnissen grün sein")
    v = sub.add_parser("verify")
    v.add_argument("verzeichnis", type=Path)
    n = ap.parse_args(argv)
    cfg = load_config()
    if n.cmd == "abdeckung":
        res = load_junit([n.ergebnisse] if n.ergebnisse else None) if n.strikt else None
        funde = check_coverage(cfg, res) + check_semantic(cfg, res) + check_capacity(cfg, res)
        print("\n".join(funde) if funde else f"Abdeckung vollständig ({'strikt' if n.strikt else 'statisch'})")
        return 1 if funde else 0
    if n.cmd == "verify":
        funde = verify_release(n.verzeichnis, cfg)
        print("\n".join(funde) if funde else "Release-Artefakt in Ordnung")
        return 1 if funde else 0
    res = run_gate(cfg, ergebnisse=n.ergebnisse, ueberspringen={s for s in n.ueberspringen.split(",") if s}, ausnahmen=load_exceptions(n.ausnahmen),
                   nur={s for s in n.stufen.split(",") if s} or None, modus="ci" if n.ci else "probelauf")
    for s in res.stages:
        print(f"[{s.status:<22}] {s.id:<14} {s.name}" + (f"  -- {s.detail}" if s.detail else ""))
        for c in s.checks:
            print(f"    {c.status:<8} {c.id} ({c.dauer_s:.1f} s)" + (f"  {c.detail.splitlines()[0]}" if c.status != "PASSED" and c.detail else ""))
    for p in res.problems:
        print("Problem:", p)
    path, why = (None, "ohne Artefakt angefordert (--ohne-artefakt)") if n.ohne_artefakt else write_release(res, n.out)
    if not n.ohne_artefakt and res.passed and path is None and n.ci:
        res.problems.append(why)   # ein grüner CI-Release-Lauf, der nicht signiert werden kann, ist kein Erfolg
    print(f"Ergebnis: {'GRÜN' if res.passed else 'ROT'}, freigabefähig: {res.release_faehig}. {why}")
    (n.out).mkdir(parents=True, exist_ok=True)
    (n.out / "gate-bericht.json").write_text(json.dumps({"build_id": res.build_id, "modus": res.modus, "gruen": res.passed, "release_faehig": res.release_faehig,
                                                         "stufen": [{"id": s.id, "status": s.status, "detail": s.detail,
                                                                     "pruefungen": [{"id": c.id, "status": c.status, "dauer_s": round(c.dauer_s, 1)} for c in s.checks]} for s in res.stages]},
                                                        indent=1, ensure_ascii=False), encoding="utf-8")
    return 0 if res.passed else 1


if __name__ == "__main__":
    sys.exit(main())
