# Bereitstellung in die gemeinsame Integrationsumgebung (WP-M0-35)

**Status: Entwurf. Nichts davon wurde ausgeführt.** Es gibt keine Pipeline-Stufe (`.github/` liegt außerhalb dieser Zuweisung), kein Hostingkonto (D-05) und keinen Docker-Daemon. Akzeptanzkriterien 1–5 sind damit **nicht erfüllt**; geliefert sind nur Vorabverträge und ein Entwurf.

## Reihenfolge und Abbruchregeln

| # | Schritt | Abbruchregel |
|---|---|---|
| 0 | Sperre (Serialisierung): genau eine Bereitstellung je Umgebung gleichzeitig; die jüngere wartet, am Ende läuft der jüngere Stand | Sperre nicht erhältlich nach Frist: Abbruch ohne Änderung |
| 1 | Signaturprüfung des Images (WP-M0-14) | unsigniert/manipuliert: Abbruch, nichts wird ausgerollt |
| 2 | Flyway-Migration als eigener kurzlebiger Schritt mit Migrationszugang | Fehler: Abbruch, neue Anwendungsversion startet nicht, alte bleibt laufen |
| 3 | Start `backend/app` mit Runtime-Zugang (ohne Migrationszugang) | Startfehler: alte Version bleibt bzw. wird wiederhergestellt |
| 4 | Rauchtest: Health, Migrationsstand, Rollenattribute der Runtime-Verbindung (kein Superuser, kein BYPASSRLS, kein Tabelleneigentum), Keycloak erreichbar; Ergebnis mit Build-ID | Fehlschlag: Bereitstellung gilt als fehlgeschlagen, Rücknahme |

## Rücknahme
Vorheriges Image erneut ausrollen (Schritte 0, 1, 3, 4). Die Datenbank wird nur vorwärts korrigiert (Kap. 01 §1.8).

## Übergangslösung (solange D-05 kein Hostingkonto liefert)
Integrationshost auf Basis von `infra/compose/docker-compose.yml` (**vorläufig**; Wechselbedingung auf die Zielplattform: Hostingkonto/Region aus D-05 und angewendetes IaC-Gerüst). Muss im Gate-Bericht (WP-M0-34) stehen. PSP-Simulator (WP-M0-23) und Sandbox-Partner (WP-M0-24) kommen als nur im Testnetz erreichbare Dienste hinzu, sobald vorhanden.

## Geheimnisse und Umgebungen
Siehe `geheimnisse.csv`, `geheimnisse.md`, `umgebungsmatrix.csv`. Nur synthetische Daten und Testidentitäten (Realm `infra/keycloak/realm-taktovia-dev.json`).

## Pipeline-Entwurf
`pipeline-entwurf.yml.example` (nicht aktiv, nicht verifiziert) skizziert die Stufe; Übernahme nach `.github/` gehört zu WP-M0-14.
