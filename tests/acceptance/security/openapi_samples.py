"""Erzeugt gültige Beispielwerte aus den OpenAPI-Schemas (WP-M1-38): Grundlage des Angriffskatalogs und des Regressionslaufs ohne Fehlalarm.

Bevorzugt `examples` des Schemas; sonst aus Typ, Format, Muster und Grenzen abgeleitet. Kann ein Wert nicht erzeugt werden, ist das ein Fehler
des Vertrags oder des Generators und wird gemeldet, nicht verschwiegen.
"""
import re
import uuid
import re._parser as sre_parse  # Syntaxbaum regulärer Ausdrücke (Standardbibliothek)

UUID_EXAMPLE = '00000000-0000-4000-8000-0000000000aa'


class NotGenerable(Exception):
    pass


def resolve(doc, node):
    seen = 0
    while isinstance(node, dict) and '$ref' in node:
        ref = node['$ref']
        if not ref.startswith('#/'):
            ref = ref[ref.index('#'):]
        cur = doc
        for part in ref[2:].split('/'):
            cur = cur[part.replace('~1', '/').replace('~0', '~')]
        node, seen = cur, seen + 1
        if seen > 20:
            raise NotGenerable('Referenzschleife')
    return node


def from_pattern(pattern: str, min_len=0) -> str:
    """Kürzester Beispielstring zu einem regulären Ausdruck (Wiederholungen mit Untergrenze, erstes Zeichen jeder Klasse)."""
    tree = sre_parse.parse(pattern)

    def gen(items):
        out = ''
        for op, av in items:
            name = str(op)
            if name == 'LITERAL':
                out += chr(av)
            elif name == 'IN':
                kind, val = av[0]
                if str(kind) == 'RANGE':
                    out += chr(val[0])
                elif str(kind) == 'LITERAL':
                    out += chr(val)
                elif str(kind) == 'CATEGORY':
                    out += '0' if 'DIGIT' in str(val) else 'a'
                else:
                    raise NotGenerable(pattern)
            elif name in ('MAX_REPEAT', 'MIN_REPEAT'):
                lo, _hi, sub = av
                out += gen(sub) * lo
            elif name == 'SUBPATTERN':
                out += gen(av[3])
            elif name == 'BRANCH':
                out += gen(av[1][0])
            elif name == 'CATEGORY':
                out += '0' if 'DIGIT' in str(av) else 'a'
            elif name == 'ANY':
                out += 'a'
            elif name in ('AT', 'ASSERT', 'ASSERT_NOT'):
                continue
            else:
                raise NotGenerable(f'{pattern}: {name}')
        return out

    s = gen(list(tree))
    if len(s) < min_len:
        s += (s[-1] if s else 'a') * (min_len - len(s))  # letztes Zeichen wiederholen, bis die Mindestlänge erreicht ist
    if not re.search(pattern, s):
        raise NotGenerable(f'Muster {pattern!r} nicht erzeugbar')
    return s


def sample(doc, schema, depth=0):
    if depth > 12:
        raise NotGenerable('zu tief')
    node = resolve(doc, schema)
    if 'examples' in node and node['examples']:
        return node['examples'][0]
    if 'const' in node:
        return node['const']
    if 'enum' in node:
        return node['enum'][0]
    for key in ('oneOf', 'anyOf'):
        if key in node:
            non_null = [s for s in node[key] if resolve(doc, s).get('type') != 'null']
            return sample(doc, non_null[0] if non_null else node[key][0], depth + 1)
    if 'allOf' in node:
        merged = {}
        for s in node['allOf']:
            v = sample(doc, s, depth + 1)
            if isinstance(v, dict):
                merged.update(v)
        return merged
    t = node.get('type')
    if isinstance(t, list):
        t = next((x for x in t if x != 'null'), 'null')
    fmt = node.get('format')
    if t == 'object' or 'properties' in node:
        out = {}
        for name in node.get('required', []):
            out[name] = sample(doc, node['properties'][name], depth + 1)
        return out
    if t == 'array':
        n = max(node.get('minItems', 0), 0)
        return [sample(doc, node['items'], depth + 1) for _ in range(n)]
    if t == 'string':
        if fmt == 'uuid':
            return str(uuid.UUID(UUID_EXAMPLE))
        if fmt == 'date-time':
            return '2026-12-01T10:00:00+01:00'
        if fmt == 'date':
            return '2026-12-01'
        if fmt == 'uri':
            return 'https://partner.example.org/hooks/taktovia'
        mn = node.get('minLength', 0)
        if 'pattern' in node:
            return from_pattern(node['pattern'], mn)
        return 'a' * max(mn, 1)
    if t in ('integer', 'number'):
        return int(node['minimum']) if 'minimum' in node else 0
    if t == 'boolean':
        return True
    if t == 'null':
        return None
    raise NotGenerable(f'Typ {t!r} nicht erzeugbar')
