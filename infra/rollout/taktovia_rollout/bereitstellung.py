"""Bereitstellungsverfahren (WP-M5-28; Ablauf nach infra/deploy/ablauf.md): Sperre, Artefaktprüfung, Zugangstrennung, Migration über den Migrationszugang, gestaffeltes Rollout.

Das Verfahren läuft für Staging und Production gleich. Es führt nichts aus, was nicht über `Ziel` und `Migrator` angeboten wird; die Anbindung an einen Hostinganbieter fehlt (D-05)."""
from __future__ import annotations

import fcntl
import json
import sys
from contextlib import contextmanager
from pathlib import Path
from typing import Protocol

from . import datenbank, domaenen
from .domaenen import Abgewiesen
from .stufen import gestaffelt_ausrollen

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / "tools" / "release_gate"))
import gate  # noqa: E402


class Migrator(Protocol):
    zugang: str          # Name des Migrationszugangs der Umgebung
    rolle: str           # Datenbankrolle, unter der die Migration läuft

    def migrieren(self) -> None: ...
    def migrationsstand(self) -> dict: ...


@contextmanager
def sperre(pfad: Path):
    """Serialisierung: genau eine Bereitstellung je Umgebung; die zweite wird abgewiesen, nicht verschränkt."""
    pfad.parent.mkdir(parents=True, exist_ok=True)
    fh = open(pfad, "w")
    try:
        try:
            fcntl.flock(fh, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as e:
            raise Abgewiesen("sperre_belegt", "eine Bereitstellung für diese Umgebung läuft bereits") from e
        yield
    finally:
        fh.close()


def pruefe_artefakt(verzeichnis: Path, cfg: dict | None = None) -> dict:
    """Weist ein unsigniertes, manipuliertes oder nicht vollständig bestandenes Artefakt ab (AK 2) und liefert das Manifest."""
    funde = gate.verify_release(Path(verzeichnis), cfg)
    if funde:
        raise Abgewiesen("artefakt_abgewiesen", "; ".join(funde))
    return json.loads((Path(verzeichnis) / gate.MANIFEST).read_text(encoding="utf-8"))


def bereitstellen(umgebung: str, artefakt: Path, *, plan: dict, ziel, migrator: Migrator, zugaenge: list[tuple[str, str]], staging_stand: dict | None,
                  sperrdatei: Path, cfg: dict | None = None, warte=lambda s: None) -> dict:
    """Stellt das Artefakt in `umgebung` bereit und gibt das Bereitstellungsprotokoll zurück. Abweisungen werfen `Abgewiesen`, bevor etwas verändert wurde."""
    if umgebung not in plan["umgebungen"]:
        raise Abgewiesen("umgebung_nicht_im_plan", umgebung)
    with sperre(sperrdatei):
        manifest = pruefe_artefakt(artefakt, cfg)                                   # 1. Signatur und Release-Gate
        for art, kennung in zugaenge:                                                # Zugangsdaten gehören zur Zielumgebung
            domaenen.pruefe_zugang(umgebung, art, kennung)
        domaenen.pruefe_zugang(umgebung, "db_migration", migrator.zugang)
        if migrator.rolle == "taktovia_runtime":                                     # 2. Migration nie mit der Runtime-Rolle
            raise Abgewiesen("migration_mit_laufzeitrolle", "Schemaänderungen laufen nur über den kurzlebigen Migrationszugang")
        migrator.migrieren()
        stand = migrator.migrationsstand()
        if staging_stand is not None and stand["sha256"] != staging_stand["sha256"]:  # AK 1: gleicher Migrationsstand wie Staging
            raise Abgewiesen("migrationsstand_abweichend", f"{stand['sha256'][:12]} != Staging {staging_stand['sha256'][:12]}")
        protokoll = gestaffelt_ausrollen(plan, ziel, manifest["build_id"], warte)    # 3./4. Start je Stufe mit Rauchprobe
        protokoll.update(umgebung=umgebung, migrationsstand=stand, artefakt_signatur_geprueft=True)
        return protokoll
