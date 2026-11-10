# M4 – Personal, Zeit, Belege, Kosten, Kennzahlen

- **Meilenstein:** M4 (setzt M0 und M1 voraus; läuft parallel zu M2 und M3 und nutzt deren Buchungs-, Fahrtenplan-, Fahrer- und Fahrzeugdaten)
- **Phase:** P1 (alle Anforderungen MUSS, P1; Phase und Priorität unverändert)
- **Anzahl Anforderungen:** 53 (SCH 10, TIM 6, DOC 13, FCO 5, KPI 14, ANA 3, ETA 2)
- **Anzahl WPs:** 65 (50 mit Anforderungen, davon 3 mit je zwei Anforderungen; 15 Enabler)

**Ziel:** Nach M4 plant ein Personalplaner Schichten in einem Dienstplan mit getrenntem Entwurf und veröffentlichter Revision (UI-12); Konflikte aus Doppelbelegung, Qualifikation, Abwesenheit und Ruhezeit sind vor der Veröffentlichung sichtbar. Fahrer stempeln ihre Ist-Arbeitszeit getrennt vom Plan, auch offline und DST-fest (UI-13). Belege werden über Uploadticket und Quarantäne mit unveränderlichem Original erfasst, per OCR als Vorschlag ausgelesen, auf Dubletten geprüft, nach Rollentrennung freigegeben und genau einmal als Kostenereignis mit ausgeglichenem Journal gebucht (UI-14); Kosten sind centgenau auf Kostenobjekte verteilbar und je Fahrzeug auswertbar (UI-15). Ein Kennzahlregister liefert die P1-Kennzahlen mit Definitionsversion, Nenner, Datenstand und Vollständigkeit, der Nachfragetrichter beruht auf `travel_attempt`, und die ETA beruht auf einem fahrerneutralen, regelkonformen Grundmodell.

**Gate M4 (Umsetzungsplan §4):** PH-AT-012, PH-AT-013, PH-AT-015, PH-AT-019, PH-AT-021; AT-37, AT-39 bis AT-41, AT-45, AT-47, AT-48, E2E-064 bis E2E-068, E2E-072, E2E-073, E2E-080 bis E2E-084. Mehrere dieser Tests verlangen Funktionen, deren Anforderungen P2 sind; siehe Offene Punkte 1 bis 7.

**Zusagen an andere Meilensteine:** Andere WP-Dateien verweisen namentlich auf WP-IDs dieser Datei (u. a. WP-M4-21 aus M3, WP-M4-61/62 aus M2 und M3, WP-M4-14/16/17/46/63 aus M5). Die WP-IDs dieser Datei werden deshalb nicht umnummeriert; neue WPs werden hinten angefügt. M1 erwartet aus M4 außerdem einen `ImpactProvider` für Schichten (WP-M4-17) und die Beleg-, Finanz-, Arbeitszeit- und Abwesenheitsendpunkte für den vollständigen Lauf von PH-AT-001, AT-49 und E2E-091 (WP-M4-64); M2 und M3 erwarten das ETA-Grundmodell als Quelle der laufenden ETA (WP-M4-62) und die fachlich führende Pflege von `staff_shift` (WP-M4-65).

**Lesehinweise:**
- „Planungsdefault“ kennzeichnet Werte, die das Pflichtenheft selbst als vorläufig bezeichnet. „Vorschlag dieses Plans“ kennzeichnet Festlegungen, die im Pflichtenheft fehlen und im Detailvertrag (WP-M4-01) zu bestätigen sind.
- Kurz-IDs in Klammern hinter Akzeptanzkriterien verweisen auf die Anforderung dieser Einheit (z. B. DOC-009 = `LH-I.1-DOC-009`).
- In `openapi.yaml` ausformuliert sind nur `/v1/rosters/{roster_id}/publish`, `/v1/expenses/{expense_id}/approve`, `/v1/cost-events/{cost_event_id}/allocations` und `/v1/driver/events`. Alle übrigen Pfade stammen aus dem Endpunktkatalog (Kap. 05 §5.7); Unterpfade sind Vorschläge dieses Plans.
- Im Kern-DDL (`kernschema.sql`) vorhanden: `staff_shift`, `resource_release`, `vehicle`, `travel_attempt`, `expense_document`, `cost_object`, `economic_event`, `allocation_set`, `allocation_line`, `fiscal_period`, `journal`, `journal_line`. Alle anderen genannten Tabellen stammen aus Kap. 04 §4.4 und werden in M4 als Migration neu angelegt (Mandantenschlüssel `(tenant_id,id)`, zusammengesetzte FKs, RLS, Revision, Audit).
- Für alle WPs gelten die Leitlinien des Umsetzungsplans §2. Module: `staff-time`, `documents-finance`, `analytics`; Worker `workers/documents`; Adapter `ocr`, `erp-export`. Ein Modul schreibt nur eigene Tabellen.
- Enabler-WPs bauen Fähigkeiten, die der Meilensteinplan für M4 nennt (z. B. Fiskalperiode, ERP-Export), deren zugehörige Anforderungen aber P2 sind. Sie liefern nur den für die P1-Anforderungen und das Gate nötigen Ausschnitt; die P2-Anforderung selbst bleibt in M7/M8 und wird dort abgenommen.

## Übersicht

| WP-ID | Titel | Anforderungen (Anzahl) | Größe | hängt ab von |
|---|---|---:|---|---|
| WP-M4-01 | Detailverträge und Zustandsautomaten M4 | 0 | M | WP-M0-31, WP-M0-13, WP-M1-03, WP-M1-17, WP-M1-19 |
| WP-M4-02 | Referenzfixtures M4 und Leseports zu Buchung, Fahrt und Flotte | 0 | M | WP-M4-01, WP-M0-08, WP-M0-17, WP-M0-18, WP-M1-05, WP-M1-09, WP-M1-01, WP-M2-16, WP-M2-11, WP-M3-03, WP-M3-05 |
| WP-M4-03 | Schichtmodell mit Pausenplan, Qualifikation und Personalbedarf | 1 | M | WP-M4-01, WP-M4-02, WP-M4-64, WP-M1-09, WP-M1-18, WP-M1-02, WP-M3-05 |
| WP-M4-04 | Dienstplan-Entwurf getrennt vom veröffentlichten Plan | 1 | M | WP-M4-03, WP-M1-07, WP-M1-08, WP-M1-46 |
| WP-M4-05 | Offene Schichten und unbesetzter Bedarf | 1 | S | WP-M4-04 |
| WP-M4-06 | Abwesenheit als Verfügbarkeitsrestriktion (Minimalmodell) | 0 | S | WP-M4-01, WP-M4-64, WP-M1-31, WP-M1-07 |
| WP-M4-07 | Konfliktprüfung: Doppelbelegung, Qualifikation, Abwesenheit | 1 | M | WP-M4-04, WP-M4-06, WP-M3-05 |
| WP-M4-08 | Ist-Arbeitszeit als Zeitereignisse | 1 | M | WP-M4-01, WP-M4-64, WP-M1-13, WP-M1-14, WP-M1-46, WP-M1-31 |
| WP-M4-09 | Trennung von Schicht-Soll und Arbeitszeit-Ist | 1 | S | WP-M4-03, WP-M4-08, WP-M0-03 |
| WP-M4-10 | Zeitrechnung über Mitternacht, Zeitzone und DST | 1 | S | WP-M4-03, WP-M4-08, WP-M1-18 |
| WP-M4-11 | Stempeln in der Fahrer-App | 1 | M | WP-M4-08, WP-M3-24, WP-M3-25, WP-M3-13, WP-M3-14 |
| WP-M4-12 | Offline-Stempel mit einmaliger Übernahme | 1 | M | WP-M4-11, WP-M0-28, WP-M3-24 |
| WP-M4-13 | Hinweise zu Pausen, Ruhezeit und Überschreitungen | 1 | M | WP-M4-04, WP-M4-08, WP-M4-09, WP-M4-10, WP-M1-21 |
| WP-M4-14 | Veröffentlichung des Dienstplans | 1 | M | WP-M4-05, WP-M4-07, WP-M4-13, WP-M1-13, WP-M1-14, WP-M1-02, WP-M2-32 |
| WP-M4-15 | Änderungs- und Veröffentlichungsrevisionen der Schicht | 1 | S | WP-M4-14, WP-M1-46, WP-M1-12 |
| WP-M4-16 | Mandanten- und Depotberechtigung im Dienstplan | 1 | M | WP-M4-04, WP-M1-08, WP-M1-01 |
| WP-M4-17 | Fahrerschicht, Fahrzeugbereitstellung und Disponierbarkeit | 1 | M | WP-M4-05, WP-M4-14, WP-M3-08, WP-M1-23 |
| WP-M4-18 | UI-12: Planer-Dashboard Tag, Woche, Monat | 1 | M | WP-M4-05, WP-M4-07, WP-M4-16, WP-M1-43, WP-M1-44 |
| WP-M4-19 | UI-12: Schichten per Klick, Kopie und Drag-and-drop | 1 | M | WP-M4-18, WP-M1-43 |
| WP-M4-20 | UI-13: Eigene Zeiten und Abwesenheit (Grundausbau) | 0 | M | WP-M4-09, WP-M4-13, WP-M4-14, WP-M1-43, WP-M1-44, WP-M3-24 |
| WP-M4-21 | Uploadticket, Quarantäne und Malware-Prüfung | 0 | M | WP-M4-01, WP-M4-64, WP-M0-08, WP-M1-11, WP-M1-12, WP-M1-36 |
| WP-M4-22 | Belegaggregat mit einheitlicher Struktur für vier Eingangskanäle | 1 | M | WP-M4-21, WP-M1-14, WP-M1-07 |
| WP-M4-23 | Unveränderliches Original, Versionen und Prüfspur | 1 | M | WP-M4-22, WP-M1-46, WP-M1-12 |
| WP-M4-24 | Hash und Integritätsprüfung der Originale | 1 | S | WP-M4-23, WP-M1-12, WP-M1-46, WP-M1-01 |
| WP-M4-25 | Belegerfassung per Kamera in der Fahrer-App | 1 | M | WP-M4-22, WP-M4-23, WP-M3-24, WP-M3-44 |
| WP-M4-26 | Offline-Belegerfassung mit einmaliger Synchronisation | 1 | M | WP-M4-25, WP-M3-24 |
| WP-M4-27 | OCR-Adapter und Feldextraktion | 1 | M | WP-M4-23, WP-M1-15, WP-M1-21 |
| WP-M4-28 | OCR als unbestätigter Vorschlag mit Bestätigungspflicht | 1 | M | WP-M4-27 |
| WP-M4-29 | Kostenzuordnung zu Firma, Kostenstelle oder Fahrzeug | 1 | M | WP-M4-22, WP-M1-05, WP-M1-09, WP-M1-08, WP-M3-03 |
| WP-M4-30 | Tankbeleg-Fachdaten | 1 | M | WP-M4-28, WP-M4-29, WP-M3-03 |
| WP-M4-31 | Tankplausibilität mit kontrollierbaren Hinweisen | 1 | M | WP-M4-30, WP-M1-21 |
| WP-M4-32 | Dublettenerkennung und Evidenzverknüpfung | 1 | M | WP-M4-24, WP-M4-28, WP-M4-30 |
| WP-M4-33 | Kostenereignis, Journal und einmaliges Posting | 0 | L | WP-M4-01, WP-M4-29, WP-M0-16, WP-M0-20, WP-M1-14, WP-M1-13, WP-M1-02, WP-M1-05 |
| WP-M4-34 | Freigabe-Workflow mit Rollentrennung und Vier-Augen-Schwelle | 1 | M | WP-M4-28, WP-M4-29, WP-M4-31, WP-M4-32, WP-M4-33, WP-M1-07, WP-M1-13, WP-M1-46 |
| WP-M4-35 | Belegaufteilung auf mehrere Kostenobjekte (centgenau) | 1 | M | WP-M4-33, WP-M4-34, WP-M1-02, WP-M1-13 |
| WP-M4-36 | Fiskalperiode, Periodenabschluss und Gegenereignis | 0 | M | WP-M4-33, WP-M4-35 |
| WP-M4-37 | ERP-Export als wiederholsicherer Job | 0 | M | WP-M4-33, WP-M4-34, WP-M4-36, WP-M1-15, WP-M1-07 |
| WP-M4-38 | UI-14: Belegprüfung und Freigabe | 0 | M | WP-M4-28, WP-M4-31, WP-M4-32, WP-M4-34, WP-M4-35, WP-M4-36, WP-M1-43 |
| WP-M4-39 | Kostenklassifikation: fix/variabel und direkt/umgelegt | 1 | M | WP-M4-33, WP-M4-35 |
| WP-M4-40 | Strukturierte Fahrzeugkosten nach Kostenart und Monat | 1 | M | WP-M4-34, WP-M4-39, WP-M3-03 |
| WP-M4-41 | Kilometerstände aus mehreren Quellen mit Herkunft | 1 | M | WP-M4-02, WP-M4-30, WP-M1-14, WP-M3-17, WP-M3-14, WP-M3-34 |
| WP-M4-42 | Klassifikation der Fahrzeugkilometer | 1 | M | WP-M4-02, WP-M4-41, WP-M4-45, WP-M2-16, WP-M2-10, WP-M3-29, WP-M3-34 |
| WP-M4-43 | Energiekosten je 100 km und je Betriebsstunde | 1 | M | WP-M4-31, WP-M4-33, WP-M4-42, WP-M4-46 |
| WP-M4-44 | UI-15: Kostenübersicht je Kostenobjekt (Grundausbau) | 0 | M | WP-M4-40, WP-M4-42, WP-M4-43, WP-M1-43, WP-M1-31 |
| WP-M4-45 | Analytics-Faktenbasis und Datenstand | 0 | M | WP-M4-01, WP-M4-02, WP-M4-64, WP-M1-14, WP-M1-01, WP-M2-11, WP-M2-21, WP-M2-22, WP-M2-23, WP-M2-26, WP-M2-30, WP-M3-29, WP-M3-08, WP-M3-34 |
| WP-M4-46 | Kennzahlregister mit Definitionsversion, Nenner und Datenstand | 1 | M | WP-M4-45, WP-M1-21, WP-M1-07 |
| WP-M4-47 | Eindeutiger Versuchseintrag je Fahrtanfrage | 1 | S | WP-M4-45, WP-M4-46, WP-M2-11 |
| WP-M4-48 | Ablehnungen mit normiertem Primärgrund und Mehrfachursachen | 1 | S | WP-M4-47, WP-M2-13 |
| WP-M4-49 | Ablehnungscodes für Kapazität, Fahrzeug, Fahrer, Schichtende, Ausstattung | 1 | M | WP-M4-17, WP-M4-48, WP-M2-15, WP-M3-31, WP-M3-04 |
| WP-M4-50 | Ablehnungscodes für Gebiet, Zeit, Grenzen, Partner und Technik | 1 | S | WP-M4-48, WP-M2-12, WP-M2-18 |
| WP-M4-51 | Nachfragetrichter und getrennte Zählung der Buchungsstufen | 2 | M | WP-M4-46, WP-M4-48, WP-M2-21, WP-M2-23, WP-M2-26, WP-M2-30, WP-M3-29 |
| WP-M4-52 | Getrennte Zeitpunkte: geplant, angekündigt, zugesagt, prognostiziert, tatsächlich | 1 | M | WP-M4-45, WP-M1-27, WP-M2-23, WP-M2-28, WP-M2-34, WP-M3-29 |
| WP-M4-53 | Abholpünktlichkeit gegen Zusagefenster | 1 | S | WP-M4-46, WP-M4-52, WP-M1-21, WP-M1-20 |
| WP-M4-54 | Ankunftspünktlichkeit getrennt von der Abholung | 1 | S | WP-M4-46, WP-M4-52 |
| WP-M4-55 | Nicht durchgeführte Fahrten: Ursache und Verursachungskategorie | 1 | M | WP-M4-45, WP-M4-46, WP-M2-26, WP-M2-27, WP-M3-29, WP-M3-53, WP-M3-54 |
| WP-M4-56 | Kilometer-, Betriebsstunden- und Fahrgastkilometer-Kennzahlen | 2 | M | WP-M4-42, WP-M4-46 |
| WP-M4-57 | Auslastung als Kapazitätszeit- und Fahrgastkilometerkennzahl | 1 | M | WP-M4-56, WP-M3-04 |
| WP-M4-58 | Filterdimensionen der Berichte ohne Mandantenüberschreitung | 2 | M | WP-M4-46, WP-M1-08, WP-M1-01, WP-M1-09, WP-M2-03, WP-M2-04 |
| WP-M4-59 | Kennzahlenansicht im Operator-Web | 0 | M | WP-M4-49, WP-M4-50, WP-M4-51, WP-M4-53, WP-M4-54, WP-M4-55, WP-M4-56, WP-M4-57, WP-M4-58, WP-M1-43 |
| WP-M4-60 | Live-Dashboard für den laufenden Betrieb | 1 | M | WP-M4-45, WP-M4-59, WP-M2-11, WP-M2-22, WP-M2-14, WP-M3-31, WP-M3-08, WP-M3-29, WP-M3-36 |
| WP-M4-61 | Fahrerneutrale Referenzfahrzeit | 1 | M | WP-M4-01, WP-M4-02, WP-M0-30, WP-M0-08, WP-M2-10 |
| WP-M4-62 | Regelkonforme Prognose mit Haltezeiten und Sicherheitsreserve | 1 | M | WP-M4-61, WP-M1-21, WP-M1-19, WP-M3-34 |
| WP-M4-63 | M4-Gate-Abnahmesuite | 0 | M | alle anderen WPs dieser Datei (WP-M4-01 bis WP-M4-62, WP-M4-64, WP-M4-65), WP-M0-22, WP-M0-12, WP-M2-21, WP-M2-23, WP-M3-24, WP-M3-29 |
| WP-M4-64 | Modulzugänge, RLS und Isolationsnachweis für alle M4-Tabellen und -Endpunkte | 0 | M | WP-M4-01, WP-M0-16, WP-M0-17, WP-M0-18, WP-M1-01, WP-M1-11, WP-M1-31, WP-M1-12 |
| WP-M4-65 | Übernahme der Schichtzuweisungen aus M3 in den Dienstplan | 0 | S | WP-M4-07, WP-M4-14, WP-M4-16 |

## Arbeitspakete

### WP-M4-01 – Detailverträge und Zustandsautomaten M4

- **Ziel:** Vor Codebeginn liegen für alle 53 Anforderungen OpenAPI-Ergänzungen, DDL-Entwürfe, Zustandsautomaten und JSON-Schemas reviewt vor; die offenen Punkte dieser Datei sind entschieden oder als Annahme dokumentiert.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - API: OpenAPI 3.1 je Modul für `/v1/rosters`, `/v1/time-events`, `/v1/absences` (nur Leseumfang P1), `/v1/expenses/receipts`, `/extract`, `/v1/expenses/{id}`, `/v1/cost-events`, `/v1/vehicles/{id}/total-cost` (P1-Ausschnitt), `/v1/ledger/exports`, `/v1/analytics/metrics`; Übernahme von `/v1/rosters/{roster_id}/publish`, `/v1/expenses/{expense_id}/approve`, `/v1/cost-events/{cost_event_id}/allocations`, `/v1/driver/events` aus `openapi.yaml` mit Negativbeispielen.
  - Datenbank: DDL-Entwürfe für `roster_revision`, `time_event`, `absence_request`, `document_revision`, `extraction_run`, `extracted_field`, `approval_step`, `fuel_transaction`, `evidence_link`, `cost_category`, `accounting_mapping`, `allocation_activation`, `metric_definition_revision`, `aggregate_snapshot`, `dataset_watermark`, `eta_model_revision`; Erweiterungen an `staff_shift`, `expense_document`, `economic_event`, `cost_object`, `travel_attempt`, `rejection_reason` (Offene Punkte 8, 14, 15).
  - Backend: Zustandsautomaten für Dienstplan (`DRAFT → PUBLISHED → SUPERSEDED`, Vorschlag), Schicht, Beleg (Erfassung → Prüfung → `WAITING_SECOND_APPROVAL` → `APPROVED_PENDING_POSTING` → `POSTED` → Export/Korrekturfall, Kap. 03 §2.2 und Kap. 05 §5.5), `allocation_set.state`, `journal.state`, Exportbatch.
  - Konfigurationsparameter: JSON-Schemas (Typ, Einheit, Min/Max, Ebene) für die in dieser Datei genannten Parameter, soweit nicht in M1 geliefert.
  - Kennzahlen: Liste der „P1-Kennzahlen“ aus den 48 Definitionen in `kataloge.csv` (Vorschlag in WP-M4-46) mit Formel, Nenner, Ausschlüssen.
  - Ereignisse/Outbox: Ereignisschemata (Name, Version, Pflichtfelder, Aggregatrevision) für alle Outbox-Ereignisse der Module `staff-time`, `documents-finance` und `analytics`, die in dieser Datei genannt sind; Rechte-/Scope-Matrix Rolle × Endpunkt für alle M4-Pfade (Planer, Personalrolle, Fahrer/Mitarbeiter, Erfasser, Sachprüfer, Budgetfreigeber, Exportberechtigter, Berichtsleser) als Eingabe für WP-M4-64.
- **Detailverträge vorab:** dieses WP ist die Vertragsarbeit; Reviewrollen und Vorlage laut M0-Detailvertragsprozess.
- **Akzeptanzkriterien:**
  1. Jede der 53 Anforderungen hat in `arbeitspakete.csv` einen Eintrag in `detailvertrag` und mindestens einen positiven Testfall (Kap. 08 §8.4).
  2. Die ergänzten OpenAPI-Dateien bestehen `validate_artifacts.py` (parsbar, `$ref` auflösbar, `operationId` eindeutig, Beispiele gültig).
  3. Jeder Zustandsautomat nennt Zustände, Übergänge, auslösende Rolle und Fehlercode bei unzulässigem Übergang.
  4. Zu jedem Offenen Punkt dieser Datei existiert eine dokumentierte Entscheidung oder eine als Annahme gekennzeichnete Festlegung mit Verantwortlichem.
  5. Jeder neue Endpunkt hat einen dokumentierten Scope und Negativbeispiele für 403, 404, 409, 412 und 428, soweit zutreffend; jede neue Tabelle hat genau ein Eigentümermodul und ist in der Tabellenliste von WP-M4-64 geführt.
- **Tests:** positiv: Vertragsprüfung in CI grün. negativ: absichtlich fehlerhaftes Schema-Beispiel lässt CI scheitern. Keine Laufzeittests.
- **Abhängigkeiten:** intern: keine.
  - extern: WP-M0-31, WP-M0-13 (Detailvertragsprozess, Vorlage und CI-Vertragsprüfung)
  - extern: WP-M1-03, WP-M1-17, WP-M1-19 (OpenAPI je Modul und Contract-Test-Pipeline; Zustandsautomaten-Rahmen; Parameterkatalog mit `policy_schema`)
- **Entscheidungen:** D-06, D-07 (Kontenrahmen, Kostenregeln) und D-10 (ETA-Grundmodell) fließen ein; blockieren die Vertragsarbeit nicht, Annahmen werden als Testfixtures gekennzeichnet.
- **Größe:** M
- **Nicht im Umfang:** Detailverträge der P2-Anforderungen SCH-011 ff., TIM-007 ff., DOC-014 ff., FCO-006 ff., KPI-015 ff., ETA-003 ff. (M7/M8).

### WP-M4-02 – Referenzfixtures M4 und Leseports zu Buchung, Fahrt und Flotte

- **Ziel:** M4 kann unabhängig von der Fertigstellung von M2 und M3 entwickelt und getestet werden: deterministische Testdaten und schmale Leseports liefern Buchungen, Fahrtenpläne, Fahrer, Qualifikationen und Fahrzeuge.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: Leseports (explizite Modul-APIs, kein Fremdtabellenzugriff) für `booking`/`vehicle_run`/`route_stop` (Zusage, Planzeiten), Fahrerereignisse (Ist-Abholung, Ist-Ankunft, No-Show), `driver`, `qualification_revision`, `vehicle`, `vehicle_assignment`, `position_sample`; Stub-Implementierungen, solange M2/M3 nicht liefern.
  - Datenbank: Seeds für zwei Mandanten, zwei Depots, Rechtsträger, Kostenstellen, Fahrzeuge, Fahrer mit Qualifikationen, Test-Kontenrahmen, Test-Steuerregel (19 % voll abzugsfähig als Testdatum, Kap. 03), Musterfahrt mit dokumentierten Kilometern, DST-Stichtage 25.10.2026 und Frühjahrsumstellung in `Europe/Berlin`.
  - Adapter/Worker: OCR-Simulator mit steuerbaren Ergebnissen (falscher Betrag, niedrige Konfidenz), Malware-Testdatei, ERP-Empfangssimulator.
- **Detailverträge vorab:** Schnittstellen der Leseports; Fixturekatalog mit erwarteten Sollwerten (Musterfahrt, PH-AT-012, PH-AT-013, PH-AT-015, PH-AT-021).
- **Akzeptanzkriterien:**
  1. Die Fixtures laden reproduzierbar (gleicher Seed, gleiche IDs) unter der Runtime-Rolle in eine leere Datenbank.
  2. Jeder Leseport liefert ausschließlich Daten des aktiven Mandanten; ein Aufruf ohne Tenantkontext liefert keine Zeile.
  3. Stub und echte Implementierung bestehen denselben Vertragstest.
  4. Evidenz enthält keine realen Personen- oder Finanzdaten (Kap. 08 §8.4).
- **Tests:** positiv: Fixture-Ladung, Portvertrag. negativ: Fremdmandant-ID am Port ergibt leeres Ergebnis. Konkurrenz: nicht erforderlich.
- **Abhängigkeiten:** intern: WP-M4-01.
  - extern: WP-M0-08, WP-M0-17, WP-M0-18 (lokale Umgebung, Testharness mit Runtime-Rolle, MinIO)
  - extern: WP-M1-05, WP-M1-09, WP-M1-01 (Mandant, Rechtsträger, Organisationsstruktur (Depots, Kostenstellen), Tenantkontext)
  - extern: WP-M2-23, WP-M2-16, WP-M2-11 (Buchung mit Zusagesnapshot, `vehicle_run` mit Stoppliste, `travel_attempt`) – Reihenfolgekonflikt (Zyklus) für WP-M2-23, siehe Offener Punkt 44
  - extern: WP-M3-03, WP-M3-05, WP-M3-29, WP-M3-34 (Fahrer- und Fahrzeugstamm, Qualifikationen, Fahrerereignisse, Positionsdaten) – Reihenfolgekonflikt (Zyklus) für WP-M3-29, WP-M3-34, siehe Offener Punkt 44
- **Entscheidungen:** D-05 (Pilotbetreiber/Region für realistische Fixtures; blockiert nicht den Bau), D-06 (Test-Kontenrahmen statt freigegebenem Kontenrahmen).
- **Größe:** M
- **Nicht im Umfang:** Sandbox-Partner und PSP-Simulator (M0); Lastfixtures (M5).

### WP-M4-03 – Schichtmodell mit Pausenplan, Qualifikation und Personalbedarf

- **Ziel:** Schichten werden mit allen Planungsmerkmalen gespeichert; eine Schicht über Mitternacht hat einen eindeutigen Kalendertag und eine korrekte Dauer.
- **Anforderungen:** `LH-6.23-SCH-002`
- **Liefergegenstände:**
  - Backend: Modul `staff-time`, Aggregat Shift mit Datum, lokalem Beginn/Ende plus IANA-Zone, Pausenplan, Betrieb, Depot, Arbeitsplatz/Funktion, erforderlicher Qualifikation und Personalbedarf; Ableitung absoluter Zeitpunkte (`timestamptz`) aus lokaler Zeit; Regel für den zugeordneten Kalendertag.
  - Datenbank: Migration an `staff_shift` (im Kern-DDL nur `driver_ref`, `vehicle_id`, `starts_at`, `ends_at`, `timezone`, `roster_revision`, `state`): Depot-/Betriebsbezug, Funktion, Qualifikationsanforderung, Bedarf, Pausenplan, Betriebstag; `driver_ref` und `vehicle_id` nullbar für offene Schichten (Offener Punkt 8); CHECK `ends_at>starts_at` bleibt.
  - API: `/v1/rosters` (Schichten eines Dienstplans anlegen, lesen, ändern; ETag/`If-Match`).
- **Detailverträge vorab:** DDL-Erweiterung `staff_shift`; Regel „Kalendertag einer Mitternachtsschicht = lokaler Tag des Beginns“ (Vorschlag dieses Plans); Schema Pausenplan; Bezug auf Qualifikationsarten aus M3.
- **Akzeptanzkriterien:**
  1. Eine Schicht 22:00–06:00 wird dem Kalendertag des Beginns zugeordnet und mit 8 h Dauer gespeichert (SCH-002).
  2. Alle Pflichtmerkmale (Datum, Beginn/Ende, Pausenplan, Betrieb, Depot, Funktion, Qualifikation, Personalbedarf) sind gespeichert und über die API lesbar (SCH-002).
  3. Eine lokale Zeit in einer DST-Lücke wird abgelehnt; eine doppelte Ortszeit verlangt einen eindeutigen Offset (Kap. 05 §5.1).
  4. Depot und Betrieb müssen zum aktiven Mandanten gehören; fremde IDs ergeben 404.
  5. Anlegen und Ändern einer Schicht stehen mit Akteur, Zeit und Objekt im `audit_event`.
  6. Die Migration lässt in M3 bereits angelegte `staff_shift`-Zeilen (WP-M3-07) unverändert lesbar; neue Spalten sind für Altzeilen befüllt oder nullbar, `resource_release.shift_id` bleibt gültig.
- **Tests:** positiv: Tagschicht, Mitternachtsschicht. negativ: Ende vor Beginn, DST-Lücke, fremdes Depot. konkurrierend (Datentrennung): paralleles Ändern derselben Schicht mit altem ETag ergibt 412.
- **Abhängigkeiten:** intern: WP-M4-01, WP-M4-02, WP-M4-64.
  - extern: WP-M1-09, WP-M1-18, WP-M1-02 (Organisationsstruktur (Betriebshöfe/Depots), Zeit-/Zeitzonen-/DST-Baustein, ETag-Grundgerüst)
  - extern: WP-M3-05 (Katalog der Qualifikationsarten (`qualification_revision`))
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Überführung der in M3 angelegten Schichtzuweisungen in Dienstplanrevisionen und Abgleich des dortigen Überlappungsverbots mit dem Entwurfsmodell (WP-M4-65, Offener Punkt 38); Schichtvorlagen `shift_template` (SCH-011, M7); Pausen als operative Fahrzeugrestriktion (SCH-018, M7); Feiertage (SCH-019, M7).

### WP-M4-04 – Dienstplan-Entwurf getrennt vom veröffentlichten Plan

- **Ziel:** Planer arbeiten in einem Entwurf, der vom veröffentlichten Plan getrennt gespeichert wird und nur für berechtigte Planer sichtbar ist.
- **Anforderungen:** `LH-6.23-SCH-004`
- **Liefergegenstände:**
  - Backend: Aggregat RosterRevision im Modul `staff-time` mit Entwurfs- und Publikationsstand je Depot und Zeitraum; Lesesichten „Planer (Entwurf + veröffentlicht)“ und „verbindlich (nur veröffentlicht)“.
  - Datenbank: `roster_revision` (Depot, Zeitraum, Revisionsnummer, Zustand, Autor, Zeitstempel); `staff_shift.roster_revision` verweist darauf; RLS plus Rollenprüfung für Entwurfszeilen.
  - API: `/v1/rosters` (anlegen, Entwurf lesen/ändern), `GET /v1/rosters/{roster_id}` mit ETag.
- **Detailverträge vorab:** Zustandsautomat Dienstplan; Schnitt eines Dienstplans (je Depot und Woche – Vorschlag dieses Plans); Sichtbarkeitsmatrix Rolle × Zustand.
- **Akzeptanzkriterien:**
  1. Eine Änderung im Entwurf ist für Planer mit Recht sichtbar, für Fahrer und operative Rollen nicht (SCH-004).
  2. Der veröffentlichte Plan bleibt bei Entwurfsänderungen unverändert lesbar (SCH-004).
  3. Ein Benutzer ohne Planerrecht erhält auf Entwurfsdaten 403 bzw. 404 nach Kap. 05 §5.1.
  4. Entwurfsänderungen erzeugen keine Outbox-Ereignisse an Fahrer-App oder Disposition.
- **Tests:** positiv: Entwurf anlegen, ändern, lesen. negativ: Fahrerrolle liest Entwurf; Fremdmandant. konkurrierend (Datentrennung): zwei Planer ändern denselben Entwurf, zweiter mit altem ETag erhält 412 ohne stilles Überschreiben. Grundlage für AT-37.
- **Abhängigkeiten:** intern: WP-M4-03.
  - extern: WP-M1-07, WP-M1-08, WP-M1-46 (Rollenmodell und Objektberechtigung; Audit)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Veröffentlichung (WP-M4-14); getrennte Freigabe mehrerer Wochen mit Vorschau (SCH-025, M7).

### WP-M4-05 – Offene Schichten und unbesetzter Bedarf

- **Ziel:** Schichten ohne zugeordnete Person oder Fahrzeug werden als offen geführt, hervorgehoben und gezählt, ohne eine verbindliche Zuordnung vorzutäuschen.
- **Anforderungen:** `LH-6.23-SCH-008`
- **Liefergegenstände:**
  - Backend: Zustand „offen“ der Schicht; Berechnung unbesetzten Bedarfs (Personalbedarf minus zugeordnete Personen) je Tag, Depot und Funktion.
  - Datenbank: `staff_shift` mit nullbarem `driver_ref`/`vehicle_id` (aus WP-M4-03); Index `(tenant_id,state,updated_at)`.
  - API: `/v1/rosters` Filter „offen“ und Zähler im Dienstplan-Lesemodell.
  - Kennzahlen (Datenbasis): `Schicht-Abdeckungsquote`.
- **Detailverträge vorab:** Definition „offen“ und „unbesetzter Bedarf“; Feld im Lesemodell.
- **Akzeptanzkriterien:**
  1. Eine Schicht ohne Person erscheint im Lesemodell als offen und geht in den Zähler unbesetzter Schichten ein (SCH-008).
  2. Eine offene Schicht enthält weder Mitarbeiter- noch verbindliche Fahrzeugzuordnung (SCH-008).
  3. Nach Zuweisung einer Person sinkt der Zähler um genau eins.
- **Tests:** positiv: offene Schicht zählen, zuweisen. negativ: offene Schicht als besetzt melden ist nicht möglich. Konkurrenz: zwei parallele Zuweisungen derselben offenen Schicht führen zu genau einer Besetzung.
- **Abhängigkeiten:** intern: WP-M4-04.
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** `shift_offer`, `shift_application`, `shift_swap` (SCH-021 bis SCH-024, M7; AT-42, AT-43); Darstellung in UI-12 (WP-M4-18).

### WP-M4-06 – Abwesenheit als Verfügbarkeitsrestriktion (Minimalmodell)

- **Ziel:** Genehmigte Abwesenheiten liegen als diagnosefreie Zeiträume vor, damit Konfliktprüfung und Planerraster „nicht verfügbar“ auswerten können.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: Lesemodell „Verfügbarkeitsrestriktion“ im Modul `staff-time`; Erfassung genehmigter Abwesenheiten durch berechtigte Personalrolle (kein Antragsworkflow).
  - Datenbank: `absence_request` (Person, Zeitraum, Status, neutrale Art ohne Diagnose- oder Nachweisfeld); eigener Modulzugang und Spaltenrechte für HR-Daten (Kap. 04 §4.6).
  - API: `/v1/absences` (Anlegen durch Personalrolle, Lesen als Restriktion).
- **Detailverträge vorab:** Feldliste ohne Gesundheitsdetails; Projektion für Dispositionssichten („nicht verfügbar“, Kap. 06 §6.8).
- **Akzeptanzkriterien:**
  1. Eine genehmigte Abwesenheit ist für die Konfliktprüfung als Zeitraum abrufbar.
  2. Planer- und Dispositionssichten zeigen ausschließlich „nicht verfügbar“; Art und Kommentar sind dort nicht enthalten.
  3. Das Schema enthält kein Feld für Diagnosen oder medizinische Nachweise.
- **Tests:** positiv: Abwesenheit anlegen, als Restriktion lesen. negativ: Planerrolle fragt Abwesenheitsart ab und erhält sie nicht; Fremdmandant liest Abwesenheit – Abweisung und Sicherheitsprotokoll (Abwesenheitsteil von AT-49, Lauf in WP-M4-64); Anlegen ohne Personalrolle ergibt 403. Jede Anlage und Änderung steht im `audit_event`.
- **Abhängigkeiten:** intern: WP-M4-01, WP-M4-64.
  - extern: WP-M1-31, WP-M1-07 (Feldschutz für HR-Daten; Rollenmodell)
- **Entscheidungen:** D-11 (Aufbewahrung/Löschfristen für HR-Daten; blockiert nur Produktivschaltung).
- **Größe:** S
- **Nicht im Umfang:** Abwesenheitsantrag mit Genehmiger und Kommentar (TIM-008), Urlaubsanspruch (TIM-009), Krankheitsabwesenheit (TIM-010), Ersatzbedarf (SCH-020) – alle M7; AT-41 ist damit in M4 nur teilweise erfüllbar (Offener Punkt 1).

### WP-M4-07 – Konfliktprüfung: Doppelbelegung, Qualifikation, Abwesenheit

- **Ziel:** Vor der Veröffentlichung erkennt das System Doppelbelegung derselben Person, fehlende Qualifikation und genehmigte Abwesenheit und nennt die betroffenen Schichten.
- **Anforderungen:** `LH-6.23-SCH-006`
- **Liefergegenstände:**
  - Backend: Konfliktprüfdienst im Modul `staff-time`, aufrufbar beim Speichern im Entwurf (Hinweis) und bei der Publikation (Sperre); Ergebnisobjekt mit Konfliktart, Person, betroffenen Schicht-IDs und Ursache.
  - Datenbank: Lesen von `staff_shift`, `absence_request`; Qualifikationen über Leseport (`qualification_revision`); Invariante „Qualifikation: Revisionen vergleichen“ (Kap. 04 §4.5).
  - API: Konfliktliste im Dienstplan-Lesemodell von `/v1/rosters`; Problem-Details mit stabilen Codes bei Publikationsversuch.
- **Detailverträge vorab:** Konfliktcodes; Überlappungsregel (links geschlossen, rechts offen); Gültigkeitsprüfung der Qualifikation zum Schichtzeitpunkt.
- **Akzeptanzkriterien:**
  1. Zwei überlappende Schichten derselben Person ergeben eine Meldung mit beiden Schicht-IDs (SCH-006).
  2. Eine Zuweisung ohne die geforderte, zum Schichtzeitpunkt gültige Qualifikation ergibt eine Meldung mit der fehlenden Qualifikation (SCH-006).
  3. Eine Schicht im Zeitraum einer genehmigten Abwesenheit ergibt eine Meldung ohne Abwesenheitsart (SCH-006).
  4. Ein Dienstplan mit einem dieser Konflikte ist nicht veröffentlichbar; die Ursache wird genannt (AT-39).
- **Tests:** positiv: konfliktfreier Plan. negativ: je Konfliktart ein Fall, abgelaufene Qualifikation, angrenzende (nicht überlappende) Schichten ergeben keinen Konflikt. konkurrierend (Datentrennung): zwei Planer weisen parallel dieselbe Person überlappend zu – spätestens die Publikation erkennt den Konflikt. Besteht AT-39; Teil von AT-41.
- **Abhängigkeiten:** intern: WP-M4-04, WP-M4-06.
  - extern: WP-M3-05 (Qualifikationen mit Gültigkeit je Fahrer)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Ruhezeit/Pausen (WP-M4-13); Unterscheidung harte Sperre/Hinweis/Empfehlung mit Ausnahmen (SCH-035, M7); Fahrzeugkonflikte bei Ausfall (SCH-038, M7).

### WP-M4-08 – Ist-Arbeitszeit als Zeitereignisse

- **Ziel:** Tatsächliche Arbeitszeit wird unabhängig von Schichten als Folge unveränderlicher Zeitereignisse mit Beginn, Ende, Pausen und Erfassungsquelle geführt.
- **Anforderungen:** `LH-6.24-TIM-001`
- **Liefergegenstände:**
  - Backend: Aggregat TimeEvent im Modul `staff-time` (Arbeitsbeginn, Arbeitsende, Pausenbeginn, Pausenende); Ableitung von Arbeitszeitintervallen; optionaler Bezug auf eine Schicht, keine Pflicht.
  - Datenbank: `time_event` (Person, Ereignisart, Ereigniszeit, Gerätezeit, Serverempfangszeit, Quelle, stabile Ereignis-ID, optionale Schicht); append-only; Unique auf `(tenant_id, Ereignis-ID)`.
  - API: `/v1/time-events` (erfassen mit `Idempotency-Key`, eigene Ereignisse lesen; Planersicht für berechtigte Rollen).
  - Ereignisse/Outbox: Zeitereignis erfasst.
- **Detailverträge vorab:** DDL `time_event`; Enum Erfassungsquelle (App, Web, Nacherfassung durch Berechtigte); Regel zur Ableitung von Intervallen aus Ereignisfolgen; Verhalten bei fehlendem Ende.
- **Akzeptanzkriterien:**
  1. Zwei unterschiedliche Ist-Zeit-Ereignisse zu derselben geplanten Schicht bleiben beide mit Zeit, Quelle und Urheber nachvollziehbar (TIM-001).
  2. Arbeitszeit lässt sich ohne zugeordnete Schicht erfassen (TIM-001).
  3. Zeitereignisse sind nicht änder- oder löschbar; eine Wiederholung mit derselben Ereignis-ID erzeugt keinen zweiten Eintrag.
  4. Ein Mitarbeiter sieht nur eigene Zeitereignisse.
  5. Eine Nacherfassung durch eine berechtigte Rolle ist als eigene Erfassungsquelle mit Akteur gekennzeichnet und steht im `audit_event`; sie ersetzt kein vorhandenes Ereignis (TIM-001).
- **Tests:** positiv: Beginn, Pause, Ende. negativ: Ende ohne Beginn wird gekennzeichnet statt verworfen; fremde Person; UPDATE/DELETE scheitert. konkurrierend (Datentrennung): identisches Ereignis parallel gesendet ergibt genau eine Zeile.
- **Abhängigkeiten:** intern: WP-M4-01, WP-M4-64.
  - extern: WP-M1-13, WP-M1-14, WP-M1-46, WP-M1-31 (Idempotenzregister, Outbox, Audit, Feldschutz HR)
- **Entscheidungen:** D-11 (Aufbewahrung von Arbeitszeitdaten; blockiert nur Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** `time_correction` und Korrekturantrag (TIM-007, M7; AT-48 siehe Offener Punkt 2); `time_account_entry`, Überstunden und Zeitkonten (TIM-011, M7); Lohnexport (TIM-012/013, M7).

### WP-M4-09 – Trennung von Schicht-Soll und Arbeitszeit-Ist

- **Ziel:** Planwerte und Ist-Werte liegen getrennt vor; keine Plan- oder Fahrtänderung überschreibt erfasste Ist-Zeiten.
- **Anforderungen:** `LH-6.24-TIM-004`
- **Liefergegenstände:**
  - Backend: Soll/Ist-Auswertung je Person und Tag als reine Leseprojektion aus `staff_shift` (Soll) und `time_event` (Ist); Architekturtest, dass Dienstplan- und Dispositionsbefehle keine Schreibrechte auf `time_event` haben.
  - Datenbank: getrennte Tabellen `staff_shift` und `time_event`; DML-Rechte der Modulzugänge entsprechend beschränkt (Kap. 04 §4.6).
  - API: `/v1/time-events` Lesesicht „Soll/Ist je Tag“.
- **Detailverträge vorab:** Schema der Soll/Ist-Sicht; Kennzeichnung fehlender Ist-Buchung.
- **Akzeptanzkriterien:**
  1. Nach Änderung oder Neuveröffentlichung einer Schicht sind die vorhandenen Ist-Zeiten der Person unverändert (TIM-004).
  2. Eine Umplanung von Fahrten (Planrevision in der Disposition) verändert keine Ist-Zeiten (TIM-004).
  3. Die Soll/Ist-Sicht zeigt Vorbereitungszeit vor der Fahrzeugprüfung als erfasste Arbeitszeit (AT-45).
- **Tests:** positiv: Soll/Ist-Auswertung für AT-45. negativ: Schreibversuch des Dienstplanpfads auf `time_event` scheitert an Rechten. konkurrierend (Datentrennung): Neuveröffentlichung einer Schicht parallel zu einem Stempel derselben Person – beide Wirkungen bleiben erhalten, kein Ist-Wert geht verloren. Ermöglicht AT-45 (mit WP-M4-11).
- **Abhängigkeiten:** intern: WP-M4-03, WP-M4-08.
  - extern: WP-M0-03 (Architekturtest der Modulgrenzen)
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** Zeitkonten, Rundungs- und Pausenlogik (TIM-011, M7).

### WP-M4-10 – Zeitrechnung über Mitternacht, Zeitzone und DST

- **Ziel:** Arbeits- und Schichtdauern werden aus absoluten Zeitpunkten berechnet und in lokaler Zeit eindeutig dargestellt, auch über Mitternacht und Zeitumstellung.
- **Anforderungen:** `LH-6.24-TIM-005`
- **Liefergegenstände:**
  - Backend: Zeitrechenbaustein im Modul `staff-time` auf Basis des Plattform-Zeittyps: Dauer aus `timestamptz`, lokale Darstellung mit IANA-Zone und Offset, Tageszuordnung für Auswertungen.
  - Datenbank: `staff_shift.timezone`; `time_event` mit Zeitpunkt plus Zone.
  - Konfigurationsparameter: `reporting_timezone`.
- **Detailverträge vorab:** Testtabelle mit Sollwerten für Herbst- (25.10.2026, 02:30 doppelt) und Frühjahrsumstellung sowie Zeitzonenwechsel; Darstellungskonvention für doppelte Ortszeit (Offset anzeigen).
- **Akzeptanzkriterien:**
  1. Eine Nachtschicht 22:00–06:00 über die Herbstumstellung ergibt 9 h absolute Arbeitszeit, über die Frühjahrsumstellung 7 h; die lokale Darstellung bleibt 22:00–06:00 (TIM-005; AT-47).
  2. Zwei Stempel um 02:30 mit UTC+02 und UTC+01 sind zwei eindeutige Zeitpunkte; es gibt keine stille Auswahl oder Verschiebung (TIM-005; PH-AT-018).
  3. Eine Schicht über Mitternacht wird mit korrekter Dauer und definiertem Bezugstag ausgewertet (TIM-005).
- **Tests:** positiv: Sollwerttabelle. negativ: lokale Zeit in DST-Lücke wird abgelehnt. Besteht AT-47; liefert den Zeiterfassungsteil von PH-AT-018.
- **Abhängigkeiten:** intern: WP-M4-03, WP-M4-08.
  - extern: WP-M1-18 (Zeit-, Zeitzonen- und DST-Baustein)
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** Feiertage und Sonderregeln nach Ort (SCH-019, M7).

### WP-M4-11 – Stempeln in der Fahrer-App

- **Ziel:** Fahrer erfassen Arbeitsbeginn, Arbeitsende und Pausen in der App, unabhängig von Fahrzeugfreigabe und Fahrtbeginn.
- **Anforderungen:** `LH-6.24-TIM-002`
- **Liefergegenstände:**
  - Oberfläche: Fahrer-App (`mobile/driver`), Bereich Arbeitszeit im Dienststart (UI-09: „Arbeitszeit: gestartet“, Kap. 06 §6.7) mit Kommen, Pause, Gehen; Arbeitszeitbeginn und technische Einsatzfreigabe sichtbar getrennt.
  - API: `/v1/time-events` aus der App mit stabiler Ereignis-ID.
  - Backend: keine Kopplung des Stempelns an Check- oder Fahrtzustand.
  - Konfigurationsparameter: `clock_in_geofence_enabled` bleibt in P1 aus (TIM-017 ist P2).
- **Detailverträge vorab:** App-Ablauf Stempeln; Zustandsanzeige laufende Arbeitszeit/Pause; Verhältnis zu `/v1/driver/events` (Stempel laufen über `/v1/time-events`, Vorschlag dieses Plans).
- **Akzeptanzkriterien:**
  1. Ein Fahrer kann vor Fahrzeugfreigabe einstempeln und die Vor-Schicht-Checkliste innerhalb der begonnenen Arbeitszeit erledigen (TIM-002).
  2. Pausenbeginn und -ende sowie Arbeitsende sind in der App erfassbar und erscheinen als `time_event` (TIM-002).
  3. Das Einstempeln erteilt keine Fahrzeug- oder Einsatzfreigabe.
  4. Der Ablauf AT-45 (Stempeln vor Fahrzeugprüfung, Pause, Ausstempeln) liefert die erwartete Soll/Ist-Auswertung.
  5. Die Stempelfunktion ist mit Screenreader bedienbar, hat Touchflächen von mindestens 44×44 CSS-Pixel und zeigt den Status nicht nur als Farbe; während der Fahrbewegung öffnet sich kein Erfassungsdialog (Kap. 06 §6.1/§6.7).
  6. Ein Fahrer kann nur für sich selbst stempeln; ein Stempel mit fremder Personen-ID wird serverseitig abgewiesen.
- **Tests:** positiv: AT-45-Ablauf als App-Integrationstest. negativ: doppeltes Einstempeln wird als Konflikt gekennzeichnet; Stempeln ohne gültige Sitzung. Besteht AT-45 (mit WP-M4-09).
- **Abhängigkeiten:** intern: WP-M4-08.
  - extern: WP-M3-24, WP-M3-25, WP-M3-13, WP-M3-14 (Fahrer-App-Grundgerüst mit Anmeldung und Dienststart (UI-09); Pflichtcheck-Ablauf)
- **Entscheidungen:** D-10 (Mitbestimmung bei Fahrerdaten; blockiert nur Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Stempel-Geozone (TIM-017, M7); Ein-/Ausstempel-Erinnerung (SCH-026, M7).

### WP-M4-12 – Offline-Stempel mit einmaliger Übernahme

- **Ziel:** Bei gestörter Verbindung werden Zeitereignisse lokal gepuffert und nach Wiederverbindung genau einmal übernommen, mit Herkunft, Gerätezeit und Konfliktkennzeichen.
- **Anforderungen:** `LH-6.24-TIM-003`
- **Liefergegenstände:**
  - Oberfläche: Fahrer-App: Stempel in der verschlüsselten lokalen Queue mit `device_event_id`, Erfassungszeit und Status „nicht synchronisiert“ (Kap. 01 §1.5).
  - Backend: Sync-Annahme mit dauerhafter Deduplikation über die Ereignis-ID; Speicherung von Gerätezeit und Serverempfangszeit; Konfliktkennzeichen bei unplausibler Gerätezeit oder widersprüchlicher Reihenfolge; Ergebnis `APPLIED`, `DUPLICATE` oder `REVIEW_REQUIRED` (analog `/v1/driver/events`).
  - Datenbank: `time_event` (Herkunft offline, Gerätezeit, Empfangszeit, Konfliktmarke).
  - API: `/v1/time-events`.
- **Detailverträge vorab:** Konfliktregeln (zulässige Abweichung Gerätezeit/Serverzeit, überlappende Intervalle); Ergebniscodes; Aufbewahrung der Ereignis-ID über den 72-h-Response-Cache hinaus (Kap. 05 §5.1).
- **Akzeptanzkriterien:**
  1. Ein offline erfasster Stempel wird nach Wiederverbindung mit eigener Ereignis-ID genau einmal übernommen, auch bei mehrfacher Sync-Wiederholung (TIM-003).
  2. Herkunft (offline), Gerätezeit und Serverempfangszeit sind am Ereignis gespeichert (TIM-003).
  3. Ein Stempel mit widersprüchlicher Zeit wird als möglicher Konflikt markiert und nicht stillschweigend korrigiert oder verworfen (TIM-003).
- **Tests:** positiv: offline Kommen/Gehen, Sync. negativ: manipulierte Gerätezeit, Stempel für fremde Person. konkurrierend (Datentrennung): derselbe Stempel parallel von zwei Sync-Läufen ergibt genau eine Zeile. Ermöglicht AT-46 (Gate M7).
- **Abhängigkeiten:** intern: WP-M4-11.
  - extern: WP-M0-28 (Spike-Ergebnis Driver-Offline-Queue)
  - extern: WP-M3-24 (Offline-Queue der Fahrer-App mit `device_event_id`)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Klärung markierter Konflikte über Korrekturantrag (TIM-007, M7).

### WP-M4-13 – Hinweise zu Pausen, Ruhezeit und Überschreitungen

- **Ziel:** Pausen, Ruhezeitverletzungen und auffällige Überschreitungen werden für Plan und Ist nachvollziehbar angezeigt; die rechtliche Bewertung bleibt beim Menschen.
- **Anforderungen:** `LH-6.24-TIM-006`
- **Liefergegenstände:**
  - Backend: Regelprüfung im Modul `staff-time` für Ruhezeit zwischen zwei Schichten bzw. Arbeitszeitintervallen, tägliche Arbeitszeit und fehlende Pause; Ergebnis als Warnhinweis mit Regel, Berechnungsbasis und betroffenen Schichten/Zeiten; Aufruf im Dienstplan-Entwurf, bei der Publikation und über Ist-Zeiten.
  - API: Hinweisliste im Lesemodell von `/v1/rosters` und in der Soll/Ist-Sicht von `/v1/time-events`.
  - Konfigurationsparameter: `min_rest_hours`, `max_daily_work_minutes`.
- **Detailverträge vorab:** Regelmatrix mit Testfällen (vom Auftraggeber freizugeben, Offener Punkt 3); Pausenregel (kein Katalogparameter vorhanden); Einstufung Warnung oder Sperre bei Publikation.
- **Akzeptanzkriterien:**
  1. Testdaten mit unterschrittener Ruhezeit erzeugen einen Warnhinweis mit Regel, Sollwert und Istwert (TIM-006).
  2. Testdaten mit fehlender Pflichtpause bzw. überschrittener Tagesarbeitszeit erzeugen den passenden Hinweis (TIM-006).
  3. Hinweise ändern weder Plan- noch Ist-Daten und treffen keine automatische Rechtsentscheidung; sie sind in Dienstplan und Zeitübersicht sichtbar (TIM-006).
  4. Das Verhalten bei der Publikation (Warnung oder Sperre) folgt der freigegebenen Regelmatrix (AT-40).
  5. Parameterwerte unterhalb der rechtlichen Systemgrenze (`min_rest_hours`, `max_daily_work_minutes`: Katalog „rechtliche Vorgaben nicht überschreibbar“) werden von der Konfiguration abgewiesen; Hinweise sind nur für Planer mit Depotrecht und die betroffene Person sichtbar.
- **Tests:** positiv: regelkonformer Plan ohne Hinweis. negativ: Ruhezeit 1 min unter Grenze, Grenzfall exakt auf der Grenze, Verletzung über DST-Umstellung. Besteht AT-40, sobald die Regelmatrix freigegeben ist.
- **Abhängigkeiten:** intern: WP-M4-04, WP-M4-08, WP-M4-09, WP-M4-10.
  - extern: WP-M1-21 (wirksame Konfiguration je Rechtsraum/Mandant)
- **Entscheidungen:** D-10 (Mitbestimmung; blockiert nur Produktivschaltung). Regelmatrix ist AG-Freigabe (Offener Punkt 3).
- **Größe:** M
- **Nicht im Umfang:** Regeln je Beschäftigungsmodell mit Rechtshinweis (SCH-017, M7); Kapazitätsrestriktion für die Disposition (TIM-016, M7); versionierte Tarifregelwerke (TIM-018, M9).

### WP-M4-14 – Veröffentlichung des Dienstplans

- **Ziel:** Nur ein geprüfter, veröffentlichter Dienstplan wird verbindlich an Fahrer-App und operative Schichtansicht übertragen; Fahrer sehen nie einen Entwurf.
- **Anforderungen:** `LH-6.23-SCH-005`
- **Liefergegenstände:**
  - Backend: Publikationsbefehl im Modul `staff-time`: Konfliktprüfung (WP-M4-07) und Regelhinweise (WP-M4-13) unter Sperre erneut ausführen, neue veröffentlichte `roster_revision` schreiben (kein Überschreiben), `staff_shift` der Revision verbindlich setzen, Outbox – alles in einer Transaktion.
  - API: `POST /v1/rosters/{roster_id}/publish` (`publishRoster`, Scope `rosters:publish`, `Idempotency-Key`, `If-Match`; 409 bei Konflikt, 412 bei veralteter Revision, 428 ohne `If-Match`); Fahrersicht „eigene veröffentlichte Schichten“ über `/v1/rosters` (Eigen-Sicht).
  - Ereignisse/Outbox: Dienstplan veröffentlicht (Revision, betroffene Personen); Fahrerbenachrichtigung über Outbox.
  - Oberfläche: Fahrer-App zeigt den nächsten veröffentlichten Dienst (UI-09 „Nächster Dienst“).
  - Konfigurationsparameter: `shift_publish_notice_hours`.
- **Detailverträge vorab:** `PublishRequest`/`PublishedRevision` aus `openapi.yaml` konkretisieren; Konfliktantwort; Inhalt der Fahrermeldung; Wirkung von `shift_publish_notice_hours` (Offener Punkt 9).
- **Akzeptanzkriterien:**
  1. Vor der Veröffentlichung sieht der Fahrer keine Änderung aus dem Entwurf; nach der Veröffentlichung genau die veröffentlichte Revision (SCH-005; AT-37).
  2. Der Fahrer erhält nach Veröffentlichung eine Meldung mit der neuen Planrevision (AT-37).
  3. Ein Plan mit blockierendem Konflikt wird mit 409 und Konfliktliste abgewiesen; es entsteht keine Revision und kein Ereignis.
  4. Wiederholung mit gleichem `Idempotency-Key` liefert dieselbe Revision; gleicher Schlüssel mit anderem Inhalt ergibt `409 IDEMPOTENCY_CONFLICT`.
  5. Die Veröffentlichung erteilt keine technische Fahrzeugfreigabe (Kap. 05 §5.5).
  6. Die operative Schichtansicht (verbindliche Lesesicht für Leitstelle und Leseport aus WP-M4-17) enthält ausschließlich Schichten der veröffentlichten Revision (SCH-005).
  7. Benachrichtigt werden nur Personen, deren Schichten sich gegenüber der vorherigen veröffentlichten Revision geändert haben; ein Zustellfehler ist dokumentiert und macht die Veröffentlichung nicht rückgängig (Kap. 06 §6.8).
  8. Veröffentlichung steht mit Akteur, Revision und Zeit im `audit_event`.
- **Tests:** positiv: AT-37-Ablauf. negativ: Konflikt, fehlender Scope, fehlendes `If-Match`, veralteter ETag. konkurrierend (Datentrennung): zwei parallele Publikationen desselben Entwurfs – genau eine gewinnt; Publikation parallel zu einer konfliktauslösenden Zuweisung. Besteht AT-37.
- **Abhängigkeiten:** intern: WP-M4-05, WP-M4-07, WP-M4-13.
  - extern: WP-M1-13, WP-M1-14, WP-M1-02 (Idempotenzregister, Outbox-Relay, ETag-Grundgerüst)
  - extern: WP-M2-32 (Betriebsnachrichten (Push/SMS/E-Mail) mit Zustellversuchen)
  - extern: WP-M3-25 (Fahrer-App-Dienststart, der veröffentlichte Schichten anzeigt) – Reihenfolgekonflikt (Zyklus), siehe Offener Punkt 44
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Vorschau mit Differenzliste und Mehrwochenfreigabe (SCH-025, M7); gezielte Push-/E-Mail-Mitteilungen bei jeder Änderung (SCH-014, M7); Einsicht in Einsatzorte, Kontakte, Diensthinweise (SCH-015, M7).

### WP-M4-15 – Änderungs- und Veröffentlichungsrevisionen der Schicht

- **Ziel:** Jede Änderung und jede Veröffentlichung einer Schicht ist als Revision mit Autor und Zeitstempel gespeichert; historische veröffentlichte Fassungen sind auditierbar.
- **Anforderungen:** `LH-6.23-SCH-010`
- **Liefergegenstände:**
  - Backend: Revisionsschreibung je Schichtänderung und je Publikation; Lesezugriff auf frühere veröffentlichte Fassungen.
  - Datenbank: `roster_revision` (unveränderlich nach Publikation), Schichtstände je Revision; `audit_event` für Akteur, Aktion, Objekt.
  - API: `/v1/rosters` Revisionsliste und Abruf einer historischen veröffentlichten Fassung.
- **Detailverträge vorab:** Speicherform historischer Schichtstände (Snapshot je Revision – Vorschlag dieses Plans); Auditfelder.
- **Akzeptanzkriterien:**
  1. Für eine Schicht sind alle Änderungen und Veröffentlichungen mit Autor und Zeitstempel abrufbar (SCH-010).
  2. Eine frühere veröffentlichte Fassung ist nach zwei weiteren Veröffentlichungen unverändert abrufbar (SCH-010).
  3. Eine veröffentlichte Revision lässt sich nicht nachträglich ändern oder löschen.
- **Tests:** positiv: drei Veröffentlichungen, Abruf der ersten. negativ: UPDATE auf veröffentlichte Revision scheitert; Abruf ohne Auditrecht.
- **Abhängigkeiten:** intern: WP-M4-14.
  - extern: WP-M1-46, WP-M1-12 (Audit append-only)
- **Entscheidungen:** D-11 (Aufbewahrungsdauer der Planhistorie; blockiert nur Produktivschaltung).
- **Größe:** S
- **Nicht im Umfang:** Vergleichsansicht zweier Revisionen (SCH-025, M7).

### WP-M4-16 – Mandanten- und Depotberechtigung im Dienstplan

- **Ziel:** Dienstpläne sind nur innerhalb des eigenen Mandanten und der ausdrücklich berechtigten Depots les- und bearbeitbar.
- **Anforderungen:** `LH-6.23-SCH-009`
- **Liefergegenstände:**
  - Backend: Objektberechtigung „Depot“ für alle Dienstplan- und Schichtbefehle des Moduls `staff-time`; depotübergreifende Bearbeitung nur mit ausdrücklichem Recht.
  - Datenbank: RLS auf `roster_revision`, `staff_shift`, `time_event`, `absence_request`; Depotbezug als Spalte für die Objektprüfung.
  - API: alle Pfade unter `/v1/rosters` prüfen Depotrecht; nicht sichtbare Dienstpläne ergeben 404, fehlender Scope 403.
- **Detailverträge vorab:** Rechtematrix Planerrolle × Depot; Definition des ausdrücklichen depotübergreifenden Rechts.
- **Akzeptanzkriterien:**
  1. Ein Planer von Depot A kann Dienstpläne von Depot B weder lesen noch ändern noch veröffentlichen (SCH-009).
  2. Mit ausdrücklichem depotübergreifendem Recht ist die Bearbeitung möglich und wird auditiert (SCH-009).
  3. Dienstpläne eines anderen Mandanten sind in keiner Rolle erreichbar, auch nicht durch manipulierte IDs.
- **Tests:** positiv: eigenes Depot. negativ: fremdes Depot, fremder Mandant, manipulierte `tenant_id` im Payload (Contract-Fall Kap. 05 §5.8). konkurrierend (Datentrennung): Rechteentzug während offener Bearbeitung – nächster Schreibbefehl scheitert.
- **Abhängigkeiten:** intern: WP-M4-04.
  - extern: WP-M1-08, WP-M1-01 (Dienst- und Objektberechtigung, RLS-Laufzeitbasis, Isolations-Testharness)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** gemeinsamer Pool über berechtigte Bereiche (SCH-029, M7); mandantenübergreifende Fahrerfreigabe (M1/M6).

### WP-M4-17 – Fahrerschicht, Fahrzeugbereitstellung und Disponierbarkeit

- **Ziel:** Ein Fahrzeug ist nur disponierbar, wenn ihm eine veröffentlichte, besetzte Fahrerschicht zugeordnet ist; eine offene Schicht erzeugt kein verfügbares Fahrzeug.
- **Anforderungen:** `LH-6.23-SCH-007`
- **Liefergegenstände:**
  - Backend: Leseport „Schichtbesetzung“ des Moduls `staff-time` für Disposition und Einsatzfreigabe: je Fahrzeug und Zeitraum besetzt/unbesetzt mit Schicht- und Qualifikationsrevision; Verknüpfung Schicht ↔ Fahrzeug ↔ geplanter Betrieb (Dienst).
  - Datenbank: `staff_shift` (`driver_ref`, `vehicle_id`, Dienstbezug); `resource_release` verweist auf `shift_id` (Kern-DDL); Invariante „Einsatz nur mit gültiger Schicht, Qualifikation, Check und ohne Sperre“ (Kap. 04 §4.3/§4.5).
  - Ereignisse/Outbox: Schichtbesetzung geändert (für Kandidatenfilter und Ablehnungsgrund „kein Fahrer“).
  - Backend (Zusage an M1): `ImpactProvider` des Moduls `staff-time` für die Wirkungsanalyse aus WP-M1-23 (Konfigurationsrevision → betroffene veröffentlichte Schichten als Objektverweise und Anzahl, ohne Personendaten).
- **Detailverträge vorab:** Portvertrag Schichtbesetzung; Eigentum an `resource_release` (Offener Punkt 10); Verhalten bei Wegfall der Besetzung nach Veröffentlichung.
- **Akzeptanzkriterien:**
  1. Ein Fahrzeug mit offener Fahrerschicht wird vom Port als nicht besetzt gemeldet und erscheint in der Disposition nicht als verfügbar (SCH-007).
  2. Erst eine veröffentlichte, besetzte Schicht macht das Fahrzeug im Schichtzeitraum einsatzfähig im Sinne der Personalvoraussetzung (SCH-007).
  3. Eine Anfrage bei freiem Fahrzeug ohne einsatzbereiten Fahrer führt zu keinem Angebot; der Grund ist Personalmangel (E2E-081).
  4. Der `ImpactProvider` meldet für eine Testrevision (z. B. geändertes `min_rest_hours` oder verkleinertes Bediengebiet eines Dienstes) die betroffenen veröffentlichten Schichten des aktiven Mandanten; Entwurfsschichten und fremde Mandanten sind nicht enthalten.
- **Tests:** positiv: besetzte Schicht → Fahrzeug Kandidat. negativ: offene Schicht, Entwurfsschicht, Schicht außerhalb des Zeitraums. konkurrierend (Kapazität/Sicherheit): Besetzung wird entfernt, während ein Hold läuft – der Hold prüft die Schichtrevision erneut und scheitert. Ermöglicht E2E-081.
- **Abhängigkeiten:** intern: WP-M4-05, WP-M4-14.
  - extern: WP-M2-15, WP-M2-22 (Fahrzeugkandidatenfilter und Hold nutzen den Port) – Reihenfolgekonflikt (Zyklus), siehe Offener Punkt 44
  - extern: WP-M3-08 (`resource_release`/Einsatzfreigabe wertet Schicht und Qualifikation aus)
  - extern: WP-M1-23 (Provider-Port der Wirkungsanalyse (WP-M1-23))
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Bereitstellung erst nach Fahrer-Check-in und Checkliste (SCH-031, M7); Reaktion auf Fahrzeugausfall und Reparatursperre (SCH-038, M7); Krankheitsausfall (AT-44, M7).

### WP-M4-18 – UI-12: Planer-Dashboard Tag, Woche, Monat

- **Ziel:** Planer sehen im Web Tages-, Wochen- und Monatsansichten mit Mitarbeitern als Zeilen und Schichten als Einträgen, einschließlich offener Dienste und Konflikte.
- **Anforderungen:** `LH-6.23-SCH-001`
- **Liefergegenstände:**
  - Oberfläche: UI-12 Dienstplan im Operator-Web (`web/operator`): Raster Tag/Woche/Monat, Zeilen je Mitarbeiter, zugeordnetes Fahrzeug je Schicht, Zeile(n) für offene Dienste mit Zähler, Konflikt- und Hinweismarken (Ruhezeit, Doppelbelegung, unbesetzter Dienst), Abwesenheit als „nicht verfügbar“, Entwurf und veröffentlichte Revision prominent getrennt, Datenstand; barrierefreie Tabellenalternative; Zustände leer/Fehler/keine Rechte (Kap. 06 §6.8/§6.9).
  - API: `/v1/rosters` (Lesemodell je Depot und Zeitraum).
- **Detailverträge vorab:** Wireframe UI-12 mit Zustandskatalog; Lesemodell-Schema; Paging/Zeitraumgrenzen.
- **Akzeptanzkriterien:**
  1. Ein Planer sieht die verfügbaren Einsätze eines Depots für zwei aufeinanderfolgende Wochen (SCH-001).
  2. Tages-, Wochen- und Monatsansicht zeigen Mitarbeiter als Zeilen und Schichten als Einträge (SCH-001).
  3. Offene Schichten sind hervorgehoben und gezählt; Status ist nie nur Farbe (Kap. 06 §6.1).
  4. Die Ansicht ist per Tastatur und Screenreader über die Tabellenalternative nutzbar.
  5. Die Zustände „leer“ (kein Dienstplan im Zeitraum), „Fehler“ (Ladefehler mit Wiederholen) und „keine Rechte“ sind eigene, getestete Zustände; Mandant, Depot, Revisionsstatus und Datenstand sind jederzeit sichtbar (Kap. 06 §6.1).
- **Tests:** positiv: Komponententests und E2E-Test „zwei Wochen eines Depots“. negativ: fremdes Depot nicht auswählbar; Planer ohne Recht sieht keinen Entwurf. Barrierefreiheitsprüfung (axe) als CI-Schritt.
- **Abhängigkeiten:** intern: WP-M4-05, WP-M4-07, WP-M4-16.
  - extern: WP-M1-43, WP-M1-44 (Web-Designsystem, Anwendungsrahmen, Anmeldung und Mandantenwechsel)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Mitarbeiterübersicht mit Soll-/Ist-Stunden (SCH-027, M7); Export PDF/CSV/Kalenderfeed (SCH-030, M7).

### WP-M4-19 – UI-12: Schichten per Klick, Kopie und Drag-and-drop

- **Ziel:** Planer legen Schichten per Klick, Kopie oder Drag-and-drop an und ordnen sie geeigneten Personen zu, ohne Seitenwechsel.
- **Anforderungen:** `LH-6.23-SCH-003`
- **Liefergegenstände:**
  - Oberfläche: UI-12: Anlegen per Klick in eine Rasterzelle, Kopieren einer Schicht, Verschieben/Zuweisen per Drag-and-drop mit gleichwertiger Tastaturalternative; Speicherung asynchron mit Statusmeldung über Live-Region; Konfliktmeldung direkt an der Schicht; Aktion „Entwurf veröffentlichen“ mit Anzeige blockierender Konflikte; 412-Konflikt ohne stilles Überschreiben.
  - API: `/v1/rosters` (Schicht anlegen, kopieren, zuweisen mit `If-Match`), `POST /v1/rosters/{roster_id}/publish`.
- **Detailverträge vorab:** Interaktionsvertrag Drag-and-drop und Tastaturpfad; Filter „geeignete Personen“ (Qualifikation, keine Abwesenheit); Fehlertexte je Konfliktcode.
- **Akzeptanzkriterien:**
  1. Eine per Drag-and-drop zugewiesene Schicht ist ohne vollständigen Seitenwechsel gespeichert und nach Neuladen vorhanden (SCH-003).
  2. Anlegen per Klick und per Kopie erzeugt Schichten im Entwurf (SCH-003).
  3. Bei der Zuordnung werden geeignete Personen angeboten; eine ungeeignete Zuordnung zeigt die Konfliktursache (SCH-003).
  4. Jede Drag-and-drop-Aktion ist auch per Tastatur ausführbar (Kap. 08 §8.4 Gate UX).
- **Tests:** positiv: UI-E2E für Klick, Kopie, Drag-and-drop, Veröffentlichen. negativ: veralteter Stand (412) zeigt Konfliktdialog; Zuordnung unqualifizierter Person. Unterstützt AT-37 und AT-39 über die Oberfläche.
- **Abhängigkeiten:** intern: WP-M4-18.
  - extern: WP-M1-43 (Web-Designsystem)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Wochenvorlage kopieren (AT-38, SCH-011, M7); Tablet-Optimierung und offene Bedarfslinien (SCH-012, M7); alternative Listenansicht als eigene Anforderung (SCH-036, M7).

### WP-M4-20 – UI-13: Eigene Zeiten und Abwesenheit (Grundausbau)

- **Ziel:** Mitarbeiter sehen ihren veröffentlichten Plan, ihre Zeitstempel, Soll/Ist und Warnhinweise; UI-13 ist als Rahmen für die P2-Anträge vorhanden.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Oberfläche: UI-13 Eigene Zeit/Abwesenheit (Web und Fahrer-App-Ansicht): eigene veröffentlichte Schichten, Zeitereignisse mit Quelle und Offline-/Konfliktkennzeichen, Soll/Ist je Tag, Hinweise aus WP-M4-13, eigene Abwesenheitszeiträume; Zustände „Konflikt“, „fehlende Buchung“; Planzeit überschreibt keine Istzeit (Kap. 06 §6.8).
  - API: `/v1/time-events`, `/v1/rosters` (Eigen-Sicht), `/v1/absences` (Eigen-Sicht, lesend).
- **Detailverträge vorab:** Wireframe UI-13 mit Zustandskatalog; Abgrenzung der in P1 deaktivierten Aktionen „Antrag“ und „Korrektur“.
- **Akzeptanzkriterien:**
  1. Ein Mitarbeiter sieht ausschließlich eigene Schichten, Zeitereignisse und Abwesenheiten.
  2. Fehlende Ist-Buchung und markierte Konflikte sind als eigene Zustände sichtbar.
  3. DST-Tage zeigen lokale Zeiten mit eindeutigem Offset (AT-47).
- **Tests:** positiv: UI-E2E Eigen-Sicht. negativ: fremde Personen-ID in der URL ergibt 404. Unterstützt AT-45 und AT-47 über die Oberfläche.
- **Abhängigkeiten:** intern: WP-M4-09, WP-M4-13, WP-M4-14.
  - extern: WP-M1-43, WP-M1-44 (Web-Designsystem; Anmeldung)
  - extern: WP-M3-24 (Fahrer-App-Grundgerüst)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Korrekturantrag (`/v1/time-corrections`, `time_correction`; TIM-007, AT-48), Abwesenheitsantrag (TIM-008, AT-41), Saldo/Urlaubskonto (`time_account_entry`; TIM-009/011/020) – alle M7.

### WP-M4-21 – Uploadticket, Quarantäne und Malware-Prüfung

- **Ziel:** Dateien gelangen nur über ein autorisiertes Uploadticket in eine Quarantäne, werden gehasht und auf Schadsoftware geprüft, bevor sie als Belegoriginal verarbeitet oder ausgeliefert werden.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: Uploadticket im Modul `documents-finance` mit erlaubter Größe, MIME-Typ und erwartetem Hash; Quarantänezustand; Übergang in den Originalspeicher erst nach bestandener Prüfung.
  - Adapter/Worker: `workers/documents` Malware-Scan; Objektspeicher mit mandantengetrennten Präfixen und Schreibschutz für Originale.
  - API: `/v1/expenses/receipts` (Ticket anfordern, Upload abschließen).
  - Datenbank: `expense_document` (`object_key`, `original_sha256`, `mime`, `state`, `original_received_at`); Ticketdaten (Vorschlag: eigene Tabelle im Modul).
  - Ereignisse/Outbox: Upload angenommen, Quarantäne bestanden, Quarantäne abgelehnt (Sicherheitsereignis).
- **Detailverträge vorab:** Ticketschema (Größe/MIME/Hash, Ablauf); erlaubte MIME-Typen und Maximalgröße (im Pflichtenheft nicht beziffert, Offener Punkt 13); Zustände der Quarantäne.
- **Akzeptanzkriterien:**
  1. Ein Upload ohne gültiges Ticket oder mit abweichender Größe, MIME oder Hash wird abgewiesen.
  2. Eine infizierte Datei bleibt in Quarantäne, wird weder ausgeliefert noch der OCR übergeben und erzeugt ein Sicherheitsereignis (PH-AT-019).
  3. Objektschlüssel eines anderen Mandanten sind nicht erreichbar.
  4. Der gespeicherte `original_sha256` entspricht dem serverseitig berechneten Hash der Datei.
  5. Der Baustein ist als Modul-API von `documents-finance` für andere Module nutzbar (Zusage an M3: Fotoevidenz der Schadenmeldung, AT-33); ein abgelaufenes oder bereits verbrauchtes Ticket ist nicht wiederverwendbar.
- **Tests:** positiv: Upload JPEG/PDF. negativ: Malware-Testdatei, falscher MIME, übergroße Datei, abgelaufenes Ticket, fremder Mandant. konkurrierend (Sicherheit): zwei parallele Abschlüsse desselben Tickets ergeben genau ein Dokument. Liefert den Quarantäneteil von PH-AT-019.
- **Abhängigkeiten:** intern: WP-M4-01, WP-M4-64.
  - extern: WP-M0-08 (Objektspeicher (MinIO) in der lokalen Umgebung)
  - extern: WP-M1-11, WP-M1-12, WP-M1-36 (Isolation außerhalb der Datenbank (mandantengetrennte Objektbestände), Sicherheitsereignisse, Secret-Verwaltung)
- **Entscheidungen:** D-11 (Aufbewahrung; blockiert nur Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Fachliche Belegstruktur (WP-M4-22); Aufbewahrungsfristen je Dokumentart (`invoice_archive_years`, DOC-022, M8).

### WP-M4-22 – Belegaggregat mit einheitlicher Struktur für vier Eingangskanäle

- **Ziel:** Belege aus Fahreranwendung, Leitstelle, E-Mail-Eingang und Rechnungsimport entstehen in derselben fachlichen Struktur mit Herkunftskanal und Eingangszeit.
- **Anforderungen:** `LH-I.1-DOC-006`
- **Liefergegenstände:**
  - Backend: Aggregat Expense im Modul `documents-finance`; ein Anlagebefehl für alle Kanäle; Kanaladapter für Web-Upload (Leitstelle/Finanzen), Fahrer-App, E-Mail-Eingang und Dateiimport rufen denselben Befehl.
  - Datenbank: Migration an `expense_document`: Herkunftskanal, Belegart, Vorgangsbezug; `original_received_at` als Eingangszeit.
  - API: `/v1/expenses/receipts` (Anlage als Entwurf), `/v1/expenses/{id}` (lesen mit ETag).
  - Adapter/Worker: E-Mail-Eingangsadapter (Anhänge → Uploadpfad mit Quarantäne); Importadapter für Dateien.
  - Konfigurationsparameter: `invoice_email_inbound`.
  - Ereignisse/Outbox: Beleg angelegt.
- **Detailverträge vorab:** Belegschema; Enum Herkunftskanal; technische Form des E-Mail-Eingangs und des Rechnungsimports in P1 (Offener Punkt 12); Absenderprüfung („Domäne verifiziert“ laut Katalog).
- **Akzeptanzkriterien:**
  1. Je ein Beleg aus allen vier Kanälen besitzt dieselbe fachliche Struktur mit gesetztem Herkunftskanal und Eingangszeit (DOC-006).
  2. Alle Kanäle durchlaufen Uploadticket bzw. Quarantäne; kein Kanal umgeht die Prüfung.
  3. Eine E-Mail von nicht verifizierter Absenderdomäne erzeugt keinen freigabefähigen Beleg.
  4. Belege sind mandantengetrennt; `/v1/expenses/{id}` eines fremden Mandanten ergibt 404.
- **Tests:** positiv: vier Kanäle. negativ: E-Mail ohne Anhang, nicht verifizierte Domäne, Import mit defekter Datei. konkurrierend (Geld): dieselbe E-Mail zweimal zugestellt ergibt einen Beleg bzw. einen Dublettenhinweis (WP-M4-32).
- **Abhängigkeiten:** intern: WP-M4-21.
  - extern: WP-M1-14, WP-M1-07 (Inbox-Deduplikation, Rollenmodell, Outbox)
- **Entscheidungen:** D-06 (zulässige Eingangswege und Rechnungsformate; blockiert nicht den Bau mit Testformat).
- **Größe:** M
- **Nicht im Umfang:** E-Rechnung XRechnung/ZUGFeRD (DOC-014/015, M8); `supplier_invoice` mit Kreditor und Fälligkeit (DOC-016, M8); Tankkartenabrechnung als Massenimport (DOC-019, M8).

### WP-M4-23 – Unveränderliches Original, Versionen und Prüfspur

- **Ziel:** Das Originaldokument bleibt unverändert archiviert; jede Bearbeitung erzeugt eine eigene Version mit Bearbeiter und Zeitpunkt.
- **Anforderungen:** `LH-I.1-DOC-007`
- **Liefergegenstände:**
  - Backend: Versionierung im Modul `documents-finance`: Original (Objekt), bearbeitete Ansicht (eigenes Objekt), Feldstände je Revision; kein Befehl ändert das Original.
  - Datenbank: `document_revision` (Revision, Feldwerte, Bearbeiter, Zeitpunkt, Grund), `expense_document.revision`; `audit_event`; Objektspeicher ohne Überschreib-/Löschrecht der Runtime auf Originale.
  - API: `/v1/expenses/{id}` (Revisionen und Original abrufen; Änderung nur mit `If-Match`).
- **Detailverträge vorab:** DDL `document_revision`; Regel, welche Felder eine neue Revision auslösen; Auslieferung des Originals (signierte, kurzlebige URL – Vorschlag dieses Plans).
- **Akzeptanzkriterien:**
  1. Nach Korrektur eines Betrags ist das Originaldokument bytegleich abrufbar (DOC-007).
  2. Die Korrektur ist als neue Version mit Bearbeiter, Zeitpunkt, altem und neuem Wert nachvollziehbar (DOC-007).
  3. Kein API-Pfad und kein Runtime-Recht erlaubt das Überschreiben oder Löschen des Originalobjekts.
  4. Ein bereits gebuchter Beleg lässt keine freie Betragsbearbeitung zu (Kap. 06 §6.6).
- **Tests:** positiv: zwei Korrekturen, Abruf aller Versionen. negativ: Schreibversuch auf Originalobjekt, Änderung ohne `If-Match` (428), veralteter ETag (412). konkurrierend (Geld): zwei parallele Korrekturen derselben Revision – genau eine gewinnt. Teil von E2E-064, E2E-066, PH-AT-019.
- **Abhängigkeiten:** intern: WP-M4-22.
  - extern: WP-M1-46, WP-M1-12 (Audit append-only)
- **Entscheidungen:** D-11 (Archivierung/Aufbewahrung; blockiert nur Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** revisionsfähiger Archivexport für Prüfer (DOC-023, M8); Gegenbelege (DOC-030, M8; technischer Ausschnitt in WP-M4-36).

### WP-M4-24 – Hash und Integritätsprüfung der Originale

- **Ziel:** Ein Hashwert und eine wiederholbare Integritätsprüfung erkennen jede unbemerkte Änderung eines Belegoriginals und protokollieren sie als Vorfall.
- **Anforderungen:** `LH-I.1-DOC-008`
- **Liefergegenstände:**
  - Backend: Integritätsprüfung im Modul `documents-finance` (bei Auslieferung, vor Freigabe und als periodischer Job): SHA-256 des Objekts gegen `expense_document.original_sha256`.
  - Adapter/Worker: fortsetzbarer Prüfjob in `workers/documents` je Mandant mit Tenantkontext.
  - Datenbank: `expense_document.original_sha256`, Index `expense_hash`; Vorfall als Sicherheitsereignis und `audit_event`.
  - Ereignisse/Outbox: Integritätsverletzung erkannt.
- **Detailverträge vorab:** Prüfintervall (im Pflichtenheft nicht beziffert); Vorfallschema; Wirkung auf den Belegstatus (Freigabesperre).
- **Akzeptanzkriterien:**
  1. Ein im Objektspeicher manipuliertes Original wird beim Integritätstest erkannt und als Vorfall protokolliert (DOC-008).
  2. Ein Beleg mit Integritätsverletzung ist nicht freigabefähig; die Freigabe prüft den Originalhash (Kap. 05 §5.5).
  3. Unveränderte Originale bestehen die Prüfung wiederholt ohne Vorfall.
- **Tests:** positiv: Prüfung unveränderter Originale. negativ: manipuliertes Objekt, fehlendes Objekt. konkurrierend (Sicherheit): Freigabe parallel zur Feststellung einer Integritätsverletzung – die Freigabe scheitert oder der Vorfall sperrt vor dem Posting; kein Posting auf verletztem Original. Teil von PH-AT-019.
- **Abhängigkeiten:** intern: WP-M4-23.
  - extern: WP-M1-12, WP-M1-46, WP-M1-01 (Sicherheitsereignisse; Worker-Tenantkontext)
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** manipulationsgeschütztes Langzeitarchiv und Legal Hold (M8, D-11).

### WP-M4-25 – Belegerfassung per Kamera in der Fahrer-App

- **Ziel:** Fahrer fotografieren Papierbelege, schneiden zu, drehen und speichern sie in höchstens fünf Eingabeschritten als Entwurf an einem Vorgang.
- **Anforderungen:** `LH-I.1-DOC-001`
- **Liefergegenstände:**
  - Oberfläche: Fahrer-App (`mobile/driver`), Belegerfassung im Bereich UI-10: Kamera, Zuschnitt, Drehung, Qualitätsprüfung (Schärfe/Auflösung), Auswahl des Vorgangs (Fahrzeug/Schicht), Speichern als Entwurf; „synchronisiert“ und „freigegeben“ getrennt angezeigt (Kap. 06 §6.7).
  - Backend: Annahme von Rohbild und bearbeiteter Ansicht als getrennte Objekte.
  - API: `/v1/expenses/receipts`.
- **Detailverträge vorab:** Schrittfolge (max. fünf Eingabeschritte); Mindestbildqualität (im Pflichtenheft nicht beziffert, Offener Punkt 13); Vorgangsarten.
- **Akzeptanzkriterien:**
  1. Ein fotografierter Tankbeleg ist in höchstens fünf Eingabeschritten als Entwurf gespeichert (DOC-001).
  2. Rohbild und bearbeitete Ansicht sind beide abrufbar (DOC-001).
  3. Zuschnitt und Drehung verändern das Rohbild nicht.
  4. Bei unzureichender Bildqualität erhält der Fahrer einen Hinweis und kann neu aufnehmen.
  5. Der Ablauf ist mit Screenreader und vergrößerter Schrift bedienbar; während der Fahrbewegung wird kein Erfassungsdialog geöffnet (Kap. 06 §6.1/§6.7).
  6. Der Fahrer kann den Beleg nur eigenen bzw. ihm zugewiesenen Vorgängen (Fahrzeug/Schicht) zuordnen; ein fremdes Fahrzeug wird abgewiesen und der Entwurf bleibt erhalten (Kap. 03 §3 Nr. 1).
- **Tests:** positiv: App-Integrationstest mit Schrittzählung. negativ: unscharfes Bild, verweigerte Kameraberechtigung (verständlicher Hinweis, kein Absturz), fremdes Fahrzeug, Abbruch im Ablauf hinterlässt keinen halben Beleg. Teil von E2E-064.
- **Abhängigkeiten:** intern: WP-M4-22, WP-M4-23.
  - extern: WP-M3-24, WP-M3-44 (Fahrer-App-Grundgerüst (UI-10), Kamera-/Evidenzbaustein aus der Schadenmeldung)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Offline-Erfassung (WP-M4-26); OCR (WP-M4-27).

### WP-M4-26 – Offline-Belegerfassung mit einmaliger Synchronisation

- **Ziel:** Quittungen werden ohne Netz lokal zwischengespeichert und nach Synchronisation genau einmal mit unverändertem Original angelegt.
- **Anforderungen:** `LH-I.1-DOC-013`
- **Liefergegenstände:**
  - Oberfläche: Fahrer-App: lokaler Entwurf mit vor dem Upload erzeugter stabiler UUID (Kap. 03 §3 Nr. 1), verschlüsselte Ablage, Status „lokal“/„synchronisiert“.
  - Backend: Anlage idempotent über die Client-UUID; Hashvergleich lokal berechnet gegen Server.
  - Datenbank: Unique auf `(tenant_id, Client-UUID)` an `expense_document` (Migration).
  - API: `/v1/expenses/receipts`.
- **Detailverträge vorab:** Sync-Protokoll (Wiederaufnahme abgebrochener Uploads); Verhalten bei abweichendem Hash; Erfassungszeit vs. Eingangszeit.
- **Akzeptanzkriterien:**
  1. Ein offline erfasster Beleg erscheint nach Synchronisation genau einmal, auch bei abgebrochenem und wiederholtem Upload (DOC-013).
  2. Der Hash des Originals auf dem Server entspricht dem lokal erfassten Bild (DOC-013).
  3. Erfassungszeit am Gerät und Eingangszeit am Server sind getrennt gespeichert.
- **Tests:** positiv: offline erfassen, Sync. negativ: Upload-Abbruch nach 50 %, App-Neustart vor Sync. konkurrierend (Geld): derselbe Entwurf parallel von zwei Sync-Läufen ergibt genau einen Beleg. Ermöglicht E2E-069 (Gate M7).
- **Abhängigkeiten:** intern: WP-M4-25.
  - extern: WP-M3-24 (verschlüsselte Offline-Queue der Fahrer-App)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Offline-OCR auf dem Gerät.

### WP-M4-27 – OCR-Adapter und Feldextraktion

- **Ziel:** OCR erkennt Belegdatum, Betrag, Währung, Händler, Belegnummer und Mehrwertsteuerbeträge, soweit sichtbar; jedes Feld ist einzeln prüf- und korrigierbar.
- **Anforderungen:** `LH-I.1-DOC-002`
- **Liefergegenstände:**
  - Adapter/Worker: Adapter `ocr` hinter providerunabhängiger Schnittstelle; Extraktionsjob in `workers/documents`; OCR-Text wird nie als Anweisung ausgeführt (Kap. 03 §3 Nr. 2).
  - Datenbank: `extraction_run` (Dokumentrevision, OCR-Version, Zeit, Status), `extracted_field` (Feld, Rohwert, normalisierter Wert, Konfidenz, Quellenkoordinate, Korrekturbezug).
  - API: `/extract` (Extraktion zu einem Beleg unter `/v1/expenses/receipts` anstoßen), Feldkorrektur über `/v1/expenses/{id}`.
  - Konfigurationsparameter: `receipt_ocr_enabled`.
  - Ereignisse/Outbox: Extraktion abgeschlossen/fehlgeschlagen.
- **Detailverträge vorab:** Adaptervertrag OCR; Feldkatalog und Normalisierung (Betrag als `amount_minor`, Datum, Währung); Auswahl der OCR-Engine und Datenschutzprüfung bei externem Dienst (Offener Punkt 11).
- **Akzeptanzkriterien:**
  1. Ein Testsatz unterschiedlicher Kassenbelege wird extrahiert; die sechs Feldarten liegen je Feld einzeln mit Konfidenz und Fundstelle vor (DOC-002).
  2. Jedes erkannte Feld ist einzeln bestätig- und korrigierbar; der ursprüngliche OCR-Vorschlag bleibt erhalten (DOC-002; E2E-066).
  3. Nicht sichtbare Felder bleiben leer und werden nicht geraten.
  4. Mit `receipt_ocr_enabled = false` findet keine Extraktion statt; manuelle Erfassung bleibt möglich.
  5. Erkannter Text wird ausschließlich als Daten gespeichert; Inhalte, die wie Anweisungen, Formeln oder Markup aussehen, lösen keine Aktion aus und werden in der Oberfläche neutralisiert dargestellt (Kap. 03 §3 Nr. 2).
  6. Der Rohbeleg bleibt unabhängig vom OCR-Ergebnis archiviert (Katalogvalidierung `receipt_ocr_enabled`); die Extraktion läuft im Tenantkontext des Belegs.
- **Tests:** positiv: Testsatz mit erwarteter Feldtrefferliste. negativ: unlesbares Bild, Beleg mit eingebettetem Anweisungs-/Formeltext, OCR-Timeout (Job wiederholbar ohne Doppellauf), Beleg in Quarantäne wird nicht extrahiert. Teil von E2E-064, E2E-066.
- **Abhängigkeiten:** intern: WP-M4-23.
  - extern: WP-M1-15, WP-M1-21 (`operation`-Ressource mit Lease/Fencing für Worker; wirksame Konfiguration)
- **Entscheidungen:** D-06 (zulässige OCR-/Verarbeitungsdienste nicht ausdrücklich geregelt; Offener Punkt 11; blockiert nur Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Lernen von Kontierungsvorschlägen (DOC-029, M9); XML-Extraktion aus E-Rechnungen (M8).

### WP-M4-28 – OCR als unbestätigter Vorschlag mit Bestätigungspflicht

- **Ziel:** OCR-Ergebnisse bleiben Vorschläge; bei wesentlicher Unsicherheit ist eine menschliche Bestätigung Pflicht, und ohne Bestätigung entsteht kein gebuchter Aufwand.
- **Anforderungen:** `LH-I.1-DOC-003`
- **Liefergegenstände:**
  - Backend: Feldstatus „vorgeschlagen/bestätigt/korrigiert“; Markierung bei niedriger Konfidenz und bei Summenabweichung (Netto + Steuer ≠ Brutto); Freigabevoraussetzung „alle Pflichtfelder bestätigt“.
  - Datenbank: `extracted_field` (Status, Bestätiger, Zeit), `document_revision`.
  - API: `/v1/expenses/{id}` (Feld bestätigen/korrigieren).
  - Konfigurationsparameter: `receipt_mandatory_fields`.
- **Detailverträge vorab:** Schwelle „wesentliche Unsicherheit“ (kein Katalogparameter, Offener Punkt 11); Regel Summenabweichung; Problem-Code bei Freigabeversuch mit unbestätigten Feldern.
- **Akzeptanzkriterien:**
  1. Ein Feld mit niedriger Erkennungsqualität wird markiert (DOC-003).
  2. Ohne menschliche Bestätigung gelangt kein OCR-Vorschlag als gebuchter Aufwand in die Kostenrechnung; der Freigabeversuch wird abgewiesen (DOC-003).
  3. Liest das System 89,00 statt 39,00, kann ein Mensch korrigieren; der OCR-Vorschlag 89,00 bleibt nachvollziehbar (E2E-066).
- **Tests:** positiv: Bestätigung aller Felder. negativ: Freigabe mit unbestätigtem Betrag, Summenabweichung. konkurrierend (Geld): Feldkorrektur parallel zur Freigabe – die Freigabe auf der alten Dokumentrevision scheitert mit 412, es wird kein unbestätigter Wert gebucht. Besteht E2E-066 (mit WP-M4-27).
- **Abhängigkeiten:** intern: WP-M4-27.
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Freigabeschritte selbst (WP-M4-34).

### WP-M4-29 – Kostenzuordnung zu Firma, Kostenstelle oder Fahrzeug

- **Ziel:** Jeder Beleg ist einer Firma (Rechtsträger) und mindestens einer Kostenstelle oder einem Fahrzeug zugeordnet; unzulässige und mandantenfremde Zuordnungen werden abgewiesen.
- **Anforderungen:** `LH-I.1-DOC-004`
- **Liefergegenstände:**
  - Backend: Zuordnungsbefehl im Modul `documents-finance`; serverseitige Prüfung, dass Rechtsträger, Kostenstelle und Fahrzeug zum Mandanten gehören und für den Benutzer erlaubt sind; Pflege der Kostenobjekte.
  - Datenbank: `cost_object` (`kind` u. a. `VEHICLE`, `COST_CENTER`, `PROJECT`, `business_ref`, `legal_entity_id`); `expense_document.legal_entity_id`; Zuordnung Beleg ↔ Kostenobjekt (Migration); zusammengesetzte FKs als Invariante „Keine lokale Cross-Tenant-Referenz“ (Kap. 04 §4.5).
  - API: `/v1/expenses/{id}` (Zuordnung setzen), `/v1/cost-events` (Kostenobjekte lesen – Unterpfad Vorschlag dieses Plans).
- **Detailverträge vorab:** Abgleich `cost_object.kind` zwischen Kern-DDL und Kap. 03 §2 (Offener Punkt 14); Regel „erlaubte Kostenzuordnung“ je Rolle.
- **Akzeptanzkriterien:**
  1. Ein Beleg ohne erlaubte Kostenzuordnung ist nicht freigabefähig (DOC-004).
  2. Die Zuordnung zu einem Fahrzeug, einer Kostenstelle oder einem Rechtsträger eines anderen Mandanten wird zurückgewiesen, ohne dessen Existenz preiszugeben (DOC-004).
  3. Jeder freigabefähige Beleg hat genau einen Rechtsträger und mindestens ein Kostenobjekt (DOC-004).
- **Tests:** positiv: Zuordnung Fahrzeug, Kostenstelle. negativ: fremde ID (Contract-Fall „unzulässige Fremd-ID“, Kap. 05 §5.8), Kostenobjekt eines anderen Rechtsträgers. konkurrierend (Datentrennung): Kostenobjekt wird deaktiviert, während die Zuordnung läuft.
- **Abhängigkeiten:** intern: WP-M4-22.
  - extern: WP-M1-05, WP-M1-09, WP-M1-08 (Rechtsträger, Kostenstellen aus der Organisationsstruktur, Objektberechtigung)
  - extern: WP-M3-03 (Fahrzeugstamm)
- **Entscheidungen:** D-06 (Kostenstellenplan; Testkostenstellen bis zum Beschluss, blockiert Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Nutzung fremder Fahrzeuge/Belege über Kooperation (PH-AT-016, M6); Zugriffssteuerung nach Kostenstelle (DOC-028, M8).

### WP-M4-30 – Tankbeleg-Fachdaten

- **Ziel:** Für Tankquittungen werden Kraftstoffart, Liter, Preis pro Liter, Bruttobetrag, Kilometerstand und Fahrzeug erfasst; fehlende Pflichtwerte werden angezeigt.
- **Anforderungen:** `LH-I.1-DOC-005`
- **Liefergegenstände:**
  - Backend: Belegart Tankbeleg mit eigenem Feldsatz; Pflichtfeldprüfung; Übergabe als Tankvorgang an das Kostenereignis (WP-M4-33).
  - Datenbank: `fuel_transaction` (Fahrzeug, Kraftstoffart, Menge `numeric(20,8)` mit Einheit, Preis je Einheit, Brutto `bigint`, Kilometerstand, Tankzeit, Karten-ID optional, Belegbezug).
  - API: `/v1/expenses/{id}` (Tankfelder), `/v1/expenses/receipts`.
  - Konfigurationsparameter: `receipt_mandatory_fields`, `fuel_receipt_required`.
- **Detailverträge vorab:** DDL `fuel_transaction`; Einheiten (Liter, kWh getrennt, Kap. 03 §2.1); Dezimalstrings in der API.
- **Akzeptanzkriterien:**
  1. Ein vollständiger, plausibler Tankvorgang ist nach Freigabe als Kostenereignis auswertbar (DOC-005).
  2. Fehlende Pflichtwerte (z. B. Liter, Kilometerstand, Fahrzeug) werden einzeln angezeigt; der Beleg ist bis dahin nicht freigabefähig (DOC-005).
  3. Mengen und Preise werden ohne Gleitkomma verarbeitet.
  4. Die Wirkung von `fuel_receipt_required` folgt dem Detailvertrag (Offener Punkt 39); die Erfassung durch den Fahrer ersetzt in keinem Fall die Finanzprüfung (Katalogvalidierung „Fahrerfreigabe getrennt von Finanzprüfung“).
- **Tests:** positiv: vollständiger Tankbeleg. negativ: fehlende Liter, negatives Volumen, fremdes Fahrzeug. Teil von E2E-064 und PH-AT-012.
- **Abhängigkeiten:** intern: WP-M4-28, WP-M4-29.
  - extern: WP-M3-03 (Fahrzeugstamm mit Antriebs-/Kraftstoffart)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Ladevorgänge mit Ladepunktkosten (FCO-020, M8); Bestandsführung (Kap. 03 §1.1, P2).

### WP-M4-31 – Tankplausibilität mit kontrollierbaren Hinweisen

- **Ziel:** Das System prüft Literpreis, Tankvolumen, Kilometerabstand und Tankkarten-ID und erzeugt bei Abweichungen prüfbare Hinweise, ohne Daten still zu ändern.
- **Anforderungen:** `LH-I.1-DOC-012`
- **Liefergegenstände:**
  - Backend: Plausibilitätsregeln im Modul `documents-finance` mit Ergebnisobjekt (Regel, Schwelle, Istwert, Berechnungsbasis); Hinweise blockieren die finanzielle Freigabe oder erzeugen einen ausdrücklich freizugebenden Klärfall (Kap. 03 §3 Nr. 2).
  - Datenbank: Hinweise am Beleg (Migration); Lesen von `fuel_transaction`; Kilometerabstand gegen den Kilometerstand des letzten `fuel_transaction` desselben Fahrzeugs (keine Abhängigkeit vom Kilometerstand-Register aus WP-M4-41).
  - API: Hinweisliste in `/v1/expenses/{id}`; Quittierung mit Begründung.
  - Konfigurationsparameter: `fuel_max_liters`, `fuel_price_alert_pct`, `fuel_use_alert_pct`, `fuel_card_mapping`.
- **Detailverträge vorab:** Regelformeln; Vergleichsbasis für den Literpreis (Katalog: „historische Preisvergleichsdaten“ – Quelle offen, Offener Punkt 16); Verhalten ohne Vorgänger-Kilometerstand.
- **Akzeptanzkriterien:**
  1. Ein Tankvolumen über `fuel_max_liters`, ein Literpreis außerhalb `fuel_price_alert_pct`, ein unplausibler Kilometerabstand und eine nicht zum Fahrzeug passende Tankkarten-ID erzeugen je einen eigenen Hinweis (DOC-012).
  2. Hinweise ändern keine erfassten Werte; sie sind einsehbar, quittierbar und im Audit nachvollziehbar (DOC-012).
  3. Werte innerhalb der Schwellen erzeugen keinen Hinweis.
  4. Jeder Hinweis nennt die Berechnungsbasis (Vergleichswert, Schwelle, Quelle); ein nicht quittierter Hinweis blockiert die finanzielle Freigabe oder führt in einen ausdrücklich freizugebenden Klärfall (Kap. 03 §3 Nr. 2; Wirkung in WP-M4-34).
- **Tests:** positiv: plausibler Tankvorgang. negativ: je Regel ein Grenzfall knapp über und exakt auf der Schwelle; fehlender Vorgänger-Kilometerstand. Ermöglicht E2E-070 (Gate M8).
- **Abhängigkeiten:** intern: WP-M4-30.
  - extern: WP-M1-21 (wirksame Konfiguration je Fahrzeug/Fahrzeugtyp/Mandant)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Kontrollberichte zu ungewöhnlichen Verbräuchen (FCO-019, M8); Kilometerstandshistorie (WP-M4-41).

### WP-M4-32 – Dublettenerkennung und Evidenzverknüpfung

- **Ziel:** Doppelte Belege werden anhand von Händler, Datum, Betrag, Belegnummer und Dateifingerabdruck erkannt, verknüpft und führen nie zu einer doppelten Kostenbuchung.
- **Anforderungen:** `LH-I.1-DOC-009`
- **Liefergegenstände:**
  - Backend: Dublettenprüfung im Modul `documents-finance`: exakter Hash je Mandant; zusätzlich Händler, Belegnummer, Datum, Währung, Betrag, Karten-/Kassentransaktion; sicherer Treffer wird als weitere Evidenz desselben Ereignisses verknüpft, unsicherer Treffer wird zur Prüfung markiert und nie automatisch gelöscht oder zusammengeführt (Kap. 03 §3 Nr. 3).
  - Datenbank: `evidence_link` (Beleg/Transaktion ↔ wirtschaftliches Ereignis, Rolle der Evidenz, Entscheidung, Entscheider); Index `expense_hash`; Invariante „Genau eine Kostenwirkung“ (Kap. 04 §4.5).
  - API: Dublettenkandidaten in `/v1/expenses/{id}`; Entscheidung „gleiche Evidenz“/„eigener Vorgang“; Freigabe eines offenen Kandidaten ergibt `409 DUPLICATE_REVIEW_REQUIRED`.
  - Konfigurationsparameter: `receipt_duplicate_threshold`.
- **Detailverträge vorab:** Trefferregeln und Schwelle; Schema `evidence_link`; minimale Abbildung einer Tankkartentransaktion als zweite Evidenz für das Gate (Offener Punkt 5).
- **Akzeptanzkriterien:**
  1. Ein zweimal importierter Tankbeleg erzeugt einen Duplikathinweis und keine doppelte Kostenbuchung (DOC-009).
  2. Foto und identische Tankkartentransaktion werden als Evidenzen desselben Tankereignisses verknüpft; es entsteht genau ein Kostenereignis (E2E-065; PH-AT-012).
  3. Ein nur ähnlicher zweiter Beleg wird als möglicher Treffer zur Prüfung markiert, nicht gelöscht oder zusammengeführt (PH-AT-012).
  4. Ein Hashvergleich liefert keine Auskunft über Belege anderer Mandanten.
- **Tests:** positiv: identische Datei, gleiche Fachdaten bei anderer Datei. negativ: ähnlicher Beleg mit anderem Betrag, gleicher Hash in zweitem Mandanten ohne Treffer. konkurrierend (Geld): zwei identische Belege werden parallel freigegeben – genau ein Kostenereignis. Besteht E2E-065; Teil von PH-AT-012 und COST-01.
- **Abhängigkeiten:** intern: WP-M4-24, WP-M4-28, WP-M4-30.
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** transaktionsgenauer Abgleich von Tankkarten- und Firmenkartenabrechnungen (DOC-019, M8); Drei-Wege-Abgleich (DOC-017, M8).

### WP-M4-33 – Kostenereignis, Journal und einmaliges Posting

- **Ziel:** Eine Freigabe erzeugt genau ein unveränderliches Kostenereignis mit ausgeglichenem Journal je Rechtsträger und Währung; Wiederholungen erhöhen keine Summe.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: Aggregate EconomicEvent und Journal im Modul `documents-finance`; Posting-Befehl: Ereignis, Journal (erst `DRAFT` mit Zeilen, dann `POSTED`), Outbox in einer Transaktion oder Zustand `APPROVED_PENDING_POSTING` mit eindeutig referenziertem Worker (Kap. 03 §2.2); erneute Prüfung von Fremdschlüsseln, Periodensperre, Kontierung, Summengleichheit und Berechtigung unter Sperre; Salden nur als Projektion der Journalzeilen.
  - Datenbank: `economic_event` (Unique `(tenant_id,legal_entity_id,source_system,source_ref,event_type,scenario)`, `net_minor+tax_minor=gross_minor`, `cost_basis_minor`, `reverses_id`, Trigger `economic_immutable`), `journal` (Unique `(tenant_id,event_id)`), `journal_line`, Trigger `journal_guard` und `journal_line_guard`, `fiscal_period` (offene Testperiode), `currency`; neu `accounting_mapping` (Kostenart → Konto); Invarianten „Gegenereignis statt Änderung“ und „Journal ausgeglichen, ≥2 Zeilen, offene Periode“ (Kap. 04 §4.5).
  - API: `/v1/cost-events` (lesen, Liste mit Cursor und `as_of`).
  - Ereignisse/Outbox: Kostenereignis gebucht.
  - Konfigurationsparameter: `invoice_accounting_schema`.
- **Detailverträge vorab:** Kontierungsregeln und Test-Kontenrahmen; Steuerbehandlung im Testfall (Vorsteuer, `recoverable_tax_minor`); Abweichung der Unique-Definition zwischen Kern-DDL und Kap. 03 §2 (Revision, Offener Punkt 15); Entscheidung synchrones Posting oder Worker.
- **Akzeptanzkriterien:**
  1. Tankung brutto 119,00 EUR, netto 100,00 EUR, Vorsteuer 19,00 EUR (Testregel voll abzugsfähig) ergibt genau ein Kostenereignis mit 100,00 EUR Kostenbasis, 19,00 EUR Vorsteuer und 119,00 EUR Verbindlichkeit (PH-AT-012).
  2. Ein nochmaliger Posting-Auslöser für denselben Ursprung erhöht keine Summe und liefert dasselbe Ereignis (PH-AT-012).
  3. Ein unausgeglichenes Journal, ein Journal mit weniger als zwei Zeilen oder ein nur mandantenübergreifend ausgeglichener Buchungssatz wird abgewiesen (PH-AT-013).
  4. UPDATE/DELETE auf `economic_event` und auf ein `POSTED`-Journal scheitern.
  5. Nur `POSTED` zählt im Ist-Bericht.
- **Tests:** positiv: Posting eines Tankbelegs. negativ: unausgeglichen, geschlossene Periode, fehlende Kontierung, Betrag außerhalb des sicheren Integerbereichs. konkurrierend (Geld): zwei parallele Postings desselben Ursprungs – genau ein Ereignis und ein Journal; Posting parallel zum Periodenabschluss (mit WP-M4-36). Teil von E2E-064, PH-AT-012, PH-AT-013.
- **Abhängigkeiten:** intern: WP-M4-01, WP-M4-29.
  - extern: WP-M0-16, WP-M0-20 (Kernschema als ausgeführte Migration mit getesteten Triggern)
  - extern: WP-M1-14, WP-M1-13, WP-M1-02, WP-M1-05 (Outbox, Inbox, Idempotenzregister, Geldtyp, Rechtsträger)
- **Entscheidungen:** D-06 (Kontenrahmen, Steuerregeln, Vorsteuerabzug) und D-07 (Kostenbewertung): Bau mit Test-Kontenrahmen; Produktivschaltung blockiert.
- **Größe:** L
- **Nicht im Umfang:** Erlös-, Zahlungs- und Clearingbuchungen (`settlement_line`, M6); Plan/Forecast-Szenarien (`forecast_scenario`, FCO-017, M8); versionierte Steuerkennzeichen (DOC-021, M8).

### WP-M4-34 – Freigabe-Workflow mit Rollentrennung und Vier-Augen-Schwelle

- **Ziel:** Erfassung, fachliche Prüfung, Budgetfreigabe und Exportstatus sind getrennte Schritte mit eigenen Rechten; ohne Freigaberecht ist keine endgültige Freigabe möglich.
- **Anforderungen:** `LH-I.1-DOC-010`
- **Liefergegenstände:**
  - Backend: Freigabeautomat im Modul `documents-finance`; Prüfung von Pflichtfeldern, Kostenobjekt, Steuern, vollständigem Split, Dublettenstatus, Originalhash und Fremdfahrzeug vor Posting; bei Schwellenüberschreitung `WAITING_SECOND_APPROVAL`, zweite Freigabe durch eine andere Person; `POSTED` erst nach atomarem Journal- und Outbox-Commit.
  - Datenbank: `approval_step` (Beleg, Dokumentrevision, Schritt, Entscheider, Entscheidung, Begründung, Zeit); `expense_document` (`state`, `approved_by`).
  - API: `POST /v1/expenses/{expense_id}/approve` (`approveExpense`, Scope `expenses:approve`, `Idempotency-Key`, `If-Match`, `ApprovalRequest` mit exakter Dokumentrevision und Begründung; 409 `DUPLICATE_REVIEW_REQUIRED`, 412, 428).
  - Ereignisse/Outbox: Beleg geprüft, freigegeben, zweite Freigabe ausstehend.
  - Konfigurationsparameter: `approval_stage_thresholds`; `expense_claim_limit` (Wirkung in P1 laut Detailvertrag, Offener Punkt 39).
- **Detailverträge vorab:** Zustandsautomat Beleg; Rechtematrix je Schritt; Bedeutung der „Budgetfreigabe“ in P1 ohne Budgetmodell (Offener Punkt 17); Vertretungsregel „keine Selbstfreigabe“.
- **Akzeptanzkriterien:**
  1. Ein Benutzer ohne Freigaberecht kann keinen Beleg als endgültig freigegeben markieren (403) (DOC-010).
  2. Erfassung, fachliche Prüfung, Budgetfreigabe und Exportstatus sind als getrennte Schritte mit eigenem Recht und eigenem Eintrag nachvollziehbar (DOC-010).
  3. Über der Schwelle sind zwei verschiedene Personen erforderlich; der Erfasser oder Erstfreigeber kann die zweite Freigabe nicht selbst erteilen, auch nicht über Vertretung; ohne beide Freigaben gibt es keinen Export (E2E-068; PH-AT-019).
  4. Die Freigabe bezieht sich auf eine exakte Dokumentrevision; eine zwischenzeitliche Änderung ergibt 412.
  5. Wiederholung mit gleichem `Idempotency-Key` bucht nicht erneut.
  6. Fehlende Pflichtfelder, fehlende Kostenzuordnung, unvollständiger Split, nicht quittierter Plausibilitätshinweis (WP-M4-31) und Integritätsverletzung (WP-M4-24) blockieren die Freigabe jeweils mit eigenem stabilem Problem-Code.
  7. Jeder Schritt (Erfassung, Sachprüfung, Budgetfreigabe, zweite Freigabe) steht mit Akteur, Dokumentrevision, Entscheidung und Zeit in `approval_step` und `audit_event`; der Exportstatus ist ein eigenes Statusfeld am Beleg, das nur der Exportjob (WP-M4-37) unter eigener Exportberechtigung setzt (DOC-010).
  8. Eine Ablehnung in Sachprüfung oder Budgetfreigabe führt den Beleg mit Begründung in die Erfassung zurück und erzeugt weder Kostenereignis noch Journal.
- **Tests:** positiv: Freigabe unter Schwelle, Vier-Augen über Schwelle. negativ: fehlendes Recht, Selbstfreigabe, unbestätigte OCR-Felder, offene Dublette, offener Plausibilitätshinweis, veraltete Revision, Ablehnung mit Rückgabe. konkurrierend (Geld): zwei Freigeber geben parallel frei – genau ein Posting; zweimal dieselbe Person in beiden Schritten wird abgewiesen. Besteht E2E-068 und E2E-064; Teil von PH-AT-019 und COST-07.
- **Abhängigkeiten:** intern: WP-M4-28, WP-M4-29, WP-M4-31, WP-M4-32, WP-M4-33.
  - extern: WP-M1-07, WP-M1-13, WP-M1-46 (Rollenmodell und Rechtematrix, Idempotenzregister, Audit)
- **Entscheidungen:** D-06 (Freigabeschwellen und Rollen; Testwerte bis zum Beschluss, blockiert Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Freigabestufen nach Kostenstelle, Kostenart und Vertreterregelung (DOC-018, M8); vollständiger Statuskatalog bis Korrekturfall (DOC-026, M8); XML/PDF-Abweichung (E2E-067, Offener Punkt 4).

### WP-M4-35 – Belegaufteilung auf mehrere Kostenobjekte (centgenau)

- **Ziel:** Ein Beleg wird prozentual oder betragsmäßig auf mehrere Kostenstellen, Fahrzeuge oder Projekte verteilt; die Teilbeträge ergeben centgenau die Kostenbasis.
- **Anforderungen:** `LH-I.1-DOC-011`
- **Liefergegenstände:**
  - Backend: Allokationsdienst im Modul `documents-finance`: Dezimalarithmetik, Abrunden je Empfänger, Restcent nach größtem Dezimalrest, Gleichstand nach stabiler Empfänger-ID, negative Korrekturen über Absolutbetrag (Largest Remainder, Kap. 03 §2.1); Elternzeile sperren, Summe, Rechtsträger, Währung und Basis prüfen, aktive Revision atomar wechseln.
  - Datenbank: `allocation_set` (`basis_minor`, `algorithm_version`, `driver_snapshot`, `state` `DRAFT`/`POSTED`, Unique `(tenant_id,event_id,revision)`), `allocation_line` (`amount_minor`, `weight numeric(20,8)`), neu `allocation_activation` (genau eine aktive Umlagerevision); Invariante „Umlage centgenau und final unveränderlich“ (Kap. 04 §4.5).
  - API: `POST /v1/cost-events/{cost_event_id}/allocations` (`allocateCost`, Scope `costs:allocate`, `AllocationRequest`/`AllocationResult`, `SignedMoney`; 409 `PERIOD_CLOSED` oder `ALREADY_ALLOCATED`).
  - Konfigurationsparameter: `cost_split_rule`.
- **Detailverträge vorab:** `AllocationRequest`-Schema (Prozent als Dezimalstring oder Betrag); Reihenfolge Freigabe ↔ Split (Offener Punkt 18); Fehlercode bei Summe ≠ Basis.
- **Akzeptanzkriterien:**
  1. Die Summe der Teilbeträge entspricht centgenau dem Belegbetrag; eine Differenz blockiert Freigabe bzw. Posting (DOC-011).
  2. 100,01 EUR auf drei gleiche Anteile A/B/C ergibt 33,34 / 33,34 / 33,33 EUR; ein erneuter Lauf ist identisch (PH-AT-013).
  3. 10.000 Minor Units im Drittelsplit ergeben 3.334 / 3.333 / 3.333; die Korrektur hebt exakt die ursprünglichen Beträge auf (COST-02).
  4. Kostenobjekte eines anderen Rechtsträgers oder Mandanten als Ziel werden abgewiesen; die Allokation erzeugt keine zusätzliche Quellkostenbuchung (E2E-072).
  5. Im Code und in der API kommt für Geld kein Gleitkommatyp vor.
  6. Eine Allokation erzeugt weder eine Verbindlichkeit noch ein zusätzliches Journal noch eine Stammdatenfreigabe für fremde Kostenobjekte; `allocation_set` trägt Rechtsträger und Währung des Kostenereignisses (PH-AT-013, Referenzfall Eigentümer X / Betreiber Y nur als Negativfall; vertragliche X→Y-Leistung ist M6).
  7. Jede Allokationsrevision steht mit Akteur, Regelrevision und Begründung im `audit_event`; eine gebuchte (`POSTED`) Allokation wird nur über eine neue Revision bzw. Gegenbuchung korrigiert, nie überschrieben (Kap. 05 §5.5).
- **Tests:** positiv: prozentual, betragsmäßig. negativ: Summe ≠ Basis (Contract-Fall Kap. 05 §5.8), EUR-Rundungsrest, geschlossene Periode, bereits gebuchte Allokation, fremdes Kostenobjekt. konkurrierend (Geld): zwei parallele Allokationsrevisionen – genau eine aktive Fassung. Besteht E2E-072 (innerhalb eines Rechtsträgers) und den Allokationsteil von PH-AT-013.
- **Abhängigkeiten:** intern: WP-M4-33, WP-M4-34.
  - extern: WP-M1-02, WP-M1-13 (Geldtyp, Idempotenzregister)
- **Entscheidungen:** D-07 (Umlageschlüssel; Testschlüssel bis zum Beschluss, blockiert nur Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Allokation über Rechtsträgergrenzen mit Intercompany-Beleg (E2E-094, M6/M8); Kostensplit auf Auftraggeber und Linien (FCO-016, M8).

### WP-M4-36 – Fiskalperiode, Periodenabschluss und Gegenereignis

- **Ziel:** Geschlossene Perioden sind unveränderlich; spätere Korrekturen entstehen als Gegenereignis in der nächsten offenen Periode mit Bezug auf den Ursprung.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: Periodenverwaltung und Abschlussbefehl im Modul `documents-finance` (Sperre, bei Konkurrenz gewinnt genau eine Transaktion); Gegenereignis-Befehl (`reverses_id`), das die gespeicherten ursprünglichen Teilbeträge negativ umkehrt.
  - Datenbank: `fiscal_period` (`closed_at`, Überlappungsverbot je Rechtsträger als Migration), `economic_event.reverses_id`, `service_from/service_to` für den Ursprungsleistungszeitraum; `journal_guard` prüft Periodensperre.
  - API: `/v1/cost-events` (Gegenereignis anlegen – Unterpfad Vorschlag dieses Plans); Periodenabschluss als Befehl (Pfad im Endpunktkatalog nicht benannt, Offener Punkt 6).
  - Oberfläche: UI-14 bietet bei gebuchtem Beleg „Gegenbeleg erstellen“ (in WP-M4-38).
  - Konfigurationsparameter: `finance_close_day`.
- **Detailverträge vorab:** Zustandsautomat Periode; Regel „nächste offene Periode“; Schema Gegenereignis; Darstellung der negativen Korrektur im Journal (Kern-DDL: `journal_line.amount_minor > 0`, daher Gegenbuchung mit getauschter Seite statt negativer Zeile; Offener Punkt 42); Recht „Periode abschließen“ und Wiederöffnung (im Pflichtenheft nicht vorgesehen – ausgeschlossen).
- **Akzeptanzkriterien:**
  1. September mit 100,00 EUR abgeschlossen, Gutschrift 20,00 EUR im Oktober: Septemberjournal unverändert, Oktoberkorrektur −20,00 EUR mit Ursprungsbezug (PH-AT-015; E2E-073).
  2. Ein Posting in eine geschlossene Periode wird abgewiesen (`PERIOD_CLOSED`).
  3. Kostenereignis plus seine Allokationen ergeben in der Gesamtansicht 100,00 EUR, nicht 200,00 EUR (PH-AT-015).
  4. Abschluss und gleichzeitige Freigabe: genau eine Reihenfolge gewinnt; nach Abschluss ist die Periode nicht beschreibbar (COST-05).
  5. Der Periodenabschluss erfordert ein eigenes Finanzrecht und steht mit Akteur und Zeit im `audit_event`; eine geschlossene Periode lässt sich über keinen API-Pfad wieder öffnen.
  6. Das Gegenereignis ist selbst ein unveränderliches `economic_event` mit `reverses_id`; ein zweites Gegenereignis zum selben Ursprung und Betrag wird als Dublette abgewiesen (einmalige Wirkung).
- **Tests:** positiv: Abschluss, Gegenereignis. negativ: Buchung in geschlossene Periode (Contract-Fall Kap. 05 §5.8), überlappende Perioden. konkurrierend (Geld): Abschluss parallel zu Posting und zu Allokation. Besteht E2E-073 und den Journalteil von PH-AT-015.
- **Abhängigkeiten:** intern: WP-M4-33, WP-M4-35.
- **Entscheidungen:** D-06 (Periodenabschlussregel; blockiert nur Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** FCO-023 und DOC-030 als Anforderungen (M8); versionierte Restatement-Ansicht und Plan/Ist-Darstellung aus PH-AT-015 (M8, Offener Punkt 6).

### WP-M4-37 – ERP-Export als wiederholsicherer Job

- **Ziel:** Gebuchte Journale werden als Exportbatch mit stabilen IDs und Inhaltshash bereitgestellt; eine Wiederholung liefert denselben Batch.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: Adapter `erp-export` mit neutralem Testformat; fortsetzbarer Exportjob über `operation` mit Lease/Fencing.
  - Backend: Exportbatch (Zielsystem, Batch-ID, eindeutige Journal-ID je Zeile, Inhaltshash, Empfangsstatus); Exportstatus am Beleg.
  - Datenbank: Exportbatch- und Bestätigungstabellen (Namen im Pflichtenheft nicht festgelegt; `export_job` aus Kap. 04 §4.4 gehört zu CFG/SEC/INT – Zuordnung in WP-M4-01 klären).
  - API: `/v1/ledger/exports` (Batch anlegen, Status, Download; asynchron mit `as_of`).
  - Ereignisse/Outbox: Export bereitgestellt, Import bestätigt.
- **Detailverträge vorab:** Exportformat (bis D-06 neutrales CSV/JSON als Vorschlag dieses Plans); Batchschema; Bestätigungsprotokoll.
- **Akzeptanzkriterien:**
  1. Die Wiederholung eines Exports liefert denselben Batch mit denselben IDs und demselben Inhaltshash (Kap. 03 §2).
  2. Nur `POSTED`-Journale mit vollständiger Freigabe werden exportiert; ein Beleg ohne zweite Freigabe erscheint nicht im Export (E2E-068).
  3. Bestätigte Import-IDs werden gespeichert; ein Journal erscheint in genau einem Batch.
  4. Ein Workerabsturz während des Exports führt nach Wiederanlauf zu keinem zweiten Batch.
  5. Der Export erfordert eine von der Freigabe getrennte Exportberechtigung (Kap. 03 §3 Nr. 4); Export und Download stehen im `audit_event`; der Exportstatus am Beleg (nicht exportiert / bereitgestellt / bestätigt) wird ausschließlich vom Exportjob gesetzt (DOC-010, Exportstatus).
  6. Ein Batch enthält nur Journale eines Rechtsträgers und des aktiven Mandanten; der Download erfolgt über eine kurzlebige, mandantengebundene Adresse.
- **Tests:** positiv: Export, Wiederholung. negativ: Export ohne Recht, Export mit Freigabe- aber ohne Exportrecht, fremder Rechtsträger, leerer Zeitraum. konkurrierend (Geld): zwei parallele Exportläufe für denselben Zeitraum – kein Journal doppelt. Liefert den Exportteil von E2E-068.
- **Abhängigkeiten:** intern: WP-M4-33, WP-M4-34, WP-M4-36.
  - extern: WP-M1-15, WP-M1-07 (`operation`-Ressource mit Lease/Fencing; Exportberechtigung)
- **Entscheidungen:** D-06 (Ziel-ERP/DATEV-Variante, Kontenrahmen): Bau mit Testformat möglich, Abnahme und Produktivschaltung des Exports blockiert.
- **Größe:** M
- **Nicht im Umfang:** DOC-024 als Anforderung und DATEV-Adapter (DOC-025) – M8; `/v1/clearing/statements` (M6).

### WP-M4-38 – UI-14: Belegprüfung und Freigabe

- **Ziel:** Die Finanzrolle prüft Belege in einer Maske mit Original, OCR-Feldern, Dublettenhinweis, Kostenzuordnung, Aufteilung und Freigabe.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Oberfläche: UI-14 Belege/OCR im Operator-Web: links unverändertes Original, rechts OCR-Felder mit Konfidenz und Korrekturhistorie; Kostenobjekte, Steuergruppe, Dublettenverknüpfung, Aufteilung mit Summenkontrolle, Plausibilitätshinweise; „Freigeben“ nur bei vollständiger Validierung; Vier-Augen-Fall zeigt die zuständige zweite Person; gebuchter Beleg bietet „Gegenbeleg erstellen“; Zustände Dublette, fehlende Kostenstelle, 412-Konflikt (Kap. 06 §6.6/§6.9); Arbeitsliste nach Status. Belegerfassung per Web-Upload für Leitstelle/Finanzen.
  - API: `/v1/expenses/receipts`, `/extract`, `/v1/expenses/{id}`, `POST /v1/expenses/{expense_id}/approve`, `POST /v1/cost-events/{cost_event_id}/allocations`.
- **Detailverträge vorab:** Wireframe UI-14 mit Zustandskatalog; Fehlertexte je Problem-Code; Tastaturpfad.
- **Akzeptanzkriterien:**
  1. Der Ablauf E2E-064 (Fahrerscan, Ergänzung, Prüfung, Freigabe) ist über die Oberfläche durchführbar; das Original bleibt sichtbar und die Kosten werden einmal gebucht.
  2. Dublette und Rundungsrest sind in der Maske erkennbar und per Tastatur/Screenreader bearbeitbar (UI-Abnahme Kap. 06 §6.9).
  3. Ein 412-Konflikt führt zu einem Konfliktdialog ohne stilles Überschreiben.
  4. „Freigeben“ ist deaktiviert, solange Pflichtfelder, Kostenzuordnung oder Split unvollständig sind; der Grund wird genannt.
  5. Leere Arbeitsliste, Ladefehler und fehlendes Recht sind eigene, getestete Zustände; Rechtsträger, Belegstatus und Datenstand sind sichtbar (Kap. 06 §6.1). Der Zustand „XML-Konflikt“ aus Kap. 06 §6.9 ist in M4 nicht erreichbar (Offener Punkt 4).
- **Tests:** positiv: UI-E2E für E2E-064, E2E-066, E2E-068. negativ: Benutzer ohne Freigaberecht sieht keine aktive Freigabe; fremder Beleg 404. Barrierefreiheitsprüfung in CI.
- **Abhängigkeiten:** intern: WP-M4-28, WP-M4-31, WP-M4-32, WP-M4-34, WP-M4-35, WP-M4-36.
  - extern: WP-M1-43 (Web-Designsystem und Anwendungsrahmen)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Anzeige von PDF/XML-Abweichungen bei E-Rechnungen (M8, Offener Punkt 4); UI-16 Clearing/Abgleich (M6).

### WP-M4-39 – Kostenklassifikation: fix/variabel und direkt/umgelegt

- **Ziel:** Kosten werden je Fahrzeug und Verkehrsleistung nach den Achsen fix/variabel und direkt/zugerechnet unterschieden und getrennt sowie in Summe ausgewiesen, ohne Doppelzählung von Original und Umlage.
- **Anforderungen:** `LH-I.2-FCO-001`
- **Liefergegenstände:**
  - Backend: Klassifikation am Kostenereignis im Modul `documents-finance` (`cost_behavior` `FIXED`/`VARIABLE`/`MIXED`, `attribution` `DIRECT`/`ALLOCATED`); Auswertung als Kreuztabelle je Kostenobjekt und Periode; Regel, dass Originalposition und ihre Umlage nie zusammengezählt werden.
  - Datenbank: `economic_event.cost_behavior`, `economic_event.attribution`, `category`; neu `cost_category` (Kostenart mit Standardverhalten); Standardindex `(tenant_id,legal_entity_id,booked_at)`.
  - API: `/v1/cost-events` (Auswertung nach Klasse, Kostenobjekt, Periode; `valuation` explizit `ACTUAL`).
- **Detailverträge vorab:** Auslegung der „vier Klassen“ als zwei Achsen (Kap. 03 §1; Offener Punkt 19); Behandlung von `MIXED`; Auswertungsschema mit Datenstand.
- **Akzeptanzkriterien:**
  1. Die Kostenaufstellung eines Testfahrzeugs zeigt fix, variabel, direkt und zugerechnet getrennt und in Summe (FCO-001).
  2. Die Gesamtsumme entspricht der Summe der wirksamen Ist-Kosten; Originalposition und Umlage derselben Kosten werden nicht doppelt gezählt (FCO-001; COST-08).
  3. Die Auswertung nennt Bewertungsart, Periode, Währung und Datenstand.
  4. Die Klassifikation wird beim Posting gesetzt; weil `economic_event` unveränderlich ist, erfolgt eine Umklassifikation nur über Gegenereignis und Neubuchung und ist als solche nachvollziehbar.
  5. Die API-Bewertungsart `valuation` wird auf die DDL-Spalte `scenario` abgebildet; Abfragen ohne explizite Bewertungsart, Periode und Währung werden abgewiesen (Kap. 03 §1.1).
- **Tests:** positiv: Fixture nach Rechenbeispiel B (Kap. 03 §6) für Betreiber Y mit 1.680,00 EUR. negativ: Ereignis ohne Klassifikation wird als „nicht klassifiziert“ separat ausgewiesen, nicht verworfen; fremdes Fahrzeug.
- **Abhängigkeiten:** intern: WP-M4-33, WP-M4-35.
- **Entscheidungen:** D-07 (Kostenarten und Klassifikation; Testkatalog bis zum Beschluss, blockiert nur Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Plan/Forecast-Bewertung (FCO-017, M8); konsolidierte Verbundsicht (MUL-020, M6/M8).

### WP-M4-40 – Strukturierte Fahrzeugkosten nach Kostenart und Monat

- **Ziel:** Anschaffung/Leasing, Versicherung, Kfz-Steuer, Energie, Wartung und Reparatur werden je Fahrzeug strukturiert geführt und je Monat nach Kostenart auswertbar.
- **Anforderungen:** `LH-I.2-FCO-002`
- **Liefergegenstände:**
  - Backend: Kostenartenkatalog mit den sechs Gruppen; Erfassung von Fahrzeugkosten als Beleg oder als manuelles, freigabepflichtiges Kostenereignis; Monatsauswertung je Fahrzeug.
  - Datenbank: `cost_category` (Seed der sechs Gruppen), `economic_event` (`category`, `service_from/service_to`), `cost_object` (`kind='VEHICLE'`).
  - API: `/v1/cost-events` (anlegen, Auswertung), `/v1/vehicles/{id}/total-cost` (P1-Ausschnitt: Kosten je Monat und Kostenart; Offener Punkt 20).
- **Detailverträge vorab:** Kostenartenkatalog; Schema der Monatsauswertung; Behandlung von Anschaffung in P1 (keine Abschreibung, Kap. 03 §1.1; Offener Punkt 20).
- **Akzeptanzkriterien:**
  1. Kosten je Fahrzeug und Monat lassen sich ohne manuelle Tabellen nach Kostenart auswerten (FCO-002).
  2. Jede der sechs Kostengruppen ist einem Fahrzeug strukturiert zuordenbar (FCO-002).
  3. Die Monatszuordnung folgt dem Leistungszeitraum bzw. der Buchungsperiode laut Detailvertrag und ist im Ergebnis ausgewiesen.
  4. Ein manuell angelegtes Kostenereignis durchläuft denselben Freigabeautomaten wie ein Beleg (WP-M4-34, einschließlich Vier-Augen-Schwelle und Verbot der Selbstfreigabe) und wird über einen fachlichen Ursprungsschlüssel genau einmal gebucht.
- **Tests:** positiv: Fixture mit allen sechs Gruppen über drei Monate. negativ: Monat ohne Kosten liefert leere Auswertung statt Fehler; fremdes Fahrzeug 404; manuelles Kostenereignis ohne Freigaberecht. konkurrierend (Geld): dasselbe manuelle Kostenereignis wird parallel zweimal angelegt bzw. freigegeben – genau ein `economic_event`.
- **Abhängigkeiten:** intern: WP-M4-34, WP-M4-39.
  - extern: WP-M3-03 (Fahrzeugstamm)
- **Entscheidungen:** D-07 (Abschreibung/Leasing, Kostenarten; blockiert nur Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Abschreibung und Kapitalbindung (`asset_schedule`, FCO-006), Leasingverträge (`vehicle_lease`, FCO-007), Versicherungspolicen (FCO-008), `rate_revision` und Kostensätze (ANA-006), vollständiges TCO – alle M8.

### WP-M4-41 – Kilometerstände aus mehreren Quellen mit Herkunft

- **Ziel:** Kilometerstände aus Fahrercheck, Tankbeleg, Werkstatt und Telematik werden mit Herkunft zusammengeführt; Konflikte werden angezeigt und nie unprotokolliert überschrieben.
- **Anforderungen:** `LH-I.2-FCO-003`
- **Liefergegenstände:**
  - Backend: Kilometerstand-Register (Modulzuordnung laut Detailvertrag, Vorschlag `documents-finance`) mit Quelle, Messzeit, Erfassungszeit und Bezug; Konflikterkennung (rückläufiger Stand, widersprüchliche Quellen im selben Zeitraum); Auflösung nur durch protokollierte Entscheidung.
  - Datenbank: neue Tabelle für Kilometerstände (in Kap. 04 §4.4 nicht benannt, Vorschlag `odometer_reading`; Offener Punkt 21), append-only.
  - Ereignisse/Outbox: Konsum der Ereignisse Fahrercheck (M3) und Tankbeleg (WP-M4-30) über Inbox; Telematik über Leseport `position_sample`/Adapter.
  - API: Kilometerstände und Konflikte je Fahrzeug (Unterpfad unter `/v1/vehicles/{id}` – Vorschlag dieses Plans).
- **Detailverträge vorab:** DDL; Konfliktregeln und Toleranzen; Quellenrangfolge (im Pflichtenheft nicht festgelegt); Werkstattquelle in P1 (Offener Punkt 21).
- **Akzeptanzkriterien:**
  1. Kilometerstände aus allen vier Quellarten werden mit Herkunft gespeichert (FCO-003).
  2. Ein widersprüchlicher Stand wird als Konflikt angezeigt; kein vorhandener Wert wird überschrieben; die Auflösung ist mit Akteur und Grund protokolliert (FCO-003).
  3. Dasselbe Quellereignis erzeugt bei wiederholter Zustellung nur einen Eintrag.
- **Tests:** positiv: aufsteigende Stände aus zwei Quellen. negativ: rückläufiger Stand, zwei Quellen mit Abweichung über Toleranz. konkurrierend (Datentrennung): gleichzeitige Meldung aus zwei Quellen.
- **Abhängigkeiten:** intern: WP-M4-02, WP-M4-30.
  - extern: WP-M1-14 (Inbox-Deduplikation)
  - extern: WP-M3-17, WP-M3-14, WP-M3-34 (Kilometerstand aus Fahrzeugprüfung (`inspection_run`); Positionsdaten/Telematik)
- **Entscheidungen:** D-09 (Telematikanbieter; ohne Beschluss nur Simulator-Quelle, blockiert Produktivschaltung der Telematikquelle).
- **Größe:** M
- **Nicht im Umfang:** Werkstattaufträge `repair_order` als Quelle (FCO-011, M7/M8); Wartungsvorwarnung (`maintenance_reminder_km`, FCO-009, M8).

### WP-M4-42 – Klassifikation der Fahrzeugkilometer

- **Ziel:** Fahrzeugkilometer werden nach Fahrgastfahrt, Leerfahrt, Überführung und Werkstattfahrt klassifiziert; nicht zuordenbare Kilometer bleiben separat sichtbar.
- **Anforderungen:** `LH-I.2-FCO-005`
- **Liefergegenstände:**
  - Backend: Klassifikationsjob je Fahrzeug und Betriebstag aus Fahrtenplan (`vehicle_run`, `route_stop`), Fahrerereignissen (Einstieg/Ausstieg) und Kilometerständen bzw. Positionsdaten; Kategorie „nicht zuordenbar“ als Rest zur Gesamtstrecke.
  - Datenbank: Kilometerabschnitte je Fahrzeug, Zeitraum und Kategorie mit Quelle und Regelversion (neue Tabelle im Modul `analytics` – Vorschlag dieses Plans; `dataset_watermark` für den Datenstand).
  - API: `/v1/analytics/metrics` (Kilometer je Kategorie) bzw. `/v1/vehicles/{id}/total-cost` als Bezugsgröße.
  - Kennzahlen (Datenbasis): `Leerkilometerquote`, `Produktive Kilometer`.
- **Detailverträge vorab:** Definition der vier Kategorien und ihrer Erkennung (Überführung und Werkstattfahrt haben im Pflichtenheft kein Datenmodell, Offener Punkt 22); Quelle der Gesamtkilometer.
- **Akzeptanzkriterien:**
  1. Die Gesamtkilometer entsprechen der Summe der vier Kategorien plus der nicht zuordenbaren Kilometer (FCO-005).
  2. Nicht zuordenbare Kilometer werden separat ausgewiesen und nicht auf andere Kategorien verteilt (FCO-005).
  3. Die dokumentierte Musterfahrt ergibt die erwarteten Kilometer je Kategorie.
- **Tests:** positiv: Musterfahrt. negativ: Lücke in den Positionsdaten erhöht „nicht zuordenbar“; Fahrzeug ohne Fahrtenplan.
- **Abhängigkeiten:** intern: WP-M4-02, WP-M4-41, WP-M4-45.
  - extern: WP-M2-16, WP-M2-10 (Fahrtenplan mit Stoppliste und Distanzen)
  - extern: WP-M3-29, WP-M3-34 (Fahrerereignisse Einstieg/Ausstieg; Positionsdaten)
- **Entscheidungen:** D-07 (Leer-/Besetztkilometer-Definition), D-09 (Telematik): blockieren nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Kosten je Kilometer (FCO-015, M8); Kennzahlformeln (WP-M4-56).

### WP-M4-43 – Energiekosten je 100 km und je Betriebsstunde

- **Ziel:** Aus plausibilisierten Tankvorgängen werden Energiekosten pro 100 km bzw. je Betriebsstunde reproduzierbar berechnet.
- **Anforderungen:** `LH-I.2-FCO-004`
- **Liefergegenstände:**
  - Backend: Berechnung im Modul `analytics` aus freigegebenen, plausibilisierten Tankereignissen, zugehörigen Kilometern und Betriebsstunden; Einheiten strikt getrennt (L/100 km und kWh/100 km werden nicht gemischt); Nullnenner ergibt `null` mit `ZERO_DENOMINATOR`; unvollständige Quellen ergeben `PROVISIONAL` mit Abdeckungsgrad (Kap. 03 §7).
  - Datenbank: Lesen von `economic_event`, `fuel_transaction`, Kilometerabschnitten; Ergebnis in `aggregate_snapshot`.
  - API: `/v1/analytics/metrics`.
  - Kennzahlen: „Energiekosten je 100 km“ und „Energiekosten je Betriebsstunde“ als neue, im Katalog K nicht enthaltene Definitionen im Kennzahlregister (zu ergänzen, Offener Punkt 40); `Kraftstoff L/100 km`, `Strom kWh/100 km` und `Tankbeleg Vollständigkeit` nur als Datenbasis (nicht Teil der P1-Liste aus WP-M4-46).
- **Detailverträge vorab:** Referenzberechnung mit Testdatensatz; Definition „Betriebsstunde“ (Offener Punkt 22); Kennzeichnung der P1-Erwerbsnäherung (Kap. 03 §1.1).
- **Akzeptanzkriterien:**
  1. Der Testdatensatz liefert dieselbe Kennzahl wie die dokumentierte Referenzberechnung, pro 100 km und je Betriebsstunde (FCO-004).
  2. Nur plausibilisierte, freigegebene Vorgänge gehen ein; ausgeschlossene Vorgänge sind benannt (FCO-004).
  3. Fehlende Energiedaten werden als fehlend markiert, nicht als null Kosten (COST-08).
- **Tests:** positiv: Referenzdatensatz. negativ: null Kilometer, gemischte Einheiten, unfreigegebener Beleg.
- **Abhängigkeiten:** intern: WP-M4-31, WP-M4-33, WP-M4-42, WP-M4-46.
- **Entscheidungen:** D-07 (Energiebevorratung/Bewertung; blockiert nur Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Bestandskorrektur und Ladeverluste (P2); antriebsspezifische Plausibilitätsgrenzen (KPI-028, M8).

### WP-M4-44 – UI-15: Kostenübersicht je Kostenobjekt (Grundausbau)

- **Ziel:** Die Finanzrolle sieht je Fahrzeug die Kosten als Kreuztabelle fix/variabel × direkt/umgelegt mit Kostenarten, Kilometerklassen, Datenstand und Datenvollständigkeit.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Oberfläche: UI-15 TCO/Deckungsbeitrag, Grundausbau im Operator-Web: Rechtsträger, Periode, Währung, Sicht „Ist gebucht“; Kreuztabelle; Monatsauswertung nach Kostenart; Energiekosten je 100 km mit Nenner; Hinweise „Datenvollständigkeit“, „ungeklärte Belege“, „km unzugeordnet“; Drilldown auf Ursprungsereignisse; Zustände Datenlücke und Nenner 0 („nicht berechenbar“) (Kap. 06 §6.6/§6.9).
  - API: `/v1/cost-events`, `/v1/vehicles/{id}/total-cost`, `/v1/analytics/metrics`.
- **Detailverträge vorab:** Wireframe UI-15 Grundausbau; Abgrenzung der in P1 ausgeblendeten Sichten (Plan, Abweichung, Deckungsbeitrag, Partner).
- **Akzeptanzkriterien:**
  1. Für ein Testfahrzeug zeigt UI-15 die vier Zellen und die Summen konsistent zur API.
  2. Bei Nenner 0 erscheint „nicht berechenbar“, bei Datenlücke die Vollständigkeit; kein Scheinwert.
  3. Ohne Finanzrecht ist UI-15 nicht erreichbar; fremde Rechtsträger sind nicht auswählbar.
- **Tests:** positiv: UI-E2E Kostenübersicht. negativ: Nenner 0, fehlendes Recht. Barrierefreiheitsprüfung in CI.
- **Abhängigkeiten:** intern: WP-M4-40, WP-M4-42, WP-M4-43.
  - extern: WP-M1-43, WP-M1-31 (Web-Designsystem; Feldschutz Finanzen)
- **Entscheidungen:** D-07 (Darstellung verbindlicher Kostenberichte; blockiert nur Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** TCO-Modell, Budget, Deckungsbeitrag, Zuschussbedarf, Partnerkosten (FCO-006 ff., KPI-027, M8).

### WP-M4-45 – Analytics-Faktenbasis und Datenstand

- **Ziel:** Das Modul `analytics` baut aus Outbox-Ereignissen der Fachmodule reproduzierbare, mandantengetrennte Fakten mit Datenstand auf, ohne Schreibrechte auf Live-Buchungen.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: Ereigniskonsumenten im Modul `analytics` mit Inbox-Deduplikation `(consumer, event_id)` und Prüfung der Aggregatrevision; Faktenprojektionen für Versuche, Angebote, Buchungen, Fahrten/Stopps, Fahrzeugzustände; Wiederaufbau aus Ereignissen.
  - Datenbank: Faktentabellen im Modul `analytics` (Namen im Detailvertrag); `dataset_watermark` (Quelle, verarbeitet bis, Vollständigkeit, Stand); RLS; keine DML-Rechte des Moduls auf Tabellen anderer Module (Kap. 01 §1.3).
  - Adapter/Worker: Projektionsworker mit Tenantkontext je Transaktion.
- **Detailverträge vorab:** Ereignisschemata der Quellmodule (Versuch, Angebot, Hold, Buchung, Storno, Zahlung, Fahrerereignis, Fahrzeugstatus); Faktenschema; Regeln für verspätete und ungeordnete Ereignisse.
- **Akzeptanzkriterien:**
  1. Doppelte, verspätete und ungeordnete Ereignisse führen zu identischen Fakten wie die geordnete Einmalzustellung.
  2. Jede Faktenquelle hat einen Datenstand und eine Vollständigkeitsangabe in `dataset_watermark`.
  3. Ein Wiederaufbau der Projektion aus den Ereignissen liefert dieselben Zahlen.
  4. Das Modul kann keine Tabelle eines anderen Moduls beschreiben (Architekturtest).
  5. Fakten enthalten keine Fahrgast-Klartextdaten; Start und Ziel von Versuchen liegen nur in der vergröberten Form von `travel_attempt` (`coarse_origin`, `coarse_destination`) vor.
- **Tests:** positiv: Projektion der Fixtures. negativ: Ereignis eines fremden Mandanten im falschen Kontext wird verworfen; Lücke in der Aggregatrevision wird als unvollständig markiert. konkurrierend (Datentrennung): zwei Worker verarbeiten dasselbe Ereignis parallel – eine Wirkung.
- **Abhängigkeiten:** intern: WP-M4-01, WP-M4-02, WP-M4-64.
  - extern: WP-M1-14, WP-M1-01 (Outbox-Relay, Inbox-Deduplikation, Worker-Tenantkontext)
  - extern: WP-M2-11, WP-M2-21, WP-M2-22, WP-M2-23, WP-M2-26, WP-M2-30 (Ereignisse zu Versuch, Angebot, Hold, Buchung, Storno, Zahlung)
  - extern: WP-M3-29, WP-M3-08, WP-M3-34 (Fahrerereignisse (Einstieg, Ausstieg, No-Show), Fahrzeugsperre/-freigabe, Positionsdaten)
- **Entscheidungen:** D-11 (Aufbewahrung von Analysefakten; blockiert nur Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Simulation und Prognose (`simulation_run`, M9); Mobilitätsdatenexport (ANA-013, M8).

### WP-M4-46 – Kennzahlregister mit Definitionsversion, Nenner und Datenstand

- **Ziel:** Jede Kennzahl hat eine eindeutige, versionierte Definition mit Zähler, Nenner, Ausschlüssen, Zeitbezug und Filterdimensionen; identische Abfragen liefern identische Werte samt Datenstand.
- **Anforderungen:** `LH-I.6-KPI-010`
- **Liefergegenstände:**
  - Backend: Kennzahlregister im Modul `analytics`: stabile technische ID, Definitionsversion, Zeitzonen-/Periodenbasis, Zähler, Nenner, Ausschlüsse, Datenqualitätsquote, Datenstand (Kap. 01 §1.6); Berechnung gegen einen festen Snapshot; Nullnenner → `null` + `ZERO_DENOMINATOR`; unvollständige Quellen → `PROVISIONAL` + Abdeckungsgrad.
  - Datenbank: `metric_definition_revision` (unveränderlich nach Freigabe), `aggregate_snapshot` (Definition, Filter, Wert, Zähler, Nenner, Datenstand, Vollständigkeit), `dataset_watermark`.
  - API: `/v1/analytics/metrics` (Definitionen lesen; Werte mit Definition/Version, Filter, Datenstand, Vollständigkeit, Nenner).
  - Konfigurationsparameter: `reporting_timezone`.
  - Kennzahlen: Register für die P1-Kennzahlen (Vorschlag dieses Plans, Offener Punkt 24): `Anfragen`, `Technische Anfragefehler`, `Kapazitätsablehnungen`, `Personalmangelablehnungen`, `Ausstattungsablehnungen`, `Gebietsablehnungen`, `Zeitfensterablehnungen`, `Angebotsquote`, `Buchungskonversion`, `Buchungsstornoquote Fahrgast`, `Buchungsstornoquote Betreiber`, `No-Show-Rate`, `Abholung pünktlich`, `Ankunft pünktlich`, `Mittlere Abholverspätung`, `Mittlere Ankunftsverspätung`, `Leerkilometerquote`, `Produktive Kilometer`, `Kapazitätsauslastung distanzgewichtet`, `Sitzplatz-Zeit-Auslastung`, `Fahrgastkilometer`.
- **Detailverträge vorab:** JSON-Schema der Kennzahldefinition; Antwortschema von `/v1/analytics/metrics`; Regeln zu Betriebstag und Zeitzone; Liste der P1-Kennzahlen mit Formeln.
- **Akzeptanzkriterien:**
  1. Drei Benutzer erhalten bei identischen Filtern identische Kennzahlen einschließlich Datenstand (KPI-010).
  2. Jede registrierte Kennzahl weist Zähler, Nenner, Ausschlüsse, Zeitbezug und zulässige Filterdimensionen aus (KPI-010).
  3. Ein fehlender oder Null-Nenner ergibt „nicht berechenbar“, nie 0 oder 100 % (PH-AT-021).
  4. Bei 20 % fehlenden GPS-Daten wird die Vollständigkeit ausgewiesen; der Wert ist als vorläufig gekennzeichnet (PH-AT-021; E2E-084).
  5. Eine geänderte Definition erzeugt eine neue Version; bereits gespeicherte Snapshots behalten ihre Version.
- **Tests:** positiv: Fixture PH-AT-021. negativ: Nullnenner, unbekannte Kennzahl-ID, unzulässige Filterdimension. konkurrierend (Datentrennung): parallele Abfragen während laufender Projektion liefern denselben ausgewiesenen Datenstand. Besteht E2E-084 und den Nenner-/Datenlückenteil von PH-AT-021.
- **Abhängigkeiten:** intern: WP-M4-45.
  - extern: WP-M1-21, WP-M1-07 (wirksame Konfiguration; Rollenmodell)
- **Entscheidungen:** keine für den Bau; Zielwerte und verbindliche Berichtsdefinitionen sind AG-Freigabe (D-05, blockiert nur Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** alle 48 Definitionen ausführbar, Zeitreihenversionierung (KPI-020), Unsicherheitsausweis als eigene Anforderung (KPI-018), Berichtsjobs `/v1/reports/jobs`, `/v1/reports/jobs/{id}` (KPI-022/032) – M8.

### WP-M4-47 – Eindeutiger Versuchseintrag je Fahrtanfrage

- **Ziel:** Jede Fahrtanfrage bleibt als eigener Versuch erhalten, unabhängig davon, ob ein Angebot entsteht; technische Wiederholungen zählen nicht doppelt.
- **Anforderungen:** `LH-I.6-KPI-001`
- **Liefergegenstände:**
  - Backend: Übernahme der Versuche aus `travel_attempt` in die Faktenbasis; Abgleichprüfung Versuche ↔ Ereignisprotokoll.
  - Datenbank: `travel_attempt` (`created_at`, `channel`, `primary_rejection`, `secondary_rejections`, `coarse_origin`, `coarse_destination`); Regel „eine `attempt_id` je fachlich neuer Suche“ (Kap. 04 §4.3).
  - Kennzahlen: `Anfragen`, `Technische Anfragefehler`.
  - API: `/v1/analytics/metrics`.
- **Detailverträge vorab:** Definition „gültiger Anfrageversuch“ und Abgrenzung technischer Retries (gemeinsam mit M2); Ausschlüsse (Testanfragen).
- **Akzeptanzkriterien:**
  1. Zehn Anfragen mit drei Ablehnungen bleiben als zehn Nachfrageereignisse erhalten (KPI-001; PH-AT-021).
  2. Ein technischer Retry derselben Suche erzeugt keinen zweiten Versuch (KPI-001).
  3. Ein Versuch ohne Angebot ist in der Statistik vorhanden und nicht nachträglich löschbar.
- **Tests:** positiv: Fixture zehn Versuche. negativ: Retry mit gleichem Idempotenzschlüssel; Versuch eines fremden Mandanten erscheint nicht. konkurrierend (Datentrennung): parallele identische Suchanfragen.
- **Abhängigkeiten:** intern: WP-M4-45, WP-M4-46.
  - extern: WP-M2-11 (Anfrageerfassung schreibt `travel_attempt` für jeden Versuch)
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** räumliche Aggregation nicht bedienter Nachfrage (KPI-015, M8); Heatmap (ANA-007, M8).

### WP-M4-48 – Ablehnungen mit normiertem Primärgrund und Mehrfachursachen

- **Ziel:** Jede Ablehnung trägt genau einen normierten Primärgrund und optionale Sekundärgründe; ein Versuch zählt nur einmal als Ablehnung.
- **Anforderungen:** `LH-I.6-KPI-002`
- **Liefergegenstände:**
  - Backend: Auswertung von Primär- und Sekundärgründen im Modul `analytics`; Sekundärgründe als Mehrfachlabels ohne zusätzliche Ablehnungszählung (Kap. 01 §1.6).
  - Datenbank: `rejection_reason` (Code, Gruppe, Text, gültig von/bis), `travel_attempt.primary_rejection`, `travel_attempt.secondary_rejections`.
  - API: `/v1/analytics/metrics` (Ablehnungen nach Grund).
- **Detailverträge vorab:** Codekatalog mit Gruppen (Kapazität, Zeitfenster, Tarif usw.); Regel zur Wahl des Primärgrunds (Offener Punkt 25); Abgleich mit dem Katalog aus M2.
- **Akzeptanzkriterien:**
  1. Eine Kapazitätsablehnung ist von einer Zeitfenster- und einer Tarifablehnung über den Code unterscheidbar (KPI-002).
  2. Drei Ablehnungen, davon zwei mit Sekundärgründen, ergeben drei Ablehnungen; die Summe der Primärgründe ist drei (KPI-002; PH-AT-021).
  3. Ein nicht im Katalog enthaltener Code wird beim Schreiben abgewiesen.
- **Tests:** positiv: Fixture PH-AT-021. negativ: unbekannter Code, Ablehnung ohne Primärgrund. Teil von PH-AT-021.
- **Abhängigkeiten:** intern: WP-M4-47.
  - extern: WP-M2-13 (Katalog `rejection_reason` und Befüllung der Gründe bei der Angebotsbildung)
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** Drilldown auf Einzelfälle (KPI-019, M8).

### WP-M4-49 – Ablehnungscodes für Kapazität, Fahrzeug, Fahrer, Schichtende, Ausstattung

- **Ziel:** Die fünf ressourcenbezogenen Ablehnungsgründe werden mit unterschiedlichen Codes erzeugt und in derselben Berichtsspalte ausgewiesen.
- **Anforderungen:** `LH-I.6-KPI-003`
- **Liefergegenstände:**
  - Backend: Codes für keine Kapazität, kein Fahrzeug, kein Fahrer, Schichtende und falsche Ausstattung im Katalog; Zuordnungsregel aus dem Ergebnis des Kandidatenfilters (inkl. Schichtbesetzung aus WP-M4-17); Berichtsspalte „Ablehnungsgrund“.
  - Datenbank: `rejection_reason` (Seed), `travel_attempt`.
  - API: `/v1/analytics/metrics`.
  - Kennzahlen: `Kapazitätsablehnungen`, `Personalmangelablehnungen`, `Ausstattungsablehnungen`.
- **Detailverträge vorab:** Abgrenzung „keine Kapazität“ gegen „kein Fahrzeug“ und Rangfolge bei mehreren zutreffenden Gründen (Offener Punkt 25); Zuordnung der fünf Codes zu den Katalogkennzahlen.
- **Akzeptanzkriterien:**
  1. Fünf Testfälle (keine Kapazität, kein Fahrzeug, kein Fahrer, Schichtende, falsche Ausstattung) liefern fünf unterschiedliche Codes in derselben Berichtsspalte (KPI-003).
  2. Sind alle Fahrzeuge im Gebiet ausgelastet, erscheinen Anfrage und Normgrund in der Nachfrage-/Ablehnungsstatistik (E2E-080).
  3. Bei freiem Fahrzeug ohne einsatzbereiten Fahrer ist der Primärgrund Personalmangel; es entsteht keine Kapazitätszusage (E2E-081).
- **Tests:** positiv: fünf Testfälle. negativ: Kombination zweier Gründe ergibt einen Primär- und einen Sekundärgrund. Besteht E2E-080 und E2E-081 (mit M2-Angebotsbildung).
- **Abhängigkeiten:** intern: WP-M4-17, WP-M4-48.
  - extern: WP-M2-15 (Kandidatenfilter liefert den Ablehnungsgrund je Versuch)
  - extern: WP-M3-31, WP-M3-04 (Fahrzeugverfügbarkeit und Ausstattung)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Alarm ab Ablehnungszahl (`capacity_reject_alert_count`, KPI-021, M8).

### WP-M4-50 – Ablehnungscodes für Gebiet, Zeit, Grenzen, Partner und Technik

- **Ziel:** Die weiteren Ablehnungsgründe Bediengebiet, Betriebszeit, Mindestvorlauf, Maximalwartezeit, Umweggrenze, Partnerausfall und technischer Fehler sind katalogisiert und mit definierten Texten ausgewiesen.
- **Anforderungen:** `LH-I.6-KPI-004`
- **Liefergegenstände:**
  - Backend: sieben weitere Codes mit lokalisierten Texten; Vollständigkeitsprüfung „jeder vom System erzeugbare Grund hat Katalogeintrag und Text“.
  - Datenbank: `rejection_reason` (Seed, Texte je Sprache).
  - API: `/v1/analytics/metrics`.
  - Kennzahlen: `Gebietsablehnungen`, `Zeitfensterablehnungen`, `Technische Anfragefehler`; `Technische Partnerablehnungen` nur als Definition (keine Partner in P1).
  - Konfigurationsparameter (Auslöser der Gründe, Wirkung in M2): `min_booking_lead_min`, `max_wait_min`, `max_detour_pct`.
- **Detailverträge vorab:** Codeliste und Texte; Behandlung von „Partnerausfall“ ohne Partner in P1 (Offener Punkt 26).
- **Akzeptanzkriterien:**
  1. Ein Testsatz aller Gründe ergibt keine undefinierten Fehlertexte; jeder Code hat einen Text in Deutsch (KPI-004).
  2. Jeder der sieben Gründe ist als eigener Code auswertbar (KPI-004).
  3. Ein vom Buchungskern gelieferter unbekannter Grund lässt den Vertragstest scheitern.
- **Tests:** positiv: Testsatz aller Gründe aus WP-M4-49 und WP-M4-50. negativ: fehlender Text im Katalog bricht den CI-Vollständigkeitstest.
- **Abhängigkeiten:** intern: WP-M4-48.
  - extern: WP-M2-12, WP-M2-18 (Zulässigkeitsprüfung und harte Grenzen liefern die Gründe)
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** Partnerbezogene Gründe mit realen Partnern (M6).

### WP-M4-51 – Nachfragetrichter und getrennte Zählung der Buchungsstufen

- **Ziel:** Anfragen, Angebote, bestätigte und abgeschlossene Buchungen werden getrennt gezählt; der Funnel unterscheidet Ablehnung, nicht gebuchtes Angebot, fehlgeschlagene Zahlung und Fahrgaststorno.
- **Anforderungen:** `LH-I.6-KPI-005`, `LH-6.14-ANA-001`
- **Liefergegenstände:**
  - Backend: Funnelprojektion im Modul `analytics` aus expliziten Ereignissen: Anfrage, Angebot angezeigt, Buchung bestätigt, Zahlung fehlgeschlagen, Fahrgaststorno, Fahrt abgeschlossen; Abgleichbericht gegen das Ereignisprotokoll.
  - Datenbank: Funnelfakten; `aggregate_snapshot`.
  - API: `/v1/analytics/metrics` (Funnel und Zählwerte).
  - Kennzahlen: `Anfragen`, `Angebotsquote`, `Buchungskonversion`, `Buchungsstornoquote Fahrgast`, `Buchungsstornoquote Betreiber`.
- **Detailverträge vorab:** Ereignisdefinitionen, insbesondere „Angebot angezeigt“ und „abgeschlossene Buchung“ (Offener Punkt 27); Zuordnung jedes Versuchs zu genau einem Endzustand je Stufe.
- **Akzeptanzkriterien:**
  1. Ein Funnel-Bericht zeigt Anfrage abgelehnt, Angebot angezeigt aber nicht gebucht, Zahlung fehlgeschlagen und Fahrgaststorno getrennt (KPI-005).
  2. Für einen Testzeitraum stimmen die Zahlen für Anfragen, Angebote, bestätigte und abgeschlossene Buchungen mit dem Ereignisprotokoll überein (ANA-001).
  3. Die vier Zählgrößen werden getrennt und nicht als eine Summe ausgewiesen (ANA-001).
  4. Betreiberstorno und Fahrgaststorno sind unterscheidbar.
- **Tests:** positiv: Fixture mit bekanntem Ereignisprotokoll. negativ: Storno nach Abschluss, doppelte Ereigniszustellung verändert keine Zahl.
- **Abhängigkeiten:** intern: WP-M4-46, WP-M4-48.
  - extern: WP-M2-21, WP-M2-23, WP-M2-26, WP-M2-30 (Ereignisse Angebot, Buchung bestätigt, Storno mit Grund, Zahlungsstatus)
  - extern: WP-M3-29 (Fahrtabschluss (Ausstieg))
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Angebotsabbruch- und Konversionsquote als eigene P2-Kennzahlen (KPI-026, M8); Kohorten- und Vorjahresvergleich (KPI-025, M8).

### WP-M4-52 – Getrennte Zeitpunkte: geplant, angekündigt, zugesagt, prognostiziert, tatsächlich

- **Ziel:** Für Abholung und Ankunft werden die fünf Zeitarten getrennt gespeichert; Verspätung wird gegen die zum Zusagezeitpunkt gültige Zielzeit berechnet.
- **Anforderungen:** `LH-I.6-KPI-006`
- **Liefergegenstände:**
  - Backend: Zeitfakten je Fahrgastfahrt und Stopp im Modul `analytics` mit Zeitart, Wert, Quelle und Version (Zusageversion, Planrevision, Prognoseversion); Verspätungsberechnung ausschließlich gegen die gebundene Zusageversion.
  - Datenbank: Zeitfaktentabelle; Bezug auf `booking` (`promise_snapshot` mit Zeitfenster) über Leseport.
  - Ereignisse/Outbox: Konsum von Buchungs-, Planrevisions-, Benachrichtigungs- und Fahrerereignissen.
- **Detailverträge vorab:** Definition der fünf Zeitarten und ihrer Quellen, insbesondere „angekündigt“ (Offener Punkt 28); Umgang mit einvernehmlicher Änderung der Zusage (neue Zusageversion).
- **Akzeptanzkriterien:**
  1. Geplante, angekündigte, zugesagte, prognostizierte und tatsächliche Abhol- und Ankunftszeiten sind je Fahrt getrennt gespeichert und abrufbar (KPI-006).
  2. Der Zeitvergleich für Verspätung nutzt die zum Zeitpunkt der Zusage gültige Zielzeit; eine spätere Umplanung oder Prognose verändert die Vergleichsbasis nicht (KPI-006).
  3. Eine nachträgliche Änderung von Pünktlichkeitsschwelle oder Tarif ändert die gespeicherte Zusage nicht (PH-AT-018).
- **Tests:** positiv: Fahrt mit Umplanung nach Zusage. negativ: fehlende Ist-Zeit ergibt „nicht bewertbar“, keinen Nullwert; Ist-Zeit aus Offline-Ereignis mit Geräte- und Serverzeit.
- **Abhängigkeiten:** intern: WP-M4-45.
  - extern: WP-M1-27 (revisionsfeste Zusage)
  - extern: WP-M2-23, WP-M2-28, WP-M2-34 (Zusagesnapshot mit Zeitfenstern, Planrevisionen, Benachrichtigungen)
  - extern: WP-M3-29 (Ist-Abholung und Ist-Ankunft aus Fahrerereignissen)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Verspätungsursachenklassifikation (KPI-016, M8); ETA-Fehlerkennzahlen (`ETA-MAE`, `ETA-Bias`, `ETA-p90-Abdeckung`, M8).

### WP-M4-53 – Abholpünktlichkeit gegen Zusagefenster

- **Ziel:** Die Pünktlichkeit der Abholung wird gegen das zugesagte Fenster mit konfiguriertem Grenzwert und festem Vergleichsstichtag gemessen.
- **Anforderungen:** `LH-I.6-KPI-007`
- **Liefergegenstände:**
  - Backend: Kennzahlberechnung im Modul `analytics`: Abholverspätung = tatsächliche Abholung minus Ende des zugesagten Abholfensters; Klassifikation mit dem zum Vergleichsstichtag wirksamen Grenzwert des Dienstes/Gebiets; Version des Grenzwerts am Ergebnis.
  - Datenbank: `metric_definition_revision`, `aggregate_snapshot`.
  - API: `/v1/analytics/metrics`.
  - Konfigurationsparameter: `on_time_pickup_tolerance_min`, `pickup_window_min`.
  - Kennzahlen: `Abholung pünktlich`, `Mittlere Abholverspätung`.
- **Detailverträge vorab:** Formel; Bedeutung „Vergleichsstichtag“ und Ebene des Grenzwerts (Katalog: Dienst; Anforderung: gebietsspezifisch – Offener Punkt 29); Nenner „tatsächliche qualifizierte Abholungen“.
- **Akzeptanzkriterien:**
  1. Eine Fahrt mit 3 Minuten Überschreitung wird nach dem gebietsspezifisch wirksamen Grenzwert korrekt klassifiziert: pünktlich bei Grenzwert ≥ 3, verspätet bei Grenzwert < 3 (KPI-007).
  2. Zusage 08:10–08:15, tatsächliche Abholung 08:22 ergibt 7 Minuten Abholverspätung gegen die Zusageversion, getrennt von der Ankunft (E2E-082).
  3. Eine spätere Änderung des Grenzwerts verändert bereits gespeicherte Berichtstände nicht (Katalog: „an historische SLA-Version gebunden“).
- **Tests:** positiv: Grenzfälle 2/3/4 Minuten. negativ: Abholung ohne Ist-Zeit ist nicht bewertbar und nicht im Nenner. Besteht E2E-082.
- **Abhängigkeiten:** intern: WP-M4-46, WP-M4-52.
  - extern: WP-M1-21, WP-M1-20 (wirksame Konfiguration mit Historie)
- **Entscheidungen:** D-05 (SLA-Grenzwerte des Pilots; Testwerte bis zum Beschluss, blockiert nur Produktivschaltung).
- **Größe:** S
- **Nicht im Umfang:** `95%-Abholverspätung` und Wartezeitkennzahlen (ANA-004, M8).

### WP-M4-54 – Ankunftspünktlichkeit getrennt von der Abholung

- **Ziel:** Die Ankunftspünktlichkeit wird gegen die zugesagte Ankunftszeit bzw. das gewählte Servicefenster als eigene Kennzahl gemessen.
- **Anforderungen:** `LH-I.6-KPI-008`
- **Liefergegenstände:**
  - Backend: Kennzahlberechnung im Modul `analytics`: Ankunftsverspätung = tatsächliche Ankunft minus zugesagte späteste Ankunft plus Toleranz; unabhängig vom Abholergebnis.
  - Datenbank: `metric_definition_revision`, `aggregate_snapshot`.
  - API: `/v1/analytics/metrics`.
  - Konfigurationsparameter: `on_time_arrival_tolerance_min`, `arrival_grace_min`.
  - Kennzahlen: `Ankunft pünktlich`, `Mittlere Ankunftsverspätung`.
- **Detailverträge vorab:** Formel; Verhältnis von `arrival_grace_min` zu `on_time_arrival_tolerance_min`; Bezugsgröße bei gewähltem Servicefenster.
- **Akzeptanzkriterien:**
  1. Eine verspätete Ankunft trotz pünktlicher Abholung erscheint in einer eigenen Kennzahl; die Abholkennzahl bleibt „pünktlich“ (KPI-008).
  2. Abholung pünktlich, Ankunft 17 Minuten nach zugesagter Ankunft: Ankunftsverspätung 17 Minuten, getrennt ausgewiesen (E2E-083).
  3. Keine rückwirkende Statistikänderung bei geändertem Grenzwert (Katalogvalidierung).
- **Tests:** positiv: E2E-083-Fixture. negativ: Fahrt ohne Ankunftsereignis nicht im Nenner. Besteht E2E-083 (Ursachenfeld siehe WP-M4-55).
- **Abhängigkeiten:** intern: WP-M4-46, WP-M4-52.
- **Entscheidungen:** D-05 (SLA-Grenzwerte; blockiert nur Produktivschaltung).
- **Größe:** S
- **Nicht im Umfang:** `Nicht erreichte Anschlüsse` (KPI-023, M8); Alarm `late_arrival_alert_pct` (M8).

### WP-M4-55 – Nicht durchgeführte Fahrten: Ursache und Verursachungskategorie

- **Ziel:** Jede tatsächlich nicht durchgeführte Fahrt erhält eine Ursache und eine Verursachungskategorie; Korrekturen werden als Vermerk geführt, nicht überschrieben.
- **Anforderungen:** `LH-I.6-KPI-009`
- **Liefergegenstände:**
  - Backend: Fakt „Fahrt nicht durchgeführt“ im Modul `analytics` mit Ursache (z. B. Fahrzeugausfall, Fahrgast-No-Show, Storno) und Verursachungskategorie (Betreiber, Fahrgast, extern – Vorschlag dieses Plans); Korrekturbefehl mit Akteur, Grund, altem und neuem Wert.
  - Datenbank: Ursachenkatalog und Korrekturvermerke (append-only) im Modul `analytics`.
  - API: `/v1/analytics/metrics` (Auswertung); Korrektur über einen Befehl für berechtigte Rollen (Unterpfad Vorschlag dieses Plans).
  - Kennzahlen: `No-Show-Rate`, `Buchungsstornoquote Betreiber`.
- **Detailverträge vorab:** Katalog der Ursachen und Verursachungskategorien (Offener Punkt 30); Rechte für Korrekturen; Ableitung aus Storno- und Störungsereignissen.
- **Akzeptanzkriterien:**
  1. Fahrzeugausfall und Fahrgast-No-Show werden als unterschiedliche Ursachen mit unterschiedlicher Verursachungskategorie erfasst (KPI-009).
  2. Jede nicht durchgeführte bestätigte Fahrt hat genau eine Ursache; fehlt sie, ist die Fahrt als „Ursache offen“ sichtbar und nicht stillschweigend ausgeschlossen (KPI-009).
  3. Eine Korrektur der Ursache erhält den alten Wert als Vermerk mit Akteur und Zeit (KPI-009).
- **Tests:** positiv: Fahrzeugausfall, No-Show. negativ: Korrektur ohne Recht; Korrektur ohne Grund. konkurrierend (Datentrennung): zwei parallele Korrekturen – beide Vermerke bleiben, letzter Stand eindeutig.
- **Abhängigkeiten:** intern: WP-M4-45, WP-M4-46.
  - extern: WP-M2-26, WP-M2-27 (Storno mit Grund, Stornoursache Fahrzeugausfall)
  - extern: WP-M3-29, WP-M3-53, WP-M3-54 (No-Show-Ereignis, Fahrzeugausfall/Störungsablauf)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Fahrzeugausfallzeiten nach Ursache (`Ausfallminuten kritisch`, KPI-029, M8); automatische Schuldzuweisung ist ausgeschlossen.

### WP-M4-56 – Kilometer-, Betriebsstunden- und Fahrgastkilometer-Kennzahlen

- **Ziel:** Fahrzeug-, Besetzt-, Leer- und Fahrgastkilometer sowie Betriebsstunden werden mit dokumentierten Erfassungsgrenzen berechnet; eine Musterfahrt ergibt die erwarteten Werte.
- **Anforderungen:** `LH-I.6-KPI-013`, `LH-6.14-ANA-002`
- **Liefergegenstände:**
  - Backend: Kennzahlberechnung im Modul `analytics` auf Basis der Kilometerklassifikation (WP-M4-42): Fahrzeugkilometer (gesamt), Besetztkilometer/produktive Kilometer (mindestens ein Fahrgast an Bord), Leerkilometer, Fahrgastkilometer (Summe der individuellen Fahrtstrecken), Betriebsstunden; Erfassungsgrenzen (Beginn/Ende des Betriebsdienstes, Behandlung von Überführung und Werkstattfahrt) in der Definition.
  - Datenbank: `metric_definition_revision`, `aggregate_snapshot`, `dataset_watermark`.
  - API: `/v1/analytics/metrics`.
  - Kennzahlen: `Fahrgastkilometer`, `Produktive Kilometer`, `Leerkilometerquote`.
- **Detailverträge vorab:** Formeln und Erfassungsgrenzen; dokumentierte Musterfahrt mit Sollwerten; Distanzquelle (Routingdistanz mit Version oder Messung – Offener Punkt 22).
- **Akzeptanzkriterien:**
  1. Die dokumentierte Musterfahrt ergibt die erwarteten Fahrzeug-, Besetzt- und Fahrgastkilometer (ANA-002).
  2. Die Berechnung jeder der drei Größen ist in der Kennzahldefinition dokumentiert und abrufbar (ANA-002).
  3. Eine Überführungsfahrt wird nicht als produktive Fahrgaststrecke gezählt (KPI-013).
  4. Leerkilometer, produktive Kilometer, Betriebsstunden und Fahrgastkilometer haben je eine dokumentierte Erfassungsgrenze; die Summe aus Leer- und produktiven Kilometern plus nicht zuordenbaren Kilometern entspricht den Fahrzeugkilometern im Betriebsdienst (KPI-013).
  5. Fehlen GPS-Daten für einen Teil der Fahrten, wird die Vollständigkeit ausgewiesen statt eines scheingenauen Werts (E2E-084).
- **Tests:** positiv: Musterfahrt mit zwei Fahrgästen und Teilstrecken. negativ: Überführung, Werkstattfahrt, Datenlücke. Teil von E2E-084.
- **Abhängigkeiten:** intern: WP-M4-42, WP-M4-46.
- **Entscheidungen:** D-07 (Leer-/Besetztkilometer-Definition; blockiert nur Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** `Pooling-Nutzungsquote` (ANA-005, M8); `Fahrgastfahrten pro Fahrzeugstunde`, `Kosten pro Fahrzeugkilometer`, `Kosten pro Fahrgastfahrt`, `Kosten pro Fahrgastkilometer` (M8).

### WP-M4-57 – Auslastung als Kapazitätszeit- und Fahrgastkilometerkennzahl

- **Ziel:** Die Auslastung wird sowohl zeitbezogen als auch distanzbezogen mit dokumentierten Formeln berechnet.
- **Anforderungen:** `LH-I.6-KPI-012`
- **Liefergegenstände:**
  - Backend: zwei Kennzahlen im Modul `analytics`: belegte Sitzplatzminuten / verfügbare Sitzplatzminuten und besetzte Platz-km / angebotene Platz-km; Kapazität aus dem Ressourcenvektor des Fahrzeugs (`vehicle.capacity_config`).
  - Datenbank: `metric_definition_revision`, `aggregate_snapshot`.
  - API: `/v1/analytics/metrics`.
  - Kennzahlen: `Sitzplatz-Zeit-Auslastung`, `Kapazitätsauslastung distanzgewichtet`.
- **Detailverträge vorab:** Formeln; Zuordnung der Anforderung zu den beiden Katalogkennzahlen (Offener Punkt 31); Behandlung von Rollstuhlplätzen und Umrüstzuständen im Nenner.
- **Akzeptanzkriterien:**
  1. Ein Testumlauf mit vier Fahrgästen zeigt beide Metriken mit den erwarteten Werten (KPI-012).
  2. Beide Formeln sind in der Kennzahldefinition dokumentiert und über die API abrufbar (KPI-012).
  3. Ohne verfügbare Kapazität im Zeitraum ist die Kennzahl „nicht berechenbar“.
- **Tests:** positiv: Testumlauf mit vier Fahrgästen und überlappenden Teilstrecken. negativ: Nullnenner, Fahrzeug mit geänderter Kapazität im Zeitraum.
- **Abhängigkeiten:** intern: WP-M4-56.
  - extern: WP-M3-04 (Fahrzeugkapazität als Ressourcenvektor)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Standarddashboard Auslastung/Leerkilometer (ANA-009, M8).

### WP-M4-58 – Filterdimensionen der Berichte ohne Mandantenüberschreitung

- **Ziel:** Berichte und Leistungsdaten lassen sich nach Mandant, Betreiber, Bediengebiet, Dienst, Zeitraum, Tag, Stunde, Fahrzeug und Fahrzeugtyp filtern; Filter schränken die Autorisierung nur weiter ein.
- **Anforderungen:** `LH-I.6-KPI-011`, `LH-6.14-ANA-003`
- **Liefergegenstände:**
  - Backend: Filterschema im Modul `analytics` mit den Dimensionen Mandant (nur der aktive), Betreiber, Bediengebiet, Dienst, Zeitraum/Tag/Stunde (in `reporting_timezone`), Fahrzeug, Fahrzeugtyp; serverseitige Schnittmenge aus Filter und Berechtigung; Unterdrückung kleiner Gruppen.
  - Datenbank: Dimensionsspalten in den Faktentabellen; RLS; Indizes beginnend mit `tenant_id`.
  - API: `/v1/analytics/metrics` (Filterparameter, Antwort `{items,next_cursor,as_of}`).
  - Konfigurationsparameter: `reporting_timezone`, `kpi_aggregation_min_n`.
- **Detailverträge vorab:** Filterschema; Bedeutung der Dimension „Mandant“ innerhalb eines Mandantenkontexts (Offener Punkt 33); Regel zur Kleingruppenunterdrückung einschließlich Differenzbildung.
- **Akzeptanzkriterien:**
  1. Jeder der Filter Betreiber, Dienst, Gebiet, Zeitraum und Fahrzeug verändert das Ergebnis korrekt gegenüber der Referenzberechnung (ANA-003).
  2. Kein Filterwert, auch kein manipulierter, liefert Daten eines anderen Mandanten (ANA-003).
  3. Ein Bericht über zwei Regionen lässt sich auf eine Region und eine Stunde einschränken (KPI-011).
  4. Die Dimensionen Mandant, Betreiber, Bediengebiet, Dienst, Tag, Stunde und Fahrzeugtyp sind einzeln und kombiniert filterbar (KPI-011).
  5. Gruppen unter `kpi_aggregation_min_n` werden auch durch Differenzbildung benachbarter Filter nicht offengelegt (PH-AT-021).
- **Tests:** positiv: je Filter ein Referenzvergleich; Kombination Region + Stunde. negativ: fremde Dienst-ID, fremde Fahrzeug-ID, manipulierte `tenant_id`. konkurrierend (Datentrennung): parallele Abfragen zweier Mandanten über denselben Verbindungspool zeigen keine Vermischung. Teil von PH-AT-021; Isolationsteil analog PH-AT-001.
- **Abhängigkeiten:** intern: WP-M4-46.
  - extern: WP-M1-08, WP-M1-01, WP-M1-09 (Dienst- und Objektberechtigung, Isolations-Testharness, Organisationsstruktur (Regionen))
  - extern: WP-M2-03, WP-M2-04 (Dienste und Bediengebiete als Dimensionen)
- **Entscheidungen:** D-02 (Isolationsstufe; RLS-Default, blockiert nicht).
- **Größe:** M
- **Nicht im Umfang:** mandantenübergreifende Verbundkennzahlen (KPI-031, M6/M8); Export und Berichtsjobs (KPI-032, ANA-008, M8).

### WP-M4-59 – Kennzahlenansicht im Operator-Web

- **Ziel:** Berechtigte Benutzer sehen die P1-Kennzahlen, den Nachfragetrichter und die Ablehnungsstatistik mit Filtern, Definition, Datenstand und Vollständigkeit.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Oberfläche: Kennzahlenteil von UI-19 (Kennzahlen/Forecast/Simulation) im Operator-Web als Grundausbau: Filterleiste, Kennzahlkarten mit Zähler/Nenner, Funnel, Ablehnungen nach Grund in einer Spalte, Pünktlichkeit Abholung/Ankunft getrennt; Anzeige von Definitionsversion, Datenstand und Vollständigkeit; Zustände „keine Daten“, „nicht berechenbar“, „geringe Qualität“ (Kap. 06 §6.9); Tabellenalternative zu Diagrammen.
  - API: `/v1/analytics/metrics`.
- **Detailverträge vorab:** Wireframe; Zuordnung zu UI-19, das der Meilensteinplan für M4 nicht nennt (Offener Punkt 23).
- **Akzeptanzkriterien:**
  1. Der Funnel-Bericht mit vier getrennten Ereignissen und die Ablehnungsstatistik sind in der Oberfläche sichtbar und filterbar.
  2. Bei Datenlücke zeigt die Ansicht die Datenvollständigkeit und vermeidet Scheingenauigkeit (E2E-084).
  3. Drei Benutzer mit gleichen Filtern sehen dieselben Werte und denselben Datenstand.
  4. Ohne Berichtsrecht ist die Ansicht nicht erreichbar.
  5. Kilometer-, Auslastungs- und Nichtdurchführungskennzahlen (WP-M4-55 bis WP-M4-57) sind mit Formelverweis sichtbar; unterdrückte Kleingruppen erscheinen als „unterdrückt“, nicht als 0.
- **Tests:** positiv: UI-E2E für E2E-080 bis E2E-084. negativ: fehlendes Recht, leerer Zeitraum. Barrierefreiheitsprüfung in CI.
- **Abhängigkeiten:** intern: WP-M4-49, WP-M4-50, WP-M4-51, WP-M4-53, WP-M4-54, WP-M4-55, WP-M4-56, WP-M4-57, WP-M4-58.
  - extern: WP-M1-43 (Web-Designsystem und Anwendungsrahmen)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Forecast- und Simulationsteil von UI-19 (M9); Dashboard-Builder (KPI-033, M8); Heatmap (ANA-007, M8).

### WP-M4-60 – Live-Dashboard für den laufenden Betrieb

- **Ziel:** Ein Live-Dashboard zeigt offene Anfragen, verfügbare Kapazität, kritische Verspätungen und Fahrzeugausfälle und aktualisiert sich innerhalb einer definierten Frist.
- **Anforderungen:** `LH-I.6-KPI-014`
- **Liefergegenstände:**
  - Backend: Live-Projektion im Modul `analytics` aus Ereignissen zu Versuchen/Angeboten, Kapazität, Fahrtfortschritt und Fahrzeugstatus; Push- oder Abfragekanal mit Zeitstempel der letzten Aktualisierung.
  - Oberfläche: Live-Kacheln im Operator-Web (im Kontext UI-07 Operator Livebetrieb bzw. UI-19; Zuordnung in WP-M4-01): offene Anfragen, verfügbare Kapazität, kritische Verspätungen, Fahrzeugausfälle; Datenalter sichtbar; Aktualisierung über zurückhaltende Live-Region.
  - API: `/v1/analytics/metrics` (Live-Werte mit `as_of`).
  - Konfigurationsparameter: `vehicle_unavailability_alert_min`, `on_time_pickup_tolerance_min` (Schwelle „kritisch“ laut Detailvertrag).
- **Detailverträge vorab:** Aktualisierungsfrist (im Pflichtenheft nicht beziffert) und Definition „kritische Verspätung“, „offene Anfrage“, „verfügbare Kapazität“ (Offener Punkt 32); Transport (SSE/WebSocket/Polling).
- **Akzeptanzkriterien:**
  1. Die Änderung eines Fahrzeugstatus (z. B. Sperre) ist innerhalb der definierten Aktualisierungsfrist im Dashboard sichtbar (KPI-014).
  2. Das Dashboard zeigt offene Anfragen, verfügbare Kapazität, kritische Verspätungen und Fahrzeugausfälle als getrennte Werte (KPI-014).
  3. Bei unterbrochener Datenzufuhr zeigt das Dashboard das Datenalter und keinen scheinbar aktuellen Wert.
  4. Es werden ausschließlich Daten des aktiven Mandanten und der berechtigten Dienste gezeigt.
  5. Die Live-Kacheln sind per Tastatur und Screenreader lesbar; Aktualisierungen verschieben weder Fokus noch Bildschirmposition (Kap. 06 §6.1).
- **Tests:** positiv: Statusänderung mit Zeitmessung bis zur Anzeige. negativ: Ereignisstau, fremder Dienst. konkurrierend (Datentrennung): zwei Mandanten mit gleichzeitigen Statusänderungen.
- **Abhängigkeiten:** intern: WP-M4-45, WP-M4-59.
  - extern: WP-M2-11, WP-M2-22, WP-M2-14 (Ereignisse zu Anfragen, Holds, Kapazität)
  - extern: WP-M3-31, WP-M3-08, WP-M3-29, WP-M3-36 (Fahrzeugstatus (Sperre/Freigabe), Fahrtfortschritt, Livebetrieb UI-07)
- **Entscheidungen:** D-05 (SLA/Aktualisierungsziel des Pilots; Planungsannahme bis zum Beschluss, blockiert nicht den Bau).
- **Größe:** M
- **Nicht im Umfang:** Qualitätswarnungen gegen Zielwerte und Alarme (`alert_escalation_delay_min`, KPI-021, AUT P2, M8).

### WP-M4-61 – Fahrerneutrale Referenzfahrzeit

- **Ziel:** Für jede Strecke wird eine fahrerneutrale, verkehrs- und regelkonforme Referenzfahrzeit berechnet, die nicht von der Fahrer-ID abhängt.
- **Anforderungen:** `LH-I.3-ETA-001`
- **Liefergegenstände:**
  - Backend: ETA-Grundmodell als Fachoperation (Modulzuordnung laut WP-M4-01; Aufrufer ist die Disposition): Referenzfahrzeit = rechtmäßige Routingzeit des Routingadapters; Eingaben ausschließlich Strecke, Zeit, Fahrzeugprofil und Straßendatenversion; die Fahrer-ID ist kein Parameter der Schnittstelle. Jede Prognose liefert Modellversion und Fallbackgrund (Kap. 01 §1.6).
  - Datenbank: `eta_model_revision` (Version des Grundmodells, Parameter, Straßendatenversion, Freigabe).
  - Konfigurationsparameter: `eta_personalization_enabled` (in P1 fest `false`), `eta_fallback_policy`.
- **Detailverträge vorab:** Schnittstelle des ETA-Dienstes und Abgrenzung zum Routingadapter aus M2 (Offener Punkt 34); Schema `eta_model_revision`; Determinismusregel (gleiche Eingaben, gleiche Datenversion → gleiche Grundzeit).
- **Akzeptanzkriterien:**
  1. Ein Referenzlauf mit identischen Straßendaten ergibt für unterschiedliche Fahrer-IDs dieselbe Grundzeit (ETA-001).
  2. Die Schnittstelle des Grundmodells nimmt keine Fahrer-ID entgegen; ein Architekturtest verhindert den Zugriff auf Fahrerprofile (ETA-001).
  3. `eta_personalization_enabled` lässt sich in P1 nicht wirksam aktivieren; die fahrerneutrale Referenz bleibt aktiv (PH-AT-022, Teilaspekt).
  4. Jede Prognose trägt Modellversion und Straßendatenversion.
- **Tests:** positiv: Referenzstrecken mit Sollzeiten. negativ: Aktivierungsversuch der Personalisierung; Routingdienst nicht erreichbar → definierter Fallbackgrund statt geschätztem Wert. Liefert die Grundlage für PH-AT-022 (Gate M8).
- **Abhängigkeiten:** intern: WP-M4-01, WP-M4-02.
  - extern: WP-M0-30, WP-M0-08 (Routingvergleich/Spike aus D-09; Valhalla in der lokalen Umgebung)
  - extern: WP-M2-10 (Routingadapter und Fahrzeitmatrix)
- **Entscheidungen:** D-10 (Favorit: P1 ohne individuelles Fahrerprofil – Bau nach Favorit, Personalisierung bleibt aus), D-09 (Routinganbieter; Valhalla/OSM als Arbeitsstand, Produktivschaltung blockiert).
- **Größe:** M
- **Nicht im Umfang:** fahrerbezogenes Lernen (`driving_observation`, `eta_profile`, `model_approval`; ETA-003 ff.), `/v1/eta/models/{id}/quality`, `/v1/eta/profiles/{id}/challenge` – M8.

### WP-M4-62 – Regelkonforme Prognose mit Haltezeiten und Sicherheitsreserve

- **Ziel:** Fahrzeitprognosen setzen rechtmäßige Geschwindigkeiten, Haltestellenaufenthalte und Sicherheitsreserven voraus; kein ausgegebenes Zeitfenster erfordert eine Geschwindigkeitsüberschreitung.
- **Anforderungen:** `LH-I.3-ETA-002`
- **Liefergegenstände:**
  - Backend: Prognose = Referenzfahrzeit + Haltezeiten je Stopp (einschließlich Rollstuhl-Einstieg) + kalibrierter Unsicherheitspuffer; Prüfroutine, die für jedes ausgegebene Fenster die implizite Geschwindigkeit je Abschnitt gegen die zulässige Geschwindigkeit der Routendaten prüft; Ausgabe von Unsicherheit und verwendetem Puffer.
  - Backend (Zusage an M2/M3): Leseport „ETA je Stopp“ für Fahrgastanzeige, Benachrichtigungsauslöser und Leitstelle (WP-M2-34, WP-M2-43, WP-M3-26, WP-M3-38) mit Prognosezeit, Modell-/Konfigurationsversion, Datenstand und Fallbackgrund; bei Positionsdaten älter als `eta_max_data_age_sec` Rückfall laut `eta_fallback_policy` statt scheinbar aktueller Prognose. Umfang der Neuberechnung aus Live-Positionen siehe Offener Punkt 41.
  - Datenbank: `eta_model_revision` (Puffer-/Quantilparameter).
  - Konfigurationsparameter: `stop_dwell_sec`, `wheelchair_boarding_sec`, `eta_model_quantile`, `eta_safe_speed_tolerance`, `eta_max_data_age_sec`.
- **Detailverträge vorab:** Höhe und Herleitung der Sicherheitsreserve (im Pflichtenheft nicht beziffert, Offener Punkt 34); Prüfalgorithmus „keine Überschreitung“; Verhalten, wenn Routendaten keine Geschwindigkeitsbegrenzung enthalten.
- **Akzeptanzkriterien:**
  1. Für einen Testsatz von Strecken erfordert kein ausgegebenes Zeitfenster nach den verwendeten Routendaten eine Geschwindigkeitsüberschreitung (ETA-002).
  2. Haltestellenaufenthalte und Sicherheitsreserve sind in jeder Prognose als eigene Anteile ausgewiesen (ETA-002).
  3. Künstlich zu schnelle Eingabewerte führen nicht zu einem engeren Fenster (PH-AT-022, Teilaspekt).
  4. `stop_dwell_sec` lässt sich nicht unter die sichere Haltestellenabfertigung konfigurieren (Katalogvalidierung).
  5. Der Leseport „ETA je Stopp“ liefert für eine laufende Fahrt eine Prognose mit Datenstand; bei veralteten Positionsdaten wird der Fallbackgrund ausgewiesen und die verbindliche Zusage bleibt unverändert.
- **Tests:** positiv: Strecken mit bekannten Tempolimits. negativ: Abschnitt ohne Limitangabe, Rollstuhl-Stopp, Konfiguration unterhalb der Systemgrenze, Positionsdaten älter als `eta_max_data_age_sec`. konkurrierend (Sicherheit): Konfigurationswechsel während der Angebotsbildung – jede Prognose trägt genau eine Modell-/Konfigurationsversion.
- **Abhängigkeiten:** intern: WP-M4-61.
  - extern: WP-M1-21, WP-M1-19 (wirksame Konfiguration mit Systemgrenzen)
  - extern: WP-M2-17, WP-M2-21 (Angebotsbildung und Einfügeheuristik verwenden die Prognose für Zeitfenster) – Reihenfolgekonflikt (Zyklus), siehe Offener Punkt 44
  - extern: WP-M3-34 (Positionsdaten mit Standortalter für die laufende ETA (WP-M3-34))
- **Entscheidungen:** D-10 (Grundmodell nach Favorit; blockiert nicht), D-09 (Routendaten mit Geschwindigkeitsattributen; blockiert Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Unsicherheitsquantil als eigene Anforderung (ETA-018), Datenqualitätsstatus je ETA (ETA-022), ETA-Fehlerkennzahlen und Drift (ETA-019/020) – M8.

### WP-M4-63 – M4-Gate-Abnahmesuite

- **Ziel:** Alle Referenztests des Gates M4 laufen als ausführbare Szenarien mit Build, Evidenz und Entscheidung; jede der 53 Anforderungen hat einen grünen Test.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: Szenarien in `tests/acceptance` für PH-AT-012, PH-AT-013, PH-AT-015, PH-AT-019, PH-AT-021, AT-37, AT-39, AT-40, AT-41, AT-45, AT-47, AT-48, E2E-064, E2E-065, E2E-066, E2E-067, E2E-068, E2E-072, E2E-073, E2E-080, E2E-081, E2E-082, E2E-083, E2E-084 sowie COST-01, COST-02, COST-05, COST-07, COST-08 (Kap. 03 §7); Contract-Fälle „EUR-Rundungsrest“, „geschlossene Periode“, „Allokationssumme ≠ Basis“, „Manipulation tenant_id“, „unzulässige Fremd-ID“ (Kap. 05 §5.8).
  - Backend: Nachweisführung in `arbeitspakete.csv` (Spalten `detailvertrag`, `testfall`) und `e2e.csv` (Build, Evidenz, Entscheidung) ausschließlich aus Testläufen.
- **Detailverträge vorab:** Testdrehbücher je Referenztest mit Fixtures, Rollen, Mandanten und Sollwerten; dokumentierter Umgang mit den nur teilweise erfüllbaren Tests (Offene Punkte 1 bis 7).
- **Akzeptanzkriterien:**
  1. Jeder Gate-Test ist mit `source_key[]`, Build, Migrationsstand, Konfigurationsrevision, Seed, Rollen, Soll/Ist und Ergebnis dokumentiert (Kap. 08 §8.4).
  2. Alle 53 Anforderungen haben mindestens einen grünen positiven Test; Geld-, Sicherheits- und Datentrennungsregeln zusätzlich negative und konkurrierende Tests.
  3. Jede Invariante aus Kap. 04 §4.5, die M4 betrifft (Gegenereignis, Journal, eine Kostenwirkung, Umlage), hat einen Negativtest mit mindestens zwei parallelen Transaktionen.
  4. Für AT-41, AT-48, E2E-067 und den Tankkarten- und Restatement-Teil von PH-AT-012/PH-AT-015 ist die mit dem Auftraggeber vereinbarte Behandlung (Teilabnahme oder Verschiebung) dokumentiert.
  5. Kein offener Mangel der Klasse A.
  6. Die M4-Anteile von PH-AT-001, AT-49 und E2E-091 (Gate M1, dort für M4-Endpunkte offen vermerkt) sind über WP-M4-64 nachgelaufen und in `e2e.csv` mit Build und Evidenz ergänzt.
  7. Für jede Oberfläche UI-12 bis UI-15 und die Kennzahlenansicht liegt ein Barrierefreiheitsnachweis vor (Tastatur, Screenreader, 200 % Zoom, Reflow 320 CSS-Pixel; Kap. 06 §6.1).
- **Tests:** die Suite selbst; Lauf unter der Runtime-Rolle mit zwei Mandanten.
- **Abhängigkeiten:** intern: alle anderen WPs dieser Datei (WP-M4-01 bis WP-M4-62, WP-M4-64, WP-M4-65).
  - extern: WP-M0-22, WP-M0-12 (Akzeptanztest-Infrastruktur, CI)
  - extern: WP-M2-21, WP-M2-23 (lauffähige Buchung und Angebotsbildung für E2E-080 bis E2E-083)
  - extern: WP-M3-24, WP-M3-29 (lauffähige Fahrer-App und Fahrerereignisse für AT-45, E2E-064, E2E-082, E2E-083)
- **Entscheidungen:** D-06, D-07 (ohne Beschluss Abnahme nur gegen Test-Kontenrahmen; ERP-Export nicht abnehmbar), D-10.
- **Größe:** M
- **Nicht im Umfang:** Last-, Restore- und Penetrationstests (M5); AT-46, E2E-069 (Gate M7), E2E-070 und PH-AT-022 (Gate M8), auch wenn M4 deren Grundlage liefert.

### WP-M4-64 – Modulzugänge, RLS und Isolationsnachweis für alle M4-Tabellen und -Endpunkte

- **Ziel:** Jede in M4 neu angelegte oder erweiterte Tabelle ist nachweislich mandantengetrennt, nur vom Eigentümermodul beschreibbar und – wo fachlich gefordert – unveränderlich; alle M4-Endpunkte bestehen die Isolationsprüfung der Plattform.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Datenbank: Migrationsrahmen der Module `staff-time`, `documents-finance`, `analytics` (Tabelleneigentümer/Migrator getrennt von der Runtime-Rolle, Default-Rechte geschlossen); je Modul eigener Datenbankzugang mit ausdrücklichen DML-Rechten nur auf eigene Tabellen, für HR- und Finanzdaten zusätzlich Spalten-/Viewrechte (Kap. 04 §4.6); für jede M4-Tabelle (`roster_revision`, `time_event`, `absence_request`, `document_revision`, `extraction_run`, `extracted_field`, `approval_step`, `fuel_transaction`, `evidence_link`, `cost_category`, `accounting_mapping`, `allocation_activation`, `metric_definition_revision`, `aggregate_snapshot`, `dataset_watermark`, `eta_model_revision` sowie die in dieser Datei als Vorschlag benannten Tabellen für Uploadticket, Belegzuordnung, Plausibilitätshinweise, Kilometerstände, Kilometerabschnitte, Exportbatch, Fakten und Korrekturvermerke): Schlüssel `(tenant_id,id)`, zusammengesetzte FKs, RLS aktiviert und erzwungen mit Policy `tenant_isolation` (`USING` und `WITH CHECK` gegen `current_tenant()`), Indizes beginnend mit `tenant_id`; Trigger `reject_mutation()` für die append-only geführten Tabellen (`time_event`, veröffentlichte `roster_revision`, `document_revision`, `approval_step`, `evidence_link`, freigegebene `metric_definition_revision`, Kilometerstände, Korrekturvermerke).
  - Backend: Katalogtest in CI, der für jede Tabelle des Schemas mit `tenant_id` RLS, Policy, Eigentümermodul und Rechte der Runtime-Rolle prüft und bei einer neuen Tabelle ohne Eintrag scheitert; Architekturtest „Modul schreibt nur eigene Tabellen“ für die drei M4-Module.
  - API: Aufnahme aller M4-Pfade (`/v1/rosters`, `/v1/time-events`, `/v1/absences`, `/v1/expenses/receipts`, `/extract`, `/v1/expenses/{id}`, `/v1/cost-events`, `/v1/vehicles/{id}/total-cost`, `/v1/ledger/exports`, `/v1/analytics/metrics`) in den Isolations-Testharness aus M1 (fremde ID, manipulierte `tenant_id`, fehlender Tenantkontext, Listen/Suche/Export).
  - Adapter/Worker: Tenantkontext je Transaktion in `workers/documents` und in den Projektionsworkern von `analytics`; Objektspeicher-Präfixe für Belegoriginale im Isolationstest.
- **Detailverträge vorab:** Tabellenliste M4 mit Eigentümermodul, Unveränderlichkeitsklasse und Rechtematrix der Modulzugänge; Liste der HR-/Finanzspalten mit Feldschutz; Zuordnung der M4-Endpunkte zu den Isolationsfällen von PH-AT-001.
- **Akzeptanzkriterien:**
  1. Ohne Tenantkontext liefert jede M4-Tabelle unter der Runtime-Rolle keine Zeile; INSERT mit fremder `tenant_id` scheitert an `WITH CHECK` (Invarianten „Kein Mandantenkontext → keine Zeile“ und „Keine lokale Cross-Tenant-Referenz“, Kap. 04 §4.5).
  2. Die Runtime-Rolle ist weder Tabelleneigentümer noch `BYPASSRLS`; der Zugang eines Moduls kann keine Tabelle eines anderen Moduls beschreiben (Kap. 04 §4.6, Kap. 01 §1.3).
  3. UPDATE und DELETE auf die append-only geführten Tabellen scheitern unter der Runtime-Rolle.
  4. Ein Fremdmandant erhält auf Arbeitszeit, Abwesenheit, Belegoriginal, Kostenereignis, Export und Kennzahl 404 bzw. leere Listen; der Versuch ist als Sicherheitsereignis protokolliert (AT-49, E2E-091, PH-AT-001 – M4-Anteil).
  5. Der Katalogtest schlägt fehl, wenn eine M4-Migration eine Tabelle ohne RLS, ohne Policy oder ohne Eigentümereintrag anlegt.
  6. Migrationen sind versioniert, folgen expand/contract und laufen auf leerer Datenbank sowie auf dem Stand nach M3 mit Testdaten beider Mandanten; Backfills sind fortsetzbar, neue Pflichtspalten werden erst nach dem Backfill validiert (Kap. 04 §4.7).
- **Tests:** positiv: Migration auf leerer Datenbank und auf M3-Stand; Katalogtest grün. negativ: Zugriff ohne Tenantkontext, fremde ID, manipulierte `tenant_id` (Contract-Fälle Kap. 05 §5.8), modulfremder Schreibversuch, UPDATE auf append-only-Tabelle. konkurrierend (Datentrennung): zwei Mandanten parallel über denselben Verbindungspool mit Transaktionsabbruch und Poolwiederverwendung – kein Kontextübertrag (Kap. 04 §4.6). Besteht den M4-Anteil von PH-AT-001, AT-49 und E2E-091 (Gate M1, dort für M4-Endpunkte offen).
- **Abhängigkeiten:** intern: WP-M4-01.
  - extern: WP-M0-16, WP-M0-17, WP-M0-18 (Kernschema als ausgeführte Migration; Testharness mit Runtime-Rolle)
  - extern: WP-M1-01, WP-M1-11, WP-M1-31, WP-M1-12 (Tenantkontext, RLS-Laufzeitbasis und Isolations-Testharness (WP-M1-01); Isolation außerhalb der Datenbank (WP-M1-11); Feldschutz (WP-M1-31); Sicherheitsereignisse (WP-M1-12))
- **Entscheidungen:** D-02 (Isolationsstufe; RLS-Default, blockiert nicht den Bau).
- **Größe:** M
- **Nicht im Umfang:** fachliche Spalten und Regeln der einzelnen Tabellen (jeweiliges Fach-WP); Aufbewahrungs- und Löschläufe (M1/M8, D-11); Penetrationstest (M5).

### WP-M4-65 – Übernahme der Schichtzuweisungen aus M3 in den Dienstplan

- **Ziel:** `staff_shift` hat genau einen fachlich führenden Schreibpfad: Die in M3 über die Leitstelle angelegten Schichtzuweisungen sind in Dienstplanrevisionen überführt, und die Zuweisung der Leitstelle läuft über die Befehle des Moduls `staff-time`.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: Modul-API des Moduls `staff-time` für die Schichtzuweisung der Leitstelle (ersetzt den direkten Schreibpfad aus WP-M3-07); Regel, dass eine Zuweisung außerhalb des Planer-Dashboards als neue veröffentlichte Revision mit Konfliktprüfung (WP-M4-07) und Audit entsteht.
  - Datenbank: Datenmigration bestehender `staff_shift`-Zeilen in je eine veröffentlichte `roster_revision` je Depot und Zeitraum (Befüllung von `staff_shift.roster_revision`, Depot-/Betriebsbezug); Abgleich des in M3 eingeführten Überlappungsverbots je Fahrer und Fahrzeug mit dem Entwurfsmodell (Geltung nur für verbindliche Schichten; Entwurfskonflikte bleiben speicherbar und werden als Konflikt gemeldet – Offener Punkt 38); `resource_release.shift_id` bleibt gültig.
  - Adapter/Worker: Ablösung der Schicht-Fixture bzw. des Schicht-Stubs in M2 (WP-M2-02, WP-M2-15) und M3 durch den Leseport „Schichtbesetzung“ aus WP-M4-17.
- **Detailverträge vorab:** Migrationsabbildung Altzeile → Revision; Zustandswerte `staff_shift.state` gemeinsam mit M3; Verhalten des M3-Zuweisungsdialogs nach der Umstellung; Entscheidung zu Offener Punkt 38.
- **Akzeptanzkriterien:**
  1. Nach der Migration gehört jede vorhandene Schicht zu genau einer veröffentlichten Dienstplanrevision; keine `resource_release` verliert ihren Schichtbezug.
  2. Eine über die Leitstelle angelegte Zuweisung erscheint im Dienstplan (UI-12) als veröffentlichte Schicht mit Revision, Autor und Zeit und durchläuft dieselbe Konfliktprüfung wie eine Planeraktion.
  3. Zwei überlappende verbindliche Schichten derselben Person oder desselben Fahrzeugs sind weiterhin ausgeschlossen (Zusage aus WP-M3-07); im Entwurf ist die Überlappung speicherbar und als Konflikt sichtbar.
  4. Kein anderes Modul als `staff-time` hat nach der Umstellung DML-Rechte auf `staff_shift` (Architekturtest, WP-M4-64).
- **Tests:** positiv: Migration eines M3-Fixturebestands; Zuweisung über die Leitstelle. negativ: direkter Schreibversuch eines anderen Moduls auf `staff_shift`; Zuweisung in fremdem Depot. konkurrierend (Kapazität/Sicherheit): Leitstelle und Planer weisen dieselbe Person parallel überlappend zu – höchstens eine verbindliche Schicht entsteht; Migration parallel zu laufendem Hold lässt bestehende Freigaben gültig.
- **Abhängigkeiten:** intern: WP-M4-07, WP-M4-14, WP-M4-16.
  - extern: WP-M2-15 (Kandidatenfilter liest Schichten nur über den Leseport (WP-M2-15)) – Reihenfolgekonflikt (Zyklus), siehe Offener Punkt 44
  - extern: WP-M3-07, WP-M3-08 (Schichtzuweisung mit Überlappungsverbot und Zuweisungsdialog (WP-M3-07); `resource_release` (WP-M3-08)) – Reihenfolgekonflikt (Zyklus), siehe Offener Punkt 44
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** Schichttausch und Schichtangebote (SCH-021 bis SCH-024, M7); Fahrzeugwechsel in laufender Schicht (M3).

## Offene Punkte

1. **AT-41 (Urlaub gegen Schicht) ist in M4 nur teilweise erfüllbar.** Der Test verlangt Urlaubsgenehmigung, offene Ersatzschicht und Änderungsworkflow. Abwesenheitsantrag (`LH-6.24-TIM-008`), Abwesenheitsanzeige (`LH-6.23-SCH-013`) und Ersatzbedarf (`LH-6.23-SCH-020`) sind P2 (M7). M4 liefert nur die genehmigte Abwesenheit als Konfliktquelle für `LH-6.23-SCH-006` (WP-M4-06, WP-M4-07). Zu klären: Teilabnahme in M4 oder Verschiebung von AT-41 ins Gate M7.
2. **AT-48 (Zeitkorrektur) hat in M4 keine Anforderung.** Korrekturantrag und Genehmigung stehen in `LH-6.24-TIM-007` und `LH-6.24-TIM-014` (P2, M7); `time_correction` und `/v1/time-corrections` werden in M4 nicht gebaut. Entweder AT-48 aus dem Gate M4 nehmen oder TIM-007 per D-12-Beschluss vorziehen.
3. **Regelmatrix für AT-40 fehlt.** AT-40 verlangt „Warnung/Sperre gemäß freigegebener Regelmatrix“. `LH-6.24-TIM-006` verlangt nur einen Warnhinweis ohne vollständige Automatisierung der gesetzlichen Prüfung; der Katalog kennt `min_rest_hours` und `max_daily_work_minutes`, aber keinen Pausenparameter. Regeln je Beschäftigungsmodell (`LH-6.23-SCH-017`) und die Trennung Sperre/Hinweis (`LH-6.23-SCH-035`) sind P2. Offen: wer die Matrix liefert und ob eine Ruhezeitverletzung in P1 die Publikation sperrt.
4. **E2E-067 (E-Rechnung XML/PDF-Differenz) steht im Gate M4, die Anforderungen sind P2.** `LH-I.1-DOC-014` und `LH-I.1-DOC-015` (XRechnung/ZUGFeRD, XML führend) liegen in M8, ebenso der KoSIT-Validator. PH-AT-019 enthält denselben Teilaspekt. In M4 ist kein E-Rechnungsimport geplant; Verschiebung des Tests ins Gate M8 oder Vorziehen per D-12 ist zu entscheiden.
5. **Tankkartenimport für E2E-065 und PH-AT-012.** Beide Tests setzen eine importierte Tankkartentransaktion voraus; der Import steht in `LH-I.1-DOC-019` (P2, SOLL, M8). WP-M4-32 bildet die Transaktion nur minimal als zweite Evidenz über den Importkanal ab (`LH-I.1-DOC-009`, `LH-I.1-DOC-006`). Zu bestätigen, dass diese Minimalform für das Gate genügt. PH-AT-012 nennt außerdem „Rechnung“ als dritte Evidenz und verweist auf E2E-070 (Gate M8).
6. **Periodenabschluss und Gegenbeleg sind P2-Anforderungen, aber Gate-Inhalt.** E2E-073 und PH-AT-015 verlangen geschlossene Periode und Korrekturbeleg; `LH-I.2-FCO-023` und `LH-I.1-DOC-030` sind P2 (M8). WP-M4-36 baut den technischen Ausschnitt als Enabler gemäß Meilensteinplan. Die „Restatement-Ansicht“ und der Plan/Ist-Vergleich aus PH-AT-015 (Budget, `LH-I.2-FCO-017`) sind in M4 nicht enthalten. Für den Periodenabschluss nennt Kap. 05 §5.7 keinen Endpunkt.
7. **ERP-Export ohne P1-Anforderung und ohne Zielformat.** `LH-I.1-DOC-024` ist P2; der Meilensteinplan verlangt den Export dennoch in M4, und `LH-I.1-DOC-010` nennt den „Exportstatus“. Ziel-ERP, Format und Kontenrahmen sind offen (D-06). WP-M4-37 baut gegen ein neutrales Testformat; der Export ist ohne D-06 nicht abnehmbar.
8. **`staff_shift` im Kern-DDL passt nicht zu den P1-Anforderungen.** `driver_ref` und `vehicle_id` sind `NOT NULL`; `LH-6.23-SCH-008` verlangt offene Schichten ohne Person und Fahrzeug. Es fehlen Depot, Betrieb, Funktion, Qualifikationsanforderung, Personalbedarf und Pausenplan (`LH-6.23-SCH-002`). Der Kalendertag einer Mitternachtsschicht ist nicht definiert. Die Tabelle für einzelne Schichtstände je Revision ist nicht benannt (`LH-6.23-SCH-010`).
9. **Wirkung von `shift_publish_notice_hours` unklar.** Der Katalog nennt nur „Veröffentlichungsvorlauf“; ob eine Unterschreitung warnt oder sperrt, ist nicht festgelegt (`LH-6.23-SCH-005`). Ebenso offen: Inhalt der „korrekten Meldung“ an den Fahrer in AT-37, da gezielte Mitteilungen erst `LH-6.23-SCH-014` (P2) sind.
10. **Eigentum an `resource_release` und Verknüpfungsmechanik.** `resource_release` verweist auf `shift_id`, `qualification_revision` und `inspection_ref`; Kap. 01 §1.3 ordnet Shift dem Modul Staff/Time und Release dem Modul Fleet/Safety zu. Für `LH-6.23-SCH-007` ist nicht festgelegt, welches Modul die Tabelle schreibt und wie der „geplante Betrieb“ (Dienst) mit der Schicht verknüpft wird.
11. **OCR-Engine, Unsicherheitsschwelle und Datenschutz.** Das Pflichtenheft nennt keine OCR-Lösung und keinen Parameter für „wesentliche Unsicherheit“ (`LH-I.1-DOC-002`, `LH-I.1-DOC-003`); nur `receipt_ocr_enabled` existiert. Bei externem OCR-Dienst fehlt die Aussage zur zulässigen Verarbeitung von Belegbildern.
12. **Kanäle „E-Mail-Eingang“ und „Rechnungsimport“ sind technisch nicht beschrieben.** `LH-I.1-DOC-006` verlangt beide in P1; Kap. 05 §5.7 nennt dafür keinen Endpunkt, die Adapterliste des Umsetzungsplans keinen Mail-Adapter. Format des Rechnungsimports ohne E-Rechnung (P2) ist offen; `invoice_email_inbound` verlangt eine „verifizierte Domäne“ ohne Verfahren.
13. **Uploadgrenzen und Bildqualität nicht beziffert.** Kap. 05 §5.7 verlangt ein Uploadticket mit Größe/MIME/Hash, nennt aber keine Werte; „ausreichende Bildqualität“ in `LH-I.1-DOC-001` ist nicht messbar definiert; das Prüfintervall der Integritätsprüfung (`LH-I.1-DOC-008`) fehlt.
14. **`cost_object.kind` weicht zwischen DDL und Kap. 03 ab.** Kern-DDL: `VEHICLE, SERVICE, RUN, LEG, PROJECT, COST_CENTER, UNALLOCATED`; Kap. 03 §2: `VEHICLE/SERVICE/SHIFT/JOURNEY_LEG/COST_CENTER/PROJECT/CONTRACT`. Betrifft `LH-I.1-DOC-004` und `LH-I.1-DOC-011`. `expense_document` hat außerdem weder Herkunftskanal noch Belegart noch Kostenobjektbezug (`LH-I.1-DOC-006`, `LH-I.1-DOC-004`).
15. **Eindeutigkeit von `economic_event`.** Kap. 03 §2 verlangt Eindeutigkeit je Einheit/Quellsystem/Quell-ID/Revision/Ereignisart; das Kern-DDL enthält statt der Revision das Szenario. Kap. 04 §4.5 nennt „Ledgerkonto-/Periodenüberlappungsregeln“ als noch zu ergänzen. Betrifft die einmalige Kostenwirkung (`LH-I.1-DOC-009`).
16. **Vergleichsbasis der Tankplausibilität.** `fuel_price_alert_pct` bezieht sich laut Katalog auf „historische Preisvergleichsdaten“, deren Quelle nicht genannt ist; `fuel_use_alert_pct` verlangt „Stichprobengröße beachten“ ohne Wert. Die Prüfung der Tankkarten-ID (`LH-I.1-DOC-012`) setzt `fuel_card_mapping` voraus, der Kartenimport ist aber P2.
17. **„Budgetfreigabe“ ohne Budgetmodell in P1.** `LH-I.1-DOC-010` trennt eine Budgetfreigabe; Budgets (`LH-I.2-FCO-017`) und Freigabestufen nach Kostenstelle/Kostenart/Vertretung (`LH-I.1-DOC-018`) sind P2. Offen, was der Schritt in P1 prüft und welche Rollen die vier Schritte tragen.
18. **Reihenfolge Freigabe und Aufteilung.** `LH-I.1-DOC-011` spricht von der Aufteilung eines „genehmigten“ Belegs und zugleich davon, dass Differenzen die Freigabe blockieren; Kap. 03 §3 Nr. 4 prüft den vollständigen Split vor dem Posting, Kap. 05 §5.5 allokiert eine „freigegebene Kostenbasis“. Die Reihenfolge und die Zulässigkeit einer späteren Neuaufteilung sind festzulegen.
19. **„Vier Kostenklassen“ gegen zwei Achsen.** `LH-I.2-FCO-001` verlangt vier Klassen „getrennt und in Summe“; Kap. 03 §1 definiert fix/variabel und direkt/umgelegt als zwei Achsen, die nicht addiert werden dürfen. WP-M4-39 legt die Abnahme als Kreuztabelle aus; das ist mit dem Auftraggeber zu bestätigen.
20. **Umfang von `LH-I.2-FCO-002` in P1 und der Pfad `/v1/vehicles/{id}/total-cost`.** Strukturierte Verwaltung von Anschaffung/Leasing und Versicherung überschneidet sich mit `LH-I.2-FCO-006` bis `LH-I.2-FCO-008` (P2). Ob der Katalogpfad `/v1/vehicles/{id}/total-cost` bereits in P1 für die Monatsauswertung genutzt wird oder TCO vorbehalten bleibt, ist offen. Anschaffung darf laut Kap. 03 §1.1 nicht als Periodenaufwand zählen; ein Abschreibungsmodell fehlt in P1.
21. **Kein Datenmodell für Kilometerstände.** `LH-I.2-FCO-003` verlangt vier Quellen mit Herkunft; weder Kern-DDL noch Kap. 04 §4.4 nennen eine Tabelle. Die Werkstattquelle (`repair_order`) entsteht erst in M7/M8, die Telematikquelle hängt an D-09. Rangfolge und Toleranzen bei Konflikten sind nicht definiert.
22. **Kilometerkategorien und Betriebsstunde nicht modelliert.** Für Überführung und Werkstattfahrt (`LH-I.2-FCO-005`, `LH-I.6-KPI-013`) gibt es kein Objekt im Datenmodell; die Distanzquelle (Routing oder Messung) und die Definition der „Betriebsstunde“ (`LH-I.2-FCO-004`) fehlen. Die „dokumentierte Musterfahrt“ aus `LH-6.14-ANA-002` und die „dokumentierte Referenzberechnung“ aus `LH-I.2-FCO-004` liegen nicht vor und müssen in WP-M4-01 erstellt und freigegeben werden.
23. **Bildschirm für Kennzahlen fehlt im M4-Zuschnitt.** Der Meilensteinplan nennt für M4 nur UI-12 bis UI-15; Funnel, Ablehnungsstatistik und Live-Dashboard (`LH-I.6-KPI-005`, `LH-I.6-KPI-014`, `LH-6.14-ANA-003`) brauchen eine Oberfläche. UI-19 (Kennzahlen/Forecast/Simulation) ist keinem P1-Meilenstein zugeordnet. WP-M4-59 und WP-M4-60 bauen den Kennzahlenteil; die Zuordnung ist zu bestätigen.
24. **„P1-KPIs“ sind nicht benannt.** Kap. 01 §1.6 verlangt Definitionen für alle 48 Kennzahlen, der Meilensteinplan für M4 nur „die P1-KPIs“ und für M8 den vollständigen Katalog. Eine Liste existiert nicht; WP-M4-46 schlägt 21 Kennzahlen vor (`LH-I.6-KPI-010`).
25. **Rangfolge des primären Ablehnungsgrunds.** `LH-I.6-KPI-003` unterscheidet keine Kapazität, kein Fahrzeug, kein Fahrer, Schichtende, falsche Ausstattung; der KPI-Katalog K.1 kennt nur Kapazität, Personal und Ausstattung. Welche Ursache primär ist, wenn mehrere zutreffen, und wie „keine Kapazität“ von „kein Fahrzeug“ abgegrenzt wird, ist nicht geregelt (`LH-I.6-KPI-002`).
26. **Nicht erzeugbare Gründe in P1.** „Partnerausfall“ (`LH-I.6-KPI-004`) kann ohne Partner (M6) nicht real auftreten; der Code wird nur katalogisiert und per Testinjektion geprüft.
27. **Funnelereignisse nicht definiert.** „Angebot angezeigt aber nicht gebucht“ (`LH-I.6-KPI-005`) braucht ein explizites Anzeigeereignis oder die Regel „Angebot abgelaufen ohne Hold“; „abgeschlossene Buchung“ (`LH-6.14-ANA-001`) ist nicht gegen „durchgeführte Fahrt“ abgegrenzt.
28. **„Angekündigte“ Zeit ohne Quelle.** `LH-I.6-KPI-006` nennt fünf Zeitarten; für die angekündigte Zeit gibt es weder ein Feld im Kern-DDL noch eine Definition (vermutlich die dem Fahrgast mitgeteilte Zeit aus einer Benachrichtigung). `booking.promise_snapshot` ist untypisiertes JSON.
29. **Ebene des Pünktlichkeitsgrenzwerts und „Vergleichsstichtag“.** `LH-I.6-KPI-007` verlangt einen gebietsspezifischen Grenzwert und einen Vergleichsstichtag; `on_time_pickup_tolerance_min` ist im Katalog auf Ebene Dienst definiert. Die Bedeutung des Vergleichsstichtags (Zeitpunkt der Zusage oder Berichtsdatum) ist festzulegen. E2E-083 verlangt zusätzlich eine „Ursache“ der Ankunftsverspätung; die Ursachenklassifikation ist `LH-I.6-KPI-016` (P2).
30. **Katalog der Verursachungskategorien fehlt.** `LH-I.6-KPI-009` verlangt Ursache und Verursachungskategorie mit Korrekturvermerk; weder Werte noch berechtigte Rollen für Korrekturen sind definiert.
31. **Zuordnung der Auslastungskennzahlen.** `LH-I.6-KPI-012` nennt eine „Kapazitätszeit-“ und eine „Fahrgastkilometerkennzahl“; der Katalog führt `Sitzplatz-Zeit-Auslastung` und `Kapazitätsauslastung distanzgewichtet` (Platz-km). Ob Letztere gemeint ist und wie Rollstuhlplätze in den Nenner eingehen, ist zu bestätigen.
32. **Aktualisierungsfrist des Live-Dashboards.** `LH-I.6-KPI-014` verweist auf eine „definierte Aktualisierungsfrist“, die weder im Pflichtenheft noch im Parameterkatalog steht; „kritische Verspätung“ und „offene Anfrage“ sind nicht definiert.
33. **Kleingruppenschutz und Dimension „Mandant“.** PH-AT-021 verlangt den Schutz kleiner Gruppen auch gegen Differenzbildung; die zugehörige Anforderung GOV-012 ist P2. WP-M4-58 baut den Schutz mit `kpi_aggregation_min_n` trotzdem ein; die Schwelle ist nicht freigegeben. Der Filter „Mandant“ in `LH-I.6-KPI-011` ist innerhalb eines Mandantenkontexts ohne Föderation (M6) wirkungslos; „Betreiber“ ist im Einbetreiber-Pilot einwertig.
34. **Verortung und Parameter des ETA-Grundmodells.** Kap. 01 §1.3 nennt kein Modul für ETA; die Routingzeit entsteht im Routingadapter von M2. Offen: Modulzuordnung, Schnittstelle zur Disposition und ob M2 bis zur Lieferung von WP-M4-61/62 mit reiner Routingzeit arbeitet (`LH-I.3-ETA-001`). Höhe und Herleitung der „realistischen Sicherheitsreserve“ sowie der Standardwert von `eta_model_quantile` fehlen (`LH-I.3-ETA-002`). D-10 und D-09 sind nicht beschlossen.
35. **Tests außerhalb des M4-Gates, deren Anforderungen in M4 liegen.** AT-46 (Offline-Zeiterfassung) und E2E-069 (Offline-Tankbeleg) stehen im Gate M7, E2E-070 (Verbrauchsabweichung) im Gate M8; die zugehörigen Anforderungen `LH-6.24-TIM-003`, `LH-I.1-DOC-013` und `LH-I.1-DOC-012` sind P1 und werden in M4 geliefert. Vorschlag: diese Tests ins Gate M4 vorziehen.
36. **Endpunkt für Zeitstempel.** Der Katalog nennt `/v1/time-events`, `openapi.yaml` beschreibt `/v1/driver/events` nur für Ein-/Ausstieg und No-Show. Ob Stempel der Fahrer-App über `/v1/time-events` oder `/v1/driver/events` laufen und welche Ergebniswerte bei Konflikten gelten, ist festzulegen (`LH-6.24-TIM-002`, `LH-6.24-TIM-003`).
37. **E2E-072 nennt „Partnerunternehmen“.** Die Kostenteilung zwischen zwei Unternehmen berührt Rechtsträgergrenzen; Kap. 05 §5.5 schließt Allokation über Rechtsträgergrenzen per Schreibzugriff aus und verlangt Intercompany-Belege (M6/M8, E2E-094). WP-M4-35 erfüllt E2E-072 nur für zwei Kostenträger innerhalb eines Rechtsträgers (`LH-I.1-DOC-011`); die Auslegung ist zu bestätigen.
38. **Doppelte Pflege von `staff_shift` durch M3 und M4.** WP-M3-07 (`LH-6.8-FLT-003`) legt Schichtzuweisungen mit einem harten Überlappungsverbot je Fahrer und Fahrzeug an und verweist für `roster_revision` auf M4. `LH-6.23-SCH-006` verlangt, dass Konflikte „vor Veröffentlichung erkennbar“ sind, also im Entwurf speicherbar bleiben; `LH-6.23-SCH-004` trennt Entwurf und veröffentlichten Plan. Festzulegen: Geltung des Überlappungsverbots nur für verbindliche Schichten, führendes Modul für `staff_shift` (Kap. 01 §1.3: Staff/Time) und Zeitpunkt der Umstellung des M3-Dialogs (WP-M4-65).
39. **Wirkung von `expense_claim_limit` und `fuel_receipt_required` in P1.** Der Katalog nennt nur „Ausgabenlimit; Ausnahmen begründet und freigabepflichtig“ bzw. „Tankbeleg verpflichtend; Fahrerfreigabe getrennt von Finanzprüfung“. Welche P1-Anforderung die Parameter auslöst und ob eine Überschreitung sperrt oder eine zusätzliche Freigabestufe erzeugt, ist nicht geregelt (`LH-I.1-DOC-010`, `LH-I.1-DOC-005`).
40. **Keine Katalogkennzahl für Energiekosten.** `LH-I.2-FCO-004` verlangt Energiekosten pro 100 km bzw. je Betriebsstunde; der Katalog K.3 enthält nur die Verbrauchskennzahlen `Kraftstoff L/100 km` und `Strom kWh/100 km`. WP-M4-43 registriert zwei neue Definitionen; Name, Formel (netto oder Kostenbasis inkl. nicht abzugsfähiger Steuer) und Aufnahme in den Katalog sind zu bestätigen.
41. **Umfang der „laufenden ETA“ aus M4.** M2 und M3 erwarten aus M4 das ETA-Grundmodell als Quelle laufender ETA-Werte (Fahrgastanzeige, Benachrichtigung, Leitstelle). `LH-I.3-ETA-001` und `LH-I.3-ETA-002` verlangen nur Referenzfahrzeit und regelkonforme Prognose; Neuberechnung aus Live-Positionen, Aktualisierungsrhythmus und Datenqualitätsstatus (ETA-022, P2) sind nicht als P1-Anforderung beschrieben. WP-M4-62 liefert einen Leseport mit Datenstand und Fallback; der darüber hinausgehende Umfang ist festzulegen.
42. **Negative Korrektur im Journal.** PH-AT-015 erwartet eine „Oktoberkorrektur −20,00 EUR“; das Kern-DDL erlaubt in `journal_line` nur `amount_minor > 0` mit Seite `D`/`C`. Die Korrektur ist daher als Gegenbuchung mit getauschter Seite abzubilden; die Darstellung als negativer Betrag in Bericht und Export ist im Detailvertrag festzulegen (betrifft `LH-I.2-FCO-023` und `LH-I.1-DOC-030`, beide P2; in M4 nur Enabler WP-M4-36).
43. **Zusage `ImpactProvider` für Schichten.** WP-M1-23 erwartet aus M4 einen Provider, der betroffene Schichten für die Wirkungsanalyse von Konfigurationsänderungen meldet; welche Parameter für Schichten impact-pflichtig sind (z. B. `min_rest_hours`, `shift_publish_notice_hours`, Betriebszeiten eines Dienstes), ist im Pflichtenheft nicht aufgelistet (Anforderung `LH-I.7-CFG-007` liegt in M1; in M4 betroffen sind `LH-6.23-SCH-005` und `LH-6.23-SCH-007`).
44. **Reihenfolgekonflikt: zyklische Verweise auf die Parallelmeilensteine M2/M3.** Kein WP dieser Datei hängt von einem späteren Meilenstein (M5 ff.) ab. Folgende externe Verweise zeigen aber auf M2-/M3-WPs, die ihrerseits (direkt oder über ihre Vorgänger laut Übersichtstabellen bzw. extern-Zeilen von M2 und M3) auf das verweisende M4-WP angewiesen sind: WP-M4-02 → WP-M2-23, WP-M3-29, WP-M3-34 (Rückweg über WP-M3-07 → WP-M4-04 → WP-M4-03 → WP-M4-02 bzw. WP-M3-26 → WP-M4-61 → WP-M4-02); WP-M4-14 → WP-M3-25 (WP-M3-25 → WP-M3-07 → WP-M4-14); WP-M4-17 → WP-M2-15, WP-M2-22 (WP-M2-15 nennt WP-M4-17 als Ablösung der Schicht-Fixture); WP-M4-62 → WP-M2-17, WP-M2-21 (Verbraucher der Prognose; WP-M2-34 und WP-M2-43 hängen von WP-M4-62 ab); WP-M4-65 → WP-M2-15, WP-M3-07, WP-M3-08 (WP-M3-07 führt WP-M4-04, WP-M4-13, WP-M4-14 und WP-M4-65 selbst als Vorgänger; WP-M3-08 hängt von WP-M3-07 ab). Fachlich sind dies bei WP-M4-02 spätere Ablösungen der Stubs durch die echten Leseports, bei WP-M4-14, WP-M4-17 und WP-M4-62 nachgelagerte Verbraucher in M2/M3 und keine Startvoraussetzungen. Bei WP-M4-65 ist die Abhängigkeit von WP-M3-07 dagegen fachlich echt (die Übernahme setzt die M3-Zuweisungen voraus); hier ist die Gegenrichtung in M3 (WP-M3-07 → WP-M4-04/13/14/65) als spätere Integration statt als Vorgänger zu führen. Diese Verweise sind in der Übersichtstabelle („hängt ab von“) nicht eingetragen; alle übrigen Verweise auf M2/M3 sind gegen die Übersichtstabellen von M2 und M3 zyklusfrei und eingetragen. Zu klären: Bereinigung der Vorgängerliste von WP-M3-07 und Umwandlung der übrigen Paare in Integrationsschritte oder ein eigenes Integrations-WP nach Vorliegen beider Seiten (vgl. M2 Offener Punkt 50 und den Reihenfolgekonflikt-Eintrag in M3).
