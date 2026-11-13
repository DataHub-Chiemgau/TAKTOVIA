"""Quelldatei (Format `taktovia-stammdaten/1`, vorläufig bis OP-18 geklärt ist), Prüfsumme und lokale Konsistenzprüfung.

Fehlerliste: jeder Eintrag nennt Objektart, Quellschlüssel, Grundcode und einen kurzen Text ohne Datenwerte (keine Kontaktdaten, keine Koordinaten)."""
import hashlib
import json
import re
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

from . import geometrie

FORMAT = "taktovia-stammdaten/1"
# Reihenfolge der Objektarten nach Abhängigkeit (Vertrag LH-6.20-QAT-005): Betreiber vor Dienst/Gebiet, Haltepunkte nach Gebiet, Tarif nach Dienst,
# Fahrzeuge nach Betreiber und Dienst; Benutzer (Mitgliedschaft mit Rolle) und davon abhängige Fahrer zuletzt, die Erstwerte der Konfiguration am Schluss.
ARTEN = ("betreiber", "dienste", "bediengebiete", "haltepunkte", "tarife", "fahrzeuge", "benutzer", "fahrer", "konfiguration")
# Referenzen: Feld -> Zielart (Liste: mehrere Verweise); optional = Verweis darf fehlen
REFERENZEN = {
    "dienste": (("betreiber", "betreiber", False),),
    "bediengebiete": (("dienst", "dienste", False),),
    "haltepunkte": (("gebiet", "bediengebiete", False),),
    "tarife": (("dienst", "dienste", False),),
    "fahrzeuge": (("betreiber", "betreiber", False), ("dienst", "dienste", False), ("gebiet", "bediengebiete", True)),
    "benutzer": (("dienste", "dienste", True),),
    "fahrer": (("benutzer", "benutzer", False),),
}
ROLLEN = ("passenger", "driver", "callcenter", "support", "dispatcher", "planner", "tenant_admin", "finance", "staff_planning", "workshop")
KONTAKTKANAELE = ("PHONE", "EMAIL", "APP")
_UUID = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")


class QuelleFehler(Exception):
    """Die Quelle als Ganzes ist unbrauchbar (nicht lesbar, kein JSON, falsches Format); es wird nichts geschrieben."""


@dataclass
class Fehler:
    art: str
    key: str
    code: str
    text: str

    def als_dict(self):
        return {"objektart": self.art, "schluessel": self.key, "grund": self.code, "text": self.text}


@dataclass
class Quelle:
    pfad: str
    sha256: str
    daten: dict
    mandant_id: str | None = None
    datensaetze: dict = field(default_factory=dict)   # art -> Liste gültig geformter Datensätze (dicts mit "key")
    fehler: list = field(default_factory=list)
    soll: dict = field(default_factory=dict)          # art -> Anzahl Datensätze in der Quelle

    def abgelehnt(self) -> set:
        return {(f.art, f.key) for f in self.fehler}


def laden(pfad) -> Quelle:
    p = Path(pfad)
    try:
        roh = p.read_bytes()
    except OSError as e:
        raise QuelleFehler(f"Quelle nicht lesbar ({type(e).__name__})") from None
    try:
        daten = json.loads(roh)
    except ValueError:
        raise QuelleFehler("Quelle ist kein gültiges JSON") from None
    if not isinstance(daten, dict) or daten.get("format") != FORMAT:
        raise QuelleFehler(f"Quellformat unbekannt (erwartet {FORMAT})")
    unbekannt = set(daten) - set(ARTEN) - {"format", "mandant", "beschreibung"}
    if unbekannt:
        raise QuelleFehler("Unbekannte Abschnitte in der Quelle: " + ", ".join(sorted(unbekannt)))
    mandant = daten.get("mandant")
    mandant_id = mandant.get("tenant_id") if isinstance(mandant, dict) else None
    if not isinstance(mandant_id, str) or not _UUID.match(mandant_id):
        raise QuelleFehler("mandant.tenant_id fehlt oder ist keine UUID")
    q = Quelle(str(p), hashlib.sha256(roh).hexdigest(), daten, mandant_id.lower())
    _pruefen(q)
    return q


def _zeit(wert, pflicht=True):
    if wert is None:
        return None if not pflicht else False
    if not isinstance(wert, str):
        return False
    try:
        t = datetime.fromisoformat(wert.replace("Z", "+00:00"))
    except ValueError:
        return False
    return t if t.tzinfo is not None else False


def _kennzeichen_norm(s) -> str:
    return re.sub(r"[\s\-]", "", s).upper()


def _pruefen(q: Quelle):
    d = q.daten
    gueltig_keys = {a: {} for a in ARTEN}
    for art in ARTEN:
        eintraege = d.get(art, [])
        if not isinstance(eintraege, list):
            raise QuelleFehler(f"Abschnitt {art} ist keine Liste")
        q.soll[art] = len(eintraege)
        q.datensaetze[art] = []
        gesehen = set()
        for i, e in enumerate(eintraege):
            key = e.get("key") if isinstance(e, dict) else None
            if not isinstance(key, str) or not key.strip():
                q.fehler.append(Fehler(art, f"#{i + 1}", "QUELLE_SCHLUESSEL_FEHLT", "Datensatz ohne Quellschlüssel"))
                continue
            if key in gesehen:
                q.fehler.append(Fehler(art, key, "QUELLE_SCHLUESSEL_DOPPELT", "Quellschlüssel mehrfach vergeben"))
                continue
            gesehen.add(key)
            f = _pruefe_datensatz(art, e, d)
            if f:
                q.fehler.append(Fehler(art, key, f[0], f[1]))
            else:
                q.datensaetze[art].append(e)
    # Kennzeichen eindeutig (zweites Vorkommen wird abgelehnt)
    belegt = {}
    for e in list(q.datensaetze["fahrzeuge"]):
        n = _kennzeichen_norm(e["kennzeichen"])
        if n in belegt:
            q.fehler.append(Fehler("fahrzeuge", e["key"], "KENNZEICHEN_DOPPELT", "Kennzeichen kommt in der Quelle mehrfach vor"))
            q.datensaetze["fahrzeuge"].remove(e)
        else:
            belegt[n] = e["key"]
    # Subjekte je Aussteller eindeutig
    belegt = {}
    for e in list(q.datensaetze["benutzer"]):
        k = (e.get("issuer") or "", e["subject"])
        if k in belegt:
            q.fehler.append(Fehler("benutzer", e["key"], "BENUTZER_DOPPELT", "Benutzerkennung kommt in der Quelle mehrfach vor"))
            q.datensaetze["benutzer"].remove(e)
        else:
            belegt[k] = e["key"]
    # ein Benutzer hat höchstens einen Fahrerstamm
    belegt = {}
    for e in list(q.datensaetze["fahrer"]):
        if e["benutzer"] in belegt:
            q.fehler.append(Fehler("fahrer", e["key"], "FAHRER_DOPPELT", "Für denselben Benutzer ist schon ein Fahrer in der Quelle"))
            q.datensaetze["fahrer"].remove(e)
        else:
            belegt[e["benutzer"]] = e["key"]
    # Referenzen und abhängige Datensätze in Reihenfolge der Objektarten
    abgelehnt = q.abgelehnt()
    vorhanden = {a: {e["key"] for e in q.datensaetze[a]} for a in ARTEN}
    geld_gebiete = {e["key"]: e for e in q.datensaetze["bediengebiete"]}
    for art in ARTEN:
        for e in list(q.datensaetze[art]):
            f = _pruefe_referenzen(art, e, d, vorhanden, abgelehnt, geld_gebiete)
            if f:
                q.fehler.append(Fehler(art, e["key"], f[0], f[1]))
                q.datensaetze[art].remove(e)
                vorhanden[art].discard(e["key"])
                abgelehnt.add((art, e["key"]))


def _pflicht_text(e, *felder):
    return all(isinstance(e.get(f), str) and e[f].strip() for f in felder)


def _pruefe_datensatz(art, e, d):
    """(Code, Text) oder None."""
    if art == "betreiber":
        if not _pflicht_text(e, "name", "federation_ref") or not (isinstance(e.get("country"), str) and re.fullmatch(r"[A-Z]{2}", e["country"])):
            return "QUELLE_PFLICHTFELD", "Betreiber braucht name, country (ISO-Code) und federation_ref"
        if not isinstance(e.get("roles"), list) or not e["roles"]:
            return "QUELLE_PFLICHTFELD", "Betreiber braucht mindestens eine vertragliche Rolle"
    elif art == "dienste":
        if not _pflicht_text(e, "name", "betreiber", "timezone"):
            return "QUELLE_PFLICHTFELD", "Dienst braucht name, betreiber und timezone"
    elif art == "bediengebiete":
        if not _pflicht_text(e, "dienst"):
            return "QUELLE_PFLICHTFELD", "Bediengebiet braucht dienst"
        if _zeit(e.get("valid_from")) is False:
            return "QUELLE_PFLICHTFELD", "Bediengebiet braucht valid_from mit Zeitzonenangabe"
        grund = geometrie.multipolygon_fehler(e.get("area"))
        if grund:
            return "GEOMETRIE_UNGUELTIG", "Bediengebiet ist kein valides Polygon: " + grund
    elif art == "haltepunkte":
        p = e.get("point")
        if not _pflicht_text(e, "name", "gebiet") or not isinstance(p, dict) or not all(isinstance(p.get(k), (int, float)) for k in ("latitude", "longitude")):
            return "QUELLE_PFLICHTFELD", "Haltepunkt braucht name, gebiet und point (latitude, longitude)"
        if _zeit(e.get("valid_from")) is False or not isinstance(e.get("accessibility"), dict) or not isinstance(e.get("traffic_products"), list) or not e["traffic_products"]:
            return "QUELLE_PFLICHTFELD", "Haltepunkt braucht valid_from, accessibility und traffic_products"
    elif art == "tarife":
        von, bis = _zeit(e.get("valid_from")), _zeit(e.get("valid_to"), pflicht=False)
        if von is False or von is None:
            return "TARIF_OHNE_GUELTIGKEIT", "Tarif ohne gültiges Gültigkeitsintervall (valid_from mit Zeitzone fehlt)"
        if bis is False or (bis is not None and bis <= von):
            return "TARIF_OHNE_GUELTIGKEIT", "Tarif mit ungültigem Gültigkeitsende"
        if not _pflicht_text(e, "dienst", "tariff_class", "currency", "reason") or not isinstance(e.get("rules"), list) or not e["rules"]:
            return "QUELLE_PFLICHTFELD", "Tarif braucht dienst, tariff_class, currency, reason und mindestens eine Regel"
    elif art == "fahrzeuge":
        c = e.get("capacity")
        if not _pflicht_text(e, "kennzeichen", "vehicle_type", "betreiber", "dienst"):
            return "QUELLE_PFLICHTFELD", "Fahrzeug braucht kennzeichen, vehicle_type, betreiber und dienst"
        if not isinstance(c, dict) or not all(isinstance(c.get(k), int) and not isinstance(c.get(k), bool) and c[k] >= 0 for k in ("seats", "wheelchair")):
            return "FAHRZEUG_OHNE_RESSOURCENVEKTOR", "Fahrzeug ohne gültigen Ressourcenvektor (Sitze, Rollstuhlplätze)"
        if _zeit(e.get("zuordnung_ab")) is False:
            return "QUELLE_PFLICHTFELD", "Fahrzeug braucht zuordnung_ab (Beginn der Dienstzuordnung mit Zeitzone)"
    elif art == "benutzer":
        mandant = d.get("mandant") if isinstance(d.get("mandant"), dict) else {}
        if not _pflicht_text(e, "subject") or not (_pflicht_text(e, "issuer") or _pflicht_text(mandant, "issuer")):
            return "QUELLE_PFLICHTFELD", "Benutzer braucht subject und einen Aussteller (issuer am Benutzer oder in mandant)"
        if e.get("rolle") not in ROLLEN:
            return "BENUTZER_OHNE_ROLLE", "Benutzer ohne gültige Rolle"
    elif art == "fahrer":
        if not _pflicht_text(e, "benutzer", "display_name", "contact_value") or e.get("contact_channel") not in KONTAKTKANAELE:
            return "QUELLE_PFLICHTFELD", "Fahrer braucht benutzer, display_name, contact_channel und contact_value"
        for qa in e.get("qualifikationen", []):
            if not isinstance(qa, dict) or _zeit(qa.get("valid_from")) is False or not _pflicht_text(qa, "qualification_code", "decision"):
                return "QUELLE_PFLICHTFELD", "Qualifikation braucht qualification_code, decision und valid_from"
    elif art == "konfiguration":
        if not isinstance(e.get("values"), dict) or not e["values"] or _zeit(e.get("effective_from")) is False or not _pflicht_text(e, "reason"):
            return "QUELLE_PFLICHTFELD", "Konfiguration braucht values, effective_from und reason"
    return None


def _pruefe_referenzen(art, e, d, vorhanden, abgelehnt, gebiete):
    for feld, ziel, optional in REFERENZEN.get(art, ()):
        wert = e.get(feld)
        if wert is None:
            if optional:
                continue
            return "REFERENZ_FEHLT", f"Verweis {feld} fehlt"
        for ref in (wert if isinstance(wert, list) else [wert]):
            if (ziel, ref) in abgelehnt:
                return "ABHAENGIG_ABGELEHNT", f"Verweis {feld} zeigt auf einen abgelehnten Datensatz ({ziel})"
            if ref not in vorhanden[ziel]:
                return "REFERENZ_UNBEKANNT", f"Verweis {feld} zeigt auf keinen Datensatz der Quelle ({ziel})"
    if art == "haltepunkte":
        g = gebiete[e["gebiet"]]
        if not geometrie.punkt_in_multipolygon(e["point"]["latitude"], e["point"]["longitude"], g["area"]):
            return "HALTEPUNKT_AUSSERHALB_GEBIET", "Haltepunkt liegt außerhalb des Bediengebiets"
    if art == "fahrer":
        u = next((x for x in d["benutzer"] if x.get("key") == e["benutzer"]), None)
        if u is not None and u.get("rolle") != "driver":
            return "FAHRER_OHNE_FAHRERROLLE", "Der zugehörige Benutzer hat nicht die Rolle driver"
    if art == "fahrzeuge" and e.get("gebiet") is not None:
        g = gebiete.get(e["gebiet"])
        if g is not None and g["dienst"] != e["dienst"]:
            return "FAHRZEUG_GEBIET_ANDERER_DIENST", "Das Gebiet gehört zu einem anderen Dienst als dem des Fahrzeugs"
    return None
