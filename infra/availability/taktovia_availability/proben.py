"""Synthetische Proben für Kernbuchung und Operator (WP-M5-05).

Jede Probe ist ein **Lauf** aus Schritten gegen die öffentliche Schnittstelle (nur bestehende Pfade, keine neuen Endpunkte) und liefert genau ein Ergebnis:

  ok                an jedem Schritt erwarteter Statuscode und Inhalt
  fehler_plattform  Verbindungsfehler, Zeitüberschreitung, 5xx ohne Providerbezug, falscher Statuscode oder Inhalt, abgelehntes gültiges Token (401)
  fehler_provider   Problem-Code mit Providerbezug (`PROVIDER_UNAVAILABLE`): getrennt ausgewiesen, kein Plattformausfall (Messgrenze, D-05 offen)
  keine_messung     die Probe selbst ist ausgefallen: ungültige Zugangsdaten (Anmeldung 400/401/403 oder 403 am Dienst), Referenzpunkt nicht erreichbar, Ausnahme im Probenprozess;
                    wird nicht als Verfügbarkeit gewertet und löst einen Probenalarm aus

Zugangsdaten kommen aus der Umgebung (Werte der Form `env:NAME` im Zugangsblock der Konfiguration), nie aus dem Repository. Der Transport ist austauschbar (Tests,
Staging-Simulation); `urllib_transport` ist der echte HTTP-Transport.
"""
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone

OK, FEHLER_PLATTFORM, FEHLER_PROVIDER, KEINE_MESSUNG = "ok", "fehler_plattform", "fehler_provider", "keine_messung"
ERGEBNISSE = (OK, FEHLER_PLATTFORM, FEHLER_PROVIDER, KEINE_MESSUNG)
BEREICHE = ("kernbuchung", "operator")
PROVIDER_CODES = frozenset({"PROVIDER_UNAVAILABLE", "PSP_UNAVAILABLE", "PAYMENT_PROVIDER_UNAVAILABLE"})


class NetworkError(Exception):
    """Verbindung kam nicht zustande oder Zeitüberschreitung (aus Sicht der Probe zunächst unbestimmt: Plattform oder Probe, entscheidet der Referenzpunkt)."""


@dataclass
class Response:
    status: int
    headers: dict
    body: bytes = b""

    def json(self):
        try:
            return json.loads(self.body) if self.body else None
        except ValueError:
            return None


def urllib_transport(method: str, url: str, headers: dict, body: bytes | None, timeout: float) -> Response:
    req = urllib.request.Request(url, data=body, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:  # noqa: S310 (Schnittstellen-URL aus der Betriebskonfiguration)
            return Response(r.status, {k.lower(): v for k, v in r.headers.items()}, r.read())
    except urllib.error.HTTPError as e:
        return Response(e.code, {k.lower(): v for k, v in e.headers.items()}, e.read())
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        raise NetworkError(str(e)[:200]) from e


class ProbeAbbruch(Exception):
    """Lauf endet mit einem Ergebnis vor dem letzten Schritt."""

    def __init__(self, ergebnis: str, schritt: str, detail: str, http_status: int | None = None):
        super().__init__(detail)
        self.ergebnis, self.schritt, self.detail, self.http_status = ergebnis, schritt, detail, http_status


@dataclass
class Konfiguration:
    base_url: str
    token_url: str
    zugang_kernbuchung: dict                      # Formularfelder; Werte `env:NAME` werden zur Laufzeit aufgelöst
    zugang_operator: dict
    dienst_id: str = ""
    probefahrzeug_id: str = ""
    probemandant_id: str = ""
    referenz_url: str = ""                        # neutraler Referenzpunkt zur Unterscheidung Plattform-/Probenausfall (z. B. öffentliche Statusseite eines Dritten)
    timeout_s: float = 10.0
    angebot_body: dict = field(default_factory=dict)
    planbindung: object = None                    # Aufrufbar(angebot) NUR für Umgebungen ohne Kandidatensuche (Testaufbau, siehe README); in Staging mit Solver None


def aufloesen(feld: dict, umgebung=os.environ) -> dict:
    out = {}
    for k, v in feld.items():
        if isinstance(v, str) and v.startswith("env:"):
            name = v[4:]
            if not umgebung.get(name):
                raise ProbeAbbruch(KEINE_MESSUNG, "anmeldung", f"Zugangsdaten nicht gesetzt (Umgebungsvariable {name})")
            out[k] = umgebung[name]
        else:
            out[k] = v
    return out


class Lauf:
    """Ein Probenlauf: führt Schritte aus und protokolliert jeden Schritt."""

    def __init__(self, bereich: str, cfg: Konfiguration, transport, uhr=time.time, umgebung=os.environ):
        self.bereich, self.cfg, self.t, self.uhr, self.umgebung = bereich, cfg, transport, uhr, umgebung
        self.schritte: list[dict] = []
        self.token: str | None = None

    # --- Bausteine -------------------------------------------------------------------------------------------------------
    def _send(self, schritt: str, method: str, url: str, headers: dict, body: bytes | None) -> Response:
        t0 = time.monotonic()
        try:
            r = self.t(method, url, headers, body, self.cfg.timeout_s)
        except NetworkError as e:
            self.schritte.append({"schritt": schritt, "dauer_ms": round((time.monotonic() - t0) * 1000), "fehler": f"Netzwerk: {e}"})
            raise
        self.schritte.append({"schritt": schritt, "dauer_ms": round((time.monotonic() - t0) * 1000), "http_status": r.status})
        return r

    def _api(self, schritt: str, method: str, pfad: str, body=None, erwartet=(200,), if_match: str | None = None, idem: bool = False) -> Response:
        h = {"Accept": "application/json", "Authorization": f"Bearer {self.token}", "traceparent": f"00-{uuid.uuid4().hex}-{uuid.uuid4().hex[:16]}-01"}
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            h["Content-Type"] = "application/json"
        if idem:
            h["Idempotency-Key"] = "probe-" + uuid.uuid4().hex
        if if_match is not None:
            h["If-Match"] = if_match if if_match.startswith('"') else f'"{if_match}"'
        try:
            r = self._send(schritt, method, self.cfg.base_url.rstrip("/") + pfad, h, data)
        except NetworkError as e:
            raise ProbeAbbruch(FEHLER_PLATTFORM, schritt, f"Verbindungsfehler: {e}") from e
        if r.status in erwartet:
            return r
        p = r.json() if isinstance(r.json(), dict) else {}
        code = str(p.get("code", ""))
        if code in PROVIDER_CODES:
            raise ProbeAbbruch(FEHLER_PROVIDER, schritt, f"Anbieterfehler {code}", r.status)
        if r.status == 403:
            raise ProbeAbbruch(KEINE_MESSUNG, schritt, "403: Probekonto ohne Berechtigung (Konfigurationsfehler der Probe)", r.status)
        raise ProbeAbbruch(FEHLER_PLATTFORM, schritt, f"HTTP {r.status} {code}".strip(), r.status)

    def anmelden(self, zugang: dict) -> None:
        felder = aufloesen(zugang, self.umgebung)
        try:
            r = self._send("anmeldung", "POST", self.cfg.token_url, {"Content-Type": "application/x-www-form-urlencoded", "Accept": "application/json"},
                           urllib.parse.urlencode(felder).encode())
        except NetworkError as e:
            raise ProbeAbbruch(FEHLER_PLATTFORM, "anmeldung", f"Anmeldedienst nicht erreichbar: {e}") from e
        if r.status in (400, 401, 403):
            raise ProbeAbbruch(KEINE_MESSUNG, "anmeldung", f"Zugangsdaten der Probe abgelehnt (HTTP {r.status})", r.status)
        if r.status != 200 or not isinstance(r.json(), dict) or not r.json().get("access_token"):
            raise ProbeAbbruch(FEHLER_PLATTFORM, "anmeldung", f"Anmeldung liefert kein Token (HTTP {r.status})", r.status)
        self.token = r.json()["access_token"]

    # --- Läufe -----------------------------------------------------------------------------------------------------------
    def kernbuchung(self) -> None:
        c = self.cfg
        self.anmelden(c.zugang_kernbuchung)
        body = c.angebot_body or {"service_id": c.dienst_id}
        r = self._api("angebot", "POST", "/v1/ride-offers", body, (200,), idem=True)
        j = r.json() or {}
        if not j.get("offers"):
            raise ProbeAbbruch(FEHLER_PLATTFORM, "angebot", "kein Angebot erhalten (leere Angebotsliste für den Probedienst)", r.status)
        angebot = j["offers"][0]
        if c.planbindung:
            c.planbindung(angebot)
        gross = angebot.get("gross")
        r = self._api("hold", "POST", f"/v1/ride-offers/{angebot['id']}/hold", {"max_gross": gross}, (201,), if_match=str(angebot.get("revision", 1)), idem=True)
        hold = r.json() or {}
        etag = r.headers.get("etag", "1")
        buchung = None
        try:
            r = self._api("bestaetigung", "POST", "/v1/bookings", {"hold_id": hold.get("id"), "max_gross": gross}, (201,), if_match=etag, idem=True)
            buchung = r.json() or {}
            if not buchung.get("id"):
                raise ProbeAbbruch(FEHLER_PLATTFORM, "bestaetigung", "Buchung ohne Kennung")
            etag = r.headers.get("etag", "1")
        finally:
            # Aufräumen auch bei Fehlern: Probebuchung belegt keine Kapazität dauerhaft (Hold läuft sonst nach 180 s ab)
            if buchung:
                self._api("storno", "POST", f"/v1/bookings/{buchung['id']}/cancel", {"reason": "PASSENGER_REQUEST"}, (200,), if_match=etag, idem=True)

    def operator(self) -> None:
        c = self.cfg
        self.anmelden(c.zugang_operator)
        me = self._api("me", "GET", "/v1/me").json()
        if not isinstance(me, dict) or not me.get("tenant_id") or (c.probemandant_id and me["tenant_id"] != c.probemandant_id):
            raise ProbeAbbruch(FEHLER_PLATTFORM, "me", "/v1/me liefert keinen Mandantenbezug des Probemandanten")
        liste = self._api("fahrzeugliste", "GET", "/v1/operator/vehicles?limit=100").json()
        items = (liste or {}).get("items") if isinstance(liste, dict) else None
        if items is None or (c.probefahrzeug_id and not any(i.get("vehicle_id") == c.probefahrzeug_id for i in items)):
            raise ProbeAbbruch(FEHLER_PLATTFORM, "fahrzeugliste", f"Fahrzeugliste fehlt oder enthält das Probefahrzeug nicht ({'keine Liste' if items is None else str(len(items)) + ' Einträge'})")


def referenzpunkt_erreichbar(cfg: Konfiguration, transport) -> bool:
    """Selbsttest der Probe: ohne konfigurierten Referenzpunkt gilt die Probe als intakt; sonst muss er antworten (jeder HTTP-Status genügt)."""
    if not cfg.referenz_url:
        return True
    try:
        transport("GET", cfg.referenz_url, {}, None, cfg.timeout_s)
        return True
    except NetworkError:
        return False


def probe_ausfuehren(bereich: str, cfg: Konfiguration, transport=urllib_transport, uhr=time.time, umgebung=os.environ, alarm=None) -> dict:
    """Führt einen Lauf aus und liefert den Rohdatensatz (Zeitpunkt UTC, Bereich, Ergebnis, Schritte). `alarm(ereignis: dict)` wird bei `keine_messung` aufgerufen (Probenalarm)."""
    if bereich not in BEREICHE:
        raise ValueError(f"unbekannter Bereich {bereich}")
    t0 = uhr()
    lauf = Lauf(bereich, cfg, transport, uhr, umgebung)
    ergebnis, detail, schritt, status = OK, "", None, None
    try:
        getattr(lauf, bereich)()
    except ProbeAbbruch as e:
        ergebnis, detail, schritt, status = e.ergebnis, e.detail, e.schritt, e.http_status
    except Exception as e:  # noqa: BLE001 - Ausnahme im Probenprozess: die Probe selbst ist ausgefallen
        ergebnis, detail, schritt = KEINE_MESSUNG, f"Ausnahme in der Probe: {type(e).__name__}: {str(e)[:150]}", "probe"
    if ergebnis == FEHLER_PLATTFORM and not referenzpunkt_erreichbar(cfg, transport):
        ergebnis, detail = KEINE_MESSUNG, "Referenzpunkt nicht erreichbar: Ausfall auf Seite der Probe, keine Aussage über die Plattform (" + detail + ")"
    satz = {"zeitpunkt": datetime.fromtimestamp(t0, timezone.utc).isoformat(timespec="seconds"), "bereich": bereich, "ergebnis": ergebnis,
            "dauer_ms": sum(s.get("dauer_ms", 0) for s in lauf.schritte), "schritte": lauf.schritte}
    if ergebnis != OK:
        satz["schritt"], satz["detail"] = schritt, detail
        if status is not None:
            satz["http_status"] = status
    if ergebnis == KEINE_MESSUNG and alarm:
        alarm({"alarm": "ProbeKeineMessung", "bereich": bereich, "zeitpunkt": satz["zeitpunkt"], "schritt": schritt, "detail": detail})
    return satz
