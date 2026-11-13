"""Lokaler JWT-Test-Issuer mit JWKS-Endpunkt (Ersatz für Keycloak, nicht ausgeführt: echter IdP). Signiert echte RS256-Tokens; die Anwendung holt das JWKS per HTTP.
Der Schlüssel wird je Prozess erzeugt und nie gespeichert (keine Secrets im Repository)."""
from __future__ import annotations

import base64
import json
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding, rsa


def _b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def _int_b64(n: int) -> str:
    return _b64(n.to_bytes((n.bit_length() + 7) // 8, "big"))


class TestIdp:
    __test__ = False  # kein Testfall für unittest/pytest
    REALM = "taktovia-load"
    AUDIENCE = "taktovia-api"
    KID = "load-key-1"

    def __init__(self) -> None:
        self._cache: dict = {}
        self._key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        pub = self._key.public_key().public_numbers()
        self._jwks = json.dumps({"keys": [{"kty": "RSA", "use": "sig", "alg": "RS256", "kid": self.KID, "n": _int_b64(pub.n), "e": _int_b64(pub.e)}]}).encode()
        idp = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):  # noqa: N802
                if self.path.endswith("/certs"):
                    self.send_response(200); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(idp._jwks))); self.end_headers()
                    self.wfile.write(idp._jwks)
                else:
                    self.send_response(404); self.end_headers()

            def log_message(self, *args):  # still
                pass

        self._server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = self._server.server_address[1]
        threading.Thread(target=self._server.serve_forever, daemon=True).start()

    @property
    def issuer(self) -> str:
        return f"http://127.0.0.1:{self.port}/realms/{self.REALM}"

    @property
    def jwks_uri(self) -> str:
        return f"{self.issuer}/protocol/openid-connect/certs"

    def token(self, subject: str | None = None, lifetime: int = 300, acr: str = "loa2", sid: str | None = None) -> str:
        """Menschen-Token mit zweitem Faktor (privilegierte Rollen verlangen ihn), wie `TestIdp.humanClaims` im Backend-Test."""
        now = int(time.time())
        sub = subject or str(uuid.uuid4())
        key = (sub, sid, acr, lifetime)
        hit = self._cache.get(key)
        if subject is not None and hit and now - hit[0] < lifetime - 60:          # Signatur kostet CPU: Token je Konto bis kurz vor Ablauf wiederverwenden
            return hit[1]
        claims = {"iss": self.issuer, "sub": sub, "aud": [self.AUDIENCE], "exp": now + lifetime, "iat": now, "azp": "operator-web", "principal_type": "human", "acr": acr,
                  "sid": sid or "sid-" + str(uuid.uuid4()), "scope": "openid profile", "roles": ["passenger"], "tenant_ids": []}
        if acr == "loa2":
            claims["amr"] = ["pwd", "otp"]
        head = {"alg": "RS256", "typ": "JWT", "kid": self.KID}
        signing = f"{_b64(json.dumps(head, separators=(',', ':')).encode())}.{_b64(json.dumps(claims, separators=(',', ':')).encode())}"
        sig = self._key.sign(signing.encode(), padding.PKCS1v15(), hashes.SHA256())
        tok = f"{signing}.{_b64(sig)}"
        if subject is not None:
            self._cache[key] = (now, tok)
        return tok

    def close(self) -> None:
        self._server.shutdown()
