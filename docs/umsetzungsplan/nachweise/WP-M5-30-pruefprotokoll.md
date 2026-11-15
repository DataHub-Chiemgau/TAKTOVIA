# WP-M5-30 – Barrierefreiheitsprüfung der übrigen Kernreisen: Prüfplan und Prüfprotokoll

Stand 15.11.2026. Abnahmeziel WCAG 2.2 AA (Kap. 06 §6.1), als P1-Maßstab noch vom Auftraggeber zu bestätigen (OP-14, wie WP-M5-18). Mangelklassen A/B/C nach `tests/acceptance/docs/mangelklassen.md` (vorläufig, WP-M0-33). **Die manuelle Prüfung mit Screenreader ist nicht durchgeführt und nicht in `e2e.csv` eingetragen**: Hilfstechnik, Zielgeräte und ein Abnahme-Build in Staging stehen in dieser Umgebung nicht zur Verfügung. Das WP ist damit **teilweise** umgesetzt: die automatisiert belegbaren Teile sind gebaut und ausgeführt, die Screenreader-Durchläufe und die AT-19-Wiederholung auf dem Abnahme-Build sind offen.

## 1 Kernreisen und Pflichtaktionen (Ergänzung zum Prüfplan aus WP-M5-18)

| Rolle | Kernreise | Pflichtaktionen (ohne Karte, ohne Zeiger) | Oberfläche | Pflichtzustände (Kap. 06 §6.9) |
|---|---|---|---|---|
| Fahrgast | Suche, Angebot, Checkout, Reise und Storno, Konto | Orte wählen (Liste statt Karte), Angebot wählen, Zahlungsmittel und Bedingungen bestätigen, zahlungspflichtig buchen, Reise stornieren, Kontakt und Präferenzen pflegen | UI-01 bis UI-05 (Passenger-PWA) | Keine Abdeckung, abgelaufenes Angebot, Holdverlust, SCA, Preisänderung, Doppelklick, UNKNOWN, Teilstorno, Löschstatus |
| Leitstelle | Livebetrieb | Fahrzeugliste statt Karte, eigene Buchung oder Ressource bearbeiten, Störung und Vorfall bearbeiten | UI-07 (Operator-Web) | Sperre, veralteter Standort, keine Fremdrechte |
| Flotte/Werkstatt | Reparatur und Freigabe | Mangel einsehen, einstufen, Reparatur erfassen, Fahrzeug freigeben | UI-11 | Frist überschritten, Freigaberolle fehlt |
| Finance | Belege prüfen und freigeben | Beleg öffnen, Felder korrigieren, Dublette entscheiden, Aufteilung mit Rundungsrest, freigeben, Gegenbeleg | UI-14 | Dublette, XML-Konflikt, fehlende Kostenstelle, 412-Konflikt |
| Finance/Analyst | Kostenübersicht im Grundausbau | Rechtsträger und Periode wählen, auswerten | UI-15 | Datenlücke, Nenner 0, fehlende Partnerrechte |
| Admin | Gebiete, Stops, Tarife | Revision anlegen, freigeben | UI-17 | Ungültige Geometrie, konkurrierende Version |
| Admin/Support | Datenschutz, Audit, Support | Begründeten Fall bearbeiten (Zweck wählen, Abfrage, Export) | UI-20 | Fehlender Zweck, Legal Hold, abgelaufener Zugriff |

## 2 Prüfkatalog: was wo geprüft wird

| Nr. | Kriterium (AK) | Automatisiert (ausgeführt) | Manuell (offen) |
|---|---|---|---|
| K1 | Name, Rolle, Wert je Bedienelement; axe WCAG 2.2 AA (AK 1) | `web/operator/src/a11y/kernreisen.a11y.test.tsx` (8 Kernreise-Routen x 5 Zustände, vollständiger Anwendungsrahmen), `web/passenger/src/a11y/zustaende.a11y.test.tsx` (6 Routen x 4 Zustände), `web/passenger/src/a11y/kernreise.a11y.test.tsx` (Erfolgsreise, WP-M2-47), `npm run a11y:browser` (alle Routen, 320 und 1280 px, 100 und 200 % Text, inklusive Kontrast) | Screenreader je Kernreise |
| K2 | Tastatur: Pflichtaktion ohne Karte, Zeiger oder Drag-and-drop (AK 1) | `npm run kernreisen:browser` (echter Chrome: jedes sichtbare Bedienelement des Hauptbereichs auf 13 Routen allein mit Tab erreichbar, sichtbarer Fokus an jedem Stopp); jsdom: kein `role=application`, kein Element im Tab-Pfad innerhalb eines SVG; Passenger-Kernreise ohne Kartenaufruf (`kernreise.a11y.test.tsx::kernreise_suche_angebot_checkout_ohne_karte_nur_mit_nativen_bedienelementen_und_axe_wcag22aa`) | Durchlauf mit Tab, Pfeil, Enter, Leertaste |
| K3 | Karte hat gleichwertige Listenalternative (AK 1) | Operator: `vehicles/VehicleList.test.tsx::liste_ohne_karte_per_tastatur_und_screenreader_bedienbar`, Passenger: `booking/WalkingRoute.test.tsx::textanleitung_ohne_karte_vollstaendig_und_screenreader_reihenfolge` | Screenreader |
| K4 | Status nie nur über Farbe; asynchrone Meldungen über Live-Region (AK 1) | `StatusBadge` (Text und Symbol), `network/connection.test.tsx`, `ConnectionState.test.tsx` (WP-M5-09), `vehicles/veralteterStandort.test.tsx::zeigt_veraltet_kennzeichnung_nicht_nur_ueber_farbe` (Operator), `booking/veralteterStandort.test.tsx` (Passenger), `kernreise.a11y.test.tsx::detail_und_storno_per_tastatur_axe_wcag22aa_und_statusansage_ohne_fokussprung` | Ansage durch Screenreader |
| K5 | Fehler- und Rechtezustände angesagt und per Tastatur erreichbar (AK 4) | Zustandsmatrizen aus K1: Serverfehler, 403, Verbindung weg und fehlendes Recht (leere Scopes) zeigen `role=alert` oder `role=status` mit Text; `Checkout.test.tsx` (Holdverlust, Preisänderung, Abgelaufen), `Belegpruefung.test.tsx::freigeben_deaktiviert_mit_grund_bei_pflichtfeldern_kostenzuordnung_und_split`, `::ohne_freigaberecht_sieht_der_benutzer_keine_aktive_freigabe_und_das_original_hat_einen_eigenen_zustand` | Screenreader |
| K6 | Zeitlimit des Checkouts wird angekündigt (negativ, Tests) | `web/passenger/src/booking/Checkout.test.tsx::countdown_zeigt_restfrist_und_ablauf_waehrend_sca_wird_gemeldet`, `::hold_laeuft_waehrend_der_zahlung_ab_zeigt_keinen_erfolg` | Ansagezeitpunkt mit Screenreader |
| K7 | UI-Abnahmefall Finance: Dublette und Rundungsrest (AK 2) | `web/operator/src/belege/Belegpruefung.test.tsx::dublette_ist_erkennbar_und_per_tastatur_entscheidbar`, `::rundungsrest_und_summenkontrolle_sind_sichtbar_und_per_tastatur_bedienbar` (jeweils mit axe) | Screenreader |
| K8 | Mandantenwechsel mit zwei Tabs (AK 2, negativ) | `npm run kernreisen:browser` (echter Chrome, Wechsel nur per Tastatur: Tab 1 bleibt in Betrieb A, auch nach Neuladen; Tab 2 bleibt in Betrieb B; keine Anfrage von Tab 1 fordert Betrieb B an), `web/api-client/src/auth/auth.test.tsx::zwei Tabs: Tab 1 bleibt in A, Tab 2 wechselt nach B; keine Anfrage liefert Daten des anderen Mandanten, auch bei gleichzeitigen Schreibaktionen` (Server-Modell, parallele Schreibzugriffe) | Ansage des Wechsels |
| K9 | 412-Konflikt ohne stilles Überschreiben (AK 2) | `web/design-system/src/components/ConflictDialog.test.tsx`, `Belegpruefung.test.tsx::konfliktdialog_bei_412_ohne_stilles_ueberschreiben`, `Checkout.test.tsx::revisionswechsel_412_wird_nicht_still_bestaetigt` | Screenreader |
| K10 | 200 % Text, Reflow bei 320 CSS-Pixeln (Liefergegenstand) | `npm run a11y:browser` (Operator- und Passenger-Routen, beide Textgrößen und Breiten) | Browser-Zoom auf Gerät |
| K11 | AT-19 Buchungsweg erneut (AK 3) | Wiederholbar nur auf dem Abnahme-Build in Staging: nicht ausgeführt; Regression im Komponentenumfeld: `kernreise.a11y.test.tsx` | **offen** (Staging, D-12) |

## 3 CI-Schritt

Job `web` in `.github/workflows/ci.yml`: der Schritt aus WP-M5-18 (`a11y:browser`, `a11y:selftest`) bleibt Maßstab; neu `npm run kernreisen:browser` (Tastaturdurchlauf, Mandantenwechsel). Die Zustandsmatrizen laufen in `npm test` des Jobs. Ausführung in dieser Umgebung (Chrome, 15.11.2026): siehe Bericht des Arbeitspakets; `kernreisen:browser` 17 Prüfungen, 0 Verstöße.

Einschränkungen der Browserprüfung: die API ist nur für `/v1/me` und den Mandantenwechsel simuliert, alle anderen Aufrufe liefern leere Seiten; es werden daher Leerzustände und Bedienelemente der Bildschirme geprüft, keine gefüllten Listen (diese belegen die Komponententests). Die Fahrgastsuche (`/`) zeigt in der Simulation den Zustand „kein Dienst“, ihr Formular wird nur in jsdom geprüft. Die Kalenderschaltfläche von Datums- und Zeitfeldern trägt den nativen Fokusrahmen des Browsers.

## 4 Befunde

| Nr. | Befund | Klasse | Stand |
|---|---|---|---|
| C-1 | Keine blockierte Kernreise durch die automatisierten Prüfungen gefunden; die Zustandsmatrizen und der Tastaturdurchlauf liefen ohne Nachbesserung am Produktcode grün (zwei Fehlalarme der neuen Prüfung wurden in der Prüfung selbst korrigiert: Reiter mit roving tabindex, innere Stopps der Datumsfelder). | – | dokumentiert |
| C-2 | Die Kostenübersicht (UI-15) sagt das fehlende Recht über `role=status` (höflich) an statt `role=alert`; zulässig, die Ansage durch Screenreader ist manuell zu bestätigen. | C | Termin mit der manuellen Prüfung |
| C-3 | Screenreader-Durchläufe aller Kernreisen (NVDA/Firefox, VoiceOver/Safari, TalkBack für die Passenger-PWA), AT-19-Wiederholung auf dem Abnahme-Build | A für die Abnahme, solange offen | offen, Prüfer und Staging beizustellen |
| C-4 | Gefüllte Ansichten im echten Browser (Server oder Stub je Schnittstelle nötig) | C | offen |

## 5 Prüfprotokoll (manuell, offen)

| Kernreise | Kombination | Tester | Datum | Build | Ergebnis | Mängel (Klasse) | Evidenz |
|---|---|---|---|---|---|---|---|
| Fahrgast Suche bis Storno (UI-01 bis UI-05) | NVDA + Firefox, VoiceOver + Safari iOS, TalkBack + Chrome | – | – | – | offen | – | – |
| Fahrgast Checkout mit Holdablauf und Preisänderung | VoiceOver iOS, TalkBack | – | – | – | offen | – | – |
| Leitstelle Livebetrieb mit veraltetem Standort (UI-07) | NVDA + Firefox | – | – | – | offen | – | – |
| Flotte Reparatur und Freigabe (UI-11) | NVDA + Firefox | – | – | – | offen | – | – |
| Finance Beleg mit Dublette und Rundungsrest (UI-14) | NVDA + Firefox, VoiceOver + Safari | – | – | – | offen | – | – |
| Kostenübersicht (UI-15), Tarife (UI-17), Audit/Support (UI-20) | NVDA + Firefox | – | – | – | offen | – | – |
| Mandantenwechsel mit zwei Tabs | NVDA + Firefox | – | – | – | offen | – | – |
| AT-19 Buchungsweg Regression auf Abnahme-Build | Screenreader und Tastatur | – | – | – | offen | – | – |

Die Evidenz darf keine Fahrgast- oder Personaldaten enthalten (Testkonto mit Fixture-Daten). Entscheidung (bestanden, bestanden mit Auflage, nicht bestanden) trifft der Auftraggeber.
