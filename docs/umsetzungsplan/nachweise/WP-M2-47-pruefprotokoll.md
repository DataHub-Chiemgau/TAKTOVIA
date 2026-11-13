# WP-M2-47 – Barrierefreier Buchungsweg: Prüfkatalog und Prüfprotokoll AT-19

Stand 13.11.2026. Kernreise: Suche (UI-01) → Angebot (UI-02) → Checkout (UI-03) → Detail (UI-04) → Storno (UI-04), Konto (UI-05) als Rahmen. Abnahmeziel WCAG 2.2 AA (Kap. 06 §6.1). Mängelklassen A/B/C nach `tests/acceptance/docs/mangelklassen.md` (Lastenheft §11.3 lag nur als DOCX vor, vorläufige Regel aus WP-M0-33).

## 1 Prüfkatalog und was wo geprüft wird

| Nr. | Kriterium | Automatisiert (ausgeführt) | Manuell (AT-19) |
|---|---|---|---|
| K1 | Nur Tastatur: jeder Schritt erreichbar, sichtbarer Fokus, keine Fokusfalle | `kernreise.a11y.test.tsx` (nur native Bedienelemente, kein positiver tabindex, Namen) und `npm run a11y:browser` (Fokusrahmen im Browser) | Durchlauf mit Tab/Shift+Tab/Enter/Leertaste |
| K2 | Screenreader: Struktur, Namen, Zustände, Ansage von Statusänderungen | axe-core WCAG 2.2 AA je Schritt; Live-Region `role=status`, höflich, atomar, Fokus springt nicht | NVDA + Firefox und VoiceOver + Safari (Auswahl: Kombinationen mit hoher Verbreitung, Auswahl durch den Auftraggeber zu bestätigen, OP-Barrierefreiheit) |
| K3 | Keine Kartenpflicht | Test: kein Tab-Stopp im SVG, kein `role=application`, die Reise läuft ohne Kartenaufruf; Karte ist beschriftetes Bild, Marker ausgeblendet (`StopMap`) | Reise mit deaktivierter Karte (Kachelquelle blockiert) |
| K4 | 200 % Textvergrößerung | Quelltextregel: keine px-Schriftgrößen; `a11y:browser` setzt 200 % im Browser und misst Überbreite | Browser-Zoom und Nur-Text-Zoom |
| K5 | Reflow bei 320 CSS-Pixeln | `a11y:browser` (Viewport 320x568, `scrollWidth`) | Sichtprüfung |
| K6 | Kontrast | `a11y:browser` (axe `color-contrast` im echten Browser) | Hochkontrastmodus |
| K7 | Reduzierte Bewegung | Quelltextregel und `a11y:browser` (Übergangsdauer ~0) | Betriebssystemeinstellung |
| K8 | Touchflächen 44x44 | `a11y:browser` misst Schaltflächen/Links/Eingaben | Gerät |
| K9 | Statusänderungen ohne Fokusverschiebung | Test (Buchen, Storno) | Screenreader |

## 2 Prüfkombinationen (vereinbart, noch nicht durchgeführt)

1. NVDA (aktuelle Version) + Firefox, Windows.
2. VoiceOver + Safari, iOS (Passenger-PWA im Browser).
3. Ergänzend TalkBack + Chrome, Android (empfohlen, nicht gefordert).

## 3 Ausführung in dieser Umgebung

| Prüfung | Ergebnis |
|---|---|
| `web/passenger/src/a11y/kernreise.a11y.test.tsx` (8 Tests, jsdom, axe WCAG 2.2 AA ohne Kontrast) | ausgeführt, grün |
| `npm run a11y:browser` (Chrome, 1280 und 320 Breite, Text 100 und 200 %) | ausgeführt; Passenger-Rahmen ohne Verstoß; 4 Verstöße im Operator (`/admin/datenschutz`, Dateninventar nicht gerendert) sind nicht Teil dieses Pakets und werden gemeldet |
| Browserlauf der **gefüllten** Kernreise-Bildschirme (Angebot, Checkout, Detail mit Daten) | **nicht ausgeführt** (benötigt einen Server oder Stub für die Buchungs-API im Browser) |
| Screenreader-Durchläufe (NVDA, VoiceOver) | **nicht ausgeführt** (keine Hilfstechnik, keine Geräte) |

## 4 Prüfprotokoll AT-19 (manuell, offen)

| Kombination | Tester | Datum | Build | Ergebnis | Mängel (Klasse) | Evidenz |
|---|---|---|---|---|---|---|
| NVDA + Firefox | – | – | – | offen | – | – |
| VoiceOver + Safari | – | – | – | offen | – | – |
| Nur Tastatur | – | – | – | offen | – | – |
| 200 % Text / 320 px | – | – | – | offen | – | – |

Offene Mängel Klasse A/B: keine bekannt (nicht manuell geprüft). Die Evidenz darf keine Fahrgastdaten enthalten (Testkonto mit Fixture-Daten).
