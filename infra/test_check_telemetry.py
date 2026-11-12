"""Selbsttests von check_telemetry.py: positiv (Ist-Stand grün) und negativ (absichtliche Verstöße werden rot)."""
import pathlib
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import check_telemetry  # noqa: E402

INFRA = pathlib.Path(__file__).parent
REPO = INFRA.parent


class TelemetryCheckTests(unittest.TestCase):
    def copy(self):
        tmp = pathlib.Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp, True)
        dst = tmp / "infra"
        shutil.copytree(INFRA, dst, ignore=shutil.ignore_patterns("__pycache__", ".env"))
        return dst

    def mutate(self, rel, old, new):
        d = self.copy()
        f = d / rel
        text = f.read_text(encoding="utf-8")
        self.assertIn(old, text)
        f.write_text(text.replace(old, new, 1), encoding="utf-8")
        return check_telemetry.run(d, REPO)[0]

    def test_positiv(self):
        errs, _ = check_telemetry.run(INFRA, REPO)
        self.assertEqual(errs, [])

    def test_scrape_ziel_je_komponente_pflicht(self):
        errs = self.mutate("observability/prometheus.yml", "job_name: routing-worker", "job_name: anderes")
        self.assertTrue(any("routing-worker" in e for e in errs), errs)

    def test_fachkern_nicht_auf_api_port(self):
        errs = self.mutate("observability/prometheus.yml", "host.docker.internal:9464", "host.docker.internal:8080")
        self.assertTrue(any("API-Port" in e for e in errs), errs)

    def test_log_pipeline_ohne_maskierung_wird_rot(self):
        errs = self.mutate("observability/otel-collector-config.yaml", "attributes/pii_guard, transform/log_masking, batch", "attributes/pii_guard, batch")
        self.assertTrue(any("Maskierung" in e for e in errs), errs)

    def test_dashboard_mit_unbekannter_metrik_wird_rot(self):
        errs = self.mutate("observability/dashboards/taktovia-kern.json", "taktovia_outbox_depth{", "taktovia_gibt_es_nicht{")
        self.assertTrue(any("taktovia_gibt_es_nicht" in e for e in errs), errs)

    def test_gateway_log_mit_query_wird_rot(self):
        errs = self.mutate("gateway/nginx.conf.template", '"path":"$uri"', '"path":"$request_uri"')
        self.assertTrue(any("request_uri" in e for e in errs), errs)

    def test_anonymer_grafana_zugang_wird_rot(self):
        errs = self.mutate("compose/docker-compose.telemetry.yml", 'GF_AUTH_ANONYMOUS_ENABLED: "false"', 'GF_AUTH_ANONYMOUS_ENABLED: "true"')
        self.assertTrue(any("anonymer Zugang" in e for e in errs), errs)

    def test_geheimnis_im_compose_wird_rot(self):
        errs = self.mutate("compose/docker-compose.telemetry.yml", "REDIS_ADDR: redis://redis:6379", "REDIS_PASSWORD: geheim123")
        self.assertTrue(any("REDIS_PASSWORD" in e for e in errs), errs)


if __name__ == "__main__":
    unittest.main()
