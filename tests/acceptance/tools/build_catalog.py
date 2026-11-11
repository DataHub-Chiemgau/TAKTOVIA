#!/usr/bin/env python3
"""Erzeugt catalog/scenarios.csv: 106 Referenztests (e2e.csv) + 22 PH-AT-Tests (Kap. 08 §8.5) als benannte,
noch nicht implementierte Szenarien mit vorläufigem Zielmeilenstein (Bestätigung in WP-M0-33).

Zielmeilenstein = frühester Meilenstein, dessen WP-Datei die Test-ID nennt (docs/umsetzungsplan/wp/M*.md, ohne M0).
Aufruf: python tests/acceptance/tools/build_catalog.py [--check]
"""
import csv
import glob
import io
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parents[1] / "catalog" / "scenarios.csv"
ORDER = ["M1", "M2", "M3", "M4", "M5", "M6a", "M6b", "M6c", "M7a", "M7b", "M7c", "M7d", "M8a", "M8b", "M8c", "M8d", "M8e", "M9a", "M9b"]
COLS = ["test_id", "kind", "name", "version", "source_keys", "reference_ids", "unmapped_refs", "target_milestone",
        "mentioned_in", "status", "target_status"]


def wp_texts():
    return {Path(f).name.split("-")[0]: Path(f).read_text() for f in glob.glob(str(REPO / "docs/umsetzungsplan/wp/M*.md"))}


def requirement_index():
    """id_original -> source_key (eindeutige Zuordnung aus arbeitspakete.csv)."""
    idx = {}
    with open(REPO / "docs/umsetzungsplan/arbeitspakete.csv", newline="") as f:
        for r in csv.DictReader(f, delimiter=";"):
            idx.setdefault(r["id_original"], set()).add(r["source_key"])
    return idx


def ph_at_tests():
    txt = (REPO / "docs/pflichtenheft/08-abdeckung-und-abnahme.md").read_text()
    out = []
    for m in re.finditer(r"^### (PH-AT-\d{3}) – (.+?)\n\nBezug: (.+?)\.\n", txt, re.M):
        out.append((m.group(1), m.group(2).strip(), m.group(3).strip()))
    return out


def ref_tokens(bezug):
    toks = []
    for m in re.finditer(r"\b([A-Z]{3,4})-(\d{3})((?:/\d{3})*)", bezug):
        toks.append(f"{m.group(1)}-{m.group(2)}")
        for extra in re.findall(r"/(\d{3})", m.group(3)):
            toks.append(f"{m.group(1)}-{extra}")
    return toks


def build():
    texts = wp_texts()
    req = requirement_index()

    def milestones(tid):
        return [m for m in ORDER if m in texts and re.search(r"(?<![\w-])" + re.escape(tid) + r"(?!\d)", texts[m])]

    rows = []
    with open(REPO / "docs/pflichtenheft/e2e.csv", newline="") as f:
        for r in csv.DictReader(f, delimiter=";"):
            tid = r["test_id_original"]
            ms = milestones(tid)
            rows.append({"test_id": tid, "kind": "E2E" if tid.startswith("E2E-") else "AT", "name": r["name_original"],
                         "version": "0.0.0-nicht-implementiert", "source_keys": "", "reference_ids": "", "unmapped_refs": "",
                         "target_milestone": ms[0] if ms else "", "mentioned_in": "|".join(ms),
                         "status": "NOT_IMPLEMENTED", "target_status": "vorläufig (Bestätigung WP-M0-33)"})
    for tid, name, bezug in ph_at_tests():
        toks = ref_tokens(bezug)
        keys, unmapped = [], []
        for t in toks:
            cands = req.get(t)
            if cands and len(cands) == 1:
                keys.append(next(iter(cands)))
            else:
                unmapped.append(t)
        ms = milestones(tid)
        rows.append({"test_id": tid, "kind": "PH-AT", "name": name, "version": "0.0.0-nicht-implementiert",
                     "source_keys": "|".join(sorted(set(keys))), "reference_ids": bezug, "unmapped_refs": "|".join(unmapped),
                     "target_milestone": ms[0] if ms else "", "mentioned_in": "|".join(ms),
                     "status": "NOT_IMPLEMENTED", "target_status": "vorläufig (Bestätigung WP-M0-33)"})
    return rows


def render(rows):
    buf = io.StringIO()
    w = csv.DictWriter(buf, fieldnames=COLS, lineterminator="\n")
    w.writeheader()
    w.writerows(rows)
    return buf.getvalue()


if __name__ == "__main__":
    text = render(build())
    if "--check" in sys.argv:
        sys.exit(0 if OUT.read_text() == text else "catalog/scenarios.csv ist veraltet: build_catalog.py ausführen")
    OUT.write_text(text)
    print(f"{OUT} geschrieben")
