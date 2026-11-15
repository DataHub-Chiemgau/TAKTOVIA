#!/usr/bin/env python3
"""Prüfung des Dokumentationspakets (WP-M5-27, LH-6.20-QAT-006).

Prüft `docs/dokumentation/` (Administratorhandbuch, Fahrerschulung, Datenschutzdokumentation, Standardbetrieb, Übergabeprotokoll) und das Betriebshandbuch
(`docs/betrieb/betriebshandbuch.md`) gegen den Stand des Repositorys:

  1. Versionierung: jedes Dokument des Pakets (`paket.json`) trägt `dokument_version`, `abnahme_build`, `sprache: de`, `stand`; das Übergabeprotokoll führt jedes Dokument
     mit derselben Version; der Bezugs-Commit des Builds existiert im Repository.
  2. Abgleich gegen den Build: jede Oberflächen- und Schnittstellenreferenz `**Beschriftung**{{route|nav|app|src:...}}` und `{{api:operationId}}` muss im Quelltext des
     Builds existieren (Route in `App.tsx`, Navigationsbeschriftung in `navigation.ts`, Textschlüssel und Wortlaut in `strings.dart`, Wortlaut in der Quelldatei,
     operationId in `api/*.yaml`); relative Links und Überschriften-Anker müssen auflösen.
  3. Keine echten Personendaten (E-Mail, Telefon, IBAN, Kartennummer außerhalb ausdrücklich synthetischer Domänen/Muster) und keine offenen Platzhalter (TODO, TBD, XXX, `<…>`,
     leere Tabellenzellen); Fristzeilen der Datenschutzdokumentation tragen „durch Auftraggeber festzulegen“.
  4. Zugänglichkeit: genau eine Überschrift der Ebene 1, keine übersprungenen Ebenen, Bilder mit Alternativtext, keine Links ohne Text, Tabellen mit Kopfzeile.
  5. Fahrerschulung: Pflichtprüfung mit kritischem Mangel, Fahrzeugsperre, Offline, App-Ausfall; Inhaltsabgleich gegen die Notbetriebskapitel des Betriebshandbuchs.
  6. Standardbetrieb: alle sechs Abläufe des Vertrags mit Handbuchkapitel, Oberfläche/Schnittstelle und Ersatznachweis; Prüfung eines Demonstrationsprotokolls.

Exit-Code 0 ohne Befund, 1 bei Befund (JSON auf stdout), 2 bei Aufruffehler."""
import argparse
import json
import re
import subprocess
import sys
import unicodedata
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
DOK = REPO / "docs" / "dokumentation"
PAKET = DOK / "paket.json"

STANDARDBETRIEB = (
    "Benutzer und Fahrzeug anlegen",
    "Dienst und Tarif prüfen",
    "Fahrer startet Dienst mit Pflichtprüfung",
    "Buchung wird disponiert und gefahren",
    "Leitstelle überwacht",
    "Alarm wird nach Runbook behandelt",
)
SCHULUNG_THEMEN = {
    "Pflichtprüfung mit kritischem Mangel": r"Pflichtprüfung mit kritischem Mangel",
    "Fahrzeugsperre": r"Fahrzeugsperre",
    "Offline": r"Offline",
    "Ausfall der App": r"Ausfall der App",
    "Notbetrieb": r"Notbetrieb",
}
SCHULUNG_ABLAEUFE = ("Dienststart", "Pflichtprüfung", "Stoppliste", "Check-in", "Ausstieg", "No-Show", "Schadenmeldung", "Offlineverhalten", "Beleg")
ADMIN_THEMEN = ("Mandant und Organisation", "Benutzer und Rollen", "Dienste", "Gebiete", "Haltepunkte", "Tarife", "Fahrzeuge", "Checklistenvorlagen", "Konfigurationsrevisionen",
                "Vier-Augen-Freigabe", "Rollback")
DATENSCHUTZ_THEMEN = ("Dateninventar", "Löschregeln", "Datenschutz-Folgenabschätzung", "Rollen- und Feldzugriffskonzept", "Standort- und Medienminimierung", "Protokollierung", "Maskierung")
FRIST_VERMERK = "durch Auftraggeber festzulegen"
PROTOKOLL_ERGEBNISSE = ("bestanden", "bestanden mit Abweichung", "nicht bestanden")

REF = re.compile(r"\*\*([^*\n]+)\*\*\{\{(route|nav|app|src):([^}\n]+)\}\}")
REF_ALLE = re.compile(r"\{\{(\w+):([^}\n]*)\}\}")
API_REF = re.compile(r"\{\{api:(\w+)\}\}")
PLATZHALTER = re.compile(r"(?i)\b(TODO|TBD|FIXME|XXX|lorem ipsum|platzhalter folgt|noch zu ergänzen)\b|<[A-Za-zÄÖÜäöü_ -]{2,}>|\[\.\.\.\]|\.\.\.\.|…\s*$")
SYNTHETISCH_DOMAENEN = ("example.org", "example.com", "example.net", "example.test", "schulung.invalid", "taktovia.invalid")
EMAIL = re.compile(r"[\w.+-]+@([\w-]+(?:\.[\w-]+)+)")
TELEFON = re.compile(r"(?<![\w/.-])(?:\+49|0049|\(?0\d{2,5}\)?)[ /-]?\d[\d ./-]{5,}\d")
IBAN = re.compile(r"\b[A-Z]{2}\d{2}(?: ?[A-Z0-9]{4}){3,7}(?: ?[A-Z0-9]{1,3})?\b")
KARTE = re.compile(r"\b(?:\d[ -]?){13,19}\b")


def norm(s):
    """Vergleichsform: Kleinschreibung, Umlaute transliteriert (die Fahrer-App führt ihre Texte in ASCII), Leerraum vereinheitlicht."""
    s = s.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("Ä", "Ae").replace("Ö", "Oe").replace("Ü", "Ue").replace("ß", "ss").replace("·", " ")
    s = unicodedata.normalize("NFKD", s).encode("ascii", "ignore").decode()
    return re.sub(r"\s+", " ", s).strip().lower()


def frontmatter(text):
    m = re.match(r"---\n(.*?)\n---\n", text, re.S)
    if not m:
        return {}
    out = {}
    for zeile in m.group(1).splitlines():
        if ":" in zeile:
            k, v = zeile.split(":", 1)
            out[k.strip()] = v.strip()
    return out


def ohne_frontmatter(text):
    return re.sub(r"\A---\n.*?\n---\n", "", text, count=1, flags=re.S)


def slug(titel):
    """GitHub-Anker einer Überschrift."""
    s = titel.strip().lower()
    s = re.sub(r"[`*_]", "", s)
    s = re.sub(r"[^\w\- äöüß]", "", s, flags=re.U)
    return s.replace(" ", "-")


def ueberschriften(text):
    out = []
    im_code = False
    for zeile in ohne_frontmatter(text).splitlines():
        if zeile.startswith("```"):
            im_code = not im_code
            continue
        m = re.match(r"^(#{1,6}) +(.+?)\s*$", zeile)
        if m and not im_code:
            out.append((len(m.group(1)), m.group(2)))
    return out


def anker(text):
    a = {slug(t) for _, t in ueberschriften(text)}
    a.update(re.findall(r'<a id="([^"]+)"></a>', text))
    return a


def _lies(pfad):
    return pfad.read_text(encoding="utf-8")


class Build:
    """Quelltextstand der Oberflächen und Schnittstellen, gegen den die Dokumente geprüft werden."""

    def __init__(self, repo=REPO):
        self.repo = repo
        self.app = _lies(repo / "web/operator/src/App.tsx")
        self.nav = _lies(repo / "web/operator/src/navigation/navigation.ts")
        self.strings = _lies(repo / "mobile/driver/lib/ui/strings.dart")
        ops = set()
        for y in sorted((repo / "api").glob("*.yaml")):
            ops.update(re.findall(r"operationId:\s*(\w+)", _lies(y)))
        self.operationen = ops
        self._dateien = {}

    def datei(self, rel):
        if rel not in self._dateien:
            p = self.repo / rel
            self._dateien[rel] = _lies(p) if p.is_file() else None
        return self._dateien[rel]

    def route(self, pfad):
        return f'path="{pfad}"' in self.app

    def nav_label(self, pfad, label):
        for m in re.finditer(r"label:\s*'([^']+)',\s*href:\s*'([^']+)'|label:\s*'([^']+)',\s*\n\s*href:\s*'([^']+)'", self.nav):
            l, h = (m.group(1), m.group(2)) if m.group(1) else (m.group(3), m.group(4))
            if h == pfad and norm(l) == norm(label):
                return True
        return False

    def app_text(self, key, label):
        m = re.search(r"'" + re.escape(key) + r"':\s*'((?:[^'\\]|\\.)*)'", self.strings)
        return m is not None and norm(m.group(1).replace("\\'", "'")) == norm(label)

    def quelle_enthaelt(self, rel, label):
        t = self.datei(rel)
        return t is not None and norm(label) in norm(t)


def referenzen_pruefen(text, name, build):
    befunde = []
    for label, art, ziel in REF.findall(text):
        ziel = ziel.strip()
        if art == "nav":
            if not build.route(ziel):
                befunde.append(f"{name}: Oberfläche '{label}': Route {ziel} fehlt im Operator-Web (App.tsx)")
            elif not build.nav_label(ziel, label):
                befunde.append(f"{name}: Navigationseintrag '{label}' mit Ziel {ziel} fehlt in navigation.ts")
        elif art == "app" and not build.app_text(ziel, label):
            befunde.append(f"{name}: Fahrer-App: Text '{label}' unter Schlüssel '{ziel}' fehlt in strings.dart")
        elif art == "src" and not build.quelle_enthaelt(ziel, label):
            befunde.append(f"{name}: Text '{label}' kommt in {ziel} nicht vor (Datei oder Beschriftung fehlt im Build)")
    for art, ziel in REF_ALLE.findall(text):
        if art not in ("route", "nav", "app", "src", "api"):
            befunde.append(f"{name}: unbekannte Referenzart {{{{{art}:{ziel}}}}}")
    # Routen dürfen ohne Beschriftung stehen (reine Pfadangabe), müssen aber existieren; Navigations-, App- und Quellreferenzen brauchen die Beschriftung davor
    for ziel in re.findall(r"\{\{route:([^}\n]+)\}\}", text):
        if not build.route(ziel.strip()):
            befunde.append(f"{name}: Route {ziel.strip()} fehlt im Operator-Web (App.tsx)")
    ohne = len(re.findall(r"\{\{(?:nav|app|src):", text)) - len([1 for _, a, _ in REF.findall(text) if a != "route"])
    if ohne:
        befunde.append(f"{name}: {ohne} Oberflächenreferenz(en) ohne fett gesetzte Beschriftung unmittelbar davor")
    for op in API_REF.findall(text):
        if op not in build.operationen:
            befunde.append(f"{name}: Schnittstelle {op} (operationId) fehlt in api/*.yaml")
    return befunde


def links_pruefen(text, pfad, repo=REPO):
    befunde = []
    name = str(pfad.relative_to(repo)) if pfad.is_absolute() else str(pfad)
    for _, ziel in re.findall(r"(?<!!)\[([^\]]*)\]\(([^)\s]+)\)", text):
        if re.match(r"^[a-z]+:", ziel):
            continue
        datei, _, frag = ziel.partition("#")
        zielpfad = (pfad.parent / datei).resolve() if datei else pfad
        if not zielpfad.exists():
            befunde.append(f"{name}: Link auf fehlende Datei {ziel}")
        elif frag and zielpfad.suffix == ".md" and frag not in anker(_lies(zielpfad)):
            befunde.append(f"{name}: Link auf fehlenden Anker {ziel}")
    return befunde


def personendaten_und_platzhalter(text, name):
    befunde = []
    inhalt = ohne_frontmatter(text)
    for n, zeile in enumerate(inhalt.splitlines(), 1):
        for m in EMAIL.finditer(zeile):
            if not m.group(1).lower().endswith(SYNTHETISCH_DOMAENEN):
                befunde.append(f"{name}:{n}: echte E-Mail-Adresse? '{m.group(0)}' (nur synthetische Domänen {', '.join(SYNTHETISCH_DOMAENEN)} zulässig)")
        if TELEFON.search(zeile) and "555" not in zeile:
            befunde.append(f"{name}:{n}: Telefonnummer im Text (Beispiele nur mit 555-Nummern)")
        if IBAN.search(zeile):
            befunde.append(f"{name}:{n}: IBAN im Text")
        for m in KARTE.finditer(zeile):
            ziffern = re.sub(r"\D", "", m.group(0))
            if len(ziffern) >= 13 and _luhn(ziffern):
                befunde.append(f"{name}:{n}: Kartennummer im Text")
        if PLATZHALTER.search(zeile):
            befunde.append(f"{name}:{n}: offener Platzhalter '{PLATZHALTER.search(zeile).group(0)}'")
        if re.match(r"^\|(.*\|)+\s*$", zeile) and re.search(r"\|\s*\|", zeile) and not re.match(r"^\|[\s:|-]+\|$", zeile):
            befunde.append(f"{name}:{n}: leere Tabellenzelle (offener Platzhalter)")
    return befunde


def _luhn(ziffern):
    s = 0
    for i, c in enumerate(reversed(ziffern)):
        d = int(c)
        if i % 2:
            d *= 2
            if d > 9:
                d -= 9
        s += d
    return s % 10 == 0


def zugaenglichkeit(text, name):
    befunde = []
    h = ueberschriften(text)
    if sum(1 for e, _ in h if e == 1) != 1:
        befunde.append(f"{name}: genau eine Überschrift der Ebene 1 erforderlich")
    letzte = 0
    for ebene, titel in h:
        if letzte and ebene > letzte + 1:
            befunde.append(f"{name}: Überschriftenebene übersprungen vor '{titel}' (H{letzte} -> H{ebene})")
        letzte = ebene
    for alt, ziel in re.findall(r"!\[([^\]]*)\]\(([^)]*)\)", text):
        if len(alt.strip()) < 10:
            befunde.append(f"{name}: Bild {ziel} ohne aussagekräftigen Alternativtext")
    for ziel in re.findall(r"(?<!!)\[\s*\]\(([^)]*)\)", text):
        befunde.append(f"{name}: Link ohne Text auf {ziel}")
    zeilen = ohne_frontmatter(text).splitlines()
    for i, z in enumerate(zeilen):
        if re.match(r"^\|.*\|\s*$", z) and (i == 0 or not re.match(r"^\|.*\|\s*$", zeilen[i - 1])):
            if i + 1 >= len(zeilen) or not re.match(r"^\|[\s:|-]+\|\s*$", zeilen[i + 1]):
                befunde.append(f"{name}:{i + 1}: Tabelle ohne Kopfzeile")
    return befunde


def paket():
    return json.loads(PAKET.read_text(encoding="utf-8"))


def versionierung(repo=REPO):
    befunde = []
    p = json.loads((repo / "docs/dokumentation/paket.json").read_text(encoding="utf-8"))
    uebergabe = _lies(repo / "docs/dokumentation/uebergabeprotokoll.md")
    ids = [d["id"] for d in p["dokumente"]]
    if len(set(ids)) != len(ids) or len(ids) < 4:
        befunde.append("paket.json: mindestens vier Dokumente mit eindeutiger Kennung erforderlich (Betriebs-, Administratorhandbuch, Fahrerschulung, Datenschutzdokumentation)")
    for d in p["dokumente"]:
        pfad = repo / d["pfad"]
        if not pfad.is_file():
            befunde.append(f"{d['id']}: Datei {d['pfad']} fehlt")
            continue
        fm = frontmatter(_lies(pfad))
        for feld in ("dokument_version", "abnahme_build", "sprache", "stand"):
            if not fm.get(feld):
                befunde.append(f"{d['pfad']}: Kopfzeile '{feld}' fehlt")
        if fm.get("sprache") != "de":
            befunde.append(f"{d['pfad']}: Sprache muss 'de' sein")
        if fm.get("dokument_version") and not re.search(rf"\|\s*{re.escape(d['pfad'].split('/')[-1])}\s*\|[^\n]*\|\s*{re.escape(fm['dokument_version'])}\s*\|", uebergabe) \
                and not re.search(rf"{re.escape(d['pfad'])}[^\n]*\|\s*{re.escape(fm['dokument_version'])}\s*\|", uebergabe):
            befunde.append(f"{d['pfad']}: Version {fm['dokument_version']} steht nicht im Übergabeprotokoll")
        if fm.get("abnahme_build") and fm["abnahme_build"] != p["abnahme_build"]:
            befunde.append(f"{d['pfad']}: abnahme_build '{fm['abnahme_build']}' weicht von paket.json '{p['abnahme_build']}' ab (kein Mischen von Builds)")
    commit = p.get("bezugs_commit", "")
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        befunde.append("paket.json: bezugs_commit ist kein vollständiger Commit-Hash")
    else:
        r = subprocess.run(["git", "-C", str(repo), "cat-file", "-e", commit + "^{commit}"], capture_output=True)
        if r.returncode != 0:
            befunde.append(f"paket.json: Bezugs-Commit {commit} existiert nicht im Repository")
        if commit not in uebergabe:
            befunde.append("Übergabeprotokoll nennt den Bezugs-Commit des Abnahme-Builds nicht")
    return befunde


def abgleich_build(repo=REPO, build=None):
    """Referenzen, Links, Zugänglichkeit und Personendaten aller Dokumente des Pakets."""
    build = build or Build(repo)
    befunde = []
    p = json.loads((repo / "docs/dokumentation/paket.json").read_text(encoding="utf-8"))
    for d in p["dokumente"]:
        pfad = repo / d["pfad"]
        if not pfad.is_file():
            continue
        text = _lies(pfad)
        befunde += referenzen_pruefen(text, d["pfad"], build)
        befunde += links_pruefen(text, pfad, repo)
        befunde += zugaenglichkeit(text, d["pfad"])
    return befunde


def inhalt_pruefen(repo=REPO):
    """Personendaten, Platzhalter, Fristvermerke und Pflichtinhalte (ohne das Betriebshandbuch, das mit WP-M5-26 geprüft wird)."""
    befunde = []
    p = json.loads((repo / "docs/dokumentation/paket.json").read_text(encoding="utf-8"))
    for d in p["dokumente"]:
        pfad = repo / d["pfad"]
        if not pfad.is_file() or d.get("pruefung") == "betriebshandbuch":
            continue
        text = _lies(pfad)
        befunde += personendaten_und_platzhalter(text, d["pfad"])
        pflicht = {"administratorhandbuch": ADMIN_THEMEN, "datenschutzdokumentation": DATENSCHUTZ_THEMEN, "fahrerschulung": SCHULUNG_ABLAEUFE}.get(d["id"], ())
        for t in pflicht:
            if t.lower() not in text.lower():
                befunde.append(f"{d['pfad']}: Pflichtthema '{t}' fehlt")
        if d["id"] == "datenschutzdokumentation":
            befunde += fristvermerke(text, d["pfad"])
    return befunde


def fristvermerke(text, name):
    befunde = []
    for n, zeile in enumerate(ohne_frontmatter(text).splitlines(), 1):
        if re.search(r"(?i)(löschfrist|aufbewahrungsfrist|speicherfrist|aufbewahrungsdauer|löschzeitpunkt)", zeile) and FRIST_VERMERK not in zeile \
                and not re.search(r"(?i)(kein|ohne)\b.{0,40}frist", zeile) and "nicht festgelegt" not in zeile:
            befunde.append(f"{name}:{n}: Frist ohne Vermerk '{FRIST_VERMERK}' (D-11)")
    if text.count(FRIST_VERMERK) < 3:
        befunde.append(f"{name}: der Vermerk '{FRIST_VERMERK}' kommt zu selten vor")
    return befunde


def betriebshandbuch_anker(repo=REPO):
    return anker(_lies(repo / "docs/betrieb/betriebshandbuch.md"))


def schulung_pruefen(text, handbuch):
    """Inhaltsabgleich der Fahrerschulung: sicherheitsrelevante Themen und die Offline-/Serverpflicht-Tabelle des Betriebshandbuchs (Abschnitt 11)."""
    befunde = []
    for thema, muster in SCHULUNG_THEMEN.items():
        if not re.search(muster, text):
            befunde.append(f"Fahrerschulung: sicherheitsrelevantes Thema '{thema}' fehlt")
    m = re.search(r"\| Offline erlaubt \| Immer serverpflichtig \|\n\|[-| ]+\|\n(.*?)\n\n", handbuch, re.S)
    if not m:
        befunde.append("Betriebshandbuch: Tabelle 'Offline erlaubt | Immer serverpflichtig' (Abschnitt 11) nicht gefunden")
    else:
        for zeile in m.group(1).splitlines():
            zellen = [z.strip() for z in zeile.strip().strip("|").split("|")]
            for stichwort in (z for z in zellen if z):
                kern = re.sub(r"\(.*?\)", "", stichwort)
                kern = re.split(r"[,;]| und ", kern)[0].strip()
                woerter = [w for w in re.findall(r"[A-Za-zÄÖÜäöüß-]{5,}", kern)]
                if woerter and not any(w.lower() in text.lower() for w in woerter[:3]):
                    befunde.append(f"Fahrerschulung deckt den Notbetriebsinhalt '{kern}' (Betriebshandbuch Abschnitt 11) nicht ab")
    for ziel in ("112-ausfall-der-driver-app-verbindung",):
        if ziel not in text:
            befunde.append(f"Fahrerschulung verweist nicht auf das Notbetriebskapitel #{ziel} des Betriebshandbuchs")
    return befunde


def ablaeufe(text):
    """Zeilen der Standardbetrieb-Tabelle: {id, name, kapitel, oberflaeche, nachweis}."""
    out = []
    for zeile in text.splitlines():
        if re.match(r"^\| SB-\d+ \|", zeile):
            z = [c.strip() for c in zeile.strip().strip("|").split("|")]
            if len(z) >= 5:
                out.append({"id": z[0], "name": z[1], "kapitel": z[2], "oberflaeche": z[3], "nachweis": z[4]})
    return out


def standardbetrieb_pruefen(repo=REPO, build=None):
    """Trockenlauf der Demonstration: jeder Ablauf verweist auf Handbuchkapitel, Oberfläche/Schnittstelle und Ersatznachweis, die alle existieren."""
    build = build or Build(repo)
    befunde = []
    pfad = repo / "docs/dokumentation/standardbetrieb.md"
    text = _lies(pfad)
    a = ablaeufe(text)
    namen = [x["name"] for x in a]
    for soll in STANDARDBETRIEB:
        if soll not in namen:
            befunde.append(f"Standardbetrieb: Ablauf '{soll}' fehlt")
    for x in a:
        if not re.search(r"\]\([^)]+\)", x["kapitel"]):
            befunde.append(f"Standardbetrieb {x['id']}: kein Handbuchkapitel verlinkt")
        if not re.search(r"\{\{(route|nav|app|src|api):", x["oberflaeche"]):
            befunde.append(f"Standardbetrieb {x['id']}: keine Oberfläche oder Schnittstelle des Builds referenziert")
        for p in re.findall(r"`([^`]+\.(?:py|kt|md|tsx|dart))`", x["nachweis"]):
            if not (repo / p).exists():
                befunde.append(f"Standardbetrieb {x['id']}: Ersatznachweis {p} existiert nicht")
        if "nicht ausgeführt" not in x["nachweis"] and "`" not in x["nachweis"]:
            befunde.append(f"Standardbetrieb {x['id']}: Ersatznachweis fehlt")
    befunde += referenzen_pruefen(text, "docs/dokumentation/standardbetrieb.md", build)
    befunde += links_pruefen(text, pfad, repo)
    vorlage = repo / "docs/dokumentation/vorlagen/demonstrationsprotokoll.md"
    if not vorlage.is_file():
        befunde.append("Vorlage vorlagen/demonstrationsprotokoll.md fehlt")
    return befunde


def protokoll_pruefen(text, soll_ablaeufe):
    """Prüft ein ausgefülltes Demonstrationsprotokoll: Kopf (Abnehmer, Datum, Build), je Ablauf ein Ergebnis, Abweichungen beschrieben."""
    befunde = []
    for feld in ("Abnehmer", "Datum", "Abnahme-Build", "Unterstützung durch das Entwicklungsteam"):
        if not re.search(rf"(?m)^- \*\*{re.escape(feld)}:\*\* \S", text):
            befunde.append(f"Protokollkopf: Feld '{feld}' fehlt oder ist leer")
    zeilen = {}
    for z in text.splitlines():
        m = re.match(r"^\| (SB-\d+) \|.*\|\s*$", z)
        if m:
            zeilen[m.group(1)] = [c.strip() for c in z.strip()[1:-1].split("|")]
    for a in soll_ablaeufe:
        z = zeilen.get(a["id"])
        if z is None:
            befunde.append(f"Protokoll: Ablauf {a['id']} ({a['name']}) fehlt")
            continue
        if len(z) < 4:
            befunde.append(f"Protokoll {a['id']}: Spalten fehlen")
            continue
        if z[2] not in PROTOKOLL_ERGEBNISSE:
            befunde.append(f"Protokoll {a['id']}: Ergebnis '{z[2]}' ungültig (zulässig: {', '.join(PROTOKOLL_ERGEBNISSE)})")
        elif z[2] != "bestanden" and not z[3]:
            befunde.append(f"Protokoll {a['id']}: Abweichung ohne Beschreibung")
    return befunde


def alles(repo=REPO):
    build = Build(repo)
    b = versionierung(repo) + abgleich_build(repo, build) + inhalt_pruefen(repo) + standardbetrieb_pruefen(repo, build)
    d = {x["id"]: x for x in json.loads((repo / "docs/dokumentation/paket.json").read_text(encoding="utf-8"))["dokumente"]}
    b += schulung_pruefen(_lies(repo / d["fahrerschulung"]["pfad"]), _lies(repo / "docs/betrieb/betriebshandbuch.md"))
    return b


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--protokoll", help="ausgefülltes Demonstrationsprotokoll prüfen")
    a = ap.parse_args(argv)
    try:
        if a.protokoll:
            befunde = protokoll_pruefen(Path(a.protokoll).read_text(encoding="utf-8"), ablaeufe(_lies(DOK / "standardbetrieb.md")))
        else:
            befunde = alles()
    except (OSError, ValueError) as e:
        print(f"Aufruffehler: {e}", file=sys.stderr)
        return 2
    print(json.dumps({"ergebnis": "OHNE_BEFUND" if not befunde else "BEFUNDE", "befunde": befunde}, ensure_ascii=False, indent=2))
    return 1 if befunde else 0


if __name__ == "__main__":
    sys.exit(main())
