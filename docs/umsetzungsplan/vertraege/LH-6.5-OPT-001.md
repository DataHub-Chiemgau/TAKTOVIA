# LH-6.5-OPT-001 – Zulässigkeitsprüfung Gebiet und Bedienzeit

- **Verantwortlicher:** Rolle Buchung/Disposition (Modul `booking-dispatch`, Fachoperationen `service-geo`)
- **Arbeitspaket:** WP-M2-12
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Die Engine prüft für jede Anfrage geografische und zeitliche Zulässigkeit.

**Originalabnahme (unverändert):** Anfrage außerhalb Bedienzeiten oder Gebiet wird ohne Fahrzeugzuweisung abgelehnt.

## Vertrag

- **Tabellen / DDL:** keine neuen. Gelesen: `service_area` (`area`, `valid_from`, `valid_to`, `revision`; nur zum Fahrtzeitpunkt wirksame Revision), `stop_revision` (`point`, `accessibility`, `valid_from`/`valid_to`), Betriebszeit des Dienstes (WP-M2-03, `service.timezone`); Schreibend nur `travel_attempt` (`primary_rejection`, `secondary_rejections`). Mandantenisolation per RLS (V1).
- **API:** Anfrage über `POST /v1/ride-offers`; unzulässige Anfrage: kein Treffer als `200` mit leerem Angebot und normiertem Ablehnungsgrund (analog Kap. 05 §5.7/§5.1), fehlerhafte Eingabe `422`. Das Pflichtenheft legt Antwortform und Codes der Ablehnungsgründe nicht fest (OP-14).
- **Prüfreihenfolge (Vorschlag, Kap. 01 §1.4 Schritt 1 und 2):** (1) Betriebszeit des Dienstes, (2) Gebiet: Start und Ziel in wirksamem `service_area` zum Fahrtzeitpunkt, (3) Relationsregel (WP-M2-06), (4) buchbare Haltepunkte in Reichweite `stop_walk_max_m`, bei Rollstuhlbedarf nur Haltepunkte mit geeignetem `stop_revision.accessibility` (Minimalfeld aus WP-M2-05). Erste fehlgeschlagene Prüfung liefert den Primärgrund, weitere Verletzungen als Sekundärgründe; Zuordnung: Betriebszeit/Frist zu `Zeitfensterablehnungen`, Gebiet zu `Gebietsablehnungen`. Konkrete Codes: OP-14.
- **Nachweis „keine Fahrzeugzuweisung“:** kein Aufruf des Routing-Workers (Zähler/Trace), kein `capacity_hold`, keine neue Planrevision (`vehicle_run.revision` unverändert).
- **Zustandsautomat / Schema / Ereignisse:** keine.
- **Parameter und Kennzahlen:** `stop_walk_max_m` (LH-J.1-stop_walk_max_m, Dienst/Profil); Datenbasis `Gebietsablehnungen` (LH-K.1-Zeile-06), `Zeitfensterablehnungen` (LH-K.1-Zeile-07; Trennung Betrieb geschlossen und Frist).
- **Oberfläche:** Ablehnungsanzeige in UI-01 (Keine Abdeckung, ungültige Zeit) mit normiertem Grund und nächster Handlung.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-14 (Katalog der Ablehnungsgründe, Prioritätsregel Primärgrund), OP-36 (Gehzeitmodell, Datenquelle sichere Treffpunkte), Gebiet-Relationsregel aus WP-M2-06. Parallelfahrtenverbot/ÖPNV nicht im Umfang (OPT-012, P2). Nicht blockierend (Grundcodes im Bau als Vorschlag).

## Tests

- **Positiv:** zulässige Anfrage (Start/Ziel im Gebiet, Betriebszeit offen, geeignete Haltepunkte in Reichweite) erreicht die Kandidatensuche.
- **Negativ:** außerhalb der Betriebszeit; Start oder Ziel außerhalb des Gebiets; kein Haltepunkt in `stop_walk_max_m`; Gebiet zum Fahrtzeitpunkt nicht wirksam (`valid_from`/`valid_to`); Rollstuhlbedarf nur mit ungeeignetem Haltepunkt (`accessibility`) wird mit normiertem Grund abgelehnt, kein ungeprüfter Haltepunkt angeboten. In allen Fällen: `travel_attempt` mit passendem `primary_rejection`; Solver-Aufrufzähler 0, kein Hold, keine Planrevision.
- **Konkurrierend:** entfällt (lesende Vorprüfung ohne Kapazitätswirkung); Gebietsrevisionswechsel parallel zur Anfrage verwendet konsistent genau eine Revision.
- **Referenztests:** AT-04 (Stoppteil „Stopp zulässig“).
- Testfälle: AT-04; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/EligibilityCheckTest.kt::zulaessige_anfrage_erreicht_kandidatensuche; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/EligibilityCheckTest.kt::ausserhalb_betriebszeit_ohne_fahrzeugzuweisung_abgelehnt; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/EligibilityCheckTest.kt::ausserhalb_gebiet_ohne_fahrzeugzuweisung_abgelehnt; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/EligibilityCheckTest.kt::gebiet_zum_fahrtzeitpunkt_nicht_wirksam_abgelehnt; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/EligibilityCheckTest.kt::kein_haltepunkt_in_stop_walk_max_m_abgelehnt; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/EligibilityCheckTest.kt::rollstuhl_ungeeigneter_haltepunkt_abgelehnt; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/EligibilityCheckTest.kt::ablehnung_schreibt_travel_attempt_primary_rejection; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/EligibilityCheckTest.kt::keine_fahrzeugzuweisung_solver_zaehler_null_kein_hold_keine_planrevision; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/EligibilityCheckTest.kt::betriebsschluss_und_frist_getrennt_zugeordnet; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/EligibilityCheckTest.kt::gebiet_fremder_mandant_wird_nicht_verwendet; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/EligibilityCheckTest.kt::gebietsrevisionswechsel_parallel_verwendet_eine_revision; backend/app/src/test/kotlin/de/taktovia/app/booking/RideOfferHttpApiTest.kt::zulaessigkeit_gebiet_und_rollstuhl_ueber_http

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
