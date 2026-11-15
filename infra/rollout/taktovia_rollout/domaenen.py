"""Trennung der Zugangsdaten je Umgebung (WP-M5-28 AK 3, WP-M1-40): ein Secret, Datenbankzugang, Token oder PSP-Konto einer anderen Umgebung wird abgewiesen.

Jede Kennung wird anhand der Umgebungsmatrix (`infra/umgebungsmatrix.json`) ihrer Umgebung zugeordnet. Unbekannte Kennungen werden abgewiesen (fail closed)."""
from __future__ import annotations

import base64
import json
import re
from pathlib import Path

MATRIX = Path(__file__).resolve().parents[2] / "umgebungsmatrix.json"

ARTEN = {  # Art -> Pfad in der Matrix
    "secret": ("secrets", "domaene"),
    "idp_domaene": ("identitaet", "domaene"),
    "issuer": ("identitaet", "issuer"),
    "psp": ("psp", "domaene"),
    "konto": ("konto",),
    "netz": ("netz",),
    "db_laufzeit": ("datenbank", "laufzeitzugang"),
    "db_migration": ("datenbank", "migrationszugang"),
    "db_host": ("datenbank", "host"),
}


class Abgewiesen(Exception):
    """Ein Vorgang wurde aus einem benannten Grund abgewiesen; `grund` ist maschinenlesbar."""

    def __init__(self, grund: str, detail: str = "") -> None:
        super().__init__(f"{grund}: {detail}" if detail else grund)
        self.grund, self.detail = grund, detail


def lade_matrix(path: Path = MATRIX) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))["umgebungen"]


def _wert(env: dict, pfad: tuple) -> str:
    for p in pfad:
        env = env[p]
    return env


def eigentuemer(art: str, kennung: str, matrix: dict | None = None) -> str | None:
    """Umgebung, der die Kennung gehört (oder None, wenn keine sie kennt)."""
    if art not in ARTEN:
        raise ValueError(f"unbekannte Art {art!r}")
    matrix = matrix or lade_matrix()
    for name, env in matrix.items():
        if _wert(env, ARTEN[art]) == kennung:
            return name
    return None


def pruefe_zugang(ziel: str, art: str, kennung: str, matrix: dict | None = None) -> None:
    """Weist ab, wenn die Kennung nicht zur Zielumgebung gehört (auch Production -> Staging)."""
    herkunft = eigentuemer(art, kennung, matrix)
    if herkunft is None:
        raise Abgewiesen("unbekannte_kennung", f"{art} {kennung!r} gehört zu keiner Umgebung der Matrix")
    if herkunft != ziel:
        raise Abgewiesen("fremde_umgebung", f"{art} {kennung!r} gehört zu {herkunft}, Ziel ist {ziel}")


def jwt_claims(token: str) -> dict:
    """Liest die Ansprüche eines JWT OHNE Signaturprüfung (nur zur Herkunftsprüfung; die Signaturprüfung macht die Anwendung gegen die JWKS der eigenen Domäne)."""
    teile = token.split(".")
    if len(teile) != 3 or not re.fullmatch(r"[A-Za-z0-9_-]+", teile[1]):
        raise Abgewiesen("kein_token", "kein JWT")
    try:
        return json.loads(base64.urlsafe_b64decode(teile[1] + "=" * (-len(teile[1]) % 4)))
    except (ValueError, json.JSONDecodeError) as e:
        raise Abgewiesen("kein_token", "Ansprüche nicht lesbar") from e


def pruefe_token(ziel: str, token: str, matrix: dict | None = None) -> dict:
    """Ein Token ist nur zulässig, wenn Issuer und Audience der Identitätsdomäne der Zielumgebung entsprechen."""
    c = jwt_claims(token)
    pruefe_zugang(ziel, "issuer", str(c.get("iss", "")), matrix)
    matrix = matrix or lade_matrix()
    aud = c.get("aud")
    aud = aud if isinstance(aud, list) else [aud]
    if matrix[ziel]["identitaet"]["audience"] not in aud:
        raise Abgewiesen("falsche_audience", f"aud {aud}")
    return c
