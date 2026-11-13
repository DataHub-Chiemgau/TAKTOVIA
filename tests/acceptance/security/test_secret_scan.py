"""WP-M1-36 (LH-6.18-SEC-003): der Secret-Scan findet Zugangsschlüssel in Quellcode, Git-Historie und Auslieferungsartefakten
und lässt den Lauf (Exit != 0, CI-Build) scheitern; das echte Repository ist ohne Funde. Testgeheimnisse werden zur Laufzeit
zusammengesetzt, damit dieses Repository selbst keinen Fund enthält. Ausgabe enthält nie den Wert."""
import io
import subprocess
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
TOOL = REPO / "tools" / "secret_scan.py"
sys.path.insert(0, str(REPO / "tools"))
import secret_scan  # noqa: E402

KEY = "AKIA" + "ABCDEFGHIJKLMNOP"            # bekanntes Schlüsselmuster (AWS-Zugangsschlüsselkennung)
PEM = "-----BEGIN " + "OPENSSH PRIVATE KEY-----"


def run(*args):
    p = subprocess.run([sys.executable, str(TOOL), *map(str, args)], capture_output=True, text=True)
    return p.returncode, p.stdout + p.stderr


def git(repo, *a):
    subprocess.run(["git", "-C", str(repo), "-c", "user.name=t", "-c", "user.email=t@example.test", *a], check=True, capture_output=True)


class SecretScan(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)

    def repo(self):
        r = self.dir / "repo"
        r.mkdir()
        git(r, "init", "-q")
        (r / "a.txt").write_text("harmlos\n")
        git(r, "add", "-A")
        git(r, "commit", "-qm", "init")
        return r

    def test_repository_ohne_funde(self):
        rc, out = run("tree", REPO)
        self.assertEqual(rc, 0, out)

    def test_testsecret_laesst_build_scheitern(self):
        r = self.repo()
        (r / "config.properties").write_text(f"aws_access_key_id={KEY}\n")
        rc, out = run("tree", r)
        self.assertEqual(rc, 1)
        self.assertIn("aws-access-key", out)
        self.assertNotIn(KEY, out, "der Wert darf nie ausgegeben werden")
        (r / "config.properties").write_text("aws_access_key_id=secret://taktovia-development-secrets/adapter_client_secrets\n")
        self.assertEqual(run("tree", r)[0], 0)

    def test_zugangswert_in_konfiguration_wird_gefunden_referenzen_und_platzhalter_nicht(self):
        r = self.repo()
        pw = "Zx9" + "kQ2mLp7vRt4wYb"
        (r / "app.yml").write_text(f"spring:\n  datasource:\n    password: {pw}\n")
        rc, out = run("tree", r)
        self.assertEqual(rc, 1, out)
        self.assertNotIn(pw, out)
        (r / "app.yml").write_text("password: ${DB_PASSWORD}\ntoken: secret://taktovia-staging-secrets/x_y\napi_key: dev-only-abcdefghijklmnop\n")
        self.assertEqual(run("tree", r)[0], 0)

    def test_git_historie_ohne_zugangsschluessel(self):
        r = self.repo()
        self.assertEqual(run("history", r)[0], 0)
        (r / "b.txt").write_text(PEM + "\n")
        git(r, "add", "-A"); git(r, "commit", "-qm", "versehentlich")
        (r / "b.txt").unlink()
        git(r, "add", "-A"); git(r, "commit", "-qm", "entfernt")
        self.assertEqual(run("tree", r)[0], 0, "im Arbeitsbaum nicht mehr vorhanden")
        rc, out = run("history", r)
        self.assertEqual(rc, 1, "die Historie enthält den Schlüssel weiterhin")
        self.assertIn("private-key", out)

    def test_git_historie_des_repositories(self):
        rc, out = run("history", REPO)
        self.assertEqual(rc, 0, out)
        self.assertIn("4 genutzte Ausnahmen", out, "jede Ausnahme wird im Lauf aufgeführt (Allowlist: drei Einträge, die Lastlauf-Ausnahme deckt zwei Fingerabdrücke)")

    def test_image_mit_env_datei_wird_abgewiesen(self):
        layer = io.BytesIO()
        with tarfile.open(fileobj=layer, mode="w") as lt:
            for name, data in (("app/app.jar.txt", b"nichts"), ("app/.env", b"DB=x\n")):
                ti = tarfile.TarInfo(name); ti.size = len(data); lt.addfile(ti, io.BytesIO(data))
        image = self.dir / "image.tar"
        with tarfile.open(image, "w") as it:   # `docker save`-ähnlich: Archiv mit Schicht-Archiv
            data = layer.getvalue(); ti = tarfile.TarInfo("abc123/layer.tar"); ti.size = len(data); it.addfile(ti, io.BytesIO(data))
        rc, out = run("artifact", image)
        self.assertEqual(rc, 1, out)
        self.assertIn("verbotene-datei", out)
        self.assertIn(".env", out)

    def test_auslieferungsartefakt_ohne_zugangsschluessel(self):
        art = self.dir / "rootfs"
        (art / "app").mkdir(parents=True)
        (art / "app" / "application.yml").write_text("spring:\n  datasource:\n    password: ${TAKTOVIA_DB_RUNTIME_PASSWORD}\n")
        (art / "app" / ".env.example").write_text("PASSWORD=dev-only-x\n")
        (art / "sbom.json").write_text('{"components":[{"name":"spring-core","version":"7.0"}]}\n')
        self.assertEqual(run("artifact", art)[0], 0)
        (art / "sbom.json").write_text(f'{{"note":"token={KEY}"}}\n')
        rc, out = run("artifact", art)
        self.assertEqual(rc, 1); self.assertNotIn(KEY, out)

    def test_abgelaufene_oder_begruendungslose_ausnahme_ist_ein_fund(self):
        al = self.dir / "al.csv"
        al.write_text("pfad;muster;grund;ablauf\nx;private-key;;2099-01-01\ny;private-key;alt;2020-01-01\nz;private-key;ok;bitte\n")
        _, errors = secret_scan.load_allowlist(al)
        self.assertEqual(len(errors), 3, errors)
        al.write_text("pfad;muster;grund;ablauf\nx;private-key;ok;2099-01-01\n")
        active, errors = secret_scan.load_allowlist(al)
        self.assertEqual((len(active), errors), (1, []))


if __name__ == "__main__":
    unittest.main()
