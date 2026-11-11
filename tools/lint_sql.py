#!/usr/bin/env python3
"""Query-Lint (WP-M1-38, LH-6.18-SEC-009): SQL wird ausschließlich parametrisiert abgesetzt.

  lint_sql.py [--format json] [Wurzel ...]     Standard: backend/**/src/main (Kotlin) und workers/**/src (Python)

Geprüft wird jeder Aufruf einer SQL-Senke (query, queryForObject, update, execute, prepareStatement, executeQuery, …): das SQL-Argument muss ein
Stringliteral ohne Interpolation sein (`$name`, `${…}`, `+`, `.format`, f-String, `%`) oder eine `const val`. Werte gehören in Platzhalter (`?`).
Ausnahmen sind im Code mit `// sql-lint: ok <Grund>` in derselben Zeile namentlich begründet. Befunde sind Schwere KRITISCH (SQL-Injektion, Klasse A).
Exitcode 1 bei Befunden.
"""
import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
KOTLIN_SINKS = ('query', 'queryForObject', 'queryForList', 'queryForMap', 'queryForRowSet', 'queryForStream', 'update', 'batchUpdate', 'execute', 'prepareStatement',
                'prepareCall', 'executeQuery', 'executeUpdate', 'addBatch', 'createNativeQuery')
PY_SINKS = ('execute', 'executemany', 'mogrify')
INTERPOLATION = re.compile(r'(?<!\\)\$(\{|[A-Za-z_])')
ALLOW = re.compile(r'sql-lint:\s*ok\s+\S+')


def top_level_arg(src, i):
    """Erstes Argument ab Position i (hinter der öffnenden Klammer) bis zum Komma/Ende auf oberster Ebene; beachtet Strings."""
    depth, j, n = 0, i, len(src)
    while j < n:
        c = src[j]
        if src.startswith('"""', j):
            j = src.index('"""', j + 3) + 3
            continue
        if c == '"':
            j += 1
            while j < n and src[j] != '"':
                j += 2 if src[j] == '\\' else 1
            j += 1
            continue
        if c in '([{':
            depth += 1
        elif c in ')]}':
            if depth == 0:
                return src[i:j]
            depth -= 1
        elif c == ',' and depth == 0:
            return src[i:j]
        j += 1
    return src[i:]


def kotlin_arg_ok(arg, consts):
    a = arg.strip()
    if not a or a.startswith('{'):
        return True                                           # Lambda (z. B. TransactionTemplate.execute { … }), kein SQL
    if a in consts:
        return True
    parts = re.split(r'\s*\+\s*', a) if '"' in a else [a]
    for p in parts:
        p = re.sub(r'\.(trimIndent|trimMargin|replace|lowercase|uppercase)\([^)]*\)$', '', p.strip())
        m = re.fullmatch(r'"""(.*)"""', p, re.S) or re.fullmatch(r'"((?:[^"\\]|\\.)*)"', p, re.S)
        if not m or INTERPOLATION.search(m.group(1)):
            return p in consts
    return True


def scan_kotlin(path):
    src = path.read_text()
    consts = set(re.findall(r'\bconst\s+val\s+([A-Za-z_]\w*)\s*(?::\s*String)?\s*=', src))
    out = []
    for m in re.finditer(r'\.(' + '|'.join(KOTLIN_SINKS) + r')\s*\(', src):
        arg = top_level_arg(src, m.end())
        line = src.count('\n', 0, m.start()) + 1
        if ALLOW.search(src.splitlines()[line - 1]):
            continue
        # Nur String-artige Argumente sind SQL: Lambdas, Callbacks und Zahlen werden von kotlin_arg_ok / der Bedingung unten ausgenommen.
        if not kotlin_arg_ok(arg, consts) and ('"' in arg or re.fullmatch(r'[A-Za-z_]\w*', arg.strip() or '0')):
            out.append((str(path), line, m.group(1), arg.strip()[:90]))
    return out


PY_BAD = (
    re.compile(r'\.(?:execute|executemany|mogrify)\(\s*[rb]*f[rb]*["\']'),                              # f-String
    re.compile(r'\.(?:execute|executemany|mogrify)\(\s*[rb]*(?:"""|\'\'\'|"|\')(?:(?!["\']\s*[,)]).)*?["\']\s*(?:%|\+|\.format\()', re.S),  # "…" % x, "…" + x, "…".format
    re.compile(r'\.(?:execute|executemany|mogrify)\(\s*(?![A-Z][A-Z0-9_]*\s*[,)])[A-Za-z_]\w*\s*[,)]'),  # Variable statt Literal (Konstanten in GROSSBUCHSTABEN sind ausgenommen)
)


def scan_python(path):
    src = path.read_text()
    out = []
    for rx in PY_BAD:
        for m in rx.finditer(src):
            line = src.count('\n', 0, m.start()) + 1
            if ALLOW.search(src.splitlines()[line - 1]):
                continue
            out.append((str(path), line, 'execute', src.splitlines()[line - 1].strip()[:90]))
    return sorted(set(out))


def main(argv):
    fmt = 'json' if '--format' in argv and argv[argv.index('--format') + 1] == 'json' else 'text'
    roots = [Path(a) for a in argv if not a.startswith('--') and a != 'json'] or [REPO / 'backend', REPO / 'workers']
    findings = []
    for root in roots:
        for p in sorted(root.rglob('*.kt')):
            if '/src/main/' in p.as_posix() and '/build/' not in p.as_posix():
                findings += scan_kotlin(p)
        for p in sorted(root.rglob('*.py')):
            s = p.as_posix()
            if '/src/' in s and '/tests/' not in s and '/build/' not in s and '/.venv/' not in s:
                findings += scan_python(p)
    if fmt == 'json':
        print(json.dumps({'tool': 'lint_sql', 'findings': [{'rule': 'sql-nicht-parametrisiert', 'location': f'{p}:{l}', 'severity': 'KRITISCH', 'title': f'{sink}({a})'} for p, l, sink, a in findings]}))
    else:
        for p, l, sink, a in findings:
            print(f'FEHLER {p}:{l}: {sink}(…) mit nicht parametrisiertem SQL: {a}')
        print('Query-Lint:', 'FEHLER' if findings else 'PASS')
    return 1 if findings else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
