# web

TypeScript/React-Weboberflächen: Designsystem, Passenger-PWA, Operator (Leitstelle, Callcenter, Finanzen, Personal, Admin).

- **Verantwortlich:** Betrieb/Mobil (Rolle laut D-12, noch keine Personen benannt)
- **Build/Test:** `npm ci && npm run lint && npm test && npm run build` (im Verzeichnis `web/`)
- **Regeln:** [CONTRIBUTING.md](../CONTRIBUTING.md)

## Struktur

npm-Workspaces-Monorepo (`package.json`, `package-lock.json` im Verzeichnis `web/`).

| Paket                                       | Inhalt                                                                                                                                                                                                  |
| ------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `api-client` (`@taktovia/api-client`)       | Aus `api/*.yaml` erzeugte Typen und Operationstabelle (`src/generated.ts`, per `python api/tools/gen_ts_client.py --write`), Fetch-Laufzeit mit Problem-Fehlern, Rechte aus `/v1/me`, Sitzungs-Provider |
| `design-system` (`@taktovia/design-system`) | Komponenten, Tokens, i18n, Rahmen, Zustände, axe-core-Testbaustein unter `@taktovia/design-system/testing`; siehe [design-system/README.md](design-system/README.md)                                    |
| `passenger` (`@taktovia/passenger`)         | Passenger-PWA: App-Shell, Routen UI-01 bis UI-05 und Hilfe, Offlinezustand, Idempotenz-Befehle, Service Worker nur für App-Shell ([Vertrag](../docs/umsetzungsplan/vertraege/web-passenger-pwa.md))     |
| `operator` (`@taktovia/operator`)           | Operator-Shell mit Routen-Platzhaltern Callcenter, Finanzen, Personal, Admin                                                                                                                            |

Paketgrenzen: Apps hängen von `design-system` und `api-client` ab, nicht umgekehrt; `design-system` kennt den API-Client nicht; `passenger` und `operator` hängen nicht voneinander ab. Umgesetzt sind nur Rahmen und Grundgerüste (UI-17, UI-18, UI-20 als Leerzustand, UI-20 Dateninventar-Export); Fachbildschirme folgen in M2 bis M8.

## Anmeldung und Mandantenwechsel (WP-M1-44)

`api-client/src/auth/` enthält OIDC (Authorization Code mit PKCE), `AuthProvider` (Tokens nur im Arbeitsspeicher je Tab, Erneuerung, 401/403-Behandlung), Entwurfsspeicher je Mandant und die Hooks `useTenantSwitch`, `useMySessions`, `useSignOut`. Die Anwendungen binden sie in `main.tsx` ein (`AuthProvider` → `AuthGate` → `SessionProvider`); Konfiguration über `VITE_OIDC_ISSUER`, `VITE_OIDC_CLIENT_ID`, `VITE_API_ORIGIN`. Vertrag: [web-anmeldung.md](../docs/umsetzungsplan/vertraege/web-anmeldung.md). Tests laufen gegen lokale Ersatzmodelle (`@taktovia/api-client/testing`: `createMockIdp`, `createFakeApi`); Browser-Ende-zu-Ende mit echtem Keycloak ist **nicht ausgeführt**.

## Befehle

| Befehl                               | Wirkung                                                                                                                                   |
| ------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------- |
| `npm ci`                             | Reproduzierbare Installation aus `package-lock.json`                                                                                      |
| `npm run lint`                       | ESLint (typescript-eslint strict, react-hooks) und Prettier-Prüfung                                                                       |
| `npm run format`                     | Prettier-Formatierung anwenden                                                                                                            |
| `npm run typecheck`                  | `tsc --noEmit` je Paket (strikte Konfiguration in `tsconfig.base.json`)                                                                   |
| `npm test`                           | Vitest (jsdom, Testing Library, axe-core) je Paket                                                                                        |
| `npm run build`                      | Typprüfung und Vite-Build je Paket (Ausgabe `dist/`, nicht versioniert)                                                                   |
| `npm run a11y:browser`               | Barrierefreiheit im echten Chrome (axe mit Kontrast, 320 px, 200 % Text, Touchflächen); `CHROME_PATH` setzen                              |
| `npm run e2e:pwa`                    | PWA im echten Chrome gegen den Produktionsbuild (Manifest, Service Worker, Cache, Offline); vorher `npm run build -w @taktovia/passenger` |
| `npm run dev -w @taktovia/passenger` | Lokaler Entwicklungsserver (analog `operator`)                                                                                            |

Ein Typfehler oder ein axe-Verstoß (siehe negativer Test im `design-system`: Schaltfläche ohne zugänglichen Namen) bricht den Lauf ab.

## Versionen

- **Node.js:** Ziel ist Node 24 LTS (Versionsmatrix, `.nvmrc`). Lokal und in dieser Umgebung wurde mit Node 22.23.4 gebaut; `engines` verlangt `>=22.12.0` (Mindestanforderung von Vite 8 und Vitest 5). Ein Lauf unter Node 24 steht aus.
- **React:** 19.3.1, alle Abhängigkeiten mit festen Versionen (kein `^`/`~`, `.npmrc` mit `save-exact`).
- **TypeScript:** 6.0.3 statt der in der Versionsmatrix genannten 7.0.2. Grund: `typescript-eslint` 8.72.2 unterstützt TypeScript nur `<6.1.0`. Wechsel auf 7.x, sobald die Lint-Werkzeugkette es trägt.
- **Werkzeuge:** Vite 8.3.8, Vitest 5.1.0, ESLint 10.14.0, Prettier 3.9.10, axe-core 4.14.0 (Details in `package.json`).

## PWA-Grundlage

`passenger` und `operator` liefern ein Web-Manifest (`public/manifest.webmanifest`, noch ohne Icons); `passenger` registriert im Produktionsbuild einen Service Worker (`public/sw.js`), der ausschließlich App-Shell-Assets cacht (nie `/v1/*`, nie Anfragen mit Authorization); Manifest mit Symbolen (192/512). Regel: [web-passenger-pwa.md](../docs/umsetzungsplan/vertraege/web-passenger-pwa.md).

## Bekannte Hinweise

- `npm audit` meldet moderate Befunde in transitiven Entwicklungsabhängigkeiten (minimatch über ESLint/typescript-eslint); sie betreffen keine ausgelieferten Laufzeitpakete. Bewertung gehört zur Lieferketten-Stufe (WP-M0-14).
- Farbkontrast wird in den axe-Tests nicht geprüft, da jsdom kein Layout berechnet.
