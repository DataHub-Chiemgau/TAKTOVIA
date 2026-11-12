"""Auswertung der PromQL-TEILMENGE, die die Alarmregeln unter infra/observability/alert-rules.d/ benutzen (WP-M5-04).

WICHTIG (Ehrlichkeit des Nachweises): Das ist NICHT Prometheus. In dieser Umgebung gibt es weder Docker noch Prometheus/promtool. Damit die Alarmregeln trotzdem gegen vorgegebene
Messreihen ausgelöst werden können, wertet dieses Modul genau die Teilmenge aus, die die Regeln verwenden:
  Selektoren mit =, !=, =~, !~; rate()/increase() über Bereichsselektoren; sum/max/min/avg/count mit `by (…)`; + - * / %; == != > < >= <=; and/or/unless; time(); vector(); Zahlen, Klammern.
Alles andere (on/ignoring, group_left, offset, Subqueries, histogram_quantile, absent, …) wird mit `PromqlError` abgewiesen, nie still anders ausgewertet. Vereinfachungen gegenüber Prometheus:
Bereichsfunktionen extrapolieren nicht (Zuwachs / Zeitspanne zwischen erstem und letztem Messpunkt im Fenster, Zählerrücksetzer werden erkannt), Veraltung nach 5 min, kein Metadatenabgleich.
Zusätzlich liegen promtool-Testfälle der Regeln bei (rules_test.yml, in CI mit promtool auszuführen; hier NICHT ausgeführt).
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field

STALE = 300.0
DURATION = re.compile(r"^(\d+)(s|m|h|d)$")
UNIT = {"s": 1, "m": 60, "h": 3600, "d": 86400}


class PromqlError(ValueError):
    pass


def parse_duration(text: str) -> float:
    m = DURATION.match(text.strip())
    if not m:
        raise PromqlError(f"ungültige Dauer: {text!r}")
    return int(m.group(1)) * UNIT[m.group(2)]


# ---------------------------------------------------------------------------------------------------------------- Messreihen

class Store:
    """Messreihen: (Name, Etiketten) -> [(Zeit, Wert)]."""

    def __init__(self):
        self.series: dict[tuple[str, frozenset], list[tuple[float, float]]] = {}

    def add(self, name: str, labels: dict, t: float, value: float) -> None:
        key = (name, frozenset(labels.items()))
        pts = self.series.setdefault(key, [])
        pts.append((t, float(value)))
        pts.sort(key=lambda p: p[0])

    def select(self, name: str, matchers: list[tuple[str, str, str]]):
        for (n, lab), pts in self.series.items():
            if n != name:
                continue
            labels = dict(lab)
            if all(_match(labels.get(k, ""), op, v) for k, op, v in matchers):
                yield labels, pts


def _match(actual: str, op: str, expected: str) -> bool:
    if op == "=":
        return actual == expected
    if op == "!=":
        return actual != expected
    if op == "=~":
        return re.fullmatch(expected, actual) is not None
    if op == "!~":
        return re.fullmatch(expected, actual) is None
    raise PromqlError(f"Vergleichsoperator {op}")


# ---------------------------------------------------------------------------------------------------------------- Parser

TOKEN = re.compile(r"""\s*(?:(?P<num>\d+\.\d+|\d+)|(?P<id>[A-Za-z_][A-Za-z0-9_:]*)|(?P<str>"(?:[^"\\]|\\.)*")|(?P<op>==|!=|>=|<=|=~|!~|[-+*/%()<>{}\[\],=]))""")
KEYWORDS = {"and", "or", "unless", "by", "without", "on", "ignoring", "group_left", "group_right", "offset", "bool"}
AGG = {"sum", "max", "min", "avg", "count"}
FUNCS = {"rate", "increase", "time", "vector"}


@dataclass
class Selector:
    name: str
    matchers: list
    range_s: float | None = None


@dataclass
class Number:
    value: float


@dataclass
class Binary:
    op: str
    left: object
    right: object


@dataclass
class Call:
    fn: str
    args: list


@dataclass
class Agg:
    op: str
    by: list
    arg: object


def tokenize(text: str) -> list[tuple[str, str]]:
    pos, out = 0, []
    text = text.strip()
    while pos < len(text):
        m = TOKEN.match(text, pos)
        if not m or m.end() == pos:
            raise PromqlError(f"unerwartetes Zeichen bei {pos}: {text[pos:pos + 12]!r}")
        pos = m.end()
        kind = m.lastgroup
        out.append((kind, m.group(kind)))
    return out


class Parser:
    def __init__(self, text: str):
        self.toks = tokenize(text)
        self.i = 0

    def peek(self):
        return self.toks[self.i] if self.i < len(self.toks) else (None, None)

    def take(self, value=None):
        k, v = self.peek()
        if k is None or (value is not None and v != value):
            raise PromqlError(f"erwartet {value!r}, gefunden {v!r}")
        self.i += 1
        return k, v

    def parse(self):
        e = self.or_()
        if self.i != len(self.toks):
            raise PromqlError(f"überzähliges Token {self.peek()[1]!r}")
        return e

    def or_(self):
        left = self.and_()
        while self.peek()[1] == "or":
            self.take(); left = Binary("or", left, self.and_())
        return left

    def and_(self):
        left = self.cmp()
        while self.peek()[1] in ("and", "unless"):
            op = self.take()[1]; left = Binary(op, left, self.cmp())
        return left

    def cmp(self):
        left = self.add()
        if self.peek()[1] in ("==", "!=", ">", "<", ">=", "<="):
            op = self.take()[1]
            if self.peek()[1] == "bool":
                raise PromqlError("bool-Modifikator nicht unterstützt")
            left = Binary(op, left, self.add())
        return left

    def add(self):
        left = self.mul()
        while self.peek()[1] in ("+", "-"):
            op = self.take()[1]; left = Binary(op, left, self.mul())
        return left

    def mul(self):
        left = self.unary()
        while self.peek()[1] in ("*", "/", "%"):
            op = self.take()[1]; left = Binary(op, left, self.unary())
        return left

    def unary(self):
        if self.peek()[1] == "-":
            self.take(); return Binary("-", Number(0.0), self.unary())
        return self.primary()

    def primary(self):
        k, v = self.peek()
        if k == "num":
            self.take(); return Number(float(v))
        if v == "(":
            self.take(); e = self.or_(); self.take(")"); return e
        if k == "id":
            if v in KEYWORDS:
                raise PromqlError(f"nicht unterstütztes Schlüsselwort {v!r}")
            if v in AGG:
                return self.aggregation()
            if v in FUNCS:
                return self.call()
            return self.selector()
        raise PromqlError(f"unerwartetes Token {v!r}")

    def aggregation(self):
        op = self.take()[1]
        by: list[str] = []
        if self.peek()[1] == "by":
            by = self.by_list()
        if self.peek()[1] == "without":
            raise PromqlError("without nicht unterstützt")
        self.take("(")
        arg = self.or_()
        self.take(")")
        if self.peek()[1] == "by":
            by = self.by_list()
        return Agg(op, by, arg)

    def by_list(self):
        self.take("by"); self.take("(")
        names = []
        while self.peek()[1] != ")":
            names.append(self.take()[1])
            if self.peek()[1] == ",":
                self.take()
        self.take(")")
        return names

    def call(self):
        fn = self.take()[1]
        self.take("(")
        args = []
        while self.peek()[1] != ")":
            args.append(self.or_())
            if self.peek()[1] == ",":
                self.take()
        self.take(")")
        if fn in ("rate", "increase") and not (len(args) == 1 and isinstance(args[0], Selector) and args[0].range_s):
            raise PromqlError(f"{fn}() braucht genau einen Bereichsselektor")
        return Call(fn, args)

    def selector(self):
        name = self.take()[1]
        matchers = []
        if self.peek()[1] == "{":
            self.take("{")
            while self.peek()[1] != "}":
                label = self.take()[1]
                op = self.take()[1]
                if op not in ("=", "!=", "=~", "!~"):
                    raise PromqlError(f"Vergleichsoperator {op!r} im Selektor")
                k, val = self.take()
                if k != "str":
                    raise PromqlError("Etikettenwert muss ein String sein")
                matchers.append((label, op, bytes(val[1:-1], "utf-8").decode("unicode_escape")))
                if self.peek()[1] == ",":
                    self.take()
            self.take("}")
        rng = None
        if self.peek()[1] == "[":
            self.take("[")
            k, num = self.take()
            k2, unit = self.take()
            rng = parse_duration(num + unit)
            self.take("]")
        return Selector(name, matchers, rng)


def parse(text: str):
    return Parser(text).parse()


# ---------------------------------------------------------------------------------------------------------------- Auswertung

Vector = list  # [(labels: dict, value: float)]


def _key(labels: dict) -> frozenset:
    return frozenset(labels.items())


def evaluate(expr, store: Store, t: float):
    """Wert zur Zeit [t]: `float` (Skalar) oder Vektor `[(Etiketten, Wert)]`."""
    if isinstance(expr, str):
        expr = parse(expr)
    return _eval(expr, store, t)


def _eval(e, store: Store, t: float):
    if isinstance(e, Number):
        return e.value
    if isinstance(e, Selector):
        if e.range_s:
            raise PromqlError("Bereichsselektor nur in rate()/increase()")
        out = []
        for labels, pts in store.select(e.name, e.matchers):
            last = [p for p in pts if p[0] <= t and t - p[0] <= STALE]
            if last:
                out.append((labels, last[-1][1]))
        return out
    if isinstance(e, Call):
        if e.fn == "time":
            return float(t)
        if e.fn == "vector":
            v = _eval(e.args[0], store, t)
            if not isinstance(v, float):
                raise PromqlError("vector() erwartet einen Skalar")
            return [({}, v)]
        sel = e.args[0]
        out = []
        for labels, pts in store.select(sel.name, sel.matchers):
            window = [p for p in pts if t - sel.range_s < p[0] <= t]
            if len(window) < 2:
                continue
            inc = 0.0
            for (_, a), (_, b) in zip(window, window[1:]):
                inc += b - a if b >= a else b   # Zählerrücksetzer: nach dem Reset zählt der neue Wert von 0 an
            span = window[-1][0] - window[0][0]
            out.append((labels, inc / span if e.fn == "rate" else inc))
        return out
    if isinstance(e, Agg):
        v = _eval(e.arg, store, t)
        if isinstance(v, float):
            raise PromqlError("Aggregation über Skalar")
        groups: dict[frozenset, list[float]] = {}
        names: dict[frozenset, dict] = {}
        for labels, val in v:
            sub = {k: labels[k] for k in e.by if k in labels}
            groups.setdefault(_key(sub), []).append(val); names[_key(sub)] = sub
        f = {"sum": sum, "max": max, "min": min, "avg": lambda x: sum(x) / len(x), "count": len}[e.op]
        return [(names[k], float(f(vals))) for k, vals in groups.items()]
    if isinstance(e, Binary):
        return _binary(e, _eval(e.left, store, t), _eval(e.right, store, t))
    raise PromqlError(f"nicht auswertbar: {e!r}")


ARITH = {"+": lambda a, b: a + b, "-": lambda a, b: a - b, "*": lambda a, b: a * b, "/": lambda a, b: a / b if b != 0 else float("nan"), "%": lambda a, b: a % b if b != 0 else float("nan")}
CMP = {"==": lambda a, b: a == b, "!=": lambda a, b: a != b, ">": lambda a, b: a > b, "<": lambda a, b: a < b, ">=": lambda a, b: a >= b, "<=": lambda a, b: a <= b}


def _binary(e: Binary, a, b):
    op = e.op
    if op in ("and", "or", "unless"):
        if isinstance(a, float) or isinstance(b, float):
            raise PromqlError(f"{op} nur zwischen Vektoren")
        kb = {_key(l) for l, _ in b}
        if op == "and":
            return [(l, v) for l, v in a if _key(l) in kb]
        if op == "unless":
            return [(l, v) for l, v in a if _key(l) not in kb]
        ka = {_key(l) for l, _ in a}
        return list(a) + [(l, v) for l, v in b if _key(l) not in ka]
    fn = CMP.get(op) or ARITH[op]
    is_cmp = op in CMP
    if isinstance(a, float) and isinstance(b, float):
        if is_cmp:
            raise PromqlError("Vergleich zweier Skalare braucht bool")
        return fn(a, b)
    if isinstance(b, float):
        return [(l, v if is_cmp else fn(v, b)) for l, v in a if not is_cmp or fn(v, b)]
    if isinstance(a, float):
        return [(l, v if is_cmp else fn(a, v)) for l, v in b if not is_cmp or fn(a, v)]
    right = {_key(l): v for l, v in b}
    out = []
    for l, v in a:
        if _key(l) not in right:
            continue
        w = right[_key(l)]
        if is_cmp:
            if fn(v, w):
                out.append((l, v))
        else:
            out.append((l, fn(v, w)))
    return out


# ---------------------------------------------------------------------------------------------------------------- Regelauswertung

@dataclass
class Alert:
    alertname: str
    labels: dict
    annotations: dict
    active_at: float
    state: str = "pending"          # pending | firing
    fired_at: float | None = None
    value: float = 0.0

    @property
    def fingerprint(self) -> str:
        return self.alertname + "|" + ",".join(f"{k}={v}" for k, v in sorted(self.labels.items()))


@dataclass
class RuleEvaluator:
    """Wertet Alarmregeln (Gruppenstruktur wie in Prometheus-Regeldateien) wiederholt aus und führt je Reihe die Zustände pending -> firing -> aufgelöst."""
    groups: list
    store: Store
    active: dict = field(default_factory=dict)
    log: list = field(default_factory=list)   # (Zeit, Ereignis, Fingerprint)

    def step(self, t: float) -> tuple[list[Alert], list[Alert]]:
        """Ein Auswertungsschritt zur Zeit t. Liefert (neu feuernde, aufgelöste) Alarme."""
        new_firing, resolved = [], []
        seen: set[str] = set()
        for g in self.groups:
            for r in g["rules"]:
                if "alert" not in r:
                    continue
                vec = evaluate(r["expr"], self.store, t)
                if isinstance(vec, float):
                    raise PromqlError(f"Regel {r['alert']}: Skalar statt Vektor")
                hold = parse_duration(str(r["for"])) if r.get("for") else 0.0
                for labels, value in vec:
                    full = {**labels, **r.get("labels", {}), "alertname": r["alert"]}
                    a = Alert(r["alert"], full, dict(r.get("annotations", {})), t, value=value)
                    fp = a.fingerprint
                    seen.add(fp)
                    cur = self.active.get(fp)
                    if cur is None:
                        cur = a; self.active[fp] = a; self.log.append((t, "pending", fp))
                    cur.value = value
                    if cur.state == "pending" and t - cur.active_at >= hold:
                        cur.state = "firing"; cur.fired_at = t; new_firing.append(cur); self.log.append((t, "firing", fp))
        for fp in list(self.active):
            if fp not in seen:
                a = self.active.pop(fp)
                self.log.append((t, "resolved", fp))
                if a.state == "firing":
                    resolved.append(a)
        return new_firing, resolved
