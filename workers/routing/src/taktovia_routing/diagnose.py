"""Diagnose und Telemetrie der Worker (WP-M5-03 Telemetrie, WP-M5-06 Maskierung). Nur Standardbibliothek.

IDENTISCH in workers/routing/src/taktovia_routing und workers/documents/src/taktovia_documents (die Worker sind eigenständig
paketiert; ein Test in tests/acceptance/diagnose_logging hält beide Kopien gleich). Inhalt:

- Maskierung wie `backend/platform/.../logging/Maskierung.kt` (Regeln: docs/betrieb/maskierung.md): ``mask_text``, ``klartext_klassen``,
  ``describe_exception``; UUIDs und Trace-IDs bleiben lesbar.
- W3C ``traceparent``: ``parse_traceparent``, ``continue_trace`` (ungültig oder fehlend: neue Trace-ID = Correlation-ID, wie im Kern).
- Metriken im Prometheus-Textformat mit Namens- und Labelkonvention (docs/betrieb/telemetrie.md); verbotene Labels werden verworfen und gezählt.
- Spans (OTLP/HTTP-JSON, best effort): ein ausgefallener Sammler beeinträchtigt die Verarbeitung nie.
- ``JsonFormatter``: strukturierte, maskierte Logzeilen; Felder nur aus der Positivliste.
"""

# ruff: noqa: E501
from __future__ import annotations

import json
import logging
import re
import secrets
import threading
import time
import urllib.request
import uuid
from collections.abc import Iterator, Mapping
from contextlib import contextmanager
from dataclasses import dataclass, field

# --------------------------------------------------------------------------- Maskierung

CARD_MASK = "[card-data-removed]"
_UUID = r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
_UUID_RE = re.compile(_UUID)
_TRACE_ID = re.compile(
    r"(?<![0-9A-Za-z])(?=[0-9a-f]*[a-f])(?:[0-9a-f]{32}|[0-9a-f]{16})(?![0-9A-Za-z])"
)
_SECRET_KV = re.compile(
    r"(?i)\b(pass(?:word|wd)?|passwort|secret|token|access[_-]?token|refresh[_-]?token|authorization|api[_-]?key|client[_-]?secret|"
    r"cookie|session[_-]?id|otp|pin|cvc|cvv)\b(\s*[=:]\s*|\"\s*:\s*\")(?!\[maskiert)(?:Bearer\s+|Basic\s+)?[^\s,;&\"'}\]]+"
)
_BOARDING_KV = re.compile(
    r"(?i)\b(boarding[_ -]?code)\b(\s*(?:[=:]|\"\s*:)\s*\"?|\s+)(?!\[maskiert)[A-Za-z0-9-]{4,}"
)
_PRE: list[tuple[str, re.Pattern[str]]] = [
    ("token", re.compile(r"eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{4,}")),
    ("token", re.compile(r"(?i)\b(?:bearer|basic)\s+[A-Za-z0-9._~+/=-]{8,}")),
    ("email", re.compile(r"[A-Za-z0-9._%+'-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}")),
    ("iban", re.compile(r"\b[A-Z]{2}\d{2}(?:\s?[A-Z0-9]{4}){2,7}(?:\s?[A-Z0-9]{1,3})?\b")),
    ("boarding-code", re.compile(r"\bBC-[A-Z0-9]{4,}\b")),
]
_POST: list[tuple[str, re.Pattern[str]]] = [
    (
        "telefon",
        re.compile(r"(?<![\w.-])(?:\+|00)\d{1,3}[\s/().-]*\d(?:[\s/().-]?\d){5,13}(?![\w-])"),
    ),
    ("telefon", re.compile(r"(?<![\w.+-])0\d{2,5}[\s/-]?\d(?:[\s/-]?\d){4,9}(?![\w-])")),
    (
        "adresse",
        re.compile(
            r"\b[A-ZÄÖÜ][\wäöüß.-]*(?:stra(?:ß|ss)e|str\.|weg|allee|platz|gasse|ring|damm|ufer|chaussee)\s+\d{1,4}\s?[a-zA-Z]?\b"
            r"(?:,?\s*\d{5}\s+[A-ZÄÖÜ][\wäöüß-]+)?"
        ),
    ),
    ("adresse", re.compile(r"(?<![\d.-])\d{5}\s+[A-ZÄÖÜ][a-zäöüß]{2,}(?:-[A-ZÄÖÜ][a-zäöüß]+)?\b")),
]
_RULES = _PRE + _POST  # IBAN/E-Mail/Token vor der Kartenerkennung, Telefon/Adresse danach
_CARD_RUN = re.compile(r"\d(?:[ .\-]?\d){12,}")
_PREFIXES = (
    "34",
    "37",
    "4",
    "51",
    "52",
    "53",
    "54",
    "55",
    "36",
    "300",
    "301",
    "302",
    "303",
    "304",
    "305",
    "35",
    "6011",
    "65",
    "62",
    "50",
    "56",
    "57",
    "58",
    "63",
    "67",
)


def _luhn(d: str) -> bool:
    s, alt = 0, False
    for c in reversed(d):
        n = int(c)
        if alt:
            n = n * 2 - 9 if n * 2 > 9 else n * 2
        s += n
        alt = not alt
    return s % 10 == 0


def _card_spans(text: str) -> list[tuple[int, int]]:
    scan = _UUID_RE.sub(lambda m: "x" * len(m.group()), text)
    found: list[tuple[int, int]] = []
    for m in _CARD_RUN.finditer(scan):
        idx = [i for i in range(m.start(), m.end()) if scan[i].isdigit()]
        d = "".join(scan[i] for i in idx)
        for ln in range(13, min(19, len(d)) + 1):
            for st in range(0, len(d) - ln + 1):
                part = d[st : st + ln]
                if part.startswith(_PREFIXES) and _luhn(part):
                    found.append((idx[st], idx[st + ln - 1] + 1))
    found.sort()
    merged: list[tuple[int, int]] = []
    for a, b in found:
        if merged and a <= merged[-1][1]:
            merged[-1] = (merged[-1][0], max(merged[-1][1], b))
        else:
            merged.append((a, b))
    return merged


def _mask_cards(text: str) -> str:
    spans = _card_spans(text) if len(text) >= 13 else []
    out, pos = [], 0
    for a, b in spans:
        out += [text[pos:a], CARD_MASK]
        pos = b
    return "".join(out) + text[pos:]


def _protected(s: str) -> list[tuple[int, int]]:
    rs = sorted(
        [(m.start(), m.end()) for m in _UUID_RE.finditer(s)]
        + [(m.start(), m.end()) for m in _TRACE_ID.finditer(s)]
    )
    merged: list[tuple[int, int]] = []
    for a, b in rs:
        if merged and a <= merged[-1][1]:
            merged[-1] = (merged[-1][0], max(merged[-1][1], b))
        else:
            merged.append((a, b))
    return merged


def _phase(s: str, rules: list[tuple[str, re.Pattern[str]]]) -> str:
    out, pos = [], 0
    for a, b in _protected(s):
        out += [_segment(s[pos:a], rules), s[a:b]]
        pos = b
    out.append(_segment(s[pos:], rules))
    return "".join(out)


def _segment(seg: str, rules: list[tuple[str, re.Pattern[str]]]) -> str:
    for klass, rx in rules:
        seg = rx.sub(f"[maskiert:{klass}]", seg)
    return seg


def mask_text(text: str | None) -> str:
    """Ersetzt Klartext der Feldklassen (Karte, IBAN, E-Mail, Telefon, Adresse, Token, Boarding-Code)."""
    if not text:
        return text or ""
    s = _SECRET_KV.sub(lambda m: m.group(1) + m.group(2) + "[maskiert:token]", text)
    s = _BOARDING_KV.sub(lambda m: m.group(1) + m.group(2) + "[maskiert:boarding-code]", s)
    s = _phase(s, _PRE)
    s = _mask_cards(s)
    return _phase(s, _POST)


def klartext_klassen(text: str) -> set[str]:
    """Feldklassen, die in ``text`` im Klartext stehen (maskierte Ersatzdarstellungen zählen nicht)."""
    found: set[str] = set()
    if _card_spans(text):
        found.add("karte")
    if _SECRET_KV.search(text):
        found.add("token")
    if _BOARDING_KV.search(text):
        found.add("boarding-code")
    segs, pos = [], 0
    for a, b in _protected(text):
        segs.append(text[pos:a])
        pos = b
    segs.append(text[pos:])
    for seg in segs:
        for klass, rx in _RULES:
            if rx.search(seg):
                found.add(klass)
    return found


def describe_exception(exc: BaseException) -> str:
    """Klassenname und maskierte, gekürzte Nachricht der Ursachenkette; kein Stapel, keine Objektinhalte."""
    parts: list[str] = []
    cur: BaseException | None = exc
    while cur is not None and len(parts) < 4:
        msg = str(cur)
        parts.append(type(cur).__name__ + (": " + mask_text(msg)[:300] if msg else ""))
        cur = cur.__cause__ or cur.__context__
    return " <- ".join(parts)


_SPERR = re.compile(
    r"(?i)(?<![a-z])(pass(word|wd)?|secret|token|authorization|api[_-]?key|credential|cookie|signature|private[_-]?key|otp|pin|cvc|cvv|bearer|session|"
    r"iban|card|pan|e-?mail|phone|mobil|tel(efon)?|first[_-]?name|last[_-]?name|full[_-]?name|vorname|nachname|address|adresse|street|strasse|postal|"
    r"birth|geburt|boarding|body|query|search|passenger_name|fahrgast_name)s?(?![a-z])"
)


def is_blocked_key(key: str) -> bool:
    return bool(_SPERR.search(key))


def trace_attribute(key: str, value: object) -> str:
    if value is None:
        return ""
    if is_blocked_key(key):
        return "[maskiert:token]"
    return mask_text(str(value))[:256]


# Positivliste protokollierbarer Felder (wie Positivliste.kt: ALLGEMEIN + Modul).
LOG_FIELDS = frozenset(
    [
        "correlation_id",
        "tenant_id",
        "tenant_ref",
        "request_id",
        "trace_id",
        "span_id",
        "event",
        "event_type",
        "aggregate_type",
        "aggregate_id",
        "operation_id",
        "status",
        "result",
        "code",
        "attempts",
        "count",
        "duration_ms",
        "queue",
        "component",
        "reason_code",
        "revision",
        "http_method",
        "route_class",
        "http_status",
        "actor_ref",
        "error_class",
        "solver_ms",
        "queue_wait_ms",
        "timeout",
        "plan_id",
        "vehicle_id",
        "document_id",
        "scan_result",
        "ocr_state",
        "size_bytes",
        "media_type",
        "task_id",
        "kind",
    ]
)

# --------------------------------------------------------------------------- Trace-Kontext


@dataclass(frozen=True)
class TraceContext:
    trace_id: str
    span_id: str
    flags: str = "01"
    parent_span_id: str | None = None

    @property
    def correlation_id(self) -> str:
        t = self.trace_id
        return f"{t[:8]}-{t[8:12]}-{t[12:16]}-{t[16:20]}-{t[20:]}"

    def traceparent(self) -> str:
        return f"00-{self.trace_id}-{self.span_id}-{self.flags}"


_TP = re.compile(r"^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$")


def parse_traceparent(header: str | None) -> TraceContext | None:
    m = _TP.match(header or "")
    if not m:
        return None
    version, trace, parent, flags = m.groups()
    if version == "ff" or set(trace) == {"0"} or set(parent) == {"0"}:
        return None
    return TraceContext(trace, parent, flags)


def new_span_id() -> str:
    return secrets.token_hex(8)


def continue_trace(header: str | None) -> TraceContext:
    """Gültiger Header: gleiche Trace-ID, neue Span-ID mit dem Aufrufer als Elternteil. Sonst eine neue Trace-ID (= neue Correlation-ID)."""
    parent = parse_traceparent(header)
    if parent is None:
        return TraceContext(uuid.uuid4().hex, new_span_id())
    return TraceContext(parent.trace_id, new_span_id(), parent.flags, parent.span_id)


# --------------------------------------------------------------------------- Metriken

_NAME = re.compile(r"taktovia_[a-z][a-z0-9_]{2,100}")
_SAFE_VALUE = re.compile(r"[A-Za-z0-9_.:/-]{0,64}")
ALLOWED_LABELS = frozenset(
    [
        "component",
        "route_class",
        "method",
        "status_class",
        "result",
        "kind",
        "queue",
        "outcome",
        "error_class",
        "tenant_id",
        "worker",
        "event_type",
        "consumer",
        "state",
        "operation",
        "reason",
        "phase",
    ]
)
_BUCKETS = (0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0)


def label_violation(name: str, labels: Mapping[str, str]) -> str | None:
    if not _NAME.fullmatch(name):
        return "Metrikname verletzt die Namenskonvention"
    for k, v in labels.items():
        if is_blocked_key(k):
            return f"Labelschlüssel '{k}' ist verboten"
        if k not in ALLOWED_LABELS:
            return f"Labelschlüssel '{k}' steht nicht in der Positivliste"
        if not _SAFE_VALUE.fullmatch(v):
            return f"Labelwert zu '{k}' unzulässig"
        if k == "tenant_id" and not _UUID_RE.fullmatch(v):
            return "tenant_id nur als UUID"
        if klartext_klassen(v):
            return f"Labelwert zu '{k}' enthält Kontakt-, Zahlungs- oder Tokenmuster"
    return None


class Registry:
    """Thread-sicheres Metrikregister; Aufrufe werfen nie (Telemetrie darf die Verarbeitung nicht stören)."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._counters: dict[tuple[str, tuple[tuple[str, str], ...]], float] = {}
        self._hist: dict[tuple[str, tuple[tuple[str, str], ...]], list[float]] = {}
        self._gauges: dict[tuple[str, tuple[tuple[str, str], ...]], float] = {}
        self.violations: list[str] = []
        self.dropped = 0

    def _key(
        self, name: str, labels: Mapping[str, str]
    ) -> tuple[str, tuple[tuple[str, str], ...]] | None:
        v = label_violation(name, labels)
        if v:
            self.dropped += 1
            if len(self.violations) < 200:
                self.violations.append(f"{name}: {v}")
            return None
        return name, tuple(sorted(labels.items()))

    def inc(self, name: str, labels: Mapping[str, str] | None = None, value: float = 1.0) -> None:
        try:
            with self._lock:
                k = self._key(name, labels or {})
                if k:
                    self._counters[k] = self._counters.get(k, 0.0) + value
        except Exception:  # noqa: BLE001 - Telemetrieausfall darf nichts stören
            self.dropped += 1

    def set_gauge(self, name: str, value: float, labels: Mapping[str, str] | None = None) -> None:
        try:
            with self._lock:
                k = self._key(name, labels or {})
                if k:
                    self._gauges[k] = value
        except Exception:  # noqa: BLE001
            self.dropped += 1

    def observe(self, name: str, seconds: float, labels: Mapping[str, str] | None = None) -> None:
        try:
            with self._lock:
                k = self._key(name, labels or {})
                if k:
                    h = self._hist.setdefault(
                        k, [0.0] * (len(_BUCKETS) + 2)
                    )  # Buckets, Summe, Anzahl
                    for i, b in enumerate(_BUCKETS):
                        if seconds <= b:
                            h[i] += 1
                    h[-2] += seconds
                    h[-1] += 1
        except Exception:  # noqa: BLE001
            self.dropped += 1

    @staticmethod
    def _fmt(labels: tuple[tuple[str, str], ...], extra: str = "") -> str:
        items = [f'{k}="{v}"' for k, v in labels] + ([extra] if extra else [])
        return "{" + ",".join(items) + "}" if items else ""

    def render(self) -> str:
        """Prometheus-Textformat."""
        lines: list[str] = []
        with self._lock:
            for (n, lb), v in sorted(self._counters.items()):
                lines.append(f"{n}{self._fmt(lb)} {v}")
            for (n, lb), v in sorted(self._gauges.items()):
                lines.append(f"{n}{self._fmt(lb)} {v}")
            for (n, lb), h in sorted(self._hist.items()):
                for i, b in enumerate(_BUCKETS):
                    lines.append(f"{n}_bucket{self._fmt(lb, f'le="{b}"')} {h[i]}")
                lines.append(f"{n}_bucket{self._fmt(lb, 'le="+Inf"')} {h[-1]}")
                lines.append(f"{n}_sum{self._fmt(lb)} {h[-2]}")
                lines.append(f"{n}_count{self._fmt(lb)} {h[-1]}")
            lines.append(f"taktovia_telemetry_dropped_total {float(self.dropped)}")
        return "\n".join(lines) + "\n"


# --------------------------------------------------------------------------- Spans und Logs


@dataclass
class Span:
    name: str
    ctx: TraceContext
    start_ns: int
    attributes: dict[str, str] = field(default_factory=dict)
    error: str | None = None

    def set(self, key: str, value: object) -> None:
        self.attributes[key] = trace_attribute(key, value)


class Telemetry:
    """Je Worker eine Instanz: Dienstname, Metrikregister, Span-Export (OTLP/HTTP-JSON, best effort) und Logger."""

    def __init__(
        self, service: str, otlp_endpoint: str | None = None, timeout_s: float = 0.5
    ) -> None:
        self.service = service
        self.metrics = Registry()
        self.endpoint = otlp_endpoint
        self.timeout_s = timeout_s
        self.export_failures = 0
        self.log = logging.getLogger(f"taktovia.{service}")

    @contextmanager
    def span(
        self,
        name: str,
        traceparent: str | None = None,
        attributes: Mapping[str, object] | None = None,
    ) -> Iterator[Span]:
        ctx = continue_trace(traceparent)
        sp = Span(name, ctx, time.time_ns())
        for k, v in (attributes or {}).items():
            sp.set(k, v)
        try:
            yield sp
        except BaseException as e:
            sp.error = type(e).__name__
            raise
        finally:
            self._export(sp)

    def _export(self, sp: Span) -> None:
        if not self.endpoint:
            return
        try:
            attrs = [{"key": k, "value": {"stringValue": v}} for k, v in sp.attributes.items()]
            span = {
                "traceId": sp.ctx.trace_id,
                "spanId": sp.ctx.span_id,
                "name": sp.name,
                "kind": 2,
                "startTimeUnixNano": str(sp.start_ns),
                "endTimeUnixNano": str(time.time_ns()),
                "attributes": attrs,
                "status": {"code": 2 if sp.error else 1},
            }
            if sp.ctx.parent_span_id:
                span["parentSpanId"] = sp.ctx.parent_span_id
            body = {
                "resourceSpans": [
                    {
                        "resource": {
                            "attributes": [
                                {"key": "service.name", "value": {"stringValue": self.service}}
                            ]
                        },
                        "scopeSpans": [{"scope": {"name": "taktovia.diagnose"}, "spans": [span]}],
                    }
                ]
            }
            req = urllib.request.Request(
                self.endpoint,
                json.dumps(body).encode(),
                {"Content-Type": "application/json"},
                method="POST",
            )
            urllib.request.urlopen(req, timeout=self.timeout_s).close()  # noqa: S310 - Endpunkt aus der Betriebskonfiguration
        except Exception:  # noqa: BLE001 - ausgefallener Sammler: Verarbeitung läuft weiter
            self.export_failures += 1


class JsonFormatter(logging.Formatter):
    """Strukturierte Logzeile (JSON) mit maskierter Nachricht; zusätzliche Felder (``extra=``) nur aus der Positivliste."""

    def __init__(self, component: str) -> None:
        super().__init__()
        self.component = component

    def format(self, record: logging.LogRecord) -> str:
        out: dict[str, object] = {
            "ts": time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(record.created))
            + f".{int(record.msecs):03d}Z",
            "level": record.levelname,
            "component": self.component,
            "logger": record.name,
            "msg": mask_text(record.getMessage()),
        }
        dropped = []
        base = set(logging.LogRecord("", 0, "", 0, "", None, None).__dict__) | {
            "message",
            "asctime",
            "taskName",
        }
        for k, v in record.__dict__.items():
            if k in base:
                continue
            if k in LOG_FIELDS:
                out[k] = v if isinstance(v, (int, float, bool)) else mask_text(str(v))[:256]
            else:
                dropped.append("".join(c for c in k if c.isalnum() or c in "_.-")[:64])
        if dropped:
            out["dropped_fields"] = dropped
        if record.exc_info and record.exc_info[1] is not None:
            out["error"] = describe_exception(record.exc_info[1])
        return json.dumps(out, ensure_ascii=False)
