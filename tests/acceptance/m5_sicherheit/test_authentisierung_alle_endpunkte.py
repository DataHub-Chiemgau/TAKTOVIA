"""WP-M5-17 (AK 1: „kein Endpunkt ohne Negativtest“, Penetrationstest-Ersatzlauf, nicht der unabhängige Test): Authentisierungsangriffe auf JEDEN gebauten Endpunkt der OpenAPI-Verträge.

Echte Anwendung (Spring-Boot-Jar, Runtime-Rolle, echte Datenbank) mit eingeschaltetem Ressourcenserver gegen einen lokalen JWT-Test-Issuer (RS256 mit JWKS, Ersatz für Keycloak, NICHT
ausgeführt: echter IdP). Je Operation (gültige Anfrage aus dem Schemagenerator `attack_catalog.valid_requests`) werden fünf Angriffe gefahren: ohne Token, Müll-Token, mit fremdem Schlüssel
signiertes Token, abgelaufenes Token, Token mit falscher Zielgruppe, `alg: none`. Erwartung: 401 als `application/problem+json` ohne interne Details, nie 2xx, nie 5xx.
Zusätzlich: gültiges Token eines Subjects ohne jede Mitgliedschaft liefert nirgends Erfolg außer an den ausdrücklich begründeten Selbstregistrierungswegen.
Der Wächter prüft die Vollständigkeit: jede gebaute Operation (nicht in `api/contract-pending.txt`) kommt im Lauf vor.
Voraussetzung: `cd backend && ./gradlew :app:bootJar`; DB-Umgebung aus db/db.py; ohne Jar übersprungen (mit TAKTOVIA_REQUIRE_APP_JAR=true Fehlschlag).
"""
import base64
import http.client
import json
import os
import re
import sys
import time
import unittest
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
ACC = HERE.parent
REPO = ACC.parents[1]
sys.path.insert(0, str(ACC)); sys.path.insert(0, str(ACC / "security")); sys.path.insert(0, str(REPO / "api" / "tools"))
import attack_catalog as ac  # noqa: E402
from bundle import bundle  # noqa: E402
from harness import INTERNAL  # noqa: E402
from stammdaten_testwelt.app import JAR, AppProcess  # noqa: E402
from stammdaten_testwelt.idp import AUDIENCE, TestIdp, _b64  # noqa: E402

DOC = bundle()
PENDING = {l.split("#")[0].strip() for l in (REPO / "api/contract-pending.txt").read_text(encoding="utf-8").splitlines() if l.split("#")[0].strip()}
SIGNATUR = {"receivePartnerEvent", "receiveProviderEvent", "receivePaymentProviderEvent"}   # Signatur statt Bearer-Token (eigene Prüfung unten)
# Selbstregistrierung: ein gültiges Token ohne Mitgliedschaft ist der vorgesehene Eingang (Fahrgast legt sein Konto an, Kap. 01 §1.7 / WP-M1-28)
OHNE_MITGLIEDSCHAFT_ERLAUBT = {"createPassenger", "getMe", "listMySessions", "revokeAllMySessions"}
APP = IDP = OTHER = None
RESULTS = []   # (operationId, angriff, status, content_type, body)
PROBE = []     # (operationId, status, body)


def claims(idp, **over):
    now = int(time.time())
    c = {"iss": idp.issuer, "sub": str(uuid.uuid4()), "aud": [AUDIENCE], "exp": now + 840, "iat": now, "azp": "operator-web", "principal_type": "human", "acr": "loa2",
         "amr": ["pwd", "otp"], "sid": "sid-" + str(uuid.uuid4()), "scope": "openid profile", "roles": ["tenant_admin"], "tenant_ids": []}
    c.update(over)
    return c


def alg_none(c):
    return _b64(json.dumps({"alg": "none", "typ": "JWT"}).encode()) + "." + _b64(json.dumps(c).encode()) + "."


def send(method, path, body, headers):
    conn = http.client.HTTPConnection("127.0.0.1", APP.port, timeout=30)
    try:
        hs = dict(headers)
        data = body.encode() if isinstance(body, str) else body
        if data is not None:
            hs.setdefault("Content-Type", "application/json")
        conn.request(method, path, body=data, headers=hs)
        r = conn.getresponse()
        return r.status, r.getheader("Content-Type") or "", r.read().decode("utf-8", "replace")
    finally:
        conn.close()


def setUpModule():
    global APP, IDP, OTHER
    if not JAR.exists():
        if os.environ.get("TAKTOVIA_REQUIRE_APP_JAR") == "true":
            raise AssertionError(f"{JAR} fehlt (TAKTOVIA_REQUIRE_APP_JAR=true): cd backend && ./gradlew :app:bootJar")
        raise unittest.SkipTest(f"{JAR} fehlt: cd backend && ./gradlew :app:bootJar (nicht ausgeführt)")
    if not os.environ.get("TAKTOVIA_DB_RUNTIME_PASSWORD"):
        if os.environ.get("TAKTOVIA_REQUIRE_DB") == "true":
            raise AssertionError("Datenbank erforderlich (TAKTOVIA_REQUIRE_DB=true)")
        raise unittest.SkipTest("TAKTOVIA_DB_RUNTIME_PASSWORD nicht gesetzt: nicht ausgeführt")
    IDP, OTHER = TestIdp(), TestIdp()
    OTHER.kid = IDP.kid            # gleiche Schlüsselkennung, anderer Schlüssel: Fälschung mit „richtiger“ kid
    APP = AppProcess(os.environ.get("TAKTOVIA_DB_NAME", "taktovia"), IDP)
    reqs = [r for r in ac.valid_requests(DOC) if r[0] not in PENDING and r[0] not in SIGNATUR]
    foreign_c = lambda: claims(IDP)  # noqa: E731
    for oid, method, path, body, headers in reqs:
        base = {k: v for k, v in headers.items() if k.lower() != "authorization"}
        angriffe = {
            "ohne-token": {},
            "muell-token": {"Authorization": "Bearer not.a.jwt"},
            "fremder-schluessel": {"Authorization": "Bearer " + OTHER.sign(foreign_c())},
            "abgelaufen": {"Authorization": "Bearer " + IDP.sign(claims(IDP, exp=int(time.time()) - 120, iat=int(time.time()) - 1000))},
            "falsche-zielgruppe": {"Authorization": "Bearer " + IDP.sign(claims(IDP, aud=["anderer-dienst"]))},
            "alg-none": {"Authorization": "Bearer " + alg_none(claims(IDP))},
        }
        for name, h in angriffe.items():
            s, ct, b = send(method, path, body, {**base, **h})
            RESULTS.append((oid, name, s, ct, b))
        s, ct, b = send(method, path, body, {**base, "Authorization": "Bearer " + IDP.sign(claims(IDP, roles=[], tenant_ids=[]))})
        PROBE.append((oid, s, b))


def tearDownModule():
    if APP:
        APP.close()
    for i in (IDP, OTHER):
        if i:
            i.close()


class Authentisierung(unittest.TestCase):
    def test_waechter_jede_gebaute_operation_wurde_angegriffen(self):
        gebaut = {op["operationId"] for _, _, op in ac.operations(DOC) if op["operationId"] not in PENDING and op["operationId"] not in SIGNATUR}
        angegriffen = {r[0] for r in RESULTS}
        self.assertEqual(gebaut - angegriffen, set(), "Operationen ohne Authentisierungs-Negativtest")
        self.assertGreater(len(gebaut), 250)
        print(f"\nAuthentisierungsangriffe: {len(RESULTS)} Anfragen gegen {len(angegriffen)} Operationen (6 Angriffsarten)")

    def test_alle_angriffe_ergeben_401_problem_json_ohne_interne_details(self):
        falsch = []
        for oid, name, status, ct, body in RESULTS:
            if status != 401:
                falsch.append(f"{oid} {name}: {status}")
            elif not ct.startswith("application/problem+json") or INTERNAL.search(body):
                falsch.append(f"{oid} {name}: Antwortform/Details: {ct} {body[:120]}")
        self.assertEqual(falsch[:15], [], f"{len(falsch)} Angriffe nicht wie erwartet abgewiesen")

    def test_gueltiges_token_ohne_mitgliedschaft_liefert_nirgends_erfolg_ausser_selbstregistrierung(self):
        erfolge = [f"{oid}: {s}" for oid, s, _ in PROBE if 200 <= s < 300 and oid not in OHNE_MITGLIEDSCHAFT_ERLAUBT]
        fehler5 = [f"{oid}: {s}" for oid, s, _ in PROBE if s >= 500]
        self.assertEqual(erfolge, [])
        self.assertEqual(fehler5, [])
        self.assertTrue(any(s in (403, 404, 401) for _, s, _ in PROBE))

    def test_webhook_eingang_des_psp_weist_fehlende_und_falsche_signatur_ab(self):
        pfad = "/v1/payments/provider-events/simulator"
        koerper = json.dumps({"event_id": "evt-" + str(uuid.uuid4()), "type": "payment.succeeded"})
        for name, h in {"ohne-signatur": {}, "falsche-signatur": {"X-Signature": "sha256=" + "0" * 64, "Stripe-Signature": "t=1,v1=" + "0" * 64},
                        "muell-signatur": {"X-Signature": "zz", "Stripe-Signature": "zz"}}.items():
            s, ct, b = send("POST", pfad, koerper, {"Idempotency-Key": "idempotency-key-0123456789", **h})
            self.assertTrue(400 <= s < 500, f"{name}: {s} {b[:150]}")
            self.assertFalse(INTERNAL.search(b), name)
        s, _, _ = send("POST", "/v1/payments/provider-events/gibt-es-nicht", koerper, {"Idempotency-Key": "idempotency-key-0123456789"})
        self.assertTrue(400 <= s < 500)


if __name__ == "__main__":
    unittest.main()
