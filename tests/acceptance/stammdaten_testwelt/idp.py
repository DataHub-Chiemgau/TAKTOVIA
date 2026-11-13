"""Lokaler JWT-Test-Issuer mit JWKS-Endpunkt (Ersatz für Keycloak; kein Docker). Signiert echte RS256-Tokens mit einem echten Schlüssel;
die Anwendung holt das JWKS per HTTP wie vom echten IdP. Nur für Tests, kein Nachweis gegen einen echten Keycloak."""
import base64
import json
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding, rsa

REALM = "taktovia-stammdaten"
AUDIENCE = "taktovia-api"


def _b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def _int(n: int) -> str:
    return _b64(n.to_bytes((n.bit_length() + 7) // 8, "big"))


class TestIdp:
    def __init__(self):
        self.key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        nums = self.key.public_key().public_numbers()
        self.kid = "stammdaten-key-1"
        self._jwks = json.dumps({"keys": [{"kty": "RSA", "use": "sig", "alg": "RS256", "kid": self.kid, "n": _int(nums.n), "e": _int(nums.e)}]}).encode()
        idp = self

        class H(BaseHTTPRequestHandler):
            def do_GET(self):  # noqa: N802
                if self.path.endswith("/protocol/openid-connect/certs"):
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(idp._jwks)))
                    self.end_headers()
                    self.wfile.write(idp._jwks)
                else:
                    self.send_response(404)
                    self.end_headers()

            def log_message(self, *a):
                pass

        self._server = ThreadingHTTPServer(("127.0.0.1", 0), H)
        self.port = self._server.server_address[1]
        threading.Thread(target=self._server.serve_forever, daemon=True).start()

    @property
    def issuer(self) -> str:
        return f"http://127.0.0.1:{self.port}/realms/{REALM}"

    @property
    def jwks_uri(self) -> str:
        return self.issuer + "/protocol/openid-connect/certs"

    def close(self):
        self._server.shutdown()
        self._server.server_close()

    def token(self, subject, roles=("tenant_admin",), acr="loa2", lifetime=840) -> str:
        """Menschen-Token mit zweitem Faktor (privilegierte Rollen verlangen ihn)."""
        now = int(time.time())
        claims = {"iss": self.issuer, "sub": str(subject), "aud": [AUDIENCE], "exp": now + lifetime, "iat": now, "azp": "operator-web",
                  "principal_type": "human", "acr": acr, "sid": "sid-" + str(uuid.uuid4()), "scope": "openid profile",
                  "roles": list(roles), "tenant_ids": []}
        if acr == "loa2":
            claims["amr"] = ["pwd", "otp"]
        return self.sign(claims)

    def sign(self, claims: dict) -> str:
        header = {"alg": "RS256", "typ": "JWT", "kid": self.kid}
        signing_input = _b64(json.dumps(header).encode()) + "." + _b64(json.dumps(claims).encode())
        sig = self.key.sign(signing_input.encode(), padding.PKCS1v15(), hashes.SHA256())
        return signing_input + "." + _b64(sig)
