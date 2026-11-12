"""Angriffskatalog je Endpunkt, generiert aus den OpenAPI-Dateien (WP-M1-38, LH-6.18-SEC-009).

Arbeitsgrundlage: OWASP API Security Top 10 (vom Pflichtenheft nicht vorgegeben, siehe docs/security/eingabehaertung.md). Der Katalog ist
Ziel-unabhängig: derselbe Lauf geht gegen die lokal gestartete Anwendung und, mit `TAKTOVIA_BASE_URL`, gegen Staging (DAST-Ersatz/-Ergänzung).
Jeder Angriff trägt seine Erwartung; abgewiesen heißt: 4xx als `application/problem+json`, ohne interne Details.
"""
import json
from dataclasses import dataclass, field
from typing import Callable, Optional

import openapi_samples as samples
from harness import INTERNAL, SECURITY_HEADERS

METHODS = ('get', 'post', 'put', 'patch', 'delete')
SQLI = ["' OR '1'='1' --", "1; DROP TABLE tenant; --", "' UNION SELECT NULL,NULL--", "1 OR 1=1"]
XSS = ["<script>alert(1)</script>", "\"><img src=x onerror=alert(1)>", "javascript:alert(1)"]
MANIPULATION = ["{p}%0d%0aSet-Cookie:%20pwned=1", "{p};jsessionid=1", "{p}/..%2f..%2fadmin", "{p}/%2e%2e/x", "{p}%00", "{p}%5c..%5cx", "/v1//{q}", "{p}/./x"]
SSRF_URLS = [
    "https://127.0.0.1/hook", "https://localhost/hook", "https://169.254.169.254/latest/meta-data/", "https://10.0.0.1/x", "https://192.168.1.1/x", "https://[::1]/x",
    "https://2130706433/x", "https://0x7f.0.0.1/x", "https://intern.internal/x", "https://user:pw@partner.example.org/x", "http://partner.example.org/x", "file:///etc/passwd",
]


@dataclass
class Attack:
    category: str
    op: str
    method: str
    path: str
    body: Optional[str] = None
    headers: dict = field(default_factory=dict)
    content_type: Optional[str] = 'application/json'
    expect: Callable = lambda r: None
    raw: Optional[bytes] = None           # roh gesendete Anfrage (Header-Injektion)
    note: str = ''

    def label(self):
        return f'{self.category} {self.method} {self.path} ({self.op}) {self.note}'.strip()


def operations(doc):
    for path, item in doc['paths'].items():
        for method in METHODS:
            if method in item:
                yield method.upper(), path, item[method]


def param_names(doc, op):
    out = []
    for p in op.get('parameters', []):
        out.append(samples.resolve(doc, p))
    return out


def fill_path(doc, path, op, replace=None):
    """Pfadvariablen mit gültigen Werten füllen; `replace` = (Variablenname, Wert) ersetzt eine."""
    for p in param_names(doc, op):
        if p['in'] == 'path':
            # Ganzzahlige Pfadvariable (z. B. Planrevision, WP-M4-15): gültiger Wert 1, sonst die Beispiel-UUID
            default = '1' if samples.resolve(doc, p.get('schema', {})).get('type') == 'integer' else samples.UUID_EXAMPLE
            value = replace[1] if replace and replace[0] == p['name'] else default
            path = path.replace('{' + p['name'] + '}', value)
    return path


def valid_headers(doc, op):
    h = {}
    for p in param_names(doc, op):
        if p['in'] != 'header' or not p.get('required'):
            continue
        if p['name'] == 'If-Match':
            h['If-Match'] = '"1"'
        elif p['name'] == 'Idempotency-Key':
            h['Idempotency-Key'] = 'idempotency-key-0123456789'
        else:
            h[p['name']] = str(samples.sample(doc, p['schema']))
    return h


def valid_query(doc, op):
    q = {}
    for p in param_names(doc, op):
        if p['in'] == 'query' and p.get('required'):
            q[p['name']] = str(samples.sample(doc, p['schema']))
    return q


def body_schema(op):
    return op.get('requestBody', {}).get('content', {}).get('application/json', {}).get('schema')


def valid_body(doc, op):
    s = body_schema(op)
    return samples.sample(doc, s) if s else None


def with_query(path, q):
    return path + ('?' + '&'.join(f'{k}={v}' for k, v in q.items()) if q else '')


def string_leaves(value, pointer=''):
    """JSON-Pointer aller String-Blätter eines Beispielkörpers."""
    if isinstance(value, str):
        yield pointer
    elif isinstance(value, dict):
        for k, v in value.items():
            yield from string_leaves(v, f'{pointer}/{k}')
    elif isinstance(value, list):
        for i, v in enumerate(value):
            yield from string_leaves(v, f'{pointer}/{i}')


def set_pointer(value, pointer, new):
    out = json.loads(json.dumps(value))
    cur = out
    parts = [p for p in pointer.split('/') if p]
    for part in parts[:-1]:
        cur = cur[int(part)] if isinstance(cur, list) else cur[part]
    last = parts[-1]
    if isinstance(cur, list):
        cur[int(last)] = new
    else:
        cur[last] = new
    return out


# ------------------------------------------------------------------ Erwartungen
def rejected(statuses):
    def check(r):
        if r.status not in statuses:
            return f'erwartet {sorted(statuses)}, erhalten {r.status}: {r.body[:160]}'
    return check


def robust(r):
    """Kein Serverfehler; ein 4xx ist ein Problem."""
    if r.status >= 500:
        return f'Serverfehler {r.status}: {r.body[:160]}'


def not_reflected(*needles):
    def check(r):
        for n in needles:
            if n in r.body:
                return f'Angriffsdaten {n!r} in der Antwort gespiegelt'
    return check


def both(*checks):
    def check(r):
        for c in checks:
            m = c(r)
            if m:
                return m
    return check


def generate(doc):
    attacks = []
    for method, path, op in operations(doc):
        oid = op['operationId']
        mutating = method in ('POST', 'PUT', 'PATCH', 'DELETE')
        hdr = valid_headers(doc, op)
        q = valid_query(doc, op)
        ok_path = fill_path(doc, path, op)
        schema = body_schema(op)
        body = valid_body(doc, op) if schema else None
        add = lambda cat, **kw: attacks.append(Attack(cat, oid, kw.pop('method', method), kw.pop('path', ok_path), headers=kw.pop('headers', hdr), **kw))

        # 1. SQL-Injection über Pfadvariablen (alle UUID): Formatprüfung
        for p in param_names(doc, op):
            if p['in'] == 'path':
                for payload in SQLI[:2]:
                    add('sql-injection-pfad', path=with_query(fill_path(doc, path, op, (p['name'], payload.replace(' ', '%20'))), q), expect=rejected({400, 422}), note=p['name'])
        # 2. SQL-Injection über typisierte Query-Parameter
        for p in param_names(doc, op):
            if p['in'] == 'query':
                t = samples.resolve(doc, p['schema'])
                if t.get('type') in ('integer', 'number') or t.get('format') in ('date-time', 'uuid'):
                    for payload in SQLI[:2]:
                        add('sql-injection-query', path=with_query(ok_path, {**q, p['name']: payload.replace(' ', '%20')}), expect=rejected({422}), note=p['name'])
        # 3. Unbekannter Query-Parameter / Parameter Pollution
        add('unbekannter-query-parameter', path=with_query(ok_path, {**q, 'admin': 'true'}), expect=rejected({422}))
        # 4. Pfadmanipulation
        flat = ok_path
        for tpl in MANIPULATION:
            add('pfadmanipulation', path=tpl.format(p=flat, q=flat.lstrip('/')), expect=rejected({400}), note=tpl.split('{')[0] or tpl[-8:])
        # 5. Fremde CORS-Origin und fehlender CSRF-Schutz
        add('fremde-origin', headers={**hdr, 'Origin': 'https://evil.example'}, path=with_query(ok_path, q), expect=rejected({403}))
        if mutating:
            add('csrf-cross-site', headers={**hdr, 'Sec-Fetch-Site': 'cross-site'}, path=with_query(ok_path, q), body=json.dumps(body) if schema else None, expect=rejected({403}))
        # 6. Header-Injektion (roh): CR/LF im Headerwert
        # Zeilenumbrüche in Headerwerten (CR LF und einzelnes LF) dürfen nie als Header in der Antwort erscheinen
        raw = (f'{method} {with_query(ok_path, q)} HTTP/1.1\r\nHost: localhost\r\nX-Evil: a\r\nSet-Cookie: pwned=1\r\nX-Evil2: b\nSet-Cookie: pwned2=1\r\n'
               f'Connection: close\r\n\r\n').encode()
        attacks.append(Attack('header-injektion', oid, method, ok_path, raw=raw, expect=both(
            lambda r: None if r.status < 500 else f'Serverfehler {r.status}', lambda r: 'Set-Cookie in der Antwort' if 'set-cookie' in r.headers else None,
            not_reflected('pwned'))))
        if not schema:
            continue
        # ---- Angriffe auf Requests mit Body
        path_q = with_query(ok_path, q)
        # 7. Falscher Content-Type
        for ct in ('text/plain', 'application/x-www-form-urlencoded', 'application/xml', 'multipart/form-data; boundary=x'):
            add('falscher-content-type', path=path_q, body=json.dumps(body), content_type=ct, expect=rejected({415}), note=ct)
        # 8. Übergroßer und tief verschachtelter Body
        pad = json.dumps({**body, 'pad': 'x' * 1_200_000}) if isinstance(body, dict) else '[' + '"x",' * 400_000 + '"x"]'
        add('uebergrosser-body', path=path_q, body=pad, expect=rejected({413}))
        add('tief-verschachtelter-body', path=path_q, body='[' * 200 + ']' * 200, expect=rejected({400, 413, 422}))
        # 9. Doppelte Schlüssel, Text nach dem Body, kaputtes JSON
        add('doppelte-schluessel', path=path_q, body='{"a":1,"a":2}', expect=rejected({400}))
        add('text-nach-json', path=path_q, body=json.dumps(body) + ' {"x":1}', expect=rejected({400}))
        add('kaputtes-json', path=path_q, body='{"a":', expect=rejected({400}))
        # 10. Mass Assignment
        if isinstance(body, dict):
            for field_name in ('is_admin', 'tenant_id', 'revision'):
                if field_name not in body:
                    add('mass-assignment', path=path_q, body=json.dumps({**body, field_name: 'x'}), expect=rejected({422}), note=field_name)
        # 11. Injektionen und Steuerzeichen in String-Feldern
        for pointer in list(string_leaves(body))[:3]:
            for payload in XSS[:2] + SQLI[:1]:
                add('injektion-im-feld', path=path_q, body=json.dumps(set_pointer(body, pointer, payload)), expect=both(robust, not_reflected('<script>', 'onerror=')), note=pointer)
            add('steuerzeichen-im-feld', path=path_q, body=json.dumps(set_pointer(body, pointer, 'a\u0000b')), expect=rejected({422}), note=pointer)
        # 12. SSRF-Ziele in URL-Feldern (x-url)
        if isinstance(body, dict):
            props = samples.resolve(doc, body_schema(op)).get('properties', {})
            for name, prop in props.items():
                if samples.resolve(doc, prop).get('x-url'):
                    for url in SSRF_URLS:
                        add('ssrf', path=path_q, body=json.dumps({**body, name: url}), expect=rejected({422}), note=url)
    return attacks


def valid_requests(doc):
    """Gültige Anfragen je Endpunkt für den Regressionslauf ohne Fehlalarm."""
    out = []
    for method, path, op in operations(doc):
        schema = body_schema(op)
        out.append((op['operationId'], method, with_query(fill_path(doc, path, op), valid_query(doc, op)), json.dumps(valid_body(doc, op)) if schema else None, valid_headers(doc, op)))
    return out


# ------------------------------------------------------------------ Invarianten und Ausführung
def invariants(resp, label):
    """Gilt für jede Antwort, auch auf Angriffe: Sicherheitsheader, Correlation-ID, Problem ohne interne Details."""
    errs = []
    for k, v in SECURITY_HEADERS.items():
        if resp.headers.get(k) != v:
            errs.append(f'{label}: Header {k} fehlt oder falsch ({resp.headers.get(k)!r})')
    if resp.status >= 400:
        if not resp.is_problem():
            errs.append(f'{label}: Fehlerantwort {resp.status} ist kein application/problem+json')
        else:
            try:
                p = resp.json
                missing = {'type', 'title', 'status', 'code', 'correlation_id'} - set(p)
                if missing or p['status'] != resp.status:
                    errs.append(f'{label}: Problem unvollständig {sorted(missing)}')
            except ValueError:
                errs.append(f'{label}: Fehlerantwort ist kein JSON')
        m = INTERNAL.search(resp.body.replace('api.taktovia.example', ''))
        if m:
            errs.append(f'{label}: interne Details in der Antwort ({m.group(0)!r}): {resp.body[:200]}')
    return errs


def dechunk(raw: bytes) -> bytes:
    out, rest = b'', raw
    while rest:
        size, _, rest = rest.partition(b'\r\n')
        n = int(size.split(b';')[0] or b'0', 16)
        if n == 0:
            break
        out, rest = out + rest[:n], rest[n + 2:]
    return out


def run(client, attacks):
    """Führt Angriffe aus; Rückgabe: Liste (Attack, Resp, Fehlermeldungen)."""
    from harness import Resp
    results = []
    for a in attacks:
        if a.raw is not None:
            data = client.raw(a.raw)
            head, _, body = data.partition(b'\r\n\r\n')
            lines = head.decode('latin-1').split('\r\n')
            if any(l.lower() == 'transfer-encoding: chunked' for l in lines):
                body = dechunk(body)
            status = int(lines[0].split()[1]) if lines and lines[0].startswith('HTTP/') else 0
            resp = Resp(status, [tuple(l.split(': ', 1)) for l in lines[1:] if ': ' in l], body.decode('utf-8', 'replace'))
        else:
            headers = dict(a.headers)
            if a.content_type is None:
                headers.pop('Content-Type', None)
            elif a.body is not None:
                headers['Content-Type'] = a.content_type
            resp = client.request(a.method, a.path, a.body, headers)
        errs = []
        msg = a.expect(resp)
        if msg:
            errs.append(f'{a.label()}: {msg}')
        errs += invariants(resp, a.label())
        results.append((a, resp, errs))
    return results
