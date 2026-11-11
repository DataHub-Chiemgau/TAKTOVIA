"""Offline-Tests der Token-Hilfsbibliothek (kein Keycloak noetig). Lauf: python3 -m unittest discover infra/keycloak"""
import base64
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import token_helper as th  # noqa: E402


def fake_token(claims):
    b = lambda d: base64.urlsafe_b64encode(json.dumps(d).encode()).rstrip(b"=").decode()
    return f"{b({'alg': 'none'})}.{b(claims)}.sig"


class T(unittest.TestCase):
    ISS = "http://127.0.0.1:18080/realms/taktovia-dev"

    def test_ok(self):
        c = th.decode_claims(fake_token({"iss": self.ISS, "aud": ["taktovia-api"], "exp": 2000}))
        self.assertEqual(th.check_claims(c, self.ISS, "taktovia-api", now=1000), [])

    def test_wrong_audience_expired_issuer(self):
        c = th.decode_claims(fake_token({"iss": "x", "aud": "andere", "exp": 500}))
        self.assertEqual(len(th.check_claims(c, self.ISS, "taktovia-api", now=1000)), 3)


if __name__ == "__main__":
    unittest.main()
