# Designsystem und Anwendungsrahmen (WP-M1-43)

Gemeinsame Bausteine für `operator` und `passenger` nach Kap. 06 §6.1. Fach-WPs bauen ihre Oberflächen aus diesen Komponenten; Texte stehen im Katalog `src/i18n.tsx` (Deutsch als erste Sprache, weitere Sprachen über `I18nProvider`).

## Komponentenliste

| Komponente                                              | Zweck                                                                                                                                                       |
| ------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `AppFrame`                                              | Rahmen: Kopf, Navigation links (schmal: ausklappbar per Menüschalter), genau ein `main`, Sprunglink                                                         |
| `ScreenFrame`, `ContextBar`                             | Bildschirm: Überschrift, **Pflicht-Kontext** (Mandant, Dienst, fachlicher Status, Datenstand mit Zone), optionales Detailpanel                              |
| `visibleNav`, `NavEntry`                                | Navigation nach Recht; Anzeige ist keine Rechtequelle                                                                                                       |
| `Form`, `Field`                                         | Formulare; Feldfehler aus `violations[].pointer` und `code`, freier Servertext wird nie angezeigt, Verletzungen ohne Feld erscheinen in der Zusammenfassung |
| `DataTable`                                             | Tabelle mit Cursor-Paging (opaker `next_cursor`), Leerzustand, Scrollbehälter statt Seitenüberbreite                                                        |
| `StatusBadge`                                           | Status mit Text und Symbol, nie nur Farbe                                                                                                                   |
| `LiveRegionProvider`, `useAnnounce`                     | zurückhaltende Live-Region (`role=status`, höflich) für asynchrone Meldungen                                                                                |
| `BusyButton`                                            | Mehrfachabsendeschutz: gesperrt (`aria-disabled`, fokussierbar) während die Operation läuft                                                                 |
| `ConflictDialog`                                        | 412: nur „Aktuellen Stand laden“, „Meine Eingaben prüfen“, „Abbrechen“; bewusst kein „Überschreiben“                                                        |
| `StateView`, `EmptyState`, `ErrorState`, `ResourceView` | Zustände laden/leer/Fehler/Rechte; `ResourceView` ist der wiederverwendbare Rahmen für UI-17, UI-18, UI-20 und alle folgenden Bildschirme                   |
| `DateTimeField`, `ZonedTime`                            | Datum/Zeit mit Zone (WP-M1-18)                                                                                                                              |

## Design-Tokens (`src/styles.css`)

CSS-Variablen `--tk-*`: Farben (Text, Hintergrund, Primär, Fokus, Status ok/warning/error/info), Abstände (`--tk-space-1..4`), `--tk-touch-min: 44px`, `--tk-focus-width: 3px`, `--tk-nav-width`. Schriftgrößen sind relativ (`rem`), damit 200 % Textvergrößerung greift. Die App importiert `@taktovia/design-system/styles.css` (Content-Security-Policy `style-src 'self'`).

## Fehler- und Leerzustand-Konvention

| Status      | Zustand                | Anzeige                                                    |
| ----------- | ---------------------- | ---------------------------------------------------------- |
| 401         | `unauthenticated`      | „Anmeldung erforderlich“                                   |
| 403         | `forbidden`            | „Keine Berechtigung“, keine Einzelheiten, keine Daten      |
| 404         | `notFound`             | „Nicht gefunden“                                           |
| 412         | `conflict`             | `ConflictDialog` oder Zustand „Die Daten wurden geändert“  |
| 428         | `preconditionRequired` | „Aktueller Stand erforderlich“                             |
| 429         | `rateLimited`          | Wartezeit aus `Retry-After`                                |
| 502/503/504 | `unavailable`          | „Dienst vorübergehend nicht erreichbar“                    |
| 0           | `offline`              | „Keine Verbindung“                                         |
| sonst       | `error`                | allgemeiner Fehler mit Support-Referenz (`correlation_id`) |

Texte stammen aus dem Katalog, nie aus `title`/`detail`/`message` des Servers. Ein Leerzustand nennt den fachlichen Grund und die nächste Handlung. Steht ein Zustand für die ganze Seite, trägt er `level={1}` (genau eine `h1`).

## Navigationsstruktur je Rolle (vorläufig)

`web/operator/src/navigation/navigation.ts`: Start (immer), Callcenter (`callcenter`), Finanzen (`finance`), Personal (`staff`), UI-17 Gebiete/Stops/Tarife (`admin.ui17`), UI-18 Kooperation und Rollen (`admin.ui18`), UI-20 Datenschutz/Audit/Support (`admin.ui20`). `passenger`: Start, Meine Reisen, Konto (`passenger.app`). Die Rechte werden in `@taktovia/api-client` (`rights.ts`) aus den Rollen von `/v1/me` abgeleitet; die Rollencodes und die Zuordnung sind **vorläufig** (OP-10, WP-M1-07) und werden durch die freigegebene Rechtematrix ersetzt.

## Browser- und Gerätematrix

Im Pflichtenheft nicht festgelegt (OP-20). Geprüft wird mit aktuellem Chrome (Desktop 1280x800, Mobil 320x568). Firefox, Safari und konkrete Geräte stehen aus.

## Nachweis der Barrierefreiheit

- Komponenten- und Rahmentests: Vitest mit axe-core in jsdom (ohne Kontrast, da jsdom kein Layout berechnet).
- `npm run a11y:browser` (`web/tools/a11y-browser.mjs`): echter Chrome, axe-core mit Farbkontrast und WCAG-2.2-AA-Regeln, Reflow bei 320 CSS-Pixeln, 200 % Textgröße, Touchflächen 44x44, sichtbarer Fokus, reduzierte Bewegung; beide Rahmenanwendungen, jeweils Desktop und Mobil. Tastatur- und Screenreader-Prüfung der Kernreisen folgt im UX-Gate (WP-M5-30).
- Visuelle Regression (Screenshot-Vergleich) ist noch nicht eingerichtet.
