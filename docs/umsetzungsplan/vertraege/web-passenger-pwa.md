# Detailvertrag Passenger-PWA: Grundgerüst (WP-M2-35, Enabler)

Legt fest, was das WP als „Detailverträge vorab“ nennt: Routen- und Zustandskatalog UI-01 bis UI-05, Textkatalog, Cache-Regel des Service Workers. Baustein: `web/passenger`; Anmeldung und Tokenhaltung gelten unverändert nach [web-anmeldung.md](web-anmeldung.md).

## Routen- und Zustandskatalog

Quelle im Code: `web/passenger/src/routen.ts`. Navigation (Kap. 06 §6.2): „Meine Reisen | Hilfe“; Start ist die Marke/„Neue Fahrt“, Konto ein Link im Kopfbereich.

| Route | Bildschirm | öffentlich | Recht | Fachinhalt durch |
|---|---|---|---|---|
| `/` | UI-01 Fahrt suchen | nein | – | WP-M2-37 |
| `/angebote` | UI-02 Angebote | nein | `passenger.app` | WP-M2-41 |
| `/checkout/:offerId` | UI-03 Checkout | nein | `passenger.app` | WP-M2-42 |
| `/reisen`, `/reisen/:bookingId` | UI-04 Meine Reisen / Buchungsdetail | nein | `passenger.app` | WP-M2-43 bis 46 |
| `/konto` | UI-05 Konto | nein | `passenger.app` | WP-M2-36 |
| `/hilfe` | Hilfe | **ja** | – | dieses WP (statischer Text) |

Zustände je Bildschirm (alle über `StateView`/`ResourceView` des Designsystems, Texte nie aus dem Freitext des Servers):

| Auslöser | Zustand | Verhalten |
|---|---|---|
| Laden | `loading` | `aria-busy`, kein Inhalt |
| leere Liste | `empty` | Titel und nächste Handlung aus dem Textkatalog |
| 401 / Token abgelaufen | Anmeldezustand `expired` bzw. `revoked` | keine Daten, Anmelden |
| 403 | `forbidden` | keine Einzelheiten; auf `/v1/me` = „keine Mitgliedschaft“ |
| 404 | `notFound` | auch bei fremder ID (kein Unterschied zu „gibt es nicht“) |
| 409 | `conflict` | Lesestand neu laden; fachliche Codes (`OFFER_EXPIRED`, `HOLD_EXPIRED`, `REQUOTE_REQUIRED`, `CAPACITY_UNAVAILABLE`, `OPERATION_IN_PROGRESS`) behandeln die Fach-WPs über `problem.code` |
| 412 | `conflict` | Konfliktdialog, Eingaben bleiben |
| 428 | `preconditionRequired` | aktuellen Stand laden |
| 429 | `rateLimited` | `Retry-After` anzeigen |
| 502/503/504 | `unavailable` | Eingaben bleiben, erneut versuchen |
| keine Verbindung (`ApiError.status 0` oder `navigator.onLine=false`) | `offline` | Hinweis „Offline“ als `role=status` im Rahmen; **keine** Buchungs- oder Änderungsaktion; vor der Anmeldung statt der Weiterleitung zum Anmeldedienst |

Asynchrone Statusmeldungen laufen über die Live-Region des Designsystems (`LiveRegionProvider`, `useAnnounce`). Jeder Bildschirm zeigt Mandant, Dienst, Status (Text, nie nur Farbe) und Datenstand (`ScreenFrame`).

## Textkatalog

`web/passenger/src/texte.ts`: Schlüssel `<bereich>.<name>`, Deutsch als erste Sprache; weitere Sprachen liefern nur abweichende Werte (Umschaltung mit WP-M2-36). Zustandstexte der Fehlerklassen liegen im Katalog des Designsystems (`state.*`). Test: alle Schlüssel nicht leer und schemakonform (`pwa.test.tsx`).

## Idempotenz

Befehle laufen über `useCommands().run(intent, operationId, args)` (Baustein `runCommand`/`CommandKeys` in `@taktovia/api-client`). Der `Idempotency-Key` gehört zur **Benutzerabsicht** (`intent`, z. B. `confirmBooking:<Angebots-ID>`) und wird im Arbeitsspeicher gehalten: Bei unbekanntem Ausgang (Netzabbruch, 408, 5xx, 409 `OPERATION_IN_PROGRESS`) geht die Wiederholung mit demselben Schlüssel; nach Erfolg oder endgültiger Ablehnung (übrige 4xx) wird der Schlüssel verbraucht. Der Schlüssel wird nie persistiert.

## Service Worker: Cache-Regel

Datei `web/passenger/public/sw.js`. **Gecacht** werden nur statische App-Shell-Assets: `/`, `/manifest.webmanifest`, `/icons/*`, `/assets/*` (gehashte Build-Dateien, enthalten keine personenbezogenen Daten). Seitenaufrufe: Netz zuerst, ohne Verbindung die gecachte Shell `/` (die Anwendung zeigt dann den Offlinezustand). **Nie gecacht und nie abgefangen:** `/v1/*` (API), `/realms/*` und jede fremde Herkunft (Anmeldedienst), Anfragen mit `Authorization`-Kopf, alle Nicht-GET-Anfragen. Es gibt keine Offline-Buchung und keine Warteschlange für Befehle (Kap. 01 §1.5).

## Nachweis und Grenzen

Ausgeführt: Vitest (Routen, Zustände, Anmeldung gegen lokalen OIDC-Ersatz, Token nie im Web-Speicher, Service-Worker-Skript in Ersatzumgebung, Idempotenz), axe im echten Chrome bei 320 px und 200 % Text (`npm run a11y:browser`), PWA-Prüfung im echten Chrome gegen den Produktionsbuild (`npm run e2e:pwa`: Manifest, Installierbarkeit, Service Worker, Cache ohne `/v1/*`, Offlinezustand nach Neuladen). **Nicht ausgeführt:** Anmeldung gegen echten Keycloak im Browser, Installation auf Mobilgeräten, Test unter Node 24. Hinweis zu `e2e:pwa`: Chromium meldet bei emulierter Offline-Verbindung nach einer Navigation `navigator.onLine` weiter als `true`; die Prüfung setzt den Wert für die geladene Seite nach (Netzsperre und Cache sind echt).
