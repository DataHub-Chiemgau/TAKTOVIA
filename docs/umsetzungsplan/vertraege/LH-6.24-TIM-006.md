# LH-6.24-TIM-006 – Hinweise zu Pausen, Ruhezeit und Überschreitungen

- **Verantwortlicher:** Rolle Personal/Zeit (Modul `staff-time`)
- **Arbeitspaket:** WP-M4-13
- **Status:** Bereit (unter Planungsannahme PA-09, siehe planungsannahmen.md; keine Entscheidung des Auftraggebers)
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Pausen, Ruhezeitverletzungen und auffällige Überschreitungen werden nachvollziehbar angezeigt, ohne die gesetzliche Prüfung vollständig zu automatisieren.

**Originalabnahme (unverändert):** Testdaten zu Ruhe-/Pausenverletzung erzeugen passenden Warnhinweis.

## Vertrag

- **Tabellen / DDL:** keine eigene Tabelle zwingend; Hinweise werden aus `staff_shift` (Plan) und `time_event` (Ist) berechnet und mit Regel, Berechnungsbasis und betroffenen Schichten/Zeiten ausgegeben (ob persistiert, ist mit dem Bau zu entscheiden). Hinweise ändern weder Plan- noch Ist-Daten. Sichtbarkeit nur für Planer mit Depotrecht und die betroffene Person (RLS/Objektberechtigung).
- **API:** Hinweisliste im Lesemodell von `/v1/rosters` und in der Soll/Ist-Sicht von `/v1/time-events`; Aufruf im Dienstplan-Entwurf, bei der Publikation (`/v1/rosters/{roster_id}/publish`, `api/rosters.yaml`) und über Ist-Zeiten. Statuscodes 200, 401, 403.
- **Zustandsautomat:** keiner.
- **JSON-Schema / Ereignisse:** Hinweisobjekt mit Regel, Sollwert, Istwert, Bezug (Schema vorab festzulegen).
- **Parameter und Kennzahlen:** `min_rest_hours` (Stunden, Rechtsraum, rechtlich erforderliche Mindestdauer), `max_daily_work_minutes` (Minuten, Rechtsraum); beide „rechtliche Vorgaben nicht überschreibbar“: Werte unterhalb der rechtlichen Systemgrenze werden von der Konfiguration abgewiesen. Für die Pause gibt es keinen Katalogparameter.
- **Oberfläche:** Warnhinweis in Dienstplan und Zeitübersicht mit Regel, Sollwert, Istwert; kein automatischer Rechtsentscheid; Anzeige nur für Planer mit Depotrecht und betroffene Person.
- **Entscheidungen:** D-10 (Mitbestimmung; blockiert nur Produktivschaltung).
- **Offene Punkte:** Regelmatrix für AT-40 fehlt (WP-M4 Offener Punkt 3): wer sie liefert (AG-Freigabe), Pausenregel ohne Katalogparameter, und ob eine Ruhezeitverletzung in P1 die Publikation sperrt (Warnung oder Sperre). Das blockiert den Bau der Pausen- und Publikationsregel (Akzeptanzkriterium 2 und 4 des WP) bis zur Freigabe; Ruhezeit- und Tagesarbeitszeit-Hinweise mit den Katalogparametern sind baubar. Der Testfall zur fehlenden Pflichtpause (Akzeptanzkriterium 2, Teil Pause) wird erst nach Freigabe der Regelmatrix benannt. Deshalb bleibt der Status „Vertrag in Arbeit“.

- **Planungsannahme:** PA-09 in [planungsannahmen.md](../planungsannahmen.md) gilt als Arbeitsstand; offene Punkte oben bleiben bis zur Entscheidung des Auftraggebers bestehen.

## Tests

- **Positiv:** regelkonformer Plan ohne Hinweis; Testdaten mit unterschrittener Ruhezeit erzeugen einen Warnhinweis mit Regel, Sollwert und Istwert; fehlende Pflichtpause und überschrittene Tagesarbeitszeit erzeugen den passenden Hinweis (Originalabnahme).
- **Negativ:** Ruhezeit 1 min unter Grenze; Grenzfall exakt auf der Grenze (kein Hinweis); Verletzung über DST-Umstellung; Parameterwert unter rechtlicher Systemgrenze wird abgewiesen; Hinweise für Dritte ohne Depotrecht nicht sichtbar; Hinweis ändert keine Plan-/Ist-Daten.
- **Konkurrierend:** Publikation parallel zu einem neuen Ist-Stempel derselben Person: Hinweisberechnung bleibt konsistent und verändert keine Daten (Datentrennung).
- **Referenztests:** AT-40 (erst nach Freigabe der Regelmatrix erfüllbar).
- Testfälle: AT-40; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/RuheZeitHinweisTest.kt::ruhezeit_eine_minute_unter_grenze_erzeugt_warnhinweis; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/RuheZeitHinweisTest.kt::tagesarbeitszeit_ueberschreitung_erzeugt_hinweis; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/RuheZeitHinweisTest.kt::ruhezeit_exakt_auf_grenze_kein_hinweis; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/RuheZeitHinweisTest.kt::ruhezeit_ueber_dst_umstellung_aus_absoluten_zeitpunkten; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/RuheZeitHinweisTest.kt::hinweis_fuer_dritte_ohne_depotrecht_nicht_sichtbar; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/RuheZeitHinweisTest.kt::pflichtpause_fehlt_erzeugt_hinweis_planungsannahme; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/RuheZeitHinweisTest.kt::hinweise_aendern_keine_plan_oder_ist_daten_und_regeln_sind_konfigurierbar; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/RuheZeitHinweisTest.kt::ist_hinweise_aus_zeitereignissen; backend/app/src/test/kotlin/de/taktovia/app/staff/ConfigWorkRuleSourceTest.kt::wirksame_werte_ersetzen_die_gesetzlichen_standardwerte; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/RosterPublishTest.kt::publikation_parallel_zu_stempel_hinweis_konsistent_ohne_datenaenderung; backend/modules/config/src/test/kotlin/de/taktovia/config/ArbeitszeitParameterTest.kt::wert_unter_rechtlicher_systemgrenze_abgewiesen

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
