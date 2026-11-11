# web

TypeScript/React-Weboberflächen: Designsystem, Passenger-PWA, Operator (Leitstelle, Callcenter, Finanzen, Personal, Admin).

- **Verantwortlich:** Betrieb/Mobil (Rolle laut D-12, noch keine Personen benannt)
- **Build/Test:** `npm ci && npm run lint && npm test && npm run build` (im Verzeichnis `web/`)
- **Regeln:** [CONTRIBUTING.md](../CONTRIBUTING.md)

## Struktur

npm-Workspaces-Monorepo (`package.json`, `package-lock.json` im Verzeichnis `web/`).

| Paket                                       | Inhalt                                                                                                                    |
| ------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| `design-system` (`@taktovia/design-system`) | Gemeinsame Komponenten (Platzhalter `Button`, `PageShell`), axe-core-Testbaustein unter `@taktovia/design-system/testing` |
| `passenger` (`@taktovia/passenger`)         | Passenger-PWA mit leerer Startseite, Web-Manifest und Service-Worker-Grundgerüst                                          |
| `operator` (`@taktovia/operator`)           | Operator-Shell mit Routen-Platzhaltern Callcenter, Finanzen, Personal, Admin                                              |

Paketgrenzen: Apps hängen vom `design-system` ab, nicht umgekehrt; `passenger` und `operator` hängen nicht voneinander ab. Es sind keine UI-IDs aus Kap. 06 §6.9 umgesetzt.

## Befehle

| Befehl                               | Wirkung                                                                 |
| ------------------------------------ | ----------------------------------------------------------------------- |
| `npm ci`                             | Reproduzierbare Installation aus `package-lock.json`                    |
| `npm run lint`                       | ESLint (typescript-eslint strict, react-hooks) und Prettier-Prüfung     |
| `npm run format`                     | Prettier-Formatierung anwenden                                          |
| `npm run typecheck`                  | `tsc --noEmit` je Paket (strikte Konfiguration in `tsconfig.base.json`) |
| `npm test`                           | Vitest (jsdom, Testing Library, axe-core) je Paket                      |
| `npm run build`                      | Typprüfung und Vite-Build je Paket (Ausgabe `dist/`, nicht versioniert) |
| `npm run dev -w @taktovia/passenger` | Lokaler Entwicklungsserver (analog `operator`)                          |

Ein Typfehler oder ein axe-Verstoß (siehe negativer Test im `design-system`: Schaltfläche ohne zugänglichen Namen) bricht den Lauf ab.

## Versionen

- **Node.js:** Ziel ist Node 24 LTS (Versionsmatrix, `.nvmrc`). Lokal und in dieser Umgebung wurde mit Node 22.23.4 gebaut; `engines` verlangt `>=22.12.0` (Mindestanforderung von Vite 8 und Vitest 5). Ein Lauf unter Node 24 steht aus.
- **React:** 19.3.1, alle Abhängigkeiten mit festen Versionen (kein `^`/`~`, `.npmrc` mit `save-exact`).
- **TypeScript:** 6.0.3 statt der in der Versionsmatrix genannten 7.0.2. Grund: `typescript-eslint` 8.72.2 unterstützt TypeScript nur `<6.1.0`. Wechsel auf 7.x, sobald die Lint-Werkzeugkette es trägt.
- **Werkzeuge:** Vite 8.3.8, Vitest 5.1.0, ESLint 10.14.0, Prettier 3.9.10, axe-core 4.14.0 (Details in `package.json`).

## PWA-Grundlage

`passenger` und `operator` liefern ein Web-Manifest (`public/manifest.webmanifest`, noch ohne Icons); `passenger` registriert im Produktionsbuild einen Service Worker (`public/sw.js`) ohne Caching-Strategie. Offline-Verhalten und Icons folgen mit den Bildschirmen.

## Bekannte Hinweise

- `npm audit` meldet moderate Befunde in transitiven Entwicklungsabhängigkeiten (minimatch über ESLint/typescript-eslint); sie betreffen keine ausgelieferten Laufzeitpakete. Bewertung gehört zur Lieferketten-Stufe (WP-M0-14).
- Farbkontrast wird in den axe-Tests nicht geprüft, da jsdom kein Layout berechnet.
