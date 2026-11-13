"""Schlanker HTTP-Client der Anwendungs-API (nur Standardbibliothek). Der Client führt Idempotency-Key und If-Match, fasst Fehlerantworten in
`ApiFehler` zusammen und gibt nie Antworttexte weiter, die Personendaten enthalten könnten (nur Code, Status und Feldverweise)."""
import hashlib
import json
import re
import time
import urllib.error
import urllib.request
import uuid


class ApiFehler(Exception):
    """Fehlerantwort der Anwendung; `grund` enthält nur Status, Fehlercode und Feldverweise, nie die Detailtexte der Antwort."""

    def __init__(self, status, code, felder=(), method="", pfad=""):
        self.status, self.code, self.felder, self.method, self.pfad = status, code, tuple(felder), method, pfad
        super().__init__(self.grund)

    @property
    def grund(self) -> str:
        teile = [f"HTTP {self.status}", self.code or "ohne Code"]
        if self.felder:
            teile.append("Felder: " + ", ".join(self.felder))
        return "; ".join(teile)


class Antwort:
    def __init__(self, status, headers, body):
        self.status, self.headers, self.body = status, headers, body

    @property
    def etag_zahl(self):
        e = self.headers.get("ETag")
        return int(e.strip('"')) if e and e.strip('"').isdigit() else None


_FELD = re.compile(r"^[A-Za-z0-9_./\[\]-]{1,120}$")


def _felder(body) -> list:
    """Nur strukturelle Feldverweise (JSON-Pointer) aus `errors`/`violations`, nie Werte."""
    out = []
    if isinstance(body, dict):
        for key in ("errors", "violations", "invalid_params"):
            for e in body.get(key) or []:
                if isinstance(e, dict):
                    p = e.get("pointer") or e.get("field") or e.get("path")
                    if isinstance(p, str) and _FELD.match(p):
                        out.append(p)
    return out[:10]


class ApiClient:
    def __init__(self, base_url, timeout=60):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def anfrage(self, methode, pfad, token, body=None, if_match=None, idem_key=None, wiederholen=3) -> Antwort:
        daten = None if body is None else json.dumps(body).encode()
        headers = {"Authorization": f"Bearer {token}", "Accept": "application/json"}
        if daten is not None:
            headers["Content-Type"] = "application/json"
        if methode != "GET":
            headers["Idempotency-Key"] = idem_key or "imp-" + _ohne_ziffern(uuid.uuid4().hex)
        if if_match is not None:
            headers["If-Match"] = f'"{if_match}"'
        for versuch in range(wiederholen):
            req = urllib.request.Request(self.base_url + pfad, data=daten, method=methode, headers=headers)
            try:
                with urllib.request.urlopen(req, timeout=self.timeout) as r:
                    raw = r.read()
                    return Antwort(r.status, dict(r.headers), json.loads(raw) if raw else None)
            except urllib.error.HTTPError as e:
                raw = e.read()
                try:
                    body_j = json.loads(raw) if raw else None
                except ValueError:
                    body_j = None
                if e.code in (429, 503) and versuch + 1 < wiederholen:
                    time.sleep(min(int(e.headers.get("Retry-After", "1") or 1), 5))
                    continue
                code = body_j.get("code") if isinstance(body_j, dict) else None
                raise ApiFehler(e.code, code, _felder(body_j), methode, pfad) from None
        raise AssertionError("unerreichbar")

    def get(self, pfad, token):
        return self.anfrage("GET", pfad, token)

    def seiten(self, pfad, token, limit=100):
        """Alle Elemente einer Cursor-Liste."""
        sep = "&" if "?" in pfad else "?"
        cursor = None
        while True:
            p = f"{pfad}{sep}limit={limit}" + (f"&cursor={cursor}" if cursor else "")
            r = self.get(p, token)
            yield from r.body.get("items", [])
            cursor = r.body.get("next_cursor")
            if not cursor:
                return


_BUCHSTABEN = str.maketrans("0123456789", "ghijklmnop")


def _ohne_ziffern(hexwert: str) -> str:
    """Die Anwendung weist Eingabewerte mit kartenartigen Ziffernfolgen ab (CardDataGuard); Schlüssel enthalten deshalb keine Ziffern."""
    return hexwert.translate(_BUCHSTABEN)


def idem_key(quellpruefsumme: str, art: str, key: str, schritt: str = "") -> str:
    """Deterministischer Idempotenzschlüssel je Datensatz: Wiederholung desselben Schritts derselben Quelle liefert dieselbe Antwort."""
    h = hashlib.sha256(f"{quellpruefsumme}|{art}|{key}|{schritt}".encode()).hexdigest()
    return "imp-" + _ohne_ziffern(h[:48])
