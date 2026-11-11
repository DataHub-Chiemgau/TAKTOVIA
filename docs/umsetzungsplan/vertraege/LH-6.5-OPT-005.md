# LH-6.5-OPT-005 – Harte Grenzen für Wartezeit, Umweg und Verspätung

- **Verantwortlicher:** Rolle Disposition (Modul `booking-dispatch`, Constraint Validator)
- **Arbeitspaket:** WP-M2-18
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Konfigurierbare Höchstwerte für Wartezeit, Umweg und Verspätung werden als Nebenbedingungen geprüft.

**Originalabnahme (unverändert):** Bei jeder bestätigten Testfahrt sind sämtliche harten Grenzen erfüllt.

## Vertrag

- **Tabellen / DDL:** Keine eigene neue Tabelle. Die wirksamen Höchstwerte stammen aus der `config_revision` des Dienstes (`service.config_revision_id`, V1); das Angebot referenziert die zum Angebotszeitpunkt wirksame Revision, die bestätigte Buchung behält sie in `booking.promise_snapshot` (Konfigurationsrevision). Geprüft wird auf `route_stop`-Planzeiten (neue Tabelle aus WP-M2-16, nicht in V1–V3 vorhanden; Variantenerzeugung durch die Einfügeheuristik WP-M2-17). Änderungen des Höchstwerts wirken nur auf neue Angebote.
- **API:** Keine eigene Schnittstelle. Wirkung auf `POST /v1/ride-offers` (WP-M2-11/-21): eine Variante, die einen Höchstwert überschreitet, wird nicht in `offers` aufgenommen; Bleibt keine Variante übrig, enthält die Antwort eine leere `offers`-Liste mit normiertem Ablehnungsgrund (OPT-011; Zuordnung zu Zeitfenster/Frist ist Vorschlag, OP-14). Konfigurationswerte außerhalb der Katalogvalidierung lehnt das Konfigurationsregister (WP-M1-19; Veröffentlichung über `/v1/config/revisions/{revision_id}/publish`, api/config.yaml) ab.
- **Zustandsautomat:** keiner (Bestandteil der Angebotsberechnung; Angebotszustände siehe WP-M2-21).
- **JSON-Schema / Ereignisse:** Kein neues Schema; Konfigurationsschema der Dienstparameter aus WP-M1-19. Automatischer Prüfer (Property-Prüfung) liest alle bestätigten Buchungen des Testlaufs.
- **Parameter und Kennzahlen:** `max_wait_min` (Minuten, 0..240), `max_detour_pct` (Prozent, 0..200), `arrival_grace_min` (Minuten), `pickup_window_min` (Minuten, positiv; bei Änderung bereits bestätigte Buchungen prüfen). `arrival_grace_min` ist vertragsabhängig und wird nicht nachträglich rückwirkend geändert (kataloge.csv J.1). Sicherheits- und Vertragsvorgaben sowie harte Zusagegrenzen gehen vor (kataloge.csv, J.1). Grenzprüfung: Wartezeit <= `max_wait_min`; Umweg gegenüber Direktfahrt <= `max_detour_pct`; Ankunft innerhalb Zusage plus `arrival_grace_min`.
- **Oberfläche:** Keine Fahrgastoberfläche; Diagnoseausgabe der geprüften Grenzen je Variante für Tests.
- **Entscheidungen:** D-05 (freigegebene Produktionswerte; blockiert nur die Produktivschaltung, nicht den Bau).
- **Offene Punkte:** Messdefinitionen fehlen im Pflichtenheft (OP-17 des WP-Dokuments): Bezugspunkt der Wartezeit (ab Anfrage oder ab Wunschzeit), Basis des Umwegs (Direktzeit oder Direktdistanz), Definition Verspätung (welcher Fensterrand; kein eigener Parameter für maximale Verspätung, `arrival_grace_min` ist Ankunftstoleranz). Bis zum Beschluss parametrisiert der Test den Bezugspunkt, statt eine Fachregel zu erfinden. Blockiert den Bau nicht, die Abnahme der Messdefinition aber die Fachfreigabe.

## Tests

- **Positiv:** Variante liegt je Grenze knapp innerhalb (Wartezeit = `max_wait_min`, Umweg = `max_detour_pct`, Ankunft = Zusage + `arrival_grace_min`) und wird angeboten; nach Bestätigung zeigt der Prüfer über alle bestätigten Buchungen des Testlaufs keine Verletzung.
- **Negativ:** Variante knapp außerhalb je Grenze (+1 min bzw. +1 Prozentpunkt) wird nicht angeboten; Konfigurationswert `max_wait_min` = 241 oder `max_detour_pct` = 201 wird vom Konfigurationsregister abgelehnt; bestätigte Buchung behält ihre Revision nach Änderung des Höchstwerts; Änderung von `arrival_grace_min` wirkt nicht rückwirkend auf bestätigte Buchungen.
- **Konkurrierend:** Zwei parallele Bestätigungen, die einzeln innerhalb der Grenzen liegen, zusammen aber den Umweg der ersten Buchung über `max_detour_pct` heben: höchstens eine wird bestätigt; Prüfer bleibt grün. Parallel zu einer Änderung des Höchstwerts: laufendes Angebot behält seine referenzierte Revision.
- **Referenztests:** AT-02 (Pooling: zugesagte Zeitgrenzen gültig), Property-Test über alle bestätigten Testfahrten.
- Testfälle: AT-02; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/HardLimitsValidatorTest.kt::bestaetigte_fahrten_halten_alle_harten_grenzen; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/HardLimitsValidatorTest.kt::variante_ueber_grenze_wird_nicht_angeboten

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
