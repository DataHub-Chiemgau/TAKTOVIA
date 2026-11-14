# WP-M5-18 – Barrierefreiheitsprüfung Fahrer und Planer (AT-54): Prüfplan und Prüfprotokoll

Stand 14.11.2026. Abnahmeziel WCAG 2.2 AA (Kap. 06 §6.1), als P1-Maßstab noch vom Auftraggeber zu bestätigen (OP-14). Mangelklassen A/B/C nach `tests/acceptance/docs/mangelklassen.md` (vorläufig, WP-M0-33). **AT-54 ist nicht bestanden und nicht in `e2e.csv` eingetragen**: die Pflichtschritte mit Screenreader und auf Zielgeräten sind in dieser Umgebung nicht ausführbar (keine Hilfstechnik, keine Geräte, kein Flutter).

## 1 Kernreisen und Pflichtaktionen

| Rolle | Kernreise | Pflichtaktionen | Oberfläche |
|---|---|---|---|
| Fahrer | Dienststart bis Fahrtende | Prüfpunkt beantworten, Mangel mit Foto melden, Prüfung abschließen (auch offline, Sperrwirkung und Synchronisationsergebnis angesagt), Check-in/out, Mangel melden | UI-09, UI-10 (Driver-App, Flutter) |
| Planer | Entwurf bis Veröffentlichung | Schicht anlegen, zuweisen, verschieben, Konflikt einsehen, Plan veröffentlichen, eigene Zeit/Abwesenheit | UI-12, UI-13 (Operator-Web) |

## 2 Prüfkatalog: was wo geprüft wird

| Nr. | Kriterium | Automatisiert (ausgeführt) | Manuell (AT-54, offen) |
|---|---|---|---|
| F1 | Zugänglicher Name, Rolle, Wert für jedes Bedienelement | `npm run a11y:browser` (axe 4.14, WCAG 2.2 AA, im echten Chrome, alle in `a11y-browser.mjs` konfigurierten Operator- und Passenger-Routen, 320 px und 1280 px, 100 % und 200 % Text); Selbsttest `npm run a11y:selftest`; Flutter `test/a11y_guidelines_test.dart` (nicht ausgeführt) | Screenreader je Kernreise |
| F2 | Tastatur: jede Pflichtaktion ohne Zeiger oder Drag-and-drop | `web/operator/src/dienstplan/SchichtDragDrop.test.tsx::tastaturpfad_entspricht_drag_and_drop`, `DienstplanRaster.test.tsx::tabellenalternative_per_tastatur_nutzbar`, `npm run dienstplan:browser` (22 Prüfungen im Browser, Tastaturpfad, Klick, Kopie) | Durchlauf mit Tab, Pfeil, Enter, Leertaste |
| F3 | Fokus: sichtbar, nicht verloren nach Dialogen, Rollen fokussierbar, kein positiver `tabindex` | gemeinsame Regeln `web/tools/a11y-regeln.mjs` (`fokusVerstoesse`), Dialogtests `ConflictDialog.test.tsx` | Durchlauf |
| F4 | Status nie nur über Farbe, asynchrone Meldungen über Live-Region | `StatusBadge` (Symbol), `LiveRegionProvider`, `network/connection.test.tsx`, Passenger- und Operator-Tests `ConnectionState.test.tsx` (WP-M5-09); Dart `test/verbindungszustand_test.dart` (nicht ausgeführt) | Ansage von Sperrwirkung, Offlinezustand, Synchronisationsergebnis (UI-Abnahmefall Kap. 06 §6.9) |
| F5 | Fehler- und Rechtezustände per Tastatur erreichbar und angesagt (Validierung, 412-Konflikt, fehlende Freigaberolle, Sperre) | Operator-Tests je Bildschirm (Konfliktdialog, `fehlendes_recht_verhindert_aktion`), axe auf den Zustandsansichten | Screenreader |
| F6 | 200 % Text, Reflow bei 320 CSS-Pixeln | `a11y:browser`, `dienstplan:browser` | Browser-Zoom, Nur-Text-Zoom, Gerät |
| F7 | Kontrast | axe `color-contrast` im echten Browser (Selbsttest zeigt Fehlschlag bei Kontrast #aaa auf Weiß) | Hochkontrastmodus |
| F8 | Driver-App mit TalkBack und VoiceOver | Flutter-Leitlinien (Tippziel 48 dp / 44 pt, Beschriftung, Kontrast) in `test/a11y_guidelines_test.dart`, nicht ausgeführt | **vollständig offen** (Zielgeräte, OP-27) |

## 3 CI-Schritt (AK 4)

Job `web` in `.github/workflows/ci.yml`: `a11y:browser` (alle P1-Weboberflächen, jetzt mit simulierter OIDC-Anmeldung, weil die Oberflächen seit WP-M1-44 sonst nur den Anmeldebildschirm zeigten), `a11y:selftest` (Probekomponenten ohne Namen, ohne Fokus, mit zu geringem Kontrast schlagen mit denselben Regeln fehl, die saubere Probe besteht) und `dienstplan:browser`. Job `mobile`: `flutter test` einschließlich `a11y_guidelines_test.dart` (der Job bleibt `continue-on-error`, bis Flutter einmal gelaufen ist).

Ausführung in dieser Umgebung (Chrome, 14.11.2026): `a11y:browser` 116 Ansichten, 0 Verstöße; `a11y:selftest` bestanden (4 von 4 Erwartungen); `dienstplan:browser` 22 Prüfungen, 0 Verstöße. Einschränkung: die Operator-Routen laufen ohne Server, nur `/v1/me` ist simuliert, die meisten Bildschirme zeigen daher Leer- oder Fehlerzustände; gefüllte Ansichten belegen nur die Jest-ähnlichen Komponententests (jsdom, axe ohne Kontrast) und `dienstplan:browser` für UI-12.

## 4 Befunde und Lücken

| Nr. | Befund | Klasse | Stand |
|---|---|---|---|
| B-1 | Die Browserprüfung `a11y:browser` meldete vor diesem Paket vier Fehler für `/admin/datenschutz`, weil die Oberflächen nach der Einführung der Anmeldung (WP-M1-44) nur noch die Anmeldeseite lieferten; die Prüfung lief faktisch gegen den Anmeldebildschirm. Behoben durch die OIDC-Simulation. | B (Prüfwirksamkeit) | behoben |
| B-2 | Der vollständige Listen- und Screenreaderweg für alle Schichtaktionen ist P2 (WP-M7d-06, OP-14); in M4 sind Klick, Kopie, Tastaturpfad und Tabellenalternative vorhanden und getestet. | C | Termin mit M7d |
| B-3 | UI-09/UI-10 der Driver-App (Flutter): nichts ausgeführt, keine Screenreader-Prüfung | A für die Abnahme AT-54, solange offen | offen, Zielgeräte und Prüfer beizustellen (D-12) |
| B-4 | Gefüllte Operator-Bildschirme im Browser (Server oder Stub nötig) | C | offen |

## 5 Prüfprotokoll AT-54 (manuell, offen)

| Kernreise | Kombination | Tester | Datum | Build | Ergebnis | Mängel (Klasse) | Evidenz |
|---|---|---|---|---|---|---|---|
| Fahrer Dienststart bis Fahrtende | TalkBack + Android-Zielgerät | – | – | – | offen | – | – |
| Fahrer Dienststart bis Fahrtende | VoiceOver + iOS-Zielgerät | – | – | – | offen | – | – |
| Fahrer Prüfpfad kritisches Nein und Offline-Sync | TalkBack, VoiceOver | – | – | – | offen | – | – |
| Planer Entwurf bis Veröffentlichung | NVDA + Firefox, Windows | – | – | – | offen | – | – |
| Planer Entwurf bis Veröffentlichung | VoiceOver + Safari | – | – | – | offen | – | – |
| Planer nur Tastatur | Chrome/Firefox | – | – | – | offen | – | – |

Die Evidenz darf keine Fahrgast- oder Personaldaten enthalten (Testkonto mit Fixture-Daten). Entscheidung (bestanden, bestanden mit Auflage, nicht bestanden) trifft der Auftraggeber.
