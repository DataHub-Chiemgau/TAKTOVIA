"""WP-M1-40 (LH-6.18-SEC-012): Umgebungsmatrix und Trennung der Zugänge.

Prüft `infra/umgebungsmatrix.json` gegen Terraform-Parameter, Realm-Exporte, Bereitstellungsmatrix und Compose-Konfiguration:
je Umgebung eigenes Konto, Netz, Datenbankhost, Identitätsdomäne (Realm/Issuer), Secret- und PSP-Domäne, eigene Datenbankzugänge;
kein Netzpfad und keine geteilten Zugangsdaten zwischen Entwicklung und Produktion; Entwicklung enthält keine Spur der Produktionsbezeichner.
Statische Prüfung der Konfiguration als Code: weder Terraform noch Netze noch ein Keycloak wurden hier ausgeführt (kein Docker/Terraform).
"""
import copy
import json
import sys
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / 'infra'))
import check_env_separation as ces  # noqa: E402

INFRA = REPO / 'infra'
ENVS = ['development', 'staging', 'performance', 'production']


def matrix():
    return json.loads((INFRA / 'umgebungsmatrix.json').read_text(encoding='utf-8'))


class Umgebungsmatrix(unittest.TestCase):
    def test_kein_netzpfad_und_keine_geteilten_secrets_zwischen_entwicklung_und_produktion(self):
        m = matrix()
        self.assertEqual(list(m['umgebungen']), ENVS)
        self.assertEqual(ces.check(m, INFRA), [])
        dev, prod = m['umgebungen']['development'], m['umgebungen']['production']
        # ausdrücklich: Entwicklung und Produktion teilen weder Konto, Netz, Datenbank, Identität noch Secrets
        for key in (('konto',), ('netz',), ('datenbank', 'host'), ('identitaet', 'realm'), ('identitaet', 'issuer'), ('secrets', 'domaene'), ('psp', 'domaene'),
                    ('datenbank', 'migrationszugang'), ('datenbank', 'laufzeitzugang')):
            self.assertNotEqual(ces.dig(dev, key), ces.dig(prod, key), key)
        self.assertFalse(any(p['von'] != p['nach'] for p in m['netzpfade']), 'jeder erlaubte Netzpfad bleibt in der eigenen Umgebung')
        self.assertEqual(prod['datenbank']['erlaubte_quellnetze'], [prod['netz']])
        self.assertNotIn('production', dev['erlaubte_dump_herkunft'])
        self.assertEqual(prod['erlaubte_dump_herkunft'], ['production'])

    def test_verstoesse_werden_erkannt(self):
        base = matrix()

        def broken(mutate):
            m = copy.deepcopy(base)
            mutate(m)
            return ces.check(m, INFRA)

        shared = broken(lambda m: m['umgebungen']['development']['secrets'].update(domaene=m['umgebungen']['production']['secrets']['domaene']))
        self.assertTrue(any('secrets.domaene' in e and 'geteilt' in e for e in shared), shared)
        self.assertTrue(any('geteilt' in e for e in broken(lambda m: m['umgebungen']['development']['identitaet'].update(realm='taktovia-production'))))
        self.assertTrue(any('geteilt' in e for e in broken(lambda m: m['umgebungen']['staging'].update(konto=m['umgebungen']['production']['konto']))))
        path = broken(lambda m: m['netzpfade'].append({'von': 'taktovia-development-net', 'nach': 'taktovia-production-net', 'zweck': 'Debug'}))
        self.assertTrue(any('Netzpfad' in e and 'taktovia-development-net' in e for e in path), path)
        ingress = broken(lambda m: m['umgebungen']['production']['datenbank'].update(erlaubte_quellnetze=['taktovia-production-net', 'taktovia-development-net']))
        self.assertTrue(any('Datenbank' in e and 'Quellnetz' in e for e in ingress), ingress)
        self.assertTrue(any('Dump' in e for e in broken(lambda m: m['umgebungen']['development'].update(erlaubte_dump_herkunft=['synthetisch', 'production']))))
        self.assertTrue(any('Migrations' in e for e in broken(lambda m: m['umgebungen']['development']['datenbank'].update(laufzeitzugang=m['umgebungen']['development']['datenbank']['migrationszugang']))))
        self.assertTrue(any('Umgebungskennung' in e for e in broken(lambda m: m['umgebungen']['staging'].update(umgebungskennung='production'))))
        self.assertTrue(any('Datenherkunft' in e or 'Datenklasse' in e for e in broken(lambda m: m['umgebungen']['development'].update(datenklasse='Produktionsdaten'))))

    def test_realms_terraform_und_compose_sind_je_umgebung_getrennt(self):
        m = matrix()
        for env in ENVS:
            u = m['umgebungen'][env]
            realm = json.loads((INFRA / u['identitaet']['realm_datei']).read_text(encoding='utf-8'))
            self.assertEqual(realm['realm'], u['identitaet']['realm'])
            self.assertEqual(realm['attributes']['taktovia.environment'], env)
            self.assertTrue(u['identitaet']['issuer'].endswith('/realms/' + realm['realm']))
            tf = ces.tfvars(INFRA / 'terraform/envs' / env / 'terraform.tfvars.example')
            self.assertEqual(tf['secret_domain'], u['secrets']['domaene'])
            self.assertEqual(tf['identity_domain'], u['identitaet']['domaene'])
            self.assertEqual(tf['account_domain'], u['konto'])
            self.assertEqual(tf['network_domain'], u['netz'])
            self.assertEqual(tf['db_ingress_network_domain'], u['netz'])
        # Entwicklungs-Compose und Entwicklungs-Terraform enthalten keine Bezeichner anderer Umgebungen
        self.assertEqual(ces.cross_contamination(m, INFRA), [])
        leaked = ces.cross_contamination(m, INFRA, extra_files={'compose/docker-compose.yml': 'jdbc:postgresql://postgres.production.taktovia.internal:5432/x'})
        self.assertTrue(any('production' in e for e in leaked), leaked)


if __name__ == '__main__':
    unittest.main()
