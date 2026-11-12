# LH-I.3-ETA-002 – Regelkonforme Prognose mit Haltezeiten und Sicherheitsreserve

- **Verantwortlicher:** Rolle Disposition (ETA-Prognosebaustein; Modulzuordnung laut WP-M4-01, OP-34)
- **Arbeitspaket:** WP-M4-62
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Fahrzeitprognosen setzen rechtmäßige Geschwindigkeiten, Haltestellenaufenthalte und realistische Sicherheitsreserven voraus.

**Originalabnahme (unverändert):** Kein ausgegebenes Zeitfenster erfordert nach den verwendeten Routendaten eine Geschwindigkeitsüberschreitung.

## Vertrag

- **Tabellen / DDL:** `eta_model_revision` (aus WP-M4-61) um Puffer-/Quantilparameter erweitert (Migration nach V3); keine weitere Tabelle. Jede Prognose referenziert genau eine Modell-/Konfigurationsversion.
- **API:** Fachoperation „Prognose“ = Referenzfahrzeit (WP-M4-61) + Haltezeiten je Stopp (einschließlich Rollstuhl-Einstieg) + kalibrierter Unsicherheitspuffer; die drei Anteile werden je Prognose eigenständig ausgewiesen, zusätzlich Unsicherheit und verwendeter Puffer. Prüfroutine: für jedes ausgegebene Zeitfenster wird die implizite Geschwindigkeit je Abschnitt gegen die zulässige Geschwindigkeit der Routendaten geprüft; ein Fenster, das eine Überschreitung erfordern würde, wird nicht in dieser Form ausgegeben, es wird verlängert, nie verengt (Vorschlag, Prüfalgorithmus in WP-M4-01 festzulegen). `eta_safe_speed_tolerance` ist kein Freibrief und erweitert die zulässige Geschwindigkeit nicht. Leseport „ETA je Stopp“ (für WP-M2-34, WP-M2-43, WP-M3-26, WP-M3-38): Prognosezeit, Modell-/Konfigurationsversion, Datenstand, Fallbackgrund; bei Positionsdaten älter als `eta_max_data_age_sec` Rückfall laut `eta_fallback_policy`, die verbindliche Zusage bleibt unverändert.
- **Zustandsautomat / Schema / Ereignisse:** keine.
- **Parameter und Kennzahlen:** `stop_dwell_sec` (Dienst; Katalogvalidierung: nicht unter sichere Haltestellenabfertigung), `wheelchair_boarding_sec` (Fahrzeugtyp), `eta_model_quantile` (Dienst; p50/p75/p85/p90, Standardwert fehlt), `eta_safe_speed_tolerance` (km/h; kein Freibrief für Überschreitungen), `eta_max_data_age_sec`, `eta_fallback_policy`. Keine Kennzahlen (ETA-018/019/020/022 sind M8).
- **Oberfläche:** keine eigene; Verbraucher (Fahrgast-App, Benachrichtigung, Fahrer-App, Leitstelle) werden mit WP-M4-66 angebunden.
- **Entscheidungen:** D-10 (Grundmodell nach Favorit; blockiert nicht), D-09 (Routendaten mit Geschwindigkeitsattributen; blockiert Produktivschaltung).
- **Offene Punkte:** OP-34 (Höhe und Herleitung der Sicherheitsreserve und Standardwert `eta_model_quantile` im Pflichtenheft nicht beziffert; Bau mit konfigurierbarem Quantil und Planungsdefault, Freigabe vor Produktivschaltung; Modulzuordnung, Testpfad folgt dem Aufrufer `booking-dispatch`); Verhalten bei Abschnitt ohne Geschwindigkeitsbegrenzung in den Routendaten (Vorschlag: fail-closed, Fallbackgrund statt Fenster, in WP-M4-01 festzulegen); Werte von `eta_fallback_policy`; OP-41 (Umfang laufender ETA). Vollabnahme mit angebundenen Verbrauchern erst mit WP-M4-66.

## Tests

- **Positiv:** Strecken mit bekannten Tempolimits: kein ausgegebenes Zeitfenster erfordert nach den Routendaten eine Geschwindigkeitsüberschreitung; Haltezeit und Sicherheitsreserve sind als eigene Anteile je Prognose ausgewiesen, die Reserve folgt dem konfigurierten `eta_model_quantile`; Leseport liefert für eine laufende Fahrt Prognose mit Datenstand.
- **Negativ:** Abschnitt ohne Limitangabe ergibt das definierte Verhalten (kein stilles Annehmen eines hohen Limits); Rollstuhl-Stopp verlängert den Haltezeitanteil um `wheelchair_boarding_sec`; `stop_dwell_sec` unterhalb der Systemgrenze wird bei der Konfiguration abgewiesen; künstlich zu schnelle Eingabewerte erzeugen kein engeres Fenster (PH-AT-022 Teilaspekt); `eta_safe_speed_tolerance` erlaubt keine Überschreitung; Positionsdaten älter als `eta_max_data_age_sec` ergeben Fallbackgrund, die Zusage bleibt unverändert.
- **Konkurrierend (Sicherheit):** Konfigurationswechsel während der Angebotsbildung: jede Prognose trägt genau eine Modell-/Konfigurationsversion, nie eine Mischung; der Nachweis an echten Angeboten folgt mit WP-M4-66.
- **Referenztests:** PH-AT-022 (Teilaspekte: keine engere Zusage durch zu schnelle Werte, Referenz bleibt aktiv).

Testfälle: PH-AT-022; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/EtaForecastTest.kt::kein_zeitfenster_erfordert_geschwindigkeitsueberschreitung; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/EtaForecastTest.kt::haltezeit_und_reserve_als_eigene_anteile_mit_quantil; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/EtaForecastTest.kt::rollstuhl_stopp_verlaengert_haltezeit; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/EtaForecastTest.kt::stop_dwell_sec_unter_systemgrenze_wird_abgewiesen; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/EtaForecastTest.kt::zu_schnelle_eingabewerte_verengen_fenster_nicht; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/EtaForecastTest.kt::toleranz_ist_kein_freibrief_fuer_ueberschreitung; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/EtaForecastTest.kt::abschnitt_ohne_limit_ergibt_fallbackgrund; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/EtaForecastTest.kt::veraltete_positionsdaten_ergeben_fallbackgrund_zusage_unveraendert; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/EtaForecastTest.kt::konfigurationswechsel_waehrend_angebotsbildung_genau_eine_version

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Sicherheit).
