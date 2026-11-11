"""WP-M1-31 (LH-6.18-SEC-005): Vertragsseite des Feldschutzes.

Belegt statisch, dass kein M1-Endpunkt das Unterstützungsmerkmal ausgibt außer dem geschützten `getPassengerSupportNeeds`, dass dessen Scope nur
den berechtigten Rollen der Rechtematrix gehört (nie dem Administrator), und dass Rollen×Feld-Matrix, Rollenkatalog und API-Zwecke zusammenpassen.
Verhalten gegen Datenbank: backend/platform FieldProtectionTest, db/tests/test_08_feldschutz.py.
"""
import csv
import json
import re
import sys
import unittest
from pathlib import Path

from jsonschema import Draft202012Validator

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / "api" / "tools"))
from bundle import bundle  # noqa: E402

FELDRECHTE = REPO / "backend/platform/src/main/resources/fieldprotection/feldrechte.json"
SCHEMA = REPO / "docs/umsetzungsplan/vertraege/schemas/feldrechte.v1.schema.json"
MATRIX = REPO / "backend/modules/identity/src/main/resources/rechtematrix/rechtematrix.csv"
SENSITIVE_SCHEMA = "PassengerSupportNeeds"
SENSITIVE_OP = "getPassengerSupportNeeds"
SENSITIVE_NAME = re.compile(r"(?i)(support_?needs?|support_ciphertext|assistance_?needs?|mobility_?needs?|diagnos\w*)")


def rights():
    return json.loads(FELDRECHTE.read_text(encoding="utf-8"))


def matrix_rows():
    lines = [l for l in MATRIX.read_text(encoding="utf-8").splitlines() if l.strip() and not l.startswith("#")]
    header = lines[0].split(";")
    return header, [dict(zip(header, l.split(";"))) for l in lines[1:]]


def refs(node, out):
    """Alle $ref-Ziele unterhalb von node."""
    if isinstance(node, dict):
        for k, v in node.items():
            if k == "$ref" and isinstance(v, str):
                out.add(v.split("/")[-1])
            else:
                refs(v, out)
    elif isinstance(node, list):
        for v in node:
            refs(v, out)
    return out


def reachable(schema_name, schemas, seen=None):
    seen = seen if seen is not None else set()
    if schema_name in seen or schema_name not in schemas:
        return seen
    seen.add(schema_name)
    for r in refs(schemas[schema_name], set()):
        reachable(r, schemas, seen)
    return seen


class FeldschutzVertrag(unittest.TestCase):
    def test_feldrechte_entsprechen_dem_schema(self):
        Draft202012Validator(json.loads(SCHEMA.read_text(encoding="utf-8"))).validate(rights())
        bad = rights(); bad["fields"][0]["grants"][0]["role"] = "Tenant Admin!"
        with self.assertRaises(Exception):
            Draft202012Validator(json.loads(SCHEMA.read_text(encoding="utf-8"))).validate(bad)

    def test_nur_die_geschuetzte_operation_gibt_das_merkmal_aus(self):
        doc = bundle()
        schemas = doc["components"]["schemas"]
        self.assertIn(SENSITIVE_SCHEMA, schemas)
        users = []
        for path, item in doc["paths"].items():
            for method, op in item.items():
                if method not in {"get", "post", "put", "patch", "delete"}:
                    continue
                for code, resp in op.get("responses", {}).items():
                    for media in resp.get("content", {}).values():
                        direct = refs(media.get("schema", {}), set())
                        reach = set(direct)
                        for r in direct:
                            reach |= reachable(r, schemas)
                        if SENSITIVE_SCHEMA in reach:
                            users.append(op["operationId"])
        self.assertEqual(sorted(set(users)), [SENSITIVE_OP])

    def test_kein_anderes_schema_traegt_ein_unterstuetzungsfeld(self):
        schemas = bundle()["components"]["schemas"]
        leaks = []
        def walk(name, node, path):
            if isinstance(node, dict):
                for k, v in (node.get("properties") or {}).items():
                    if SENSITIVE_NAME.search(k):
                        leaks.append(f"{name}{path}/{k}")
                for k, v in node.items():
                    walk(name, v, f"{path}/{k}")
            elif isinstance(node, list):
                for i, v in enumerate(node):
                    walk(name, v, f"{path}/{i}")
        for name, s in schemas.items():
            if name != SENSITIVE_SCHEMA:
                walk(name, s, "")
        self.assertEqual(leaks, [])
        # das geschützte Schema selbst: nur Katalogauswahl, kein Freitext
        props = schemas[SENSITIVE_SCHEMA]["properties"]
        self.assertEqual(set(props), {"passenger_id", "codes"})
        self.assertIn("enum", props["codes"]["items"])
        self.assertFalse(schemas[SENSITIVE_SCHEMA].get("additionalProperties", True))

    def test_zweckkatalog_der_api_entspricht_der_matrix(self):
        op = bundle()["paths"]["/v1/passengers/{passenger_id}/support-needs"]["get"]
        header = next(p for p in op["parameters"] if p.get("name") == "X-Access-Purpose")
        self.assertTrue(header["required"])
        used = {p for f in rights()["fields"] if f["field"] == "passenger.support_needs" for g in f["grants"] for p in g["purposes"]}
        self.assertEqual(set(header["schema"]["enum"]), used)
        self.assertTrue(used <= set(rights()["purposes"]))

    def test_feldscope_gehoert_nur_den_berechtigten_rollen_nie_dem_administrator(self):
        header, rows = matrix_rows()
        roles = header[4:]
        field = next(f for f in rights()["fields"] if f["field"] == "passenger.support_needs")
        row = next(r for r in rows if r["operation_id"] == SENSITIVE_OP)
        self.assertEqual(row["scope"], field["scope"])
        self.assertEqual(row["datenklasse"], "PII")
        allowed = {r for r in roles if row[r] == "J"}
        self.assertEqual(allowed, {g["role"] for g in field["grants"] if g["actor"] == "HUMAN"})
        self.assertNotIn("tenant_admin", allowed)
        self.assertNotIn("passenger", allowed)

    def test_feldrechte_nennen_nur_rollen_des_katalogs(self):
        header, _ = matrix_rows()
        catalog = set(header[4:])
        for f in rights()["fields"]:
            for g in f["grants"]:
                if g["actor"] == "HUMAN":
                    self.assertIn(g["role"], catalog, f["field"])
                    self.assertNotEqual(g["role"], "tenant_admin", f"{f['field']}: Administrator nur über gesonderte Zuweisung")

    def test_katalog_der_oberflaeche_entspricht_dem_vertrag(self):
        ui = (REPO / "web/design-system/src/components/SupportNeedsSelect.tsx").read_text(encoding="utf-8")
        codes = re.findall(r"'([A-Z_]+)',", ui.split("SUPPORT_NEED_CODES = [")[1].split("]")[0])
        api = bundle()["components"]["schemas"][SENSITIVE_SCHEMA]["properties"]["codes"]["items"]["enum"]
        self.assertEqual(codes, api)


if __name__ == "__main__":
    unittest.main()
