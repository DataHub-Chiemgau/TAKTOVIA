"""Evidenzdatensatz: Aufbau, Schema- und Personendatenprüfung, Ablage (Kap. 08 §8.4)."""
import hashlib
import json
import re
from pathlib import Path

import jsonschema

from . import AT_DIR, REPO

SCHEMA_PATH = AT_DIR / "schema" / "evidence.schema.json"

# Prüfregel "Evidenz enthält keine Fahrgast- oder Personaldaten":
#  (1) Schlüsselnamen aus der Verbotsliste (Kontakt-, Namens-, Adress-, Bank-, Gesundheits-, Chiffrat-Felder),
#  (2) ausdrückliche Markierung: Schlüssel endet auf "__pii" oder Objekt trägt "personal_data": true / "pii": true,
#  (3) Werte, die wie E-Mail-Adresse, Telefonnummer oder IBAN aussehen.
PII_KEY_RE = re.compile(r"(?i)(^|_)(e_?mail|phone|telefon|mobile|first_?name|last_?name|surname|vorname|nachname|"
                        r"birth|geburt|iban|address|adresse|street|strasse|license|fuehrerschein|ciphertext|diagnos|"
                        r"passenger_name|driver_name)($|_)")
PII_VALUE_RES = [("email", re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")),
                 ("phone", re.compile(r"(?<!\w)\+\d[\d ()/-]{7,}\d")),
                 ("iban", re.compile(r"\b[A-Z]{2}\d{2}(?: ?[A-Z0-9]{4}){3,7}(?: ?[A-Z0-9]{1,4})?\b"))]


class EvidenceError(Exception):
    def __init__(self, problems):
        super().__init__("; ".join(problems))
        self.problems = problems


def check_no_personal_data(obj, path="$"):
    """-> Liste der Verstöße (leer = in Ordnung)."""
    out = []
    if isinstance(obj, dict):
        if obj.get("personal_data") is True or obj.get("pii") is True:
            out.append(f"{path}: als personenbezogen markiert")
        for k, v in obj.items():
            if k.endswith("__pii") or k == "__pii__":
                out.append(f"{path}.{k}: als personenbezogen markiert")
            elif PII_KEY_RE.search(k):
                out.append(f"{path}.{k}: Feldname personenbezogen")
            out += check_no_personal_data(v, f"{path}.{k}")
    elif isinstance(obj, list):
        for i, v in enumerate(obj):
            out += check_no_personal_data(v, f"{path}[{i}]")
    elif isinstance(obj, str):
        for name, rx in PII_VALUE_RES:
            if rx.search(obj):
                out.append(f"{path}: Wert sieht aus wie {name}")
    return out


def load_schema():
    return json.loads(SCHEMA_PATH.read_text())


def validate(record: dict):
    """Schema- und Personendatenprüfung. Wirft EvidenceError, sonst gibt sie den Datensatz zurück."""
    schema = load_schema()
    v = jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker())
    problems = [f"{'/'.join(str(p) for p in e.absolute_path) or '$'}: {e.message}" for e in v.iter_errors(record)]
    problems += check_no_personal_data(record)
    if problems:
        raise EvidenceError(problems)
    return record


def software_build():
    """Build-ID: TAKTOVIA_BUILD_ID, sonst Commit aus .git (nur lesend, ohne git-Aufruf), sonst 'unversioniert'."""
    import os
    if os.environ.get("TAKTOVIA_BUILD_ID"):
        return {"build_id": os.environ["TAKTOVIA_BUILD_ID"], "origin": "TAKTOVIA_BUILD_ID"}
    head = REPO / ".git" / "HEAD"
    try:
        txt = head.read_text().strip()
        if txt.startswith("ref: "):
            ref = REPO / ".git" / txt[5:]
            if ref.exists():
                txt = ref.read_text().strip()
            else:
                packed = (REPO / ".git" / "packed-refs").read_text()
                m = re.search(r"^([0-9a-f]{40}) " + re.escape(txt[5:]) + r"$", packed, re.M)
                txt = m.group(1) if m else ""
        if re.fullmatch(r"[0-9a-f]{40}", txt):
            return {"build_id": txt, "origin": "git-head"}
    except OSError:
        pass
    return {"build_id": "unversioniert", "origin": "unversioniert"}


def migration_state(dbname):
    from .db import migrator_conn
    c = migrator_conn(dbname)
    try:
        rows = c.execute("SELECT script, checksum FROM taktovia_meta.schema_history ORDER BY script").fetchall()
    finally:
        c.close()
    scripts = [{"script": s, "checksum": cs} for s, cs in rows]
    versioned = sorted((s for s, _ in rows if s.startswith("V")), key=lambda s: int(re.match(r"V(\d+)__", s).group(1)))
    return {"latest": versioned[-1] if versioned else "none", "scripts": scripts}


def run_id(test_id, seed_hash, clock_start):
    return "run-" + hashlib.sha256(f"{test_id}|{seed_hash}|{clock_start}".encode()).hexdigest()[:16]


def write(record: dict, out_dir: Path) -> Path:
    validate(record)
    out_dir.mkdir(parents=True, exist_ok=True)
    p = out_dir / f"{record['test']['id']}-{record['run_id']}.json"
    p.write_text(json.dumps(record, indent=2, sort_keys=True, ensure_ascii=False) + "\n")
    return p
