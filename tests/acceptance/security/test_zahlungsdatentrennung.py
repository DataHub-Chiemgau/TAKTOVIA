"""WP-M1-37 (LH-6.18-SEC-008): Zahlungsdatentrennung, Vertragsseite.

Der OpenAPI-Lint (`api/tools/lint.py`, Regel SEC-008) verbietet Schemaeigenschaften und Parameter für rohe Kartendaten (PAN, CVC, Ablaufdatum,
Karteninhaber). Dieser Test belegt: (1) die echten Verträge bestehen die Regel, (2) die Regel schlägt an einem Schema mit Kartendatenfeld an,
(3) PSP-Referenzwerte bleiben erlaubt, (4) das Datenflussdokument nennt die Systemgrenzen und alle Nachweise. Schema-Lint der Datenbank:
db/tests/test_11_zahlungsdaten.py; Eingangsfilter, Log-/Audit-Scan und 100-fach parallele Last: backend/platform CardDataFilterTest und
backend/app CardDataIntegrationTest.
"""
import copy
import sys
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / "api" / "tools"))
import lint  # noqa: E402
from bundle import bundle  # noqa: E402


class ZahlungsdatentrennungVertrag(unittest.TestCase):
    def test_openapi_ohne_kartendatenfeld(self):
        doc = bundle()
        self.assertGreater(len(doc["components"]["schemas"]), 50)   # die Prüfung läuft über die echten Verträge
        self.assertEqual(lint.card_data_violations(doc), [])
        self.assertEqual([e for e in lint.lint_doc(doc) if "SEC-008" in e], [])

    def test_openapi_schema_mit_kartendatenfeld_scheitert(self):
        for field in ("card_number", "cardNumber", "pan", "cvc", "cvv2", "expiry_date", "expiration_month", "exp_year", "cardholder_name", "card_holder"):
            doc = copy.deepcopy(bundle())
            doc["components"]["schemas"]["Passenger"]["properties"][field] = {"type": "string"}
            errs = [e for e in lint.lint_doc(doc) if "SEC-008" in e]
            self.assertEqual(len(errs), 1, f"{field}: {errs}")
        doc = copy.deepcopy(bundle())
        doc["paths"]["/v1/passengers"]["get"]["parameters"].append({"name": "card_number", "in": "query", "schema": {"type": "string"}})
        self.assertTrue([e for e in lint.lint_doc(doc) if "SEC-008" in e])

    def test_psp_referenzwerte_bleiben_erlaubt(self):
        names = {"payment_method_token", "provider_ref", "provider", "payment_state", "authorized_minor"}
        self.assertEqual(lint.card_data_violations({"properties": {n: {"type": "string"} for n in names}}), [])
        # Die Verträge führen den PSP-Token tatsächlich nur als Referenzwert (Beispiel ausdrücklich ohne echte Daten)
        text = (REPO / "api" / "federation.yaml").read_text(encoding="utf-8")
        self.assertIn("psp_test_token_not_a_real_credential", text)

    def test_datenflussdokument_nennt_grenzen_und_nachweise(self):
        doc = (REPO / "docs/umsetzungsplan/vertraege/psp-trennung.md").read_text(encoding="utf-8")
        for needle in ("PSP-gehostet", "Systemgrenze", "provider_ref", "CardDataFilter", "test_keine_kartendatenspalte_im_schema",
                       "test_openapi_ohne_kartendatenfeld", "CardDataFilterTest", "WP-M2-29", "PCI"):
            self.assertIn(needle, doc, needle)


if __name__ == "__main__":
    unittest.main()
