"""Selbsttests von check.py: positiv (Ist-Stand gruen) und negativ (absichtliche Verstoesse werden rot)."""
import pathlib
import re
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import check  # noqa: E402

INFRA = pathlib.Path(__file__).parent


class CheckTests(unittest.TestCase):
    def copy(self):
        tmp = pathlib.Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp, True)
        dst = tmp / "infra"
        shutil.copytree(INFRA, dst, ignore=shutil.ignore_patterns("__pycache__", ".env"))
        return dst

    def test_positive(self):
        errs, _ = check.run(INFRA)
        self.assertEqual(errs, [])

    def test_missing_required_tf_param(self):
        d = self.copy()
        f = d / "terraform/envs/staging/terraform.tfvars.example"
        f.write_text(re.sub(r"hosting_region.*\n", "", f.read_text()))
        errs, _ = check.run(d)
        self.assertTrue(any("hosting_region" in e for e in errs), errs)

    def test_gateway_klartext_und_alte_tls_version_werden_rot(self):
        d = self.copy()
        f = d / "gateway/nginx.conf.template"
        orig = f.read_text()
        for old, new, needle in (("ssl_protocols TLSv1.2 TLSv1.3;", "ssl_protocols TLSv1 TLSv1.2 TLSv1.3;", "ssl_protocols"),
                                 ("add_header Strict-Transport-Security", "add_header X-Foo", "HSTS"),
                                 ("return 444;", "return 301 https://x;", "Klartext-Port"),
                                 ("proxy_pass https://backend", "proxy_pass http://backend", "Klartext-Upstream")):
            f.write_text(orig.replace(old, new))
            self.assertTrue(any(needle in e for e in check.run(d)[0]), needle)

    def test_alerting_klartextadresse_und_zweiter_kanal_werden_rot(self):
        d = self.copy()
        f = d / "alerting/alertmanager.yml"
        orig = f.read_text()
        f.write_text(orig.replace("url_file: /run/secrets/alert_channel_credential", "url: https://hooks.example/x"))
        self.assertTrue(any("url_file" in e for e in check.run(d)[0]))
        f.write_text(orig + "  - name: zweiter\n    webhook_configs: [{ url_file: /x }]\n")
        self.assertTrue(any("nicht genau ein Empfaengerkanal" in e for e in check.run(d)[0]))

    def test_shared_secret_domain(self):
        d = self.copy()
        f = d / "terraform/envs/production/terraform.tfvars.example"
        f.write_text(f.read_text().replace("taktovia-production-secrets", "taktovia-staging-secrets"))
        self.assertTrue(any("geteilt" in e for e in check.run(d)[0]))

    def test_privileged_role_without_mfa(self):
        d = self.copy()
        f = d / "keycloak/realm-taktovia-dev.json"
        f.write_text(f.read_text().replace('"mfa_required": [\n          "true"\n        ]', '"mfa_required": [\n          "false"\n        ]', 1))
        self.assertTrue(any("mfa_required" in e for e in check.run(d)[0]))

    def test_secret_pattern(self):
        d = self.copy()
        (d / "deploy/x.txt").write_text("-----BEGIN " + "RSA PRIVATE KEY-----\n")
        self.assertTrue(any("secret-Muster" in e for e in check.run(d)[0]))

    def test_compose_service_without_healthcheck(self):
        d = self.copy()
        f = d / "compose/docker-compose.yml"
        f.write_text(f.read_text().replace('healthcheck:\n      test: ["CMD", "mc", "ready", "local"]', 'x-hc:\n      test: ["CMD", "mc", "ready", "local"]'))
        self.assertTrue(any("minio ohne Healthcheck" in e for e in check.run(d)[0]))


if __name__ == "__main__":
    unittest.main()
