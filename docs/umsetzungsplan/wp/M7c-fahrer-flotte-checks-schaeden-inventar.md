# M7c – Fahrer, Flotte, Checks, Schäden und Inventar (Betriebsausbau)

- **Meilenstein:** M7 – Betriebsausbau, Strom „Fahrer, Flotte, Personal“ (setzt M5 voraus; Nachbareinheiten M7a Fahrgast/Buchung/Geo, M7b Kommunikation/Support/Leitstelle, M7d Personal/Zeit)
- **Phase:** P2 (32 MUSS, 22 SOLL; Phase und Priorität jeder Anforderung unverändert)
- **Anzahl Anforderungen:** 54 (DRV 11, FLT 5, CHK 13, DMG 15, AST 10)
- **Anzahl WPs:** 54 (51 mit Anforderungen, 3 Enabler)

**Ziel:** Nach M7c arbeitet der Fahrer mit geschützten Nachrichten, priorisierten Betriebsinformationen, Notfallknopf, Pausenmeldung, Fahrtenbuch und einem belastbaren Offline-Modus. Prüfvorlagen erhalten einen Freigabe-Lebenszyklus, bedingte Fragen, saison- und fahrzeugtypabhängige Zusatzgruppen, einen Schichtende-/Übergabecheck, Ausnahmen mit Frist sowie Export und Auswertung. Die Schadenakte wird um Skizze, Verantwortliche und Fristen, den vollständigen Statusautomaten, Werkstattunterlagen, Kostenarten, technische Wiederfreigabe, Ersatzfahrzeug, Dublettenhinweis, Unfallakte und geschützten Export erweitert. Neu entsteht die Betriebsmittelverwaltung mit Assets, Übergaben, Standortlager, Fahrzeugdokumenten, Werkstattverträgen, Einkauf und Vertragskalender. Alle Erweiterungen nutzen den Sperr- und Freigabekern aus M3 (`resource_release`, `vehicle.safety_revision`); es entsteht kein zweiter Freigabeweg.

**Gate-Anteil M7c (Umsetzungsplan §4, Gate M7):** AT-57 (Schadenskosten), AT-62 (Schichtendreport), E2E-071 (Wartungsablauf), E2E-100 (Werkstattausfall, Partneranteil aus M6b) und die Regression von E2E-069 (Offline Tankbeleg, fachlich aus M4) nach dem Ausbau der Offline-Synchronisation. Die übrigen M7-Gate-Tests (AT-06, AT-12, AT-17, AT-38, AT-42 bis AT-44, AT-46, AT-52, AT-56, AT-63) gehören zu M7a, M7b und M7d.

**Lesehinweise:**

- Kurz-IDs in Klammern hinter Akzeptanzkriterien (z. B. CHK-020) verweisen auf den `source_key` des jeweiligen WP.
- Tabellen aus Kap. 04 §4.4 und Pfade aus Kap. 05 §5.7 sind mit ihrem exakten Namen genannt. Für das Modul AST enthält Kap. 04 §4.4 keine Tabellen; dort verwendete Namen sind ausdrücklich als **Arbeitsname** gekennzeichnet und werden in WP-M7c-01 festgelegt (Offene Punkte OP-01).
- Ereignisnamen sind Arbeitsnamen und werden in WP-M7c-01 verbindlich festgelegt.
- Planungsdefaults des Pflichtenhefts sind als solche gekennzeichnet (z. B. signierter Download höchstens 60 s, Kap. 02; Standortalter ≤ 30 s, Kap. 01 §1.7).
- Größen: S bis 1, M bis 3, L bis 6 Personenwochen.
- Querschnittsregel Oberfläche: Jede in einem WP neu gebaute oder erweiterte Ansicht liefert die Leere-, Fehler- und Rechtezustände ihrer UI-ID aus Kap. 06 §6.9 (UI-07: Sperre, veralteter Standort, keine Fremdrechte; UI-09: Offline, Mangel, andere Fahrzeugrevision; UI-10: falscher Stopp/Code, vergangene Revision; UI-11: Frist überschritten, Freigaberolle fehlt) sowie den 412-Konflikt ohne stilles Überschreiben. Die Sammelabnahme dieser Zustände liegt in WP-M7c-54.
- Referenztests außerhalb des M7-Gates (z. B. AT-11, AT-32, AT-53, AT-54, AT-59, PH-AT-xxx) sind als Regression bzw. Bezug genannt; ihre Gate-Zuordnung bleibt beim jeweiligen Meilenstein (Umsetzungsplan §4).

## Übersicht

| WP-ID | Titel | Anforderungen (Anzahl) | Größe | hängt ab von |
|---|---|---:|---|---|
| WP-M7c-01 | Detailverträge und Zustandsautomaten M7c | – (Enabler) | M | WP-M2-01, WP-M2-32, WP-M2-22, WP-M2-30, WP-M3-01, WP-M4-01, WP-M5-29, WP-M5-31 |
| WP-M7c-02 | Migration der M7c-Tabellen mit RLS | – (Enabler) | M | WP-M7c-01, WP-M1-01, WP-M1-12, WP-M1-46, WP-M2-32, WP-M3-02, WP-M4-08, WP-M4-06, WP-M4-22, WP-M4-23, WP-M4-32, WP-M4-34, WP-M4-64, UNGEDECKT (`supplier_invoice`) |
| WP-M7c-03 | Geschützte Nachrichten des Fahrers an Fahrgast und Leitstelle | 1 | M | WP-M7c-01, WP-M7c-02, WP-M2-32, WP-M2-33, WP-M3-24, WP-M3-26 |
| WP-M7c-04 | Priorisierte Betriebsinformationen mit Lesebestätigung | 1 | M | WP-M7c-02, WP-M7c-03, WP-M2-32, WP-M3-24 |
| WP-M7c-05 | Fahrtenbuch mit Export und Änderungsprotokoll | 1 | M | WP-M7c-02, WP-M3-29, WP-M3-34, WP-M4-41, WP-M4-42 |
| WP-M7c-06 | Fahrer-Notfallknopf und Unfall-/Notfallprozess | 2 | M | WP-M7c-01, WP-M7c-02, WP-M3-52, WP-M3-35, WP-M3-34 |
| WP-M7c-07 | Offline-Vorhaltung bestätigter Aufträge und Karte | 1 | M | WP-M7c-01, WP-M3-24, WP-M3-26, WP-M3-28 |
| WP-M7c-08 | Konfliktbewusste Offline-Synchronisation mit Ereignis-IDs | 1 | M | WP-M7c-07, WP-M3-24, WP-M3-29, WP-M4-12, WP-M4-26 |
| WP-M7c-09 | Pausen starten und beenden mit Wirkung auf die Disposition | 1 | M | WP-M7c-01, WP-M3-31, WP-M4-08, WP-M4-13, WP-M7a-34 |
| WP-M7c-10 | Fahrermeldungen: Fahrzeugmangel, Straßenhindernis, falsche Haltestelle | 1 | M | WP-M7c-02, WP-M3-43, WP-M3-44 |
| WP-M7c-11 | Trinkgeld als getrennter Posten | 1 | M | WP-M7c-01, WP-M2-29, WP-M2-30, WP-M2-08, WP-M6c-13, WP-M6c-20, WP-M6c-22 |
| WP-M7c-12 | GPS-Plausibilitätsprüfung und Hinweis zur Datenfrische | 1 | S | WP-M7c-01, WP-M3-34 |
| WP-M7c-13 | Dienstplanzeiten und Schichtschluss in der Buchungsprüfung | 1 | M | WP-M7c-01, WP-M2-22, WP-M2-23, WP-M2-15, WP-M4-03, WP-M4-17 |
| WP-M7c-14 | Zusammengeführte Verfügbarkeit aus Werkstatt, Sperre und Abwesenheit | 1 | M | WP-M7c-02, WP-M3-08, WP-M3-09, WP-M4-06 |
| WP-M7c-15 | Werkstattfenster, Reinigung und Ladeereignisse in der Fahrzeugverfügbarkeit | 1 | M | WP-M7c-02, WP-M7c-14, WP-M2-15, WP-M2-16 |
| WP-M7c-16 | Revisionsbewusste Kilometer- und Nutzungsauswertung mit Schichtbericht | 1 | M | WP-M7c-05, WP-M4-41, WP-M4-42, WP-M4-46 |
| WP-M7c-17 | Qualifikationen und Fahrzeugberechtigungen in der Schichtzuweisung | 1 | S | WP-M7c-01, WP-M3-05, WP-M3-19, WP-M4-07 |
| WP-M7c-18 | Vorlagen-Lebenszyklus: Freigabestatus, Version, Gültigkeit, Herausgeber | 1 | M | WP-M7c-02, WP-M3-10, WP-M3-13 |
| WP-M7c-19 | Bedingte Fragen, Grenzwerte, Pflichtfotos und Abhilfemaßnahmen | 1 | M | WP-M7c-18 |
| WP-M7c-20 | Zusatzgruppen nach Jahreszeit, Wetter und Fahrzeugtyp | 1 | M | WP-M7c-19 |
| WP-M7c-21 | Vor-Schicht-Checkliste mit Freigabeworkflow bei kritischem Mangel | 2 | M | WP-M7c-18, WP-M3-14, WP-M3-16, WP-M3-17 |
| WP-M7c-22 | Schichtende- und Übergabecheck mit Anfangs-/Endvergleich | 1 | M | WP-M7c-19, WP-M7c-24, WP-M3-14, WP-M3-17, WP-M3-31 |
| WP-M7c-23 | Prüfpflichten, Erinnerungen und Eskalationen je Mandant | 1 | M | WP-M7c-18, WP-M2-32, WP-M3-52, WP-M3-23, WP-M4-14 |
| WP-M7c-24 | Automatische Mangelmeldung aus nicht bestandener Prüfung | 1 | S | WP-M7c-02, WP-M3-20, WP-M3-43 |
| WP-M7c-25 | Ausnahmen für nichtkritische Mängel mit Rolle, Grund und Frist | 1 | M | WP-M7c-24, WP-M3-08, WP-M3-45 |
| WP-M7c-26 | Medienmetadaten, Prüfsumme und Manipulationserkennung | 1 | M | WP-M7c-02, WP-M3-44, WP-M3-48, WP-M4-21, WP-M4-24 |
| WP-M7c-27 | Medienprüfung und Entfernen von EXIF-Positionsdaten | 1 | M | WP-M7c-26 |
| WP-M7c-28 | Prüfnachweis als signierter PDF-/CSV-Export | 1 | M | WP-M7c-18, WP-M3-14, WP-M4-37 |
| WP-M7c-29 | Offline-Prüfung ohne irrtümliche Verfügbarkeit und idempotenter Abschluss | 2 | M | WP-M7c-08, WP-M7c-24, WP-M3-33 |
| WP-M7c-30 | Auswertung der Checklisten je Fahrzeugklasse | 1 | M | WP-M7c-22, WP-M7c-24, WP-M4-45, WP-M4-46, WP-M4-58 |
| WP-M7c-31 | Barrierefreie Checklisten | 1 | M | WP-M7c-19, WP-M7c-22 |
| WP-M7c-32 | Schadenbereich auf konfigurierbarer Fahrzeugskizze | 1 | M | WP-M7c-02, WP-M3-43, WP-M3-44, WP-M3-21 |
| WP-M7c-33 | Bearbeiter, Verantwortlicher, Fristen, Wiedervorlage und Aufgabenliste | 1 | M | WP-M7c-02, WP-M3-49 |
| WP-M7c-34 | Vollständiger Statusautomat der Schadenakte | 1 | M | WP-M7c-02, WP-M3-47, WP-M3-51 |
| WP-M7c-35 | Werkstatttermine, Ersatzteile, Kostenvoranschläge, Rechnungen, Reparaturnachweise | 1 | M | WP-M7c-34, WP-M7c-26, WP-M4-22, WP-M4-23, WP-M4-32 |
| WP-M7c-36 | Technische Freigabe nach Reparatur mit Folgecheckliste | 1 | M | WP-M7c-34, WP-M7c-35, WP-M3-51, WP-M3-08 |
| WP-M7c-37 | Getrennte Domänen Fahrzeug, Schaden, Verkehrsauftrag über Ereignisse | 1 | M | WP-M7c-36, WP-M1-14, WP-M2-28, WP-M2-25 |
| WP-M7c-38 | Ersatzfahrzeug aus dem Schadenfall aktivieren | 1 | L | WP-M7c-21, WP-M7c-37, WP-M2-28, WP-M2-22, WP-M2-32, WP-M3-22, WP-M3-40, WP-M3-54, WP-M6b-04, WP-M6b-05, WP-M6b-13, WP-M6b-33 |
| WP-M7c-39 | Dublettenvorschlag und manuelle Verknüpfung | 1 | M | WP-M7c-34 |
| WP-M7c-40 | Unfall- und Sicherheitsvorfälle als getrennte, beschränkte Akte | 1 | M | WP-M7c-02, WP-M1-07, WP-M1-08, WP-M1-31, WP-M3-50 |
| WP-M7c-41 | Versicherungs- und Haftungsangaben ohne automatische Schuldzuweisung | 1 | S | WP-M7c-40 |
| WP-M7c-42 | Drittbeteiligte und Zeugen mit begrenztem Zugriff und Löschfrist | 1 | M | WP-M7c-40, WP-M1-33 |
| WP-M7c-43 | Geschützter Export der Schadenakte | 1 | M | WP-M7c-35, WP-M7c-26, WP-M7c-42, WP-M4-37 |
| WP-M7c-44 | Kostenarten und getrennte Rechnungsfreigabe | 1 | M | WP-M7c-35, WP-M4-33, WP-M4-34, WP-M4-35, WP-M4-39 |
| WP-M7c-45 | Wiedervorlagen, Service-Level und Eskalation je Schadenskategorie | 1 | M | WP-M7c-33, WP-M3-52 |
| WP-M7c-46 | Asset-Stamm für kostenrelevante Betriebsmittel | 1 | M | WP-M7c-02, WP-M1-09, WP-M1-08 |
| WP-M7c-47 | Materialübernahme und Rückgabe mit digitaler Bestätigung | 1 | M | WP-M7c-46, WP-M3-24, WP-M3-25 |
| WP-M7c-48 | Standortlager mit Bestand, Mindestbestand, Entnahme und Inventur | 1 | M | WP-M7c-46, WP-M1-09, WP-M1-08 |
| WP-M7c-49 | Versionierte Fahrzeugdokumente mit Gültigkeit | 1 | M | WP-M7c-02, WP-M7c-26, WP-M4-23 |
| WP-M7c-50 | Wartungsverträge und Service-Level externer Werkstätten | 1 | M | WP-M7c-35 |
| WP-M7c-51 | Einkaufsanforderung von Genehmigung bis Rechnungsbezug | 1 | M | WP-M7c-48, WP-M4-22, WP-M4-34, WP-M4-33 |
| WP-M7c-52 | Digitale Fahrzeugübergabe mit Übergaberevision | 1 | M | WP-M7c-22, WP-M7c-47, WP-M3-22, WP-M3-21 |
| WP-M7c-53 | Vertragskalender mit Ablaufwarnung | 1 | M | WP-M7c-49, WP-M7c-50, WP-M7c-14 |
| WP-M7c-54 | Gate-Nachweis M7c und Regressionssuite | – (Enabler) | M | WP-M7c-01 bis WP-M7c-53, WP-M6b-03, WP-M6a-03 |

## Arbeitspakete

### WP-M7c-01 – Detailverträge und Zustandsautomaten M7c
- **Ziel:** Für alle 54 Anforderungen liegen vor Codebeginn geprüfte Verträge vor (Leitlinie „Vertrag vor Code“, Kap. 08 §8.4); die Lücken des Pflichtenhefts für AST, Fahrernachrichten, Fahrtenbuch und Unfallakte sind entschieden oder als Annahme dokumentiert.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - API: OpenAPI-3.1-Ergänzungen unter `api/` für `/v1/inspections`, `/v1/inspections/{id}/submit`, `/v1/driver/shifts/{id}/run`, `/v1/driver/events` (neue Ereignistypen Pause, Notfall, Meldung), `/v1/defects`, `/v1/defects/{id}/triage`, `/v1/repair-orders`, `/v1/vehicles/{id}/release`, `/v1/operator/vehicles`, `/v1/operator/incidents`, `/v1/notifications`, `/v1/reports/jobs`, `/v1/reports/jobs/{id}`; Vorschlag neuer Pfade für Vorlagenverwaltung, Assets, Lager, Einkauf, Fahrzeugdokumente, Werkstattverträge und Übergaben (in Kap. 05 §5.7 nicht enthalten, OP-02).
  - Datenbank: DDL-Entwürfe für die Erweiterung von `checklist_template_revision`, `checklist_step`, `inspection_run`, `inspection_answer`, `defect_case`, `defect_evidence`, `repair_order`, `vehicle_release_decision` und für die neuen AST-Tabellen (Arbeitsnamen, OP-01).
  - Backend: Zustandsautomaten Vorlagenrevision, Schadenakte (neun Status), Reparaturauftrag, Ausnahme, Asset-Ausgabe, Einkaufsanforderung, Übergabe, Notfallalarm; Ereigniskatalog M7c.
  - Konfigurationsparameter: JSON-Schemas (`policy_schema`) für `vehicle_readiness_require_checks`, `workshop_auto_block_severity`, `inventory_min_stock`, `inspection_warning_days`, `maintenance_reminder_km`, `min_battery_soc_pct`, `min_return_soc_pct`, `charge_reservation_min`, `alert_escalation_delay_min`, `vehicle_unavailability_alert_min`, `notification_quiet_hours`, `approval_stage_thresholds`, soweit in M1/M3/M4 noch nicht mit P2-Wertebereich versehen.
  - Konfigurationsparameter: Vorschlag neuer Konfigurationsschlüssel, die in `kataloge.csv` fehlen und dort nach Beschluss nachzutragen sind (Namen, Ebene, Wertebereich jeweils als **neu/zu ergänzen** gekennzeichnet): Aktivierung Trinkgeld je Dienst (OP-08), GPS-Toleranzen (OP-09), Vorlauf/Erinnerungsstufen/Eskalationsschwelle der Prüfpflicht (OP-16), Fristen je Schadenskategorie (OP-26), Sichtbarkeitsfenster der Fahrernachricht (OP-04).
  - Kennzahlen: Ereignisbindung für `Fahrzeugverfügbarkeit`, `Ausfallminuten kritisch`, `Werkstattquote` an die neuen Status und Fenster; Definitionsentwürfe (Zähler, Nenner, Ausschlüsse) der vier in `kataloge.csv` fehlenden Checklisten-Kennzahlen als **neu/zu ergänzen** (OP-20).
  - Oberfläche: Zustandskatalog je neuer oder erweiterter Ansicht in UI-07, UI-09, UI-10, UI-11, UI-12, UI-14, UI-19, UI-20 (leer, Fehler, fehlendes Recht, offline, 412-Konflikt) nach Kap. 06 §6.9; Zuordnung der im Pflichtenheft nicht eigens benannten Ansichten Asset, Lager, Einkauf und Vertragskalender zu einer UI-ID (OP-33).
  - Adapter/Worker: Offline-Vertrag `mobile/driver` (welche Ereignistypen offline erfassbar sind, Konfliktmatrix, Löschregeln der lokalen Ablage) als Grundlage für WP-M7c-07, -08, -29, -42, -47.
- **Detailverträge vorab:** dieses WP erzeugt sie; Eingang sind die Verträge aus WP-M3-01 und WP-M4-01.
- **Akzeptanzkriterien:**
  1. Jede der 54 Anforderungen ist in `arbeitspakete.csv` mit Detailvertrag und mindestens einem positiven Testfall verknüpft.
  2. Die ergänzte OpenAPI besteht `validate_artifacts.py` und die Contract-Test-Pipeline; `operationId`s sind eindeutig, Beispiele schema-gültig.
  3. Jeder Zustandsautomat nennt je Übergang Rolle, Vorbedingung, Ereignis und Negativfall.
  4. Jeder neu vorgeschlagene Pfad, Tabellenname, Konfigurationsschlüssel und Kennzahlname ist als Ergänzung zu `openapi.yaml`, Kap. 04 §4.4 bzw. `kataloge.csv` ausgewiesen; kein WP verwendet einen Namen, der dort weder vorhanden noch als Ergänzung beschlossen ist.
  5. Für Sicherheits-, Geld-, Kapazitäts- und Datentrennungsregeln ist je Anforderung zusätzlich ein negativer und ein konkurrierender Testfall verknüpft (Kap. 08 §8.4).
  6. Für jeden Punkt unter „Offene Punkte“ liegt ein Beschluss oder eine vom Auftraggeber bestätigte Annahme vor; ohne sie beginnt das betroffene WP nicht.
- **Tests:** positiv: Contract-Tests der Beispielpayloads. negativ: je Endpunkt mindestens ein Negativbeispiel (fehlende Rolle, fremder Mandant, veraltete Revision → 412).
- **Abhängigkeiten:** intern: keine.
  - extern: WP-M2-01, WP-M2-32, WP-M2-22, WP-M2-30 (Verträge Nachrichtenversand, Hold/Confirm, Zahlungsautomat)
  - extern: WP-M3-01 (Verträge und Automaten Check, Schaden, Sperr-/Freigabekern)
  - extern: WP-M4-01 (Verträge Beleg, Kostenereignis, Dienstplan)
  - extern: WP-M5-29, WP-M5-31 (P1-Pilotabnahme als Ausgangsstand)
- **Entscheidungen:** D-11 (Notbetrieb, Löschfristen), D-10 (Fahrerdaten), D-06 (Finanzumfang), D-07 (Kostenbewertung Fahrzeugausfall), D-09 (Karten-/Telematikquelle) fließen in die Verträge ein – blockieren den Bau der jeweils betroffenen WPs nicht, aber deren Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Verträge der Einheiten M7a, M7b, M7d; Implementierung.

### WP-M7c-02 – Migration der M7c-Tabellen mit RLS
- **Ziel:** Alle Tabellenerweiterungen und neuen Tabellen dieser Einheit sind per Flyway migriert, mandantengetrennt und unter der Laufzeitrolle getestet.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Datenbank: Expand-Migrationen für `checklist_template_revision`, `checklist_step`, `inspection_run`, `inspection_answer`, `defect_case`, `defect_evidence`, `repair_order`, `vehicle_release_decision`, `vehicle_assignment`, `charging_slot`; neue Tabellen für Assets, Asset-Zuordnung, Lagerartikel, Lagerbewegung, Einkaufsanforderung, Fahrzeugdokument, Werkstattvertrag, Fahrzeugübergabe, Unfallakte, Drittbeteiligte, Ausnahme, Fahrernachricht/Lesebestätigung, Fahrtenbucheintrag und -korrektur, Verfügbarkeits-/Reinigungsfenster, Skizzenvorlage, Dienstleisterstamm, Kostenposition je Schadenfall, Notfallereignis und Fundsachen-Vorgang (soweit nicht über die Incident-Ablage aus M3 bzw. `support_case` gelöst, OP-15) (Arbeitsnamen gemäß WP-M7c-01). Expand-Migrationen zusätzlich für `position_sample` (Qualitätsgrund), `time_event` (Pausenankündigung) und `notification` (Priorität, Schichtbezug), soweit die Verträge aus WP-M7c-01 das verlangen. Jede Tabelle mit Primärschlüssel `(tenant_id,id)`, zusammengesetzten FKs, RLS-Policy über `current_tenant()`, Revision und Audit (Kap. 04 §4.4, §4.5 „Keine lokale Cross-Tenant-Referenz“, „Kein Mandantenkontext → keine Zeile“).
  - Datenbank: Append-only-Schutz (UPDATE/DELETE-Verbot) für Prüfantworten, Evidenzmetadaten, Übergaberevisionen und Lagerbewegungen; eigene Modulzugänge und Spaltenrechte für Unfallakte und Drittbeteiligte (Kap. 04 §4.6).
  - Datenbank: Standardindizes `(tenant_id,state,updated_at)` für Arbeitslisten (Kap. 04 §4.7).
  - Datenbank: Invarianten als Constraint bzw. Domänentransaktion vorbereitet: nicht überlappende gültige Vorlagenrevision je Geltungsbereich (Kap. 04 §4.5 „Nicht überlappende aktive … Version“), Seriennummer eindeutig je Mandant und Assetart, höchstens eine offene Asset-Ausgabe je Asset, Lagerbestand nie negativ, höchstens eine offene Übergabe je Fahrzeug.
  - Adapter/Worker: Zwei-Mandanten-Testfixtures (Fahrzeuge, Fahrer, Schichten, Vorlagen, Schadenfälle, Assets, Lager) für alle Folge-WPs.
- **Detailverträge vorab:** DDL aus WP-M7c-01; Entscheidung OP-01 (Tabellen AST), OP-03 (Unfallakte).
- **Akzeptanzkriterien:**
  1. Migration läuft auf leerer Datenbank und auf einem M5-Bestand mit Daten zweier Mandanten ohne Datenverlust (expand/contract).
  2. Ohne Mandantenkontext liefert jede neue Tabelle keine Zeile; ein FK auf eine Zeile eines anderen Mandanten wird abgelehnt.
  3. UPDATE und DELETE auf die Append-only-Tabellen scheitern unter der Laufzeitrolle.
  4. Die Statusmigration bestehender `defect_case`- und `checklist_template_revision`-Zeilen ist rückwärtskompatibel (expand/contract); die P1-Regressionstests laufen gegen den migrierten Bestand.
  5. Die Laufzeitrolle des Moduls `fleet-safety` hat keinen Lesezugriff auf die Spalten der Unfallakte und der Drittbeteiligten ohne den dafür vorgesehenen Modulzugang.
- **Tests:** positiv: Migration, Backfill fortsetzbar. negativ: Cross-Tenant-FK, fehlender Tenantkontext, UPDATE auf Append-only. konkurrierend: je neuer Invariante ein Test mit zwei parallelen Transaktionen (Kap. 04 §4.5), insbesondere Lagerbestand und Asset-Ausgabe. Bezug: PH-AT-001 (Isolation in Datenbank, API, Suche und Export) wird für jede neue Tabelle wiederholt.
- **Abhängigkeiten:** intern: WP-M7c-01.
  - extern: WP-M1-01, WP-M1-12, WP-M1-46 (Tenantkontext, RLS-Laufzeitbasis, Audit)
  - extern: WP-M2-32 (bestehende Tabellen `notification`, `notification_attempt`)
  - extern: WP-M3-02 (bestehende CHK-/DMG-/DRV-Tabellen)
  - extern: WP-M4-08, WP-M4-06, WP-M4-22, WP-M4-23, WP-M4-32, WP-M4-34, WP-M4-64 (bestehende Tabellen `time_event`, `absence_request`, `document_revision`, `evidence_link`, `approval_step`)
  - extern: UNGEDECKT – bestehende Tabelle `supplier_invoice`
- **Entscheidungen:** D-02 (Isolationsstufe) – blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Fachlogik; Tabellen der Einheit M7d (`shift_offer`, `shift_swap`, `time_correction`).

### WP-M7c-03 – Geschützte Nachrichten des Fahrers an Fahrgast und Leitstelle
- **Ziel:** Der Fahrer sendet aus der Driver-App Nachrichten an den Fahrgast seiner aktuellen Fahrt und an die Leitstelle, ohne dass private Telefonnummern sichtbar werden.
- **Anforderungen:** `LH-6.6-DRV-009`
- **Liefergegenstände:**
  - Backend: Modul `communication-support` – Nachrichtenauftrag mit Bezug zu Schicht, Fahrt und Buchung; Empfängerauflösung serverseitig; Berechtigung nur für zugeordnete Fahrten und nur im betrieblich nötigen Zeitraum.
  - Datenbank: `notification`, `notification_attempt`, `channel_template_revision`; Bezug zu `staff_shift`, `vehicle_run`, `booking`.
  - API: `/v1/notifications` (Senden, Verlauf je Fahrt); Leitstellensicht über `/v1/operator/incidents` bzw. Arbeitsliste.
  - Oberfläche: UI-10 – Nachricht an Fahrgast (Textbausteine und Freitext) und an Leitstelle; UI-07 – eingehende Fahrernachricht mit Fahrt- und Fahrzeugbezug.
  - Ereignisse/Outbox: Fahrernachricht gesendet, zugestellt, fehlgeschlagen.
- **Detailverträge vorab:** Nachrichtenschema, zulässige Kanäle, Sichtbarkeitsfenster, Verhalten bei deaktiviertem Systemkanal.
- **Akzeptanzkriterien:**
  1. Bei aktivem Systemkanal erreicht die Nachricht Fahrgast und Leitstelle; weder Fahrer noch Fahrgast sehen die private Telefonnummer des anderen (DRV-009).
  2. Die API-Antwort und die App-Projektion enthalten keine Kontaktdaten aus `passenger_private` (DRV-009).
  3. Ein Fahrer kann nur Fahrgäste seiner zugeordneten, laufenden oder unmittelbar bevorstehenden Fahrt anschreiben; andere Buchungen werden abgelehnt.
  4. Ist der Systemkanal deaktiviert, zeigt die App das an und bietet keinen Ausweichweg, der Nummern offenlegt.
  5. Jede Nachricht ist mit Absender, Fahrtbezug und Zustellstatus nachvollziehbar.
  6. Nachrichten sind mandantengetrennt; Inhalte werden nach der Aufbewahrungsregel gelöscht und erscheinen nicht in Logs oder Evidenz (D-11).
  7. Leerer Verlauf, Zustellfehler und fehlendes Recht sind in UI-10 und UI-07 als eigene Zustände dargestellt; eine offline verfasste Nachricht ist als „nicht gesendet“ erkennbar.
- **Tests:** positiv: Nachricht an Fahrgast und an Leitstelle. negativ: fremde Buchung, fremder Mandant, Nachricht nach Fahrtende außerhalb des Fensters, Kanal aus. konkurrierend (Datentrennung): zwei Fahrer verschiedener Mandanten senden gleichzeitig – keine Zustellung über die Mandantengrenze.
- **Abhängigkeiten:** intern: WP-M7c-01, WP-M7c-02.
  - extern: WP-M2-32, WP-M2-33 (asynchroner Nachrichtenversand mit Zustellversuchen und Vorlagen)
  - extern: WP-M3-24, WP-M3-26 (Driver-App-Basis und Stoppliste)
- **Entscheidungen:** D-11 (Aufbewahrung der Nachrichteninhalte) – blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Sprachanruf mit Rufnummernmaskierung (nicht gefordert, OP-04); Massenkommunikation (M7b).

### WP-M7c-04 – Priorisierte Betriebsinformationen mit Lesebestätigung
- **Ziel:** Die Leitstelle sendet Betriebsinformationen mit Priorität an Fahrer; der Fahrer bestätigt das Lesen, und die Bestätigung ist der Schicht zugeordnet.
- **Anforderungen:** `LH-6.6-DRV-010`
- **Liefergegenstände:**
  - Backend: Modul `communication-support` – Prioritätsstufen, Lesebestätigung je Empfänger, Zuordnung zur aktiven Schicht; Dedupe je Auftrag und Empfänger.
  - Datenbank: `notification`, `notification_attempt`; Lesebestätigung (Arbeitsname, in WP-M7c-02).
  - API: `/v1/notifications` (Auftrag, Bestätigung, Verlauf je Schicht).
  - Oberfläche: UI-10 – hochpriorisierte Meldung als bestätigungspflichtiger Hinweis, nicht während Fahrbewegung zu quittieren (Kap. 06 §6.7); UI-07 – Lesestatus je Fahrer.
  - Konfigurationsparameter: `notification_quiet_hours` (Sicherheitsalarme ausgenommen).
  - Adapter/Worker: Push-Kanal des Adapters `notification`.
- **Detailverträge vorab:** Prioritätsstufen und deren Darstellung; Schema der Lesebestätigung; Verhalten offline.
- **Akzeptanzkriterien:**
  1. Eine hochpriorisierte Meldung ist in der Schichtansicht des Fahrers und in der Leitstelle mit Sendezeit, Zustellstatus und Lesezeit nachvollziehbar (DRV-010).
  2. Die Lesebestätigung wird genau einmal gespeichert, auch bei wiederholtem Senden aus der App.
  3. Meldungen außerhalb einer Schicht respektieren `notification_quiet_hours`; hochpriorisierte Sicherheitsmeldungen nicht.
  4. Offline bestätigte Meldungen werden nach Synchronisation mit Gerätezeit und Serverempfangszeit geführt.
  5. Nur adressierte Fahrer des eigenen Mandanten erhalten die Meldung; die Priorität ist in App und Push erkennbar (DRV-010).
- **Tests:** positiv: Senden, Zustellen, Bestätigen, Verlauf. negativ: Bestätigung durch nicht adressierten Fahrer, fremder Mandant. konkurrierend: doppelte Bestätigung von zwei Geräten.
- **Abhängigkeiten:** intern: WP-M7c-02, WP-M7c-03.
  - extern: WP-M2-32 (Nachrichtenversand)
  - extern: WP-M3-24 (Driver-App-Basis mit Outbox)
- **Entscheidungen:** D-10 (Auswertung von Lesezeiten als Fahrerdaten, Mitbestimmung) – blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Zielgruppen-Massenversand (M7b, AT-17); Schichtstart-Erinnerungen (M7d, AT-63).

### WP-M7c-05 – Fahrtenbuch mit Export und Änderungsprotokoll
- **Ziel:** Je Fahrzeug und Schicht entsteht ein Fahrtenbuch aus betrieblichen Strecken, Zeiten und Dienstereignissen, das exportiert werden kann und jede Änderung protokolliert.
- **Anforderungen:** `LH-6.6-DRV-011`
- **Liefergegenstände:**
  - Backend: Modul `fleet-safety` – Fahrtenbuchprojektion aus Fahrerereignissen, `vehicle_run`, Kilometerständen und Zeitereignissen; Korrektur nur als neuer Eintrag mit Bezug, Grund und Akteur.
  - Datenbank: Lesezugriff auf `vehicle_run`, `staff_shift`, `position_sample`, `time_event`; Fahrtenbucheintrag und Korrektur (Arbeitsname); `audit_event`.
  - API: `/v1/driver/shifts/{id}/run` (Sicht des Fahrers), `/v1/reports/jobs`, `/v1/reports/jobs/{id}` (Export), `export_job`.
  - Oberfläche: UI-10 – eigenes Fahrtenbuch der Schicht; UI-11 – Fahrtenbuch je Fahrzeug mit Änderungsprotokoll und Export.
- **Detailverträge vorab:** Definition „relevante betriebliche Strecken“ (OP-05); Exportformat; Korrekturautomat.
- **Akzeptanzkriterien:**
  1. Das Fahrtenbuch einer Schicht ist als Datei exportierbar und enthält Strecken, Zeiten und Dienstereignisse (DRV-011).
  2. Jede nachträgliche Änderung erscheint im Änderungsprotokoll mit altem und neuem Wert, Akteur, Zeit und Grund; der ursprüngliche Eintrag bleibt erhalten (DRV-011).
  3. Der Export enthält Datenstand und Prüfsumme und wird als wiederholsicherer Job ausgeführt.
  4. Ein Fahrer sieht nur eigene Schichten; Exportrechte sind rollengebunden.
- **Tests:** positiv: Schicht mit Leerfahrt, Fahrgastfahrt, Pause → vollständiges Buch und Export. negativ: Änderung ohne Grund, Export durch unberechtigte Rolle, fremder Mandant. konkurrierend: zwei gleichzeitige Korrekturen desselben Eintrags → eine gewinnt, die andere erhält 412. Bezug: AT-53 (Export und Fristen, Gate M5) als Muster für Exportberechtigung.
- **Abhängigkeiten:** intern: WP-M7c-02.
  - extern: WP-M3-29, WP-M3-34 (Fahrerereignisse, Positionserfassung)
  - extern: WP-M4-41, WP-M4-42 (Kilometerstände mit Herkunft, Klassifikation der Fahrzeugkilometer)
- **Entscheidungen:** D-10 (Fahrerdaten, Zweckbindung), D-11 (Aufbewahrung) – blockieren nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** steuerliches Fahrtenbuch für Privatnutzung (nicht gefordert); Auswertung über Schichten hinweg (WP-M7c-16).

### WP-M7c-06 – Fahrer-Notfallknopf und Unfall-/Notfallprozess
- **Ziel:** Der Fahrer löst mit einem Knopf einen priorisierten Alarm an die definierten Leitstellenrollen aus und erhält sofort Notruf- und Absicherungshinweise; die Leitstelle bearbeitet den Alarm in einem Rückmeldeworkflow.
- **Anforderungen:** `LH-6.6-DRV-012`, `LH-6.22-DMG-021`
- **Liefergegenstände:**
  - Backend: Modul `fleet-safety` mit `communication-support` – Notfallereignis mit Ort, Fahrzeug, Schicht, laufender Fahrt und Fahrgastzahl; Alarm an Rollen laut Alarmregel; Rückmeldeworkflow (angenommen, in Bearbeitung, abgeschlossen) mit Rückmeldung an den Fahrer; dokumentierte Eskalationskontakte je Mandant.
  - Datenbank: `business_alert`, `alert_policy_revision`, `alert_acknowledgement`; Notfallereignis als Incident (Ablage gemäß OP-02 aus M3).
  - API: `/v1/driver/events` (Ereignistyp Notfall), `/v1/operator/incidents`.
  - Oberfläche: UI-10 – Notfallknopf von jeder Fahrtansicht in einem Schritt erreichbar; Notfallseite mit Notrufhinweis, Absicherungshinweisen, Eskalationskontakten, auch offline lesbar; UI-07 – Alarm oben in der Arbeitsliste mit Ort und Kontext.
  - Ereignisse/Outbox: Notfall ausgelöst, angenommen, eskaliert, abgeschlossen.
  - Konfigurationsparameter: `alert_escalation_delay_min` (kritische Ereignisse ohne Verzögerung).
- **Detailverträge vorab:** Zustandsautomat Notfallalarm; Inhalt der Notfallseite je Mandant; Verhalten ohne Verbindung (Hinweis sichtbar, Alarm in Outbox, deutliche Anzeige „nicht übermittelt“).
- **Akzeptanzkriterien:**
  1. Der Alarm erscheint in der Leitstelle mit Ort, Fahrzeug, Fahrer und Fahrtkontext und durchläuft den Rückmeldeworkflow bis zur Rückmeldung an den Fahrer (DRV-012).
  2. Das System setzt keinen automatischen Behördennotruf ab; die App weist ausdrücklich auf den amtlichen Notruf hin (DRV-012, DMG-021).
  3. Die Notfallinformation ist aus der Fahrtansicht ohne Umweg in einem Schritt erreichbar (DMG-021).
  4. Das Ereignis steht in der Leitstelle vor allen nicht kritischen Einträgen und wird ohne Eskalationsverzögerung zugestellt (DMG-021).
  5. Unquittierte Alarme eskalieren an die nächste Rolle gemäß `alert_policy_revision`.
  6. Ohne Verbindung sieht der Fahrer, dass der Alarm noch nicht übermittelt ist; der Alarm wird nach Wiederverbindung genau einmal wirksam.
  7. Die Notfallseite zeigt Notrufhinweis, Absicherungshinweise und die je Mandant dokumentierten Eskalationskontakte; fehlen Kontakte in der Konfiguration, wird das in der Leitstelle als Konfigurationsmangel angezeigt statt eine leere Seite auszuliefern (DMG-021).
  8. Den Alarm erhalten ausschließlich die in der Alarmregel definierten Leitstellenrollen des eigenen Mandanten; Auslösung, Annahme, Eskalation und Abschluss stehen mit Akteur und Zeit im Audit (DRV-012).
- **Tests:** positiv: Alarm mit Ort und Workflow. negativ: Alarm ohne aktive Schicht, Empfänger ohne Rolle sieht ihn nicht, fremder Mandant. konkurrierend (Sicherheit): doppelt ausgelöster Alarm von zwei Sync-Versuchen → ein Alarm; zwei Disponenten nehmen gleichzeitig an → genau eine Annahme. Offlinefall: Alarm im Flugmodus → Hinweisseite lesbar, Anzeige „nicht übermittelt“, nach Wiederverbindung ein Alarm. Regression AT-28 (Leitstelle alarmiert).
- **Abhängigkeiten:** intern: WP-M7c-01, WP-M7c-02.
  - extern: WP-M3-52, WP-M3-35, WP-M3-34 (Alarm-Grundlage, Livekanal, Positionserfassung)
- **Entscheidungen:** D-11 (Notbetriebs-/Kontaktprozess und berechtigte Rollen liefert der Betreiber) – blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Unfallakte und Drittbeteiligte (WP-M7c-40, WP-M7c-42); Anbindung an Behördennotruf (ausdrücklich ausgeschlossen).

### WP-M7c-07 – Offline-Vorhaltung bestätigter Aufträge und Karte
- **Ziel:** Bei Funkunterbrechung setzt der Fahrer die zuletzt bestätigte Route sicher fort, weil Aufträge und Kartenausschnitt lokal verschlüsselt vorliegen.
- **Anforderungen:** `LH-6.6-DRV-014`
- **Liefergegenstände:**
  - Adapter/Worker: `mobile/driver` – verschlüsselte lokale Ablage der letzten serverbestätigten Planrevision (Stoppliste, Zeitfenster, Unterstützungshinweise) und des Kartenausschnitts der Route; Löschen bei Schichtende und Abmeldung.
  - Oberfläche: UI-10 – Offlinebanner mit Zeit des letzten Plans (Kap. 06 §6.7); Kennzeichnung, dass neue Aufträge erst nach Verbindung erscheinen.
  - Backend: `/v1/driver/shifts/{id}/run` liefert Planrevision und Kartenbezug für die Vorhaltung.
- **Detailverträge vorab:** Umfang und Lizenz der Offlinekarte (OP-06); Schlüsselverwaltung auf verwalteten Geräten; Löschregeln.
- **Akzeptanzkriterien:**
  1. Bei unterbrochener Verbindung bleiben die zuletzt bestätigten Stopps mit Reihenfolge und Karte nutzbar; die Fahrt kann fortgesetzt werden (DRV-014).
  2. Die lokale Ablage ist verschlüsselt; ohne Anmeldung sind die Daten auf dem Gerät nicht lesbar (DRV-014).
  3. Offline entsteht keine neue verbindliche Buchung, Fahrzeugfreigabe oder Sperraufhebung (Kap. 01 §1.5).
  4. Der Stand der Daten (Planrevision, Zeit) ist jederzeit sichtbar.
  5. Nach Schichtende sind Fahrgast- und Unterstützungsdaten vom Gerät entfernt.
  6. Reicht der vorgehaltene Kartenausschnitt nicht aus oder ist keine Karte geladen, bleibt die Stoppliste mit Adressen nutzbar und die App zeigt den Mangel an.
- **Tests:** positiv: Flugmodus während Fahrt, Fortsetzung bis zum letzten bestätigten Stopp. negativ: Zugriff auf lokale Ablage ohne Sitzung; Plan einer fremden Schicht nicht vorhanden. Gerätetest auf den Zielgeräten des Spikes WP-M0-28. Regression AT-11 (Offline Driver App: letzte gültige Route sichtbar).
- **Abhängigkeiten:** intern: WP-M7c-01.
  - extern: WP-M3-24, WP-M3-26, WP-M3-28 (Driver-App-Basis mit verschlüsselter Outbox, Stoppliste, Navigation)
- **Entscheidungen:** D-09 (Karten-/Routinganbieter, Offline-Nutzungsrecht) – blockiert den Bau des Kartenteils; D-11 (Notbetrieb) – blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Offline-Neuberechnung von Routen; Synchronisation der Offline-Änderungen (WP-M7c-08).

### WP-M7c-08 – Konfliktbewusste Offline-Synchronisation mit Ereignis-IDs
- **Ziel:** Offline erfasste Änderungen werden nach Wiederverbindung genau einmal verarbeitet; Konflikte mit einer inzwischen neueren Planrevision werden erkannt und sichtbar gemacht.
- **Anforderungen:** `LH-6.6-DRV-015`
- **Liefergegenstände:**
  - Backend: `/v1/driver/events` – Verarbeitung mit `device_event_id`, letzter bekannter Serverrevision, Erfassungszeit und Empfangszeit; Konfliktklassen (bereits verarbeitet, veraltete Planrevision, fachlich nicht mehr zulässig) mit eindeutiger Antwort.
  - Datenbank: `inbox`, `idempotency_record`, `outbox`.
  - Adapter/Worker: `mobile/driver` – Reihenfolgetreue Wiedergabe der Queue, Wiederholung mit Backoff, Anzeige je Ereignis „lokal“, „synchronisiert“, „Konflikt“.
  - Oberfläche: UI-10 – Konfliktliste mit nächstem Handlungsschritt.
- **Detailverträge vorab:** Konfliktmatrix je Ereignistyp; Contract-Test „Offline-Check-in auf alter Planrevision“ (Kap. 05 §5.8).
- **Akzeptanzkriterien:**
  1. Ein doppelt übermittelter Check-in wird genau einmal verarbeitet; die zweite Übermittlung erhält das gespeicherte Ergebnis (DRV-015).
  2. Ein Check-in auf einer inzwischen ersetzten Planrevision wird nicht still übernommen, sondern als Konflikt mit Grund zurückgemeldet.
  3. Gerätezeit und Serverempfangszeit bleiben getrennt gespeichert; GPS-Messzeit gilt nicht als Serverzeit.
  4. Die Reihenfolge der Ereignisse einer Schicht bleibt nach der Synchronisation erhalten.
  5. Die Antwort je Ereignis ist `APPLIED`, `DUPLICATE` oder `REVIEW_REQUIRED` (Kap. 05 §5.5); kein verspätetes Ereignis hebt eine aktuelle Schadenssperre auf (DRV-015).
- **Tests:** positiv: Offline-Folge Ankunft, Check-in, Check-out → einmal wirksam. negativ: gleiches `device_event_id` mit anderem Inhalt → Ablehnung; Ereignis nach Schichtende. konkurrierend: dieselbe Queue von zwei App-Instanzen; 100 identische Wiederholungen. Regression: E2E-069 (Offline Tankbeleg, WP-M4-26) und AT-46 (Offline-Zeiterfassung, WP-M4-12) bleiben grün. Bezug: AT-11 (Events dedupliziert), PH-AT-011 (unterschiedlicher Request bei gleichem Idempotenzschlüssel), PH-AT-017 (Reihenfolge und Verspätung von Events), E2E-104 (Systemausfall und Nachlauf); Contract-Test „Offline-Check-in auf alter Planrevision“ (Kap. 05 §5.8).
- **Abhängigkeiten:** intern: WP-M7c-07.
  - extern: WP-M3-24, WP-M3-29 (Fahrerereignisse und Outbox der Driver-App)
  - extern: WP-M4-12, WP-M4-26 (Offline-Stempel und Offline-Beleg als mitzuprüfende Ereignisarten)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Offline-Prüfung (WP-M7c-29); Offline-Zeitkorrekturen (M7d).

### WP-M7c-09 – Pausen starten und beenden mit Wirkung auf die Disposition
- **Ziel:** Der Fahrer kündigt Pausen an, startet und beendet sie; die Disposition weist während einer aktiven Pause keine unzulässige neue Fahrt zu und berücksichtigt angekündigte Pausen.
- **Anforderungen:** `LH-6.6-DRV-016`
- **Liefergegenstände:**
  - Backend: Modul `fleet-safety` – Pausenstatus der Schicht (angekündigt, aktiv, beendet) als Verfügbarkeitsrestriktion; synchrone Prüfung im Dispositions-Commit des Moduls `booking-dispatch`.
  - Datenbank: `time_event` (Pause Beginn/Ende), `staff_shift`, `vehicle_run`.
  - API: `/v1/driver/events` (Ereignistypen Pause), `/v1/time-events`, `/v1/operator/vehicles` (Pausenstatus).
  - Oberfläche: UI-10 – Pause ankündigen/starten/beenden, nur wenn keine Fahrgäste an Bord sind; UI-07 – Pausenstatus je Fahrzeug.
  - Ereignisse/Outbox: Pause angekündigt, begonnen, beendet.
- **Detailverträge vorab:** Zustandsautomat Pause; Regel „unzulässige Zuweisung“ (OP-07); Abgleich mit der geplanten Pause der Schicht.
- **Akzeptanzkriterien:**
  1. Während einer aktiven Pause erhält das Fahrzeug keine neue Zuweisung, deren Abholung in die Pause fällt (DRV-016).
  2. Eine angekündigte Pause wird bei der Angebotsbildung als nicht verfügbare Zeit behandelt (DRV-016).
  3. Eine Pause kann nicht gestartet werden, solange Fahrgäste an Bord sind oder ein zugesagter Stopp in die Pause fiele; die App nennt den Grund.
  4. Pausenbeginn und -ende stehen als Ist-Zeitereignisse zur Verfügung, getrennt vom Schicht-Soll.
- **Tests:** positiv: Pause ohne offene Stopps. negativ: Pausenstart mit Fahrgast an Bord; Zuweisung in aktive Pause. konkurrierend (Kapazität): Pausenstart und Buchungs-Confirm auf dasselbe Fahrzeug in zwei parallelen Transaktionen – in keiner Serialisierung entsteht eine Zusage in die aktive Pause. Regression AT-45 (Soll-/Ist-Zeiten mit Pause), AT-40 (Ruhe-/Pausenkonflikt). Offlinefall: offline gestartete Pause wird nach Synchronisation mit Gerätezeit übernommen, eine inzwischen erfolgte Zuweisung erscheint als Konflikt.
- **Abhängigkeiten:** intern: WP-M7c-01.
  - extern: WP-M3-31 (Fahrzeug- und Dienststatus)
  - extern: WP-M4-08, WP-M4-13 (Zeitereignisse, Pausenhinweise)
  - extern: WP-M7a-34 (Pausenbedingungen in der Disposition)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** arbeitszeitrechtliche Bewertung der Pausen und Zeitkonten (M7d); geplante Pausen im Dienstplan (WP-M7c-13).

### WP-M7c-10 – Fahrermeldungen: Fahrzeugmangel, Straßenhindernis, falsche Haltestelle
- **Ziel:** Der Fahrer meldet drei Arten von Beobachtungen; jede Meldung landet im Operator als zugeordneter Vorgang.
- **Anforderungen:** `LH-6.6-DRV-017`
- **Liefergegenstände:**
  - Backend: Meldungsannahme mit Art, Ort, Zeit, Fahrzeug, Schicht, optional Foto; Weiterleitung: Fahrzeugmangel → `defect_case`, Straßenhindernis und falsche Haltestelle → Vorgang der Leitstelle bzw. Geodatenpflege.
  - Datenbank: `defect_case`, `defect_evidence`; Incident/Vorgang gemäß M3 OP-02; Bezug auf `stop_revision`.
  - API: `/v1/driver/events` (Ereignistyp Meldung), `/v1/defects`, `/v1/operator/incidents`.
  - Oberfläche: UI-10 – Meldedialog mit drei Arten, höchstens wenige Schritte, nicht während Fahrbewegung; UI-07 bzw. UI-17 – Vorgang mit Meldebezug.
- **Detailverträge vorab:** Vorgangstypen und zuständige Rollen je Meldeart; Bezug zur Haltepunktrevision.
- **Akzeptanzkriterien:**
  1. Jede Meldung ist im Operator genau einem Vorgang mit eindeutiger Referenz zugeordnet (DRV-017).
  2. Ein Fahrzeugmangel erzeugt eine Schadenakte nach dem bestehenden Meldeweg; es entsteht kein paralleler Mangelbestand.
  3. Eine Meldung „falsche Haltestelle“ verweist auf die betroffene `stop_revision` und ändert diese nicht automatisch.
  4. Der Fahrer sieht den Status seiner Meldung.
  5. Eine offline erfasste Meldung (mit Foto) wird nach Wiederverbindung genau einmal übermittelt; bis dahin ist sie als „lokal“ gekennzeichnet (DRV-017).
- **Tests:** positiv: je Meldeart ein Durchlauf. negativ: Meldung ohne aktive Schicht, fremder Mandant, ungültige Haltepunktreferenz. konkurrierend: doppelte Übermittlung derselben Meldung → ein Vorgang. Regression AT-33 (Schadenfall mobil).
- **Abhängigkeiten:** intern: WP-M7c-02.
  - extern: WP-M3-43, WP-M3-44 (mobile Schadenerfassung, Schadenakte)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** redaktionelle Haltepunktfreigabe (WP-M7a-25); Sperrbereiche aus Hindernissen (WP-M7a-21).

### WP-M7c-11 – Trinkgeld als getrennter Posten
- **Ziel:** Ist das Angebot aktiviert, kann ein Fahrgast freiwillig Trinkgeld geben; es erscheint auf Quittung und Auszahlung als eigener Posten und nie als Teil des Beförderungsentgelts.
- **Anforderungen:** `LH-6.6-DRV-018`
- **Liefergegenstände:**
  - Backend: Modul `fare-payment` – Trinkgeld als unveränderliche Preiskomponente „freiwilliges Trinkgeld“ (Kap. 03, `price_components`) mit Begünstigtem; Aktivierung je Dienst; eigener Zahlungsvorgang nach Fahrtende.
  - Datenbank: `fare_component`, `payment_intent`, `payment_event`, `economic_event`, `settlement_line`.
  - API: `/v1/bookings` (Trinkgeld nach Fahrtende), Quittungsprojektion.
  - Oberfläche: UI-04 – Trinkgeldangebot nach Fahrtende, klar als freiwillig gekennzeichnet; UI-10 – Fahrer sieht erhaltenes Trinkgeld der Schicht (Umfang gemäß OP-08).
- **Detailverträge vorab:** Aktivierungsschalter (kein Parameter in `kataloge.csv`, OP-08); Steuer- und Auszahlungsbehandlung; Quittungsposition.
- **Akzeptanzkriterien:**
  1. Quittung und Auszahlungsnachweis führen Trinkgeld als separaten Posten, getrennt vom Beförderungsentgelt (DRV-018).
  2. Bei deaktiviertem Angebot wird kein Trinkgeld angeboten und die API lehnt einen Trinkgeldbetrag ab (DRV-018).
  3. Ein fehlgeschlagener Trinkgeldeinzug verändert weder Buchungs- noch Zahlungsstatus der Fahrt.
  4. Beträge sind ganzzahlig in kleinster Einheit; eine Korrektur erfolgt nur über Gegenereignis.
  5. Vor der Bestätigung sieht der Fahrgast Betrag, Freiwilligkeit und die Trennung vom Fahrpreis; das Trinkgeld verändert weder Fahrpreis noch Tarifkomponenten der Buchung (DRV-018).
- **Tests:** positiv: Trinkgeld nach Fahrtende, Quittung, Auszahlungsposten. negativ: Angebot deaktiviert, negativer Betrag, Trinkgeld auf fremde Buchung. konkurrierend (Geld): doppelt gesendetes Trinkgeld mit gleichem Idempotenzschlüssel → ein Einzug; PSP-Timeout → Zustand bleibt unbestimmt bis Abgleich. Bezug: PH-AT-011 (Idempotenzschlüssel mit abweichendem Request → Ablehnung).
- **Abhängigkeiten:** intern: WP-M7c-01.
  - extern: WP-M2-29, WP-M2-30, WP-M2-08 (PSP-Adapter, Zahlungsautomat, Tarifkomponenten)
  - extern: WP-M6c-13, WP-M6c-20, WP-M6c-22 (Auszahlungs- und Clearingposten für Begünstigte)
- **Entscheidungen:** D-03 (Geldfluss/Merchant), D-06 (Finanzumfang) – blockieren die Produktivschaltung; die Auszahlungsart an Fahrer blockiert den Bau des Auszahlungsteils (OP-08).
- **Größe:** M
- **Nicht im Umfang:** Lohnabrechnung des Trinkgelds; Bartrinkgeld.

### WP-M7c-12 – GPS-Plausibilitätsprüfung und Hinweis zur Datenfrische
- **Ziel:** Unplausible oder veraltete Positionen werden markiert und nie unbemerkt als Echtzeit angezeigt.
- **Anforderungen:** `LH-6.6-DRV-019`
- **Liefergegenstände:**
  - Backend: Plausibilitätsprüfung beim Positionsempfang (Genauigkeit, Sprung, unmögliche Geschwindigkeit, Messzeit gegen Empfangszeit); Qualitätskennzeichen je Messung.
  - Datenbank: `position_sample` (Mess-/Empfangszeit, Qualität).
  - API: `/v1/operator/vehicles` (Position mit Alter und Qualitätskennzeichen).
  - Oberfläche: UI-10 – Hinweis in der Driver-App bei ungeeigneter Position; UI-07 – markierte Position mit Standortalter; Fahrgastsicht UI-04 zeigt keine als unplausibel markierte Position als live.
  - Konfigurationsparameter: `eta_max_data_age_sec` (Planungsdefault Standortalter ≤ 30 s im Normalbetrieb, Kap. 01 §1.7).
- **Detailverträge vorab:** Toleranzwerte und deren Parameter (OP-09); Qualitätsklassen.
- **Akzeptanzkriterien:**
  1. Eine Position außerhalb der Toleranz wird in Driver-App und Leitstelle markiert und nicht als Echtzeitposition ausgegeben (DRV-019).
  2. Bei Überschreitung des Höchstalters zeigt die Oberfläche das Alter an; die ETA nutzt den Fallback (DRV-019).
  3. Markierte Messungen bleiben mit Qualitätsgrund gespeichert und fließen nicht in Kilometerwerte ein.
- **Tests:** positiv: gute Messfolge unmarkiert. negativ: Sprung, veraltete Messung, Messzeit in der Zukunft. Bezug: E2E-076 (Fehlerhafte GPS-Daten, M8) nutzt dasselbe Qualitätskennzeichen. negativ zusätzlich: Position mit Fahrzeug-/Schichtbezug eines fremden Mandanten wird abgelehnt. Regression AT-10 (GPS-Ausfall: Veraltet-Kennzeichnung, keine fiktive Liveposition).
- **Abhängigkeiten:** intern: WP-M7c-01.
  - extern: WP-M3-34 (Positionserfassung und Standortalter)
- **Entscheidungen:** D-09 (Telematikquelle) – blockiert nur die Produktivschaltung.
- **Größe:** S
- **Nicht im Umfang:** Ausschluss aus ETA-Training (M8); Live-Position für Fahrgäste (WP-M7a-18).

### WP-M7c-13 – Dienstplanzeiten und Schichtschluss in der Buchungsprüfung
- **Ziel:** Dienstpläne führen Einsatzzeiten, geplante Pausen und Depot; Buchungen über den genehmigten Schichtschluss hinaus werden verhindert oder nur mit gesonderter Freigabe zugelassen.
- **Anforderungen:** `LH-6.8-FLT-005`
- **Liefergegenstände:**
  - Backend: Modul `staff-time` liefert Einsatzzeit, geplante Pausen und Depot je Schicht als Leseport; `booking-dispatch` prüft den Schichtschluss im Hold-/Confirm-Commit; gesonderte Freigabe als begründete, rollengebundene Entscheidung.
  - Datenbank: `staff_shift`, `roster_revision`, `vehicle_run`, `capacity_hold`; Freigabeentscheidung im `audit_event`.
  - API: `/v1/rosters`, `/v1/runs/{id}/revisions` (Freigabe mit Begründung).
  - Oberfläche: UI-12 – Einsatzzeit, Pausen, Depot je Dienst; UI-07 – Konfliktanzeige „über Schichtschluss“ mit Freigabeaktion.
  - Kennzahlen: Ablehnungsgrund Schichtende für `Personalmangelablehnungen` (Code aus WP-M4-49).
- **Detailverträge vorab:** Regel, wann „verhindert“ und wann „separat freigegeben“ gilt, sowie freigebende Rolle (OP-10).
- **Akzeptanzkriterien:**
  1. Eine Buchung, deren Ausstieg nach dem genehmigten Schichtschluss läge, wird nicht automatisch zugesagt (FLT-005).
  2. Mit gesonderter Freigabe durch die berechtigte Rolle wird sie zugesagt; Freigebender, Grund und Zeitpunkt sind protokolliert (FLT-005).
  3. Geplante Pausen und Depot sind im Dienstplan sichtbar und stehen der Disposition zur Verfügung.
  4. Eine Verkürzung der Schicht nach Zusage erzeugt einen sichtbaren Konflikt statt stiller Stornierung.
  5. Eine wegen Schichtschluss nicht zugesagte Anfrage wird mit normiertem Ablehnungsgrund erfasst und nicht als Kapazitätsablehnung gezählt.
- **Tests:** positiv: Buchung innerhalb der Schicht; Freigabe über Schichtschluss. negativ: Zusage über Schichtschluss ohne Freigabe; Freigabe durch nicht berechtigte Rolle. konkurrierend (Kapazität): Schichtverkürzung und Confirm parallel – keine Zusage außerhalb der dann gültigen Schicht ohne Konfliktanzeige. Bezug: E2E-081 (Fehlender Fahrer: Ablehnung primär Personalmangel).
- **Abhängigkeiten:** intern: WP-M7c-01.
  - extern: WP-M2-22, WP-M2-23, WP-M2-15 (Hold und Confirm, Fahrzeugkandidatenfilter)
  - extern: WP-M4-03, WP-M4-17 (Schichtmodell mit Pausenplan, Fahrerschicht und Disponierbarkeit)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Schichtangebote, Tausch und Vorlagen (M7d); Rückkehr- und Abstellortregeln (WP-M7a-35).

### WP-M7c-14 – Zusammengeführte Verfügbarkeit aus Werkstatt, Sperre und Abwesenheit
- **Ziel:** Es gibt eine einzige Verfügbarkeitsaussage je Fahrzeug und Fahrer, die Werkstatt, technische Sperre und Fahrerabwesenheit zusammenführt; ein gesperrtes Fahrzeug erhält keine Fahrt.
- **Anforderungen:** `LH-6.8-FLT-006`
- **Liefergegenstände:**
  - Backend: Modul `fleet-safety` – Verfügbarkeitsdienst, der aktive Sperren, Werkstattaufenthalte (`repair_order`), abgelaufene Pflichtnachweise und Fahrerabwesenheit (`absence_request`, nur als Verfügbarkeitsrestriktion ohne Grund) zu einer Aussage mit Ursachenliste vereint; Nutzung durch `assertDispatchable` des Sperrkerns.
  - Datenbank: `vehicle`, `resource_release`, `repair_order`, `vehicle_assignment`, `absence_request`, `staff_shift`.
  - API: `/v1/operator/vehicles` (Verfügbarkeit mit Ursache, ohne Abwesenheitsgrund).
  - Oberfläche: UI-07 und UI-11 – Verfügbarkeitsstatus mit Ursache und voraussichtlichem Ende.
  - Kennzahlen: `Fahrzeugverfügbarkeit`, `Ausfallminuten kritisch` (Ursache mitgeführt).
  - Konfigurationsparameter: `vehicle_unavailability_alert_min`, `absence_substitution_mode`.
- **Detailverträge vorab:** Ursachenkatalog und Vorrang; Schnittstelle zum Abwesenheitsmodul ohne Diagnosedaten.
- **Akzeptanzkriterien:**
  1. Ein gesperrtes Fahrzeug erhält weder automatisch noch manuell eine Fahrt (FLT-006).
  2. Die Verfügbarkeit nennt alle gleichzeitig wirksamen Ursachen; das Ende einer Ursache macht das Fahrzeug nicht verfügbar, solange eine andere besteht.
  3. Fällt der Fahrer aus, ist der Dienst nicht disponierbar; die Leitstelle sieht Ersatzbedarf und betroffene Fahrten (Zuarbeit zu AT-44, Abnahme in M7d).
  4. Die Leitstelle sieht bei Fahrerabwesenheit keinen Abwesenheitsgrund.
- **Tests:** positiv: je Ursache ein Fall. negativ: manuelle Zuweisung auf gesperrtes Fahrzeug wird abgelehnt. konkurrierend (Sicherheit): Sperre und Zuweisung in zwei parallelen Transaktionen – keine Zusage auf das gesperrte Fahrzeug (Regression PH-AT-020). Ermöglicht den Sperrteil von E2E-071.
- **Abhängigkeiten:** intern: WP-M7c-02.
  - extern: WP-M3-08, WP-M3-09 (Sperr- und Freigabekern, Readiness-Gate)
  - extern: WP-M4-06 (Abwesenheit als Verfügbarkeitsrestriktion)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** geplante Fenster für Werkstatt, Reinigung und Laden (WP-M7c-15); Ersatzbesetzung (M7d).

### WP-M7c-15 – Werkstattfenster, Reinigung und Ladeereignisse in der Fahrzeugverfügbarkeit
- **Ziel:** Geplante Werkstatt-, Reinigungs- und Ladezeiten sind als Fenster am Fahrzeug hinterlegt und stehen der Disposition nicht als Fahrkapazität zur Verfügung.
- **Anforderungen:** `LH-I.10-AST-008`
- **Liefergegenstände:**
  - Backend: Verfügbarkeitsfenster je Fahrzeug mit Art (Werkstatt, Reinigung, Laden) und Zeitraum; Übergabe an den Fahrzeugkandidatenfilter und den Routing-Worker als nicht verfügbare Zeit.
  - Datenbank: `repair_order` (Termin), `charging_slot` (Ladefenster/SoC), Reinigungsfenster (Arbeitsname); `vehicle_run`.
  - API: `/v1/operator/vehicles`, `/v1/repair-orders`.
  - Oberfläche: UI-11 – Fensterplanung je Fahrzeug mit Konfliktanzeige gegen zugesagte Fahrten.
  - Konfigurationsparameter: `charge_reservation_min`, `min_battery_soc_pct`, `min_return_soc_pct`.
  - Kennzahlen: `Werkstattquote` (geplante und ungeplante Stunden getrennt), `Fahrzeugverfügbarkeit`.
- **Detailverträge vorab:** Fenstermodell und Überlappungsregel; Verhalten, wenn ein Fenster auf bereits zugesagte Fahrten trifft.
- **Akzeptanzkriterien:**
  1. Eine terminierte Werkstattstunde steht der Disposition nicht als reguläre Fahrkapazität zur Verfügung; in diesem Zeitraum entsteht kein Angebot für das Fahrzeug (AST-008).
  2. Reinigungs- und Ladefenster wirken gleich; der Ladepuffer `charge_reservation_min` wird eingerechnet (AST-008).
  3. Ein neues Fenster, das zugesagte Fahrten berührt, wird nicht still angelegt, sondern zeigt die betroffenen Fahrten.
  4. Geplante Fenster zählen nicht als ungeplanter Ausfall in `Werkstattquote`.
- **Tests:** positiv: Fenster anlegen, Angebot außerhalb des Fensters. negativ: Fenster mit Ende vor Beginn, Fenster auf fremdem Fahrzeug. konkurrierend (Kapazität): Fensteranlage und Buchungs-Confirm parallel – entweder Fenster mit Konfliktliste oder Zusage, nie beides unbemerkt.
- **Abhängigkeiten:** intern: WP-M7c-02, WP-M7c-14.
  - extern: WP-M2-15, WP-M2-16 (Fahrzeugkandidatenfilter und Fahrtenplanmodell)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Ladeplanung und E-Reichweitenkonflikt (AT-20, M9); Werkstattunterlagen (WP-M7c-35).

### WP-M7c-16 – Revisionsbewusste Kilometer- und Nutzungsauswertung mit Schichtbericht
- **Ziel:** Kilometer- und Nutzungsdaten sind je Fahrzeug, Schicht und Zeitraum auswertbar; der Schichtbericht stimmt mit den protokollierten Fahrerereignissen überein, und Korrekturen bleiben als Revision sichtbar.
- **Anforderungen:** `LH-6.8-FLT-008`
- **Liefergegenstände:**
  - Backend: Modul `analytics` mit `fleet-safety` – Schichtbericht aus Fahrerereignissen, Kilometerständen (Schichtstart, Schichtende, Tankbeleg) und Fahrtenbuch; Abweichungsanzeige zwischen Quellen; Auswertung mit Datenstand und Definitionsversion.
  - Datenbank: `aggregate_snapshot`, `dataset_watermark`, `metric_definition_revision`; Quellen `inspection_run`, `vehicle_run`, `position_sample`.
  - API: `/v1/analytics/metrics`, `/v1/reports/jobs`, `/v1/reports/jobs/{id}`.
  - Oberfläche: UI-11 – Nutzungsauswertung je Fahrzeug; UI-19 – Kennzahlenansicht.
  - Kennzahlen: `Produktive Kilometer`, `Leerkilometerquote`, `Kosten pro Fahrzeugkilometer` (Nennerquelle).
- **Detailverträge vorab:** Vorrangregel der Kilometerquellen; Toleranz für „stimmt überein“ (OP-11).
- **Akzeptanzkriterien:**
  1. Der Schichtbericht (Kilometer, Zeiten, Anzahl Fahrten, Ereignisse) stimmt mit den protokollierten Fahrerereignissen derselben Schicht überein; Abweichungen werden ausgewiesen (FLT-008).
  2. Eine nachträgliche Korrektur ändert den Bericht nur als neue Revision; die vorherige bleibt abrufbar (FLT-008).
  3. Jede Auswertung nennt Datenstand, Vollständigkeit und Definitionsversion.
- **Tests:** positiv: Schicht mit bekannten Ereignissen → Bericht deckungsgleich. negativ: fehlender Endkilometerstand → Bericht als unvollständig gekennzeichnet; fremder Mandant. Zuarbeit zu AT-62 (Zeitstempel prüfbar).
- **Abhängigkeiten:** intern: WP-M7c-05.
  - extern: WP-M4-41, WP-M4-42, WP-M4-46 (Kilometerstände, Kilometerklassifikation, Kennzahlregister)
- **Entscheidungen:** D-10 (keine personenbezogene Leistungsbewertung aus Nutzungsdaten) – blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** TCO-Auswertung `/v1/vehicles/{id}/total-cost` (M8).

### WP-M7c-17 – Qualifikationen und Fahrzeugberechtigungen in der Schichtzuweisung
- **Ziel:** Ein Fahrer ohne die für Dienst oder Fahrzeug definierte Berechtigung kann keiner Schicht zulässig zugeteilt werden.
- **Anforderungen:** `LH-6.8-FLT-014`
- **Liefergegenstände:**
  - Backend: Regel „erforderliche Qualifikation/Fahrzeugberechtigung“ je Fahrzeugklasse und Dienst; Prüfung bei Schichtzuweisung, Fahrzeugwechsel und Einsatzfreigabe gegen die gültige `qualification_revision`.
  - Datenbank: `driver`, `qualification_revision`, `staff_shift`, `resource_release` (`qualification_revision`), `vehicle`.
  - API: `/v1/rosters` (Konfliktantwort mit Ursache).
  - Oberfläche: UI-12 – Konflikt „Berechtigung fehlt“ mit benannter Berechtigung; UI-11 – erforderliche Berechtigungen je Fahrzeugklasse.
- **Detailverträge vorab:** Katalog der Fahrzeugberechtigungen (M3 OP-14); Gültigkeitsprüfung zum Schichtzeitraum.
- **Akzeptanzkriterien:**
  1. Die Zuweisung eines Fahrers ohne definierte Berechtigung wird abgelehnt bzw. als nicht zulässig markiert und nicht veröffentlicht (FLT-014).
  2. Läuft die Berechtigung vor Schichtende ab, entsteht ein Konflikt mit Ablaufdatum.
  3. Der Entzug einer Berechtigung widerruft eine bereits erteilte Einsatzfreigabe.
- **Tests:** positiv: berechtigter Fahrer. negativ: fehlende, abgelaufene Berechtigung; Regression AT-39. konkurrierend (Sicherheit): Berechtigungsentzug und Zuweisung parallel – keine gültige Zuweisung ohne Berechtigung. Bezug: PH-AT-016 (Einsatz ohne passende Qualifikation, Schicht oder bestätigten Check wird abgewiesen), E2E-095.
- **Abhängigkeiten:** intern: WP-M7c-01.
  - extern: WP-M3-05, WP-M3-19 (Fahrerstamm und Qualifikationsrevisionen, Einsatzbereitschaft)
  - extern: WP-M4-07 (Konfliktprüfung im Dienstplan)
- **Entscheidungen:** keine
- **Größe:** S
- **Nicht im Umfang:** Bewerbung auf Schichtangebote (M7d, AT-56); Nachweisdokumente der Qualifikation (WP-M7c-53 führt nur Fristen).

### WP-M7c-18 – Vorlagen-Lebenszyklus: Freigabestatus, Version, Gültigkeit, Herausgeber
- **Ziel:** Checklistenvorlagen durchlaufen Entwurf, Freigabe und Ablösung mit Version, Gültigkeitsdatum, Mandant, Fahrzeugklasse und Herausgeber; archivierte Prüfprotokolle bleiben von Änderungen unberührt.
- **Anforderungen:** `LH-6.21-CHK-017`
- **Liefergegenstände:**
  - Backend: Modul `fleet-safety` – Zustandsautomat der Vorlagenrevision (Entwurf, freigegeben, abgelöst, zurückgezogen); nicht überlappende Gültigkeit je Mandant, Fahrzeugklasse und Prüfart (Kap. 04 §4.5 „Nicht überlappende aktive … Version“).
  - Datenbank: `checklist_template_revision` (Freigabestatus, Version, gültig von/bis, Fahrzeugklasse, Herausgeber), `checklist_step`; `inspection_run` verweist unveränderlich auf Schrittrevisionen.
  - API: Vorlagenverwaltung (neuer Pfad gemäß WP-M7c-01); `/v1/inspections` liefert die exakt gültige Revision.
  - Oberfläche: UI-11 – Vorlagenliste mit Status, Version, Gültigkeit, Herausgeber; Freigabeaktion.
- **Detailverträge vorab:** Zustandsautomat; Freigaberolle und ggf. Vier-Augen-Regel (OP-12); Überlappungsprüfung.
- **Akzeptanzkriterien:**
  1. Jede Vorlagenrevision trägt Freigabestatus, Version, Gültigkeitsdatum, Mandant, Fahrzeugklasse und verantwortlichen Herausgeber (CHK-017).
  2. Nach Freigabe einer neuen Revision zeigt ein archiviertes Prüfprotokoll unverändert Schritte, Texte und Version der damaligen Revision (CHK-017).
  3. Eine freigegebene Revision ist nicht editierbar; Änderungen erzeugen eine neue Revision.
  4. Zwei gleichzeitig gültige Revisionen für denselben Geltungsbereich sind nicht möglich.
  5. Freigabe, Ablösung und Rückzug einer Revision stehen mit Akteur, Zeit und Grund im Audit; eine Vorlage eines anderen Mandanten ist weder sichtbar noch auswählbar.
- **Tests:** positiv: Entwurf, Freigabe, Ablösung. negativ: Änderung an freigegebener Revision, Freigabe durch Ersteller bei Vier-Augen-Pflicht, fremder Mandant. konkurrierend: zwei parallele Freigaben für denselben Geltungsbereich → genau eine wird wirksam. Regression AT-32 (Prüfversionen: Historie zeigt ursprüngliche Checkfragen und Antworten).
- **Abhängigkeiten:** intern: WP-M7c-02.
  - extern: WP-M3-10, WP-M3-13 (Checklistenvorlagen und Prüfelemente, Prüflauf-Start)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Inhalte der Schritte (WP-M7c-19, WP-M7c-20).

### WP-M7c-19 – Bedingte Fragen, Grenzwerte, Pflichtfotos und Abhilfemaßnahmen
- **Ziel:** Vorlagen können Folgeabschnitte von Antworten abhängig machen, Zahlenwerte gegen Mindest- und Maximalwerte prüfen, Fotos erzwingen und strukturierte Abhilfemaßnahmen vorgeben.
- **Anforderungen:** `LH-6.21-CHK-018`
- **Liefergegenstände:**
  - Backend: Regelauswertung je Schritt (Bedingung auf Vorantwort, Min/Max, Pflichtfoto, Abhilfemaßnahme aus Katalog); serverseitige Vollständigkeitsprüfung beim Submit.
  - Datenbank: `checklist_step` (Bedingung, Grenzwerte, Fotopflicht, Abhilfekatalog), `inspection_answer`.
  - API: `/v1/inspections`, `/v1/inspections/{id}/submit` (Fehler je Schritt).
  - Oberfläche: UI-09 – Folgeabschnitt erscheint nach auslösender Antwort; Grenzwertfehler am Feld; UI-11 – Vorlageneditor für Bedingungen.
- **Detailverträge vorab:** JSON-Schema der Schrittregeln; Ausdrucksumfang der Bedingungen (bewusst begrenzt).
- **Akzeptanzkriterien:**
  1. Bei einer negativen Antwort erscheint der festgelegte Folgeabschnitt; bei positiver Antwort nicht (CHK-018).
  2. Ein Wert außerhalb von Min/Max wird nicht als gültige Antwort angenommen bzw. führt zur festgelegten Folge (CHK-018).
  3. Ein Schritt mit Pflichtfoto lässt sich ohne Foto nicht abschließen; der Server prüft das beim Submit erneut (CHK-018).
  4. Die gewählte Abhilfemaßnahme wird strukturiert gespeichert und in die Mangelmeldung übernommen.
- **Tests:** positiv: Pfad mit und ohne Folgeabschnitt. negativ: Submit mit übersprungenem Pflichtfolgeabschnitt, Wert außerhalb der Grenzen, fehlendes Pflichtfoto; manipulierte Clientantwort ohne Folgeabschnitt wird serverseitig abgelehnt. negativ zusätzlich: Vorlage mit zyklischer oder auf einen nicht existierenden Schritt verweisender Bedingung wird bei der Freigabe abgelehnt; Min > Max wird abgelehnt.
- **Abhängigkeiten:** intern: WP-M7c-18. extern: keine.
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** kontextabhängige Zusatzgruppen (WP-M7c-20); Bildprüfung (WP-M7c-27).

### WP-M7c-20 – Zusatzgruppen nach Jahreszeit, Wetter und Fahrzeugtyp
- **Ziel:** Zusatzgruppen wie Winterbetrieb oder elektrischer Antrieb erscheinen in der Prüfung nur, wenn ihre Aktivierungsbedingungen erfüllt sind.
- **Anforderungen:** `LH-6.21-CHK-019`
- **Liefergegenstände:**
  - Backend: Aktivierungsbedingungen je Schrittgruppe (Zeitraum, Fahrzeugtyp/Antrieb, Wetterbedingung); Auflösung beim Prüflauf-Start, Ergebnis wird am `inspection_run` festgeschrieben.
  - Datenbank: `checklist_step` (Gruppe, Aktivierungsbedingung), `inspection_run` (aufgelöste Gruppen), `vehicle`.
  - Adapter/Worker: Wetterquelle bzw. manuelle Aktivierung durch die Leitstelle (OP-13).
  - Oberfläche: UI-09 – Zusatzgruppe mit Kennzeichnung des Anlasses; UI-11 – Pflege der Bedingungen.
- **Detailverträge vorab:** Quelle der Wetterbedingung; Bedingungsschema; Verhalten bei nicht verfügbarer Quelle.
- **Akzeptanzkriterien:**
  1. Die Zusatzgruppe „elektrischer Antrieb“ erscheint nur bei Fahrzeugen dieses Typs; die Wintergruppe nur bei erfüllter Zeit- bzw. Wetterbedingung (CHK-019).
  2. Sind die Bedingungen nicht erfüllt, erscheint die Gruppe nicht und wird nicht als fehlend gewertet (CHK-019).
  3. Die beim Start aufgelösten Gruppen bleiben für den Prüflauf fest, auch wenn sich die Bedingung während der Prüfung ändert.
  4. Fällt die Wetterquelle aus, greift die im Vertrag festgelegte sichere Regel und wird protokolliert.
- **Tests:** positiv: je Bedingung ein erfüllter Fall. negativ: nicht erfüllte Bedingung, Quelle nicht verfügbar. Grenzfall: Bedingungswechsel während laufender Prüfung.
- **Abhängigkeiten:** intern: WP-M7c-19. extern: keine.
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Wetterprognosen für die Disposition (M9).

### WP-M7c-21 – Vor-Schicht-Checkliste mit Freigabeworkflow bei kritischem Mangel
- **Ziel:** Die Vor-Schicht-Checkliste erfasst Schäden, Sauberkeit und Kilometerstand, speichert Durchführung und Mängelstatus; ein kritischer Mangel verhindert die Freigabe oder führt in den konfigurierten Freigabeworkflow mit autorisierter Übersteuerung.
- **Anforderungen:** `LH-6.6-DRV-013`, `LH-6.8-FLT-007`
- **Liefergegenstände:**
  - Backend: Modul `fleet-safety` – Prüfpunkte Schäden, Sauberkeit, Kilometerstand in der Vor-Schicht-Vorlage; Mängelstatus je Prüflauf; konfigurierter Freigabeworkflow bei kritischem Mangel (Sperre, Benachrichtigung der Freigaberolle, begründete Entscheidung mit erneuter Freigabe über `vehicle_release_decision`).
  - Datenbank: `inspection_run`, `inspection_answer`, `resource_release`, `vehicle_release_decision`, `vehicle` (`active_block`, `safety_revision`).
  - API: `/v1/inspections`, `/v1/inspections/{id}/submit`, `/v1/vehicles/{id}/release`.
  - Oberfläche: UI-09 – Checkliste mit Schäden, Sauberkeit, Kilometerstand; Zustand „Freigabe verweigert – Entscheidung der Leitstelle ausstehend“; UI-11 – Freigabeworkflow.
  - Konfigurationsparameter: `vehicle_readiness_require_checks`, `workshop_auto_block_severity`.
  - Ereignisse/Outbox: Prüfung abgeschlossen, kritischer Mangel, Freigabe entschieden.
- **Detailverträge vorab:** Workflowkonfiguration je Mandant; Bedeutung „autorisierte Übersteuerung“ gegenüber der unaufhebbaren kritischen Sperre aus CHK-023 (OP-14).
- **Akzeptanzkriterien:**
  1. Die Checkliste lässt sich vor Schichtbeginn mit Schäden, Sauberkeit und Kilometerstand ausfüllen; Durchführung und Mängelstatus sind gespeichert (DRV-013, FLT-007).
  2. Ein kritischer Mangel verhindert die Einsatzfreigabe; eine Übersteuerung ist nur durch die autorisierte Rolle mit Begründung möglich und wird protokolliert (DRV-013).
  3. Ein kritischer Mangel löst den konfigurierten Freigabeworkflow aus und informiert die zuständige Rolle (FLT-007).
  4. Der Fahrer kann die Sperre nicht selbst aufheben; die Freigabe entsteht ausschließlich serverseitig.
  5. Ein Kilometerstand unter dem letzten bekannten Stand wird nicht still übernommen (Regel aus WP-M3-17 gilt unverändert).
- **Tests:** positiv: mängelfreie Prüfung → Freigabe; kritischer Mangel → Workflow → autorisierte Entscheidung. negativ: Übersteuerung durch Fahrer oder Rolle ohne Recht; Übersteuerung ohne Begründung. konkurrierend (Sicherheit): Submit mit kritischem Mangel parallel zu einer Zuweisung – keine Zusage auf das Fahrzeug. Regression AT-26 bis AT-29.
- **Abhängigkeiten:** intern: WP-M7c-18.
  - extern: WP-M3-14, WP-M3-16, WP-M3-17 (Prüfabschluss, kritischer Mangel sperrt synchron, Schichtstartwerte)
- **Entscheidungen:** D-11 (berechtigte Freigaberollen im Notbetrieb) – blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Ausnahmen für nichtkritische Mängel (WP-M7c-25); Wiederfreigabe nach Reparatur (WP-M7c-36).

### WP-M7c-22 – Schichtende- und Übergabecheck mit Anfangs-/Endvergleich
- **Ziel:** Am Schichtende dokumentiert der Fahrer Kilometerstand, Restenergie, Sauberkeit, Fundsachen und neue Schäden; die Leitstelle vergleicht Anfangs- und Endzustand je Fahrzeugumlauf.
- **Anforderungen:** `LH-6.21-CHK-020`
- **Liefergegenstände:**
  - Backend: Prüfart „Schichtende/Übergabe“ mit eigenem Vorlagentyp; Folgeprozesse: neuer Schaden → Schadenakte, Fundsache → Vorgang; Vergleich von Start- und Endprüfung desselben `vehicle_run`.
  - Datenbank: `inspection_run` (Prüfart, Bezug Schicht/Fahrzeug/Umlauf), `inspection_answer`, `defect_case`; Fundsache (Ablage gemäß OP-15).
  - API: `/v1/inspections`, `/v1/inspections/{id}/submit`, `/v1/operator/vehicles` (Vergleich).
  - Oberfläche: UI-10 – Schichtendreport; UI-07/UI-11 – Gegenüberstellung Anfang/Ende je Umlauf mit Differenzen (Kilometer, Energie, neue Schäden).
  - Konfigurationsparameter: `min_return_soc_pct`.
  - Ereignisse/Outbox: Schichtendprüfung abgeschlossen, Fundsache gemeldet.
- **Detailverträge vorab:** Vorlagentyp Schichtende; Vorgangstyp Fundsache; Plausibilitätsregel Endkilometer ≥ Startkilometer.
- **Akzeptanzkriterien:**
  1. Der Schichtendcheck erfasst Kilometerstand, Restenergie, Sauberkeit, Fundsachen und neu erkannte Schäden (CHK-020).
  2. Die Leitstelle sieht Anfangs- und Endzustand je Fahrzeugumlauf nebeneinander mit ausgewiesenen Unterschieden (CHK-020).
  3. Ein neuer Mangel eröffnet eine Schadenakte, eine Fundsache einen Folgevorgang; beide tragen die Referenz der Prüfung (AT-62).
  4. Alle Angaben tragen prüfbare Zeitstempel (Gerätezeit und Serverempfangszeit) (AT-62).
  5. Ein Endkilometerstand unter dem Startwert wird abgewiesen bzw. verlangt eine Begründung.
- **Tests:** positiv: AT-62 (Schichtende mit Kilometerstand, Fundsache und neuem Mangel). negativ: fehlender Kilometerstand, unplausibler Wert, Abschluss für fremde Schicht. konkurrierend: doppelter Abschluss → eine Prüfung, ein Schaden, eine Fundsache. Offlinefall: offline abgeschlossener Schichtendcheck wird nach Synchronisation genau einmal wirksam.
- **Abhängigkeiten:** intern: WP-M7c-19, WP-M7c-24.
  - extern: WP-M3-14, WP-M3-17, WP-M3-31 (Prüfabschluss, Schichtstartwerte, Dienststatus)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Fundsachenverwaltung über die Vorgangseröffnung hinaus (OP-15); Übergabe an den nächsten Fahrer (WP-M7c-52).

### WP-M7c-23 – Prüfpflichten, Erinnerungen und Eskalationen je Mandant
- **Ziel:** Je Mandant ist konfiguriert, welche Prüfungen wann fällig sind; ausstehende Prüfungen werden Fahrer und Leitstelle im definierten Vorlauf vor dem geplanten Schichtbeginn angezeigt und bei Ausbleiben eskaliert.
- **Anforderungen:** `LH-6.21-CHK-021`
- **Liefergegenstände:**
  - Backend: Prüfpflichtregel (Prüfart, Vorlauf, Erinnerungsstufen, Eskalationsrolle) je Mandant; zeitgesteuerter Worker, der gegen den geplanten Schichtbeginn rechnet und bei Schichtänderung neu plant.
  - Datenbank: `config_revision` (Regel), `staff_shift`, `inspection_run`, `business_alert`, `alert_policy_revision`, `notification`.
  - API: `/v1/config/revisions`, `/v1/config/effective`, `/v1/operator/vehicles` (ausstehende Prüfungen).
  - Oberfläche: UI-09 – „Prüfung fällig bis …“; UI-07 – Liste ausstehender Prüfungen mit Restzeit.
  - Konfigurationsparameter: `vehicle_readiness_require_checks`, `alert_escalation_delay_min`, `notification_quiet_hours`; Vorlaufparameter fehlt in `kataloge.csv` (OP-16).
- **Detailverträge vorab:** Regelschema; Dedupe-Schlüssel `(policy,event,recipient)`; Neuberechnung bei geänderter Schichtzeit.
- **Akzeptanzkriterien:**
  1. Eine ausstehende Prüfung erscheint Fahrer und Leitstelle im konfigurierten Vorlauf vor dem geplanten Schichtbeginn (CHK-021).
  2. Zwei Mandanten mit unterschiedlicher Konfiguration erhalten unterschiedliche Vorläufe und Eskalationen (CHK-021).
  3. Ohne Prüfung bis zur Eskalationsschwelle wird die konfigurierte Rolle alarmiert; je Stufe und Empfänger genau eine Nachricht.
  4. Wird der Schichtbeginn verschoben, verschieben sich Erinnerung und Eskalation; alte Erinnerungen werden nicht mehr gesendet.
- **Tests:** positiv: Erinnerung und Eskalation im Zeitraffer. negativ: Prüfung bereits abgeschlossen → keine Erinnerung; Schicht storniert. konkurrierend: zwei Workerinstanzen → kein doppelter Versand. Zeitzonen-/DST-Fall. Bezug: PH-AT-018 (DST-Fixture 25.10.2026, `Europe/Berlin`).
- **Abhängigkeiten:** intern: WP-M7c-18.
  - extern: WP-M2-32 (Nachrichtenversand)
  - extern: WP-M3-52, WP-M3-23 (Alarm-Grundlage, Prüfstatus in App und Leitstelle)
  - extern: WP-M4-14 (veröffentlichter Dienstplan mit Schichtbeginn)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Schichtstart-Erinnerungen ohne Prüfbezug (M7d, AT-63).

### WP-M7c-24 – Automatische Mangelmeldung aus nicht bestandener Prüfung
- **Ziel:** Jeder nicht bestandene Prüfschritt öffnet automatisch eine bearbeitbare Mangel- oder Störungsmeldung mit eindeutiger Referenz; der Schadenfall ist aus dem Prüfschritt ohne erneute Eingabe aufrufbar.
- **Anforderungen:** `LH-6.21-CHK-022`
- **Liefergegenstände:**
  - Backend: Erzeugung von `defect_case` beim Submit im selben Commit, vorbefüllt mit Fahrzeug, Schicht, Prüfschritt, Antwort, Fotos und Abhilfemaßnahme; Dedupe je Prüfantwort.
  - Datenbank: `inspection_answer` ↔ `defect_case` (Referenz), `defect_evidence`.
  - API: `/v1/inspections/{id}/submit` (liefert Fallreferenzen), `/v1/defects`.
  - Oberfläche: UI-09/UI-10 – Sprung vom Prüfschritt in den Schadenfall; UI-11 – Fall mit Rückverweis auf Prüfung und Vorlagenversion.
- **Detailverträge vorab:** Abbildung Antwort → Fallart (Mangel oder Störung) und Schweregrad; Dedupe-Schlüssel.
- **Akzeptanzkriterien:**
  1. Eine nicht bestandene Prüfung erzeugt automatisch eine Meldung mit eindeutiger Fallnummer (CHK-022).
  2. Aus dem Prüfschritt lässt sich der Schadenfall aufrufen; Fahrzeug, Schritt, Antwort und Fotos sind bereits übernommen (CHK-022).
  3. Die Meldung bleibt bearbeitbar (Ergänzung, Triage), ohne die unveränderliche Prüfantwort zu verändern.
  4. Je nicht bestandenem Schritt entsteht genau ein Fall.
- **Tests:** positiv: ein und mehrere nicht bestandene Schritte. negativ: bestandener Schritt erzeugt keinen Fall; Aufruf des Falls durch nicht zugeordneten Fahrer. konkurrierend: doppelter Submit → keine doppelten Fälle.
- **Abhängigkeiten:** intern: WP-M7c-02.
  - extern: WP-M3-20, WP-M3-43 (Abweichung mit Foto und Schadenvorgang aus Prüfschritt, Schadenakte)
- **Entscheidungen:** keine
- **Größe:** S
- **Nicht im Umfang:** Dublettenvorschlag gegen bestehende Fälle (WP-M7c-39).

### WP-M7c-25 – Ausnahmen für nichtkritische Mängel mit Rolle, Grund und Frist
- **Ziel:** Die Leitstelle kann einen nichtkritischen Mangel nach dokumentierter Risikoabwägung befristet als Ausnahme behandeln; eine kritische Sperre lässt sich so nicht umgehen.
- **Anforderungen:** `LH-6.21-CHK-023`
- **Liefergegenstände:**
  - Backend: Ausnahmeaggregat (Mangel, Genehmiger, Rolle, Begründung, Ablaufdatum); Ablauf-Worker, der die Ausnahme beendet und den Mangel wieder wirksam macht; serverseitige Ablehnung für kritische Mängel.
  - Datenbank: Ausnahme (Arbeitsname, WP-M7c-02), `defect_case`, `resource_release`, `vehicle_release_decision`, `audit_event`.
  - API: `/v1/defects/{id}/triage` (Ausnahme erteilen), `/v1/vehicles/{id}/release`.
  - Oberfläche: UI-11/UI-07 – Ausnahmedialog mit Pflichtfeldern; Fahrzeug zeigt „mit Ausnahme bis …“; UI-09 – Fahrer sieht die geltende Ausnahme.
  - Ereignisse/Outbox: Ausnahme erteilt, abgelaufen, widerrufen.
- **Detailverträge vorab:** Zustandsautomat Ausnahme; berechtigte Rollen; Bedeutung „erneute Freigabe“ für kritische Sperren (OP-14).
- **Akzeptanzkriterien:**
  1. Jede Ausnahme hat Genehmiger, Ablaufdatum und Begründung; ohne eines der drei wird sie abgelehnt (CHK-023).
  2. Eine kritische Sperre kann nicht über den Ausnahmedialog oder einen einfachen Operator-Klick aufgehoben werden; der Server lehnt ab (CHK-023).
  3. Mit Ablauf der Frist endet die Ausnahme automatisch; das Fahrzeug fällt in den Zustand ohne Ausnahme zurück.
  4. Eine Ausnahme erhöht `safety_revision` und ist für die Disposition sichtbar.
- **Tests:** positiv: Ausnahme erteilen, Ablauf. negativ: Ausnahme für kritischen Mangel, ohne Frist, durch nicht berechtigte Rolle, direkter API-Aufruf am Dialog vorbei. konkurrierend (Sicherheit): Hochstufung des Mangels auf kritisch parallel zur Ausnahme – am Ende ist das Fahrzeug gesperrt.
- **Abhängigkeiten:** intern: WP-M7c-24.
  - extern: WP-M3-08, WP-M3-45 (Sperr- und Freigabekern, Triage)
- **Entscheidungen:** D-11 (berechtigte Freigaberollen) – blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** eingeschränkte Freigabe bei Barrierefreiheitsmängeln (WP-M3-12).

### WP-M7c-26 – Medienmetadaten, Prüfsumme und Manipulationserkennung
- **Ziel:** Jede Mediendatei zu Prüfung, Schaden oder Fahrzeug trägt Datei-ID, Prüfsumme, Uploadzeit, Uploader, Berechtigungsbereich und Aufbewahrungsregel; eine nachträglich veränderte Datei wird erkannt.
- **Anforderungen:** `LH-6.22-DMG-023`
- **Liefergegenstände:**
  - Backend: Medienregister für `fleet-safety`: Uploadticket mit Größe/MIME/Hash, Quarantäne, serverseitige Hashberechnung, Vergleich bei jedem Abruf und im periodischen Integritätslauf; signierter Download erst nach Objekt- und Zweckprüfung (Planungsdefault Gültigkeit höchstens 60 s, Kap. 02).
  - Datenbank: `defect_evidence` (Datei-ID, SHA-256, Uploadzeit, Uploader, Berechtigungsbereich, Aufbewahrungsregel), `evidence_link`, `retention_rule`.
  - API: Evidenz-Upload zu `/v1/defects` und `/v1/inspections` (Uploadvertrag, M3 OP-13).
  - Oberfläche: UI-11 – Mediendetail mit Prüfsumme und Integritätsstatus.
  - Adapter/Worker: `workers/documents` (Malware-Scan), Objektspeicher mit mandantengetrennten Präfixen.
- **Detailverträge vorab:** Uploadvertrag für Evidenz; Berechtigungsbereiche; Zuordnung Aufbewahrungsregel je Medienart.
- **Akzeptanzkriterien:**
  1. Jede Mediendatei besitzt Datei-ID, Prüfsumme, Uploadzeit, Uploader, Berechtigungsbereich und Aufbewahrungsregel (DMG-023).
  2. Wird ein Beweisfoto im Speicher verändert, erkennt das System die Abweichung anhand der Prüfsumme, kennzeichnet die Datei und protokolliert den Befund (DMG-023).
  3. Metadaten sind nach Upload unveränderlich; Löschung erfolgt nur über den kontrollierten Lifecycleprozess.
  4. Ein Download außerhalb des Berechtigungsbereichs wird abgelehnt und protokolliert.
- **Tests:** positiv: Upload, Abruf, Integritätslauf. negativ: manipuliertes Objekt, Upload mit falschem Hash, Zugriff aus fremdem Mandanten (Regression AT-49), abgelaufener Downloadlink. konkurrierend (Datentrennung): paralleler Upload derselben Datei durch zwei Mandanten → zwei getrennte Objekte. Regression AT-36 (Medienintegritätsprüfung erkennt Veränderung).
- **Abhängigkeiten:** intern: WP-M7c-02.
  - extern: WP-M3-44, WP-M3-48 (Fotoevidenz und unveränderliche Historie)
  - extern: WP-M4-21, WP-M4-24 (Uploadticket, Quarantäne, Hash- und Integritätsprüfung)
- **Entscheidungen:** D-11 (Aufbewahrungsfristen der Medien) – blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Inhaltsprüfung und EXIF (WP-M7c-27); Export (WP-M7c-43).

### WP-M7c-27 – Medienprüfung und Entfernen von EXIF-Positionsdaten
- **Ziel:** Hochgeladene Fotos werden auf Metadaten und personenbezogene Inhalte geprüft; nicht benötigte EXIF-Positionsdaten werden standardmäßig entfernt, der Zugriff bleibt kontrolliert.
- **Anforderungen:** `LH-6.21-CHK-024`
- **Liefergegenstände:**
  - Adapter/Worker: `workers/documents` – Metadatenprüfung, Entfernen von EXIF-GPS und weiteren nicht benötigten Feldern, Kennzeichnung möglicher personenbezogener Inhalte zur Sichtprüfung.
  - Backend: Regel je Medienart, welche Metadaten erforderlich sind; Verhältnis von Original und bereinigter Fassung (OP-17).
  - Datenbank: `defect_evidence` (Hash des Originals, Hash der bereinigten Fassung, Prüfergebnis).
  - Oberfläche: UI-11 – Kennzeichen „Sichtprüfung personenbezogener Inhalt“.
- **Detailverträge vorab:** Liste zu entfernender und zu erhaltender Metadaten; Verhältnis zur Beweisprüfsumme aus WP-M7c-26; Prüfverfahren für personenbezogene Inhalte (OP-17).
- **Akzeptanzkriterien:**
  1. Ein hochgeladenes Foto mit EXIF-Position ist nach Verarbeitung ohne diese Zusatzmetadaten gespeichert bzw. ausgeliefert (CHK-024).
  2. Der Abruf des Fotos ist nur für berechtigte Rollen möglich; ein unberechtigter Abruf wird abgelehnt (CHK-024).
  3. Die Prüfsummenkette bleibt nachvollziehbar: Welche Fassung beweisrelevant ist, steht in den Metadaten.
  4. Ein Foto mit Hinweis auf personenbezogenen Inhalt wird gekennzeichnet und nicht automatisch verworfen.
- **Tests:** positiv: Foto mit GPS-EXIF → bereinigt. negativ: Datei mit falschem MIME, beschädigte Datei, unberechtigter Abruf, fremder Mandant. Regression AT-59 (EXIF-Minimierung, Gate M5), AT-33 (Medienprüfung).
- **Abhängigkeiten:** intern: WP-M7c-26. extern: keine.
- **Entscheidungen:** D-11 (Datenrechte) – blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** automatische Unkenntlichmachung von Gesichtern/Kennzeichen (nicht gefordert, OP-17).

### WP-M7c-28 – Prüfnachweis als signierter PDF-/CSV-Export
- **Ziel:** Berechtigte Stellen exportieren einen Prüfnachweis als PDF oder CSV mit Signatur oder Prüfsumme.
- **Anforderungen:** `LH-6.21-CHK-025`
- **Liefergegenstände:**
  - Backend: Exportjob für Prüfläufe mit Feldauswahl nach Empfängerrolle; Signatur bzw. Prüfsumme über den Exportinhalt.
  - Datenbank: `export_job`, `inspection_run`, `inspection_answer`, `checklist_template_revision`.
  - API: `/v1/reports/jobs`, `/v1/reports/jobs/{id}`.
  - Oberfläche: UI-11 – Export aus dem Prüfnachweis; UI-20 – Exportprotokoll.
- **Detailverträge vorab:** Feldkatalog je Empfängerrolle; Signaturverfahren (OP-18); CSV-Schema.
- **Akzeptanzkriterien:**
  1. Der Export enthält Prüfschritte, Status, Zeiten und Vorlagenversion (CHK-025).
  2. Der Export enthält keine Daten, für die der Empfänger nicht berechtigt ist (z. B. personenbezogene Fahrerangaben, Fotos anderer Bereiche) (CHK-025).
  3. Signatur bzw. Prüfsumme lässt sich gegen den Exportinhalt verifizieren; eine veränderte Datei fällt durch.
  4. Jeder Export ist mit Anforderer, Zweck und Zeitpunkt protokolliert.
- **Tests:** positiv: PDF und CSV, Verifikation. negativ: Export durch unberechtigte Rolle, fremder Mandant, manipulierte Datei. konkurrierend: wiederholter Jobstart mit gleichem Idempotenzschlüssel → ein Export. Regression AT-53 (Export Checkliste, Berechtigungen und Pflichtfelder), AT-61 (frühere Prüfungen anderer Mandanten verweigert).
- **Abhängigkeiten:** intern: WP-M7c-18.
  - extern: WP-M3-14 (unveränderlicher Prüfnachweis)
  - extern: WP-M4-37 (wiederholsicherer Exportjob als Muster)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Export der Schadenakte (WP-M7c-43).

### WP-M7c-29 – Offline-Prüfung ohne irrtümliche Verfügbarkeit und idempotenter Abschluss
- **Ziel:** Eine Prüfung kann bei kurzzeitig fehlender Verbindung verschlüsselt lokal erfasst werden, ohne dass serverseitig eine Verfügbarkeit entsteht; mehrfaches oder gleichzeitiges Senden führt zu genau einem Abschluss.
- **Anforderungen:** `LH-6.21-CHK-026`, `LH-6.21-CHK-027`
- **Liefergegenstände:**
  - Adapter/Worker: `mobile/driver` – verschlüsselte lokale Prüfablage mit Idempotenzschlüssel je Prüflauf und `device_event_id` je Meldeereignis.
  - Backend: `/v1/inspections/{id}/submit` idempotent; Zusammenführung gleichzeitiger Übermittlungen; Neubewertung beim Empfang (Sperre, Vorlagenrevision, Schicht); Notbetriebsverfahren als separat freigegebener, protokollierter Weg (nur wenn vom Betreiber definiert).
  - Datenbank: `idempotency_record`, `inbox`, `inspection_run`, `defect_case`, `resource_release`.
  - Oberfläche: UI-09 – getrennte Zustände „lokal abgeschlossen“, „synchronisiert“, „freigegeben“ (Kap. 06 §6.7).
- **Detailverträge vorab:** Idempotenzschlüssel und Zusammenführungsregel; Notbetriebsverfahren (OP-19).
- **Akzeptanzkriterien:**
  1. Eine offline erfasste Prüfung erzeugt serverseitig keine Verfügbarkeit, bevor die Synchronisation bestätigt ist (CHK-026).
  2. Die lokale Ablage der Prüfung ist verschlüsselt (CHK-026).
  3. Doppelsenden derselben Prüfung erzeugt exakt einen Abschluss (CHK-027).
  4. Je Meldeereignis entsteht höchstens ein Schaden, auch bei gleichzeitiger Übermittlung von zwei Geräten oder Sync-Versuchen (CHK-027).
  5. Ein Notbetriebsverfahren ist nur nach gesonderter Freigabe wirksam und wird als solches protokolliert.
- **Tests:** positiv: Offline-Prüfung, Sync, Freigabe. negativ: Offline-Abschluss, danach Sperre, dann Sync → keine Freigabe; gleicher Schlüssel mit anderem Inhalt → Ablehnung. konkurrierend (Sicherheit): zwei gleichzeitige Submits derselben Prüfung; 100 Wiederholungen. Regression AT-31; UI-Abnahmefall „Driverprüfpfad mit kritischem Nein und Offline-Sync“ (Kap. 06 §6.9). Bezug: PH-AT-011 und PH-AT-020 (Offline-Prüfung erzeugt keine operative Serverfreigabe).
- **Abhängigkeiten:** intern: WP-M7c-08, WP-M7c-24.
  - extern: WP-M3-33 (Offline-Prüfung und Offline-Entwürfe)
- **Entscheidungen:** D-11 (Notbetrieb: neue technische Freigaben bleiben serverpflichtig) – blockiert den Bau des Notbetriebsverfahrens, nicht den Rest.
- **Größe:** M
- **Nicht im Umfang:** allgemeine Offline-Synchronisation der Fahrtereignisse (WP-M7c-08).

### WP-M7c-30 – Auswertung der Checklisten je Fahrzeugklasse
- **Ziel:** Abschlussquote, durchschnittliche Prüfdauer, häufigste Mängel und Wiederholungsfehler sind je Fahrzeugklasse mit Filter nach Zeitraum und Depot auswertbar.
- **Anforderungen:** `LH-6.21-CHK-028`
- **Liefergegenstände:**
  - Backend: Modul `analytics` – Fakten aus `inspection_run`, `inspection_answer`, `defect_case`; vier Auswertungen mit Definitionsversion.
  - Datenbank: `metric_definition_revision`, `aggregate_snapshot`, `dataset_watermark`.
  - API: `/v1/analytics/metrics`, `/v1/reports/jobs`.
  - Oberfläche: UI-19 – Checklistenbericht mit Filtern Zeitraum, Depot, Fahrzeugklasse.
  - Kennzahlen: Checklisten-Abschlussquote, Prüfdauer, Mängelhäufigkeit, Wiederholungsfehler – in `kataloge.csv` nicht enthalten, als neue Definitionen zu registrieren (OP-20); Bezug zu `Fahrzeugverfügbarkeit`.
  - Konfigurationsparameter: `kpi_aggregation_min_n`, `reporting_timezone`.
- **Detailverträge vorab:** Definition je Kennzahl (Zähler, Nenner, Ausschlüsse), insbesondere „Wiederholungsfehler“ (OP-20).
- **Akzeptanzkriterien:**
  1. Der Bericht liefert Abschlussquote, durchschnittliche Prüfdauer, häufigste Mängel und Wiederholungsfehler je Fahrzeugklasse (CHK-028).
  2. Filter nach Zeitraum und Depot liefern konsistente Werte: Summen der Teilmengen entsprechen der Gesamtmenge (CHK-028).
  3. Jede Zahl nennt Definitionsversion, Datenstand und Nenner; Nenner 0 wird als solcher ausgewiesen.
- **Tests:** positiv: Referenzdatensatz mit bekannten Ergebnissen. negativ: Depot ohne Berechtigung, fremder Mandant, leerer Zeitraum. Bezug: PH-AT-021 (Nenner „nicht berechenbar“, Vollständigkeit, Kleingruppenschutz über `kpi_aggregation_min_n`).
- **Abhängigkeiten:** intern: WP-M7c-22, WP-M7c-24.
  - extern: WP-M4-45, WP-M4-46, WP-M4-58 (Analytics-Faktenbasis, Kennzahlregister, Filterdimensionen)
- **Entscheidungen:** D-10 (keine fahrerbezogene Leistungsauswertung) – blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Auswertung je Fahrer; erweiterte KPI-Berichte (M8).

### WP-M7c-31 – Barrierefreie Checklisten
- **Ziel:** Alle Prüfabläufe sind bei großer Schrift und mit Screenreader vollständig bedienbar, mit klaren Warnhinweisen, großen Schaltflächen und eindeutigem Fokus.
- **Anforderungen:** `LH-6.21-CHK-030`
- **Liefergegenstände:**
  - Oberfläche: UI-09 und UI-10 (Vor-Schicht-, Schichtende-, Übergabeprüfung, Folgeabschnitte, Fotoaufnahme, Fehlermeldungen) – Skalierung mit Systemschriftgröße, Mindestgröße der Bedienflächen, semantische Beschriftungen, Fokusreihenfolge, Warnhinweise nicht nur über Farbe.
  - Adapter/Worker: automatisierte Barrierefreiheitsprüfung in der CI für `mobile/driver`.
- **Detailverträge vorab:** Prüfkatalog (Schriftstufen, Screenreader je Plattform, Zielgeräte); Kriterienbezug zur Barrierefreiheitsvorgabe aus M5.
- **Akzeptanzkriterien:**
  1. Ein vollständiger Prüfdurchlauf einschließlich Mangel mit Foto gelingt auf dem Smartphone ausschließlich mit Screenreader (CHK-030).
  2. Bei größter unterstützter Schriftstufe sind alle Schritte ohne abgeschnittene Texte oder verdeckte Schaltflächen bedienbar (CHK-030).
  3. Warnhinweise (kritischer Mangel, Freigabe verweigert) werden vom Screenreader angesagt und sind ohne Farbwahrnehmung erkennbar.
  4. Die P2-Erweiterungen (Folgeabschnitte, Zusatzgruppen, Schadenskizze mit Listenalternative, Schichtendreport, Übergabe, Notfallknopf) sind im selben Durchlauf enthalten; keine Pflichtaktion verlangt eine Zeigegeste ohne Alternative (CHK-030).
- **Tests:** positiv: manueller Gerätetest mit Screenreader auf Android und iOS, dokumentiert als Evidenz; automatisierte Prüfung. negativ: Fehlerfall (Pflichtfoto fehlt) ist per Screenreader verständlich. Regression AT-54 (Barrierefreiheit Fahrer: Checkliste per Tastatur/Screenreader, Gate M5); UX-Gate Kap. 08 §8.4.
- **Abhängigkeiten:** intern: WP-M7c-19, WP-M7c-22. extern: keine.
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Barrierefreiheit der Operator-Weboberfläche (M5/M8).

### WP-M7c-32 – Schadenbereich auf konfigurierbarer Fahrzeugskizze
- **Ziel:** Ein Schaden wird auf einer Skizze einem Bereich (Front, Heck, links, rechts, Innenraum) zugeordnet; die Zuordnung erscheint in der Akte.
- **Anforderungen:** `LH-6.22-DMG-013`
- **Liefergegenstände:**
  - Backend: Skizzenvorlage je Fahrzeugklasse mit benannten Bereichen als Konfiguration; Bereich und Markierungsposition am Fall.
  - Datenbank: `defect_case` (Skizzenrevision, Bereich, Position); Skizzenvorlage als `config_revision` bzw. eigene Tabelle (WP-M7c-01).
  - API: `/v1/defects`.
  - Oberfläche: UI-10 – Bereich antippen, Alternative als Auswahlliste; UI-11 – Skizze mit allen offenen Schäden des Fahrzeugs.
- **Detailverträge vorab:** Format der Skizze (SVG mit Bereichs-IDs); Versionierung bei Skizzenänderung.
- **Akzeptanzkriterien:**
  1. Der gewählte Schadensbereich wird gespeichert und in der Akte auf der Skizze wiedergegeben (DMG-013).
  2. Die Skizze ist je Fahrzeugklasse konfigurierbar; ältere Fälle zeigen die Skizze ihrer damaligen Revision (DMG-013).
  3. Die Bereichswahl ist auch ohne Zeigegeste (Liste, Screenreader) möglich.
- **Tests:** positiv: je Bereich ein Fall. negativ: unbekannte Bereichs-ID, Skizze eines anderen Mandanten. Regression AT-33 (Schaden mit Foto und Fahrzeugbereich).
- **Abhängigkeiten:** intern: WP-M7c-02.
  - extern: WP-M3-43, WP-M3-44, WP-M3-21 (Schadenakte, mobile Erfassung, Bekannte Altschäden)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** freie 3D-Modelle; Bilderkennung.

### WP-M7c-33 – Bearbeiter, Verantwortlicher, Fristen, Wiedervorlage und Aufgabenliste
- **Ziel:** Jeder Fall trägt Bearbeiter, Verantwortlichen, Priorität, Fristen und Wiedervorlage; überfällige offene Schäden erscheinen in einer nach Verantwortlichen filterbaren Aufgabenliste.
- **Anforderungen:** `LH-6.22-DMG-014`
- **Liefergegenstände:**
  - Backend: Zuweisungs- und Fristfelder am Fall; Überfälligkeitsberechnung; Aufgabenprojektion.
  - Datenbank: `defect_case` (Bearbeiter, Verantwortlicher, Priorität, Frist, Wiedervorlage), Index `(tenant_id,state,updated_at)`.
  - API: `/v1/defects` (Filter Verantwortlicher, überfällig, Priorität).
  - Oberfläche: UI-11 – Aufgabenliste „Meine Fälle“ und Teamliste mit Überfälligkeitskennzeichen.
- **Detailverträge vorab:** Filterschema; Definition „überfällig“ (Frist oder Wiedervorlage überschritten).
- **Akzeptanzkriterien:**
  1. Bearbeiter, Verantwortlicher, Priorität, Frist und Wiedervorlage sind je Fall hinterlegbar und änderbar; Änderungen stehen in der Historie (DMG-014).
  2. Überfällige offene Schäden erscheinen in der Aufgabenliste; der Filter nach Verantwortlichem zeigt genau dessen Fälle (DMG-014).
  3. Abgeschlossene Fälle erscheinen nicht als überfällig.
- **Tests:** positiv: Zuweisung, Fristablauf, Filter. negativ: Zuweisung an Nutzer ohne Rolle oder aus fremdem Mandanten. konkurrierend: gleichzeitige Umzuweisung → 412 für den späteren Schreiber.
- **Abhängigkeiten:** intern: WP-M7c-02.
  - extern: WP-M3-49 (Schadenliste der Leitstelle mit Filtern)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Eskalation und Service-Level (WP-M7c-45).

### WP-M7c-34 – Vollständiger Statusautomat der Schadenakte
- **Ziel:** Die Schadenakte unterscheidet die neun Status erfasst, triagiert, in Prüfung, Freigabe offen, Werkstatt geplant, Reparatur, Qualitätsprüfung, abgeschlossen und archiviert; ein Werkstattauftrag wechselt erst nach festgelegter Freigabe in die Reparatur.
- **Anforderungen:** `LH-6.22-DMG-015`
- **Liefergegenstände:**
  - Backend: Erweiterung des Automaten aus M3 auf neun Status mit Rollen und Vorbedingungen je Übergang; Kopplung an `repair_order`.
  - Datenbank: `defect_case` (Status), `repair_order` (Status, Freigabebezug), Migration der M3-Status auf die neuen Werte.
  - API: `/v1/defects`, `/v1/defects/{id}/triage`, `/v1/repair-orders`.
  - Oberfläche: UI-11 – Statusanzeige und erlaubte nächste Aktionen; Zustand „Freigaberolle fehlt“.
  - Ereignisse/Outbox: Statuswechsel der Schadenakte.
- **Detailverträge vorab:** Zustandsautomat mit Übergangstabelle; Abbildung der P1-Status; Definition der „festgelegten Freigabe“ vor Reparatur (Kostenfreigabe, OP-21).
- **Akzeptanzkriterien:**
  1. Alle neun Status sind unterscheidbar und nur über definierte Übergänge erreichbar (DMG-015).
  2. Ein Werkstattauftrag kann ohne die festgelegte Freigabe nicht in den Status Reparatur wechseln; der Versuch wird abgelehnt und protokolliert (DMG-015).
  3. Bestehende Fälle aus P1 sind nach der Migration einem der neun Status eindeutig zugeordnet.
  4. Archivierte Fälle sind unveränderlich.
- **Tests:** positiv: Durchlauf aller Status. negativ: je unzulässiger Übergang ein Fall; Reparaturstart ohne Freigabe; Freigabe durch unberechtigte Rolle. konkurrierend: zwei gleichzeitige Statuswechsel → einer gewinnt.
- **Abhängigkeiten:** intern: WP-M7c-02.
  - extern: WP-M3-47, WP-M3-51 (Statusautomat der Schadenakte, Reparaturauftrag)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** technische Wiederfreigabe des Fahrzeugs (WP-M7c-36); Kostenfreigabe im Detail (WP-M7c-44).

### WP-M7c-35 – Werkstatttermine, Ersatzteile, Kostenvoranschläge, Rechnungen, Reparaturnachweise
- **Ziel:** Alle Werkstattunterlagen sind dem Fall zugeordnet, versioniert und rollenbasiert abrufbar archiviert.
- **Anforderungen:** `LH-6.22-DMG-016`
- **Liefergegenstände:**
  - Backend: Zuordnung von Termin, Ersatzteilposition, Kostenvoranschlag, Rechnung und Reparaturnachweis zu `repair_order` und `defect_case`; Versionierung je Dokument; Sichtbarkeit je Dokumentart und Rolle.
  - Datenbank: `repair_order`, `document_revision`, `evidence_link`, `supplier_invoice`, `expense_document`, `defect_evidence`.
  - API: `/v1/repair-orders`, `/v1/defects`, `/v1/expenses/receipts`.
  - Oberfläche: UI-11 – Reiter „Werkstatt“ der Akte mit Dokumentliste, Versionen und Terminen.
  - Konfigurationsparameter: `invoice_archive_years`.
- **Detailverträge vorab:** Dokumentarten und Rollenmatrix; Bezug Rechnung ↔ Belegaggregat aus M4 (dasselbe Ereignis, keine zweite Kostenwirkung).
- **Akzeptanzkriterien:**
  1. Termin, Ersatzteile, Kostenvoranschlag, Rechnung und Reparaturnachweis lassen sich dem Fall zuordnen (DMG-016).
  2. Jede Dokumentart wird versioniert; frühere Versionen bleiben abrufbar (DMG-016).
  3. Der Abruf ist rollenbasiert: eine Rolle ohne Recht auf Rechnungen sieht diese nicht (DMG-016).
  4. Eine dem Fall zugeordnete Rechnung erzeugt genau eine Kostenwirkung über den Belegweg aus M4.
- **Tests:** positiv: alle fünf Arten zuordnen, neue Version. negativ: Abruf ohne Rolle, fremder Mandant, Löschen einer Version. konkurrierend (Geld): dieselbe Rechnung über Fall und Belegeingang gleichzeitig → eine Kostenwirkung (Kap. 04 §4.5 „Genau eine Kostenwirkung“). Bezug: PH-AT-012 (ein Kostenereignis trotz mehrerer Eingangswege).
- **Abhängigkeiten:** intern: WP-M7c-34, WP-M7c-26.
  - extern: WP-M4-22, WP-M4-23, WP-M4-32 (Belegaggregat, unveränderliches Original, Dublettenerkennung)
- **Entscheidungen:** D-06 (Finanzumfang) – blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Kostenarten und Rechnungsfreigabe (WP-M7c-44); Ersatzteilbestand (WP-M7c-48).

### WP-M7c-36 – Technische Freigabe nach Reparatur mit Folgecheckliste
- **Ziel:** Nach einer Reparatur gibt eine befugte Rolle das Fahrzeug technisch frei; bis dahin bleibt die Disposition deaktiviert, danach ist eine neue relevante Checkliste nötig. Eine Fahrerquittung allein hebt keine sicherheitskritische Sperre auf.
- **Anforderungen:** `LH-6.22-DMG-017`
- **Liefergegenstände:**
  - Backend: Freigabeentscheidung mit Rollenprüfung, Reparaturnachweis als Vorbedingung, Vieraugenpflicht gemäß Kap. 05 §5.7; nach Freigabe wird die Prüfpflicht neu ausgelöst, die Einsatzfreigabe entsteht erst nach bestandener Folgeprüfung.
  - Datenbank: `vehicle_release_decision`, `resource_release`, `vehicle` (`active_block`, `safety_revision`), `repair_order`, `inspection_run`.
  - API: `/v1/vehicles/{id}/release`.
  - Oberfläche: UI-11 – Freigabedialog mit Nachweisprüfung; Zustand „Freigaberolle fehlt“; UI-09 – „Folgeprüfung erforderlich“.
  - Ereignisse/Outbox: technische Freigabe erteilt, Folgeprüfung angefordert.
- **Detailverträge vorab:** Rollen und Vieraugenregel; Auswahl der „relevanten“ Checkliste nach Reparaturart (OP-22).
- **Akzeptanzkriterien:**
  1. Vor der technischen Freigabe ist das Fahrzeug nicht disponierbar (DMG-017).
  2. Nach der Freigabe ist eine neue relevante Checkliste erforderlich; erst deren bestandener Abschluss stellt die Einsatzfreigabe her (DMG-017).
  3. Eine Fahrerquittung oder ein Prüfabschluss des Fahrers hebt eine sicherheitskritische Sperre nicht auf (DMG-017).
  4. Freigabe ohne Reparaturnachweis oder durch unbefugte Rolle wird abgelehnt.
- **Tests:** positiv: Reparatur → Freigabe → Folgeprüfung → disponierbar (Regression AT-35, AT-51, AT-60). negativ: Freigabe durch Fahrer, ohne Nachweis, durch dieselbe Person bei Vieraugenpflicht. konkurrierend (Sicherheit): Freigabe und neue kritische Meldung parallel – Fahrzeug bleibt gesperrt.
- **Abhängigkeiten:** intern: WP-M7c-34, WP-M7c-35.
  - extern: WP-M3-51, WP-M3-08 (Reparaturauftrag und Wiederfreigabe mit Rollenprüfung, Sperrkern)
- **Entscheidungen:** D-11 (berechtigte Freigaberollen) – blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Ausnahmen für nichtkritische Mängel (WP-M7c-25).

### WP-M7c-37 – Getrennte Domänen Fahrzeug, Schaden, Verkehrsauftrag über Ereignisse
- **Ziel:** Fahrzeugstatus, Schadenstatus und Verkehrsauftragsstatus bleiben eigene Automaten und werden über Ereignisse konsistent gehalten; ein Reparaturabschluss startet keine Fahrt und hebt keine Sperre auf.
- **Anforderungen:** `LH-6.22-DMG-029`
- **Liefergegenstände:**
  - Backend: Ereignisverträge zwischen `fleet-safety` (Fahrzeug, Schaden) und `booking-dispatch` (Verkehrsauftrag); Verbraucher mit Inbox-Deduplikation; Architekturtest, dass kein Modul fremde Tabellen schreibt; Abgleichslauf für Projektionen.
  - Datenbank: `outbox`, `inbox`, `defect_case`, `vehicle`, `vehicle_run`, `booking`.
  - Ereignisse/Outbox: Schaden gemeldet/triagiert/abgeschlossen, Fahrzeug gesperrt/freigegeben, Reparatur abgeschlossen; Verbraucherreaktionen dokumentiert.
  - Oberfläche: UI-07/UI-11 – drei getrennte Statusanzeigen je Fahrzeug.
- **Detailverträge vorab:** Ereigniskatalog mit Schema und Reihenfolgegarantie; Tabelle „welches Ereignis darf welche Wirkung in welcher Domäne haben“.
- **Akzeptanzkriterien:**
  1. Ein Reparaturabschluss startet keine Fahrgastfahrt und ändert keinen Verkehrsauftrag (DMG-029).
  2. Ein Reparaturabschluss hebt die Fahrzeugsperre nicht auf; das geschieht nur über die technische Freigabe (DMG-029).
  3. Die drei Status sind getrennt gespeichert und nach Ereignisverarbeitung konsistent; ein doppelt zugestelltes Ereignis hat keine zweite Wirkung (DMG-029).
  4. Fällt der Ereignistransport aus, bleibt die Sperre wirksam, weil sie synchron im Sperrkern gilt.
- **Tests:** positiv: Ereigniskette Schaden → Sperre → Reparatur → Freigabe. negativ: Reparaturabschluss ohne Freigabe → Fahrzeug bleibt gesperrt; Ereignis in falscher Reihenfolge. konkurrierend: 100 identische Ereignisse; Relay-Ausfall und Wiederanlauf. Architekturtest Modulgrenzen (WP-M0-03). Bezug: PH-AT-017 (Reihenfolge und Verspätung von Events), PH-AT-020 (Reparaturvermerk allein entsperrt nicht), Regression AT-35.
- **Abhängigkeiten:** intern: WP-M7c-36.
  - extern: WP-M1-14 (Outbox-Relay und Inbox-Deduplikation)
  - extern: WP-M2-28, WP-M2-25 (Planrevisionen und Buchungsstatus)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Ersatzdisposition (WP-M7c-38).

### WP-M7c-38 – Ersatzfahrzeug aus dem Schadenfall aktivieren
- **Ziel:** Die Leitstelle aktiviert aus dem Schadenfall ein Ersatzfahrzeug; Fahrer-/Fahrzeugzuordnung und Buchungsinformationen werden aktualisiert, und die Checklistenpflicht des Ersatzfahrzeugs wird ausgelöst.
- **Anforderungen:** `LH-6.22-DMG-018`
- **Liefergegenstände:**
  - Backend: Befehl „Ersatzfahrzeug aktivieren“ am Schadenfall: Kandidatenliste (verfügbar, qualifikationsverträglich, Kapazitätsvektor deckt zugesagte Fahrten), neue Zuordnung, Umhängen der betroffenen Fahrten als neue Planrevision mit Plan-CAS, Fahrgastinformation; Einsatzfreigabe des Ersatzfahrzeugs erst nach bestandener Prüfung. Partnerreserven nur als Option, wenn ein Kooperationsvertrag sie erlaubt.
  - Datenbank: `vehicle_assignment`, `staff_shift`, `vehicle_run`, `booking`, `resource_release`, `inspection_run`, `defect_case`; für Partneroption `cooperation_grant`, `partner_contract_version`.
  - API: `/v1/defects`, `/v1/runs/{id}/revisions`, `/v1/operator/vehicles`.
  - Oberfläche: UI-11/UI-07 – Aktion „Ersatzfahrzeug“ mit Auswirkungsliste; UI-09 – Prüfpflicht am Ersatzfahrzeug.
  - Ereignisse/Outbox: Ersatzfahrzeug aktiviert, Zuordnung geändert, Fahrten umgehängt.
  - Konfigurationsparameter: `vehicle_unavailability_alert_min`, `sharing_vehicle_quota`, `interoperator_bookings`.
- **Detailverträge vorab:** Ablauf als Domänentransaktion (Sperrreihenfolge Run vor Fahrzeug); Verhalten, wenn das Ersatzfahrzeug nicht alle Fahrten decken kann; Vertrag der Partneroption.
- **Akzeptanzkriterien:**
  1. Aus dem Schadenfall lässt sich ein Ersatzfahrzeug aktivieren; die neue Fahrer-/Fahrzeugzuordnung und die betroffenen Buchungen zeigen das Ersatzfahrzeug (DMG-018).
  2. Mit dem Fahrzeugwechsel wird die Checklistenpflicht des Ersatzfahrzeugs ausgelöst; ohne bestandene Prüfung erhält es keine Einsatzfreigabe (DMG-018).
  3. Kann das Ersatzfahrzeug eine Fahrt nicht decken (Kapazität, Ausstattung), bleibt sie als ungelöst sichtbar; es wird nichts still zugesagt.
  4. Hat ein Partner Reserven, erscheint die vertraglich zulässige Übernahme als Option; es erfolgt keine Zuweisung ohne Berechtigung (E2E-100).
  5. Betroffene Fahrgäste erhalten die aktualisierten Buchungsinformationen (Fahrzeug, ggf. geänderte Zeit) über den bestehenden Nachrichtenweg genau einmal; die Buchungsansicht zeigt das Ersatzfahrzeug (DMG-018).
  6. Die Aktivierung ist mit Disponent, Schadenfall, altem und neuem Fahrzeug und betroffenen Fahrten im Audit nachvollziehbar.
- **Tests:** positiv: Ersatz innerhalb des Mandanten; E2E-100 (Werkstattausfall, Partner mit Reserven). negativ: Ersatzfahrzeug gesperrt oder Fahrer ohne Berechtigung; Partner ohne Grant. konkurrierend (Kapazität): zwei Disponenten aktivieren gleichzeitig dasselbe Ersatzfahrzeug für zwei Schadenfälle → genau eine Aktivierung; Aktivierung parallel zu neuer Buchung auf das Ersatzfahrzeug. Regression AT-09, AT-30, AT-50. Bezug: PH-AT-016 (kein Einsatz ohne Qualifikation, Schicht oder bestätigten Check trotz Partnervertrag), PH-AT-020.
- **Abhängigkeiten:** intern: WP-M7c-21, WP-M7c-37.
  - extern: WP-M2-28, WP-M2-22, WP-M2-32 (Planrevisionen, Hold/Confirm, Nachrichtenversand an Fahrgäste)
  - extern: WP-M3-22, WP-M3-40, WP-M3-54 (Fahrzeugwechsel in laufender Schicht, manuelle Umplanung mit Plan-CAS, Störungsablauf)
  - extern: WP-M6b-04, WP-M6b-05, WP-M6b-13, WP-M6b-33 (Kooperationsvertrag, Grant und Reservenangebot des Partners)
- **Entscheidungen:** D-08 (tatsächliche Buchungsrechte bei Partnern), D-01 (Verantwortung bei Übernahme) – blockieren nur die Produktivschaltung der Partneroption.
- **Größe:** L
- **Nicht im Umfang:** automatische Ersatzdisposition ohne Leitstellenentscheidung; Clearing der Partnerübernahme (M6c).

### WP-M7c-39 – Dublettenvorschlag und manuelle Verknüpfung
- **Ziel:** Das System schlägt mögliche Dubletten von Schadensmeldungen vor; die Verknüpfung erfolgt nur manuell.
- **Anforderungen:** `LH-6.22-DMG-019`
- **Liefergegenstände:**
  - Backend: Ähnlichkeitsprüfung beim Anlegen und in der Triage über Fahrzeug, Ort, Kategorie, Zeit und offene Fallakten; Verknüpfung „Dublette von“ ohne Löschen; Aufheben der Verknüpfung.
  - Datenbank: `defect_case` (Verknüpfung, Dublettenstatus).
  - API: `/v1/defects` (Kandidaten), `/v1/defects/{id}/triage` (verknüpfen).
  - Oberfläche: UI-11 – Hinweis „mögliche Dublette“ mit Vergleich; UI-10 – Hinweis an den Melder auf bestehenden offenen Fall.
- **Detailverträge vorab:** Ähnlichkeitsregel und Schwellen (OP-23).
- **Akzeptanzkriterien:**
  1. Bei einer zweiten Meldung zum selben Fahrzeug, Ort, Kategorie und Zeitraum schlägt das System die Dublette vor (DMG-019).
  2. Es findet keine automatische Zusammenführung statt; beide Fälle bleiben bis zur manuellen Entscheidung eigenständig (DMG-019).
  3. Die Verknüpfung ist mit Akteur und Zeit protokolliert und umkehrbar; Evidenz beider Fälle bleibt erhalten.
- **Tests:** positiv: Vorschlag und Verknüpfung; Regression AT-36. negativ: ähnlicher Fall an anderem Fahrzeug → kein Vorschlag; Verknüpfung über Mandantengrenze. konkurrierend: zwei Bearbeiter verknüpfen gegenläufig → kein Zyklus.
- **Abhängigkeiten:** intern: WP-M7c-34. extern: keine.
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Bildähnlichkeit; Dubletten von Belegen (M4).

### WP-M7c-40 – Unfall- und Sicherheitsvorfälle als getrennte, beschränkte Akte
- **Ziel:** Unfall- und Sicherheitsvorfälle werden getrennt von gewöhnlichen Fahrzeugschäden geführt; nicht berechtigte Disponenten erhalten keine personenbezogenen Unfallakten.
- **Anforderungen:** `LH-I.10-AST-007`
- **Liefergegenstände:**
  - Backend: eigene Unfallakte mit Bezug zum Schadenfall, eigener Rolle und Zweckprüfung; Projektion für Disponenten ohne personenbezogene Inhalte (nur „Unfallakte vorhanden“ und betriebliche Wirkung).
  - Datenbank: Unfallakte (Arbeitsname, eigener Modulzugang und Spaltenrechte nach Kap. 04 §4.6), `defect_case` (Referenz), `audit_event`.
  - API: eigener Pfad für Unfallakten (WP-M7c-01); `/v1/defects` liefert nur die Referenz.
  - Oberfläche: UI-11 – Schadenakte ohne Unfalldetails; UI-20 – Unfallakte mit begründetem Zugriff.
- **Detailverträge vorab:** Rollen- und Zweckmatrix; Feldkatalog der Unfallakte (M3 OP-23, OP-03).
- **Akzeptanzkriterien:**
  1. Unfall- und Sicherheitsvorfälle liegen in einer eigenen Akte und nicht in den Feldern der gewöhnlichen Schadenakte (AST-007).
  2. Ein Disponent ohne Berechtigung erhält über API, Liste, Suche und Export keine personenbezogenen Unfallakten (AST-007).
  3. Jeder Zugriff auf die Unfallakte wird mit Akteur, Zweck und Zeit protokolliert; abgelehnte Zugriffe ebenfalls.
- **Tests:** positiv: berechtigte Rolle liest und bearbeitet. negativ: Disponent, Fahrer, fremder Mandant, Zugriff über Fall-ID-Raten, Zugriff über Export. konkurrierend (Datentrennung): Rechteentzug während offener Sitzung → nächster Zugriff abgelehnt. Bezug: PH-AT-001 (Isolation in API, Suche und Export), Regression AT-49.
- **Abhängigkeiten:** intern: WP-M7c-02.
  - extern: WP-M1-07, WP-M1-08, WP-M1-31 (Rollenmodell, Objektberechtigung, Feldschutz)
  - extern: WP-M3-50 (Vertraulichkeit der Schadenakte)
- **Entscheidungen:** D-11 (Datenrechte, Supportzugriff) – blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Versicherungsangaben (WP-M7c-41); Drittbeteiligte (WP-M7c-42).

### WP-M7c-41 – Versicherungs- und Haftungsangaben ohne automatische Schuldzuweisung
- **Ziel:** Versicherungs- und Haftungsangaben können optional und nur mit eigener Berechtigung verwaltet werden; das System trifft keine Feststellung zur Verantwortlichkeit.
- **Anforderungen:** `LH-6.22-DMG-020`
- **Liefergegenstände:**
  - Backend: optionaler Abschnitt der Unfallakte (Versicherer, Schadennummer, Meldestatus, Freitext der Sachbearbeitung) mit eigener Berechtigung; keine berechneten Felder zu Schuld oder Verursachung.
  - Datenbank: Abschnitt der Unfallakte (WP-M7c-40).
  - Oberfläche: UI-20/UI-11 – Abschnitt nur für berechtigte Rolle sichtbar.
- **Detailverträge vorab:** Feldkatalog; ausdrückliche Negativliste (kein Feld „Verursacher laut System“, keine Scores).
- **Akzeptanzkriterien:**
  1. Die Unfallakte enthält keine algorithmisch erzeugte Feststellung der Verantwortlichkeit; alle Haftungsangaben sind manuell erfasst und als solche gekennzeichnet (DMG-020).
  2. Ohne die eigenständige Berechtigung sind die Angaben weder sichtbar noch änderbar (DMG-020).
  3. Der Abschnitt ist je Mandant abschaltbar.
- **Tests:** positiv: Erfassung durch berechtigte Rolle. negativ: Zugriff ohne Berechtigung; Schemaprüfung, dass kein abgeleitetes Schuldfeld existiert. negativ zusätzlich: fremder Mandant; Export der Schadenakte ohne Profilrecht enthält den Abschnitt nicht.
- **Abhängigkeiten:** intern: WP-M7c-40. extern: keine.
- **Entscheidungen:** keine
- **Größe:** S
- **Nicht im Umfang:** Schnittstelle zu Versicherern.

### WP-M7c-42 – Drittbeteiligte und Zeugen mit begrenztem Zugriff und Löschfrist
- **Ziel:** Angaben zu Drittbeteiligten und Zeugen werden nur bei betrieblicher Erforderlichkeit, mit begrenztem Zugriff und definierter Löschfrist gespeichert; der Fahrer hat nach Abgabe keinen pauschalen Zugriff.
- **Anforderungen:** `LH-6.22-DMG-022`
- **Liefergegenstände:**
  - Backend: Erfassung mit Pflichtangabe der Erforderlichkeit; verschlüsselte Ablage; Zugriff nur für berechtigte Rolle; Löschjob nach Frist mit Legal-Hold-Ausnahme.
  - Datenbank: Drittbeteiligte (Arbeitsname, eigener Modulzugang), `retention_rule`, `erasure_job`.
  - API: Unterressource der Unfallakte; Driver-Erfassung über `/v1/driver/events` bzw. `/v1/defects` ohne Rücklesepfad.
  - Oberfläche: UI-10 – Erfassung am Unfallort, danach nur Bestätigung „abgegeben“; UI-20 – Einsicht und Löschstatus.
- **Detailverträge vorab:** Löschfrist und Auslöser (OP-24); Feldkatalog; Rücklesesperre für die Rolle Fahrer.
- **Akzeptanzkriterien:**
  1. Nach der Abgabe kann die Standardrolle Fahrer die Drittpersonendaten nicht mehr pauschal abrufen (DMG-022).
  2. Erfassung ohne Angabe der betrieblichen Erforderlichkeit wird abgelehnt (DMG-022).
  3. Nach Ablauf der Löschfrist sind die Daten entfernt; der Löschlauf ist protokolliert; ein Legal Hold verhindert die Löschung nachvollziehbar (DMG-022).
  4. Die lokale Kopie auf dem Fahrergerät wird nach bestätigter Synchronisation entfernt.
- **Tests:** positiv: Erfassung, Einsicht durch berechtigte Rolle, Löschlauf. negativ: Rücklesen durch Fahrer, Disponent, fremden Mandanten; Löschung trotz Legal Hold. konkurrierend (Datentrennung): Löschlauf parallel zum Export → Export enthält die Daten nicht mehr oder schlägt kontrolliert fehl.
- **Abhängigkeiten:** intern: WP-M7c-40.
  - extern: WP-M1-33 (Aufbewahrungs-, Archivierungs- und Löschregeln)
- **Entscheidungen:** D-11 (Löschfristen, Legal Hold) – blockiert die Produktivschaltung; der Bau nutzt einen konfigurierbaren Wert.
- **Größe:** M
- **Nicht im Umfang:** Auskunftsprozess `/v1/privacy/requests` (M8).

### WP-M7c-43 – Geschützter Export der Schadenakte
- **Ziel:** Die vollständige Schadenakte lässt sich als geschützter PDF- oder Datenexport für Werkstatt, Versicherung oder Auftraggeber erzeugen, jeweils mit passender Datenauswahl, Schwärzung und Berechtigung.
- **Anforderungen:** `LH-6.22-DMG-024`
- **Liefergegenstände:**
  - Backend: Exportprofile je Empfängerart (Werkstatt, Versicherung, Auftraggeber) mit Feld- und Medienauswahl sowie Schwärzungsregeln; geschützte Bereitstellung (befristeter signierter Download, Prüfsumme).
  - Datenbank: `export_job`, `defect_case`, `defect_evidence`, `repair_order`, Unfallakte.
  - API: `/v1/reports/jobs`, `/v1/reports/jobs/{id}`.
  - Oberfläche: UI-11 – Exportdialog mit Profilwahl und Vorschau der enthaltenen Bereiche; UI-20 – Exportprotokoll.
- **Detailverträge vorab:** Exportprofile; Schwärzungsregeln; Schutzverfahren (OP-18).
- **Akzeptanzkriterien:**
  1. Der Export für die Werkstatt enthält technische Angaben und Fotos, aber keine Drittbeteiligten und keine Haftungsangaben; das Versicherungsprofil enthält die dafür freigegebenen Bereiche (DMG-024).
  2. Geschwärzte Inhalte sind im Export nicht rekonstruierbar (kein verdeckter Text, keine Metadaten) (DMG-024).
  3. Nur Rollen mit Exportrecht für das jeweilige Profil können den Export auslösen; jeder Export ist protokolliert (DMG-024).
  4. Der Export trägt Prüfsumme und Datenstand.
- **Tests:** positiv: je Profil ein Export. negativ: Profil ohne Recht, fremder Mandant, Suche nach geschwärztem Text in der Datei, abgelaufener Download. konkurrierend: Export parallel zum Löschlauf der Drittbeteiligten. Regression AT-53 (Export Schadenakte, Löschregeln).
- **Abhängigkeiten:** intern: WP-M7c-35, WP-M7c-26, WP-M7c-42.
  - extern: WP-M4-37 (wiederholsicherer Exportjob)
- **Entscheidungen:** D-11 (Datenrechte je Empfänger) – blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** elektronische Übermittlung an Versicherer; Prüfnachweis-Export (WP-M7c-28).

### WP-M7c-44 – Kostenarten und getrennte Rechnungsfreigabe
- **Ziel:** Material, Arbeitszeit, Abschleppen und Fahrzeugausfall werden am Schadenfall getrennt erfasst; die Rechnungsfreigabe ist vom Melden getrennt, Summen und Rollen sind nachvollziehbar.
- **Anforderungen:** `LH-6.22-DMG-025`
- **Liefergegenstände:**
  - Backend: Kostenpositionen je Fall mit Kostenart; Werkstattbeleg über den Belegweg aus M4; Freigabe mit Rollentrennung (Melder ≠ Freigeber) und Schwellen; Summenbildung je Kostenart und gesamt; Anzeige der Erstattungsdaten.
  - Datenbank: `repair_order`, `cost_category`, `cost_object`, `economic_event`, `expense_document`, `supplier_invoice`, `approval_step`, `allocation_line`.
  - API: `/v1/repair-orders`, `/v1/expenses/receipts`, `/v1/expenses/{id}`, `/v1/expenses/{expense_id}/approve`, `/v1/cost-events`.
  - Oberfläche: UI-11 – Kostenreiter der Akte mit Summen je Kostenart; UI-14 – Freigabe des Werkstattbelegs.
  - Konfigurationsparameter: `approval_stage_thresholds`, `cost_split_rule`.
  - Kennzahlen: `Kosten pro Fahrzeugkilometer`, `Werkstattquote`, `Offene Rechnungswerte`.
- **Detailverträge vorab:** Kostenartenkatalog für Schäden; Bewertung „Fahrzeugausfall“ (kalkulatorisch oder Ist, OP-25); Inhalt „Erstattungsdaten“ (OP-25).
- **Akzeptanzkriterien:**
  1. Material, Arbeitszeit, Abschleppen und Fahrzeugausfall sind als getrennte Kostenarten am Fall erfasst; die Summen je Art und gesamt stimmen centgenau (DMG-025, AT-57).
  2. Wer den Schaden gemeldet oder den Beleg erfasst hat, kann die Rechnung nicht selbst freigeben; die Rollen sind am Beispiel nachvollziehbar (DMG-025, AT-57).
  3. Erst die Freigabe erzeugt die Kostenwirkung; ein abgelehnter Beleg erzeugt keine.
  4. Erstattungsdaten werden am Fall angezeigt und stimmen mit den Kostenpositionen überein (AT-57).
  5. Eine Korrektur nach Freigabe erfolgt nur als Gegenereignis.
- **Tests:** positiv: AT-57 (Werkstattbeleg erfassen, freigeben, Erstattungsdaten anzeigen). negativ: Selbstfreigabe, Freigabe über Schwelle ohne zweite Stufe, Beleg in geschlossener Periode, Summe ≠ Positionen. konkurrierend (Geld): zwei gleichzeitige Freigaben desselben Belegs → eine Kostenwirkung; Rundungsrest bei Aufteilung auf zwei Kostenobjekte. Regression E2E-068 (Werkstattrechnung über Vier-Augen-Schwelle) und PH-AT-019 (Selbstfreigabe blockiert); Bezug PH-AT-013 (centgenaue Allokation).
- **Abhängigkeiten:** intern: WP-M7c-35.
  - extern: WP-M4-33, WP-M4-34, WP-M4-35, WP-M4-39 (Kostenereignis und Journal, Freigabe-Workflow mit Rollentrennung, Belegaufteilung, Kostenklassifikation)
- **Entscheidungen:** D-06 (Finanzumfang, ERP), D-07 (Kostenbewertung Fahrzeugausfall) – blockieren die Produktivschaltung; D-07 blockiert den Bau der Ausfallbewertung.
- **Größe:** M
- **Nicht im Umfang:** Regress und Versicherungsabrechnung; TCO-Bericht (M8).

### WP-M7c-45 – Wiedervorlagen, Service-Level und Eskalation je Schadenskategorie
- **Ziel:** Je Schadenskategorie sind Wiedervorlagen, Service-Level und Eskalationen konfiguriert; ein überfälliger kritischer Fall alarmiert die zuständige Rolle.
- **Anforderungen:** `LH-6.22-DMG-026`
- **Liefergegenstände:**
  - Backend: Regel je Kategorie (Reaktions- und Lösungsfrist, Wiedervorlageintervall, Eskalationsstufen und Rollen); Worker für Fristüberwachung; Alarm mit Dedupe.
  - Datenbank: `alert_policy_revision`, `business_alert`, `alert_acknowledgement`, `config_revision`, `defect_case`.
  - API: `/v1/config/revisions`, `/v1/defects` (SLA-Status).
  - Oberfläche: UI-11 – SLA-Ampel und Restzeit je Fall, Zustand „Frist überschritten“; Pflege der Regeln im Admin-Bereich.
  - Konfigurationsparameter: `alert_escalation_delay_min`, `notification_quiet_hours`; kategoriebezogene Fristen fehlen in `kataloge.csv` (OP-26).
- **Detailverträge vorab:** Regelschema je Kategorie; Uhr der Fristen (Betriebszeit oder Kalenderzeit, OP-26).
- **Akzeptanzkriterien:**
  1. Ein überfälliger kritischer Fall löst einen Alarm an die zuständige Rolle aus (DMG-026).
  2. Wiedervorlage, Service-Level und Eskalation lassen sich je Schadenskategorie unterschiedlich konfigurieren und wirken ab Publikation der Revision (DMG-026).
  3. Je Fall, Stufe und Empfänger entsteht genau ein Alarm; nach Quittierung ohne Lösung folgt die nächste Stufe.
  4. Kritische Alarme ignorieren `notification_quiet_hours`.
- **Tests:** positiv: Fristablauf im Zeitraffer, Eskalation; Regression AT-34. negativ: Fall vor Frist gelöst → kein Alarm; Regel eines anderen Mandanten wirkt nicht. konkurrierend: zwei Workerinstanzen → kein doppelter Alarm.
- **Abhängigkeiten:** intern: WP-M7c-33.
  - extern: WP-M3-52 (Alarm-Grundlage mit Dedupe und Quittierung)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Service-Level externer Werkstätten (WP-M7c-50).

### WP-M7c-46 – Asset-Stamm für kostenrelevante Betriebsmittel
- **Ziel:** Tankkarten, Ladekarten, Smartphones, Schlüssel und Bordgeräte werden als Assets mit Seriennummer, Eigentümer, Ausgabestatus und Zuordnungshistorie geführt.
- **Anforderungen:** `LH-I.10-AST-001`
- **Liefergegenstände:**
  - Backend: Modul `fleet-safety` (Zuordnung des Bausteins Asset/Inventory zu einem Modul siehe OP-01) – Asset-Aggregat mit Art, Seriennummer, Eigentümer (Rechtsträger), Ausgabestatus, Standort/Depot, Kostenobjektbezug; Zuordnungshistorie als unveränderliche Intervalle.
  - Datenbank: Asset und Asset-Zuordnung (Arbeitsnamen, WP-M7c-02); Bezug zu `legal_entity`, `organization`, `cost_object`, `vehicle`, `driver`; Eindeutigkeit Seriennummer je Mandant und Art.
  - API: neuer Pfad Assets (WP-M7c-01).
  - Oberfläche: UI-11 – Assetliste und Assetdetail mit Historie.
  - Konfigurationsparameter: `fuel_card_mapping` (Tankkarte ↔ Fahrzeug/Kostenstelle).
- **Detailverträge vorab:** Assetarten und Pflichtfelder; Zustandsautomat Ausgabestatus (im Lager, ausgegeben, verloren, ausgesondert); Verhältnis zu `asset_schedule` (Anlagenverzeichnis FCO/FIN).
- **Akzeptanzkriterien:**
  1. Jeder Gegenstand besitzt Seriennummer, Eigentümer, Ausgabestatus und eine historische Zuordnung (AST-001).
  2. Die Historie zeigt lückenlos, wem oder welchem Fahrzeug das Asset wann zugeordnet war; frühere Zuordnungen sind nicht änderbar (AST-001).
  3. Eine doppelte Seriennummer derselben Art im Mandanten wird abgelehnt.
  4. Eigentümer und nutzender Bereich sind getrennte Angaben.
  5. Jede Statusänderung und Zuordnung steht mit Akteur und Zeit im Audit; Assets sind nur innerhalb des eigenen Mandanten und der berechtigten Depots sichtbar.
- **Tests:** positiv: Anlage je Assetart, Zuordnungswechsel. negativ: doppelte Seriennummer, Asset ohne Eigentümer, Zugriff aus fremdem Mandanten, Depot ohne Berechtigung. konkurrierend: gleichzeitige Anlage derselben Seriennummer → genau ein Asset.
- **Abhängigkeiten:** intern: WP-M7c-02.
  - extern: WP-M1-09, WP-M1-08 (Organisationsstruktur mit Betriebshöfen und Kostenstellen, Objektberechtigung)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Abschreibung und Anlagenbuchhaltung (`asset_schedule`, M8); Ausgabevorgang (WP-M7c-47).

### WP-M7c-47 – Materialübernahme und Rückgabe mit digitaler Bestätigung
- **Ziel:** Fahrer dokumentieren Übernahme und Rückgabe von Material mit Zeit und digitaler Bestätigung; nicht zurückgegebene Gegenstände erscheinen als offene Übergabevorgänge.
- **Anforderungen:** `LH-I.10-AST-002`
- **Liefergegenstände:**
  - Backend: Übergabevorgang (Ausgabe, Rückgabe) je Asset und Person mit Zeit, Bestätigung beider Seiten bzw. des Fahrers, Zustand; Liste offener Vorgänge; Fälligkeit zum Schichtende.
  - Datenbank: Asset-Zuordnung (Arbeitsname), `staff_shift`, `audit_event`.
  - API: Unterressource der Assets; Driver-Bestätigung über die Driver-API (Outbox-fähig).
  - Oberfläche: UI-09/UI-10 – „Material übernehmen/zurückgeben“ mit Bestätigung; UI-11 – offene Übergabevorgänge.
- **Detailverträge vorab:** Form der „digitalen Bestätigung“ (angemeldete Bestätigung in der App, OP-27); Zustandsautomat des Vorgangs.
- **Akzeptanzkriterien:**
  1. Übernahme und Rückgabe werden mit Zeit und digitaler Bestätigung des Fahrers gespeichert (AST-002).
  2. Eine nicht zurückgegebene Tankkarte erscheint als offener Übergabevorgang mit Person, Ausgabezeit und Fälligkeit (AST-002).
  3. Ein Asset kann nicht gleichzeitig an zwei Personen ausgegeben sein.
  4. Offline bestätigte Übernahmen werden nach Synchronisation genau einmal wirksam.
  5. Eine zum Schichtende nicht zurückgegebene Ausgabe wird dem Fahrer im Schichtendreport und der Leitstelle in der Liste offener Vorgänge angezeigt.
- **Tests:** positiv: Ausgabe, Rückgabe. negativ: Rückgabe eines nicht ausgegebenen Assets, Bestätigung durch andere Person, fremder Mandant. konkurrierend: zwei gleichzeitige Ausgaben desselben Assets → genau eine.
- **Abhängigkeiten:** intern: WP-M7c-46.
  - extern: WP-M3-24, WP-M3-25 (Driver-App-Basis, Dienstanmeldung)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Fahrzeugübergabe (WP-M7c-52); Verlustabwicklung mit Kostenfolge.

### WP-M7c-48 – Standortlager mit Bestand, Mindestbestand, Entnahme und Inventur
- **Ziel:** Je Standort gibt es ein Lager mit Bestand, Mindestbestand, Entnahmen, Inventuren und Zugriffsrechten; eine Entnahme reduziert den Bestand und löst bei Erreichen des Mindestwerts eine Bestellmeldung aus.
- **Anforderungen:** `LH-I.10-AST-003`
- **Liefergegenstände:**
  - Backend: Lagerartikel je Depot; Bewegungen (Zugang, Entnahme, Inventurdifferenz) als unveränderliche Buchungen; Bestand als Summe der Bewegungen mit Zeilensperre; Bestellmeldung bei Erreichen oder Unterschreiten des Mindestbestands; Inventur mit Zählliste und Differenzbuchung; Zugriffsrechte je Depot.
  - Datenbank: Lagerartikel und Lagerbewegung (Arbeitsnamen, append-only), `organization` (Depot), `business_alert`.
  - API: neuer Pfad Lager (WP-M7c-01).
  - Oberfläche: UI-11 – Lagerbestand je Depot, Entnahmedialog, Inventur, Bestellmeldungen.
  - Konfigurationsparameter: `inventory_min_stock` (Ebene Depot/Artikel, nicht negativ).
  - Ereignisse/Outbox: Mindestbestand erreicht (Bestellmeldung).
- **Detailverträge vorab:** Bewegungsarten; Regel „Erreichen“ (≤ Mindestbestand); Verhalten bei Entnahme über Bestand; Empfänger der Bestellmeldung.
- **Akzeptanzkriterien:**
  1. Eine Entnahme reduziert den Bestand um die entnommene Menge (AST-003).
  2. Erreicht der Bestand durch die Entnahme den Mindestwert, entsteht genau eine Bestellmeldung für den Artikel (AST-003).
  3. Eine Inventur erzeugt Differenzbuchungen statt Überschreiben; die Historie bleibt nachvollziehbar (AST-003).
  4. Nur Nutzer mit Recht für das Depot können entnehmen oder inventarisieren (AST-003).
  5. Der Bestand wird nie negativ; eine Entnahme über Bestand wird abgelehnt.
- **Tests:** positiv: Entnahme bis Mindestbestand, Inventur. negativ: Entnahme über Bestand, fremdes Depot, negativer Mindestbestand. konkurrierend (Kapazität): zwei parallele Entnahmen der letzten Einheiten → keine Überentnahme, genau eine Bestellmeldung.
- **Abhängigkeiten:** intern: WP-M7c-46.
  - extern: WP-M1-09, WP-M1-08 (Betriebshöfe und Depotberechtigung)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Bestellung und Wareneingang (WP-M7c-51); Lagerbewertung in der Finanzbuchhaltung (M8).

### WP-M7c-49 – Versionierte Fahrzeugdokumente mit Gültigkeit
- **Ziel:** Versicherungsnachweise, Genehmigungen, Betriebsanleitungen und Prüfberichte werden je Fahrzeug zentral versioniert; das Fahrzeugdetail zeigt gültige und auslaufende Nachweise.
- **Anforderungen:** `LH-I.10-AST-004`
- **Liefergegenstände:**
  - Backend: Fahrzeugdokument mit Art, Gültigkeit von/bis, Version, Datei; Auswertung „gültig“, „läuft aus“, „abgelaufen“.
  - Datenbank: `document_revision`, `evidence_link`, Fahrzeugdokument (Arbeitsname), `vehicle`.
  - API: neuer Pfad Fahrzeugdokumente (WP-M7c-01); `/v1/operator/vehicles` (Nachweisstatus).
  - Oberfläche: UI-11 – Fahrzeugdetail, Reiter „Nachweise“ mit Status und Restlaufzeit.
  - Konfigurationsparameter: `inspection_warning_days` (HU-/Prüffrist Vorwarnung, Ebene Mandant).
- **Detailverträge vorab:** Dokumentarten und welche davon Pflichtnachweise mit Sperrwirkung sind (OP-28); Uploadvertrag wie WP-M7c-26.
- **Akzeptanzkriterien:**
  1. Das Fahrzeugdetail zeigt alle aktuell gültigen Nachweise sowie die innerhalb der Vorwarnfrist auslaufenden Dokumente (AST-004).
  2. Eine neue Version ersetzt die alte nicht; alle Versionen bleiben abrufbar (AST-004).
  3. Dateien tragen Prüfsumme und sind nur für berechtigte Rollen abrufbar.
- **Tests:** positiv: gültig, auslaufend, abgelaufen. negativ: Gültig-bis vor Gültig-von, Dokument an fremdem Fahrzeug, unberechtigter Abruf. Zuarbeit zu E2E-071 (fällige HU ist als auslaufender bzw. abgelaufener Nachweis sichtbar). konkurrierend: zwei gleichzeitige neue Versionen desselben Dokuments → zwei fortlaufende Versionen oder 412, keine überschriebene Version.
- **Abhängigkeiten:** intern: WP-M7c-02, WP-M7c-26.
  - extern: WP-M4-23 (unveränderliches Original und Versionen)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Warnung und Sperrwirkung bei Ablauf (WP-M7c-53); Fahrerdokumente.

### WP-M7c-50 – Wartungsverträge und Service-Level externer Werkstätten
- **Ziel:** Wartungsverträge mit externen Werkstätten sind mit Service-Level, Ansprechpartner und Gültigkeit hinterlegt; ein Schadenauftrag kann dem zuständigen Vertragsdienstleister zugewiesen werden.
- **Anforderungen:** `LH-I.10-AST-005`
- **Liefergegenstände:**
  - Backend: Dienstleisterstamm und Wartungsvertrag (Leistungsumfang, Fahrzeugklassen/Depots, Service-Level, Ansprechpartner, gültig von/bis); Vorschlag des zuständigen Dienstleisters bei Auftragserstellung; Zuweisung am `repair_order`.
  - Datenbank: Werkstattvertrag (Arbeitsname; `partner_contract_version` ist Kooperationsverträgen vorbehalten, OP-01), `repair_order` (Dienstleister, Vertragsbezug).
  - API: `/v1/repair-orders`; neuer Pfad Werkstattverträge (WP-M7c-01).
  - Oberfläche: UI-11 – Vertragsliste, Zuweisung im Reparaturauftrag mit Anzeige des Service-Levels.
- **Detailverträge vorab:** Zuständigkeitsregel (Fahrzeugklasse, Depot, Schadenskategorie); Service-Level-Felder.
- **Akzeptanzkriterien:**
  1. Ein Schadenauftrag kann dem zuständigen Vertragsdienstleister zugewiesen werden; der Auftrag zeigt Vertrag, Service-Level und Ansprechpartner (AST-005).
  2. Ein abgelaufener Vertrag wird nicht als zuständig vorgeschlagen; die Zuweisung erfordert dann eine bewusste Auswahl mit Hinweis (AST-005).
  3. Vertragsänderungen wirken als neue Version; bestehende Aufträge behalten ihren Vertragsstand.
- **Tests:** positiv: Zuweisung an gültigen Vertrag. negativ: abgelaufener Vertrag, Dienstleister eines anderen Mandanten, Vertrag ohne Gültigkeit.
- **Abhängigkeiten:** intern: WP-M7c-35. extern: keine.
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Zugang der Werkstatt zum System (nicht gefordert); Überwachung der Service-Level-Einhaltung als Kennzahl (M8).

### WP-M7c-51 – Einkaufsanforderung von Genehmigung bis Rechnungsbezug
- **Ziel:** Ein Bedarf wird von der genehmigten Einkaufsanforderung über Bestellung und Wareneingang bis zur verbuchten Rechnung durchgängig verfolgt.
- **Anforderungen:** `LH-I.10-AST-006`
- **Liefergegenstände:**
  - Backend: Einkaufsanforderung mit Zustandsautomat (angefordert, genehmigt, bestellt, Wareneingang, Rechnung zugeordnet, abgeschlossen, abgelehnt); Genehmigung mit Rollentrennung und Schwellen; Wareneingang bucht Lagerzugang; Rechnungsbezug über den Belegweg.
  - Datenbank: Einkaufsanforderung (Arbeitsname), `approval_step`, `supplier_invoice`, `expense_document`, `economic_event`, Lagerbewegung.
  - API: neuer Pfad Einkauf (WP-M7c-01); `/v1/expenses/receipts`, `/v1/expenses/{expense_id}/approve`.
  - Oberfläche: UI-11 – Einkaufsanforderungen mit Verlauf; UI-14 – Rechnung mit Bezug zur Anforderung.
  - Konfigurationsparameter: `approval_stage_thresholds`.
- **Detailverträge vorab:** Zustandsautomat; Teil- und Überlieferung; Abgleich Bestellung/Wareneingang/Rechnung (Umfang gemäß OP-29).
- **Akzeptanzkriterien:**
  1. Ein Ersatzteil ist vom genehmigten Bedarf über Bestellung und Wareneingang bis zur verbuchten Rechnung in einer Ansicht verfolgbar (AST-006).
  2. Ohne Genehmigung kann nicht bestellt werden; Anforderer und Genehmiger sind verschiedene Personen (AST-006).
  3. Der Wareneingang erhöht den Lagerbestand genau einmal.
  4. Die Rechnung erzeugt genau eine Kostenwirkung und verweist auf die Anforderung.
- **Tests:** positiv: Durchlauf mit Ersatzteil aus der Bestellmeldung. negativ: Bestellung ohne Genehmigung, Selbstgenehmigung, Rechnung ohne Wareneingang (Hinweis), fremder Mandant. konkurrierend (Geld): doppelter Wareneingang bzw. doppelte Rechnungszuordnung → eine Wirkung.
- **Abhängigkeiten:** intern: WP-M7c-48.
  - extern: WP-M4-22, WP-M4-34, WP-M4-33 (Belegaggregat, Freigabe-Workflow, Kostenereignis)
- **Entscheidungen:** D-06 (Finanzumfang, ERP-Anbindung des Einkaufs) – blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** elektronische Bestellübermittlung an Lieferanten; Lieferantenbewertung.

### WP-M7c-52 – Digitale Fahrzeugübergabe mit Übergaberevision
- **Ziel:** Ein Fahrerwechsel erzeugt eine von beiden Seiten bestätigte Übergaberevision mit offenen Mängeln, Kilometerstand, Tankstand und übernommenen Schlüsseln.
- **Anforderungen:** `LH-I.10-AST-009`
- **Liefergegenstände:**
  - Backend: Übergabe-Aggregat je Fahrzeug: übergebender und übernehmender Fahrer, Kilometerstand, Tank-/Ladestand, Schlüssel (Assets), Liste offener Mängel zum Zeitpunkt; Bestätigung beider Seiten; unveränderliche Revision.
  - Datenbank: Fahrzeugübergabe (Arbeitsname, append-only), `vehicle_assignment`, `defect_case`, Asset-Zuordnung, `inspection_run`.
  - API: neuer Pfad Übergaben (WP-M7c-01); Driver-Bestätigung über die Driver-API.
  - Oberfläche: UI-09/UI-10 – Übergabe/Übernahme mit Anzeige offener Mängel; UI-11 – Übergabehistorie je Fahrzeug.
  - Ereignisse/Outbox: Fahrzeug übergeben.
- **Detailverträge vorab:** Zustandsautomat Übergabe; Verhältnis zur Schichtende- und Vor-Schicht-Prüfung (keine Doppelerfassung); Übergabe ohne anwesenden Vorgänger (OP-30).
- **Akzeptanzkriterien:**
  1. Ein Fahrerwechsel erzeugt eine bestätigte Übergaberevision, die die zum Zeitpunkt offenen Mängel enthält (AST-009).
  2. Die Revision enthält Kilometerstand, Tankstand und übernommene Schlüssel (AST-009).
  3. Eine bestätigte Revision ist unveränderlich; Korrekturen entstehen als neue Revision mit Bezug.
  4. Die Übergabe ersetzt nicht die Pflichtprüfung des übernehmenden Fahrers; die Einsatzfreigabe folgt den bestehenden Regeln.
  5. Die Schlüsselzuordnung im Asset-Stamm wechselt mit der Bestätigung.
- **Tests:** positiv: Wechsel mit zwei offenen Mängeln. negativ: Bestätigung nur einer Seite → keine bestätigte Revision; Kilometerstand kleiner als letzter Stand; fremdes Fahrzeug. konkurrierend: zwei Fahrer übernehmen gleichzeitig dasselbe Fahrzeug → genau eine Übernahme.
- **Abhängigkeiten:** intern: WP-M7c-22, WP-M7c-47.
  - extern: WP-M3-22, WP-M3-21 (Fahrzeugwechsel in laufender Schicht, bekannte Altschäden quittieren)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Übergabe an Werkstatt oder Partner; Schlüsseltresor-Hardware.

### WP-M7c-53 – Vertragskalender mit Ablaufwarnung
- **Ziel:** Ein Kalender führt den Ablauf von Genehmigungen, Nachweisen und Dienstleisterverträgen; ein bevorstehender Ablauf warnt den zuständigen Mandanten, und eine abgelaufene Pflichtfrist wirkt auf die Fahrzeugverfügbarkeit.
- **Anforderungen:** `LH-I.10-AST-011`
- **Liefergegenstände:**
  - Backend: Fristenprojektion aus Fahrzeugdokumenten, Werkstattverträgen und Genehmigungen; täglicher Worker für Vorwarnung und Ablauf; bei abgelaufenem Pflichtnachweis Meldung an den Verfügbarkeitsdienst (Warnen oder Sperren gemäß Freigaberegel).
  - Datenbank: Fahrzeugdokument, Werkstattvertrag (Arbeitsnamen), `business_alert`, `alert_policy_revision`, `notification`.
  - API: Fristenliste (neuer Pfad, WP-M7c-01); `/v1/operator/vehicles`.
  - Oberfläche: UI-11 – Vertragskalender mit Filter nach Art und Fälligkeit, Zustand „Frist überschritten“.
  - Konfigurationsparameter: `inspection_warning_days`, `maintenance_reminder_km`, `alert_escalation_delay_min`.
- **Detailverträge vorab:** Freigaberegel „warnt/sperrt“ je Nachweisart (OP-28); Empfängerrolle „zuständiger Mandant“; Zeitzone der Fristberechnung (`reporting_timezone`).
- **Akzeptanzkriterien:**
  1. Ein bevorstehender Genehmigungsablauf löst innerhalb der Vorwarnfrist eine Warnung beim zuständigen Mandanten aus (AST-011).
  2. Der Kalender zeigt Genehmigungen, Nachweise und Dienstleisterverträge mit Ablaufdatum (AST-011).
  3. Je Frist, Stufe und Empfänger entsteht genau eine Warnung; nach Verlängerung des Dokuments entfällt sie.
  4. Liegt eine fällige HU vor einer geplanten Fahrzeugschicht, warnt bzw. sperrt das System gemäß der hinterlegten Freigaberegel; die Schicht ist entsprechend gekennzeichnet (E2E-071).
  5. Warnungen eines Mandanten erreichen keinen anderen Mandanten.
- **Tests:** positiv: Vorwarnung, Ablauf; E2E-071 (Wartungsablauf). negativ: verlängerter Nachweis → keine Warnung; Regel „nur warnen“ sperrt nicht. konkurrierend: zwei Workerinstanzen → keine Doppelwarnung; Ablauf um Mitternacht mit DST-Wechsel. Bezug: PH-AT-018 (DST-Fixture).
- **Abhängigkeiten:** intern: WP-M7c-49, WP-M7c-50, WP-M7c-14. extern: keine.
- **Entscheidungen:** keine (die „rechtskonforme Freigaberegel“ aus E2E-071 ist vom Betreiber zu liefern, OP-28 – blockiert die Abnahme von E2E-071)
- **Größe:** M
- **Nicht im Umfang:** Fristen von Kooperationsverträgen (M6b, UI-18); Fahrerqualifikationsfristen (M3/M7d).

### WP-M7c-54 – Gate-Nachweis M7c und Regressionssuite
- **Ziel:** Der M7c-Anteil des M7-Gates ist mit Build, Evidenz und Entscheidung nachgewiesen; die P1-Sicherheitsregeln sind nach dem Ausbau unverändert wirksam.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: Akzeptanzszenarien unter `tests/acceptance/` für AT-57, AT-62, E2E-071, E2E-100 sowie Regression E2E-069; Zwei-Mandanten-Fixtures für Assets, Lager, Unfallakte; Evidenzeinträge in `e2e.csv`.
  - Backend: Negativtests je Invariante mit zwei parallelen Transaktionen (Kap. 04 §4.5) für die neuen Aggregate; Architekturtest Modulgrenzen.
  - Oberfläche: UI-Abnahmefall „Driverprüfpfad mit kritischem Nein und Offline-Sync“ (Kap. 06 §6.9) mit den P2-Erweiterungen; Screenreader-Evidenz aus WP-M7c-31.
- **Detailverträge vorab:** Zuordnung der Gate-Tests zu M7c ist mit dem Auftraggeber bestätigt (WP-M0-33).
- **Akzeptanzkriterien:**
  1. AT-57 und AT-62 sind grün mit Evidenz.
  2. E2E-071 ist grün mit der vom Betreiber gelieferten Freigaberegel.
  3. E2E-100 ist grün gegen den Sandbox-Partner; ohne Grant entsteht keine Zuweisung.
  4. E2E-069 und AT-46 sind nach dem Ausbau der Offline-Synchronisation weiterhin grün.
  5. Die M3-Regression (PH-AT-020, AT-26 bis AT-36, AT-50, AT-51, AT-60) ist grün.
  6. Alle 32 MUSS-Anforderungen der Einheit haben einen grünen Test; für die 22 SOLL-Anforderungen ist der Teststatus ausgewiesen, Abweichungen mit Begründung und AG-Freigabe (Kap. 08 §8.4).
  7. Für jede neue oder erweiterte Ansicht sind die Leere-, Fehler- und Rechtezustände ihrer UI-ID (Kap. 06 §6.9), der 412-Konflikt ohne stilles Überschreiben und der Mandantenwechsel mit zwei Tabs nachgewiesen.
  8. Die außerhalb des M7-Gates liegenden Regressionen AT-10, AT-11, AT-32, AT-33, AT-53, AT-54, AT-59, AT-61 und E2E-068 sind nach dem Ausbau weiterhin grün; PH-AT-001, PH-AT-011, PH-AT-016 und PH-AT-017 sind für die neuen Ressourcen und Ereignisse wiederholt.
  9. Jeder Evidenzeintrag enthält `source_key[]`, Build, Migrationsstand, Konfigurationsrevision, Rollen und Mandanten und keine unnötigen Fahrgast- oder Personaldaten (Kap. 08 §8.4).
- **Tests:** positiv: die genannten Referenztests. negativ: Mandantentrennung für jede neue Ressource (Fremd-ID, manipulierte `tenant_id`). konkurrierend: Sammelsuite der Konkurrenztests aus WP-M7c-06, -09, -11, -13, -14, -15, -17, -18, -21, -25, -29, -36, -38, -44, -47, -48, -51, -52.
- **Abhängigkeiten:** intern: WP-M7c-01, WP-M7c-02, WP-M7c-03, WP-M7c-04, WP-M7c-05, WP-M7c-06, WP-M7c-07, WP-M7c-08, WP-M7c-09, WP-M7c-10, WP-M7c-11, WP-M7c-12, WP-M7c-13, WP-M7c-14, WP-M7c-15, WP-M7c-16, WP-M7c-17, WP-M7c-18, WP-M7c-19, WP-M7c-20, WP-M7c-21, WP-M7c-22, WP-M7c-23, WP-M7c-24, WP-M7c-25, WP-M7c-26, WP-M7c-27, WP-M7c-28, WP-M7c-29, WP-M7c-30, WP-M7c-31, WP-M7c-32, WP-M7c-33, WP-M7c-34, WP-M7c-35, WP-M7c-36, WP-M7c-37, WP-M7c-38, WP-M7c-39, WP-M7c-40, WP-M7c-41, WP-M7c-42, WP-M7c-43, WP-M7c-44, WP-M7c-45, WP-M7c-46, WP-M7c-47, WP-M7c-48, WP-M7c-49, WP-M7c-50, WP-M7c-51, WP-M7c-52, WP-M7c-53.
  - extern: WP-M6b-03, WP-M6a-03 (Sandbox-Partner mit Kooperationsvertrag und Reserven für E2E-100)
- **Entscheidungen:** D-08, D-11 – blockieren nur die Produktivschaltung der Partneroption bzw. des Notbetriebs.
- **Größe:** M
- **Nicht im Umfang:** Gate-Tests der Einheiten M7a, M7b, M7d; Last- und Restore-Tests (M8).

## Offene Punkte

- **OP-01 Keine Tabellen und kein Modul für AST** (`LH-I.10-AST-001`, `LH-I.10-AST-002`, `LH-I.10-AST-003`, `LH-I.10-AST-005`, `LH-I.10-AST-006`, `LH-I.10-AST-009`): Kap. 04 §4.4 enthält für Asset, Inventory, Depot und Workshop keine Tabellen (nur `asset_schedule` unter FCO/FIN), und die Zielstruktur des Repositorys nennt kein Modul dafür. Diese Datei plant mit Arbeitsnamen im Modul `fleet-safety`; Modulzuschnitt und DDL sind in WP-M7c-01 zu beschließen. `partner_contract_version` ist Kooperationsverträgen zugeordnet; ob Werkstattverträge dort oder in einer eigenen Tabelle liegen, ist offen.
- **OP-02 Fehlende Endpunkte** (AST-Anforderungen, `LH-6.21-CHK-017`, `LH-6.22-DMG-024`): Kap. 05 §5.7 nennt keine Pfade für Vorlagenverwaltung, Assets, Lager, Einkauf, Fahrzeugdokumente, Werkstattverträge, Übergaben, Unfallakte und Evidenz-Upload. `/v1/reports/jobs` wird hier für Exporte angenommen.
- **OP-03 Unfallakte ohne Datenmodell** (`LH-I.10-AST-007`, `LH-6.22-DMG-020`, `LH-6.22-DMG-022`): Feldumfang, Rollen und Tabelle der Unfallakte sind nicht beschrieben (siehe auch M3 OP-23). AST-007 ist MUSS, DMG-020 und DMG-022 sind SOLL; die Reihenfolge ist so geschnitten, dass die MUSS-Akte ohne die SOLL-Teile abnehmbar ist.
- **OP-04 „Systemkanal“** (`LH-6.6-DRV-009`): Nicht festgelegt ist, ob der geschützte Kontakt nur Textnachrichten oder auch maskierte Anrufe umfasst und was gilt, wenn der Systemkanal nicht aktiv ist („sofern Systemkanal aktiv“).
- **OP-05 Umfang des Fahrtenbuchs** (`LH-6.6-DRV-011`): „Relevante betriebliche Strecken“ ist nicht definiert (Leerfahrten, Werkstattfahrten, Privatnutzung?), ebenso wenig das Exportformat und die Aufbewahrungsfrist.
- **OP-06 Offlinekarte** (`LH-6.6-DRV-014`): Umfang des lokal vorgehaltenen Kartenausschnitts und das Nutzungsrecht zur Offline-Speicherung hängen an D-09 und sind nicht festgelegt.
- **OP-07 „Unzulässige Zuweisung“ in der Pause** (`LH-6.6-DRV-016`): Offen ist, ob jede Zuweisung während einer aktiven Pause unzulässig ist oder nur solche mit Abholung innerhalb der Pause, wie lange im Voraus eine Pause anzukündigen ist und ob die Leitstelle eine Pause ablehnen darf.
- **OP-08 Trinkgeld** (`LH-6.6-DRV-018`): `kataloge.csv` enthält keinen Aktivierungsparameter. Begünstigter (Fahrer oder Betreiber), Auszahlungsweg, steuerliche Behandlung und der Zeitpunkt der Erhebung sind nicht beschrieben; Kap. 03 nennt nur die Preiskomponente „freiwilliges Trinkgeld“. Die Abnahme verlangt einen Auszahlungsnachweis, ein Auszahlungsprozess an Fahrer ist aber nirgends spezifiziert.
- **OP-09 GPS-Toleranz** (`LH-6.6-DRV-019`): Für die Plausibilitätsprüfung gibt es keinen Parameter; `eta_max_data_age_sec` liegt auf Ebene „Modell“. Toleranzwerte (Genauigkeit, Sprungweite, Geschwindigkeit) fehlen.
- **OP-10 Freigabe über Schichtschluss** (`LH-6.8-FLT-005`): Wann „verhindert“ und wann „separat freigegeben“ gilt, welche Rolle freigibt und ob der Fahrer zustimmen muss, ist nicht festgelegt.
- **OP-11 Übereinstimmung des Schichtberichts** (`LH-6.8-FLT-008`): Die Toleranz für „stimmt überein“ und der Vorrang zwischen Kilometerquellen (Eingabe, Telematik, GPS) sind offen.
- **OP-12 Freigaberolle der Vorlagen** (`LH-6.21-CHK-017`): Ob die Vorlagenfreigabe eine Vier-Augen-Regel braucht und wer „verantwortlicher Herausgeber“ sein darf, ist nicht beschrieben. Die Begriffe Fahrzeugklasse und Prüfart sind weiterhin ohne Katalog (M3 OP-14).
- **OP-13 Wetterbedingung** (`LH-6.21-CHK-019`): Eine Wetterdatenquelle ist im Pflichtenheft nicht vorgesehen; offen ist, ob Wetter automatisch oder durch manuelle Aktivierung der Leitstelle bestimmt wird.
- **OP-14 Übersteuerung kontra unaufhebbare Sperre** (`LH-6.6-DRV-013`, `LH-6.8-FLT-007`, `LH-6.21-CHK-023`): DRV-013 erlaubt bei kritischem Mangel eine „autorisierte Übersteuerung“, CHK-023 erklärt kritische Sperren für „unaufhebbar ohne erneute Freigabe“. Geplant ist: Übersteuerung nur als formale Freigabeentscheidung über `vehicle_release_decision`; das ist zu bestätigen. Was die „erneute Freigabe“ mindestens umfasst, ist offen.
- **OP-15 Fundsachen** (`LH-6.21-CHK-020`, AT-62): Für Fundsachen gibt es weder Tabelle noch Prozess; AT-62 verlangt einen „eröffneten Folgeprozess“. Ob dieser ein Supportfall (`support_case`, M7b) oder ein eigener Vorgang ist, ist zu entscheiden.
- **OP-16 Vorlauf der Prüfpflicht** (`LH-6.21-CHK-021`): `kataloge.csv` kennt keinen Parameter für Vorlauf, Erinnerungsstufen oder Eskalationsschwelle der Prüfung; neue Konfigurationsschlüssel sind zu definieren.
- **OP-17 Medienprüfung** (`LH-6.21-CHK-024`, `LH-6.22-DMG-023`): Das Verfahren zur Prüfung „personenbezogener Inhalte“ ist nicht beschrieben. Das Entfernen von EXIF-Daten verändert die Datei; welche Fassung die beweisrelevante Prüfsumme trägt und ob das Original aufbewahrt wird, ist zu entscheiden. Die Abnahmeformulierung „Upload ohne erforderliche Zusatzmetadaten“ ist sprachlich unklar und wird hier als „ohne nicht erforderliche Zusatzmetadaten“ gelesen.
- **OP-18 Signatur und Exportschutz** (`LH-6.21-CHK-025`, `LH-6.22-DMG-024`): „Signiert oder mit Prüfsumme“ lässt das Verfahren offen (qualifizierte Signatur, Siegel, Hash); ebenso der „Schutz“ des Schadenexports (Passwort, befristeter Link, Verschlüsselung).
- **OP-19 Notbetriebsverfahren** (`LH-6.21-CHK-026`): Das „separat freigegebene Notbetriebsverfahren“ ist nicht beschrieben und hängt an D-11; ohne Betreiberangabe wird nur der serverbestätigte Weg gebaut.
- **OP-20 Kennzahlen der Checklisten** (`LH-6.21-CHK-028`): Die vier Kennzahlen stehen nicht unter den 48 KPIs in `kataloge.csv`; Zähler, Nenner und die Definition von „Wiederholungsfehler“ fehlen.
- **OP-21 „Festgelegte Freigabe“ vor Reparatur** (`LH-6.22-DMG-015`): Nicht festgelegt ist, ob damit eine Kostenfreigabe, eine technische Beauftragung oder beides gemeint ist und welche Rolle sie erteilt. Die Abbildung der P1-Status aus M3 auf die neun Status ist zu beschließen.
- **OP-22 „Relevante“ Folgecheckliste** (`LH-6.22-DMG-017`): Welche Checkliste nach welcher Reparatur erforderlich ist und wer sie durchführen darf (Fahrer oder qualifizierte Stelle, vgl. Kap. 05 §5.7 „qualifizierte Folgeprüfung“), ist offen.
- **OP-23 Dublettenregel** (`LH-6.22-DMG-019`): Zeit- und Ortsfenster der Ähnlichkeitsprüfung sind nicht angegeben.
- **OP-24 Löschfristen Drittbeteiligte** (`LH-6.22-DMG-022`): „Definierte Löschfristen“ sind nicht beziffert; abhängig von D-11.
- **OP-25 Fahrzeugausfall und Erstattungsdaten** (`LH-6.22-DMG-025`, AT-57): Wie die Kostenart „Fahrzeugausfall“ bewertet wird (hängt an D-07) und was „Erstattungsdaten“ in AT-57 umfasst (Versicherung, Verursacher, Mitarbeitererstattung), ist nicht beschrieben.
- **OP-26 Service-Level je Kategorie** (`LH-6.22-DMG-026`): Kategorienkatalog, Fristwerte und die Zeitbasis (Kalender- oder Betriebszeit) fehlen; `kataloge.csv` enthält dafür keinen Parameter.
- **OP-27 Digitale Bestätigung** (`LH-I.10-AST-002`, `LH-I.10-AST-009`): Die Form der Bestätigung (angemeldeter Klick, Unterschrift auf dem Display, zweite Person) ist nicht festgelegt.
- **OP-28 Sperrwirkung abgelaufener Nachweise** (`LH-I.10-AST-004`, `LH-I.10-AST-011`, E2E-071): E2E-071 verlangt „warnt/sperrt entsprechend rechtskonformer Freigaberegel“; diese Regel und die Liste der Pflichtnachweise mit Sperrwirkung liefert das Pflichtenheft nicht. `maintenance_reminder_km` und `inspection_warning_days` regeln nur die Vorwarnung.
- **OP-29 Umfang des Einkaufs** (`LH-I.10-AST-006`): Lieferantenstamm, Teil- und Überlieferung, Preisabweichung und der Abgleich Bestellung/Wareneingang/Rechnung sind nicht beschrieben; abhängig von D-06.
- **OP-30 Übergabe ohne Vorgänger** (`LH-I.10-AST-009`): Ob eine Übergabe auch ohne anwesenden übergebenden Fahrer (Fahrzeug stand im Depot) eine „bestätigte Übergaberevision“ erzeugt und wer dann bestätigt, ist offen. „Tankstand“ ist für E-Fahrzeuge als Ladestand zu lesen; das ist zu bestätigen.
- **OP-31 Zuordnung von E2E-069** (`LH-6.6-DRV-015`): Der Gate-Test E2E-069 (Offline Tankbeleg) wird fachlich bereits von WP-M4-26 erfüllt und ist hier nur als Regression geführt; die Zuordnung zum M7-Gate ist in WP-M0-33 zu bestätigen.
- **OP-32 Abgleich mit `arbeitspakete.csv`**: Nach Freigabe dieser Datei sind WP-IDs, Detailverträge und Testfälle je Anforderung dort nachzutragen (außerhalb dieses Auftrags).
- **OP-33 UI-Zuordnung der AST-Ansichten** (`LH-I.10-AST-001`, `LH-I.10-AST-003`, `LH-I.10-AST-006`, `LH-I.10-AST-011`): Kap. 06 §6.9 nennt keine eigene Ansicht für Assets, Standortlager, Einkauf und Vertragskalender; diese Datei ordnet sie UI-11 (Flotte/Werkstatt) zu. Ebenso fehlt eine UI-ID für die Pflege von Alarm-/Fristregeln (in WP-M7c-23 und WP-M7c-45 über den Konfigurationseditor aus Kap. 06 §6.8 angenommen). Zu bestätigen in WP-M7c-01.
- **OP-34 Auszahlungsnachweis Trinkgeld kontra Abhängigkeit M6c** (`LH-6.6-DRV-018`): Die Abnahme verlangt Trinkgeld als separaten Posten der Auszahlung; `payout_instruction` und `settlement_statement` gehören zum Clearing (M6c). Liegt M6c zum Bau von WP-M7c-11 nicht vor, ist nur der Quittungsteil abnehmbar.
- **OP-35 Fahrzeugreinigung** (`LH-I.10-AST-008`): Für Reinigungsfenster gibt es weder Tabelle noch Parameter; offen ist, ob Reinigung als geplantes Fenster, als Folge des Sauberkeitsbefunds aus dem Schichtendcheck (`LH-6.21-CHK-020`) oder beides entsteht.
- **OP-36 Rollen des Notfallalarms** (`LH-6.6-DRV-012`, `LH-6.22-DMG-021`): Die „definierten Leitstellenrollen“ und die „dokumentierten Eskalationskontakte“ sind nicht benannt; sie sind je Mandant vom Betreiber zu liefern (D-11). Ebenso ist offen, ob der Notfallalarm eine Unfallakte (WP-M7c-40) automatisch anlegt oder nur verknüpfbar macht.
- **Ungedeckte Abhängigkeit `supplier_invoice`** (WP-M7c-02, mittelbar WP-M7c-35, WP-M7c-44, WP-M7c-51): Die Tabelle `supplier_invoice` wird als „bestehend aus M4“ vorausgesetzt, ist dort aber ausdrücklich nicht im Umfang (M4: „`supplier_invoice` mit Kreditor und Fälligkeit (DOC-016, M8)“). Geliefert wird sie erst durch WP-M8a-02/WP-M8a-07; WP-M8a-02 hängt seinerseits von WP-M7c-02 ab, eine Referenz dorthin ergäbe einen Zyklus. Zu entscheiden: Minimaltabelle in WP-M7c-02 anlegen (M8a erweitert) oder Rechnungsbezug in M7c nur über `expense_document` führen.
