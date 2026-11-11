"""Hilfsbibliothek fuer Tests: Tokens aus dem lokalen Keycloak beziehen und Claims pruefen (WP-M0-09).

Nur Standardbibliothek. Der Tokenbezug (get_user_token/get_client_token) braucht einen laufenden Keycloak und
wurde in der Entwicklungsumgebung NICHT ausgefuehrt (kein Docker). decode_claims/check_claims sind offline testbar.
Die Signatur wird hier NICHT geprueft (nur Testhilfe; Pruefung ist Aufgabe des Gateways, M1).
"""
import base64
import json
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:18080"
REALM = "taktovia-dev"


def _post_token(form: dict, base: str = BASE, realm: str = REALM) -> dict:
    req = urllib.request.Request(f"{base}/realms/{realm}/protocol/openid-connect/token",
                                 data=urllib.parse.urlencode(form).encode(), method="POST")
    with urllib.request.urlopen(req, timeout=10) as r:  # HTTPError bei falschem Passwort/Secret = kein Token
        return json.load(r)


def get_user_token(username: str, password: str, client_id: str = "operator-web", **kw) -> str:
    return _post_token({"grant_type": "password", "client_id": client_id, "username": username, "password": password}, **kw)["access_token"]


def get_client_token(client_id: str, secret: str, **kw) -> str:
    return _post_token({"grant_type": "client_credentials", "client_id": client_id, "client_secret": secret}, **kw)["access_token"]


def decode_claims(token: str) -> dict:
    payload = token.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))


def check_claims(claims: dict, issuer: str, audience: str, now: float | None = None) -> list[str]:
    """Liefert eine Liste von Fehlern (leer = ok): Issuer, Audience, Ablauf."""
    now = time.time() if now is None else now
    errs = []
    if claims.get("iss") != issuer:
        errs.append(f"issuer {claims.get('iss')!r} != {issuer!r}")
    aud = claims.get("aud")
    if audience not in (aud if isinstance(aud, list) else [aud]):
        errs.append(f"audience {aud!r} enthaelt {audience!r} nicht")
    if not isinstance(claims.get("exp"), (int, float)) or claims["exp"] <= now:
        errs.append("Token abgelaufen oder ohne exp")
    return errs
