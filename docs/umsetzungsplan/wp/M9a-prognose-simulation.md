# M9a – Nachfrageprognose und Szenariosimulation

- **Meilenstein:** M9 – Phase 3 (72 Anforderungen: 9 MUSS, 46 SOLL, 17 KANN); M9a ist der Teil „Nachfrageprognose mit Model Registry und Monitoring; Szenariosimulation mit eigenen Daten, Workern, Queue und Ressourcenlimits“. E-Mobilität und die verstreuten P3-Einzelanforderungen liegen in den Nachbareinheiten von M9.
- **Phase:** P3 (6 MUSS, 9 SOLL, 3 KANN; Phase und Priorität jeder Anforderung unverändert)
- **Anzahl Anforderungen:** 18 (AI 9, SIM 9)
- **Anzahl WPs:** 24 (18 mit Anforderungen, 6 Enabler)

**Ziel:** Nach M9a erzeugt die Plattform je Gebiet und Zeithorizont Nachfrageprognosen, die immer einem freigegebenen, versionierten Modell zugeordnet sind, überwacht deren Güte gegen ein naives Referenzmodell und leitet daraus unverbindliche, begründete Vorschläge für Schichtplanung und Fahrzeugstandorte ab. Prognosen sind ausschließlich Hinweise: harte Kapazitäts-, Sicherheits- und Betriebsregeln bleiben unberührt, und ein Ausfall des KI-Dienstes verhindert weder Buchung noch Disposition. Planer können Szenarien aus historischer oder synthetischer Nachfrage in einer vom Produktionsbetrieb getrennten Daten- und Rechenumgebung simulieren, reproduzierbar auswerten und vergleichen.

**Gate-Anteil M9a (Umsetzungsplan §4, Gate M9):** führend AT-21 (Prognoseausfall) und AT-22 (Simulationsisolation); Beitrag zu AT-55 (Unterdeckung und KI-Bedarf; die Vorschlagsübernahme und Publikation im Dienstplan liegt bei M7d). AT-20 und AT-58 (E-Flotte) gehören nicht zu M9a. AT-21, AT-22 und AT-55 werden gemeinsam mit M9b abgenommen (WP-M9b-57: Dispositions-, Entkopplungs- und Personalplanungsseite); M9a liefert den Prognose- und Simulationsdienst.

**Lesehinweise:**

- Kurz-IDs in Klammern hinter Akzeptanzkriterien verweisen auf die Anforderung dieser Einheit (AI-0xx = `LH-6.15-AI-0xx`, SIM-0xx = `LH-6.16-SIM-0xx`).
- `openapi.yaml` enthält für diesen Bereich **keinen** ausformulierten Pfad. Kap. 05 §5.7 nennt für „ETA/AI/Simulation“ nur `/v1/eta/models/{id}/quality`, `/v1/eta/profiles/{id}/challenge`, `/v1/scenarios` und `/v1/scenarios/{id}/runs`; die beiden ETA-Pfade gehören zu M8b. Für die Nachfrageprognose nennt das Pflichtenheft **keinen** Pfad. Alle weiteren Pfade in dieser Datei sind als **Arbeitsname** gekennzeichnet und werden in WP-M9a-01 festgelegt.
- Kap. 04 §4.4 nennt für „AI/ETA/SIM“ die Tabellen `driving_observation`, `eta_model_revision`, `eta_profile`, `model_approval`, `simulation_run` (Regel: „Freigaben und Zweck; Trainings-/Simulationsdaten getrennt von Live“). Für diese Einheit relevant sind `model_approval` und `simulation_run`; für Nachfragemodelle, Prognoseläufe, Szenarien und Ergebnisse gibt es **keine** benannten Tabellen. Weitere Tabellen in dieser Datei sind **Arbeitsnamen**. `forecast_scenario` (Kap. 04 §4.4, Zeile FCO/FIN) ist die Finanzprognose aus M8a und nicht die Nachfrageprognose dieser Einheit.
- Im Kern-DDL (`kernschema.sql`) vorhanden und hier nur **gelesen** (über Projektionen, nie direkt aus den Workern): `travel_attempt`, `booking`, `journey`, `journey_leg`, `fare_quote`, `vehicle`, `vehicle_run`, `staff_shift`, `economic_event`, `config_revision`; geschrieben werden aus dem Kern nur `audit_event` und `outbox`.
- Architekturvorgaben, die für alle WPs gelten: Modul „Analytics/Forecast“ mit Aggregaten MetricDefinition, DatasetRevision, Scenario und der Zusage „Reproduzierbare Datenstände; keine Schreibrechte auf Live-Buchungen“ (Kap. 01 §1.3); „Reporting und Simulation belasten keine Live-Buchung“ (Kap. 01 §1.1); „Simulation hat separate Daten, Worker, Queue und Ressourcenlimits“ und „Jede Prognose liefert Modellversion, Unsicherheit und Fallbackgrund“ (Kap. 01 §1.6); „Mindestzellgrößen/Unterdrückung kleiner Gruppen“ für aggregierte Statistik (Kap. 01 §1.7); „Produktionsdaten werden nicht in Entwicklung kopiert“ (Kap. 01 §1.7).
- `kataloge.csv` enthält **keinen** Parameter für Prognose oder Simulation (Horizont, Zeitraster, Güteschwelle, Drift-Schwelle, Reproduktionstoleranz, Ressourcenlimit) und **keine** Kennzahl für den Prognosefehler der Nachfrage oder für Wartezeit. Solche Größen sind in dieser Datei als **Arbeitsname** bzw. als Fixturewert gekennzeichnet und stehen unter „Offene Punkte“.
- Die Zielstruktur des Repositorys (Umsetzungsplan §3) nennt `backend/modules/analytics`, `workers/routing` und `workers/documents`. `workers/forecast` und `workers/simulation` sind Arbeitsnamen dieser Datei.
- KANN-Anforderungen (`LH-6.15-AI-009`, `LH-6.16-SIM-008`, `LH-6.16-SIM-009`) sind geplant, werden aber nur nach ausdrücklicher Aufnahme ins Release gebaut (Kap. 08 §8.4, Umsetzungsplan M9).
- Abnehmer in der Nachbareinheit M9b, die Fähigkeiten dieser Einheit als EXTERN[M9a] voraussetzen: WP-M9b-22 (`LH-6.5-OPT-020`, Fahrzeugpositionierung: Prognose je Gebiet und Zeithorizont mit Modellversion), WP-M9b-34 (`LH-6.10-PAY-017`, Tarif-Sandbox: isolierte Simulationsumgebung und Szenarioläufe), WP-M9b-41 (`LH-6.14-ANA-012`: Prognosegüte- und Simulationsergebnisse), WP-M9b-48 (`LH-6.23-SCH-034`: anonymisierte Nachfrageprognose mit Modellversion), WP-M9b-54 (`LH-6.19-NFR-019`: Prognose- und Simulationsworker mit Queue), WP-M9b-55 (`LH-6.20-QAT-011`: Prognosemodelle, Simulationsverfahren, Model Registry), WP-M9b-57 (Gate). Diese Anforderungen gehören **nicht** zu M9a; die hier gelieferten Leseports sind in WP-M9a-01 als Vertrag festzuhalten (Offener Punkt 19).
- Referenztests aus Kap. 08 §8.5 (PH-AT) sind vorgeschlagene technische Abnahmetests anderer Module; wo sie hier genannt sind, wird ihr **Prüfmuster** auf die Tabellen und Endpunkte dieser Einheit angewendet (PH-AT-001 Isolation, PH-AT-004 letzter Platz unter Konkurrenz, PH-AT-011 Idempotenzkonflikt, PH-AT-020 Fahrzeugfreigabe, PH-AT-021 Nenner/Datenlücke/Aggregationsschutz).
- Größen: S bis 1, M bis 3, L bis 6 Personenwochen.

## Übersicht

| WP-ID | Titel | Anforderungen (Anzahl) | Größe | hängt ab von |
|---|---|---:|---|---|
| WP-M9a-01 | Detailverträge M9a: Prognose, Model Registry, Szenario und Simulationslauf | – (Enabler) | M | WP-M0-31, WP-M0-13, WP-M1-02, WP-M1-13, WP-M1-15, WP-M1-07, WP-M4-46, WP-M4-45, WP-M4-58, WP-M8b-01, WP-M8b-04, WP-M8c-04, WP-M8c-11, WP-M9b-01 |
| WP-M9a-02 | Migration der Prognose- und Simulationstabellen mit RLS und getrenntem Datenbereich | – (Enabler) | M | WP-M9a-01, WP-M1-01, WP-M1-07, WP-M1-46, WP-M8b-02, WP-M8b-23 |
| WP-M9a-03 | Referenzdatensatz: synthetische Nachfragehistorie, Prognose-Orakel, Referenzszenarien | – (Enabler) | M | WP-M9a-02, WP-M4-45, WP-M4-47, WP-M4-48, WP-M5-10, WP-M5-11 |
| WP-M9a-04 | Nachfragedatensatz als versionierte DatasetRevision und Trainingsworker | – (Enabler) | M | WP-M9a-02, WP-M9a-03, WP-M2-11, WP-M2-23, WP-M4-45, WP-M8c-05, WP-M8c-11 |
| WP-M9a-05 | Model Registry: Version, Trainingszeitraum, Datenherkunft, Freigabe | 1 | M | WP-M9a-02, WP-M9a-04, WP-M1-07, WP-M1-08, WP-M1-22, WP-M1-46, WP-M8b-04, WP-M8b-02 |
| WP-M9a-06 | Prognoselauf: Wert und Zeithorizont je Gebiet aus Historie, Datum, Wochentag | 1 | M | WP-M9a-04, WP-M9a-05, WP-M2-04, WP-M8c-19 |
| WP-M9a-07 | Zusatzsignale Ferien, Veranstaltungen, Wetter und gekennzeichneter Basisforecast | 1 | M | WP-M9a-06, WP-M1-07, WP-M1-08, WP-M1-46, WP-M1-36, WP-M7d-11, WP-M8d-08 |
| WP-M9a-08 | Prognose als reiner Hinweis: harte Regeln nicht umgehbar | 1 | M | WP-M9a-06, WP-M2-22, WP-M2-23, WP-M2-20, WP-M3-08, WP-M3-09, WP-M4-13 |
| WP-M9a-09 | Trennung von Training und Live-Disposition mit sicherem Rückfall | 1 | M | WP-M9a-06, WP-M9a-08, WP-M2-17, WP-M2-22, WP-M2-23, WP-M5-10, WP-M5-11, WP-M8d-22 |
| WP-M9a-10 | Prognosefehler-Dashboard mit naivem Referenzmodell | 1 | M | WP-M9a-06, WP-M4-46, WP-M4-45, WP-M4-47, WP-M8c-04, WP-M8c-32, WP-M8c-11 |
| WP-M9a-11 | Modellüberwachung: Drift, Datenqualität, Fehlerraten mit Hinweis an zuständige Rolle | 1 | M | WP-M9a-05, WP-M9a-10, WP-M8d-22, WP-M8d-20, WP-M8d-23, WP-M8d-16 |
| WP-M9a-12 | Vorschläge für Schichtplanung und Leerfahrzeugstandorte mit Begründung und Effektschätzung | 1 | M | WP-M9a-06, WP-M9a-08, WP-M3-36, WP-M2-16, WP-M4-03, WP-M4-46, WP-M7d-32, WP-M7d-20 |
| WP-M9a-13 | Auditierbare Datenminimierung der Trainingspipeline | 1 | S | WP-M9a-04, WP-M9a-05, WP-M8b-22, WP-M8b-23, WP-M8b-06 |
| WP-M9a-14 | Simulationsumgebung: eigene Queue, Worker, Ressourcenlimits, Leseport ohne Live-Schreibrecht | – (Enabler) | M | WP-M9a-02, WP-M0-27, WP-M1-15, WP-M1-14, WP-M1-02, WP-M2-17, WP-M2-10, WP-M2-16, WP-M5-10, WP-M5-11 |
| WP-M9a-15 | Szenario aus historischer Nachfrage oder synthetischen Buchungen, getrennt von Produktion | 1 | M | WP-M9a-03, WP-M9a-04, WP-M9a-14, WP-M1-07, WP-M1-08, WP-M1-01, WP-M1-46 |
| WP-M9a-16 | Szenarioparameter: Fahrzeuge, Schichten, Gebiete, Betriebszeiten, Optimierungsgewichte | 1 | M | WP-M9a-15, WP-M1-19, WP-M1-20, WP-M1-02, WP-M2-04, WP-M2-03, WP-M3-03, WP-M2-20, WP-M4-03 |
| WP-M9a-17 | Simulationslauf mit Nachfrageerfüllung, Kostenindikatoren, Wartezeit, Fahrzeugkilometern | 1 | L | WP-M9a-14, WP-M9a-15, WP-M9a-16, WP-M1-13, WP-M1-15, WP-M1-46, WP-M1-07, WP-M1-08, WP-M2-17, WP-M2-16, WP-M2-10, WP-M8a-39, WP-M8a-04, WP-M8c-22 |
| WP-M9a-18 | Reproduzierbarkeit: Algorithmusversion, Eingabedatensatz, Zufallsseed, Toleranz | 1 | S | WP-M9a-17 |
| WP-M9a-19 | Szenariovergleich mit absoluten und relativen Differenzen | 1 | M | WP-M9a-17, WP-M9a-18, WP-M8c-19, WP-M8c-04 |
| WP-M9a-20 | E-Flotten-Simulation: Ladeinfrastruktur, Reichweiten, Standzeiten | 1 | M | WP-M9a-16, WP-M9a-17, WP-M9b-02, WP-M9b-05, WP-M9b-10 |
| WP-M9a-21 | Anonymisierte Heatmaps und Engpässe je Gebiet aus Simulationsergebnissen | 1 | M | WP-M9a-17, WP-M8b-29, WP-M8c-12, WP-M8c-11 |
| WP-M9a-22 | Tarifsimulation auf anonymisierten historischen Fahrten ohne reale Wirkung | 1 | M | WP-M9a-15, WP-M9a-17, WP-M2-07, WP-M2-08, WP-M2-09, WP-M0-23, WP-M8b-29, WP-M8b-06, WP-M8d-10 |
| WP-M9a-23 | Maßnahmenempfehlungen mit Sensitivität und ausgewiesenen Limitierungen | 1 | M | WP-M9a-17, WP-M9a-19 |
| WP-M9a-24 | M9a-Gate-Abnahmesuite: AT-21, AT-22, Beitrag AT-55 | – (Enabler) | M | WP-M9a-08, WP-M9a-09, WP-M9a-11, WP-M9a-12, WP-M9a-14, WP-M9a-15, WP-M9a-17, WP-M9a-19, WP-M9a-21, WP-M5-10, WP-M5-11, WP-M5-01, WP-M7d-20, WP-M7d-32, WP-M9b-57 |

## Arbeitspakete

### WP-M9a-01 – Detailverträge M9a: Prognose, Model Registry, Szenario und Simulationslauf

- **Ziel:** Vor Codebeginn liegen alle Verträge der Einheit vor: OpenAPI für Prognose und Simulation, DDL der Ergänzungstabellen, Zustandsautomaten für Modellversion, Prognoselauf, Szenario und Simulationslauf sowie die JSON-Schemata für Szenarioparameter und Ergebnis.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - API: OpenAPI-3.1-Ergänzung für `/v1/scenarios` und `/v1/scenarios/{id}/runs` (Kap. 05 §5.7) nach den Konventionen aus Kap. 05 §5.1 (Fehlermodell `application/problem+json`, `limit`/`cursor`, Antwort `{items,next_cursor,as_of}`, ETag/`If-Match`, Idempotenz, asynchrone Läufe über die `operation`-Ressource `/v1/operations/{id}`); Festlegung der Arbeitsnamen-Pfade `/v1/forecast/models`, `/v1/forecast/models/{id}/approval`, `/v1/forecast/runs`, `/v1/forecast/runs/{id}/values`, `/v1/forecast/quality`, `/v1/forecast/suggestions`, `/v1/forecast/signal-sources`, `/v1/forecast/models/{id}/data-audit`, `/v1/scenarios/{id}`, `/v1/scenarios/{id}/runs/{run_id}`, `/v1/scenarios/{id}/runs/{run_id}/heatmap`, `/v1/scenarios/{id}/recommendations`, `/v1/scenarios/compare` oder deren Abbildung auf Katalogpfade (z. B. Prognosegüte über `/v1/analytics/metrics`).
  - Datenbank: DDL-Entwurf für `simulation_run` und `model_approval` (Erweiterung gegenüber M8b um den Modellzweck „Nachfrageprognose“) sowie für die Arbeitsnamen-Tabellen aus WP-M9a-02; Entscheidung, ob Nachfragemodelle in einer verallgemeinerten `eta_model_revision` oder in einer eigenen Tabelle liegen (Offener Punkt 2).
  - Backend: Zustandsautomaten Modellversion (Entwurf → trainiert → freigegeben → aktiv → zurückgezogen), Prognoselauf (angefordert → laufend → abgeschlossen / fehlgeschlagen / Basisforecast), Szenario (Entwurf → eingefroren), Simulationslauf (eingereiht → laufend → abgeschlossen / fehlgeschlagen / abgebrochen / Ressourcenlimit überschritten / unzulässig); JSON-Schema Szenarioparameter (Fahrzeuganzahl, Schichten, Gebiete, Betriebszeiten, Optimierungsgewichte), JSON-Schema Simulationsergebnis (Kennzahlen, Versions- und Annahmendaten), JSON-Schema Prognosewert (Gebiet, Zeithorizont, Wert, Modellversion, Unsicherheit, Fallbackgrund – Kap. 01 §1.6), JSON-Schema Vorschlag (Begründung, Effektschätzung, Kennzeichen „unverbindlich“).
  - Ereignisse/Outbox: Ereignistypen (Arbeitsnamen) `forecast.run.completed`, `forecast.model.approved`, `forecast.anomaly.detected`, `forecast.suggestion.created`, `simulation.run.completed` mit Empfängerbindung und minimiertem Payload.
  - Backend (Rechte): Rollen-/Rechtematrix der Einheit (Modell anlegen, Modell freigeben/zurückziehen, Signalquelle aktivieren, Prognoselauf starten, Güte/Überwachung einsehen, Vorschlag verwerfen, Szenario anlegen/ändern/löschen, Lauf starten/abbrechen, Vergleich/Heatmap/Auditbericht lesen) mit Objekt-Scope je Mandant, Gebiet und Depot; das Pflichtenheft nennt dafür keine Rollen- oder Rechtenamen (Offener Punkt 21).
  - Backend (Leseports): Vertrag des Prognose-Leseports (nur lesend; Gebiet, Zeithorizont, Wert, Modellversion, Unsicherheit, Fallbackgrund, Antwort „keine Prognose“) und des Simulations-Ergebnisports für die Abnehmer WP-M7d-32, WP-M9b-22, WP-M9b-34, WP-M9b-41, WP-M9b-48 (Offener Punkt 19).
  - Datenbank (Lebenszyklus): Zuordnung jeder neuen Tabelle und des Modellartefakts im Objektspeicher zu einer Aufbewahrungs-/Löschklasse aus M8b (Werte offen, D-11).
  - Konfigurationsparameter: Liste der fehlenden Parameter (Prognosehorizont, Zeitraster, Güteschwelle, Drift-Schwelle, Reproduktionstoleranz, Ressourcenlimits) als Vorschlag zur Aufnahme ins Konfigurationsregister; Verwendung der vorhandenen `kpi_aggregation_min_n`, `reporting_timezone`, `min_battery_soc_pct`, `min_return_soc_pct`, `charge_reservation_min`.
  - Kennzahlen: Zuordnung der Simulationskennzahlen zu Definitionen aus `kataloge.csv` (`Angebotsquote`, `Kapazitätsablehnungen`, `Kosten pro Fahrzeugkilometer`, `Kosten pro Fahrgastfahrt`, `Leerkilometerquote`, `Produktive Kilometer`, `Mittlere Abholverspätung`, `Schicht-Abdeckungsquote`) bzw. Kennzeichnung als neu zu definierende Größe.
- **Detailverträge vorab:** dieses WP ist der Detailvertrag; Reviewrollen gemäß Detailvertragsprozess aus M0.
- **Akzeptanzkriterien:**
  1. `validate_artifacts.py` läuft mit den ergänzten OpenAPI-Dateien fehlerfrei; alle `$ref` auflösbar, `operationId`s eindeutig, Beispiele schemagültig (Kap. 05 §5.8).
  2. Für jede der 18 Anforderungen ist ein Detailvertrag, ein Verantwortlicher und mindestens ein positiver Testfall benannt; für die 6 MUSS-Anforderungen ist das Gate-Voraussetzung (Kap. 08 §8.4).
  3. Jeder Arbeitsname dieser Datei (Pfad, Tabelle, Ereignis, Parameter) ist bestätigt oder durch den endgültigen Namen ersetzt.
  4. Die Offenen Punkte 1 bis 21 sind entschieden oder als gekennzeichnete Annahme mit Fixturewert dokumentiert.
  5. Die Rechtematrix nennt für jeden Endpunkt der Einheit die berechtigte Rolle und den Fall „fremdes oder nicht vorhandenes Objekt“ (gleichartige Antwort, Kap. 08 §8.5 PH-AT-001); die Leseport-Verträge sind von den abnehmenden Einheiten M7d und M9b gegengezeichnet.
- **Tests:** positiv: Vertragslinting und Schemavalidierung der Beispielpayloads. negativ: Prognosewert ohne Modellversion, Simulationsergebnis ohne Seed bzw. Datensatzrevision und Szenarioparameter mit unbekanntem Feld werden vom Schema abgelehnt.
- **Abhängigkeiten:** intern: keine.
  - extern: WP-M0-31, WP-M0-13 (Detailvertragsprozess und CI-Vertragsprüfung)
  - extern: WP-M1-02, WP-M1-13, WP-M1-15, WP-M1-07 (API-Konventionen (Fehlermodell, Idempotenz, ETag, `operation`-Ressource), Rollen-/Rechtemodell)
  - extern: WP-M4-46, WP-M4-45, WP-M4-58 (Kennzahlregister, Faktenschema, Gebiets-/Zeitdimensionen)
  - extern: WP-M8b-01, WP-M8b-04 (Vertrag `model_approval` und Modellfreigabe-Governance)
  - extern: WP-M8c-04, WP-M8c-11 (versionierte Kennzahldefinitionen und Kleingruppenregel)
  - extern: WP-M9b-01 (Abstimmung der Leseport-Verträge mit den Abnehmern (WP-M9b-22, -34, -41, -48) und des E-Mobilitätsmodells)
- **Entscheidungen:** D-05 (SLO-Zielwerte, Ressourcenlimits), D-11 (Aufbewahrung von Trainings- und Simulationsdaten), D-12 (Aufnahme der KANN-Funktionen) – blockieren nur Produktivschaltung; Verträge entstehen mit gekennzeichneten Annahmen.
- **Größe:** M
- **Nicht im Umfang:** ETA-Verträge (`/v1/eta/models/{id}/quality`, `/v1/eta/profiles/{id}/challenge`, M8b); Finanzprognose `forecast_scenario` (M8a); Implementierung.

### WP-M9a-02 – Migration der Prognose- und Simulationstabellen mit RLS und getrenntem Datenbereich

- **Ziel:** Alle Tabellen der Einheit existieren als Flyway-Migration mit Mandantenschlüssel, RLS, Revision und Audit; Simulations- und Trainingsdaten liegen in einem vom Live-Bestand getrennten Bereich mit eigenen Datenbankrollen.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Datenbank: Expand-Migration für `simulation_run` und Erweiterung `model_approval` (Kap. 04 §4.4); Arbeitsnamen-Tabellen `forecast_model_revision`, `forecast_dataset_revision`, `forecast_run`, `forecast_value`, `forecast_signal_source`, `forecast_quality_snapshot`, `forecast_suggestion`, `simulation_scenario`, `simulation_scenario_revision`, `simulation_dataset`, `simulation_result`, `simulation_comparison`. Jede Tabelle erhält Mandantenschlüssel, lokale zusammengesetzte FKs, RLS-Policy über `current_tenant()`, Revision und Audit (Kap. 04 §4.4 Einleitung). Eigenes Schema (Arbeitsname `sim`) für Simulations- und Trainingsdaten.
  - Datenbank (Rollen): eigene Modulzugänge für Prognose- und Simulationsworker: NOSUPERUSER, NOBYPASSRLS, kein Tabelleneigentum (Kap. 04 §4.6); **keine** INSERT/UPDATE/DELETE-Rechte auf `booking`, `capacity_hold`, `journey`, `journey_leg`, `payment_intent`, `vehicle_run`, `staff_shift`, `economic_event`; Leserechte nur auf freigegebene Projektionen.
  - Datenbank (Invarianten aus Kap. 04 §4.5): „Keine lokale Cross-Tenant-Referenz“ (zusammengesetzte FK + RLS) und „Kein Mandantenkontext → keine Zeile“ für alle neuen Tabellen; „Gegenereignis statt Änderung“ für `model_approval` und abgeschlossene `simulation_result`-/`forecast_value`-Zeilen (UPDATE/DELETE-Verbot per Trigger).
  - Datenbank (Lebenszyklus): je Tabelle hinterlegte Aufbewahrungs-/Löschklasse; Löschung von Trainings-, Szenario- und Ergebnisdaten ausschließlich über den kontrollierten Lifecycleprozess aus M8b (Kap. 04 §4.5 „Wartung/Löschung nur kontrollierter Lifecycleprozess“), Fristen offen (D-11).
  - Backend: Repositories im Modul `analytics` (Teilbereich Forecast); Tenantkontext je Workertransaktion über `set_config('app.tenant_id', …, true)`.
- **Detailverträge vorab:** DDL aus WP-M9a-01; Rollen-/Rechtematrix der Workerzugänge; Entscheidung getrenntes Schema vs. getrennte Datenbankinstanz (Offener Punkt 3).
- **Akzeptanzkriterien:**
  1. Migration läuft auf leerer Datenbank und aus dem freigegebenen Vorstand (M8) fehlerfrei.
  2. Unter der Simulations-Workerrolle schlägt jedes INSERT/UPDATE/DELETE auf die genannten Live-Tabellen mit Rechtefehler fehl.
  3. Ohne Mandantenkontext liefert jede neue Tabelle null Zeilen; Mandant B sieht keine Zeile von Mandant A.
  4. UPDATE/DELETE auf abgeschlossene `simulation_result`-Zeilen und auf `model_approval` schlägt fehl.
  5. Alle Rechte- und RLS-Prüfungen laufen unter den echten, nicht privilegierten Modulrollen, nicht als Tabellenbesitzer oder Superuser (Prüfmuster PH-AT-001).
  6. Die Löschung eines Szenarios bzw. einer DatasetRevision über den Lifecycleprozess entfernt ausschließlich Zeilen des Simulations-/Trainingsbereichs und ist im Audit nachvollziehbar.
- **Tests:** positiv: Migration frisch und inkrementell; Schreiben/Lesen unter den Modulrollen. negativ: Schreibversuch auf Live-Tabellen; Zugriff ohne Tenantkontext; Fremd-Tenant-FK. konkurrierend (Datentrennung): zwei parallele Transaktionen verschiedener Mandanten über denselben Pool – kein Kontextübertrag nach Poolwiederverwendung; Negativtest mit zwei parallelen Transaktionen je Invariante aus Kap. 04 §4.5 (Fremd-Tenant-FK gegen gleichzeitiges Anlegen; UPDATE auf abgeschlossene Ergebniszeile gegen gleichzeitigen Abschluss). Wendet das Prüfmuster PH-AT-001 auf alle Tabellen der Einheit an.
- **Abhängigkeiten:** intern: WP-M9a-01.
  - extern: WP-M1-01, WP-M1-07, WP-M1-46 (RLS-Grundgerüst, Rollenmodell, Audit, Testharness Tenantkontext)
  - extern: WP-M8b-02, WP-M8b-23 (bestehende Tabelle `model_approval`; Aufbewahrungsklassen und Lifecycleprozess)
- **Entscheidungen:** D-02 (Isolationsstufe; bei höherer physischer Isolation ändert sich der Zuschnitt) – blockiert nur Produktivschaltung. D-11 (Löschfristen) – blockiert nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Partitionierung großer Ergebnistabellen (erst nach gemessenem Volumen, Kap. 04 §4.7); `driving_observation`, `eta_model_revision`, `eta_profile` (M4/M8b); `charging_slot` (E-Mobilität, Nachbareinheit M9).

### WP-M9a-03 – Referenzdatensatz: synthetische Nachfragehistorie, Prognose-Orakel, Referenzszenarien

- **Ziel:** Ein deterministischer, vollständig synthetischer Datensatz mit bekannter Nachfragestruktur und vorab berechneten Sollergebnissen macht Prognose- und Simulationstests reproduzierbar.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: Generator für synthetische Nachfragehistorie (mehrere Gebiete, Wochentagsmuster, Ferien-/Veranstaltungseffekt, bewusst eingebaute Drift und Datenlücke) mit festem Seed; Generator für synthetische Buchungen; zwei Referenzszenarien A/B mit unterschiedlicher Fahrzeuganzahl; ein Referenzszenario mit E-Flotte ohne Ladeoption; ein Gebiet mit Gruppengröße unter `kpi_aggregation_min_n`.
  - Backend: Orakelwerte (naives Referenzmodell, erwarteter Prognosefehler, erwartete Simulationskennzahlen) als versionierte Fixtures in `tests/acceptance`; „falsche Prognose“-Fixture (stark überhöhter und stark untertriebener Wert) für WP-M9a-08.
  - Konfigurationsparameter: gekennzeichnete Fixturewerte für `kpi_aggregation_min_n`, `min_battery_soc_pct`, `min_return_soc_pct`, `charge_reservation_min` sowie für die Arbeitsnamen-Parameter aus WP-M9a-01 (kein Wert stammt aus dem Pflichtenheft).
- **Detailverträge vorab:** Fixtureformat und Seed-Konvention; Erfassungsfelder je Testlauf nach Kap. 08 §8.4 (`source_key[]`, Build, Migrationsstand, Seed und Datenstand).
- **Akzeptanzkriterien:**
  1. Zweimalige Erzeugung mit gleichem Seed liefert bytegleiche Fixtures.
  2. Der Datensatz enthält keine Produktionsdaten und keine realen Personen (Kap. 01 §1.7).
  3. Jedes WP mit Anforderung dieser Einheit hat mindestens eine Fixture, auf die sein positiver Test verweist.
- **Tests:** positiv: Determinismusprüfung; Orakel gegen Handrechnung an einem kleinen Beispiel. negativ: Fixture mit abweichendem Seed wird als andere Datensatzrevision erkannt.
- **Abhängigkeiten:** intern: WP-M9a-02.
  - extern: WP-M4-45, WP-M4-47, WP-M4-48 (Faktenschema für Nachfrage und Ablehnungen)
  - extern: WP-M5-10, WP-M5-11 (Lastprofil und SLO-Messaufbau als Basis für AT-22)
- **Entscheidungen:** D-05 (Pilotregion, Peaklast) – blockiert nur die Abnahme mit realitätsnahem Profil, nicht den Bau.
- **Größe:** M
- **Nicht im Umfang:** Lastgenerator für die Live-Buchung (M5); ETA-Beobachtungsgenerator (M8b).

### WP-M9a-04 – Nachfragedatensatz als versionierte DatasetRevision und Trainingsworker

- **Ziel:** Nachfragehistorie steht als eingefrorener, versionierter Datensatz mit Datenstand bereit, und ein von der Live-Disposition getrennter Worker kann darauf Modelle trainieren.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: Aggregat DatasetRevision (Kap. 01 §1.3) im Modul `analytics`: Extraktion der Nachfrage je Gebiet und Zeitraster aus Projektionen (Quelle `travel_attempt`, `booking` über Lesereplikat/inkrementelle Projektionen, Kap. 01 §1.1), mit Datenstand, Vollständigkeit und Herkunftsangabe; aggregierte Speicherung ohne Fahrgastkontakte.
  - Datenbank: `forecast_dataset_revision` (Arbeitsname; Zeitraum, Quelle, Datenstand, Hash), lesend `dataset_watermark`, `aggregate_snapshot`.
  - Adapter/Worker: `workers/forecast` (Arbeitsname, Python) mit eigener RabbitMQ-Queue; Trainingsauftrag über persistierte Operation mit Lease/Fencing (Kap. 01 §1.5); Ergebnis ist ein Modellartefakt im S3-kompatiblen Objektspeicher mit SHA-256 in der Datenbank.
  - Ereignisse/Outbox: Trainingsauftrag und -ergebnis über `outbox`/`inbox`.
- **Detailverträge vorab:** Datensatzschema (Gebiet, Zeitraster, Zählgröße), Auftragsvertrag Trainingsworker, Modellartefaktformat; Festlegung, welche Zählgröße „Nachfrage“ ist (Offener Punkt 4).
- **Akzeptanzkriterien:**
  1. Eine DatasetRevision ist nach Erzeugung unveränderlich; ein neuer Datenstand erzeugt eine neue Revision.
  2. Der Trainingsworker besitzt keine Verbindung mit Schreibrecht auf Live-Tabellen und liest ausschließlich die DatasetRevision.
  3. Ein abgebrochener Trainingsauftrag wird nach Workerneustart genau einmal fortgesetzt oder als fehlgeschlagen markiert.
  4. Der Datensatz enthält keine direkten Personenmerkmale (Name, Kontakt, Adresse in Klarform).
- **Tests:** positiv: Datensatz aus Referenzhistorie, Training eines Minimalmodells. negativ: Datensatzanforderung für fremden Mandanten ergibt 404; Datensatz mit Datenlücke wird mit Vollständigkeit < 100 % gekennzeichnet. konkurrierend (Datentrennung): zwei parallele Trainingsaufträge verschiedener Mandanten mischen keine Daten.
- **Abhängigkeiten:** intern: WP-M9a-02, WP-M9a-03.
  - extern: WP-M2-11, WP-M2-23 (Anfrageversuche mit `attempt_id` und Buchungsereignisse als Quelle)
  - extern: WP-M4-45 (Faktenbasis, Projektionen, `dataset_watermark`)
  - extern: WP-M8c-05, WP-M8c-11 (Datenvollständigkeit und Nachfragedefinition (nicht bediente Nachfrage))
- **Entscheidungen:** D-11 (Aufbewahrungsfrist für Trainingsdaten) – blockiert nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Modellfreigabe (WP-M9a-05); Prognoseausführung (WP-M9a-06); Nachweis der Datenminimierung als Auditbericht (WP-M9a-13).

### WP-M9a-05 – Model Registry: Version, Trainingszeitraum, Datenherkunft, Freigabe

- **Ziel:** Jedes Prognosemodell ist versioniert und mit Trainingszeitraum und Datenherkunft dokumentiert; nur freigegebene Modellversionen dürfen Prognoseläufe erzeugen.
- **Anforderungen:** `LH-6.15-AI-002`
- **Liefergegenstände:**
  - Backend: Aggregat Modellversion mit Zustandsautomat aus WP-M9a-01; Pflichtfelder Version, Trainingszeitraum (von/bis), DatasetRevision, Datenherkunft, Algorithmus-/Codeversion, Artefakt-Hash, Zweck; Freigabe als eigener, auditierter Schritt (Human Review, Kap. 05 §5.7) mit Trennung Ersteller/Freigeber (Planungsannahme dieser Datei, keine Vorgabe des Pflichtenhefts für Nachfragemodelle – Offener Punkt 20); Rückzug erzeugt neuen Zustand, keine Löschung.
  - Datenbank: `forecast_model_revision` (Arbeitsname), `model_approval`, `audit_event`; Fremdschlüssel `forecast_run` → freigegebene Modellversion (NOT NULL).
  - API: Arbeitsnamen `/v1/forecast/models` (anlegen, listen, lesen mit ETag) und `/v1/forecast/models/{id}/approval` (freigeben, zurückziehen; `If-Match` Pflicht).
  - Oberfläche: UI-19 – Modellliste mit Version, Trainingszeitraum, Datenherkunft, Freigabestatus und Freigeber; Zustände „Keine Daten“ (kein Modell), fehlendes Freigaberecht (Aktion nicht angeboten, 403 serverseitig), Konflikt bei veralteter Revision (412) mit Neuladen; Bedienung per Tastatur, Status als Text (Kap. 06 §6.1).
  - Ereignisse/Outbox: `forecast.model.approved` (Arbeitsname).
- **Detailverträge vorab:** Zustandsautomat Modellversion; Rollen für Anlegen und Freigeben; Verhältnis zur Modellfreigabe aus M8b (Offener Punkt 2).
- **Akzeptanzkriterien:**
  1. Jeder gespeicherte Prognoselauf verweist auf genau eine Modellversion, die zum Laufzeitpunkt freigegeben war (AI-002).
  2. Ein Prognoselauf mit nicht freigegebener oder zurückgezogener Modellversion wird abgelehnt und erzeugt keinen Prognosewert (AI-002).
  3. Zu jeder Modellversion sind Version, Trainingszeitraum und Datenherkunft lesbar; eine Modellversion ohne diese Angaben kann nicht freigegeben werden (AI-002).
  4. Freigabe und Rückzug sind mit Akteur, Zeit und Grund im Audit nachvollziehbar; Ersteller und Freigeber sind verschiedene Personen (Planungsannahme, Offener Punkt 20).
  5. Eine Modellversion ist nach Freigabe unveränderlich (Version, Trainingszeitraum, Datenherkunft, Artefakt-Hash); eine Änderung erzeugt eine neue Version. Ein Artefakt, dessen SHA-256 nicht zum gespeicherten Hash passt, wird nicht geladen.
  6. Modellversionen sind nur im eigenen Mandanten sichtbar; fremde ID ergibt 404.
- **Tests:** positiv: Modell anlegen, freigeben, Lauf zuordnen. negativ: Lauf mit Entwurfsmodell; Freigabe ohne Trainingszeitraum; Freigabe durch den Ersteller; veralteter ETag ergibt 412, fehlender `If-Match` 428; Freigabe ohne Freigaberecht 403; Modell eines fremden Mandanten 404; manipuliertes Artefakt (Hashabweichung). konkurrierend (Sicherheit): zwei gleichzeitige Freigaben derselben Version – genau eine `model_approval`-Zeile; gleichzeitiger Rückzug und Laufstart – der Lauf wird entweder vor dem Rückzug zugeordnet oder abgelehnt, nie einer zurückgezogenen Version.
- **Abhängigkeiten:** intern: WP-M9a-02, WP-M9a-04.
  - extern: WP-M1-07, WP-M1-08, WP-M1-22, WP-M1-46 (Rollen-/Objektberechtigung, Vier-Augen-Freigabe, Audit)
  - extern: WP-M8b-04, WP-M8b-02 (Modellfreigabe-Governance und `model_approval`)
- **Entscheidungen:** keine für den Bau; D-11 (Aufbewahrung der Modellartefakte) blockiert nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** ETA-Modelle und deren Rollout (M4/M8b); automatischer Modellwechsel bei schlechter Güte (das Pflichtenheft verlangt nur Kennzeichnung, WP-M9a-10).

### WP-M9a-06 – Prognoselauf: Wert und Zeithorizont je Gebiet aus Historie, Datum, Wochentag

- **Ziel:** Ein Prognoselauf liefert je Gebiet einen Prognosewert mit zugehörigem Zeithorizont, abgeleitet aus historischer Nachfrage, Datum und Wochentag.
- **Anforderungen:** `LH-6.15-AI-001`
- **Liefergegenstände:**
  - Backend: Prognosedienst im Modul `analytics` (Teilbereich Forecast): Laufauftrag (zeitgesteuert und manuell), Merkmalsbildung aus DatasetRevision, Datum und Wochentag; Ereignissignale nur, wenn sie freigegeben sind (Schalter je Mandant, Freigabe mit Akteur, Zeit und Grund im `audit_event`; Auswertung in WP-M9a-07); Ergebnis je Gebiet und Zeitabschnitt mit Prognosewert, Zeithorizont, Modellversion, Unsicherheit und Fallbackgrund (Kap. 01 §1.6); „Prognose“ bleibt getrennt von Wunsch, Zusage und tatsächlichem Ereignis (Kap. 05 §5.1 Zeile Zeit).
  - Datenbank: `forecast_run`, `forecast_value` (Arbeitsnamen), lesend `forecast_model_revision`, `forecast_dataset_revision`.
  - API: Arbeitsnamen `/v1/forecast/runs` (starten mit Idempotency-Key, listen) und `/v1/forecast/runs/{id}/values` (Liste `{items,next_cursor,as_of}`); Laufstatus über `/v1/operations/{id}`.
  - Oberfläche: UI-19 – Prognoseansicht je Gebiet und Zeitabschnitt mit Horizont, Modellversion, Datenstand; Leerzustand „Keine Daten“, Zustand „geringe Qualität“, Fehlerzustand bei fehlgeschlagenem Lauf mit Grund, fehlendes Recht (403); Tabellenalternative zu Diagrammen und Kennzeichnung nicht nur über Farbe (Kap. 06 §6.1).
  - Backend (Leseport): Prognose-Leseport gemäß Vertrag aus WP-M9a-01 für die Abnehmer WP-M9a-12, WP-M7d-32, WP-M9b-22 und WP-M9b-48; liefert ausschließlich aggregierte Werte je Gebiet ohne Personenbezug.
  - Adapter/Worker: Inferenz im `workers/forecast` (Arbeitsname).
  - Ereignisse/Outbox: `forecast.run.completed` (Arbeitsname).
- **Detailverträge vorab:** Schema Prognosewert; Gebietsbezug (Bediengebiet oder Zelle), Zeitraster und Horizont (Offener Punkt 5); Zeitzonenbasis `reporting_timezone`.
- **Akzeptanzkriterien:**
  1. Die Ausgabe eines Laufs enthält für jedes angefragte Gebiet einen Prognosewert und den zugehörigen Zeithorizont (AI-001).
  2. Die Prognose nutzt nachweislich historische Nachfrage, Datum und Wochentag: Auf dem Referenzdatensatz mit Wochentagsmuster unterscheidet sich die Prognose für Werktag und Wochenende in der erwarteten Richtung (AI-001).
  3. Weitere Ereignissignale fließen nur ein, wenn sie für den Mandanten freigegeben sind; ohne Freigabe bleibt das Ergebnis unverändert (AI-001).
  4. Jeder Prognosewert trägt Modellversion, Unsicherheit und gegebenenfalls Fallbackgrund.
  5. Wiederholter Start mit gleichem Idempotency-Key erzeugt denselben Lauf.
  6. Freigabe und Rücknahme der Freigabe weiterer Ereignissignale sind mit Akteur und Zeit im Audit nachvollziehbar; der Lauf weist aus, ob freigegebene Signale verwendet wurden (AI-001).
  7. Der manuelle Laufstart ist nur mit dem dafür vorgesehenen Recht möglich; ohne Recht 403, kein Lauf entsteht.
- **Tests:** positiv: Lauf auf Referenzhistorie, Vergleich mit Orakel. negativ: Gebiet ohne Historie liefert „nicht verfügbar“ statt eines Scheinwerts; fremdes Gebiet ergibt 404; gleicher Idempotency-Key mit anderem Request ergibt `409 IDEMPOTENCY_CONFLICT` (Prüfmuster PH-AT-011); Start ohne Recht 403; nicht freigegebenes Ereignissignal im Request wird ignoriert bzw. abgelehnt und verändert das Ergebnis nicht; Lauf über DST-Umstellung ordnet Zeitabschnitte gemäß `reporting_timezone` eindeutig zu. konkurrierend (Datentrennung/Idempotenz): parallele Läufe zweier Mandanten liefern nur eigene Gebiete; zwei gleichzeitige Starts mit gleichem Idempotency-Key erzeugen genau einen Lauf.
- **Abhängigkeiten:** intern: WP-M9a-04, WP-M9a-05.
  - extern: WP-M2-04 (Bediengebiete (`/v1/service-areas`) als Gebietsdimension)
  - extern: WP-M8c-19 (Soll/Ist/Prognose-Vergleich im Statistikbereich als Abnehmer)
- **Entscheidungen:** D-05 (Pilotregion bestimmt Gebietszuschnitt) – blockiert nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Ferien, Veranstaltungen, Wetter (WP-M9a-07); Gütemessung (WP-M9a-10); Vorschläge (WP-M9a-12); Ankunftsprognosen/ETA (M8b).

### WP-M9a-07 – Zusatzsignale Ferien, Veranstaltungen, Wetter und gekennzeichneter Basisforecast

- **Ziel:** Auf Wunsch berücksichtigt die Prognose Ferien, Veranstaltungen und geeignete Wetterdaten; fehlt eine Zusatzquelle, entsteht ein ausdrücklich gekennzeichneter Basisforecast.
- **Anforderungen:** `LH-6.15-AI-004`
- **Liefergegenstände:**
  - Backend: Signalquellenregister mit Aktivierung je Mandant („auf Wunsch“), Herkunft, Lizenz, Quellenzeit und Staleness; Merkmalsbildung für Ferien, Veranstaltungen, Wetter; Kennzeichen am Prognoselauf und an jedem Wert, welche Signale verwendet wurden, bzw. Kennzeichen „Basisforecast“ mit Grund (Quelle nicht aktiviert, nicht erreichbar, veraltet).
  - Datenbank: `forecast_signal_source` (Arbeitsname), Erweiterung `forecast_run` um verwendete Signale.
  - Adapter/Worker: Adapter für Feiertagskalender (Wiederverwendung des Kalenders aus WP-M8d-08 bzw. WP-M7d-11); Schulferien sind in keinem Artefakt als Datenquelle benannt und werden als eigene Signalquelle mit manueller Pflege bzw. Datei geführt (Offener Punkt 6), Veranstaltungsimport (manuelle Pflege bzw. Datei), Wetteradapter hinter austauschbarer Schnittstelle; Aufrufe mit Timeout außerhalb von SQL-Transaktionen.
  - API: Arbeitsname `/v1/forecast/signal-sources` (aktivieren/deaktivieren mit `If-Match`, Status; Aktivierung nur mit dem dafür vorgesehenen Recht und mit Eintrag im `audit_event`); Kennzeichen in `/v1/forecast/runs/{id}/values`.
  - Oberfläche: UI-19 – sichtbares Kennzeichen „Basisforecast“ als Text, nicht nur als Farbe (Kap. 06 §6.1).
- **Detailverträge vorab:** Adapterverträge je Signalquelle; Auswahl der Wetter- und Veranstaltungsquelle (Offener Punkt 6); Staleness-Grenze.
- **Akzeptanzkriterien:**
  1. Ohne Zusatzquelle entsteht ein Forecast, der im API-Ergebnis und in UI-19 als Basisforecast gekennzeichnet ist (AI-004).
  2. Mit aktivierter Ferienquelle unterscheidet sich die Prognose für Ferientage auf dem Referenzdatensatz vom Basisforecast, und der Lauf weist die verwendete Quelle aus (AI-004).
  3. Fällt eine aktivierte Quelle zur Laufzeit aus oder ist sie veraltet, wird der Lauf als Basisforecast mit Grund abgeschlossen statt abzubrechen (AI-004).
  4. Ferien, Veranstaltungen und Wetter sind je Mandant einzeln zu- und abschaltbar („auf Wunsch“); mit aktivierter Veranstaltungs- bzw. Wetterquelle weist der Lauf die jeweils verwendete Quelle samt Quellenzeit aus (AI-004).
  5. Aktivierung und Deaktivierung einer Quelle sind mit Akteur und Zeit im Audit nachvollziehbar; ohne Recht 403.
  6. Sind nur einzelne aktivierte Quellen ausgefallen, weist der Lauf aus, welche Signale verwendet wurden und welche fehlen (kein stilles Teil-Ergebnis).
- **Tests:** positiv: Lauf mit Ferien-, Veranstaltungs- und Wettersignal. negativ: Quelle deaktiviert; Quelle mit Timeout; veraltete Quelle; Wetterdaten für fremdes Gebiet werden nicht verwendet; Aktivierung ohne Recht 403; Signalquelle eines fremden Mandanten 404; fehlerhafte Veranstaltungsdatei wird mit Feldfehler abgelehnt. konkurrierend: gleichzeitige Deaktivierung einer Quelle und Laufstart – der Lauf nutzt die Quelle vollständig oder gar nicht und weist dies aus.
- **Abhängigkeiten:** intern: WP-M9a-06.
  - extern: WP-M1-07, WP-M1-08, WP-M1-46, WP-M1-36 (Rollen-/Objektberechtigung, Audit, Secret-Verwaltung für Adapterzugänge)
  - extern: WP-M8d-08 (Feiertagskalender und Zeitzonenregeln (WP-M8d-08))
  - extern: WP-M7d-11 (Feiertage und Sonderregelungen nach Ort und Datum (WP-M7d-11; gleiche Kalenderquelle))
- **Entscheidungen:** keine für den Bau (Sandbox-Quellen); Auswahl und Lizenz produktiver Wetter-/Veranstaltungsquellen blockiert nur deren Produktivschaltung (Offener Punkt 6).
- **Größe:** M
- **Nicht im Umfang:** Echtzeit-Verkehrslage und Fahrplandaten (`timetable_feed`, `realtime_observation`, M6); Feiertagskennzeichnung in Berichten (M8c).

### WP-M9a-08 – Prognose als reiner Hinweis: harte Regeln nicht umgehbar

- **Ziel:** Prognosen wirken nur als Hinweis oder weiches Ziel; keine Prognose, auch keine falsche, kann eine Zusage erzeugen, die harte Kapazitäts-, Sicherheits- oder Betriebsregeln verletzt.
- **Anforderungen:** `LH-6.15-AI-003`
- **Liefergegenstände:**
  - Backend: Architekturregel und technischer Nachweis, dass der Hold-/Confirm-Pfad im Modul `booking-dispatch` keine Prognosewerte als Eingang für Zulässigkeitsprüfungen verwendet: Prognose-Leseport liefert ausschließlich Hinweise an Planungsansichten und an die weiche Zielfunktion; Ressourcenvektor, Safety-Block, Pausen, Lade-/Reichweitenreserven und zugesagte Zeitfenster bleiben harte Constraints („erst Unzulässigkeit (muss null sein)“, Kap. 01 §1.4). Modulabhängigkeitstest (ArchUnit o. ä.) verbietet Zugriff des Forecast-Teilbereichs auf Schreiboperationen von `booking-dispatch`, `fleet-safety`, `staff-time`.
  - Datenbank: keine neuen Tabellen; Prüfung der Rechte aus WP-M9a-02 (Prognoserolle ohne DML auf `booking`, `capacity_hold`, `vehicle_run`, `resource_release`, `staff_shift`).
  - Konfigurationsparameter: harte Grenzen bleiben wirksam unabhängig von der Prognose: `min_capacity_reserve`, `max_wait_min`, `max_detour_pct`, `min_battery_soc_pct`, `min_rest_hours`, `max_daily_work_minutes`.
- **Detailverträge vorab:** Liste der Stellen, an denen Prognosewerte gelesen werden dürfen; Vertrag des Prognose-Leseports (nur lesend, mit Fallback „keine Prognose“).
- **Akzeptanzkriterien:**
  1. Test mit falscher Prognose (stark überhöht und stark untertrieben, Fixture aus WP-M9a-03): keine Buchungszusage überschreitet den Ressourcenvektor, verletzt einen Sicherheitsblock, eine Pausen-/Ruhezeitregel oder ein zugesagtes Zeitfenster (AI-003).
  2. Die Menge der zulässigen Zusagen ist mit und ohne Prognose identisch; die Prognose verändert höchstens die Reihenfolge weicher Ziele bzw. die Hinweise (AI-003).
  3. Der Modulabhängigkeitstest schlägt fehl, sobald der Forecast-Teilbereich eine Schreiboperation eines Live-Moduls aufruft (AI-003).
  4. Der Nachweis gilt für alle drei Regelklassen der Anforderung getrennt: Kapazität (Ressourcenvektor, `min_capacity_reserve`), Sicherheit (Fahrzeugsperre, `min_battery_soc_pct`) und Betrieb (`max_wait_min`, `max_detour_pct`, `min_rest_hours`, `max_daily_work_minutes`, Betriebszeiten/Bediengebiet) – je Klasse mindestens ein Negativtest (AI-003).
  5. Der Prognose-Leseport ist die einzige Stelle, über die andere Module Prognosewerte beziehen; die Liste der lesenden Stellen ist dokumentiert und durch den Modulabhängigkeitstest abgesichert.
- **Tests:** positiv: Buchung mit plausibler Prognose verläuft unverändert. negativ: fehlende, veraltete und syntaktisch ungültige Prognose (NaN, negativer Wert) führen zu „keine Prognose“ und nie zu einer Zusage; überhöhte Prognose bei letztem freien Platz führt nicht zu Überbuchung; untertriebene Prognose hebt keine Fahrzeugsperre auf; Prognose für gesperrtes Fahrzeug erzeugt keinen Einsatz. konkurrierend (Kapazität/Sicherheit): 100 parallele Hold-Anfragen auf den letzten Platz bei extremer Prognose – genau ein Hold, Rest 409 (Prüfmuster PH-AT-004 mit Prognose als Störgröße); Fahrzeugsperre gleichzeitig mit prognosebasiertem Hinweis – keine neue Zusage auf dem gesperrten Fahrzeug (Prüfmuster PH-AT-020).
- **Abhängigkeiten:** intern: WP-M9a-06.
  - extern: WP-M2-22, WP-M2-23, WP-M2-20 (Hold-/Confirm-Kern mit Kapazitätslock und Solver-Zielfunktion)
  - extern: WP-M3-08, WP-M3-09 (Sicherheitsblock und Fahrzeugfreigabe)
  - extern: WP-M4-13 (Arbeitszeit-/Ruhezeitregeln (`min_rest_hours`, `max_daily_work_minutes`) als harte Betriebsregeln)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** prognosegestützte Fahrzeugpositionierung in der Live-Disposition (`LH-6.5-OPT-020`, WP-M9b-22; nutzt den Leseport dieses WPs); Einfluss der Prognose auf die Optimierungsgewichte der Live-Disposition (in dieser Einheit nicht gefordert, Offener Punkt 7); ETA-Sicherheitsgrenzen (M8b).

### WP-M9a-09 – Trennung von Training und Live-Disposition mit sicherem Rückfall

- **Ziel:** Modelltraining und Prognosedienst laufen logisch getrennt von der Live-Disposition; fällt der KI-Dienst aus, funktionieren Buchung und Disposition unverändert weiter.
- **Anforderungen:** `LH-6.15-AI-007`
- **Liefergegenstände:**
  - Backend: Prognose-Leseport mit Timeout, Circuit Breaker und definiertem Rückfall „keine Prognose“ (Fallbackgrund wird ausgewiesen, Kap. 01 §1.6); keine synchrone Abhängigkeit des Angebots-, Hold- und Confirm-Pfads vom Prognosedienst; Planungsansichten zeigen bei Ausfall den letzten gültigen Stand mit Datenstand oder „nicht verfügbar“.
  - Adapter/Worker: `workers/forecast` (Arbeitsname) als eigene Deployment-Einheit mit eigener Queue, eigenen Ressourcenlimits und eigenem Datenbankzugang; Trainingslast läuft nicht auf dem primären Buchungsknoten (Lesereplikat/Projektionen, Kap. 01 §1.1).
  - Oberfläche: UI-19 und UI-12 – Zustand „Prognose nicht verfügbar“ mit Datenstand; UI-07 unverändert bedienbar.
  - Ereignisse/Outbox: Betreiberwarnung bei Ausfall über das Alert-Center (M8d); Telemetrie über OpenTelemetry ohne Fahrgastdaten in Metriklabels.
- **Detailverträge vorab:** Timeout- und Breaker-Werte (Fixturewerte, keine Vorgabe im Pflichtenheft); Rückfallverhalten je Abnehmer; Deployment-Trennung.
- **Akzeptanzkriterien:**
  1. Bei abgeschaltetem KI-Dienst (Worker gestoppt, Queue nicht erreichbar, Prognosetabellen leer) lassen sich Fahrten regulär suchen, halten, bestätigen und disponieren (AI-007; AT-21).
  2. Angebots- und Buchungslatenz bleiben bei abgeschaltetem und bei überlastetem KI-Dienst innerhalb der Zielwerte aus Kap. 01 §1.8 (Standard-API p95 ≤ 1 s, Angebote p95 ≤ 5 s; vorläufige Zielwerte unter D-05) (AI-007).
  3. Ein laufendes Training verändert keine Live-Tabelle und belegt keine Verbindung des Buchungspools (AI-007).
  4. Nach Wiederanlauf des KI-Dienstes werden Prognosen ohne manuellen Eingriff wieder geliefert; der Ausfall ist als Ereignis mit Zeitraum nachvollziehbar.
  5. Jeder Abnehmer des Leseports (UI-19, UI-12, UI-07, WP-M9a-12) zeigt bei Ausfall „Prognose nicht verfügbar“ bzw. den letzten gültigen Stand mit Datenstand; ein veralteter Wert wird nie ohne Datenstand als aktuell dargestellt (AI-007).
  6. Fällt der KI-Dienst während eines laufenden Prognose- oder Trainingsauftrags aus, entsteht kein teilweise geschriebener Lauf; der Auftrag wird nach Wiederanlauf genau einmal fortgesetzt oder als fehlgeschlagen ausgewiesen.
- **Tests:** positiv: Normalbetrieb mit Prognose. negativ: Worker gestoppt; Prognosedienst antwortet nicht (Timeout); Prognosedienst liefert Fehler; Modellartefakt beschädigt. konkurrierend (Kapazität): Buchungslast gemäß Lastprofil parallel zu Training – keine Verletzung der Buchungs-SLOs, kein Lock-Konflikt. Besteht AT-21.
- **Abhängigkeiten:** intern: WP-M9a-06, WP-M9a-08.
  - extern: WP-M2-17, WP-M2-22, WP-M2-23 (Buchungs- und Dispositionspfad)
  - extern: WP-M5-10, WP-M5-11 (Lastprofil und SLO-Messung)
  - extern: WP-M8d-22 (Alert-Center für die Betreiberwarnung)
- **Entscheidungen:** D-05 (verbindliche SLO-Werte) – blockiert nur die Abnahme gegen endgültige Zielwerte.
- **Größe:** M
- **Nicht im Umfang:** Stresstest und Ressourcenentkopplung als nichtfunktionale Abnahme (`LH-6.19-NFR-019`, WP-M9b-54); Fallback der prognosegestützten Positionierung (WP-M9b-22); Rückfalllogik der ETA-Modelle (`eta_fallback_policy`, M8b); Notbetrieb der Gesamtplattform (M3/M5).

### WP-M9a-10 – Prognosefehler-Dashboard mit naivem Referenzmodell

- **Ziel:** Ein Dashboard zeigt den Prognosefehler nach Gebiet und Uhrzeit im Vergleich zu einem naiven Referenzmodell und kennzeichnet schlecht abschneidende Modelle erkennbar.
- **Anforderungen:** `LH-6.15-AI-005`
- **Liefergegenstände:**
  - Backend: Gütedienst, der Prognosewerte mit eingetretener Nachfrage abgleicht, sobald der Zeithorizont abgelaufen und der Datenstand vollständig ist; Berechnung eines naiven Referenzmodells auf derselben DatasetRevision; Fehlermaß je Gebiet und Uhrzeit für Modell und Referenz; Kennzeichen „schlechter als Referenz“ bzw. „unter Güteschwelle“; Unterdrückung kleiner Gruppen nach `kpi_aggregation_min_n`.
  - Datenbank: `forecast_quality_snapshot` (Arbeitsname), lesend `forecast_value`, `aggregate_snapshot`, `metric_definition_revision` (Fehlermaß als versionierte Definition).
  - API: Arbeitsname `/v1/forecast/quality` bzw. Auslieferung über `/v1/analytics/metrics` mit Definition/Version, Filter, Datenstand, Vollständigkeit (Kap. 05 §5.7).
  - Oberfläche: UI-19 – Dashboard „Prognosefehler“ mit Filter Gebiet/Uhrzeit, Vergleich Modell gegen Referenz, textlicher Kennzeichnung schlechter Modelle, Tabellenalternative zur Grafik (Kap. 06 §6.1); Zustände „Keine Daten“, „geringe Qualität“.
  - Kennzahlen: neue Definition für den Nachfrage-Prognosefehler (nicht in `kataloge.csv`; `ETA-MAE`, `ETA-Bias`, `ETA-p90-Abdeckung` betreffen nur Ankunftsprognosen und werden nicht umgedeutet).
- **Detailverträge vorab:** Definition des Fehlermaßes und des naiven Referenzmodells; Schwelle für „schlechtes Ergebnis“ (Offener Punkt 8); Null-Nenner-Verhalten („nicht berechenbar“, Kap. 01 §1.6).
- **Akzeptanzkriterien:**
  1. Das Dashboard zeigt den Prognosefehler getrennt nach Gebiet und Uhrzeit und stellt ihn dem naiven Referenzmodell gegenüber (AI-005).
  2. Ein Modell, das auf dem Referenzdatensatz schlechter als die Referenz bzw. unter der Schwelle liegt, wird im Dashboard und in der API erkennbar gekennzeichnet (AI-005).
  3. Gebiete/Zeitabschnitte unter der Mindestgruppengröße werden unterdrückt; Null-Nenner wird als „nicht berechenbar“ ausgewiesen.
  4. Jede angezeigte Zahl nennt Modellversion, Definitionsversion und Datenstand.
- **Tests:** positiv: gutes Modell ohne Kennzeichen, schlechtes Modell mit Kennzeichen (Fixtures). negativ: Zeitraum ohne eingetretene Nachfrage; unvollständiger Datenstand wird als vorläufig gekennzeichnet; Nutzer ohne Statistikrolle erhält 403; Zelle unter `kpi_aggregation_min_n` lässt sich auch durch Differenzbildung benachbarter Gebiets-/Uhrzeitfilter nicht offenlegen; Null-Nenner „nicht berechenbar“ (Prüfmuster PH-AT-021); Güte eines fremden Mandanten 404. konkurrierend (Datentrennung): parallele Güteberechnung zweier Mandanten erzeugt nur eigene Snapshots; zwei parallele Berechnungen desselben Zeitraums erzeugen genau einen Snapshot je Modell, Gebiet und Uhrzeit.
- **Abhängigkeiten:** intern: WP-M9a-06.
  - extern: WP-M4-46, WP-M4-45, WP-M4-47 (Kennzahlregister und Faktenbasis der eingetretenen Nachfrage)
  - extern: WP-M8c-04, WP-M8c-32, WP-M8c-11 (versionierte Definitionen, Dashboardrahmen UI-19, Kleingruppenschutz)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** automatische Alarmierung (WP-M9a-11); automatischer Modellwechsel; ETA-Güteüberwachung (M8b); getrennte Darstellung von Prognosequalität und Simulationspotenzial gegenüber Ist-KPIs (`LH-6.14-ANA-012`, WP-M9b-41); gesonderte Abnahme gegen eine Baseline (`LH-6.20-QAT-011`, WP-M9b-55).

### WP-M9a-11 – Modellüberwachung: Drift, Datenqualität, Fehlerraten mit Hinweis an zuständige Rolle

- **Ziel:** Der Modellbetrieb überwacht Drift, Datenqualität und Fehlerraten; eine definierte Anomalie löst einen Hinweis an die verantwortliche Rolle aus.
- **Anforderungen:** `LH-6.15-AI-008`
- **Liefergegenstände:**
  - Backend: Überwachungsjob mit drei Prüfklassen: Drift (Verteilung der Eingangsmerkmale bzw. des Fehlers gegenüber dem Trainingszeitraum), Datenqualität (Vollständigkeit, Aktualität, Ausreißer der DatasetRevision und der Signalquellen), Fehlerraten (technische Fehler von Lauf und Worker sowie Prognosefehler aus WP-M9a-10); Anomaliedefinition als versionierte Regel; Zuordnung einer verantwortlichen Rolle je Modell.
  - Datenbank: Erweiterung `forecast_quality_snapshot` (Arbeitsname); Hinweise über `alert_policy_revision`, `business_alert`, `alert_acknowledgement` (Dedupe `(policy,event,recipient)`, Kap. 04 §4.4).
  - Ereignisse/Outbox: `forecast.anomaly.detected` (Arbeitsname) über `outbox` an das Alert-Center.
  - Oberfläche: UI-19 – Überwachungsstatus je Modell; Hinweis erscheint im Alert-Center aus M8d mit Quittierung.
  - Konfigurationsparameter: `alert_escalation_delay_min`, `notification_quiet_hours` (bestehende Eskalationsregeln); Drift- und Qualitätsschwellen als Arbeitsnamen-Parameter.
  - Kennzahlen: technische Metriken Prometheus-kompatibel ohne Personen-/Ortsbezug in Labels.
- **Detailverträge vorab:** Definition der überwachten Anomalien und Schwellen (Offener Punkt 8); verantwortliche Rolle (Offener Punkt 9); Alarmregelvertrag mit M8d.
- **Akzeptanzkriterien:**
  1. Die im Referenzdatensatz eingebaute Drift löst genau einen Hinweis an die für das Modell hinterlegte verantwortliche Rolle aus (AI-008).
  2. Eine definierte Datenqualitätsanomalie (Datenlücke) und eine definierte Fehlerratenanomalie (wiederholt fehlgeschlagene Läufe) lösen jeweils einen Hinweis an die verantwortliche Rolle aus (AI-008).
  3. Wiederholte Erkennung derselben Anomalie erzeugt keinen zweiten Hinweis an denselben Empfänger; Quittierung ist mit Akteur und Zeit gespeichert.
  4. Ohne Anomalie entsteht kein Hinweis.
  5. Der Hinweis nennt Modellversion, Prüfklasse, gemessenen Wert, Schwelle und Datenstand und enthält keine Personen- oder Einzelbuchungsdaten.
  6. Fällt der Überwachungsjob selbst aus oder liegen keine Daten vor, wird der Überwachungsstatus in UI-19 als „nicht geprüft“ ausgewiesen und nicht als „unauffällig“.
- **Tests:** positiv: je Prüfklasse eine ausgelöste Anomalie. negativ: Werte knapp unter der Schwelle; Modell ohne hinterlegte Rolle kann nicht aktiviert werden; Nutzer ohne Rolle sieht den Hinweis nicht. konkurrierend: zwei parallele Überwachungsläufe erzeugen einen Hinweis (Dedupe).
- **Abhängigkeiten:** intern: WP-M9a-05, WP-M9a-10.
  - extern: WP-M8d-22, WP-M8d-20, WP-M8d-23, WP-M8d-16 (Alert-Center, Regel-Scope, Deduplikation, Eskalationskette)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Drift-Überwachung der Routinggrundlagen und ETA-Güte (M8b); automatischer Rollout-Stopp (für Nachfragemodelle nicht gefordert).

### WP-M9a-12 – Vorschläge für Schichtplanung und Leerfahrzeugstandorte mit Begründung und Effektschätzung

- **Ziel:** Aus Prognosen entstehen unverbindliche Vorschläge für Schichtplanung und für Standorte leerer Fahrzeuge, jeweils mit erklärbarer Begründung und Effektschätzung.
- **Anforderungen:** `LH-6.15-AI-006`
- **Liefergegenstände:**
  - Backend: Vorschlagsdienst im Modul `analytics` (Teilbereich Forecast) mit zwei Vorschlagsarten: (a) Personal-/Schichtbedarf je Gebiet und Zeitabschnitt, bereitgestellt als Eingang für den Bedarfsvorschlag aus M7d (WP-M7d-32); (b) Bereitstellungsstandorte für leere Fahrzeuge je Zeitabschnitt als Hinweis für die Leitstelle. Jeder Vorschlag trägt Begründung (verwendete Prognose, Modellversion, Zeithorizont, maßgebliche Einflussgrößen), Effektschätzung (erwartete Wirkung mit Unsicherheit), Kennzeichen „unverbindlich“ und Gültigkeit. Vorschläge schreiben weder Schichten noch Fahrzeugumläufe.
  - Datenbank: `forecast_suggestion` (Arbeitsname; Art, Bezug, Begründung, Effektschätzung, Status vorgeschlagen/übernommen/verworfen/abgelaufen), lesend `forecast_value`, `staff_shift`, `vehicle_run`, `roster_revision`.
  - API: Arbeitsname `/v1/forecast/suggestions` (listen, verwerfen); Übernahme von Schichtvorschlägen ausschließlich über `/v1/rosters` (M7d), Standorthinweise lesend für `/v1/operator/vehicles`.
  - Oberfläche: UI-19 – Vorschlagsliste mit Begründung und Effektschätzung; UI-12 – Vorschlagsebene im Dienstplan (Anzeige liegt bei M7d); UI-07 – Standorthinweis als nicht bindende Empfehlung.
  - Ereignisse/Outbox: Vorschlagsereignis (Arbeitsname `forecast.suggestion.created`) für M7d über `outbox`/`inbox`.
  - Kennzahlen: `Schicht-Abdeckungsquote`, `Leerkilometerquote`, `Kapazitätsablehnungen`, `Personalmangelablehnungen` als Bezugsgrößen der Effektschätzung.
- **Detailverträge vorab:** Schema Vorschlag; Schnittstelle zu WP-M7d-32; Methode und Darstellung der Effektschätzung (Offener Punkt 10); Regel, dass Vorschläge keine personenbezogene Leistungsbewertung enthalten (Kap. 05 §5.7).
- **Akzeptanzkriterien:**
  1. Jeder Schichtvorschlag und jeder Standortvorschlag enthält eine erklärbare Begründung und eine Effektschätzung; ein Vorschlag ohne eines von beiden wird nicht ausgeliefert (AI-006).
  2. Erhöhte Nachfrageprognose auf dem Referenzdatensatz erzeugt einen Vorschlag mit höherem Personalbedarf, der als unverbindlich markiert ist (AI-006; AT-55).
  3. Ein Vorschlag erzeugt ohne Planeraktion weder Schicht, Angebot, Mitteilung noch Fahrzeugverlegung; Publikation erfolgt erst nach Freigabe im Dienstplan (AT-55).
  4. Vorschläge enthalten keine Aussage über einzelne Beschäftigte.
  5. Ein Standortvorschlag für leere Fahrzeuge nennt Gebiet/Standort, Zeitabschnitt, Begründung und Effektschätzung und ist in UI-07 als nicht bindende Empfehlung textlich gekennzeichnet; er bezieht sich nur auf Fahrzeuge und Gebiete im Scope des Nutzers (AI-006).
  6. Verwerfen eines Vorschlags und die Rückmeldung „übernommen“ aus M7d sind mit Akteur und Zeit gespeichert; der Statuswechsel ist idempotent.
- **Tests:** positiv: Vorschlag erzeugen, in UI-19 anzeigen, an M7d übergeben. negativ: fehlende Prognose führt zu „kein Vorschlag“; Vorschlag, der eine Ruhezeit- oder Qualifikationsregel verletzen würde, wird bei Übernahme von M7d abgelehnt (harte Regeln bleiben wirksam, WP-M9a-08); abgelaufener Vorschlag ist nicht übernehmbar; Vorschlag auf Basis eines Basisforecasts bzw. eines als schlecht gekennzeichneten Modells trägt diesen Hinweis in der Begründung; Zugriff ohne Planerrolle 403, fremder Mandant 404. konkurrierend: gleichzeitiges Verwerfen und Übernehmen desselben Vorschlags – genau ein Endzustand; doppelt zugestelltes `forecast.suggestion.created` erzeugt bei M7d genau einen Vorschlag (Inbox-Dedupe). Ermöglicht AT-55 gemeinsam mit M7d und WP-M9b-57.
- **Abhängigkeiten:** intern: WP-M9a-06, WP-M9a-08.
  - extern: WP-M7d-32, WP-M7d-20 (Bedarfsvorschlag im Dienstplan (WP-M7d-32), Übernahme und Publikation)
  - extern: WP-M4-03, WP-M4-46 (Schichtmodell mit Personalbedarf, Kennzahlen)
  - extern: WP-M3-36, WP-M2-16 (Leitstellenansicht Fahrzeuge und Umlaufdaten (Leseport))
- **Entscheidungen:** D-10 (Mitbestimmung bei personalbezogenen Auswertungen) – blockiert nur Produktivschaltung der Schichtvorschläge, nicht den Bau.
- **Größe:** M
- **Nicht im Umfang:** automatische Umsetzung von Vorschlägen; Wirkung der Prognose auf die Live-Positionierung (`LH-6.5-OPT-020`, WP-M9b-22); KI-gestützte Personalbedarfsempfehlung aus geplanten Verkehrsdiensten (`LH-6.23-SCH-034`, WP-M9b-48); Kapazitätsempfehlung bei wiederholten Ablehnungen (M8d); Ersatzfahrzeugvorschläge (M8d).

### WP-M9a-13 – Auditierbare Datenminimierung der Trainingspipeline

- **Ziel:** Die Trainingspipeline verwendet personenbezogene Daten nach Möglichkeit nur aggregiert oder pseudonymisiert und lässt sich auf Datenminimierung auditieren. KANN-Funktion: Bau nur nach ausdrücklicher Aufnahme ins Release.
- **Anforderungen:** `LH-6.15-AI-009`
- **Liefergegenstände:**
  - Backend: Felderinventar der Trainingspipeline (welches Feld, aus welcher Quelle, in welcher Form: aggregiert, pseudonymisiert, roh, mit Zweck); Pipeline-Protokoll je Trainingsauftrag (verwendete Felder, Aggregationsstufe, DatasetRevision); Prüfregel, die nicht inventarisierte Felder im Datensatz ablehnt.
  - Datenbank: Erweiterung `forecast_dataset_revision` (Arbeitsname) um Feldinventar-Version; `audit_event`.
  - API: Auditbericht als Export über `/v1/reports/jobs` (asynchron) bzw. Arbeitsname `/v1/forecast/models/{id}/data-audit`.
  - Oberfläche: UI-20 – Einsicht in den Auditbericht für die Datenschutzrolle.
- **Detailverträge vorab:** Feldinventar-Schema; Eintrag im Verarbeitungsregister (M8b); Pseudonymisierungsverfahren aus M8b.
- **Akzeptanzkriterien:**
  1. Für jede Modellversion ist ein Auditbericht abrufbar, der alle im Training verwendeten Felder, ihre Form (aggregiert/pseudonymisiert/roh) und die DatasetRevision ausweist (AI-009).
  2. Ein Trainingsdatensatz mit einem nicht inventarisierten oder direkt identifizierenden Feld wird abgelehnt und protokolliert (AI-009).
  3. Der Auditbericht selbst enthält keine personenbezogenen Einzelwerte.
  4. Für jedes roh (nicht aggregiert/pseudonymisiert) verwendete personenbezogene Feld weist der Bericht die Begründung aus, warum Aggregation oder Pseudonymisierung nicht möglich war („nach Möglichkeit“, AI-009).
  5. Abruf des Auditberichts wird selbst im `audit_event` protokolliert.
- **Tests:** positiv: Bericht für das Referenzmodell. negativ: eingeschleustes Klarfeld (Fixture) wird erkannt; Zugriff ohne Datenschutzrolle ergibt 403.
- **Abhängigkeiten:** intern: WP-M9a-04, WP-M9a-05.
  - extern: WP-M8b-22, WP-M8b-23, WP-M8b-06 (Verarbeitungsregister, Aufbewahrungsklassen, Pseudonymisierung)
- **Entscheidungen:** D-12 (Aufnahme der KANN-Funktion ins Release) – blockiert den Bau. D-11 (Löschfristen) – blockiert nur Produktivschaltung.
- **Größe:** S
- **Nicht im Umfang:** Auskunfts- und Löschprozess (M8b); DSFA (organisatorisch, Auftraggeber).

### WP-M9a-14 – Simulationsumgebung: eigene Queue, Worker, Ressourcenlimits, Leseport ohne Live-Schreibrecht

- **Ziel:** Simulationen laufen in einer vom Produktionsbetrieb getrennten Rechenumgebung mit eigener Queue, eigenen Workern und Ressourcenlimits; technisch ist kein Schreibzugriff auf Live-Buchungen möglich.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: `workers/simulation` (Arbeitsname, Python, OR-Tools; Wiederverwendung des Solverkerns aus `workers/routing` als Bibliothek in fester Version) als eigene Deployment-Einheit; eigene RabbitMQ-Quorum-Queue; CPU-/Speicher-/Laufzeitlimit je Lauf und Parallelitätslimit je Mandant; Abbruch bei Limitüberschreitung mit Status; eigene Routinginstanz oder ratenbegrenzter Zugriff, damit die Live-Routinglast nicht steigt.
  - Backend: Auftragsannahme über persistierte Operation mit Lease/Fencing (Kap. 01 §1.5); Simulations-Leseport auf eingefrorene Datensätze; Quoten nach Mandant (Kap. 05 §5.1, 429 mit Retry-After).
  - Datenbank: Nutzung der Rolle und des Schemas aus WP-M9a-02; eigener Verbindungspool, getrennt vom Buchungspool.
  - Ereignisse/Outbox: `simulation.run.completed` (Arbeitsname); Outbox-Ereignisse der Simulation tragen einen eigenen Typraum und werden von keinem Live-Consumer verarbeitet.
  - Kennzahlen: technische Metriken (Warteschlangenlänge, Laufzeit, Limitabbrüche) Prometheus-kompatibel.
- **Detailverträge vorab:** Ressourcenlimits und Parallelität (Fixturewerte, Offener Punkt 11); Deployment-Topologie; Vertrag der Solverbibliothek mit Versionskennung.
- **Akzeptanzkriterien:**
  1. Ein Simulationsworker kann keine Zeile in Live-Tabellen schreiben (Rechtefehler) und kein Ereignis in einen Live-Consumer einspeisen.
  2. Ein Lauf, der sein Ressourcenlimit überschreitet, wird beendet und als „Ressourcenlimit überschritten“ ausgewiesen; andere Mandanten und die Live-Buchung sind nicht beeinträchtigt.
  3. Während einer großen Simulation bleiben die Buchungs-SLOs aus Kap. 01 §1.8 gültig (Vorbereitung AT-22).
  4. Nach Workerneustart wird ein Lauf genau einmal fortgesetzt oder als fehlgeschlagen markiert.
  5. Der Simulationsworker besitzt weder Secrets noch Netzfreigaben für PSP-, Partner-, Benachrichtigungs- oder Telematikadapter; ein Aufrufversuch scheitert an der Netzrichtlinie.
  6. Ein Worker mit veraltetem Lease (Fencing-Token) kann kein Ergebnis mehr schreiben.
- **Tests:** positiv: Minimal-Lauf durch die Queue. negativ: Schreibversuch auf `booking`; Limitüberschreitung; Queueausfall beeinträchtigt die Buchung nicht; Überschreitung der Mandantenquote ergibt 429 mit Retry-After; Aufruf eines externen Adapters aus dem Worker; Ergebnis mit veraltetem Fencing-Token. konkurrierend (Kapazität/Datentrennung): zwei Worker übernehmen denselben Lauf – genau ein Ergebnis; zwei Mandanten starten parallel große Läufe unter Buchungslast – keine SLO-Verletzung, keine Datenvermischung.
- **Abhängigkeiten:** intern: WP-M9a-02.
  - extern: WP-M0-27 (Solver-Spike und Messwerte zur Solverlaufzeit)
  - extern: WP-M1-15, WP-M1-14, WP-M1-02 (Operation-Ressource mit Lease/Fencing, Outbox/Inbox, Rate Limits)
  - extern: WP-M2-17, WP-M2-10, WP-M2-16 (Solver- und Routingkern als wiederverwendbare Bibliothek)
  - extern: WP-M5-10, WP-M5-11 (Lastprofil und SLO-Messung)
- **Entscheidungen:** D-05 (Hosting, Budget, SLO) und D-09 (Routinganbieter, Lizenz für Simulationsnutzung) – blockieren nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** fachliche Simulationslogik (WP-M9a-17); getrenntes Warehouse („später“, Kap. 01 §1.1); Stresstest-Abnahme der Ressourcenentkopplung (`LH-6.19-NFR-019`, WP-M9b-54, setzt dieses WP voraus).

### WP-M9a-15 – Szenario aus historischer Nachfrage oder synthetischen Buchungen, getrennt von Produktion

- **Ziel:** Nutzer erzeugen Szenarien wahlweise aus historischer Nachfrage oder aus synthetischen Buchungen; ein Szenario ist vollständig vom Produktionsbetrieb getrennt.
- **Anforderungen:** `LH-6.16-SIM-001`
- **Liefergegenstände:**
  - Backend: Aggregat Scenario (Kap. 01 §1.3) im Modul `analytics`: Anlage mit Nachfragequelle „historisch“ (Kopie eines Zeitraums aus einer DatasetRevision in den Simulationsbereich, ohne Fahrgastkontakte) oder „synthetisch“ (erzeugte Buchungen mit Seed und Erzeugungsregel); Szenario erhält eigene Identität, Ersteller, Zweck, Datenstand; Daten liegen ausschließlich im Simulationsbereich; simulierte Buchungen sind eigene Objekte und keine `booking`-Zeilen.
  - Datenbank: `simulation_scenario`, `simulation_scenario_revision`, `simulation_dataset` (Arbeitsnamen), `audit_event`.
  - API: `/v1/scenarios` (POST mit Idempotency-Key, GET-Liste `{items,next_cursor,as_of}`), Arbeitsname `/v1/scenarios/{id}` (GET mit ETag, PATCH mit `If-Match`).
  - Oberfläche: UI-19 – Szenario anlegen (Quelle wählen, Zeitraum bzw. Erzeugungsregel), dauerhaft sichtbare Kennzeichnung „Simulation – keine Wirkung auf den Betrieb“; Zustand „Live-Schreibversuch“ wird als Fehler mit Erklärung dargestellt (Kap. 06 §6.9).
- **Detailverträge vorab:** OpenAPI `/v1/scenarios`; Schema der Nachfragequelle; Erzeugungsregeln synthetischer Buchungen; Rolle „Planer“ und deren Scope.
- **Akzeptanzkriterien:**
  1. Ein Szenario lässt sich sowohl aus historischer Nachfrage als auch aus synthetischen Buchungen erzeugen (SIM-001).
  2. Anlage, Änderung und Löschung eines Szenarios verändern keine Zeile in `booking`, `capacity_hold`, `journey`, `journey_leg`, `payment_intent`, `vehicle_run`, `staff_shift`; Zeilenanzahl und Revisionen des Produktionsbestands sind vor und nach dem Test identisch (SIM-001; AT-22).
  3. Kein Simulationsobjekt erscheint in Live-Ansichten (UI-04, UI-07), Live-Suchen, Berichten des Ist-Betriebs oder Partnerprojektionen (SIM-001).
  4. Ein Szenario ist nur im eigenen Mandanten sichtbar; fremder Zugriff ergibt 404.
  5. Anlage, Änderung und Löschung eines Szenarios sind mit Akteur, Zeit und Zweck im Audit nachvollziehbar; das Szenario weist Nachfragequelle, Zeitraum bzw. Erzeugungsregel mit Seed und Datenstand aus.
  6. Ein Szenario aus historischer Nachfrage enthält keine Fahrgastkontakte und keine direkten Personenmerkmale; ein Szenario lässt sich nur aus Daten des eigenen Mandanten und im Gebiets-/Depot-Scope des Nutzers erzeugen.
  7. Erzeugte Simulationsobjekte lösen keine Mitteilung an Fahrgäste, Fahrer oder Partner aus (kein Live-Consumer verarbeitet Simulationsereignisse).
- **Tests:** positiv: je ein Szenario aus Historie und aus synthetischen Buchungen. negativ: Versuch, eine Live-Buchung über die Szenario-API zu ändern, ergibt Ablehnung; historischer Zeitraum ohne Daten ergibt verständlichen Leerzustand; Zugriff ohne Planerrolle 403; fremdes Szenario 404; gleicher Idempotency-Key mit anderem Request `409 IDEMPOTENCY_CONFLICT` (Prüfmuster PH-AT-011); DatasetRevision eines fremden Mandanten als Quelle 404; Löschen eines Szenarios mit laufendem Lauf wird abgelehnt. konkurrierend (Datentrennung/Idempotenz): zwei gleichzeitige Anlagen mit gleichem Idempotency-Key erzeugen genau ein Szenario; Szenarioanlage parallel zu laufenden Echt-Buchungen – Produktionsbestand unverändert, keine Sperrkonflikte auf Live-Tabellen. Ermöglicht AT-22.
- **Abhängigkeiten:** intern: WP-M9a-03, WP-M9a-04, WP-M9a-14.
  - extern: WP-M1-07, WP-M1-08, WP-M1-01, WP-M1-46 (Rollen-/Objektberechtigung, Tenantkontext, Audit)
- **Entscheidungen:** D-11 (Aufbewahrung von Szenariodaten) – blockiert nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Löschfristen und automatischer Löschlauf (Lifecycleprozess M8b, Anbindung in WP-M9a-02); Parameter des Szenarios (WP-M9a-16); Ausführung (WP-M9a-17); Finanz-Plan-/Forecast-Szenarien (`forecast_scenario`, M8a).

### WP-M9a-16 – Szenarioparameter: Fahrzeuge, Schichten, Gebiete, Betriebszeiten, Optimierungsgewichte

- **Ziel:** Ein Szenario kann Fahrzeuganzahl, Schichten, Gebiete, Betriebszeiten und Optimierungsgewichte abweichend vom Ist festlegen; die Änderungen wirken ausschließlich im Simulationslauf.
- **Anforderungen:** `LH-6.16-SIM-002`
- **Liefergegenstände:**
  - Backend: Parametersatz als versionierte Szenariorevision (Kopie der wirksamen Ist-Konfiguration als Ausgangspunkt, danach nur im Szenario änderbar); Validierung gegen JSON-Schema aus WP-M9a-01 (Werttyp, Einheit, Min/Max); Szenarioparameter werden nie in `config_revision` geschrieben und nie vom Konfigurationsdienst des Live-Betriebs aufgelöst.
  - Datenbank: `simulation_scenario_revision` (Arbeitsname; Parameter als JSON mit Schema-Version), lesend `config_revision`, `vehicle`, `staff_shift`.
  - API: Arbeitsname `/v1/scenarios/{id}` (PATCH mit `If-Match`; 412 bei veralteter Revision, 428 ohne Header).
  - Oberfläche: UI-19 – Parametereditor mit Ist-Wert und Szenariowert nebeneinander, Einheit und Validierungsfehlern.
  - Konfigurationsparameter: als Ausgangswerte gelesen, nie verändert: `max_wait_min`, `max_detour_pct`, `pooling_allowed`, `min_capacity_reserve`, `pickup_window_min`, `stop_dwell_sec`, `wheelchair_boarding_sec`.
- **Detailverträge vorab:** JSON-Schema Szenarioparameter; Liste der zulässigen Optimierungsgewichte und ihrer Grenzen (Offener Punkt 7); Abbildung „Schichten“ und „Gebiete“ im Szenario (Kopie vs. Referenz auf eingefrorene Revision).
- **Akzeptanzkriterien:**
  1. Fahrzeuganzahl, Schichten, Gebiete, Betriebszeiten und Optimierungsgewichte sind im Szenario einzeln änderbar (SIM-002).
  2. Nach Änderung jedes dieser Parameter sind die wirksame Live-Konfiguration (`/v1/config/effective`), Live-Fahrzeuge, veröffentlichte Dienstpläne und freigegebene Gebietsrevisionen unverändert; ein paralleles Live-Angebot wird mit den Ist-Werten berechnet (SIM-002).
  3. Derselbe Simulationslauf liefert vor und nach einer Parameteränderung unterschiedliche Ergebnisse; die Änderung wirkt also im Lauf (SIM-002).
  4. Ungültige Werte (negative Fahrzeuganzahl, Betriebsende vor Betriebsbeginn, Gewicht außerhalb der Grenzen) werden mit Feldverletzung abgelehnt.
  5. Jede Parameteränderung erzeugt eine neue Szenariorevision mit Akteur und Zeit; ein bereits gestarteter Lauf bleibt an seine eingefrorene Revision gebunden und ändert sich nicht nachträglich (SIM-002).
  6. Der Parametereditor in UI-19 ist per Tastatur bedienbar, nennt Einheit und Ist-Wert je Feld und zeigt Validierungsfehler als Text am Feld (Kap. 06 §6.1).
- **Tests:** positiv: jede Parametergruppe ändern und Lauf starten. negativ: ungültige Werte; Änderung eines eingefrorenen Szenarios; veralteter ETag 412; fehlender `If-Match` 428; Gebiet, Fahrzeug oder Schicht eines fremden Mandanten als Parameter 404; Änderung ohne Planerrolle 403; Schreibversuch des Szenariodienstes auf `config_revision` schlägt mit Rechtefehler fehl. konkurrierend: zwei Planer ändern dasselbe Szenario – genau eine Änderung gewinnt, die andere erhält 412 ohne stilles Überschreiben; gleichzeitige Live-Konfigurationsänderung verändert bestehende Szenariorevisionen nicht.
- **Abhängigkeiten:** intern: WP-M9a-15.
  - extern: WP-M1-19, WP-M1-20, WP-M1-02 (Konfigurationsregister (lesend), ETag/`If-Match`-Konvention)
  - extern: WP-M2-04, WP-M2-03, WP-M3-03, WP-M2-20 (Gebiets- und Dienstrevisionen, Fahrzeugstamm, Optimierungsziele des Solvers)
  - extern: WP-M4-03 (Schichtmodell)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Lade-/Reichweitenparameter (WP-M9a-20); Tarifparameter (WP-M9a-22); Übernahme eines Szenarios in die Live-Konfiguration (vom Pflichtenheft nicht gefordert).

### WP-M9a-17 – Simulationslauf mit Nachfrageerfüllung, Kostenindikatoren, Wartezeit, Fahrzeugkilometern

- **Ziel:** Ein Simulationslauf berechnet für ein Szenario Nachfrageerfüllung, Kostenindikatoren, Wartezeit und Fahrzeugkilometer; jedes Ergebnis trägt Versions- und Annahmendaten.
- **Anforderungen:** `LH-6.16-SIM-003`
- **Liefergegenstände:**
  - Backend: Laufsteuerung (Start mit Idempotency-Key, Status, Abbruch) gemäß Zustandsautomat aus WP-M9a-01; Ergebnisaggregat mit den vier Ergebnisgruppen und einem Block Versions-/Annahmendaten (Szenariorevision, Parametersatz, Algorithmusversion, DatasetRevision, Kostensatzrevision, Routing-/Kartenstand, Seed, Datenstand, bekannte Vereinfachungen).
  - Adapter/Worker: Simulationskern im `workers/simulation` (Arbeitsname): ereignisgesteuerte Abarbeitung der Szenarionachfrage gegen die Szenarioflotte und -schichten mit denselben harten Regeln wie die Live-Disposition (Ressourcenvektor, Zeitfenster, Pausen; Kap. 01 §1.4); Kostenindikatoren aus eingefrorenen Kostensätzen.
  - Datenbank: `simulation_run`, `simulation_result` (Arbeitsname); lesend `rate_revision`, `cost_category` als eingefrorene Kopie im Simulationsbereich. Kein Schreiben nach `economic_event` (Offener Punkt 1).
  - API: `/v1/scenarios/{id}/runs` (POST, GET-Liste), Arbeitsname `/v1/scenarios/{id}/runs/{run_id}` (Status, Ergebnis); Laufstatus über `/v1/operations/{id}`.
  - Oberfläche: UI-19 – Ergebnisansicht mit den vier Ergebnisgruppen, Block „Version und Annahmen“, Laufstatus und Abbruchmöglichkeit; Kennzeichnung „Simulation“.
  - Ereignisse/Outbox: `simulation.run.completed` (Arbeitsname).
  - Kennzahlen: Nachfrageerfüllung über `Angebotsquote` und `Kapazitätsablehnungen`; Kostenindikatoren `Kosten pro Fahrzeugkilometer`, `Kosten pro Fahrgastfahrt`, `Kosten pro Fahrgastkilometer`; Fahrzeugkilometer mit `Leerkilometerquote` und `Produktive Kilometer`; Wartezeit als eigene Simulationsgröße (keine Katalogkennzahl, Offener Punkt 12). Gleiche Definitionsversionen wie im Ist, damit Simulation und Ist vergleichbar sind.
- **Detailverträge vorab:** OpenAPI `/v1/scenarios/{id}/runs`; JSON-Schema Simulationsergebnis; Definition der vier Ergebnisgruppen (Offener Punkt 12); Kostenmodell der Indikatoren (Vollkosten vs. variable Kosten).
- **Akzeptanzkriterien:**
  1. Ein abgeschlossener Lauf liefert Nachfrageerfüllung, Kostenindikatoren, Wartezeit und Fahrzeugkilometer (SIM-003).
  2. Jedes Ergebnis enthält Versionsdaten (Algorithmusversion, Szenariorevision, Datensatzrevision, Definitionsversionen) und Annahmendaten (Parametersatz, Kostensätze, Vereinfachungen); ein Ergebnis ohne diese Angaben wird nicht gespeichert (SIM-003).
  3. Auf dem Referenzszenario stimmen die Ergebnisse mit dem Orakel aus WP-M9a-03 überein.
  4. Während eines großen Laufs ändert sich keine Echt-Buchung, und die Buchungs-SLOs bleiben gültig (AT-22).
  5. Nicht bedienbare Nachfrage wird mit Ablehnungsgrund ausgewiesen, nicht stillschweigend verworfen.
  6. Ein Lauf startet nur auf einer eingefrorenen Szenariorevision; Start und Abbruch sind mit Akteur und Zeit im Audit nachvollziehbar; ein abgebrochener oder fehlgeschlagener Lauf liefert kein als vollständig ausgewiesenes Ergebnis.
  7. Kostenindikatoren sind als Simulationswerte gekennzeichnet, erscheinen in keinem Ist-, Plan- oder Forecast-Finanzbericht und erzeugen keine Zeile in `economic_event` (Offener Punkt 1). Geldwerte als `{amount_minor,currency,exponent}`, kein binary float.
  8. Kennzahlen mit Null-Nenner werden als „nicht berechenbar“ ausgewiesen (Kap. 01 §1.6); UI-19 zeigt Laufstatus, Fehlergrund und „Ressourcenlimit überschritten“ als Text.
- **Tests:** positiv: Lauf des Referenzszenarios; Abbruch eines laufenden Laufs. negativ: Szenario ohne Fahrzeuge liefert Nachfrageerfüllung 0 mit Grund statt Fehler; fehlende Kostensätze führen zu „Kostenindikator nicht berechenbar“; Start für fremdes Szenario 404; doppelter Start mit gleichem Idempotency-Key liefert denselben Lauf, mit anderem Request `409 IDEMPOTENCY_CONFLICT` (Prüfmuster PH-AT-011); Start auf nicht eingefrorenem Szenario wird abgelehnt; Start ohne Planerrolle 403; Abbruch eines bereits abgeschlossenen Laufs wird abgelehnt; Lauf mit Limitüberschreitung endet mit Status statt Teilergebnis. konkurrierend (Kapazität/Datentrennung/Idempotenz): zwei gleichzeitige Starts mit gleichem Idempotency-Key erzeugen genau einen Lauf; Abbruch gleichzeitig mit Abschluss ergibt genau einen Endzustand; großer Lauf parallel zu Buchungslast gemäß Lastprofil. Besteht AT-22 gemeinsam mit WP-M9a-14 und WP-M9a-15.
- **Abhängigkeiten:** intern: WP-M9a-14, WP-M9a-15, WP-M9a-16.
  - extern: WP-M2-17, WP-M2-16, WP-M2-10 (Dispositions-/Solverkern und Routingadapter als Bibliothek)
  - extern: WP-M8a-39, WP-M8a-04 (versionierte Kostensätze (`rate_revision`, `cost_category`))
  - extern: WP-M1-13, WP-M1-15, WP-M1-46, WP-M1-07, WP-M1-08 (Idempotenz, `operation`-Ressource, Audit, Rollen-/Objektberechtigung)
  - extern: WP-M8c-22 (ausführbare Kennzahldefinitionen)
- **Entscheidungen:** D-07 (Kostenbewertung) – blockiert nur die Produktivschaltung der Kostenindikatoren; D-09 (Routing/Karten) – blockiert nur Produktivschaltung.
- **Größe:** L
- **Nicht im Umfang:** Toleranznachweis der Wiederholbarkeit (WP-M9a-18); Vergleich (WP-M9a-19); E-Flotte (WP-M9a-20); Heatmaps (WP-M9a-21); Erlöse/Tarife (WP-M9a-22).

### WP-M9a-18 – Reproduzierbarkeit: Algorithmusversion, Eingabedatensatz, Zufallsseed, Toleranz

- **Ziel:** Zu jedem Lauf sind Algorithmusversion, Eingabedatensatz und Zufallsseed festgehalten; die Wiederholung mit identischen Parametern liefert ein Ergebnis innerhalb einer definierten Toleranz.
- **Anforderungen:** `LH-6.16-SIM-005`
- **Liefergegenstände:**
  - Backend: Pflichtfelder Algorithmusversion (Solver-/Bibliotheks-/Containerversion), Eingabedatensatz (Revision und Hash) und Zufallsseed am Lauf; Funktion „Lauf wiederholen“ mit identischen Eingaben; Toleranzprüfung zwischen Original und Wiederholung je Ergebniskennzahl; Aufbewahrung der Algorithmusversion (Containerabbild) für die Dauer der Nachvollziehbarkeit.
  - Datenbank: `simulation_run` (Spalten Algorithmusversion, Datensatzrevision/-hash, Seed, Bezug auf Ursprungslauf).
  - API: Arbeitsname `/v1/scenarios/{id}/runs` mit Option „Wiederholung von Lauf X“; Toleranzergebnis im Laufergebnis.
  - Oberfläche: UI-19 – Anzeige von Algorithmusversion, Datensatz und Seed; Aktion „identisch wiederholen“ mit Abweichungsanzeige.
  - Konfigurationsparameter: Reproduktionstoleranz als Arbeitsnamen-Parameter (kein Katalogeintrag, Offener Punkt 11).
- **Detailverträge vorab:** Definition der Toleranz je Kennzahl; Quellen von Nichtdeterminismus (Parallelität, Solver-Zeitlimit) und deren Behandlung.
- **Akzeptanzkriterien:**
  1. Jeder Lauf speichert Algorithmusversion, Eingabedatensatz und Zufallsseed; ohne diese Angaben startet kein Lauf (SIM-005).
  2. Die Wiederholung eines Laufs mit identischen Parametern liefert Ergebnisse innerhalb der definierten Toleranz; der Test wiederholt den Referenzlauf mehrfach (SIM-005).
  3. Eine Wiederholung mit anderer Algorithmusversion oder anderem Datensatz wird nicht als identische Wiederholung ausgewiesen.
  4. Die angewendete Toleranz (Wert, Version, je Kennzahl) ist im Wiederholungsergebnis ausgewiesen; eine Überschreitung wird als „außerhalb Toleranz“ gekennzeichnet und nicht verworfen (SIM-005).
  5. Algorithmusversion, Eingabedatensatz und Seed eines abgeschlossenen Laufs sind unveränderlich.
- **Tests:** positiv: Mehrfachwiederholung des Referenzlaufs. negativ: Wiederholung mit geändertem Seed zeigt Abweichung und ist als nicht identisch gekennzeichnet; nicht mehr verfügbare Algorithmusversion führt zu klarer Meldung statt zu stiller Nutzung einer neueren Version; gelöschte bzw. veränderte Datensatzrevision (Hashabweichung) verhindert die identische Wiederholung mit Meldung; nachträgliche Änderung des Seeds wird abgelehnt. konkurrierend: zwei parallele Wiederholungen desselben Laufs auf verschiedenen Workern liegen beide innerhalb der Toleranz.
- **Abhängigkeiten:** intern: WP-M9a-17.
- **Entscheidungen:** keine für den Bau; D-11 (Aufbewahrungsdauer von Datensätzen und Algorithmusversionen) blockiert nur Produktivschaltung.
- **Größe:** S
- **Nicht im Umfang:** Reproduzierbarkeit von Ist-Berichten (M8c).

### WP-M9a-19 – Szenariovergleich mit absoluten und relativen Differenzen

- **Ziel:** Zwei Szenarien bzw. Läufe lassen sich vergleichen; der Vergleich zeigt Unterschiede in absoluten und relativen Kennzahlen reproduzierbar.
- **Anforderungen:** `LH-6.16-SIM-004`
- **Liefergegenstände:**
  - Backend: Vergleichsdienst über zwei abgeschlossene Läufe: je Kennzahl Wert A, Wert B, absolute und relative Differenz; Prüfung der Vergleichbarkeit (gleiche Definitionsversionen, gleiche Periodenbasis); Null-Basis ergibt „relative Differenz nicht berechenbar“; Vergleich wird mit den Lauf-IDs gespeichert und ist erneut abrufbar.
  - Datenbank: `simulation_comparison` (Arbeitsname), lesend `simulation_result`.
  - API: Arbeitsname `/v1/scenarios/compare` (bzw. Unterressource von `/v1/scenarios/{id}/runs`).
  - Oberfläche: UI-19 – primäre Aktion „Vergleich durchführen“ (Kap. 06 §6.9): Gegenüberstellung als Tabelle mit absoluter und relativer Differenz, Hinweis bei eingeschränkter Vergleichbarkeit; Zustände „Keine Daten“, „geringe Qualität“.
- **Detailverträge vorab:** Vergleichsschema; Regeln der Vergleichbarkeit; Rundung relativer Differenzen.
- **Akzeptanzkriterien:**
  1. Der Vergleich der zwei Referenzszenarien A/B zeigt je Kennzahl absolute und relative Differenz (SIM-004).
  2. Wiederholtes Ausführen desselben Vergleichs liefert identische Differenzen; nach identischer Wiederholung beider Läufe liegen die Differenzen innerhalb der Toleranz aus WP-M9a-18 (SIM-004).
  3. Läufe mit unterschiedlichen Definitionsversionen werden nur mit sichtbarem Hinweis verglichen.
  4. Der Vergleich nennt je Seite Lauf, Szenariorevision, Algorithmusversion, Datensatz und Seed; die Vergleichsansicht in UI-19 ist als Tabelle per Tastatur und Screenreader nutzbar, Vorzeichen und Richtung der Differenz stehen als Text (Kap. 06 §6.1, §6.9).
  5. Geldbezogene Differenzen werden centgenau aus `amount_minor` gebildet; unterschiedliche Währungen werden nicht verrechnet.
- **Tests:** positiv: Vergleich A/B gegen Orakeldifferenzen. negativ: Vergleich mit nicht abgeschlossenem Lauf wird abgelehnt; Basiswert 0; Vergleich mit Lauf eines fremden Mandanten ergibt 404; Vergleich eines Laufs mit sich selbst ergibt Differenz 0; Vergleich ohne Planer-/Statistikrolle 403; Vergleich mit unzulässigem bzw. abgebrochenem Lauf wird abgelehnt.
- **Abhängigkeiten:** intern: WP-M9a-17, WP-M9a-18.
  - extern: WP-M8c-19, WP-M8c-04 (Statistikbereich UI-19 und Definitionsversionen)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Vergleich Simulation gegen Ist-Betrieb als eigener Bericht (nicht gefordert; Ist-Vergleiche liegen bei M8c); Maßnahmenempfehlung (WP-M9a-23).

### WP-M9a-20 – E-Flotten-Simulation: Ladeinfrastruktur, Reichweiten, Standzeiten

- **Ziel:** Die Simulation kann für E-Flotten Ladeinfrastruktur, Reichweiten und Standzeiten berücksichtigen; fehlt eine Ladeoption, wird das Szenario als unzulässig sichtbar.
- **Anforderungen:** `LH-6.16-SIM-006`
- **Liefergegenstände:**
  - Backend: Erweiterung des Szenarioparametersatzes um Ladepunkte (Ort, Anzahl, Leistung, Verfügbarkeitsfenster), Fahrzeugreichweite/Verbrauch und Mindeststandzeiten; Zulässigkeitsprüfung vor und während des Laufs; Laufstatus „unzulässig“ mit Ursache je Fahrzeug/Umlauf.
  - Adapter/Worker: Lade-/Reichweitenbedingungen im Simulationskern als harte Constraints („Lade-/Reichweitenreserven“, Kap. 01 §1.4), mit derselben Regelbibliothek wie die Live-Disposition der E-Mobilität.
  - Datenbank: Erweiterung `simulation_scenario_revision`, `simulation_result` (Arbeitsnamen); lesend `charging_slot` (Ladefenster/SoC, Kap. 04 §4.4) als eingefrorene Kopie.
  - Oberfläche: UI-19 – Abschnitt „E-Flotte“ im Parametereditor; Ergebnisansicht mit Kennzeichnung „Szenario unzulässig: keine Ladeoption“ samt betroffener Fahrzeuge.
  - Konfigurationsparameter: `min_battery_soc_pct`, `min_return_soc_pct`, `charge_reservation_min` als Ausgangswerte, im Szenario überschreibbar ohne Live-Wirkung.
  - Kennzahlen: `Strom kWh/100 km` als Ergebnisgröße.
- **Detailverträge vorab:** Schema Lade-/Reichweitenparameter; Verbrauchs- und Lademodell (Offener Punkt 13); Schnittstelle zur E-Mobilitätsregel der Nachbareinheit.
- **Akzeptanzkriterien:**
  1. Ein Szenario mit E-Fahrzeugen berücksichtigt Ladeinfrastruktur, Reichweite und Standzeiten: Verlängerte Standzeit bzw. reduzierte Reichweite verändert das Ergebnis des Referenzszenarios in erwarteter Richtung (SIM-006).
  2. Im Referenzszenario ohne Ladeoption wird das Szenario als unzulässig ausgewiesen, mit Ursache und betroffenen Fahrzeugen; es entsteht kein Ergebnis, das Zulässigkeit vortäuscht (SIM-006).
  3. Kein simuliertes Fahrzeug unterschreitet `min_battery_soc_pct` bzw. bei Rückkehr `min_return_soc_pct`.
  4. Im Szenario geänderte Lade-/Reichweitenparameter verändern weder `charging_slot` noch die wirksamen Live-Werte von `min_battery_soc_pct`, `min_return_soc_pct`, `charge_reservation_min`.
  5. Die Kennzeichnung „unzulässig“ steht in API-Status und in UI-19 als Text mit Ursache; das unzulässige Szenario erscheint im Vergleich (WP-M9a-19) nicht als gültiger Lauf.
- **Tests:** positiv: Szenario mit ausreichender Ladeinfrastruktur. negativ: keine Ladeoption; Ladepunkt nur außerhalb der Betriebszeit; Reichweite kleiner als kürzester Umlauf; Mischflotte ohne E-Parameter läuft unverändert.
- **Abhängigkeiten:** intern: WP-M9a-16, WP-M9a-17.
  - extern: WP-M9b-02, WP-M9b-05, WP-M9b-10 (E-Mobilität der Nachbareinheit – `charging_slot` (WP-M9b-02), SoC-/Reichweitenmodell, Regelbibliothek Ladefenster/Reichweitenkonflikt)
- **Entscheidungen:** D-09 (Telematik als Quelle realer Verbrauchswerte) – blockiert nur Produktivschaltung mit realen Werten.
- **Größe:** M
- **Nicht im Umfang:** Live-Disposition und Fahrzeugkontrolle der E-Flotte (AT-20, AT-58, Nachbareinheit M9b); Beschaffungs- oder Investitionsrechnung.

### WP-M9a-21 – Anonymisierte Heatmaps und Engpässe je Gebiet aus Simulationsergebnissen

- **Ziel:** Die Simulation erzeugt anonymisierte Heatmaps und Engpassdarstellungen je Gebiet; kleinräumige Gruppen werden datenschutzkonform aggregiert.
- **Anforderungen:** `LH-6.16-SIM-007`
- **Liefergegenstände:**
  - Backend: Aggregation der Simulationsergebnisse auf Raum-/Zeitzellen (Nachfrage, nicht bediente Nachfrage, Engpass je Gebiet); Unterdrückung bzw. Zusammenlegung von Zellen unter `kpi_aggregation_min_n` („Mindestzellgrößen/Unterdrückung kleiner Gruppen“, Kap. 01 §1.7), auch gegen Differenzbildung über benachbarte Filter; Wiederverwendung der Kleingruppenlogik aus M8c.
  - Datenbank: Erweiterung `simulation_result` (Arbeitsname) um Zellaggregate; keine Speicherung einzelner Start-/Zielpunkte in der Auslieferungsschicht.
  - API: Arbeitsname `/v1/scenarios/{id}/runs/{run_id}/heatmap` (GeoJSON gemäß GeoJSON/SRID-Konvention aus Kap. 05 §5.7).
  - Oberfläche: UI-19 – Heatmap mit gleichwertiger Tabellen-/Listenalternative (Kap. 06 §6.1), Engpassliste je Gebiet, Hinweis „aggregiert wegen kleiner Gruppe“.
  - Konfigurationsparameter: `kpi_aggregation_min_n`.
  - Kennzahlen: `Kapazitätsablehnungen`, `Gebietsablehnungen`, `Zeitfensterablehnungen` als Engpassgrößen.
- **Detailverträge vorab:** Zellraster und Aggregationsstufen; Regel für Zusammenlegung vs. Unterdrückung; Schutz gegen Differenzangriff.
- **Akzeptanzkriterien:**
  1. Für einen abgeschlossenen Lauf sind Heatmap und Engpässe je Gebiet abrufbar; die Ausgabe enthält keine Einzelbuchung und keine Einzeladresse (SIM-007).
  2. Im Referenzgebiet mit einer Gruppe unter `kpi_aggregation_min_n` wird die Zelle nicht einzeln ausgewiesen, sondern aggregiert bzw. unterdrückt; auch durch Kombination zweier Filter lässt sich der Zellwert nicht zurückrechnen (SIM-007).
  3. Bei Szenarien aus historischer Nachfrage gilt dieselbe Regel wie bei Ist-Heatmaps.
  4. Die Tabellen-/Listenalternative enthält dieselben Werte wie die Karte; unterdrückte bzw. zusammengelegte Zellen sind dort als solche benannt. Exporte der Heatmap unterliegen derselben Unterdrückung.
- **Tests:** positiv: Heatmap des Referenzlaufs. negativ: Zelle unter Mindestgröße; Differenzbildung über zwei Zeitfilter; feineres Zellraster als erlaubt wird abgelehnt; Zugriff ohne Planer-/Statistikrolle 403; Heatmap eines fremden Laufs 404; Lauf ohne nicht bediente Nachfrage liefert leere Engpassliste statt Fehler (Prüfmuster PH-AT-021 für den Aggregationsschutz). konkurrierend (Datentrennung): parallele Heatmapabrufe zweier Mandanten liefern nur eigene Zellen.
- **Abhängigkeiten:** intern: WP-M9a-17.
  - extern: WP-M8c-12, WP-M8c-11 (Heatmap-Komponente und Kleingruppenschutz der Ist-Statistik)
  - extern: WP-M8b-29 (Regel „Aggregation und Mindestgruppengröße für personenbezogene Auswertungen“)
- **Entscheidungen:** D-11 (Freigabe aggregierter Statistik für Aufgabenträger/Partner, Kap. 01 §1.7) – blockiert nur Weitergabe außerhalb des Mandanten.
- **Größe:** M
- **Nicht im Umfang:** Ist-Heatmaps der Nachfrage (M8c); Weitergabe an Partner oder Aufgabenträger.

### WP-M9a-22 – Tarifsimulation auf anonymisierten historischen Fahrten ohne reale Wirkung

- **Ziel:** Eine Tarifsimulation errechnet Erlösänderungen auf Basis historischer anonymisierter Fahrten; kein simuliertes Ergebnis löst eine reale Buchung oder Zahlung aus. KANN-Funktion: Bau nur nach ausdrücklicher Aufnahme ins Release.
- **Anforderungen:** `LH-6.16-SIM-008`
- **Liefergegenstände:**
  - Backend: Tarifvariante als Szenarioparameter (Kopie einer Tarifrevision, im Szenario änderbar); Neuberechnung der Preise historischer anonymisierter Fahrten mit dem Tarifkern als reine Funktion (ohne Quote-, Hold-, Payment- oder Outbox-Seiteneffekte); Ergebnis: Erlös Ist-Tarif, Erlös Variante, absolute und relative Änderung, Annahmen (keine Nachfragereaktion, sofern nicht modelliert). Geldbeträge als `{amount_minor,currency,exponent}`, kein binary float (Kap. 05 §5.1).
  - Datenbank: Erweiterung `simulation_scenario_revision`, `simulation_result` (Arbeitsnamen); lesend `tariff_revision`, `tariff_rule`, `fare_component` als eingefrorene Kopie; anonymisierter Fahrtendatensatz in `simulation_dataset`. Kein Schreiben nach `fare_quote`, `payment_intent`, `booking`, `economic_event`, `settlement_line`.
  - Adapter/Worker: kein PSP-Adapter und kein Partneradapter im Simulationsworker konfiguriert (keine Secrets, keine Netzfreigabe).
  - API: `/v1/scenarios/{id}/runs` mit Laufart „Tarifsimulation“ (Arbeitsname).
  - Oberfläche: UI-19 – Ergebnisansicht „Erlösänderung“ mit Annahmenblock und Kennzeichnung „Simulation“.
- **Detailverträge vorab:** Anonymisierungsregel des Fahrtendatensatzes; Schnittstelle „Tarifkern als reine Funktion“; Annahme zur Nachfrageelastizität (Offener Punkt 14).
- **Akzeptanzkriterien:**
  1. Für eine geänderte Tarifvariante wird die Erlösänderung gegenüber dem Ist-Tarif auf anonymisierten historischen Fahrten ausgewiesen (SIM-008).
  2. Während und nach einem Tarifsimulationslauf entsteht keine Zeile in `booking`, `fare_quote`, `payment_intent`, `economic_event` oder `settlement_line`, und der PSP-Simulator verzeichnet keinen Aufruf (SIM-008).
  3. Der Fahrtendatensatz enthält keine Personenmerkmale; Gruppen unter `kpi_aggregation_min_n` werden in der Ausgabe nicht einzeln ausgewiesen.
  4. Die Live-Tarifrevision ist nach dem Lauf unverändert.
  5. Kein simuliertes Ergebnis erzeugt eine Mitteilung, einen Beleg oder einen Gutschein an Fahrgäste; das Ergebnis ist in API und UI-19 als Simulation mit Annahmenblock gekennzeichnet (SIM-008).
- **Tests:** positiv: Variante mit erhöhtem Grundpreis gegen Handrechnung. negativ: Versuch, aus dem Ergebnis eine Buchung oder Zahlung auszulösen, ist technisch nicht möglich (keine Route, keine Rechte); Rundungsrest in EUR wird centgenau behandelt; fremde Tarifrevision 404. konkurrierend (Geld/Datentrennung): Tarifsimulation parallel zu echtem Checkout – Echt-Zahlung unverändert, genau eine PSP-Operation je echter Buchung, keine aus der Simulation.
- **Abhängigkeiten:** intern: WP-M9a-15, WP-M9a-17.
  - extern: WP-M2-07, WP-M2-08, WP-M2-09, WP-M0-23 (Tarifkern und Tarifrevisionen (`tariff_revision`, `tariff_rule`, `fare_component`), PSP-Simulator)
  - extern: WP-M8d-10 (Preis-, Rabatt- und Erstattungsregeln mit Gültigkeit)
  - extern: WP-M8b-29, WP-M8b-06 (Pseudonymisierung/Anonymisierung und Mindestgruppengröße)
- **Entscheidungen:** D-12 (Aufnahme der KANN-Funktion ins Release) – blockiert den Bau. D-11 (Aufbewahrung/Anonymisierung) – blockiert nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Tarif-Sandbox der Zahlungsdomäne (`LH-6.10-PAY-017`, WP-M9b-34; fachlich überlappend, Abgrenzung siehe Offener Punkt 19); Übernahme einer Tarifvariante in den Live-Tarif (regulärer Freigabeweg, UI-17); Clearing- und Partnererlöse (M6c).

### WP-M9a-23 – Maßnahmenempfehlungen mit Sensitivität und ausgewiesenen Limitierungen

- **Ziel:** Maßnahmenempfehlungen aus der Simulation begründen ihre Sensitivität gegenüber Annahmen und Unsicherheiten; jedes Ergebnis weist die Limitierungen der Prognose erkennbar aus. KANN-Funktion: Bau nur nach ausdrücklicher Aufnahme ins Release.
- **Anforderungen:** `LH-6.16-SIM-009`
- **Liefergegenstände:**
  - Backend: Sensitivitätslauf (gezielte Variation einzelner Annahmen eines Szenarios in mehreren Läufen) und Ableitung von Empfehlungen aus dem Szenariovergleich; je Empfehlung: zugrunde liegende Läufe, Sensitivität je variierter Annahme, Unsicherheitsangabe, Liste der Limitierungen (Datenstand, Modellgüte, Vereinfachungen); Kennzeichen „unverbindlich“.
  - Datenbank: Erweiterung `simulation_comparison` (Arbeitsname) um Sensitivitäts- und Empfehlungsblock.
  - API: Arbeitsname `/v1/scenarios/{id}/recommendations`.
  - Oberfläche: UI-19 – Empfehlung mit Abschnitt „Annahmen und Grenzen“ als Text, nicht nur als Symbol.
- **Detailverträge vorab:** Schema Empfehlung; Katalog der Limitierungstexte; Regel, welche Annahmen variiert werden (Offener Punkt 15).
- **Akzeptanzkriterien:**
  1. Jedes Empfehlungsergebnis enthält erkennbare Limitierungen der Prognose; ein Ergebnis ohne Limitierungsangabe wird nicht ausgeliefert (SIM-009).
  2. Jede Empfehlung weist aus, wie stark sie auf die variierten Annahmen reagiert, und nennt die Unsicherheit (SIM-009).
  3. Eine Empfehlung löst keine Änderung an Konfiguration, Dienstplan oder Flotte aus.
  4. Jede Empfehlung verweist auf die zugrunde liegenden Läufe mit Algorithmusversion, Datensatz und Seed und ist damit reproduzierbar; Empfehlungen enthalten keine Aussage über einzelne Beschäftigte oder Fahrgäste.
- **Tests:** positiv: Empfehlung aus dem Vergleich A/B mit Sensitivität. negativ: Empfehlung auf Basis eines Laufs mit schlechter Modellgüte trägt den entsprechenden Hinweis; fehlende Sensitivitätsläufe führen zu „keine Empfehlung“; Empfehlung auf Basis eines unzulässigen oder abgebrochenen Laufs wird nicht erzeugt; Zugriff ohne Planerrolle 403, fremder Mandant 404.
- **Abhängigkeiten:** intern: WP-M9a-17, WP-M9a-19.
- **Entscheidungen:** D-12 (Aufnahme der KANN-Funktion ins Release) – blockiert den Bau.
- **Größe:** M
- **Nicht im Umfang:** Vorschläge aus der Nachfrageprognose (WP-M9a-12); Plausibilitätsvorschläge für Stellgrößen (M8d).

### WP-M9a-24 – M9a-Gate-Abnahmesuite: AT-21, AT-22, Beitrag AT-55

- **Ziel:** Die Referenztests des Gates M9, die auf diese Einheit entfallen, laufen automatisiert in `tests/acceptance` und sind mit Build, Evidenz und Entscheidung in `e2e.csv` eingetragen.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: automatisierte Szenarien AT-21 (Prognosesystem abgeschaltet → Buchung und Disposition funktionieren ohne KI), AT-22 (Planer startet große Szenariosimulation → keine Änderung an Echt-Buchungen, SLOs bleiben gültig) und der M9a-Anteil von AT-55 (Nachfrageprognose erhöht Personalbedarf → Vorschlag unverbindlich; Publikation erst nach Freigabe); Vorher/Nachher-Abgleich des Produktionsbestands (Zeilenanzahl, Revisionen, Hash) für AT-22; SLO-Messung unter Lastprofil.
  - Backend: Nachweisliste je MUSS-Anforderung (`LH-6.15-AI-001`, `LH-6.15-AI-002`, `LH-6.15-AI-003`, `LH-6.16-SIM-001`, `LH-6.16-SIM-002`, `LH-6.16-SIM-003` werden hier nur referenziert, zugeordnet sind sie den WPs 05, 06, 08, 15, 16, 17) mit grünem Test; Testprotokoll mit den Pflichtfeldern aus Kap. 08 §8.4.
  - Oberfläche: UX-Nachweis für UI-19 (Vergleich durchführen per Tastatur und Screenreader, Tabellenalternativen, Zustände „Keine Daten“, „geringe Qualität“, „Live-Schreibversuch“).
- **Detailverträge vorab:** Definition „große Szenariosimulation“ und des Lastprofils für AT-22 (Offener Punkt 11); Aufteilung von AT-55 zwischen M9a und M7d.
- **Akzeptanzkriterien:**
  1. AT-21 und AT-22 sind grün und mit Build-ID in `e2e.csv` eingetragen.
  2. Der M9a-Anteil von AT-55 ist grün; das vollständige AT-55 läuft gemeinsam mit M7d.
  3. Alle sechs MUSS-Anforderungen der Einheit haben einen grünen Test; SOLL-Abweichungen sind mit Begründung und Freigabe des Auftraggebers dokumentiert; nicht aufgenommene KANN-Funktionen sind als solche ausgewiesen (Kap. 08 §8.4).
  4. Evidenz enthält keine unnötigen Fahrgast- oder Personaldaten.
  5. Die Prüfmuster PH-AT-001 (Isolation über Detail-, Listen-, Such- und Exportendpunkte der Einheit, leerer Transaktionskontext, Poolwiederverwendung), PH-AT-011 (Idempotenzkonflikt auf `/v1/scenarios`, `/v1/scenarios/{id}/runs` und Prognoselauf) und PH-AT-021 (Null-Nenner, Datenlücke, Aggregationsschutz für Prognosegüte und Heatmap) sind für die Endpunkte dieser Einheit automatisiert und grün.
  6. Die Zuständigkeit je Prüfschritt von AT-21, AT-22 und AT-55 zwischen M9a, M9b (WP-M9b-57) und M7d ist dokumentiert; kein Prüfschritt bleibt ohne Verantwortlichen.
- **Tests:** positiv: AT-21, AT-22, AT-55 (Anteil). negativ: Kontrolllauf mit absichtlich eingeschleustem Live-Schreibzugriff der Simulation lässt AT-22 fehlschlagen (Wirksamkeit des Tests). konkurrierend: AT-22 unter gleichzeitiger Buchungslast zweier Mandanten.
- **Abhängigkeiten:** intern: WP-M9a-08, WP-M9a-09, WP-M9a-11, WP-M9a-12, WP-M9a-14, WP-M9a-15, WP-M9a-17, WP-M9a-19, WP-M9a-21.
  - extern: WP-M5-10, WP-M5-11, WP-M5-01 (Lastprofil, SLO-Messung, Abnahmeprotokollierung)
  - extern: WP-M7d-20, WP-M7d-32 (Dienstplan-Freigabe und Publikation für AT-55)
  - extern: WP-M9b-57 (Dispositions-, Entkopplungs- und Personalplanungsseite der gemeinsamen Tests (WP-M9b-57))
- **Entscheidungen:** D-05 (Zielwerte und Lastprofil) – blockiert nur die Abnahme gegen endgültige Werte.
- **Größe:** M
- **Nicht im Umfang:** AT-20 und AT-58 (E-Flotte, Nachbareinheit M9b); Gate-Nachweise der übrigen 54 P3-Anforderungen (M9b); gesonderte fachliche Abnahme von Simulation und KI gegen eine Baseline (`LH-6.20-QAT-011`, WP-M9b-55).

## Offene Punkte

1. **`economic_event.scenario = 'SIMULATION'` gegen getrennte Simulationsdaten.** `kernschema.sql` erlaubt in `economic_event` den Szenariowert `SIMULATION`; Kap. 01 §1.6 („Simulation hat separate Daten“), Kap. 04 §4.4 („Trainings-/Simulationsdaten getrennt von Live“) und die Abnahme von `LH-6.16-SIM-001` („vollständig getrennt vom Produktionsbetrieb“) sprechen dagegen, Simulationsergebnisse in eine Live-Finanztabelle zu schreiben. Kap. 03 Abschnitt 1 führt zudem `valuation = ACTUAL | PLAN | FORECAST | SIMULATION` als Bewertungsart von Kostenereignissen. Diese Datei plant ohne Schreiben nach `economic_event`; der Zweck des Wertes und ob Simulationskosten als Kostenereignisse mit Bewertungsart SIMULATION zu führen sind, ist zu klären (betrifft `LH-6.16-SIM-003`, `LH-6.16-SIM-008`).
2. **Keine Tabelle für Nachfragemodelle und Prognoseläufe.** Kap. 04 §4.4 nennt nur `eta_model_revision` und `model_approval`. Ob Nachfragemodelle dort mitgeführt oder in eigenen Tabellen liegen, und ob die Modellfreigabe aus M8b unverändert gilt, ist offen (`LH-6.15-AI-002`). Wer ein Nachfragemodell freigibt (Rolle), nennt das Pflichtenheft nicht.
3. **Grad der Trennung.** „Vollständig getrennt“ (`LH-6.16-SIM-001`) und „isolierte Daten-/Rechenumgebung“ (Baustein) legen nicht fest, ob ein eigenes Schema mit eigenen Rollen genügt oder eine eigene Datenbankinstanz nötig ist; Kap. 01 §1.1 nennt ein getrenntes Warehouse erst „später“. Hängt mit D-02 zusammen.
4. **Zählgröße der Nachfrage.** Das Pflichtenheft definiert nicht, ob „historische Nachfrage“ Anfrageversuche (`travel_attempt`), Buchungen oder beides einschließlich nicht bedienter Nachfrage meint (`LH-6.15-AI-001`, `LH-6.16-SIM-001`).
5. **Gebiet, Zeitraster und Horizont der Prognose.** `LH-6.15-AI-001` verlangt „Prognosewert und zugehörigen Zeithorizont je Gebiet“, nennt aber weder Gebietszuschnitt (Bediengebiet oder Zelle), Zeitraster noch Horizont; `kataloge.csv` enthält dafür keinen Parameter.
6. **Zusatzquellen.** Für Ferien, Veranstaltungen und „geeignete Wetterdaten“ (`LH-6.15-AI-004`) sind weder Quelle, Lizenz noch Adapter benannt; „nach Freigabe“ (`LH-6.15-AI-001`) und „auf Wunsch“ (`LH-6.15-AI-004`) nennen keinen Freigebenden. Ein Feiertagskalender entsteht in WP-M8d-08 bzw. WP-M7d-11; für Schulferien ist in keinem Artefakt eine Datenquelle benannt.
7. **Wirkung der Prognose auf die Live-Disposition.** Das Pflichtenheft verlangt Vorschläge (`LH-6.15-AI-006`) und verbietet das Umgehen harter Regeln (`LH-6.15-AI-003`), sagt aber nicht, ob Prognosen die weiche Zielfunktion der Live-Disposition verändern dürfen. Ebenso fehlt die Liste der zulässigen „Optimierungsgewichte“ und ihrer Grenzen (`LH-6.16-SIM-002`). Diese Datei plant Prognosen ausschließlich als Hinweis.
8. **Güte- und Anomalieschwellen.** „Schlechtes Ergebnis“ (`LH-6.15-AI-005`), das Fehlermaß, das naive Referenzmodell und die „definierte Anomalie“ (`LH-6.15-AI-008`) sind nicht definiert; `kataloge.csv` enthält weder eine Kennzahl für den Nachfrage-Prognosefehler noch Schwellenparameter.
9. **Verantwortliche Rolle.** `LH-6.15-AI-008` nennt eine „verantwortliche Rolle“, das Rollenmodell benennt keine Rolle für den Modellbetrieb.
10. **Effektschätzung.** `LH-6.15-AI-006` verlangt eine „Effektschätzung“, ohne Bezugsgröße oder Methode; für „Standorte leerer Fahrzeuge“ gibt es weder Endpunkt noch Bildschirmbeschreibung. Die Abgrenzung zum Bedarfsvorschlag `LH-6.23-SCH-028` (M7d, P2) ist nur über AT-55 erkennbar.
11. **Toleranz, Ressourcenlimits, „große“ Simulation.** Die „definierte Toleranz“ (`LH-6.16-SIM-005`), die Ressourcenlimits (Kap. 01 §1.6) und die Größe der Simulation in AT-22 sind nicht beziffert; die SLO-Zielwerte selbst sind vorläufig (D-05).
12. **Ergebnisgrößen der Simulation.** „Nachfrageerfüllung“, „Kostenindikatoren“ und „Wartezeit“ (`LH-6.16-SIM-003`) sind keiner Kennzahl aus `kataloge.csv` zugeordnet; für Wartezeit existiert keine Katalogkennzahl (nur der Parameter `max_wait_min` und `Mittlere Abholverspätung`). Welche Kostenbasis die Kostenindikatoren verwenden, hängt an D-07.
13. **E-Flotten-Modell.** Verbrauchs-, Lade- und Standzeitmodell für `LH-6.16-SIM-006` sind nicht spezifiziert; `charging_slot` ist in Kap. 04 §4.4 nur mit „Ladefenster/SoC“ beschrieben. Die Zuständigkeit für die gemeinsame Regelbibliothek mit der E-Mobilitätseinheit von M9 ist festzulegen.
14. **Tarifsimulation.** `LH-6.16-SIM-008` nennt „historische anonymisierte Fahrten“ ohne Anonymisierungsverfahren und ohne Aussage, ob eine Nachfragereaktion auf Preisänderungen modelliert wird.
15. **Maßnahmenempfehlungen.** `LH-6.16-SIM-009` beschreibt weder Art noch Herleitung der Empfehlungen; die Abnahme spricht von „Limitierungen der Prognose“, obwohl die Anforderung zur Simulation gehört.
16. **Fehlende Vertragsartefakte.** `openapi.yaml` enthält keinen Pfad dieser Einheit; Kap. 05 §5.7 nennt keinen Endpunkt für die Nachfrageprognose. Kap. 06 enthält für UI-19 nur die Katalogzeile (Kap. 06 §6.9), kein Wireframe. Die Zielstruktur des Repositorys (Umsetzungsplan §3) nennt weder einen Prognose- noch einen Simulationsworker.
17. **Aufnahme der KANN-Funktionen.** `LH-6.15-AI-009`, `LH-6.16-SIM-008` und `LH-6.16-SIM-009` benötigen eine ausdrückliche Aufnahme ins Release (Kap. 08 §8.4); ein Beschluss liegt nicht vor (D-12).
18. **Datenschutz und Mitbestimmung.** Aufbewahrungsfristen für Trainings-, Szenario- und Ergebnisdaten sind offen (D-11). Ob Schichtvorschläge aus Prognosen der Mitbestimmung unterliegen, ist nicht behandelt (D-10 betrifft dem Wortlaut nach Fahrerdaten und ETA).
19. **Überschneidung mit M9b.** Sechs Anforderungen der Nachbareinheit setzen Fähigkeiten dieser Einheit voraus oder überlappen fachlich: `LH-6.5-OPT-020` (WP-M9b-22, Positionierung mit Prognose) mit `LH-6.15-AI-003`/`LH-6.15-AI-006`; `LH-6.10-PAY-017` (WP-M9b-34, Tarif-Sandbox) mit `LH-6.16-SIM-008`; `LH-6.14-ANA-012` (WP-M9b-41) mit `LH-6.15-AI-005`; `LH-6.23-SCH-034` (WP-M9b-48) mit `LH-6.15-AI-006` und `LH-6.23-SCH-028` (M7d); `LH-6.19-NFR-019` (WP-M9b-54) mit `LH-6.15-AI-007` und AT-22; `LH-6.20-QAT-011` (WP-M9b-55). Wer die gemeinsame Implementierung (Tarifsimulation, Entkopplungsnachweis, Bedarfsvorschlag) führt und welche Leseport-Verträge gelten, ist in WP-M9a-01 gemeinsam mit M9b festzulegen. `LH-6.16-SIM-008` ist KANN, `LH-6.10-PAY-017` ist getrennt zu priorisieren; wird SIM-008 nicht aufgenommen, darf WP-M9b-34 nicht von WP-M9a-22 abhängen.
20. **Freigabeverfahren für Nachfragemodelle.** `LH-6.15-AI-002` verlangt ein „freigegebenes Modell“; Kap. 05 §5.7 nennt „Human Review“. Ob die Freigabe eine Trennung von Ersteller und Freigeber (Vier-Augen) verlangt, steht nirgends; das Pflichtenheft kennt Vier-Augen nur für Belege (Kap. 03, PH-AT-019). WP-M9a-05 plant die Trennung als gekennzeichnete Annahme.
21. **Rollen und Rechte.** Für Prognose, Model Registry, Signalquellen, Szenarien, Läufe, Heatmaps und Auditbericht nennt das Pflichtenheft weder Rollen- noch Rechtenamen; „Planer“ erscheint nur als Beispiel in Kap. 02 (`Membership`) und im Ablauf von AT-22/AT-55. „Planerrolle“, „Statistikrolle“ und „Datenschutzrolle“ sind in dieser Datei Arbeitsbezeichnungen; die Matrix entsteht in WP-M9a-01.
22. **Reihenfolgekonflikt: Zyklus WP-M9a-01 ↔ WP-M9b-01.** WP-M9a-01 hängt für die Abstimmung der Leseport-Verträge an WP-M9b-01; WP-M9b-01 nennt umgekehrt WP-M9a-01 als Voraussetzung (Gegenzeichnung der Leseport-Verträge). Beide liegen im selben Meilenstein M9, bilden aber einen Zyklus. Die Abhängigkeit ist hier unverändert dokumentiert; aufzulösen ist sie durch einen gemeinsamen Vertragstermin oder ein vorgezogenes gemeinsames Abstimmungs-WP (vgl. Offener Punkt 27 in M9b).
23. **Reihenfolgekonflikt: möglicher Zyklus WP-M9a-24 ↔ WP-M9b-57.** WP-M9a-24 hängt für die gemeinsamen Tests AT-21, AT-22 und AT-55 an WP-M9b-57; WP-M9b-57 setzt umgekehrt den Prognose- und Simulationsdienst aus M9a voraus. Löst M9b diese Abhängigkeit auf WP-M9a-24 auf, entsteht ein Zyklus; M9b-57 darf deshalb nur auf die liefernden Fach-WPs (z. B. WP-M9a-06, -09, -12, -14, -17) zeigen, oder der gemeinsame Lauf wird in ein einziges Gate-WP gelegt.
