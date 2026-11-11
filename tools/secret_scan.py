#!/usr/bin/env python3
"""Secret-Scan (WP-M1-36, LH-6.18-SEC-003): Quellcode, Git-Historie und Auslieferungsartefakte (Image-Archive, Verzeichnisse, SBOM).

  secret_scan.py tree [pfad]                Arbeitsbaum (versionierte und nicht ignorierte Dateien)
  secret_scan.py history [repo]             alle in der Git-Historie jemals hinzugefügten Zeilen (alle Referenzen)
  secret_scan.py artifact <verzeichnis|tar> entpacktes Image-Dateisystem, `docker save`/OCI-Archiv (Schichten werden durchsucht), SBOM

Exit 0 = keine Funde, 1 = Funde. Die Ausgabe nennt Ort, Muster und einen Fingerabdruck, **nie den Wert**.
Ausnahmen nur über tools/secret_scan_allowlist.csv (pfad;muster;grund;ablauf); abgelaufene oder begründungslose Einträge zählen als Fund,
jede genutzte Ausnahme wird ausgegeben und zählt nicht als „keine Funde".
"""
import csv
import hashlib
import io
import re
import subprocess
import sys
import tarfile
from datetime import date
from pathlib import Path

PATTERNS = {
    "private-key": re.compile(r"-----BEGIN (?:[A-Z]+ )*PRIVATE KEY-----"),
    "aws-access-key": re.compile(r"\b(?:AKIA|ASIA)[0-9A-Z]{16}\b"),
    "github-token": re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})\b"),
    "slack-token": re.compile(r"\bxox[abprs]-[0-9A-Za-z-]{10,}"),
    "jwt": re.compile(r"\beyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}"),
    "basic-auth-url": re.compile(r"\b[a-z][a-z0-9+.-]*://[^/\s:@]+:(?![^@\s]*(?:\$\{|\{\{|\*\*\*|<))[^/\s:@]{6,}@[^/\s]+"),
    "zugangswert": re.compile(
        r"""(?ix)\b[\w.-]*(?:password|passwd|secret|api[_-]?key|access[_-]?key|private[_-]?key|token)\b["']?\s*[:=]\s*["']?
            (?!/|\$\{|\{\{|\$[A-Za-z(]|secret://|dev-only|<|\*{3}|none\b|null\b|true\b|false\b|changeme|example|placeholder|(?:psp_)?test[_-])
            ([A-Za-z0-9/+_=-]{16,})(?=["'\s,;)]|$)"""),
}
FORBIDDEN_NAMES = re.compile(r"(?:^|/)(?:\.env(?:\.[\w-]+)?|id_rsa|id_ed25519|[^/]+\.(?:pem|p12|pfx|jks|keystore))$")
ENV_EXAMPLE = re.compile(r"\.env\.(?:example|sample|template)$")
SKIP_SUFFIX = {".png", ".jpg", ".jpeg", ".gif", ".ico", ".woff", ".woff2", ".ttf", ".jar", ".class", ".zip", ".gz", ".pdf", ".pyc", ".lock"}
MAX_BYTES = 5_000_000
ALLOWLIST = Path(__file__).with_name("secret_scan_allowlist.csv")


def fingerprint(value):
    return hashlib.sha256(value.encode()).hexdigest()[:10]


def scan_text(text):
    """Liefert [(zeile, muster, fingerabdruck)] ohne Werte."""
    out = []
    for n, line in enumerate(text.splitlines(), 1):
        if len(line) > 4000:
            line = line[:4000]
        for name, pat in PATTERNS.items():
            m = pat.search(line)
            if m:
                out.append((n, name, fingerprint(m.group(m.lastindex or 0))))
    return out


def load_allowlist(path=ALLOWLIST, today=None):
    """Gibt (aktive Einträge, Fehler) zurück. Abgelaufene oder begründungslose Einträge sind Fehler."""
    today = today or date.today()
    active, errors = [], []
    if not path.exists():
        return active, errors
    for i, r in enumerate(csv.DictReader(path.open(encoding="utf-8"), delimiter=";"), 2):
        if not r.get("grund", "").strip():
            errors.append(f"Allowlist Zeile {i}: Begründung fehlt")
        elif not re.fullmatch(r"\d{4}-\d{2}-\d{2}", r.get("ablauf") or ""):
            errors.append(f"Allowlist Zeile {i}: Ablaufdatum fehlt oder ungültig")
        elif date.fromisoformat(r["ablauf"]) < today:
            errors.append(f"Allowlist Zeile {i}: Ausnahme für {r['pfad']} ({r['muster']}) ist abgelaufen")
        else:
            active.append(r)
    return active, errors


def _allowed(allow, path, pattern):
    return next((a for a in allow if re.fullmatch(a["pfad"], path) and a["muster"] in (pattern, "*")), None)


def _check(path, text, findings, used, allow):
    if FORBIDDEN_NAMES.search(path) and not ENV_EXAMPLE.search(path):
        hit = [(0, "verbotene-datei", fingerprint(path))]
    else:
        hit = []
    for line, pat, fp in hit + scan_text(text):
        a = _allowed(allow, path, pat)
        (used if a else findings).append((path, line, pat, fp, a["grund"] if a else ""))


def _report(findings, used, errors):
    for e in errors:
        print("FEHLER", e)
    for path, line, pat, fp, grund in used:
        print(f"AUSNAHME {path}:{line} {pat} [{fp}] Grund: {grund}")
    for path, line, pat, fp, _ in findings:
        print(f"FUND {path}:{line} Muster={pat} Fingerabdruck={fp}")
    bad = bool(findings or errors)
    print(f"Secret-Scan: {len(findings)} Funde, {len(used)} genutzte Ausnahmen, {len(errors)} Allowlist-Fehler -> {'FEHLER' if bad else 'OK'}")
    return 1 if bad else 0


def _git(repo, *args):
    return subprocess.run(["git", "-C", str(repo), *args], capture_output=True, check=True).stdout


def scan_tree(root, allow=None):
    root = Path(root)
    allow = load_allowlist()[0] if allow is None else allow
    findings, used = [], []
    try:
        names = _git(root, "ls-files", "-z", "--cached", "--others", "--exclude-standard").decode().split("\0")
    except (subprocess.CalledProcessError, FileNotFoundError):
        names = [str(p.relative_to(root)) for p in root.rglob("*") if p.is_file() and ".git" not in p.parts]
    for n in filter(None, names):
        p = root / n
        if not p.is_file() or p.suffix in SKIP_SUFFIX or p.stat().st_size > MAX_BYTES:
            continue
        _check(n, p.read_text(encoding="utf-8", errors="ignore"), findings, used, allow)
    return findings, used


def scan_history(repo, allow=None):
    """Jede je hinzugefügte Zeile jeder Referenz, auch wenn sie später entfernt wurde."""
    allow = load_allowlist()[0] if allow is None else allow
    findings, used, seen = [], [], set()
    log = _git(repo, "log", "--all", "-p", "--no-color", "--no-ext-diff", "--format=@@COMMIT %H").decode("utf-8", "ignore")
    commit = path = None
    for raw in log.splitlines():
        if raw.startswith("@@COMMIT "):
            commit = raw[9:17]
        elif raw.startswith("+++ b/"):
            path = raw[6:]
            if FORBIDDEN_NAMES.search(path) and not ENV_EXAMPLE.search(path) and (path, "datei") not in seen:
                seen.add((path, "datei"))
                a = _allowed(allow, path, "verbotene-datei")
                (used if a else findings).append((f"{path}@{commit}", 0, "verbotene-datei", fingerprint(path), a["grund"] if a else ""))
        elif raw.startswith("+") and not raw.startswith("+++") and path and Path(path).suffix not in SKIP_SUFFIX:
            for _, pat, fp in scan_text(raw[1:]):
                if (path, pat, fp) in seen:
                    continue
                seen.add((path, pat, fp))
                a = _allowed(allow, path, pat)
                (used if a else findings).append((f"{path}@{commit}", 0, pat, fp, a["grund"] if a else ""))
    return findings, used


def _scan_tar(tf, prefix, findings, used, allow, depth=0):
    for m in tf:
        if not m.isfile():
            continue
        data = tf.extractfile(m).read(MAX_BYTES + 1)
        name = f"{prefix}{m.name}"
        if depth < 3 and (m.name.endswith((".tar", ".tar.gz", ".tgz")) or m.name.startswith("blobs/") and data[:2] == b"\x1f\x8b" or data[257:262] == b"ustar"):
            try:
                with tarfile.open(fileobj=io.BytesIO(data), mode="r:*") as inner:
                    _scan_tar(inner, name + "!", findings, used, allow, depth + 1)
                continue
            except tarfile.TarError:
                pass
        if Path(m.name).suffix in SKIP_SUFFIX or len(data) > MAX_BYTES:
            continue
        _check(name, data.decode("utf-8", "ignore"), findings, used, allow)


def scan_artifact(target, allow=None):
    allow = load_allowlist()[0] if allow is None else allow
    target = Path(target)
    findings, used = [], []
    if target.is_dir():
        for p in sorted(target.rglob("*")):
            if p.is_file() and p.suffix not in SKIP_SUFFIX and p.stat().st_size <= MAX_BYTES:
                _check(str(p.relative_to(target)), p.read_text(encoding="utf-8", errors="ignore"), findings, used, allow)
    else:
        with tarfile.open(target, "r:*") as tf:
            _scan_tar(tf, "", findings, used, allow)
    return findings, used


def main(argv):
    if len(argv) < 2 or argv[1] not in ("tree", "history", "artifact"):
        print(__doc__)
        return 2
    mode, arg = argv[1], (argv[2] if len(argv) > 2 else ".")
    _, errors = load_allowlist()
    f, u = {"tree": scan_tree, "history": scan_history, "artifact": scan_artifact}[mode](arg)
    return _report(f, u, errors)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
