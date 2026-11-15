"""Betriebszugriff auf Production (WP-M5-28 AK 6): personengebunden, mit MFA, protokolliert, kein geteilter Administratorzugang; Notfallzugang mit Vier-Augen-Freigabe und Ablauf."""
from __future__ import annotations

import datetime as dt
import fcntl
import hashlib
import json
import threading
from pathlib import Path

from .domaenen import Abgewiesen

KONZEPT = Path(__file__).resolve().parents[1] / "betriebszugriff.json"


def lade_konzept(path: Path = KONZEPT) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))["regeln"]


class Zugriffsprotokoll:
    """Nur anfügbares, verkettetes Protokoll (JSON Lines): jeder Eintrag trägt den Hash des Vorgängers; eine nachträgliche Änderung bricht die Kette."""

    _lock = threading.Lock()

    def __init__(self, path: Path) -> None:
        self.path = Path(path)

    def _letzter_hash(self) -> str:
        if not self.path.is_file() or not self.path.read_text(encoding="utf-8").strip():
            return "0" * 64
        return json.loads(self.path.read_text(encoding="utf-8").strip().splitlines()[-1])["hash"]

    def anfuegen(self, eintrag: dict) -> dict:
        """Hängt einen Eintrag an. Serialisiert über Prozesssperre und Dateisperre (mehrere Betriebswerkzeuge, mehrere Prozesse): die Kette verzweigt nicht."""
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self._lock, self.path.open("a+", encoding="utf-8") as fh:
            fcntl.flock(fh, fcntl.LOCK_EX)
            try:
                eintrag = dict(eintrag, vorgaenger=self._letzter_hash())
                eintrag["hash"] = hashlib.sha256(json.dumps(eintrag, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
                fh.write(json.dumps(eintrag, ensure_ascii=False) + "\n")
                fh.flush()
            finally:
                fcntl.flock(fh, fcntl.LOCK_UN)
        return eintrag

    def pruefen(self) -> list[str]:
        befunde, vorher = [], "0" * 64
        if not self.path.is_file():
            return befunde
        for i, zeile in enumerate(self.path.read_text(encoding="utf-8").splitlines(), 1):
            e = json.loads(zeile)
            h = e.pop("hash")
            if e.get("vorgaenger") != vorher:
                befunde.append(f"Zeile {i}: Kette unterbrochen")
            if hashlib.sha256(json.dumps(e, sort_keys=True, ensure_ascii=False).encode()).hexdigest() != h:
                befunde.append(f"Zeile {i}: Inhalt verändert")
            vorher = h
        return befunde


def zugriff_zulassen(zugriff: dict, protokoll: Zugriffsprotokoll, konzept: dict | None = None, jetzt: dt.datetime | None = None) -> dict:
    """Prüft einen Betriebszugriff auf Production und protokolliert ihn. `zugriff`: konto, person, mfa (bool), zweck, umfang, optional notfall (dict)."""
    konzept = konzept or lade_konzept()
    jetzt = jetzt or dt.datetime.now(dt.timezone.utc)
    konto, person = str(zugriff.get("konto", "")).strip().lower(), str(zugriff.get("person", "")).strip()
    if not person:
        raise Abgewiesen("nicht_personengebunden", "keine Person benannt")
    if konto in konzept["verbotene_kontonamen"] or zugriff.get("geteilt"):
        raise Abgewiesen("geteiltes_konto", f"Konto {konto!r} ist ein geteilter Zugang")
    if konto != person.lower() and not konto.startswith(person.lower().split("@")[0]):
        raise Abgewiesen("konto_nicht_personengebunden", f"Konto {konto!r} gehört nicht zur Person {person!r}")
    if zugriff.get("mfa") is not True:
        raise Abgewiesen("ohne_mfa", f"Zugriff von {person} ohne Mehrfaktor")
    if not zugriff.get("zweck"):
        raise Abgewiesen("ohne_zweck", "Zweck fehlt")
    eintrag = {"zeit": jetzt.strftime("%Y-%m-%dT%H:%M:%SZ"), "person": person, "konto": konto, "zweck": zugriff["zweck"], "umfang": zugriff.get("umfang", ""), "art": "regulaer"}
    nf = zugriff.get("notfall")
    if nf is not None:
        notfall = konzept["notfallzugang"]
        fehlend = [f for f in notfall["pflichtfelder"] if not nf.get(f)]
        if fehlend:
            raise Abgewiesen("notfall_unvollstaendig", f"fehlt: {', '.join(fehlend)}")
        if nf["freigeber"].strip().lower() == nf["person"].strip().lower() or nf["person"].strip() != person:
            raise Abgewiesen("notfall_keine_vier_augen", "Freigeber muss eine andere Person sein als der Anforderer")
        if int(nf.get("dauer_min", notfall["hoechstdauer_min"] + 1)) > notfall["hoechstdauer_min"]:
            raise Abgewiesen("notfall_zu_lang", f"höchstens {notfall['hoechstdauer_min']} min")
        eintrag.update(art="notfall", freigeber=nf["freigeber"], begruendung=nf["begruendung"], ticket=nf["ticket"], dauer_min=int(nf["dauer_min"]))
    return protokoll.anfuegen(eintrag)
