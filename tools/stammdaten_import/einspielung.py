"""Einspielung der Stammdaten über die fachlichen Schreibwege der Anwendung (HTTP-API), mit Trockenlauf, Wiederaufnahme und Idempotenz je Datensatz.

Idempotenz: vor jedem Schreiben wird der Datensatz über seinen natürlichen Schlüssel auf dem Server gesucht (Betreiber: federation_ref, Haltepunkt: aus dem
Quellschlüssel abgeleitete stabile `stop_id`, Fahrzeug: Kennzeichen, Benutzer: Aussteller + Subject usw.); nur Fehlendes wird angelegt. Zusätzlich trägt jedes
Schreiben einen deterministischen Idempotency-Key. Fehler einzelner Datensätze brechen den Lauf nicht ab: sie landen mit Grund in der Fehlerliste, Datensätze,
die auf einen abgelehnten verweisen, werden als abhängig abgelehnt, alle übrigen werden unverändert geladen."""
import datetime as dt
import json
import logging
import uuid
from dataclasses import dataclass

from . import geometrie, quelle as q
from .client import ApiClient, ApiFehler, idem_key

log = logging.getLogger("stammdaten_import")
NAMENSRAUM = uuid.UUID("5d0a8f5e-6c3f-4b73-9a53-2d1f4f1a7c11")   # für stabile Haltepunktkennungen aus Mandant + Quellschlüssel


class LaufAbgebrochen(Exception):
    """Der Lauf wurde unterbrochen (Prozess beendet, Netz weg, Testunterbrechung); der Zustand erlaubt die Wiederaufnahme."""


class MandantAbweichung(Exception):
    """Ein Zugang gehört nicht zum Mandanten der Quelle; es wurde nichts geschrieben."""


@dataclass
class Zugaenge:
    """Bearer-Tokens je Rolle des Zielmandanten (kein Superuser, kein Datenbankzugang). Fehlt ein Zugang, scheitern nur die Datensätze, die ihn brauchen."""
    administrator: str                    # tenant_admin: Betreiber, Dienste, Gebiete, Haltepunkte, Tarifentwurf, Mitgliedschaften, Konfigurationsentwurf
    planer: str | None = None             # planner: Tarif prüfen/veröffentlichen, Konfiguration freigeben/veröffentlichen (Vier-Augen gegenüber dem Anlegenden)
    disponent: str | None = None          # dispatcher: Fahrzeugstamm und Dienstzuordnung
    personalplanung: str | None = None    # staff_planning: Fahrerstamm und Qualifikationen

    def alle(self):
        return {"administrator": self.administrator, "planer": self.planer, "disponent": self.disponent, "personalplanung": self.personalplanung}


def _t(wert):
    """Zeitstempel als aware datetime (Vergleich unabhängig von der Darstellung)."""
    return dt.datetime.fromisoformat(wert.replace("Z", "+00:00")) if isinstance(wert, str) else None


def _jetzt():
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _norm_json(o):
    return json.dumps(o, sort_keys=True)


class Lauf:
    def __init__(self, quelle: q.Quelle, base_url: str, zugaenge: Zugaenge, modus: str, abbruch_nach: int | None):
        self.q, self.api, self.z, self.modus = quelle, ApiClient(base_url), zugaenge, modus
        self.abbruch_nach = abbruch_nach
        self.verarbeitet = 0
        self.ids: dict[str, dict[str, dict]] = {a: {} for a in q.ARTEN}
        self.fehler: list[q.Fehler] = list(quelle.fehler)
        self.abgelehnt = quelle.abgelehnt()
        self.bilanz = {a: {"soll": quelle.soll[a], "ist": 0, "angelegt": 0, "vorhanden": 0, "abgelehnt": 0, "geplant_anlegen": 0} for a in q.ARTEN}
        for f in quelle.fehler:
            if f.art in self.bilanz:
                self.bilanz[f.art]["abgelehnt"] += 1

    # ---- Hilfen
    def _token(self, rolle):
        t = getattr(self.z, rolle)
        if not t:
            raise ApiFehler(0, "ZUGANG_FEHLT")
        return t

    def _ablehnen(self, art, key, grund, text):
        self.fehler.append(q.Fehler(art, key, grund, text))
        self.abgelehnt.add((art, key))
        self.bilanz[art]["abgelehnt"] += 1
        log.warning("abgelehnt art=%s key=%s grund=%s", art, key, grund)

    def _idem(self, art, key, schritt=""):
        return idem_key(self.q.sha256, art, key, schritt)

    def _schreiben(self, methode, pfad, rolle, body=None, if_match=None, art="", key="", schritt=""):
        r = self.api.anfrage(methode, pfad, self._token(rolle), body, if_match=if_match, idem_key=self._idem(art, key, schritt))
        return r

    def _unterbrechung(self):
        self.verarbeitet += 1
        if self.abbruch_nach is not None and self.verarbeitet >= self.abbruch_nach:
            raise LaufAbgebrochen(f"Lauf nach {self.verarbeitet} Schritten unterbrochen")

    def _liste(self, pfad, rolle):
        return list(self.api.seiten(pfad, self._token(rolle)))

    # ---- Ablauf
    def ausfuehren(self):
        for art in q.ARTEN:
            for rec in self.q.datensaetze[art]:
                key = rec["key"]
                if (art, key) in self.abgelehnt:
                    continue
                grund = self._abhaengig_abgelehnt(art, rec)
                if grund:
                    self._ablehnen(art, key, "ABHAENGIG_ABGELEHNT", grund)
                    continue
                try:
                    vorhanden = self._bearbeite(art, rec)
                except ApiFehler as e:
                    self._ablehnen(art, key, "API_" + (e.code or str(e.status)) if e.status else (e.code or "API_FEHLER"), f"Anwendung hat abgelehnt ({e.grund})")
                    continue
                if vorhanden is None:
                    self.bilanz[art]["geplant_anlegen"] += 1
                elif vorhanden:
                    self.bilanz[art]["vorhanden"] += 1
                else:
                    self.bilanz[art]["angelegt"] += 1
                    log.info("angelegt art=%s key=%s", art, key)
                    self._unterbrechung()

    def _abhaengig_abgelehnt(self, art, rec):
        for feld, ziel, _opt in q.REFERENZEN.get(art, ()):
            w = rec.get(feld)
            for ref in (w if isinstance(w, list) else [w] if w is not None else []):
                if (ziel, ref) in self.abgelehnt:
                    return f"Verweis {feld} zeigt auf einen abgelehnten Datensatz ({ziel})"
        return None

    def _bearbeite(self, art, rec):
        """True = war schon vorhanden, False = angelegt, None = Trockenlauf: würde angelegt."""
        return getattr(self, "_" + art)(rec)

    def _schreibend(self):
        return self.modus != "trockenlauf"

    # ---- Betreiber
    def _betreiber(self, rec):
        key = rec["key"]
        orgs = [o for o in self._liste("/v1/organizations", "administrator") if o["federation_ref"] == rec["federation_ref"]]
        if orgs:
            self.ids["betreiber"][key] = {"org": orgs[0]["id"], "le": orgs[0]["legal_entity_id"]}
            return True
        if not self._schreibend():
            return None
        les = [x for x in self._liste("/v1/legal-entities", "administrator") if x["name"] == rec["name"] and x["country"] == rec["country"]]
        if les:
            le = les[0]["id"]
        else:
            body = {"name": rec["name"], "country": rec["country"]}
            if rec.get("tax_reference_token"):
                body["tax_reference_token"] = rec["tax_reference_token"]
            le = self._schreiben("POST", "/v1/legal-entities", "administrator", body, art="betreiber", key=key, schritt="legal-entity").body["id"]
        body = {"legal_entity_id": le, "name": rec["name"], "federation_ref": rec["federation_ref"], "roles": rec["roles"]}
        for f in ("roles_valid_from", "roles_valid_to"):
            if rec.get(f):
                body[f] = rec[f]
        org = self._schreiben("POST", "/v1/organizations", "administrator", body, art="betreiber", key=key, schritt="organization").body["id"]
        self.ids["betreiber"][key] = {"org": org, "le": le}
        return False

    # ---- Dienste (mit Betriebszeiten)
    def _dienste(self, rec):
        key = rec["key"]
        op = self.ids["betreiber"].get(rec["betreiber"], {}).get("org")
        vorhanden = [s for s in self._liste("/v1/services", "administrator") if s["name"] == rec["name"] and s["operator_id"] == op] if op else []
        neu = not vorhanden
        if not neu:
            sid = vorhanden[0]["id"]
        elif not self._schreibend():
            return None
        else:
            body = {"operator_id": op, "name": rec["name"], "timezone": rec["timezone"]}
            if rec.get("region_id"):
                body["region_id"] = rec["region_id"]
            sid = self._schreiben("POST", "/v1/services", "administrator", body, art="dienste", key=key).body["id"]
        self.ids["dienste"][key] = {"id": sid}
        hz = rec.get("betriebszeiten")
        if hz is not None:
            aktuell = self.api.get(f"/v1/services/{sid}/operating-hours", self._token("administrator")).body if not neu or self._schreibend() else None
            if aktuell and aktuell["revision"] > 0:
                if _norm_json(aktuell["weekly_hours"]) != _norm_json(hz["weekly_hours"]) or aktuell["traffic_product"] != hz["traffic_product"]:
                    raise ApiFehler(0, "ABWEICHEND_VORHANDEN")
            elif self._schreibend():
                body = {**{k: hz[k] for k in ("traffic_product", "weekly_hours", "valid_from")}, "valid_to": hz.get("valid_to"),
                        "exceptions": hz.get("exceptions", []), "reason": hz.get("reason", "Einspielung der Stammdaten")}
                self._schreiben("PUT", f"/v1/services/{sid}/operating-hours", "administrator", body, if_match=0, art="dienste", key=key, schritt="betriebszeiten")
                neu = True
        return not neu

    # ---- Bediengebiete
    def _bediengebiete(self, rec):
        key = rec["key"]
        sid = self.ids["dienste"].get(rec["dienst"], {}).get("id")
        von, rolle = _t(rec["valid_from"]), rec.get("zone_role", "BOTH")
        vorh = [a for a in self._liste(f"/v1/service-areas?service_id={sid}", "administrator") if _t(a["valid_from"]) == von and a["zone_role"] == rolle] if sid else []
        if vorh:
            self.ids["bediengebiete"][key] = {"id": vorh[0]["id"], "area": vorh[0]["area"], "service": sid}
            return True
        if not self._schreibend():
            return None
        body = {"service_id": sid, "area": rec["area"], "valid_from": rec["valid_from"], "valid_to": rec.get("valid_to"), "zone_role": rolle}
        r = self._schreiben("POST", "/v1/service-areas", "administrator", body, art="bediengebiete", key=key)
        self.ids["bediengebiete"][key] = {"id": r.body["id"], "area": r.body["area"], "service": sid}
        return False

    # ---- Haltepunkte
    def stop_id(self, key):
        return str(uuid.uuid5(NAMENSRAUM, f"{self.q.mandant_id}|haltepunkt|{key}"))

    def _haltepunkte(self, rec):
        key, sid = rec["key"], self.stop_id(rec["key"])
        try:
            self.api.get(f"/v1/stops/{sid}", self._token("administrator"))
            self.ids["haltepunkte"][key] = {"stop_id": sid}
            return True
        except ApiFehler as e:
            if e.status != 404:
                raise
        if not self._schreibend():
            return None
        body = {"stop_id": sid, "name": rec["name"], "point": rec["point"], "accessibility": rec["accessibility"], "traffic_products": rec["traffic_products"],
                "active": rec.get("active", True), "valid_from": rec["valid_from"], "valid_to": rec.get("valid_to")}
        self._schreiben("POST", "/v1/stops", "administrator", body, art="haltepunkte", key=key)
        self.ids["haltepunkte"][key] = {"stop_id": sid}
        return False

    # ---- Tarife (Entwurf, Prüfung, Veröffentlichung)
    def _tarife(self, rec):
        key = rec["key"]
        sid = self.ids["dienste"].get(rec["dienst"], {}).get("id")
        von = _t(rec["valid_from"])
        vorh = [t for t in self._liste(f"/v1/tariffs?service_id={sid}", "administrator") if t["tariff_class"] == rec["tariff_class"] and _t(t["valid_from"]) == von] if sid else []
        freigeben = rec.get("freigabe", True)
        if not vorh and not self._schreibend():
            return None
        neu = False
        if vorh:
            t = vorh[0]
        else:
            body = {"service_id": sid, "tariff_class": rec["tariff_class"], "valid_from": rec["valid_from"], "valid_to": rec.get("valid_to"),
                    "currency": rec["currency"], "reason": rec["reason"], "rules": rec["rules"]}
            r = self._schreiben("POST", "/v1/tariffs", "administrator", body, art="tarife", key=key)
            t = r.body
            neu = True
        self.ids["tarife"][key] = {"id": t["id"]}
        if freigeben and t["state"] != "VEROEFFENTLICHT" and not self._schreibend():
            return None
        if freigeben:
            if t["state"] == "ENTWURF":
                t = self._schreiben("POST", f"/v1/tariffs/{t['id']}/review", "planer", {}, if_match=t["revision"], art="tarife", key=key, schritt="review").body
                neu = True
            if t["state"] == "GEPRUEFT":
                t = self._schreiben("POST", f"/v1/tariffs/{t['id']}/publish", "planer", {}, if_match=t["revision"], art="tarife", key=key, schritt="publish").body
                neu = True
        return not neu

    # ---- Fahrzeuge (Stamm und Dienstzuordnung)
    def _fahrzeuge(self, rec):
        key = rec["key"]
        op = self.ids["betreiber"].get(rec["betreiber"], {}).get("org")
        sid = self.ids["dienste"].get(rec["dienst"], {}).get("id")
        gebiet = self.ids["bediengebiete"].get(rec.get("gebiet"), {}).get("id") if rec.get("gebiet") else None
        vorh = [v for v in self._liste("/v1/operator/vehicle-master", "disponent") if v["vehicle_label"] == rec["kennzeichen"]]
        neu = False
        if vorh:
            v = vorh[0]
            if v["operator_organization_id"] != op:
                raise ApiFehler(0, "ABWEICHEND_VORHANDEN")
        elif not self._schreibend():
            return None
        else:
            body = {"vehicle_label": rec["kennzeichen"], "vehicle_type": rec["vehicle_type"], "operator_organization_id": op, "capacity": rec["capacity"],
                    "equipment": rec.get("equipment", []), "active": rec.get("active", True)}
            v = self._schreiben("POST", "/v1/operator/vehicle-master", "disponent", body, art="fahrzeuge", key=key).body
            neu = True
        self.ids["fahrzeuge"][key] = {"id": v["id"]}
        von = _t(rec["zuordnung_ab"])
        zuord = [a for a in self._liste(f"/v1/operator/vehicles/{v['id']}/service-assignments", "disponent") if a["service_id"] == sid and _t(a["valid_from"]) == von]
        if not zuord:
            if not self._schreibend():
                return None
            body = {"service_id": sid, "service_area_id": gebiet, "valid_from": rec["zuordnung_ab"], "valid_to": rec.get("zuordnung_bis")}
            self._schreiben("POST", f"/v1/operator/vehicles/{v['id']}/service-assignments", "disponent", body, art="fahrzeuge", key=key, schritt="dienstzuordnung")
            neu = True
        return not neu

    # ---- Benutzer (Mitgliedschaft mit Rolle)
    def _issuer(self, rec):
        return rec.get("issuer") or self.q.daten["mandant"]["issuer"]

    def _benutzer(self, rec):
        key, issuer = rec["key"], self._issuer(rec)
        vorh = [m for m in self._liste("/v1/memberships", "administrator") if m["issuer"] == issuer and m["subject"] == rec["subject"] and not m.get("revoked_at")]
        if vorh:
            if vorh[0]["role_code"] != rec["rolle"]:
                raise ApiFehler(0, "ABWEICHEND_VORHANDEN")
            self.ids["benutzer"][key] = {"membership": vorh[0]["id"]}
            return True
        if not self._schreibend():
            return None
        body = {"issuer": issuer, "subject": rec["subject"], "role_code": rec["rolle"]}
        if rec.get("dienste"):
            body["service_scope"] = [self.ids["dienste"][d]["id"] for d in rec["dienste"]]
        for f in ("valid_from", "valid_to"):
            if rec.get(f):
                body[f] = rec[f]
        r = self._schreiben("POST", "/v1/memberships", "administrator", body, art="benutzer", key=key)
        self.ids["benutzer"][key] = {"membership": r.body["id"]}
        return False

    # ---- Fahrer (Stamm und Qualifikationen)
    def _fahrer(self, rec):
        key = rec["key"]
        mid = self.ids["benutzer"].get(rec["benutzer"], {}).get("membership")
        if mid is None:
            if not self._schreibend():
                return None
            raise ApiFehler(0, "BENUTZER_NICHT_ANGELEGT")
        vorh = [d for d in self._liste("/v1/drivers", "personalplanung") if d["membership_id"] == mid]
        neu = False
        if vorh:
            d = vorh[0]
        elif not self._schreibend():
            return None
        else:
            body = {"membership_id": mid, "display_name": rec["display_name"], "contact_channel": rec["contact_channel"], "contact_value": rec["contact_value"]}
            d = self._schreiben("POST", "/v1/drivers", "personalplanung", body, art="fahrer", key=key).body
            neu = True
        self.ids["fahrer"][key] = {"id": d["id"]}
        for i, qa in enumerate(rec.get("qualifikationen", [])):
            gen = self.api.get(f"/v1/drivers/{d['id']}/qualifications", self._token("personalplanung")).body["items"]
            if any(x["qualification_code"] == qa["qualification_code"] and _t(x["valid_from"]) == _t(qa["valid_from"]) for x in gen):
                continue
            if not self._schreibend():
                return None
            aktuell = self.api.get(f"/v1/drivers/{d['id']}", self._token("personalplanung"))
            body = {k: qa[k] for k in ("qualification_code", "valid_from", "decision")}
            body["valid_to"] = qa.get("valid_to")
            if qa.get("evidence_ref"):
                body["evidence_ref"] = qa["evidence_ref"]
            self._schreiben("POST", f"/v1/drivers/{d['id']}/qualifications", "personalplanung", body, if_match=aktuell.etag_zahl, art="fahrer", key=key, schritt=f"qualifikation-{i}")
            neu = True
        return not neu

    # ---- Konfiguration (Erstwerte als freigegebene Revision)
    def _konfiguration(self, rec):
        key = rec["key"]
        von = _t(rec["effective_from"])
        liste = self._liste(f"/v1/config/revisions?scope_kind=TENANT&scope_id={self.q.mandant_id}", "administrator")
        vorh = [c for c in liste if c["values"] == rec["values"] and _t(c["effective_from"]) == von and c["state"] in ("DRAFT", "VALIDATED", "APPROVED", "PUBLISHED")]
        if not vorh and not self._schreibend():
            return None
        neu = False
        if vorh:
            c = vorh[0]
        else:
            body = {"scope_kind": "TENANT", "scope_id": self.q.mandant_id, "schema_version": str(rec.get("schema_version", "1")), "values": rec["values"],
                    "effective_from": rec["effective_from"], "reason": rec["reason"]}
            c = self._schreiben("POST", "/v1/config/revisions", "administrator", body, art="konfiguration", key=key).body
            neu = True
        self.ids["konfiguration"][key] = {"id": c["id"]}
        if c["state"] == "PUBLISHED":
            return not neu
        if not self._schreibend():
            return None
        if c["state"] == "DRAFT":
            bericht = self._schreiben("POST", f"/v1/config/revisions/{c['id']}/impact", "administrator", None, if_match=c["row_version"], art="konfiguration", key=key, schritt="impact").body["id"]
            v = self._schreiben("POST", f"/v1/config/revisions/{c['id']}/validate", "administrator", None, if_match=c["row_version"], art="konfiguration", key=key, schritt="validate")
            c = {"id": c["id"], "state": "VALIDATED", "row_version": v.body["row_version"]}
        else:
            bericht = self._schreiben("POST", f"/v1/config/revisions/{c['id']}/impact", "administrator", None, if_match=c["row_version"], art="konfiguration", key=key, schritt="impact").body["id"]
        if c["state"] == "VALIDATED":
            a = self._schreiben("POST", f"/v1/config/revisions/{c['id']}/approve", "planer", None, if_match=c["row_version"], art="konfiguration", key=key, schritt="approve").body
            c = {"id": c["id"], "state": "APPROVED", "row_version": a["row_version"]}
        self._schreiben("POST", f"/v1/config/revisions/{c['id']}/publish", "planer", {"effective_from": rec["effective_from"], "reason": "Erstwerte Pilotbetrieb", "impact_report_id": bericht},
                        if_match=c["row_version"], art="konfiguration", key=key, schritt="publish")
        return False

    # ---- Nachweis: Ist-Bestand und Konsistenz (nur lesend)
    def verifizieren(self):
        """Ist-Bestand: je Objektart die Datensätze der Quelle, die nach dem Lauf vollständig auf dem Server vorhanden sind (Lesezugriffe)."""
        alt = self.modus
        self.modus = "pruefen"
        try:
            for art in q.ARTEN:
                ist = 0
                for rec in self.q.datensaetze[art]:
                    if (art, rec["key"]) in self.abgelehnt:
                        continue
                    try:
                        if self._bearbeite(art, rec) is True:
                            ist += 1
                    except ApiFehler:
                        pass
                self.bilanz[art]["ist"] = ist
        finally:
            self.modus = alt

    def konsistenz(self):
        """Konsistenzregeln (Vertrag LH-6.20-QAT-005) gegen den gelesenen Serverstand; je Regel Anzahl geprüft und Verletzungen (nur Quellschlüssel)."""
        tok = lambda r: self._token(r)  # noqa: E731
        ergebnis = []

        def regel(name, pruefungen):
            verletzt = [k for k, ok in pruefungen if not ok]
            ergebnis.append({"regel": name, "geprueft": len(pruefungen), "verletzt": verletzt, "ok": not verletzt})

        orgs = {o["id"]: o for o in self._liste("/v1/organizations", "administrator")}
        les = {x["id"] for x in self._liste("/v1/legal-entities", "administrator")}
        svcs = {s["id"]: s for s in self._liste("/v1/services", "administrator")}
        acc = lambda a: [(k, i) for k, i in self.ids[a].items() if (a, k) not in self.abgelehnt]  # noqa: E731
        # keine verwaisten Referenzen
        refs = []
        for k, i in acc("betreiber"):
            refs.append((k, i["org"] in orgs and orgs[i["org"]]["legal_entity_id"] in les))
        for k, i in acc("dienste"):
            refs.append((k, i["id"] in svcs and svcs[i["id"]]["operator_id"] in orgs))
        for k, i in acc("tarife"):
            t = self.api.get(f"/v1/tariffs/{i['id']}", tok("administrator")).body
            refs.append((k, t["service_id"] in svcs))
        mems = {m["id"] for m in self._liste("/v1/memberships", "administrator")}
        for k, i in acc("fahrer"):
            d = self.api.get(f"/v1/drivers/{i['id']}", tok("personalplanung")).body
            refs.append((k, d["membership_id"] in mems))
        for k, i in acc("fahrzeuge"):
            v = self.api.get(f"/v1/operator/vehicle-master/{i['id']}", tok("disponent")).body
            refs.append((k, v["operator_organization_id"] in orgs))
        regel("keine_verwaisten_referenzen", refs)
        # Bediengebiet = valides Polygon
        gebiete = {}
        pr = []
        for k, i in acc("bediengebiete"):
            a = self.api.get(f"/v1/service-areas/{i['id']}", tok("administrator")).body
            gebiete[k] = a["area"]
            pr.append((k, geometrie.multipolygon_fehler(a["area"]) is None))
        regel("bediengebiet_valides_polygon", pr)
        # Haltepunkt im Gebiet
        pr = []
        recs = {r["key"]: r for r in self.q.datensaetze["haltepunkte"]}
        for k, i in acc("haltepunkte"):
            s = self.api.get(f"/v1/stops/{i['stop_id']}", tok("administrator")).body
            g = gebiete.get(recs[k]["gebiet"])
            pr.append((k, g is not None and geometrie.punkt_in_multipolygon(s["point"]["latitude"], s["point"]["longitude"], g)))
        regel("haltepunkt_im_gebiet", pr)
        # Tarif mit Gültigkeitsintervall (und freigegeben, wenn so vorgesehen)
        pr = []
        recs = {r["key"]: r for r in self.q.datensaetze["tarife"]}
        for k, i in acc("tarife"):
            t = self.api.get(f"/v1/tariffs/{i['id']}", tok("administrator")).body
            pr.append((k, bool(t.get("valid_from")) and (t["state"] == "VEROEFFENTLICHT" or not recs[k].get("freigabe", True))))
        regel("tarif_mit_gueltigkeit", pr)
        # Fahrzeug: Betreiber, Dienst, Ressourcenvektor
        pr = []
        recs = {r["key"]: r for r in self.q.datensaetze["fahrzeuge"]}
        for k, i in acc("fahrzeuge"):
            v = self.api.get(f"/v1/operator/vehicle-master/{i['id']}", tok("disponent")).body
            zu = self.api.get(f"/v1/operator/vehicles/{i['id']}/service-assignments", tok("disponent")).body["items"]
            sid = self.ids["dienste"].get(recs[k]["dienst"], {}).get("id")
            pr.append((k, v["operator_organization_id"] in orgs and isinstance(v.get("capacity"), dict) and any(a["service_id"] == sid for a in zu)))
        regel("fahrzeug_mit_betreiber_dienst_ressourcenvektor", pr)
        # Benutzer mit Mitgliedschaft und Rolle
        pr = []
        aktive = {(m["issuer"], m["subject"]): m for m in self._liste("/v1/memberships", "administrator") if not m.get("revoked_at")}
        recs = {r["key"]: r for r in self.q.datensaetze["benutzer"]}
        for k, _i in acc("benutzer"):
            m = aktive.get((self._issuer(recs[k]), recs[k]["subject"]))
            pr.append((k, m is not None and m["role_code"] == recs[k]["rolle"]))
        regel("benutzer_mit_mitgliedschaft_und_rolle", pr)
        return ergebnis


# ---------------------------------------------------------------------------------------------------------------------------
class _Schwaerzfilter(logging.Filter):
    def __init__(self, geheim):
        super().__init__()
        self.geheim = [g for g in geheim if g]

    def filter(self, record):
        from .protokoll import schwaerzen
        record.msg = schwaerzen(record.getMessage(), self.geheim)
        record.args = ()
        return True


def _geheime_werte(quelle: q.Quelle):
    """Personendaten der Quelle (Fahrer: Name, Kontakt; Benutzer: Kennung), die in Protokoll und Logs nie auftauchen dürfen."""
    out = []
    for f in quelle.daten.get("fahrer", []):
        if isinstance(f, dict):
            out += [f.get("display_name"), f.get("contact_value")]
    for b in quelle.daten.get("benutzer", []):
        if isinstance(b, dict):
            out.append(b.get("subject"))
    return [g for g in out if isinstance(g, str)]


def _claim_sub(token):
    import base64
    try:
        payload = token.split(".")[1]
        return json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4))).get("sub")
    except Exception:  # noqa: BLE001
        return None


def einspielen(quelle_pfad, base_url, zugaenge: Zugaenge, *, modus="lauf", zustand_pfad=None, protokoll_pfad=None, build="unbekannt", migrationsstand="unbekannt",
               ausfuehrender=None, freigeber=None, abbruch_nach=None):
    """Führt eine Einspielung aus und liefert das Migrationsprotokoll (dict); `modus` ist `trockenlauf`, `lauf` oder `fortsetzen`.

    `build` und `migrationsstand` gibt der Aufrufer an (Werkzeugstand, Schemaversion der Zielumgebung); das Werkzeug liest sie nicht aus der Datenbank.
    `freigeber` ist Pflicht für einen schreibenden Lauf (wer die Quelle für die Einspielung freigegeben hat). Lauf ohne Abbruch liefert `ergebnis`
    ABGESCHLOSSEN oder ABGESCHLOSSEN_MIT_FEHLERN (Fehlerliste nicht leer), eine Unterbrechung ABGEBROCHEN (dann mit `fortsetzen` weitermachen)."""
    from . import protokoll as pr
    if modus not in ("trockenlauf", "lauf", "fortsetzen"):
        raise ValueError("modus")
    quelle = q.laden(quelle_pfad)
    geheim = _geheime_werte(quelle)
    flt = _Schwaerzfilter(geheim)
    log.addFilter(flt)
    try:
        if modus != "trockenlauf" and not freigeber:
            raise ValueError("Für einen schreibenden Lauf ist der Freigeber der Quelle anzugeben")
        zustand = None
        if zustand_pfad:
            try:
                with open(zustand_pfad, encoding="utf-8") as f:
                    zustand = json.load(f)
            except FileNotFoundError:
                zustand = None
        if modus == "fortsetzen":
            if not zustand or zustand.get("status") != "ABGEBROCHEN":
                raise ValueError("Keine unterbrochene Einspielung zum Fortsetzen vorhanden")
            if zustand["sha256"] != quelle.sha256:
                raise ValueError("Die Quelle hat sich seit der Unterbrechung geändert (Prüfsumme); Wiederaufnahme nicht zulässig")
        elif modus == "lauf" and zustand and zustand.get("status") == "ABGEBROCHEN" and zustand["sha256"] == quelle.sha256:
            raise ValueError("Unterbrochene Einspielung derselben Quelle vorhanden: mit Modus fortsetzen weitermachen")
        lauf = Lauf(quelle, base_url, zugaenge, modus, abbruch_nach)
        start = zustand["gestartet"] if modus == "fortsetzen" else _jetzt()
        lauf_id = zustand["lauf_id"] if modus == "fortsetzen" else str(uuid.uuid4())
        fortsetzungen = (zustand["fortsetzungen"] + 1) if modus == "fortsetzen" else 0
        ausf = ausfuehrender or _claim_sub(zugaenge.administrator) or "unbekannt"

        def basis(ergebnis, status):
            return {
                "protokoll_version": pr.PROTOKOLL_VERSION,
                "lauf": {"id": lauf_id, "modus": modus, "status": status, "ergebnis": ergebnis, "gestartet": start, "beendet": _jetzt(), "fortsetzungen": fortsetzungen},
                "quelle": {"datei": quelle.pfad.rsplit("/", 1)[-1], "sha256": quelle.sha256, "format": q.FORMAT},
                "mandant": {"tenant_id": quelle.mandant_id},
                "build": build, "migrationsstand": migrationsstand, "ausfuehrender": ausf, "freigeber": freigeber,
                "bestaende": {}, "fehlerliste": [], "konsistenz": [],
            }

        # Mandantenprüfung vor dem ersten Schreiben: jeder Zugang muss zum Mandanten der Quelle gehören
        for rolle, tok in zugaenge.alle().items():
            if not tok:
                continue
            try:
                me = lauf.api.get("/v1/me", tok).body
            except ApiFehler as e:
                prot = basis("NICHT_GESTARTET", "NICHT_GESTARTET")
                prot["fehlerliste"] = [{"objektart": "zugang", "schluessel": rolle, "grund": "ZUGANG_UNGUELTIG", "text": f"Zugang {rolle} wurde abgewiesen ({e.grund})"}]
                if protokoll_pfad:
                    pr.schreiben(protokoll_pfad, prot, geheim)
                raise MandantAbweichung(f"Zugang {rolle} ist für die Anwendung nicht nutzbar ({e.grund})") from None
            if str(me["tenant_id"]).lower() != quelle.mandant_id:
                prot = basis("NICHT_GESTARTET", "NICHT_GESTARTET")
                prot["fehlerliste"] = [{"objektart": "zugang", "schluessel": rolle, "grund": "MANDANT_ABWEICHUNG", "text": "Der Zugang gehört zu einem anderen Mandanten als die Quelle"}]
                if protokoll_pfad:
                    pr.schreiben(protokoll_pfad, prot, geheim)
                raise MandantAbweichung(f"Zugang {rolle} gehört nicht zum Mandanten der Quelle; nichts geschrieben")

        def sichern(status):
            if zustand_pfad and modus != "trockenlauf":
                import os
                os.makedirs(os.path.dirname(os.path.abspath(zustand_pfad)), exist_ok=True)
                with open(zustand_pfad, "w", encoding="utf-8") as f:
                    json.dump({"lauf_id": lauf_id, "sha256": quelle.sha256, "mandant": quelle.mandant_id, "status": status, "gestartet": start, "fortsetzungen": fortsetzungen,
                               "angelegt": angelegt_gesamt}, f)

        vorher = (zustand or {}).get("angelegt", {}) if modus == "fortsetzen" else {}
        angelegt_gesamt = dict(vorher)
        if modus != "trockenlauf":
            sichern("LAEUFT")
        try:
            lauf.ausfuehren()
        except LaufAbgebrochen:
            for a in q.ARTEN:
                angelegt_gesamt[a] = angelegt_gesamt.get(a, 0) + lauf.bilanz[a]["angelegt"]
            prot = basis("ABGEBROCHEN", "ABGEBROCHEN")
            prot["bestaende"] = {a: {**{k: v for k, v in b.items() if k != "geplant_anlegen"}, "angelegt": angelegt_gesamt.get(a, 0)} for a, b in lauf.bilanz.items()}
            prot["fehlerliste"] = [f.als_dict() for f in lauf.fehler]
            sichern("ABGEBROCHEN")
            if protokoll_pfad:
                pr.schreiben(protokoll_pfad, prot, geheim)
            raise
        for a in q.ARTEN:
            angelegt_gesamt[a] = vorher.get(a, 0) + lauf.bilanz[a]["angelegt"]
        if modus == "trockenlauf":
            prot = basis("TROCKENLAUF", "TROCKENLAUF")
            prot["bestaende"] = {a: {"soll": b["soll"], "bereits_vorhanden": b["vorhanden"], "wuerde_anlegen": b["geplant_anlegen"], "abgelehnt": b["abgelehnt"]} for a, b in lauf.bilanz.items()}
            prot["fehlerliste"] = [f.als_dict() for f in lauf.fehler]
        else:
            lauf.verifizieren()
            prot = basis("ABGESCHLOSSEN", "ABGESCHLOSSEN")
            for a, b in lauf.bilanz.items():
                b["angelegt"] = angelegt_gesamt[a]
                b["vorhanden"] = max(0, b["vorhanden"] - vorher.get(a, 0))
                b["differenz_erklaert"] = (b["soll"] - b["ist"]) == b["abgelehnt"]
                b.pop("geplant_anlegen", None)
            prot["bestaende"] = lauf.bilanz
            prot["fehlerliste"] = [f.als_dict() for f in lauf.fehler]
            prot["konsistenz"] = lauf.konsistenz()
            if lauf.fehler or not all(b["differenz_erklaert"] for b in lauf.bilanz.values()) or not all(k["ok"] for k in prot["konsistenz"]):
                prot["lauf"]["ergebnis"] = "ABGESCHLOSSEN_MIT_FEHLERN"
            sichern("ABGESCHLOSSEN")
        if protokoll_pfad:
            return pr.schreiben(protokoll_pfad, prot, geheim)
        return prot
    finally:
        log.removeFilter(flt)
