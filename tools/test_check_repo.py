import shutil, tempfile, unittest
from pathlib import Path
import check_repo

ROOT = Path(__file__).resolve().parents[1]


def copy():
    tmp = Path(tempfile.mkdtemp())
    for p in ROOT.rglob('*'):
        if '.git' in p.parts or 'build' in p.parts or '.gradle' in p.parts or '.kotlin' in p.parts or p.is_dir(): continue
        rel = p.relative_to(ROOT)
        if rel.parts[0] in ('docs',) and rel.name != 'versionen.md' and rel.name != 'README.md': continue
        (tmp / rel).parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(p, tmp / rel)
    return tmp


class CheckRepo(unittest.TestCase):
    def test_aktueller_stand_ist_gruen(self):
        self.assertEqual(check_repo.check(ROOT), [])

    def test_fehlendes_readme_faellt_auf(self):
        t = copy(); (t / 'web/README.md').unlink()
        self.assertTrue(any('web/README.md' in e for e in check_repo.check(t)))

    def test_fehlendes_lockfile_und_codeowner_faellt_auf(self):
        t = copy(); (t / 'backend/platform/gradle.lockfile').unlink()
        co = t / '.github/CODEOWNERS'; co.write_text('\n'.join(l for l in co.read_text().splitlines() if '/mobile/' not in l))
        errs = check_repo.check(t)
        self.assertTrue(any('gradle.lockfile' in e for e in errs)); self.assertTrue(any('mobile' in e for e in errs))

    def test_latest_in_versionsmatrix_faellt_auf(self):
        t = copy(); v = t / 'docs/umsetzungsplan/versionen.md'
        v.write_text(v.read_text().replace('| 19.3.1 |', '| latest |'))
        self.assertTrue(any('latest' in e for e in check_repo.check(t)))


if __name__ == '__main__':
    unittest.main()
