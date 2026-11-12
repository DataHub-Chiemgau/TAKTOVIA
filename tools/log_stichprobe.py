#!/usr/bin/env python3
"""Prüfskript "Log-Stichprobe" (WP-M5-06, LH-6.19-NFR-015): Mustersuche nach Kartennummern, IBAN, E-Mail, Telefonnummer, Adresse, Token und Boarding-Code in den
Logs eines vollständigen Testlaufs (CI-Schritt) oder in der zentralen Logablage (Betriebsprüfung, `--loki`). Die Mustererkennung ist unabhängig von den
Maskierungsimplementierungen (kein gemeinsamer Code), damit eine fehlerhafte Maskierung nicht sich selbst bestätigt.

Aufruf:
  python tools/log_stichprobe.py --source fachkern=build/diagnose/fachkern.log --source routing-worker=… [--require fachkern,routing-worker,…] [--out bericht.json]
  python tools/log_stichprobe.py --loki http://loki:3100 --query '{service_name=~".+"}' --since-minutes 60 [--component-label service_name]

Eine Quelle ist `<komponente>=<datei|verzeichnis>`. Bericht (JSON, stdout und `--out`): Umfang (Zeilen je Quelle), Zeitraum (frühester und spätester erkannter Zeitstempel),
Muster, Trefferliste (Komponente, Quelle, Zeile, Klasse, Hash-Präfix; **nie der Klartext**). Exit-Code 0 ohne Treffer, 1 bei Treffer oder fehlender/leerer Pflichtkomponente
(`--require`), 2 bei Aufruffehler. Maskierte Ersatzdarstellungen (`[maskiert:…]`, `[card-data-removed]`), UUIDs und Trace-IDs sind kein Treffer.
"""
import datetime
import hashlib
import json
import re
import sys
import urllib.parse
import urllib.request
from pathlib import Path

PLACEHOLDER = re.compile(r"\[(?:maskiert:[a-z-]+|card-data-removed)\]")
UUID = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
HEXID = re.compile(r"(?<![0-9A-Za-z])(?=[0-9a-f]*[a-f])(?:[0-9a-f]{32}|[0-9a-f]{16})(?![0-9A-Za-z])")
EMAIL = re.compile(r"[A-Za-z0-9._%+'-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}")
IBAN = re.compile(r"\b[A-Z]{2}\d{2}(?:\s?[A-Z0-9]{4}){2,7}(?:\s?[A-Z0-9]{1,3})?\b")
PHONE_INTL = re.compile(r"(?<![\w.-])(?:\+|00)\d{1,3}[\s/().-]*\d(?:[\s/().-]?\d){5,13}(?![\w-])")
PHONE_NAT = re.compile(r"(?<![\w.+-])0\d{2,5}[\s/-]?\d(?:[\s/-]?\d){4,9}(?![\w-])")
STREET = re.compile(r"\b[A-ZÄÖÜ][\wäöüß.-]*(?:stra(?:ß|ss)e|str\.|weg|allee|platz|gasse|ring|damm|ufer|chaussee)\s+\d{1,4}")
POSTAL = re.compile(r"(?<![\d.-])\d{5}\s+[A-ZÄÖÜ][a-zäöüß]{2,}\b")
TOKEN = re.compile(r"eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}|(?i:\b(?:bearer|basic)\s+[A-Za-z0-9._~+/=-]{8,})")
SECRET_KV = re.compile(r"(?i)\b(?:pass(?:word|wd)?|passwort|secret|token|access[_-]?token|refresh[_-]?token|authorization|api[_-]?key|client[_-]?secret|cookie|otp|cvc|cvv)\b\s*[=:]\s*\"?[^\s,;&\"'}\]]{3,}")
BOARDING = re.compile(r"(?i)\bboarding[_ -]?code\b\s*[=:]?\s*\"?[A-Za-z0-9-]{4,}|\bBC-[A-Z0-9]{4,}\b")
TS = re.compile(r"(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})?)")
PATTERNS = ["karte (Luhn, 13-19 Ziffern)", "iban (Prüfsumme mod 97)", "email", "telefon", "adresse (Straße+Nr., PLZ+Ort)", "token (JWT, Bearer, key=value)", "boarding-code"]

# Präfixe gängiger Kartennetze (Visa, Mastercard, Amex, Diners, Discover, JCB, UnionPay, Maestro): hält Zeitstempel und Messwerte mit Prüfsummentreffer heraus.
NETWORKS = ("34", "37", "4", "51", "52", "53", "54", "55", "36", "300", "301", "302", "303", "304", "305", "35", "6011", "65", "62", "50", "56", "57", "58", "63", "67")


def luhn_hits(text):
    for m in re.finditer(r"\d(?:[ .-]?\d){12,}", text):
        d = re.sub(r"\D", "", m.group())
        for ln in range(13, min(19, len(d)) + 1):
            for st in range(len(d) - ln + 1):
                s, alt = 0, False
                for c in reversed(d[st:st + ln]):
                    n = int(c) * 2 if alt else int(c)
                    s += n - 9 if n > 9 else n
                    alt = not alt
                if s % 10 == 0 and d[st:st + ln].startswith(NETWORKS):
                    return d[st:st + ln]
    return None


def iban_valid(raw):
    s = re.sub(r"\s", "", raw)
    if not 15 <= len(s) <= 34:
        return False
    r = s[4:] + s[:4]
    return int("".join(str(int(c, 36)) for c in r)) % 97 == 1


def classify(line):
    """[(klasse, treffertext)] – die Maskenersatzdarstellungen, UUIDs und Trace-IDs werden vorher ausgeblendet."""
    t = PLACEHOLDER.sub("§", line)   # kein Leerzeichen: sonst würde das Folgewort als Wert eines `key=`-Musters gelten
    t = UUID.sub(" ", t)
    t = HEXID.sub(" ", t)
    t = TS.sub(" ", t)
    hits = []
    if (c := luhn_hits(IBAN.sub(" ", t))) is not None:
        hits.append(("karte", c))
    for m in IBAN.finditer(t):
        if iban_valid(m.group()):
            hits.append(("iban", m.group()))
    for m in EMAIL.finditer(t):
        hits.append(("email", m.group()))
    for rx in (PHONE_INTL, PHONE_NAT):
        for m in rx.finditer(t):
            hits.append(("telefon", m.group()))
    for rx in (STREET, POSTAL):
        for m in rx.finditer(t):
            hits.append(("adresse", m.group()))
    for rx in (TOKEN, SECRET_KV):
        for m in rx.finditer(t):
            hits.append(("token", m.group()))
    for m in BOARDING.finditer(t):
        hits.append(("boarding-code", m.group()))
    return hits


def read_source(path):
    p = Path(path)
    files = sorted(p.rglob("*.log")) + sorted(p.rglob("*.jsonl")) if p.is_dir() else [p]
    for f in files:
        with open(f, encoding="utf-8", errors="replace") as fh:
            for n, line in enumerate(fh, 1):
                yield str(f), n, line.rstrip("\n")


def parse_ts(line):
    m = TS.search(line)
    if not m:
        return None
    s = m.group(1).replace("Z", "+00:00")
    try:
        d = datetime.datetime.fromisoformat(s)
    except ValueError:
        return None
    return d if d.tzinfo else d.replace(tzinfo=datetime.timezone.utc)


def sample(sources, require=()):
    """sources: {komponente: iterable[(quelle, zeile_nr, text)]} -> Bericht (dict)."""
    report = {"patterns": PATTERNS, "components": {}, "findings": []}
    first = last = None
    for comp, lines in sources.items():
        c = report["components"].setdefault(comp, {"lines": 0, "sources": []})
        for src, n, text in lines:
            c["lines"] += 1
            if src not in c["sources"]:
                c["sources"].append(src)
            ts = parse_ts(text)
            if ts:
                first = ts if first is None or ts < first else first
                last = ts if last is None or ts > last else last
            for klass, hit in classify(text):
                report["findings"].append({"component": comp, "source": src, "line": n, "class": klass,
                                           "hash": hashlib.sha256(hit.encode()).hexdigest()[:12]})
    report["total_lines"] = sum(c["lines"] for c in report["components"].values())
    report["period"] = {"from": first.isoformat() if first else None, "to": last.isoformat() if last else None}
    report["missing_components"] = sorted(c for c in require if report["components"].get(c, {}).get("lines", 0) == 0)
    report["result"] = "BEFUND" if report["findings"] or report["missing_components"] else "OHNE_BEFUND"
    return report


def loki_lines(base, query, since_minutes, component_label, limit=5000):
    """Liest Logzeilen über die Loki-HTTP-API (`/loki/api/v1/query_range`); {komponente: [(quelle, nr, text)]}."""
    end = datetime.datetime.now(datetime.timezone.utc)
    start = end - datetime.timedelta(minutes=since_minutes)
    q = urllib.parse.urlencode({"query": query, "start": int(start.timestamp() * 1e9), "end": int(end.timestamp() * 1e9), "limit": limit, "direction": "forward"})
    with urllib.request.urlopen(f"{base.rstrip('/')}/loki/api/v1/query_range?{q}", timeout=30) as r:  # noqa: S310 - Betriebsprüfung gegen die konfigurierte Ablage
        data = json.load(r)
    out = {}
    for stream in data["data"]["result"]:
        comp = stream["stream"].get(component_label, "unbekannt")
        for i, (ts_ns, line) in enumerate(stream["values"], 1):
            ts = datetime.datetime.fromtimestamp(int(ts_ns) / 1e9, datetime.timezone.utc).isoformat()
            out.setdefault(comp, []).append((f"loki:{comp}", i, f"{ts} {line}"))
    return out


def main(argv):
    srcs, require, out, loki, query, since, label = [], [], None, None, '{service_name=~".+"}', 60, "service_name"
    it = iter(argv)
    try:
        for a in it:
            if a == "--source": srcs.append(next(it))
            elif a == "--require": require = [x for x in next(it).split(",") if x]
            elif a == "--out": out = next(it)
            elif a == "--loki": loki = next(it)
            elif a == "--query": query = next(it)
            elif a == "--since-minutes": since = int(next(it))
            elif a == "--component-label": label = next(it)
            else:
                print(__doc__, file=sys.stderr)
                return 2
    except (StopIteration, ValueError):
        print(__doc__, file=sys.stderr)
        return 2
    sources = {}
    try:
        for s in srcs:
            comp, _, path = s.partition("=")
            if not path:
                print(f"Quelle ohne Komponente: {s}", file=sys.stderr)
                return 2
            sources.setdefault(comp, [])
            sources[comp] = list(sources[comp]) + list(read_source(path))
        if loki:
            for comp, lines in loki_lines(loki, query, since, label).items():
                sources[comp] = list(sources.get(comp, [])) + lines
    except OSError as e:
        print(f"Quelle nicht lesbar: {type(e).__name__}", file=sys.stderr)
        return 2
    if not sources:
        print(__doc__, file=sys.stderr)
        return 2
    report = sample(sources, require)
    text = json.dumps(report, indent=1, ensure_ascii=False)
    if out:
        Path(out).parent.mkdir(parents=True, exist_ok=True)
        Path(out).write_text(text + "\n", encoding="utf-8")
    print(text)
    return 0 if report["result"] == "OHNE_BEFUND" else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
