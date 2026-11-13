"""Migrationsprotokoll: Dateiartefakt je Lauf (JSON). Enthält Zählwerte, Quellschlüssel und Kennungen, keine Kontaktdaten und keine Datenwerte der Quelle."""
import json
import re
from pathlib import Path

PROTOKOLL_VERSION = 1
_EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
_TELEFON = re.compile(r"(?<![\w.-])\+?\d[\d ()/-]{7,}\d(?![\w.-])")


def schwaerzen(text: str, geheim=()) -> str:
    """Entfernt bekannte Personendatenwerte der Quelle sowie E-Mail-Adressen und Telefonnummern aus Text (zweite Sicherung; der Aufbau des Protokolls nennt sie nie)."""
    for wert in sorted({g for g in geheim if g}, key=len, reverse=True):
        text = text.replace(wert, "[entfernt]")
    text = _EMAIL.sub("[entfernt]", text)
    return _TELEFON.sub("[entfernt]", text)


def schreiben(pfad, protokoll: dict, geheim=()):
    text = schwaerzen(json.dumps(protokoll, indent=2, ensure_ascii=False, sort_keys=False), geheim)
    p = Path(pfad)
    p.parent.mkdir(parents=True, exist_ok=True)
    tmp = p.with_suffix(p.suffix + ".tmp")
    tmp.write_text(text + "\n", encoding="utf-8")
    tmp.replace(p)
    return json.loads(text)


def zusammenfassung(protokoll: dict) -> str:
    zeilen = [f"Ergebnis: {protokoll['lauf']['ergebnis']} (Modus {protokoll['lauf']['modus']}, Quelle {protokoll['quelle']['sha256'][:12]})",
              f"{'Objektart':<16}{'Soll':>6}{'Ist':>6}{'angelegt':>10}{'vorhanden':>10}{'abgelehnt':>10}"]
    for art, b in protokoll["bestaende"].items():
        angelegt = b.get("angelegt", b.get("wuerde_anlegen", 0))
        vorhanden = b.get("vorhanden", b.get("bereits_vorhanden", 0))
        zeilen.append(f"{art:<16}{b['soll']:>6}{b.get('ist', '-'):>6}{angelegt:>10}{vorhanden:>10}{b['abgelehnt']:>10}")
    zeilen.append(f"Fehlerliste: {len(protokoll['fehlerliste'])} Einträge")
    return "\n".join(zeilen)
