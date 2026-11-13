"""Testwelt: Anwendung + Test-Issuer + Mandanten über die Plattform-API. Mandanten legt der Plattformbetrieb über `POST /v1/platform/tenants` an (Betreiberkonto
wird vom Datenbankadministrator eingetragen, wie im Betrieb); Mitgliedschaften der Planerrolle über die API. Nur für Tests."""
import json
import sys
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tools"))
sys.path.insert(0, str(ROOT / "db"))

import db as dbtool  # noqa: E402
from stammdaten_import.client import ApiClient  # noqa: E402

from .app import AppProcess  # noqa: E402
from .idp import TestIdp  # noqa: E402


class Mandant:
    def __init__(self, tenant_id, admin_subject, admin_token, tokens):
        self.id = tenant_id
        self.admin_subject, self.admin_token = admin_subject, admin_token
        self.planer_token, self.dispo_token, self.personal_token = tokens["planner"], tokens["dispatcher"], tokens["staff_planning"]

    def zugaenge(self):
        from stammdaten_import.einspielung import Zugaenge
        return Zugaenge(administrator=self.admin_token, planer=self.planer_token, disponent=self.dispo_token, personalplanung=self.personal_token)


class Welt:
    def __init__(self, dbname: str):
        self.dbname = dbname
        self.idp = TestIdp()
        self.app = AppProcess(dbname, self.idp)
        self.client = ApiClient(self.app.base_url)
        self.operator = uuid.uuid4()
        with self.admin() as c:
            c.execute("INSERT INTO taktovia.platform_operator_account(issuer, subject) VALUES (%s, %s)", (self.idp.issuer, str(self.operator)))
        self.operator_token = self.idp.token(self.operator, roles=("platform_operator",))

    def admin(self):
        return dbtool.admin(self.dbname)

    def mandant(self, name: str) -> Mandant:
        admin = uuid.uuid4()
        r = self.client.anfrage("POST", "/v1/platform/tenants", self.operator_token, {
            "name": name, "contact_email": "betrieb@example.invalid", "legal_entity": {"name": name + " Rechtsträger", "country": "DE"},
            "first_admin": {"issuer": self.idp.issuer, "subject": str(admin)}})
        tenant_id = r.body.get("tenant_id") or r.body.get("id")
        admin_token = self.idp.token(admin, roles=("tenant_admin",))
        tokens = {}
        for rolle in ("planner", "dispatcher", "staff_planning"):
            tokens[rolle] = self.idp.token(self._grant(admin_token, rolle), roles=(rolle,))
        return Mandant(tenant_id, admin, admin_token, tokens)

    def _grant(self, admin_token, rolle):
        subject = uuid.uuid4()
        self.client.anfrage("POST", "/v1/memberships", admin_token, {"issuer": self.idp.issuer, "subject": str(subject), "role_code": rolle})
        return subject

    def grant(self, mandant: Mandant, rolle: str):
        """Mitgliedschaft (z. B. Fahrgast) im Mandanten über die API; liefert (Subject, Token)."""
        subject = self._grant(mandant.admin_token, rolle)
        return subject, self.idp.token(subject, roles=(rolle,))

    def close(self):
        self.app.close()
        self.idp.close()
