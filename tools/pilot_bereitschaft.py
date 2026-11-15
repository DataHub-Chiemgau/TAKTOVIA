#!/usr/bin/env python3
"""Pilotbereitschaft (WP-M5-29): Abbruchkriterien, Entscheidungsweg, Prozessverantwortliche, Generalprobe, Rückfallübung und Produktionseinspielung prüfen.

Enabler, keine Anforderung. Das Werkzeug ENTSCHEIDET NICHTS: es prüft Struktur und Messbarkeit der Kriterien, wertet Messwerte gegen Schwellen aus, löst den Entscheidungsweg aus und führt
das Entscheidungsjournal (nur anfügbar, Hash-Kette). Schwellen, Entscheider, Personen und Vertretungen legt der Auftraggeber bzw. der Betreiber fest; solange sie fehlen, meldet
`bereitschaft` ausdrücklich `NICHT_FREIGEGEBEN` und listet die offenen Punkte. Eine offene Schwelle gilt nie als eingehalten.

Aufruf:
  python3 tools/pilot_bereitschaft.py bereitschaft            # Prüfung der Dokumente; Exit 0 nur ohne Befund UND ohne offene Punkte (Pilot-Gate), sonst 1; Befunde allein: Exit 1
  python3 tools/pilot_bereitschaft.py bereitschaft --nur-struktur   # Exit 0, wenn nur offene Punkte (Freigaben) bleiben, 1 bei Strukturbefunden
  python3 tools/pilot_bereitschaft.py bewerten --messwerte m.json [--schwellen s.json] [--journal j.jsonl]
  python3 tools/pilot_bereitschaft.py entscheiden --journal j.jsonl --auslosung N --entscheider ID --rolle R --entscheidung E --begruendung T [--auflage T]"""
import argparse
import fcntl
import hashlib
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

import yaml

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / "tests" / "acceptance"))
PILOT = REPO / "docs" / "pilot"
VERGLEICHE = {">": lambda w, s: w > s, ">=": lambda w, s: w >= s, "<": lambda w, s: w < s, "<=": lambda w, s: w <= s}
PFLICHTFELDER = ("id", "titel", "messgroesse", "einheit", "vergleich", "schwelle", "schwelle_status", "messquelle", "mangelklasse", "runbook")
PROZESSE = ("Leitstelle", "Werkstatt und Fahrzeugfreigabe", "Zahlungsklärung", "Datenschutz", "Betrieb und Rufbereitschaft")
ENTSCHEIDUNGEN = ("PILOT_UNTERBRECHEN", "MIT_AUFLAGE_FORTSETZEN")
BESTAND_ARTEN = ("betreiber", "bediengebiete", "tarife", "fahrzeuge", "benutzer")
MANGELKLASSEN = ("A", "B", "C")


def _json(pfad):
    return json.loads(Path(pfad).read_text(encoding="utf-8"))


def kriterien(pfad=PILOT / "abbruchkriterien.json"):
    return _json(pfad)


def verantwortliche(pfad=PILOT / "prozessverantwortliche.json"):
    return _json(pfad)


def katalog():
    return yaml.safe_load((REPO / "infra" / "alerting" / "katalog.yaml").read_text(encoding="utf-8"))


def messpunktvertrag():
    return _json(REPO / "infra" / "availability" / "messpunktvertrag.json")


def rollen_der_plattform():
    """Rollencodes des Katalogs aus der Rechtematrix (Spaltenköpfe)."""
    p = REPO / "backend/modules/identity/src/main/resources/rechtematrix/rechtematrix.csv"
    for z in p.read_text(encoding="utf-8").splitlines():
        if z.startswith("operation_id;"):
            return z.split(";")[4:]
    raise ValueError("Kopfzeile der Rechtematrix nicht gefunden")


# ---------------------------------------------------------------------------------------------------------------- Abbruchkriterien
def kriterien_pruefen(doc, kat=None, vertrag=None):
    """Struktur und Messbarkeit aus dem Monitoring (WP-M5-04 Alarmkatalog, WP-M5-05 Verfügbarkeitsmessung). Rückgabe: Liste von Befunden."""
    kat = kat or katalog()
    vertrag = vertrag or messpunktvertrag()
    alarme = {s["id"]: s for s in kat["szenarien"]}
    befunde = []
    ids = [c.get("id") for c in doc.get("kriterien", [])]
    if not ids:
        befunde.append("keine Abbruchkriterien definiert")
    if len(set(ids)) != len(ids):
        befunde.append("Kriterien-IDs sind nicht eindeutig")
    for e in ("rolle", "vertretung_rolle", "auftraggeber_rolle"):
        if not doc.get("entscheider", {}).get(e):
            befunde.append(f"Entscheider: Feld '{e}' fehlt")
    if not doc.get("entscheidungsweg"):
        befunde.append("Entscheidungsweg fehlt")
    else:
        rollen = [s.get("rolle") for s in doc["entscheidungsweg"]]
        if doc.get("entscheider", {}).get("rolle") not in rollen:
            befunde.append("Entscheidungsweg enthält die Entscheiderrolle nicht")
        for s in doc["entscheidungsweg"]:
            if not (s.get("aktion") and isinstance(s.get("frist_min"), int) and s["frist_min"] > 0):
                befunde.append(f"Entscheidungsweg Stufe {s.get('stufe')}: Aktion und positive Frist erforderlich")
    if not doc.get("wiederaufnahme"):
        befunde.append("Regel zur Wiederaufnahme fehlt")
    for c in doc.get("kriterien", []):
        k = c.get("id", "?")
        for f in PFLICHTFELDER:
            if f not in c or c[f] in ("", None) and f not in ("schwelle",):
                befunde.append(f"{k}: Feld '{f}' fehlt oder ist leer")
        if c.get("vergleich") not in VERGLEICHE:
            befunde.append(f"{k}: Vergleichsoperator '{c.get('vergleich')}' ungültig")
        if c.get("mangelklasse") not in MANGELKLASSEN:
            befunde.append(f"{k}: Mangelklasse '{c.get('mangelklasse')}' ungültig")
        if c.get("schwelle") is not None and not isinstance(c["schwelle"], (int, float)):
            befunde.append(f"{k}: Schwelle ist keine Zahl")
        m = c.get("messquelle") or {}
        art = m.get("art")
        if art == "alarm":
            if m.get("id") not in alarme:
                befunde.append(f"{k}: Messquelle Alarm {m.get('id')} fehlt im Alarmkatalog (nicht aus dem Monitoring messbar)")
            elif c.get("runbook") != alarme[m["id"]]["runbook"]:
                befunde.append(f"{k}: Runbook-Verweis weicht vom Alarmkatalog ab")
        elif art == "metrik":
            a = alarme.get(m.get("alarm"))
            if a is None:
                befunde.append(f"{k}: Messquelle Metrik verweist auf unbekannten Alarm {m.get('alarm')}")
            elif m.get("name") not in a["signal"]:
                befunde.append(f"{k}: Metrik {m.get('name')} ist kein Signal des Alarms {m['alarm']}")
        elif art == "verfuegbarkeit":
            if m.get("bereich") not in vertrag.get("bereiche", {}):
                befunde.append(f"{k}: Bereich {m.get('bereich')} fehlt im Messpunktvertrag der Verfügbarkeitsmessung")
            if m.get("feld") != "verfuegbarkeit_prozent":
                befunde.append(f"{k}: Feld '{m.get('feld')}' ist kein Ausgabefeld des Monatsberichts")
        else:
            befunde.append(f"{k}: Messquelle '{art}' ist weder Alarm, Metrik noch Verfügbarkeit (nicht aus dem Monitoring messbar)")
        if c.get("runbook"):
            datei = c["runbook"].split("#")[0]
            if not (REPO / datei).is_file():
                befunde.append(f"{k}: Runbook-Datei {datei} fehlt")
    return befunde


def offene_punkte_kriterien(doc):
    """Was dem Pilot-Gate noch fehlt, ohne dass die Struktur fehlerhaft ist: Schwellen und Freigabe des Auftraggebers."""
    offen = []
    for c in doc.get("kriterien", []):
        if c.get("schwelle") is None or c.get("schwelle_status") != "FREIGEGEBEN":
            offen.append(f"{c['id']}: Schwelle durch Auftraggeber festzulegen und freizugeben (Arbeitsannahme: {c.get('schwelle_arbeitsannahme')} {c.get('einheit')})")
    fr = doc.get("freigabe", {})
    if not (fr.get("freigegeben_von") and fr.get("datum")):
        offen.append("Abbruchkriterien und Rückfallbetrieb sind nicht vom Auftraggeber freigegeben")
    return offen


def bewerten(doc, messwerte, schwellen=None):
    """Wertet Messwerte (Kriterium-ID -> Zahl) aus. `schwellen` überschreibt/ergänzt offene Schwellen (nur für Probeläufe und freigegebene Werte).
    Rückgabe: {"verletzt": [...], "nicht_bewertbar": [...], "kein_messwert": [...], "eingehalten": [...]}. Eine offene Schwelle ist `nicht_bewertbar`, nie `eingehalten`;
    ein fehlender Messwert ist `kein_messwert`, nie `eingehalten`."""
    schwellen = schwellen or {}
    erg = {"verletzt": [], "nicht_bewertbar": [], "kein_messwert": [], "eingehalten": []}
    for c in doc["kriterien"]:
        s = schwellen.get(c["id"], c.get("schwelle"))
        if s is None:
            erg["nicht_bewertbar"].append(c["id"])
            continue
        w = messwerte.get(c["id"])
        if w is None:
            erg["kein_messwert"].append(c["id"])
            continue
        if VERGLEICHE[c["vergleich"]](w, s):
            erg["verletzt"].append({"id": c["id"], "titel": c["titel"], "messwert": w, "schwelle": s, "vergleich": c["vergleich"], "einheit": c["einheit"], "mangelklasse": c["mangelklasse"],
                                    "messquelle": c["messquelle"]})
        else:
            erg["eingehalten"].append(c["id"])
    return erg


# ---------------------------------------------------------------------------------------------------------------- Entscheidungsjournal
def _jetzt():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _hash(eintrag):
    rumpf = {k: v for k, v in eintrag.items() if k != "hash"}
    return hashlib.sha256(json.dumps(rumpf, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


class Journal:
    """Nur anfügbare Hash-Kette (JSON-Zeilen). Jede Änderung läuft unter einer Dateisperre; ein Eintrag wird nie verändert."""

    def __init__(self, pfad, doc=None):
        self.pfad = Path(pfad)
        self.doc = doc or kriterien()

    def _lesen(self, f):
        f.seek(0)
        return [json.loads(z) for z in f.read().splitlines() if z.strip()]

    def _anfuegen(self, art, daten, pruefung):
        """Fügt einen Eintrag an, nachdem `pruefung(eintraege)` unter der Sperre gelaufen ist (löst ValueError aus, wenn der Eintrag unzulässig ist)."""
        self.pfad.parent.mkdir(parents=True, exist_ok=True)
        with open(self.pfad, "a+", encoding="utf-8") as f:
            fcntl.flock(f, fcntl.LOCK_EX)
            try:
                bisher = self._lesen(f)
                self._kette_pruefen(bisher)
                pruefung(bisher)
                e = {"seq": len(bisher) + 1, "zeit": _jetzt(), "art": art, **daten, "prev": bisher[-1]["hash"] if bisher else "0" * 64}
                e["hash"] = _hash(e)
                f.seek(0, 2)
                f.write(json.dumps(e, ensure_ascii=False) + "\n")
                f.flush()
                return e
            finally:
                fcntl.flock(f, fcntl.LOCK_UN)

    @staticmethod
    def _kette_pruefen(eintraege):
        prev = "0" * 64
        for i, e in enumerate(eintraege, 1):
            if e["seq"] != i or e["prev"] != prev or e["hash"] != _hash(e):
                raise ValueError(f"Journal manipuliert oder unterbrochen bei Eintrag {i}")
            prev = e["hash"]

    def eintraege(self):
        if not self.pfad.exists():
            return []
        with open(self.pfad, encoding="utf-8") as f:
            fcntl.flock(f, fcntl.LOCK_SH)
            try:
                e = self._lesen(f)
            finally:
                fcntl.flock(f, fcntl.LOCK_UN)
        self._kette_pruefen(e)
        return e

    def zustand(self):
        """BETRIEB, ENTSCHEIDUNG_OFFEN (mindestens eine Auslösung ohne Entscheidung) oder UNTERBROCHEN (letzte Wirkung: Unterbrechung ohne Wiederaufnahme)."""
        e = self.eintraege()
        if any(x["art"] == "AUSLOESUNG" and not any(y["art"] == "ENTSCHEIDUNG" and y["auslosung"] == x["seq"] for y in e) for x in e):
            return "ENTSCHEIDUNG_OFFEN"
        pause = False
        for x in e:
            if x["art"] == "ENTSCHEIDUNG" and x["entscheidung"] == "PILOT_UNTERBRECHEN":
                pause = True
            elif x["art"] == "WIEDERAUFNAHME":
                pause = False
        return "UNTERBROCHEN" if pause else "BETRIEB"

    def ausloesen(self, verletzung):
        """Das Erreichen eines Abbruchkriteriums löst den Entscheidungsweg aus: Eintrag mit Stufen, Fristen und Entscheiderrolle. Ein bereits offener Eintrag zum selben Kriterium wird nicht doppelt angelegt."""
        def pruefung(bisher):
            for x in bisher:
                if x["art"] == "AUSLOESUNG" and x["kriterium"] == verletzung["id"] and not any(y["art"] == "ENTSCHEIDUNG" and y["auslosung"] == x["seq"] for y in bisher):
                    raise ValueError(f"Für {verletzung['id']} ist bereits eine Entscheidung offen (Eintrag {x['seq']})")
        d = self.doc
        return self._anfuegen("AUSLOESUNG", {
            "kriterium": verletzung["id"], "messwert": verletzung["messwert"], "schwelle": verletzung["schwelle"], "vergleich": verletzung["vergleich"], "mangelklasse": verletzung["mangelklasse"],
            "entscheider_rolle": d["entscheider"]["rolle"], "stufen": d["entscheidungsweg"], "status": "ENTSCHEIDUNG_OFFEN"}, pruefung)

    def entscheiden(self, auslosung, entscheider, rolle, entscheidung, begruendung, auflage=None):
        """Entscheidung der Betriebsleitung (oder ihrer Vertretung) zu einer offenen Auslösung. Genau eine Entscheidung je Auslösung, auch bei gleichzeitigen Versuchen."""
        erlaubt = {self.doc["entscheider"]["rolle"], self.doc["entscheider"]["vertretung_rolle"]}

        def pruefung(bisher):
            a = next((x for x in bisher if x["seq"] == auslosung and x["art"] == "AUSLOESUNG"), None)
            if a is None:
                raise ValueError(f"Eintrag {auslosung} ist keine Auslösung")
            if any(y["art"] == "ENTSCHEIDUNG" and y["auslosung"] == auslosung for y in bisher):
                raise ValueError(f"Zu Auslösung {auslosung} liegt bereits eine Entscheidung vor")
            if rolle not in erlaubt:
                raise ValueError(f"Rolle {rolle} darf nicht entscheiden (zulässig: {', '.join(sorted(erlaubt))})")
            if not entscheider:
                raise ValueError("Entscheider (Person) fehlt")
            if entscheidung not in ENTSCHEIDUNGEN:
                raise ValueError(f"Entscheidung {entscheidung} ungültig (zulässig: {', '.join(ENTSCHEIDUNGEN)})")
            if not begruendung or not begruendung.strip():
                raise ValueError("Begründung fehlt")
            if entscheidung == "MIT_AUFLAGE_FORTSETZEN" and not (auflage and auflage.strip()):
                raise ValueError("Fortsetzen verlangt eine Auflage")
            if a["mangelklasse"] == "A" and entscheidung == "MIT_AUFLAGE_FORTSETZEN":
                raise ValueError("Bei einem Mangel der Klasse A ist Fortsetzen nicht zulässig (blockiert die Produktion, Kap. 08 §8.4)")
        return self._anfuegen("ENTSCHEIDUNG", {"auslosung": auslosung, "entscheider": entscheider, "rolle": rolle, "entscheidung": entscheidung, "begruendung": begruendung, "auflage": auflage}, pruefung)

    def wiederaufnehmen(self, betriebsleitung, auftraggeber, messwerte, schwellen=None):
        """Wiederaufnahme nur nach Unterbrechung, mit zwei verschiedenen Personen (Betriebsleitung und Auftraggeber) und nur, wenn kein Kriterium verletzt und keines unbewertbar ist."""
        def pruefung(bisher):
            if Journal._pause(bisher) is not True:
                raise ValueError("Es liegt keine Unterbrechung vor")
            if not betriebsleitung or not auftraggeber or betriebsleitung == auftraggeber:
                raise ValueError("Wiederaufnahme verlangt zwei verschiedene Personen (Betriebsleitung und Auftraggeber)")
            b = bewerten(self.doc, messwerte, schwellen)
            if b["verletzt"] or b["nicht_bewertbar"] or b["kein_messwert"]:
                raise ValueError(f"Kriterien nicht eingehalten oder nicht bewertbar: {b['verletzt'] and [v['id'] for v in b['verletzt']]} {b['nicht_bewertbar']} {b['kein_messwert']}")
        return self._anfuegen("WIEDERAUFNAHME", {"betriebsleitung": betriebsleitung, "auftraggeber": auftraggeber, "messwerte": messwerte}, pruefung)

    @staticmethod
    def _pause(e):
        pause = False
        for x in e:
            if x["art"] == "ENTSCHEIDUNG" and x["entscheidung"] == "PILOT_UNTERBRECHEN":
                pause = True
            elif x["art"] == "WIEDERAUFNAHME":
                pause = False
        return pause


# ---------------------------------------------------------------------------------------------------------------- Prozessverantwortliche
def verantwortliche_pruefen(doc, rollen=None):
    """Struktur: fünf Prozesse, Plattformrolle existiert im Rollenkatalog, Vertretung verschieden von der Person. Rückgabe: (Befunde, offene Punkte)."""
    rollen = set(rollen or rollen_der_plattform())
    befunde, offen = [], []
    prozesse = [v.get("prozess") for v in doc.get("verantwortungen", [])]
    for p in PROZESSE:
        if p not in prozesse:
            befunde.append(f"Prozessverantwortung '{p}' fehlt")
    for v in doc.get("verantwortungen", []):
        pid = v.get("id", "?")
        pr = v.get("plattformrolle")
        if pr is not None and pr not in rollen:
            befunde.append(f"{pid}: Plattformrolle '{pr}' existiert nicht im Rollenkatalog")
        if pr is None and not v.get("betriebsrolle"):
            befunde.append(f"{pid}: weder Plattform- noch Betriebsrolle")
        if v.get("person") and v.get("vertretung") and v["person"] == v["vertretung"]:
            befunde.append(f"{pid}: Vertretung ist dieselbe Person")
        if not v.get("person"):
            offen.append(f"{pid} {v.get('prozess')}: Person nicht benannt")
        if not v.get("vertretung"):
            offen.append(f"{pid} {v.get('prozess')}: Vertretung nicht benannt")
        if pr is not None and not v.get("in_plattform_angelegt"):
            offen.append(f"{pid} {v.get('prozess')}: Rolle {pr} nicht in der Plattform angelegt")
    return befunde, offen


# ---------------------------------------------------------------------------------------------------------------- Generalprobe und Rückfallübung
def generalprobe_pruefen(p, migrationsprotokoll_sha256=None):
    """Prüft ein ausgefülltes Generalprobenprotokoll: ein Betriebstag in Staging mit Pilotstammdaten und Betreiberpersonal; Befunde mit Mangelklasse; kein offener Befund der Klasse A."""
    b = []
    if p.get("umgebung") != "staging":
        b.append("Generalprobe: Umgebung muss 'staging' sein")
    for f in ("datum", "abnahme_build", "datenstand", "ergebnis"):
        if not p.get(f):
            b.append(f"Generalprobe: Feld '{f}' fehlt")
    if not p.get("betriebstag_dauer_h") or p["betriebstag_dauer_h"] < 8:
        b.append("Generalprobe: ein Betriebstag (mindestens 8 Stunden) ist nachzuweisen")
    rollen = {x.get("prozess") for x in p.get("betreiberpersonal", []) if x.get("person_pseudonym")}
    for pz in PROZESSE:
        if pz not in rollen:
            b.append(f"Generalprobe: Betreiberpersonal für '{pz}' fehlt")
    if migrationsprotokoll_sha256 and p.get("pilotstammdaten_protokoll_sha256") != migrationsprotokoll_sha256:
        b.append("Generalprobe: Pilotstammdaten stammen nicht aus dem freigegebenen Migrationsprotokoll")
    elif not p.get("pilotstammdaten_protokoll_sha256"):
        b.append("Generalprobe: Bezug auf das Migrationsprotokoll der Pilotstammdaten fehlt")
    for x in p.get("befunde", []):
        if x.get("mangelklasse") not in MANGELKLASSEN:
            b.append(f"Generalprobe: Befund {x.get('id')} ohne gültige Mangelklasse")
        if x.get("mangelklasse") == "A" and x.get("status") != "BEHOBEN_UND_WIEDERHOLT":
            b.append(f"Generalprobe: Befund {x.get('id')} der Klasse A ist nicht behoben und auf dem neuen Stand wiederholt")
    if p.get("ergebnis") == "ohne_mangel_klasse_a" and any(x.get("mangelklasse") == "A" and x.get("status") != "BEHOBEN_UND_WIEDERHOLT" for x in p.get("befunde", [])):
        b.append("Generalprobe: Ergebnis 'ohne_mangel_klasse_a' widerspricht den Befunden")
    return b


def rueckfalluebung_pruefen(p, rueckfall_schritte):
    """Prüft ein Übungsprotokoll des Rückfallbetriebs: Art (Tischübung/Teilübung), Teilnehmer je Prozess, jeder Schritt des Rückfallprozesses durchlaufen, Zeiten, Befunde."""
    b = []
    if p.get("art") not in ("Tischuebung", "Teiluebung"):
        b.append("Rückfallübung: Art muss Tischuebung oder Teiluebung sein")
    for f in ("datum", "szenario_kriterium", "abnahme_build"):
        if not p.get(f):
            b.append(f"Rückfallübung: Feld '{f}' fehlt")
    durch = {s.get("schritt"): s for s in p.get("schritte", [])}
    for s in rueckfall_schritte:
        d = durch.get(s)
        if d is None:
            b.append(f"Rückfallübung: Schritt '{s}' nicht durchlaufen")
        elif not d.get("verantwortlich") or not d.get("dauer_min") and d.get("dauer_min") != 0:
            b.append(f"Rückfallübung: Schritt '{s}' ohne Verantwortlichen oder Dauer")
    return b


# ---------------------------------------------------------------------------------------------------------------- Produktionseinspielung
def migrationsprotokoll_pruefen(prot, mandant_id, build=None, art_pflicht=BESTAND_ARTEN):
    """Bestätigt Soll- und Ist-Bestände für Bediengebiet, Tarif, Betreiber, Fahrzeuge und Benutzer aus dem Migrationsprotokoll der Einspielung (WP-M5-25). Rückgabe: Befunde."""
    b = []
    lauf = prot.get("lauf", {})
    if lauf.get("modus") != "lauf":
        b.append(f"Protokoll: Modus '{lauf.get('modus')}' ist kein Lauf (Trockenläufe belegen keine Einspielung)")
    if lauf.get("status") != "ABGESCHLOSSEN" or lauf.get("ergebnis") != "ABGESCHLOSSEN":
        b.append(f"Protokoll: Lauf nicht fehlerfrei abgeschlossen (Status {lauf.get('status')}, Ergebnis {lauf.get('ergebnis')})")
    if str(prot.get("mandant", {}).get("tenant_id")) != str(mandant_id):
        b.append("Protokoll: Mandant weicht vom Zielmandanten ab")
    if build is not None and prot.get("build") != build:
        b.append(f"Protokoll: Build {prot.get('build')} weicht vom Abnahme-Build {build} ab")
    for f in ("ausfuehrender", "freigeber", "migrationsstand"):
        if not prot.get(f):
            b.append(f"Protokoll: Feld '{f}' fehlt")
    if prot.get("fehlerliste"):
        b.append(f"Protokoll: Fehlerliste nicht leer ({len(prot['fehlerliste'])})")
    for art in art_pflicht:
        x = prot.get("bestaende", {}).get(art)
        if x is None:
            b.append(f"Protokoll: Bestand '{art}' fehlt")
        elif x.get("soll") != x.get("ist") or x.get("abgelehnt"):
            b.append(f"Protokoll: {art}: Soll {x.get('soll')} ungleich Ist {x.get('ist')} oder abgelehnte Datensätze {x.get('abgelehnt')}")
        elif not x.get("soll"):
            b.append(f"Protokoll: {art}: Soll ist 0 (nichts eingespielt)")
    if not all(k.get("ok") for k in prot.get("konsistenz", [])) or not prot.get("konsistenz"):
        b.append("Protokoll: Konsistenzregeln nicht alle erfüllt oder nicht geprüft")
    return b


def produktionsdaten_pruefen(conn, pilotmandanten, probemandanten_erlaubt=1):
    """Die Produktionsdatenbank enthält außer den freigegebenen Pilotmandanten und dem gekennzeichneten Probemandanten (`tenant.synthetic`) keine Testdaten. Rückgabe: Befunde."""
    sys.path.insert(0, str(REPO / "infra" / "rollout"))
    from taktovia_rollout.datenbank import SYSTEMMANDANT
    pilot = {str(x) for x in pilotmandanten}
    b = []
    zeilen = conn.execute("SELECT id, name, synthetic FROM taktovia.tenant WHERE id <> %s", (SYSTEMMANDANT,)).fetchall()
    for tid, name, synth in zeilen:
        if synth:
            if str(tid) in pilot:
                b.append(f"Mandant {tid} ist Pilotmandant und als Probemandant gekennzeichnet (Verwechslung)")
        elif str(tid) not in pilot:
            b.append(f"Mandant {tid} ({name}) ist weder freigegebener Pilotmandant noch Probemandant (Testdaten)")
    n = sum(1 for _, _, s in zeilen if s)
    if n > probemandanten_erlaubt:
        b.append(f"{n} Probemandanten, erlaubt {probemandanten_erlaubt}")
    fehlend = pilot - {str(t) for t, _, _ in zeilen}
    for t in sorted(fehlend):
        b.append(f"Pilotmandant {t} fehlt in der Datenbank")
    return b


# ---------------------------------------------------------------------------------------------------------------- Gesamtprüfung
def bereitschaft():
    """Gesamtprüfung der Pilotbereitschaftsdokumente: (Befunde, offene Punkte)."""
    k = kriterien()
    v = verantwortliche()
    befunde = kriterien_pruefen(k)
    vb, vo = verantwortliche_pruefen(v)
    befunde += vb
    offen = offene_punkte_kriterien(k) + vo
    text = (PILOT / "pilotbereitschaft.md").read_text(encoding="utf-8")
    for c in k["kriterien"]:
        if c["id"] not in text:
            befunde.append(f"pilotbereitschaft.md nennt das Kriterium {c['id']} nicht")
    for pz in PROZESSE:
        if pz not in text:
            befunde.append(f"pilotbereitschaft.md nennt die Prozessverantwortung '{pz}' nicht")
    for abschnitt in ("Rückfallbetrieb", "Entscheidungsweg", "Wiederaufnahme", "Generalprobe", "Produktionseinspielung"):
        if not re.search(rf"^## .*{abschnitt}", text, re.M):
            befunde.append(f"pilotbereitschaft.md: Abschnitt '{abschnitt}' fehlt")
    offen += ["Generalprobe (ein Betriebstag in Staging mit Pilotstammdaten und Betreiberpersonal) nicht durchgeführt",
              "Rückfallbetrieb nicht geübt (Tisch- oder Teilübung)",
              "Produktionseinspielung der Pilotstammdaten nicht durchgeführt (keine Produktionsumgebung, keine freigegebenen Pilotdaten)"]
    return befunde, offen


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sp = ap.add_subparsers(dest="cmd", required=True)
    b = sp.add_parser("bereitschaft")
    b.add_argument("--nur-struktur", action="store_true")
    w = sp.add_parser("bewerten")
    w.add_argument("--messwerte", required=True)
    w.add_argument("--schwellen")
    w.add_argument("--journal")
    e = sp.add_parser("entscheiden")
    e.add_argument("--journal", required=True)
    e.add_argument("--auslosung", type=int, required=True)
    e.add_argument("--entscheider", required=True)
    e.add_argument("--rolle", required=True)
    e.add_argument("--entscheidung", required=True)
    e.add_argument("--begruendung", required=True)
    e.add_argument("--auflage")
    a = ap.parse_args(argv)
    try:
        if a.cmd == "bereitschaft":
            befunde, offen = bereitschaft()
            print(json.dumps({"ergebnis": "BEFUNDE" if befunde else ("STRUKTUR_OK_NICHT_FREIGEGEBEN" if offen else "BEREIT"), "befunde": befunde, "offene_punkte": offen}, ensure_ascii=False, indent=2))
            return 1 if befunde or (offen and not a.nur_struktur) else 0
        if a.cmd == "bewerten":
            doc = kriterien()
            erg = bewerten(doc, _json(a.messwerte), _json(a.schwellen) if a.schwellen else None)
            if a.journal:
                j = Journal(a.journal, doc)
                erg["eintraege"] = []
                for v in erg["verletzt"]:
                    try:
                        erg["eintraege"].append(j.ausloesen(v)["seq"])
                    except ValueError as ex:
                        erg.setdefault("hinweise", []).append(str(ex))
            print(json.dumps(erg, ensure_ascii=False, indent=2))
            return 1 if erg["verletzt"] or erg["nicht_bewertbar"] or erg["kein_messwert"] else 0
        if a.cmd == "entscheiden":
            print(json.dumps(Journal(a.journal).entscheiden(a.auslosung, a.entscheider, a.rolle, a.entscheidung, a.begruendung, a.auflage), ensure_ascii=False, indent=2))
            return 0
    except (OSError, ValueError, KeyError) as ex:
        print(f"Fehler: {ex}", file=sys.stderr)
        return 2
    return 2


if __name__ == "__main__":
    sys.exit(main())
