"""WP-M1-03 / LH-6.17-INT-001 AK 3: Die Dokumentation nennt Version, Sicherheits-Scopes und Fehlercodes je Endpunkt."""
import sys
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'api/tools'))
import gen_docs  # noqa: E402
from bundle import bundle  # noqa: E402


class ApiDocumentation(unittest.TestCase):
    def setUp(self):
        self.doc = bundle()
        self.text = gen_docs.render(self.doc)

    def test_doku_nennt_version_scopes_fehlercodes_je_endpunkt(self):
        self.assertEqual(gen_docs.check_doc(self.text, self.doc), [])
        ops = list(gen_docs.operations(self.doc))
        self.assertGreaterEqual(len(ops), 51)
        self.assertEqual(self.text.count('<article id='), len(ops), 'jeder Endpunkt genau einmal')
        # konkret: getEffectiveConfig nennt Version, Scope und seine Fehlercodes
        start = self.text.index('<article id="getEffectiveConfig">')
        block = self.text[start:self.text.index('</article>', start)]
        for needle in (self.doc['info']['version'], '<code>config:read</code>', '<code>UNAUTHENTICATED</code>', '<code>FORBIDDEN</code>', '<code>RATE_LIMITED</code>', '<code>VALIDATION_FAILED</code>'):
            self.assertIn(needle, block)
        # Fehlercodes folgen den dokumentierten Status: publishConfig (If-Match) kennt 412 und 428
        start = self.text.index('<article id="publishConfig">')
        block = self.text[start:self.text.index('</article>', start)]
        for needle in ('<li>412: <code>REVISION_MISMATCH</code>', '<li>428: <code>PRECONDITION_REQUIRED</code>', '<code>IDEMPOTENCY_CONFLICT</code>', '<code>config:publish</code>'):
            self.assertIn(needle, block)
        # M2-Codes erscheinen nur an Föderationsendpunkten
        def article(oid):
            i = self.text.index(f'<article id="{oid}">')
            return self.text[i:self.text.index('</article>', i)]
        self.assertNotIn('OFFER_EXPIRED', article('getMe'))
        self.assertIn('OFFER_EXPIRED', article('holdOffer'))

    def test_veroeffentlichte_seite_entspricht_dem_vertrag(self):
        target = REPO / 'docs/api/index.html'
        self.assertTrue(target.exists(), 'docs/api/index.html fehlt (python api/tools/gen_docs.py --write)')
        self.assertEqual(target.read_text(), self.text, 'docs/api/index.html ist veraltet (python api/tools/gen_docs.py --write)')

    def test_doku_ohne_version_scope_oder_fehlercode_schlaegt_fehl(self):
        v = self.doc['info']['version']
        self.assertTrue(any('Version' in e for e in gen_docs.check_doc(self.text.replace(f'Vertragsversion <strong>{v}</strong>', 'Vertragsversion'), self.doc)))
        self.assertTrue(any('Scope config:read fehlt' in e for e in gen_docs.check_doc(self.text.replace('<code>config:read</code>', '<code>x</code>'), self.doc)))
        self.assertTrue(any('Fehlercode REVISION_MISMATCH fehlt' in e for e in gen_docs.check_doc(self.text.replace('<code>REVISION_MISMATCH</code>', ''), self.doc)))
        self.assertTrue(any('getMe: Endpunkt fehlt' in e for e in gen_docs.check_doc(self.text.replace('<article id="getMe">', '<article id="x">'), self.doc)))

    def test_seite_ist_statisch_ohne_skripte_und_externe_ressourcen(self):
        low = self.text.lower()
        for bad in ('<script', 'http://', 'src=', 'onclick', '<link'):
            self.assertNotIn(bad, low)


if __name__ == '__main__':
    unittest.main()
