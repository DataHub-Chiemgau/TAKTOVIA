"""WP-M1-37 (LH-6.18-SEC-008): Schema-Lint „keine Spalte für PAN, CVC, Ablaufdatum oder Karteninhaber“.

`payment_intent.provider_ref` ist die einzige PSP-Referenz. Die Regel ist als Funktion `card_column_violations` auch auf neue DDL anwendbar
(eine Migration mit Kartendatenspalte scheitert); gleiche Namensmuster wie der OpenAPI-Lint (`api/tools/lint.py`).
"""
import re
import unittest
from pathlib import Path

from helpers import admin
from test_12_keine_secretspalten import ddl_columns

CARD_COLUMN = re.compile(r'(?i)^(pan|primary_?account_?number|card_?(number|no|num)|cc_?(number|num)|cvc2?|cvv2?|cid|card_?(security_?code|verification\w*)|'
                         r'(card_?)?expir(y|ation)\w*|exp_?(month|year|date)|card_?holder\w*|cardholder_?name)$')


def card_column_violations(columns):
    """columns: Iterable[(tabelle, spalte)] -> Verstöße (Spalte mit Kartendatennamen)."""
    return [(t, c) for t, c in columns if CARD_COLUMN.match(c)]


class KeineKartendatenspalte(unittest.TestCase):
    def test_keine_kartendatenspalte_im_schema(self):
        with admin() as c:
            cols = c.execute("""SELECT table_name, column_name FROM information_schema.columns WHERE table_schema='taktovia'""").fetchall()
        self.assertGreater(len(cols), 200)
        self.assertEqual(card_column_violations(cols), [])
        self.assertIn(('payment_intent', 'provider_ref'), cols)   # die einzige PSP-Referenz ist vorhanden (Prüfung wirksam)
        # nur provider_ref trägt eine PSP-Referenz: keine weitere Spalte mit Referenz-/Tokennamen in payment_intent
        pi = sorted(c for t, c in cols if t == 'payment_intent')
        self.assertEqual([c for c in pi if re.search(r'(?i)(_ref$|token|psp)', c)], ['provider_ref'])

    def test_migration_mit_kartendatenspalte_scheitert_am_lint(self):
        for col in ('card_number', 'pan', 'cvc', 'cvv2', 'expiry_date', 'expiration_month', 'exp_year', 'cardholder_name', 'card_holder'):
            bad = f"CREATE TABLE payment_method (tenant_id uuid, id uuid, provider_ref text, {col} text);"
            self.assertEqual(card_column_violations(ddl_columns(bad)), [('payment_method', col)], col)
        self.assertEqual(card_column_violations(ddl_columns("ALTER TABLE payment_intent ADD COLUMN card_number text")), [('payment_intent', 'card_number')])
        ok = "CREATE TABLE payment_method (tenant_id uuid, id uuid, provider_ref text, expires_at timestamptz, last4 text);"
        self.assertEqual(card_column_violations(ddl_columns(ok)), [])

    def test_alle_migrationen_im_repository_bestehen_den_lint(self):
        bad = []
        for f in sorted((Path(__file__).resolve().parents[1] / 'migrations').glob('V*__*.sql')):
            bad += [(f.name, *v) for v in card_column_violations(ddl_columns(f.read_text(encoding='utf-8')))]
        self.assertEqual(bad, [])


if __name__ == '__main__':
    unittest.main()
