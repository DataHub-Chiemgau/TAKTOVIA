# Release-Gate (WP-M5-22, LH-6.20-QAT-002)

Die CI verweigert ein Release, sobald eine Pflichtprüfung für Kernbuchung und Disposition fehlt oder fehlschlägt (Kap. 08 §8.4). Vertrag:
[LH-6.20-QAT-002](../../docs/umsetzungsplan/vertraege/LH-6.20-QAT-002.md); Tests: [tests/acceptance/release](../../tests/acceptance/release/test_release_gate.py).

| Datei | Inhalt |
|---|---|
| `pflichtpruefungen.json` | Stufen und ihre Prüfungen (Befehl, JUnit-Ergebnis, Abdeckung, semantische Fälle, Kapazitätsinvarianten, extern), Katalog der Kernfunktionen, Definition „Kernbuchung“/„Disposition“ |
| `funktionsabdeckung.csv` | Zuordnung Fachfunktion → Unit-, Integrations-, Contract-Test (Angebot, Hold, Confirm, Ablauf, Storno, Planrevision, Einfügeheuristik, Constraint-Validator) |
| `semantische_faelle.csv` | Zuordnung der Contract-Fälle Kap. 05 §5.8 (P1) und PH-AT-011 zu Tests |
| `kapazitaetsinvarianten.csv` | konkurrierende Invariantentests (mindestens zwei parallele Transaktionen, Kap. 04 §4.5; PH-AT-004, PH-AT-011) |
| `ausnahmen.json` | protokollierte Ausnahmen (Stufe, Freigeber, Grund, Erfassung, Ablauf) |
| `migration_check.py` | Migration frisch und aus dem freigegebenen Vorstand (`freigegebener_stand.txt`), Schemavergleich |
| `gate.py` | Läufer, Abdeckungsprüfung, Release-Artefakt (`run`, `abdeckung`, `verify`) |

```bash
cd backend && ./gradlew build                      # liefert die JUnit-Ergebnisse unter backend/**/build/test-results
python tools/release_gate/gate.py run              # lokaler Probelauf (Datenbankumgebung wie db/db.py); schreibt release-out/gate-bericht.json
python tools/release_gate/gate.py run --ci --ergebnisse junit-backend   # Release-Lauf (CI, Job release-gate)
python tools/release_gate/gate.py abdeckung [--strikt]
python tools/release_gate/gate.py verify release-out
```

## Regeln

- **Stufen:** Unit (ohne Datenbank/HTTP), Datenbankintegration unter der Runtime-Rolle, API-Contract (Lint, Bundle, Vertragslauf, Funktionsabdeckung), Migration frisch/aus Vorstand, Architektur (Modulgrenzen), Isolationssuite, semantische Contract-Fälle, Kapazitäts-/Idempotenzinvarianten mit parallelen Transaktionen, Lieferkette.
- **Release-Artefakt:** `release-manifest.json` mit Build-ID, Migrationsstand (höchste Version, Anzahl, SHA-256 der Migrationsdateien), Liste der bestandenen Prüfungen, Ausnahmen und Digests der Boot-Jars, dazu `release-manifest.sig` (HMAC-SHA256, Schlüssel `TAKTOVIA_RELEASE_SIGNING_KEY` aus der Umgebung, mindestens 32 Zeichen). Es entsteht nur bei vollständig grünem CI-Lauf; ein Probelauf (ohne `--ci`) wird nie signiert. **Nicht ausgeführt:** eine asymmetrische Lieferkettensignatur (z. B. cosign) und die Veröffentlichung des Artefakts; hier nur der Mechanismus „kein Artefakt ohne grünen Lauf“.
- **Umgehungsschutz:** Eine Stufe lässt sich nur mit Eintrag in `ausnahmen.json` überspringen. Freigeber, Grund und Ablauf sind alle Pflicht; der Ablauf liegt höchstens 30 Tage nach der Erfassung (Planungswert, OP-17) und in der Zukunft. Eine ausgeführte rote Stufe ist nie ausnehmbar. Die Ausnahme steht im Manifest. Änderungen an `ausnahmen.json` laufen wie jede Änderung nur per Pull Request mit Code-Owner-Review (CONTRIBUTING.md).
- **Begriff „Unit“:** Das Projekt testet Fachlogik überwiegend gegen die echte Datenbank (Modultests). Als Unit-Test zählt hier nur ein Test ohne Datenbank, HTTP-Fixture und Uhr; die Zuordnung weist Unit-Tests, die Fixtures nutzen, ab. Reine Unit-Tests der Kernbuchung: `KernbuchungUnitTest` und die Python-Tests des Routing-Workers.
- **Extern:** Gitleaks und OSV-Scanner laufen als eigener CI-Job `lieferkette`; deren Ergebnis kommt als `TAKTOVIA_GATE_EXTERN_<NAME>=success`. Im lokalen Probelauf gelten sie als „nicht ausgeführt“.
- **Vorstand:** Bis zum ersten Pilotrelease gibt es keinen ausgelieferten Stand; `freigegebener_stand.txt` nennt als Platzhalter den Integrationsstand nach Welle 11 (OP-17). Mit dem ersten Release auf dessen Tag setzen.
