"""Prueft, dass eine Entwicklungsdatenbank ausschliesslich synthetische Daten enthaelt (WP-M1-40, LH-6.18-SEC-012).

Die Datenherkunft laut Umgebungsmatrix (infra/umgebungsmatrix.json) ist fuer Entwicklung/Staging/Performance "synthetisch". Synthetisch heisst
hier: jede Zeile mit Personen- oder Organisationsbezug traegt die Markierung des Seed-Generators (tests/acceptance/taktovia_at/seed.py):
Mandanten-, Rechtstraeger- und Organisationsnamen enden auf "(synthetisch)", Fahrgastpseudonyme beginnen mit "synthetic-token-", Mitgliedschaften
nutzen die Testidentitaeten des Entwicklungs-IdP, Kontaktdaten sind leer. Alles andere ist ein Fund. Die Datenbank muss ausserdem die Kennung
`development` tragen (eine als staging/performance/production gekennzeichnete Datenbank ist keine Entwicklungsdatenbank).
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'db'))
import db as dbtool  # noqa: E402

SYNTHETIC_NAME = re.compile(r'\(synthetisch\)$')
SYNTHETIC_TOKEN = re.compile(r'^synthetic-token-')
TEST_SUBJECT = re.compile(r'^(a|b|ab|none)\.[a-z_]+$')   # Benutzernamen des Entwicklungs-IdP (infra/keycloak)
DEV_ISSUER = re.compile(r'^http://(localhost|127\.0\.0\.1):\d+/realms/taktovia-dev$')


def scan(dbname, allow_environments=('development',)):
    """Liste der Funde (leer = nur synthetische Daten)."""
    found = []
    with dbtool.admin(dbname) as c:
        mark = c.execute('SELECT environment FROM taktovia_meta.environment_marker').fetchall()
        if len(mark) != 1 or mark[0][0] not in allow_environments:
            found.append(f'Umgebungskennung {mark[0][0] if mark else None!r} ist keine Entwicklungsumgebung ({", ".join(allow_environments)})')
        checks = [
            ('tenant', 'SELECT id::text, name FROM taktovia.tenant', lambda r: SYNTHETIC_NAME.search(r[1]), 'Name ohne Synthetik-Markierung'),
            ('legal_entity', 'SELECT id::text, name FROM taktovia.legal_entity', lambda r: SYNTHETIC_NAME.search(r[1]), 'Name ohne Synthetik-Markierung'),
            ('organization', 'SELECT id::text, name FROM taktovia.organization', lambda r: SYNTHETIC_NAME.search(r[1]), 'Name ohne Synthetik-Markierung'),
            ('passenger_private', 'SELECT id::text, subject_token, contact_ciphertext IS NOT NULL OR support_ciphertext IS NOT NULL FROM taktovia.passenger_private',
             lambda r: SYNTHETIC_TOKEN.search(r[1]) and not r[2], 'Pseudonym ohne Synthetik-Markierung oder Kontaktdaten vorhanden'),
            ('membership', 'SELECT id::text, issuer, subject FROM taktovia.membership', lambda r: DEV_ISSUER.match(r[1]) and TEST_SUBJECT.match(r[2]),
             'Subject/Issuer nicht aus dem Entwicklungs-IdP'),
        ]
        for table, query, ok, why in checks:
            for row in c.execute(query).fetchall():
                if not ok(row):
                    found.append(f'{table} {row[0]}: {why}')
    return found


if __name__ == '__main__':
    name = dbtool.env('TAKTOVIA_DB_NAME', 'taktovia')
    res = scan(name)
    for f in res:
        print('FUND', f)
    print(f'Synthetik-Pruefung {name}:', 'FEHLER' if res else 'nur synthetische Daten')
    sys.exit(1 if res else 0)
