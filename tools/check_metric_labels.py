#!/usr/bin/env python3
"""Prüfskript "Metriklabels" (WP-M5-03 Akzeptanzkriterium 3): findet in einer Prometheus-Textausgabe (Datei, `-` für stdin oder URL) Labels mit Kontaktdaten,
Namen, Suchbegriffen oder Zahlungsdetails sowie Labelschlüssel außerhalb der Positivliste der Konvention (docs/betrieb/telemetrie.md).

Aufruf: python tools/check_metric_labels.py <datei|-|http://…/metrics> [--json]
Exit-Code 0 ohne Befund, 1 mit Befund, 2 bei Aufruffehler. Ausgabe nennt Metrik, Labelschlüssel und Regel, nie den Wert.
"""
import json
import re
import sys
import urllib.request

ALLOWED = {"component", "route_class", "method", "status_class", "result", "kind", "queue", "outcome", "error_class", "tenant_id", "worker",
           "event_type", "consumer", "state", "operation", "reason", "phase", "le", "quantile", "job", "instance"}
# Fremdmetriken (JVM, Pool, Exporter, Spring) tragen eigene, technische Labels; geprüft werden dort nur Schlüssel und Werte auf Personenbezug.
OWN = re.compile(r"^taktovia_")
BLOCKED_KEY = re.compile(r"(?i)(?<![a-z])(pass(word|wd)?|secret|token|authorization|api[_-]?key|credential|cookie|signature|private[_-]?key|otp|pin|cvc|cvv|bearer|session|"
                         r"iban|card|pan|e-?mail|phone|mobil|tel(efon)?|first[_-]?name|last[_-]?name|full[_-]?name|vorname|nachname|address|adresse|street|strasse|"
                         r"postal|birth|geburt|boarding|body|query|search|passenger|fahrgast)s?(?![a-z])")
EMAIL = re.compile(r"[A-Za-z0-9._%+'-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}")
IBAN = re.compile(r"\b[A-Z]{2}\d{2}(?:\s?[A-Z0-9]{4}){2,7}(?:\s?[A-Z0-9]{1,3})?\b")
PHONE = re.compile(r"(?<![\w.-])(?:\+|00|0)\d{1,4}[\s/().-]*\d(?:[\s/().-]?\d){5,13}(?![\w-])")
TOKEN = re.compile(r"eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}|(?i:bearer)\s+\S{8,}")
STREET = re.compile(r"\b[A-ZÄÖÜ][\wäöüß.-]*(?:stra(?:ß|ss)e|str\.|weg|allee|platz|gasse|ring|damm|ufer)\s+\d{1,4}")
UUID = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
LINE = re.compile(r'^([A-Za-z_:][A-Za-z0-9_:]*)\{(.*)\}\s+\S+(?:\s+\d+)?\s*$')
PAIR = re.compile(r'([A-Za-z_][A-Za-z0-9_]*)="((?:[^"\\]|\\.)*)"')

# Präfixe gängiger Kartennetze (Visa, Mastercard, Amex, Diners, Discover, JCB, UnionPay, Maestro): hält Zeitstempel und Messwerte mit Prüfsummentreffer heraus.
NETWORKS = ("34", "37", "4", "51", "52", "53", "54", "55", "36", "300", "301", "302", "303", "304", "305", "35", "6011", "65", "62", "50", "56", "57", "58", "63", "67")


def luhn_card(text):
    for m in re.finditer(r"\d(?:[ .-]?\d){12,}", UUID.sub("x", text)):
        d = re.sub(r"\D", "", m.group())
        for ln in range(13, min(19, len(d)) + 1):
            for st in range(len(d) - ln + 1):
                part = d[st:st + ln]
                s, alt = 0, False
                for c in reversed(part):
                    n = int(c) * 2 if alt else int(c)
                    s += n - 9 if n > 9 else n
                    alt = not alt
                if s % 10 == 0 and part.startswith(NETWORKS):
                    return True
    return False


def value_rule(v):
    t = UUID.sub("x", v)
    if EMAIL.search(t): return "E-Mail im Labelwert"
    if IBAN.search(t): return "IBAN im Labelwert"
    if TOKEN.search(t): return "Token im Labelwert"
    if luhn_card(t): return "Kartennummer im Labelwert"
    if PHONE.search(t): return "Telefonnummer im Labelwert"
    if STREET.search(t): return "Adresse im Labelwert"
    return None


def scan(text):
    findings = []
    series = 0
    for n, line in enumerate(text.splitlines(), 1):
        if not line or line.startswith("#"):
            continue
        m = LINE.match(line.strip())
        if not m:
            continue
        series += 1
        name, labels = m.group(1), m.group(2)
        for k, v in PAIR.findall(labels):
            if BLOCKED_KEY.search(k):
                findings.append({"line": n, "metric": name, "label": k, "rule": "verbotener Labelschlüssel"})
            elif OWN.match(name) and k not in ALLOWED:
                findings.append({"line": n, "metric": name, "label": k, "rule": "Labelschlüssel nicht in der Positivliste"})
            r = value_rule(v)
            if r:
                findings.append({"line": n, "metric": name, "label": k, "rule": r})
    return series, findings


def main(argv):
    args = [a for a in argv if not a.startswith("--")]
    if len(args) != 1:
        print(__doc__, file=sys.stderr)
        return 2
    src = args[0]
    try:
        if src == "-":
            text = sys.stdin.read()
        elif src.startswith(("http://", "https://")):
            text = urllib.request.urlopen(src, timeout=10).read().decode()  # noqa: S310 - Betriebsprüfung gegen die konfigurierte Scrape-Adresse
        else:
            text = open(src, encoding="utf-8").read()
    except OSError as e:
        print(f"Quelle nicht lesbar: {type(e).__name__}", file=sys.stderr)
        return 2
    series, findings = scan(text)
    if "--json" in argv:
        print(json.dumps({"series": series, "findings": findings}, indent=1, ensure_ascii=False))
    else:
        for f in findings:
            print(f"BEFUND Zeile {f['line']}: {f['metric']} Label {f['label']}: {f['rule']}")
        print(f"{series} Reihen geprüft, {len(findings)} Befunde")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
