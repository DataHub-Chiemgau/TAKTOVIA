# LH-6.5-OPT-002 – Fahrzeugkandidatenfilter

- **Verantwortlicher:** Rolle Buchung/Disposition (Modul `booking-dispatch`, Leseport aus WP-M2-02)
- **Arbeitspaket:** WP-M2-15
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Geeignete Fahrzeuge werden nach Dienstzeit, Standort, Kapazität und Ausstattung gefiltert.

**Originalabnahme (unverändert):** Fahrzeug ohne erforderlichen Rollstuhlplatz ist kein Kandidat.

## Vertrag

- **Tabellen / DDL:** keine neuen. Gelesen über den Leseport aus WP-M2-02: `vehicle` (`capacity_config`, `active_block`, `safety_revision`), `staff_shift` (`starts_at`, `ends_at`, `state`), `resource_release` (`valid_until`, `revoked_at`), `vehicle_run` (geplanter Standort aus dem Fahrtenplan), Fahrzeit aus WP-M2-10. Der Bezug Fahrzeug zu Dienst fehlt im Kern-DDL (OP-43): M2 nutzt eine Fixture-Poolzuordnung über den Leseport, Tabelle und Besitzer mit M3 (WP-M3-06) abzustimmen. Mandantenisolation per RLS (V1); jede Abfrage mandantengebunden.
- **API:** keine eigene; interner Port im Modul `booking-dispatch`.
- **Filterreihenfolge (Vorschlag) und Ablehnungsgrund:** (1) Fahrzeug gehört zum Pool des angefragten Dienstes und Mandanten, (2) Schicht deckt das Zeitfenster (sonst Personalmangel), (3) nicht gesperrt (`active_block`) und freigegeben (gültige `resource_release`, nicht widerrufen), (4) Ressourcenvektor aus `capacity_config` deckt Bedarf inkl. Rollstuhlplatz (sonst Ausstattung/Kapazität), (5) Abholpunkt im Zeitfenster erreichbar. Bleibt kein Kandidat: Primärgrund nach Filterstufe (Ausstattung, Personal, Kapazität), Sekundärgründe zusätzlich.
- **Standort in M2:** geplante Position aus `vehicle_run` laut Fahrtenplan (Livepositionen `position_sample` erst M3, OP-16).
- **Zustandsautomat / Schema / Ereignisse:** keine.
- **Parameter und Kennzahlen:** Datenbasis `Personalmangelablehnungen` (LH-K.1-Zeile-04), `Ausstattungsablehnungen` (LH-K.1-Zeile-05), `Kapazitätsablehnungen` (LH-K.1-Zeile-03).
- **Oberfläche:** keine eigene; Ablehnungsgrund erscheint in UI-01.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-15 (Liste der Ausstattungsmerkmale im Ressourcenvektor, Umrüstzustände), OP-16 (Standort), OP-43 (Fahrzeug-Dienst-Zuordnung), OP-14 (Ablehnungscodes). Abgrenzung: Nachweis in M2 mit Fixture-Pool/-Freigabe/-Schicht; echte Quellen erst mit WP-M3-06, WP-M3-08, WP-M3-34, WP-M4-17 über denselben Leseport. Nicht blockierend.

## Tests

- **Positiv:** geeignetes Fahrzeug (Dienstpool, Schicht, Freigabe, Ausstattung, Erreichbarkeit) wird als Kandidat gefunden.
- **Negativ:** je Filterkriterium ein Ausschlussfall: Fahrzeug ohne Rollstuhlplatz bei Rollstuhlanfrage; außerhalb Dienstzeit; ohne gültige oder widerrufene Freigabe; gesperrt; Abholpunkt im Fenster nicht erreichbar; Fahrzeug eines anderen Dienstpools; Fahrzeug eines fremden Mandanten (nie Kandidat). Kein Kandidat: Ablehnung mit zutreffendem Grund (Ausstattung, Personal, Kapazität).
- **Konkurrierend:** entfällt für den lesenden Filter; die verbindliche Prüfung unter Sperre erfolgt in WP-M2-22.
- **Referenztests:** AT-04 (Fahrzeug mit/ohne geeignete Ausstattung); Vorbereitung E2E-080 (alle Fahrzeuge ausgelastet, Normgrund Kapazität) und E2E-081 (freies Fahrzeug, niemand einsatzbereit, Primärgrund Personalmangel), Auswertung in M4.
- Testfälle: AT-04; E2E-080; E2E-081; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/VehicleCandidateFilterTest.kt::geeignetes_fahrzeug_wird_kandidat; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/VehicleCandidateFilterTest.kt::fahrzeug_ohne_rollstuhlplatz_kein_kandidat; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/VehicleCandidateFilterTest.kt::fahrzeug_ausserhalb_dienstzeit_kein_kandidat; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/VehicleCandidateFilterTest.kt::fahrzeug_ohne_gueltige_oder_widerrufene_freigabe_kein_kandidat; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/VehicleCandidateFilterTest.kt::gesperrtes_fahrzeug_kein_kandidat; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/VehicleCandidateFilterTest.kt::abholpunkt_nicht_erreichbar_kein_kandidat; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/VehicleCandidateFilterTest.kt::fremder_pool_kein_kandidat; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/VehicleCandidateFilterTest.kt::fremder_mandant_nie_kandidat; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/VehicleCandidateFilterTest.kt::kein_kandidat_alle_ausgelastet_primaergrund_kapazitaet; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/VehicleCandidateFilterTest.kt::kein_kandidat_niemand_einsatzbereit_primaergrund_personalmangel; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/VehicleCandidateFilterTest.kt::kein_kandidat_ausstattung_primaergrund_ausstattung

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
