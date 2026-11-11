# LH-6.4-BKG-004 – Ressourcenvektor und Mehrpersonenbuchung

- **Verantwortlicher:** Rolle Buchung/Disposition (Modul `booking-dispatch`, Ressourcenvektor)
- **Arbeitspaket:** WP-M2-14
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Mehrere Personen und getrennte Kapazitätsarten (Sitz/Rollstuhl) können je Buchung erfasst werden.

**Originalabnahme (unverändert):** Buchung mit belegtem Rollstuhlplatz wird nicht fälschlich bestätigt.

## Vertrag

- **Tabellen / DDL:** `vehicle.capacity_config` (nur lesen über Leseport `ResourceAvailability`, WP-M2-02), `capacity_hold.capacity_vector jsonb` (V1; Eignung laut Spike WP-M0-26), neue Tabelle `passenger_trip` (n:1 zu `booking`, mit `(tenant_id,id)`, RLS, Revision, Audit; Personen/Plätze einer Buchung ohne Diagnosen). Ressourcenvektor getrennte Dimensionen Sitz, Rollstuhlplatz, Ausstattung; Wertobjekt mit komponentenweiser Addition und Vergleich, kein Ersatz einer Dimension durch eine andere (ein Rollstuhlplatz wird nie durch freie Sitze gedeckt). Nach Bestätigung unveränderlich Teil von `booking.promise_snapshot`.
- **API:** Felder Personenzahl und Unterstützungsbedarf (nur transportrelevante Merkmale, keine Diagnose, Kap. 06 §6.2) in `POST /v1/ride-offers` und in `/v1/bookings/{id}`; Ablehnung bei fehlender Kapazität/Ausstattung mit normiertem Grund (Kategorie Ausstattung) bzw. `409 CAPACITY_UNAVAILABLE` im Hold; unbekannte Dimension → 422.
- **Zustandsautomat:** keiner eigener; Wirkung im Hold-Zustandsautomaten (WP-M2-22).
- **JSON-Schema / Ereignisse:** JSON-Schema Ressourcenvektor mit gespeicherter Schema-Version (`vertraege/schemas/`, WP-M2-01, hier final); Ereignisse nur über Hold/Buchung.
- **Parameter und Kennzahlen:** `min_capacity_reserve` (Plätze, Dienst; nicht größer als tatsächlich verfügbare Kapazität; je Dimension), `wheelchair_boarding_sec` (Sekunden, Fahrzeugtyp; hier nur gelesen, die Wirkung auf die Standzeit prüft die Einfügeheuristik WP-M2-17); Kennzahl Datenbasis `Ausstattungsablehnungen`.
- **Oberfläche:** Eingabe Personenzahl/Unterstützungsbedarf in UI-01 (WP-M2-37), Anzeige im Buchungsdetail UI-04; Zustand „Rollstuhlplatz nicht verfügbar“.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-15 (Höchstwerte Personen, Rollstuhlplätze, Liste der Ausstattungsmerkmale, „Umrüstzustände“ nicht beschrieben; Arbeitsannahme: Höchstwerte als Dienstparameter im Detailvertrag, Umrüstzustände in P1 nicht modelliert); OP-41 (Rollstuhlanfrage und Haltepunkt „ungeprüft“); OP-39 (Lastprofil). Kein Punkt blockiert den Bau.

## Tests

- **Positiv:** Mehrpersonenbuchung mit zwei Sitzplätzen und einem Rollstuhlplatz speichert beide Kapazitätsarten getrennt (Vektor und `passenger_trip`); Vektor ist nach Bestätigung Teil von `promise_snapshot`; AT-04 (Fahrzeug mit geeigneter Ausstattung wird bestätigt).
- **Negativ:** Rollstuhlplatz auf dem Abschnitt belegt → weitere Rollstuhlbuchung nicht bestätigt, auch wenn Sitze frei sind (Variante aus PH-AT-004); Fahrzeug ohne Rollstuhlplatz/Ausstattung (AT-04); Vektor mit unbekannter Dimension → abgewiesen; Personenzahl 0/negativ und über dem Höchstwert (Wert offen, OP-15; Testwert aus Dienstparameter-Fixture); Unterstützungsbedarf mit Diagnosefeld abgewiesen; Abweisung wegen fehlender Ausstattung trägt den normierten Grund der Kategorie Ausstattung und zählt in `Ausstattungsablehnungen`; `min_capacity_reserve` je Dimension eingehalten.
- **Konkurrierend:** zwei parallele Rollstuhlbuchungen auf einen Platz → höchstens eine bestätigt (Ausführung Hold/Confirm in WP-M2-22/WP-M2-23); parallele Sitz- und Rollstuhlbuchung auf demselben Abschnitt belegen beide Dimensionen nie über Kapazität; keine negative Belegung unter 100 parallelen Transaktionen.
- **Referenztests:** AT-04 (nur Kapazitätsteil; der Teil „Stopp zulässig“ liegt bei WP-M2-05/WP-M2-12, OP-41), Variante aus PH-AT-004 („Ein freier Sitz ersetzt keine fehlende Rollstuhlkapazität“).
- Testfälle: AT-04; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/booking/ResourceVectorTest.kt::sitz_und_rollstuhl_getrennt_gespeichert; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/booking/ResourceVectorTest.kt::belegter_rollstuhlplatz_nicht_durch_sitze_ersetzt; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/booking/ResourceVectorTest.kt::unbekannte_dimension_abgewiesen; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/booking/WheelchairConcurrencyTest.kt::zwei_parallele_rollstuhlbuchungen_hoechstens_eine; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/booking/ResourceVectorTest.kt::unterstuetzungsbedarf_mit_diagnose_abgewiesen; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/booking/ResourceVectorTest.kt::min_capacity_reserve_je_dimension; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/booking/ResourceVectorTest.kt::vektor_im_promise_snapshot_unveraenderlich; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/booking/WheelchairConcurrencyTest.kt::sitz_und_rollstuhl_parallel_keine_negative_belegung

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
