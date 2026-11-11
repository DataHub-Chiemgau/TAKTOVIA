# Geheimnisverwaltung (Konzept, WP-M0-11 / WP-M0-35)

- Keine Geheimnisse im Repository (CONTRIBUTING). Das Repository enthält nur **Namen** von Geheimnisdomänen und Zugängen.
- Jede Umgebung hat eine **eigene** Geheimnis-, Identitäts- und PSP-Domäne (siehe `umgebungsmatrix.csv`); kein Geheimnis wird umgebungsübergreifend geteilt. `infra/check.py` prüft, dass die Domänennamen je Umgebung eindeutig sind.
- Migrationszugang und Laufzeitzugang sind getrennt (Kap. 01 §1.7): der Migrationszugang existiert nur im kurzlebigen Migrationsschritt; der Laufzeitprozess bekommt ihn nie. Zuordnung je Schritt: `geheimnisse.csv`.
- Lokal: nur Entwicklungswerte aus `compose/.env.example` (gekennzeichnet "nur lokal").
- Konkrete Geheimnisverwaltung (Anbieterdienst) ist bis D-05 offen.
