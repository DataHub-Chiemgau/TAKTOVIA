#!/usr/bin/env python3
"""CI-Gate für Sicherheitsbefunde (WP-M1-38, LH-6.18-SEC-009; Mangelklassen nach Kap. 08 §8.4).

  findings_gate.py [--decisions docs/security/befunde.csv] [--today JJJJ-MM-TT] [--report bericht.md] <Scanbericht>...

Liest Berichte von SAST (CodeQL), Abhängigkeits-/Image-Scan (OSV, Trivy), DAST (OWASP ZAP) und den eigenen Werkzeugen und ordnet jeden Befund einer
Mangelklasse zu. Das Gate ist rot, wenn
  - ein Befund keine dokumentierte Entscheidung hat,
  - ein Klasse-A-Befund (kritisch) irgendetwas anderes als eine begründete FALSCHMELDUNG als Entscheidung hat (Klasse A blockiert die Produktion),
  - ein Klasse-B-Befund weder FALSCHMELDUNG noch eine begründete Zusage mit Behebungstermin (`bis`, nicht abgelaufen) hat,
  - eine Entscheidung abgelaufen ist.
Formate: SARIF 2.1.0, ZAP-JSON (`site[].alerts[]`), `{"tool":…, "findings":[{rule,location,severity,title}]}` der eigenen Werkzeuge. Exitcode 0 grün, 1 rot, 2 Eingabefehler.

Schwellen „kritisch“ je Werkzeug (Detailvertrag LH-6.18-SEC-009, hier verbindlich; Begründung in docs/security/eingabehaertung.md):
  CodeQL / SARIF mit `security-severity`: >= 9.0 kritisch (A), >= 7.0 hoch (B), sonst C. Ohne Score: level error = hoch, warning = mittel, note = niedrig.
  OSV / Trivy (CVSS): >= 9.0 kritisch (A), >= 7.0 hoch (B), sonst C.
  OWASP ZAP: Risiko High (riskcode 3) = kritisch (A), Medium = B, Low/Informational = C.
  Eigene Werkzeuge: Schwere wird im Befund angegeben (KRITISCH/HOCH/MITTEL/NIEDRIG).
"""
import csv
import datetime as dt
import json
import sys
from pathlib import Path

CRITICAL, HIGH, MEDIUM, LOW = 'KRITISCH', 'HOCH', 'MITTEL', 'NIEDRIG'
CLASS_OF = {CRITICAL: 'A', HIGH: 'B', MEDIUM: 'C', LOW: 'C'}
DECISIONS = {'FALSCHMELDUNG', 'AKZEPTIERT', 'WIRD_BEHOBEN'}
DEFAULT_DECISIONS = Path(__file__).resolve().parents[1] / 'docs/security/befunde.csv'


def by_score(score):
    return CRITICAL if score >= 9.0 else HIGH if score >= 7.0 else MEDIUM if score >= 4.0 else LOW


def parse_sarif(doc, name):
    out = []
    for run in doc.get('runs', []):
        tool = run.get('tool', {}).get('driver', {}).get('name', name)
        rules = {r.get('id'): r for r in run.get('tool', {}).get('driver', {}).get('rules', [])}
        for res in run.get('results', []):
            rid = res.get('ruleId', '?')
            props = {**rules.get(rid, {}).get('properties', {}), **res.get('properties', {})}
            score = props.get('security-severity')
            if score is not None:
                sev = by_score(float(score))
            else:
                sev = {'error': HIGH, 'warning': MEDIUM, 'note': LOW, 'none': LOW}.get(res.get('level', rules.get(rid, {}).get('defaultConfiguration', {}).get('level', 'warning')), MEDIUM)
            loc = res.get('locations', [{}])[0].get('physicalLocation', {}).get('artifactLocation', {}).get('uri', '*')
            out.append({'tool': tool, 'rule': rid, 'location': loc, 'severity': sev, 'title': res.get('message', {}).get('text', rid)[:200]})
    return out


def parse_zap(doc):
    out = []
    for site in doc.get('site', []):
        for a in site.get('alerts', []):
            risk = int(a.get('riskcode', 0))
            sev = {3: CRITICAL, 2: HIGH, 1: MEDIUM}.get(risk, LOW)  # High = A, Medium = B, Low/Info = C
            for inst in a.get('instances') or [{'uri': '*'}]:
                out.append({'tool': 'ZAP', 'rule': str(a.get('pluginid', a.get('alert', '?'))), 'location': inst.get('uri', '*'), 'severity': sev, 'title': a.get('name', '')[:200]})
    return out


def load(path):
    try:
        doc = json.loads(Path(path).read_text())
    except (OSError, ValueError) as e:
        raise SystemExit(f'Scanbericht {path} nicht lesbar: {e}')
    if 'runs' in doc:
        return parse_sarif(doc, Path(path).stem)
    if 'site' in doc:
        return parse_zap(doc)
    if 'findings' in doc:
        return [{'tool': doc.get('tool', Path(path).stem), 'rule': f['rule'], 'location': f.get('location', '*'), 'severity': f['severity'], 'title': f.get('title', '')} for f in doc['findings']]
    raise SystemExit(f'Scanbericht {path}: unbekanntes Format (SARIF, ZAP-JSON oder taktovia-findings erwartet)')


def read_decisions(path):
    p = Path(path)
    if not p.exists():
        return []
    with p.open(newline='') as f:
        return list(csv.DictReader(f, delimiter=';'))


def decision_for(finding, decisions):
    for d in decisions:
        if d['tool'] == finding['tool'] and d['rule'] == finding['rule'] and d['location'] in ('*', finding['location']):
            return d
    return None


def judge(finding, decision, today):
    """(ok, Begründung) nach Mangelklasse."""
    cls = CLASS_OF[finding['severity']]
    if decision is None:
        return False, 'keine dokumentierte Entscheidung'
    kind = decision['entscheidung'].strip()
    if kind not in DECISIONS:
        return False, f'unbekannte Entscheidung {kind!r}'
    if not decision['begruendung'].strip() or not decision['verantwortlich'].strip():
        return False, 'Begründung und Verantwortlicher sind Pflicht'
    until = decision.get('bis', '').strip()
    if until and dt.date.fromisoformat(until) < today:
        return False, f'Entscheidung abgelaufen am {until}'
    if cls == 'A':
        return (True, 'Klasse A als Falschmeldung begründet') if kind == 'FALSCHMELDUNG' else (False, 'Klasse A blockiert die Produktion: nur Behebung oder begründete Falschmeldung')
    if cls == 'B' and kind != 'FALSCHMELDUNG' and not until:
        return False, 'Klasse B braucht Behebungstermin (bis)'
    return True, f'Klasse {cls}: {kind}'


def main(argv):
    args, i = {'decisions': str(DEFAULT_DECISIONS), 'today': None, 'report': None}, 0
    files = []
    while i < len(argv):
        if argv[i] in ('--decisions', '--today', '--report'):
            args[argv[i][2:]] = argv[i + 1]
            i += 2
        else:
            files.append(argv[i])
            i += 1
    if not files:
        print(__doc__)
        return 2
    today = dt.date.fromisoformat(args['today']) if args['today'] else dt.date.today()
    decisions = read_decisions(args['decisions'])
    findings = [f for p in files for f in load(p)]
    rows, red = [], 0
    for f in findings:
        ok, why = judge(f, decision_for(f, decisions), today)
        red += not ok
        rows.append((CLASS_OF[f['severity']], f, ok, why))
        print(f"{'OK    ' if ok else 'FEHLER'} Klasse {CLASS_OF[f['severity']]} {f['severity']:<8} {f['tool']} {f['rule']} {f['location']}: {why}")
    print(f'Befund-Gate: {"FEHLER" if red else "PASS"} ({len(findings)} Befunde, {red} ohne tragfähige Entscheidung)')
    if args['report']:
        lines = ['# Scanbericht (Befunde und Entscheidungen)', '', f'Stand {today.isoformat()}; Berichte: ' + ', '.join(Path(p).name for p in files), '',
                 '| Klasse | Schwere | Werkzeug | Regel | Ort | Ergebnis |', '|---|---|---|---|---|---|']
        lines += [f"| {c} | {f['severity']} | {f['tool']} | {f['rule']} | {f['location']} | {'OK' if ok else 'FEHLER'}: {why} |" for c, f, ok, why in rows]
        Path(args['report']).write_text('\n'.join(lines) + '\n')
    return 1 if red else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
