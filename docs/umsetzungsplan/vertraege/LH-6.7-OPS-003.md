# LH-6.7-OPS-003 – Fahrzeugbezogene Stoppreihenfolge, Prognose und Besetzung

- **Verantwortlicher:** Rolle Leitstelle/Dispatch (Modul `booking-dispatch`, Web `web/operator`)
- **Arbeitspaket:** WP-M3-38
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Operator zeigt fahrzeugbezogene Stoppreihenfolgen, prognostizierte Zeiten und aktuelle Besetzung.

**Originalabnahme (unverändert):** Umschalten zwischen Fahrzeugen verändert Anzeige konsistent.

## Vertrag

- **Tabellen / DDL:** Lesemodell auf `vehicle_run` (`revision`, `state`), `route_stop` (Stoppfolge eindeutig je Runrevision; M2), `booking`/`passenger_trip` (Zusagefenster aus `promise_snapshot`) und Besetzung aus Fahrerereignissen (WP-M3-29, `APPLIED`). Keine neue Migration. RLS (V1) plus Dienstscope.
- **API:** Fahrzeugplan im Detail `GET /v1/operator/vehicles/{id}` bzw. aktuelle Revision über `GET /v1/runs/{id}/revisions` (Antwortschema Fahrzeugplan im OpenAPI-Entwurf): Planrevision, Stopps mit Sollzeit, Prognosezeit (oder Ausweis „keine Prognose"), Zusagefenster, aktuelle Besetzung je Ressourcendimension, offene `REVIEW_REQUIRED`-Fälle ausgewiesen. Besetzung = Differenz bestätigter Ein- und Ausstiege je Dimension. Fahrzeug fremden Dienstes oder Mandanten: 404. Fahrzeug ohne Run: Leerzustand.
- **Zustandsautomat:** keiner neu.
- **JSON-Schema / Ereignisse:** Antwortschema `vehicle_plan` (Arbeitsname); Aktualisierung über Live-Kanal WP-M3-35 mit Planrevision; Client verwirft Updates, deren Fahrzeug nicht dem gewählten entspricht.
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** UI-07 – Detailpanel Fahrzeugplan mit Planrevision und Datenstand; Umschalten zwischen Fahrzeugen ersetzt Stopps, Zeiten und Besetzung vollständig (keine Restanzeige des vorherigen Fahrzeugs); Prognose, Sollzeit und Zusagefenster sind visuell unterscheidbar; Leerzustand „Fahrzeug ohne Fahrt".
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-20 (Prognosezeit vor dem ETA-Grundmodell): in M3 gilt die Planzeit aus dem Routing bzw. der Ausweis „keine Prognose"; Bestätigung durch den Auftraggeber steht aus. Vollabnahme der Prognosezeiten erst mit WP-M4-61/WP-M4-62, kein Bau-Vorbedarf. Definition „aktuelle Besetzung" aus `APPLIED`-Ereignissen ist im Detailvertrag des WP vorgesehen und hier so festgelegt.

## Tests

- **Positiv:** Für zwei Fahrzeuge mit unterschiedlichen Plänen zeigt die Ansicht nach dem Umschalten A ↔ B jeweils nur Stopps, Zeiten und Besetzung des gewählten Fahrzeugs, auch während einer Planänderung an A; Besetzung entspricht der Differenz bestätigter Ein- und Ausstiege je Dimension.
- **Negativ:** Fahrzeug ohne Run zeigt Leerzustand; Fahrzeug fremden Dienstes oder Mandanten ergibt 404; fehlende Prognose wird ausgewiesen statt erfunden; Prognose, Sollzeit und Zusagefenster sind unterscheidbar; offene `REVIEW_REQUIRED`-Fälle fließen nicht in die Besetzung ein, werden aber ausgewiesen.
- **Konkurrierend:** Schnelles Umschalten während eintreffender Live-Updates – keine vermischte Anzeige (verspätete Antwort des vorigen Fahrzeugs wird verworfen).
- **Referenztests:** keine Katalog-ID im WP genannt.
- Testfälle: geplant:web/operator/src/vehicles/VehiclePlanPanel.test.tsx::umschalten_zeigt_nur_daten_des_gewaehlten_fahrzeugs; geplant:web/operator/src/vehicles/VehiclePlanPanel.test.tsx::schnelles_umschalten_mit_live_updates_mischt_nicht; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/operator/VehiclePlanProjectionTest.kt::besetzung_ist_differenz_bestaetigter_ein_und_ausstiege; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/operator/VehiclePlanProjectionTest.kt::stoppreihenfolge_entspricht_aktueller_runrevision_mit_soll_prognose_und_zusagefenster; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/operator/VehiclePlanProjectionTest.kt::review_required_nicht_in_besetzung_aber_ausgewiesen; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/operator/VehiclePlanProjectionTest.kt::fahrzeug_ohne_run_leerzustand_und_fremder_dienst_oder_mandant_404; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/operator/VehiclePlanProjectionTest.kt::fehlende_prognose_wird_ausgewiesen

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Dienst-/Mandantentrennung).
