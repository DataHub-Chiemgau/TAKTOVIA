# M8d – Konfiguration und Automatisierung

- **Meilenstein:** M8 – Finanzen, Controlling, ETA, Governance, Strom „Daten und Steuerung“ (setzt M5 voraus; Nachbareinheiten M8a Belege/Finanzen, M8b ETA/Governance, M8c Analyse/KPI, M8e Plattform/Sicherheit)
- **Phase:** P2 (24 MUSS, 3 SOLL; Phase und Priorität jeder Anforderung unverändert)
- **Anzahl Anforderungen:** 27 (CFG 15: `LH-I.7-CFG-008` bis `LH-I.7-CFG-022`; AUT 12: `LH-I.8-AUT-003` bis `LH-I.8-AUT-013`, `LH-I.8-AUT-015`)
- **Anzahl WPs:** 34 (27 mit Anforderungen, 7 Enabler)

**Ziel:** Nach M8d ist das Konfigurationsregister aus M1 zum vollständigen Selbstverwaltungswerkzeug ausgebaut: durchgängiger Lebenszyklus Entwurf → Vorschau → Freigabe → Veröffentlichung → Rücknahme, wählbarer Zweistufenmodus, Berechtigungsmatrix je Parameter, fachliche Regelgruppen (Dienst, Tarif, Kooperation, Finanzen, Belegarten, Schichtregeln, SLA, Benachrichtigung), Paketexport/-import, Vorschläge und ein auditierter Notfallmodus. Der Alarm- und Workflowkern aus M3 wird zum Alert-Center mit eingegrenzten, versionierten und per Dry-Run getesteten Automationsregeln, Deduplikation, Unterdrückung, Eskalation und operativen Empfehlungen erweitert. Keine Automation handelt ohne sichtbare Regelversion, keine Konfiguration verändert rückwirkend Zusagen, und jeder Automatismus ist abschaltbar mit dokumentiertem manuellem Ersatzablauf.

**Gate-Anteil M8d (Umsetzungsplan §4, Gate M8):** E2E-099 (führend). Beiträge zu E2E-070 (Prüfcase aus Tanklimit; führend M8a), E2E-102 (Konfiguration über Sommerzeitwechsel; führend M8c) und E2E-103 (versionierte SLA-Schwelle; führend M8c). Fortschreibung von PH-AT-018 (Bezug CFG-011/021) als Regressionsnachweis. PH-AT-022, E2E-074 bis E2E-079, E2E-105, E2E-106 gehören zu M8b, E2E-101 zu M8c.

**Lesehinweise:**
- Tabellen aus `kernschema.sql` und Kap. 04 §4.4 sind mit exaktem Namen genannt. Wo das Pflichtenheft keine Tabelle nennt, steht „(Arbeitsname)“; der endgültige Name entsteht im Detailvertrag (WP-M8d-01).
- Kap. 05 §5.7 kennt für diese Einheit nur `/v1/config/effective`, `/v1/config/revisions`, `/v1/config/revisions/{id}`, `/validate`, `/impact`, `/v1/config/revisions/{revision_id}/publish`, `/v1/operator/incidents`, `/v1/notifications`, `/v1/reports/jobs`. Alle weiteren Pfade sind „Detailvertrag“ und in den Offenen Punkten aufgeführt.
- Kap. 06 §6.9 enthält keine eigene UI-ID für Alert-Center, Regelpflege oder Konfigurationspakete. Zuordnung dieses Plans: Konfiguration → UI-17 („Gebiete/Stops/Tarife“, Aktion „Revision freigeben“; der Konfigurationseditor selbst ist nur in Kap. 06 §6.8 beschrieben), Kooperation → UI-18, Alarme → UI-07 (Arbeitsliste), Finanzen → UI-14/UI-15, Dienstplan → UI-12, Kennzahlen → UI-19, Audit → UI-20 (siehe OP-02).
- WP-IDs sind stabil, weil Nachbareinheiten sie referenzieren (M8a nennt WP-M8d-12, WP-M8d-13, WP-M8d-22, WP-M8d-23, WP-M8d-30). Die nach der Prüfung ergänzten Enabler WP-M8d-33 (Prüfcase-Kern) und WP-M8d-34 (zeitgesteuerter Ausführungskern) stehen deshalb am Ende, obwohl sie fachlich vor ihren Abnehmern liegen; die Reihenfolge ergibt sich aus der Spalte „hängt ab von“.
- Für jede Oberfläche dieser Einheit gelten ohne Wiederholung je WP die Querschnittsregeln aus Kap. 06: Zustände leer, Ladefehler, fehlendes Recht und veralteter Stand (412 ohne stilles Überschreiben), vollständige Tastaturbedienung, sichtbarer Fokus, WCAG 2.2 AA als Abnahmeziel, Status nie nur als Farbe, Karte immer mit gleichwertiger Liste. Die Prüfung erfolgt je WP im Oberflächentest und gesammelt in WP-M8d-32.
- Planungsdefaults des Pflichtenhefts sind als solche gekennzeichnet; Beispielwerte aus Abnahmetexten (z. B. „vier Ablehnungen“, „30 Tage“) sind Testfixtures, keine Produktionswerte.

## Übersicht

| WP-ID | Titel | Anforderungen (Anzahl) | Größe | hängt ab von |
|---|---|---:|---|---|
| WP-M8d-01 | Detailverträge und Zustandsautomaten M8d | 0 (Enabler) | M | WP-M0-31, WP-M1-03, WP-M1-17 |
| WP-M8d-02 | Migration der M8d-Tabellen mit RLS und Invarianten | 0 (Enabler) | M | WP-M8d-01, WP-M1-01, WP-M1-19, WP-M1-20, WP-M2-32, WP-M2-33, WP-M3-52, WP-M8e-15 |
| WP-M8d-03 | Parameterschemas der in M8d verwendeten Katalogparameter | 0 (Enabler) | M | WP-M8d-01, WP-M1-19, WP-M8b-01 |
| WP-M8d-04 | Referenzfixtures M8d | 0 (Enabler) | S | WP-M8d-02, WP-M8d-03, WP-M4-02, WP-M6b-03 |
| WP-M8d-05 | Konfigurationslebenszyklus: Entwurf, Vorschau, Freigabe, Veröffentlichung, Rücknahme | 1 | M | WP-M8d-02, WP-M1-20, WP-M1-22, WP-M1-23, WP-M6b-17, WP-M8b-28 |
| WP-M8d-06 | Zweistufenmodus und Freigabeschwellen je Mandant | 1 | M | WP-M8d-05, WP-M1-07, WP-M1-08, WP-M1-12, WP-M1-22 |
| WP-M8d-07 | Berechtigungsmatrix der Parameter | 1 | M | WP-M8d-03, WP-M8d-05, WP-M1-06, WP-M1-07, WP-M1-08, WP-M1-19, WP-M6b-16, WP-M6b-19 |
| WP-M8d-08 | Zeitzone, Feiertagskalender und überlappende Zeiträume | 1 | M | WP-M8d-02, WP-M8d-05, WP-M1-09, WP-M4-03, WP-M4-10, WP-M8c-33 |
| WP-M8d-09 | Dienstspezifische Parameter für Reserve, Rollstuhlplätze, Vorlauf und Pooling | 1 | M | WP-M8d-03, WP-M8d-05, WP-M1-21, WP-M1-25, WP-M2-11, WP-M2-13, WP-M2-21, WP-M2-22, WP-M2-23, WP-M3-04 |
| WP-M8d-10 | Preis-, Rabatt- und Erstattungsregeln mit Gültigkeit und Ausnahmetagen | 1 | M | WP-M8d-05, WP-M8d-08, WP-M2-07, WP-M2-08, WP-M2-26, WP-M6c-06, WP-M6c-07, WP-M6c-09, WP-M6c-14 |
| WP-M8d-11 | Kooperationsregeln: Daten-, Service-, Tarif- und Zahlungsfreigaben | 1 | M | WP-M8d-03, WP-M8d-05, WP-M6a-04, WP-M6b-04, WP-M6b-05, WP-M6b-08, WP-M6c-07 |
| WP-M8d-12 | Finanzschwellen durch Finanzrollen mit Prüfworkflow | 1 | M | WP-M8d-03, WP-M8d-07, WP-M8d-22, WP-M8d-33, WP-M4-31, WP-M4-34, WP-M8a-11, WP-M8a-34, WP-M8a-35, WP-M8a-36, WP-M8c-15, WP-M8c-22 |
| WP-M8d-13 | Belegfelder, Dokumentenpflichten und Freigabegrenzen je Belegart | 1 | M | WP-M8d-03, WP-M8d-05, WP-M4-22, WP-M4-30, WP-M4-34, WP-M4-38, WP-M7c-35, WP-M8a-04, WP-M8a-06, WP-M8a-11 |
| WP-M8d-14 | Rechtsraumvorlagen für Schichtplanungsregeln | 1 | M | WP-M8d-03, WP-M8d-05, WP-M4-07, WP-M4-13, WP-M4-14, WP-M7d-04, WP-M7d-20 |
| WP-M8d-15 | Versionierte SLA-Ziele und Ausnahmeregeln im Reporting | 1 | M | WP-M8d-03, WP-M8d-05, WP-M4-46, WP-M4-53, WP-M4-54, WP-M8c-22, WP-M8c-27, WP-M8c-33 |
| WP-M8d-16 | Benachrichtigungskanäle, Vorlagen, Empfängerrollen und Eskalationsketten | 1 | M | WP-M8d-03, WP-M8d-05, WP-M2-33, WP-M2-34, WP-M3-52, WP-M6b-29, WP-M7b-29, WP-M7b-34 |
| WP-M8d-17 | Konfigurationspaket: Export und Import zwischen Umgebungen | 1 | M | WP-M8d-05, WP-M8d-07, WP-M1-13, WP-M1-15, WP-M6b-20 |
| WP-M8d-18 | Plausibilitätsvorschläge für Stellgrößen | 1 | M | WP-M8d-05, WP-M8d-06, WP-M4-45, WP-M4-51, WP-M8c-11, WP-M8c-22 |
| WP-M8d-19 | Notfallmodus mit begrenzten, auditierten Soforteingriffen | 1 | M | WP-M8d-05, WP-M8d-07, WP-M8d-34, WP-M1-12, WP-M2-04, WP-M2-12, WP-M6b-19, WP-M7b-09 |
| WP-M8d-20 | Regel-Scope: Mandant, Gebiet, Dienst, Ereignistyp, Schweregrad, Zeitraum, Zuständigkeit | 1 | M | WP-M8d-02, WP-M1-09, WP-M1-14, WP-M3-52, WP-M3-53, WP-M3-55, WP-M7b-12, WP-M7b-16 |
| WP-M8d-21 | Regeländerung mit Dry-Run, Freigabe und Versionswechsel | 1 | M | WP-M8d-20, WP-M8d-05, WP-M8b-28 |
| WP-M8d-22 | Alert-Center: Bündelung nach Priorität, Quittieren, Delegieren, Erledigen | 1 | L | WP-M8d-02, WP-M8d-20, WP-M3-36, WP-M3-52, WP-M6a-15, WP-M7b-16, WP-M7d-17, WP-M8a-35 |
| WP-M8d-23 | Deduplikation, Unterdrückungsfenster und Eskalationsregel | 1 | M | WP-M8d-16, WP-M8d-20, WP-M8d-22, WP-M8d-34, WP-M3-52, WP-M7b-16 |
| WP-M8d-24 | Kritische technische Ausfälle sind nicht unterdrückbar | 1 | S | WP-M8d-16, WP-M8d-23, WP-M5-04 |
| WP-M8d-25 | Automatischer Prüfauftrag bei Tankung über Limit | 1 | M | WP-M8d-20, WP-M8d-22, WP-M8d-33, WP-M4-23, WP-M4-30, WP-M4-31, WP-M8a-06, WP-M8a-14, WP-M8a-36 |
| WP-M8d-26 | Kapazitätsempfehlung bei wiederholten Ablehnungen | 1 | M | WP-M8d-20, WP-M8d-22, WP-M2-11, WP-M2-13, WP-M4-48, WP-M4-49, WP-M4-51, WP-M7b-16, WP-M7d-23 |
| WP-M8d-27 | Erinnerungen für HU-, Werkstatt- und Leasingfristen | 1 | M | WP-M8d-20, WP-M8d-22, WP-M8d-34, WP-M3-03, WP-M7c-35, WP-M7c-50, WP-M8a-22, WP-M8a-24, WP-M8a-25 |
| WP-M8d-28 | Ersatzfahrzeugvorschläge mit Eignungsprüfung | 1 | M | WP-M8d-20, WP-M3-08, WP-M3-54, WP-M4-17, WP-M7c-38 |
| WP-M8d-29 | Kalender- und ereignisgesteuerte Regelausführung mit Nachholprotokoll | 1 | M | WP-M8d-08, WP-M8d-20, WP-M8d-34, WP-M1-14, WP-M1-15, WP-M4-37, WP-M8c-27, WP-M8c-29 |
| WP-M8d-30 | Finanzalarme und Liste ungeklärter Buchungen zum Monatsabschluss | 1 | M | WP-M8d-12, WP-M8d-22, WP-M8d-33, WP-M4-33, WP-M4-36, WP-M8a-14, WP-M8a-18, WP-M8a-34, WP-M8a-35, WP-M8c-22 |
| WP-M8d-31 | Deaktivierbare Automationen mit manuellem Ersatzablauf | 1 | M | WP-M8d-20, WP-M8d-21, WP-M3-45, WP-M3-51, WP-M5-26, WP-M7c-34, WP-M7c-36 |
| WP-M8d-32 | M8d-Gate-Abnahmesuite | 0 (Enabler) | M | WP-M8d-04 bis WP-M8d-31, WP-M8d-33, WP-M8d-34, WP-M8a-36, WP-M8a-38, WP-M8b-26, WP-M8c-33 |
| WP-M8d-33 | Prüfcase-Kern: Aggregat, Arbeitsliste, Zuweisung, Entscheidung | 0 (Enabler) | M | WP-M8d-02, WP-M1-07, WP-M1-08, WP-M1-12, WP-M1-13, WP-M4-22, WP-M4-34, WP-M4-38, WP-M8a-06, WP-M8a-11, WP-M8a-35 |
| WP-M8d-34 | Zeitgesteuerter Ausführungskern für Regeln, Fristen und Eskalationen | 0 (Enabler) | M | WP-M8d-02, WP-M1-01, WP-M1-13, WP-M1-14, WP-M1-15, WP-M5-04 |

## Arbeitspakete

### WP-M8d-01 – Detailverträge und Zustandsautomaten M8d
- **Ziel:** Vor Codebeginn liegen für alle 27 Anforderungen OpenAPI-Ergänzungen, DDL-Entwürfe, Zustandsautomaten und JSON-Schemas reviewt vor; `arbeitspakete.csv` trägt je MUSS-Zeile Detailvertrag und Testfall (Leitlinie 7, Kap. 08 §8.4).
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - API: OpenAPI-3.1-Ergänzungen für `/v1/config/revisions` (Lebenszyklusbefehle Freigabe, Rücknahme, Verwerfen), `/v1/config/revisions/{id}/validate`, `/v1/config/revisions/{id}/impact`, `/v1/config/effective` (Berechtigungs- und Herkunftsfelder); neue Pfadgruppen (Vorschlag dieses Plans, im Pflichtenheft nicht benannt): Konfigurationspakete, Notfalleingriffe, Kalender, Alarme (Arbeitsliste, Quittieren, Delegieren, Erledigen), Automationsregeln (Revision, Dry-Run, Aktivieren/Deaktivieren), Empfehlungen, Prüfcases.
  - Datenbank: DDL-Entwürfe für Erweiterungen von `config_revision`, `policy_schema`, `alert_policy_revision`, `business_alert`, `alert_acknowledgement` und die Arbeitsnamen-Tabellen aus WP-M8d-02.
  - Backend: Zustandsautomaten Konfigurationsrevision (Fortschreibung WP-M1-22), Konfigurationspaket, Notfalleingriff, Automationsregel-Revision, Alarm (offen → quittiert → delegiert → erledigt, eskaliert, unterdrückt), Prüfcase, Empfehlung/Vorschlag (offen → erklärt/abgelehnt/übernommen).
  - Ereignisse/Outbox: Ereigniskatalog M8d mit Schema-Version (u. a. `config.revision_published`, `config.revision_rolled_back` aus M1 unverändert; neue Typen für Notfalleingriff, Alarm-Lebenszyklus, Regelversion, Empfehlung).
  - Konfigurationsparameter: JSON-Schema-Vorlage für die in WP-M8d-03 gelisteten Parameter.
- **Detailverträge vorab:** dieses WP ist die Vertragslieferung; Reviewrollen Fach (Betrieb, Finanzen, Personal), Datenschutz, Architektur.
- **Akzeptanzkriterien:**
  1. Jede der 27 Anforderungen ist in `arbeitspakete.csv` mit Detailvertrag (Datei/Abschnitt) und mindestens einem positiven Testfall verknüpft.
  2. Die ergänzte OpenAPI besteht `validate_artifacts.py` (parsbar, `$ref` auflösbar, `operationId` eindeutig, Beispiele schemagültig).
  3. Jeder Zustandsautomat nennt Zustände, Übergänge, auslösende Rolle, Vorbedingung und Ereignis; verbotene Übergänge sind explizit gelistet.
  4. Alle Offenen Punkte dieser Datei sind entweder entschieden oder mit Verantwortlichem und Termin im Beschlussprotokoll (Kap. 07) eingetragen.
- **Tests:** positiv: Vertragsprüfung in CI grün. negativ: absichtlich defekte `$ref`/doppelte `operationId` bricht CI.
- **Abhängigkeiten:** intern: keine.
  - extern: WP-M1-03, WP-M1-17 (OpenAPI je Modul und Contract-Test-Pipeline, Zustandsautomaten-Rahmen)
  - extern: WP-M0-31 (Detailvertragsprozess und Pflege von `arbeitspakete.csv`)
- **Entscheidungen:** D-11 (Notbetriebsprozess, berechtigte Freigaberollen) – blockiert den Vertrag des Notfallmodus nicht, aber dessen Produktivschaltung; D-06/D-07 – nur Produktivwerte der Finanzschwellen.
- **Größe:** M
- **Nicht im Umfang:** Verträge der Nachbareinheiten M8a, M8b, M8c, M8e; Implementierung.

### WP-M8d-02 – Migration der M8d-Tabellen mit RLS und Invarianten
- **Ziel:** Alle für M8d benötigten Tabellen und Spalten existieren als versionierte Flyway-Migration mit Mandantenschlüssel, zusammengesetzten FKs, RLS und Revision; die Migration läuft unter der Migratorrolle, alle Tests unter der Runtime-Rolle.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Datenbank (bestehend, erweitert): `config_revision` (Wertebereich `scope_kind` erweitert um die in M8d benötigten Scope-Arten Rechtsraum, Belegart, Kostenstelle, Fahrzeugtyp, Alerttyp, Gebiet/Zeitfenster – Ebenen laut Spalte „Ebene“ in `kataloge.csv`; Abbildung nicht-UUID-Scopes auf das Pflichtfeld `scope_id uuid`, OP-32; Statuswerte für Vorschau/Rücknahme, Freigabemodus, Bezug auf Vorgängerrevision bei Rücknahme, Kennzeichen Notfallrevision), `policy_schema` (Änderungsberechtigungsebene, Kritikalität, Rechtsraumbezug), `alert_policy_revision` (Scope-Felder Gebiet, Dienst, Ereignistyp, Schweregrad, Zeitraum, Zuständigkeit; Unterdrückungsfenster; Eskalationsregel; Kennzeichen „nicht unterdrückbar“; Aktiv-/Deaktiviert-Status; Dry-Run-Nachweis), `business_alert` (Kategorie Fahrzeug/Kosten/Nachfrage/Personal/Schnittstelle, Priorität, Zustand, Zuständiger, Zähler deduplizierter Vorkommen, erstes/letztes Auftreten), `alert_acknowledgement` (Aktion quittiert/delegiert/erledigt, Akteur, Ziel, Begründung), `channel_template_revision` (Empfängerrolle, Eskalationsstufe).
  - Datenbank (neu, Arbeitsnamen): `config_package` (Export-/Importpaket mit Checksumme, Schema-/Policyversion), `emergency_intervention` (Notfalleingriff mit Grund, Berechtigtem, Befristung, Nachkontrolle), `operating_calendar`/`calendar_exception_day` (Feiertags-/Ausnahmetage je Rechtsraum/Region), `config_suggestion` (Vorschlag mit Datengrundlage und Entscheidung), `automation_execution` (geplante/ausgeführte/versäumte/nachgeholte Ausführung), `review_case` (Prüfcase mit Bezug zu Beleg/Kostenereignis), `capacity_recommendation` (Empfehlung als Szenario mit Auslöserdaten).
  - Datenbank (Invarianten, Kap. 04 §4.5): „Nicht überlappende aktive Tarif-/Policy-/Dienstversion“ für `alert_policy_revision` und Kalender; Dedupe-Unique `(policy,event,recipient)` aus Kap. 04 §4.4 auf `business_alert`, ergänzt um Fingerprint-Schlüssel (OP-12); Append-only für `alert_acknowledgement` und `emergency_intervention`.
  - Datenbank (RLS): Tenant-Policy für jede Tabelle; globale Zeilen (Plattformgrenzen, Rechtsraumvorlagen, nicht unterdrückbare Alarmregeln) liegen im Plattformmandanten (M1 OP-08; `config_revision.tenant_id` ist NOT NULL), sind für Mandanten nur lesbar und ausschließlich über den Plattformkontext schreibbar (OP-32).
  - Datenbank (Lebenszyklus): Aufbewahrungs-/Löschklasse je neuer Tabelle für den Löschjob aus M8e hinterlegt (OP-33); `emergency_intervention` und `alert_acknowledgement` bleiben bis Fristablauf append-only.
- **Detailverträge vorab:** DDL aus WP-M8d-01; Entscheidung je Arbeitsname-Tabelle, ob eigene Tabelle oder Scope-Art in `config_revision`; Indizes `(tenant_id,state,updated_at)` für Arbeitslisten (Kap. 04 §4.7).
- **Akzeptanzkriterien:**
  1. Migration läuft auf leerer Datenbank und auf dem Stand nach M7 (expand/contract, Testdaten zweier Mandanten) fehlerfrei.
  2. Ohne Mandantenkontext liefert jede M8d-Tabelle null Zeilen; Mandant A sieht keine Zeile von Mandant B.
  3. Jede lokale Fremdschlüsselbeziehung enthält `tenant_id`.
  4. `alert_acknowledgement` und `emergency_intervention` lassen unter der Runtime-Rolle weder UPDATE noch DELETE zu.
  5. Zwei aktive `alert_policy_revision` desselben Scopes mit überlappender Wirksamkeit sind nicht möglich.
  6. Ein Mandantenkontext kann Zeilen des Plattformmandanten (Rechtsraumvorlagen, systemische Regeln) lesen, aber weder ändern noch löschen; der Versuch scheitert in der Datenbank, nicht erst im Dienst.
  7. Bestehende `config_revision`-Zeilen aus M1 bis M7 behalten nach der Migration Revision, Wirksamkeit und `values_json` unverändert (Vorher-/Nachher-Checksumme).
- **Tests:** positiv: Migration vorwärts, Backfill fortsetzbar. negativ: Zugriff ohne Kontext, Cross-Tenant-FK, UPDATE auf Append-only. konkurrierend (Datentrennung/Sicherheit): zwei parallele Transaktionen aktivieren überlappende Regelrevisionen – genau eine erfolgreich; zwei parallele Inserts desselben Alarm-Dedupeschlüssels – genau eine Zeile.
- **Abhängigkeiten:** intern: WP-M8d-01.
  - extern: WP-M1-01, WP-M1-19, WP-M1-20 (RLS-Laufzeitbasis und Isolations-Testharness, `config_revision`/`policy_schema`)
  - extern: WP-M3-52 (`alert_policy_revision`, `business_alert`, `alert_acknowledgement` in der Grundform)
  - extern: WP-M2-32, WP-M2-33 (`channel_template_revision`, `notification`)
  - extern: WP-M8e-15 (Lösch- und Auskunftsjob mit Aufbewahrungsklassen und Legal Hold als Abnehmer der Löschklassen)
- **Entscheidungen:** D-02 – nur falls Datenbank je Mandant gewählt wird (Umbau der globalen Vorlagenzeilen); blockiert den Bau nicht.
- **Größe:** M
- **Nicht im Umfang:** Fachlogik; Tabellen der Nachbareinheiten (`tariff_revision`, `approval_step`, `roster_revision`, `metric_definition_revision` werden nur referenziert).

### WP-M8d-03 – Parameterschemas der in M8d verwendeten Katalogparameter
- **Ziel:** Alle in dieser Einheit fachlich verwendeten Parameter aus `kataloge.csv` besitzen ein freigegebenes JSON-Schema mit Typ, Einheit, Bereich, erlaubter Ebene, Sicherheitsklasse und Änderungsberechtigung; ein CI-Abgleich weist nach, welche der 80 Katalogparameter insgesamt ein Schema haben.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Konfigurationsparameter (Schemas, soweit nicht bereits durch M1 bis M7 geliefert): `min_capacity_reserve`, `min_booking_lead_min`, `max_booking_horizon_days`, `pooling_allowed`, `max_detour_pct`, `fuel_receipt_required`, `fuel_max_liters`, `fuel_price_alert_pct`, `fuel_use_alert_pct`, `maintenance_reminder_km`, `inspection_warning_days`, `workshop_auto_block_severity`, `shift_publish_notice_hours`, `max_daily_work_minutes`, `min_rest_hours`, `receipt_mandatory_fields`, `approval_stage_thresholds`, `expense_claim_limit`, `cost_overrun_alert_pct`, `finance_close_day`, `sharing_visibility_level`, `sharing_service_ids`, `interoperator_bookings`, `on_time_pickup_tolerance_min`, `on_time_arrival_tolerance_min`, `capacity_reject_alert_count`, `late_arrival_alert_pct`, `vehicle_unavailability_alert_min`, `daily_report_time`, `reporting_timezone`, `alert_escalation_delay_min`, `notification_quiet_hours`, `shift_swap_approval`, `customer_notify_before_pickup_min`, `kpi_aggregation_min_n`, `vehicle_readiness_require_checks`, `max_wait_min`.
  - Backend: Modul `config` – Registrierung der Schemas im Parameterregister (WP-M1-19); Katalogvalidierungen als Validatorregeln, z. B. `min_capacity_reserve` „nicht größer als tatsächlich verfügbare Kapazität“, `alert_escalation_delay_min` „kritische Ereignisse ohne Verzögerung“, `notification_quiet_hours` „Sicherheitsalarme ausgenommen“, `workshop_auto_block_severity` „kritischer Sicherheitsschaden immer sperrend“, `max_daily_work_minutes`/`min_rest_hours` „rechtliche Vorgaben nicht überschreibbar“.
  - Adapter/Worker: CI-Skript „Katalogabdeckung“ (80 Parameter aus `kataloge.csv` gegen registrierte `policy_schema`-Einträge; Bericht je Parameter: Schema vorhanden, liefernde Einheit).
- **Detailverträge vorab:** JSON-Schema je Parameter; Tabelle „Parameter → liefernde Einheit“ über M1 bis M8 (OP-01); systemische Grenzen, die das Pflichtenheft nicht beziffert (z. B. Obergrenze `fuel_use_alert_pct`).
- **Akzeptanzkriterien:**
  1. Jeder gelistete Parameter ist mit exakt dem Namen aus `kataloge.csv` registriert; der CI-Abgleich schlägt bei Namensabweichung fehl.
  2. Werte außerhalb des Katalogbereichs werden mit 422 abgewiesen (z. B. `min_booking_lead_min` > 43200, `max_booking_horizon_days` > 365).
  3. Jede im Katalog genannte Validierung ist als ausführbare Regel mit Negativtest hinterlegt oder als „nicht maschinell prüfbar“ mit Begründung dokumentiert.
  4. Der Abdeckungsbericht weist nach Abschluss von M8 für alle 80 Parameter ein Schema und die liefernde Einheit aus; Lücken sind benannt.
- **Tests:** positiv: Grenzwerte je Parameter. negativ: außerhalb Bereich, falscher Typ, nicht erlaubte Ebene, Sicherheitsvalidierung verletzt. konkurrierend (Sicherheit): Schemaversion wird während eines offenen Entwurfs angehoben – Publikation validiert gegen die dann gültige Version.
- **Abhängigkeiten:** intern: WP-M8d-01.
  - extern: WP-M1-19 (Parameterregister und Policy-Validator)
  - extern: WP-M8b-01 (Schemas der ETA-Parameter (`eta_*`) werden dort geliefert, hier nur im Abdeckungsbericht gezählt)
- **Entscheidungen:** D-05, D-06, D-07 – blockieren nur die Freigabe von Produktionswerten, nicht den Bau.
- **Größe:** M
- **Nicht im Umfang:** fachliche Verwendung der Werte (jeweiliges Fach-WP); Parameter anderer Einheiten.

### WP-M8d-04 – Referenzfixtures M8d
- **Ziel:** Deterministische Testdaten und Ereignisgeneratoren für alle M8d-Szenarien stehen in `tests/acceptance` bereit und sind in jedem Integrationstest wiederverwendbar.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker (Testinfrastruktur): Fixture „zwei Dienste auf derselben Flotte“; Fixture „Gebiet A und Gebiet B mit je eigenen Diensten“; Kalenderfixture `Europe/Berlin` mit Sommer-/Winterzeitwechsel (25.10.2026 02:30 doppelt, Frühjahrslücke) und Feiertag; Ereignisgenerator für 100 gleiche GPS-Fehler, vier aufeinanderfolgende Kapazitätsablehnungen, systemischen Dispositionsausfall, überhöhte Tankmenge, Frist in 30 Tagen; Rollenfixture (Entwurfsautor, zweiter Prüfer, Finanzrolle, Bereitschaftsrolle, Plattformbetreiber, Konzernadmin, Rolle ohne Rechte); zweite Testumgebung als Importziel für Konfigurationspakete.
  - Datenbank: Seeds unter Runtime-Rolle für zwei Mandanten.
- **Detailverträge vorab:** Seed-Katalog mit stabilen IDs; steuerbare Uhr für Wirksamkeits-, Eskalations- und Nachholtests.
- **Akzeptanzkriterien:**
  1. Jede Fixture lässt sich idempotent laden und liefert bei gleichem Seed identische IDs.
  2. Die steuerbare Uhr wirkt auf Wirksamkeitsauflösung, Unterdrückungsfenster, Eskalation und Scheduler.
  3. Fixtures enthalten ausschließlich synthetische Personen- und Fahrzeugdaten (Kap. 08 §8.3).
- **Tests:** positiv: Laden in leere Datenbank und Wiederholung. negativ: Fixture eines Mandanten ist im anderen Mandanten nicht sichtbar.
- **Abhängigkeiten:** intern: WP-M8d-02, WP-M8d-03.
  - extern: WP-M4-02 (Referenzfixtures Schicht/Belege)
  - extern: WP-M6b-03 (Referenzfixtures Kooperation A, B, C, Aufgabenträger)
- **Entscheidungen:** keine
- **Größe:** S
- **Nicht im Umfang:** Lastprofile (M8e/M5).

### WP-M8d-05 – Konfigurationslebenszyklus: Entwurf, Vorschau, Freigabe, Veröffentlichung, Rücknahme
- **Ziel:** Jede Konfiguration durchläuft für alle Regelgruppen denselben Lebenszyklus mit Vorschau auf die Wirkung; ein fehlerhafter veröffentlichter Regelstand wird mit einem Schritt auf die vorherige Version zurückgesetzt.
- **Anforderungen:** `LH-I.7-CFG-008`
- **Liefergegenstände:**
  - Backend: Modul `config` – Zustandsübergänge Entwurf → validiert → Vorschau erstellt → freigegeben → veröffentlicht → abgelöst/zurückgenommen; Rücknahme als neue Revision mit den Werten der vorherigen Version (Kap. 01 §1.6); Vorschau = Wirkungsbericht auf betroffene zukünftige Angebote, Fahrten und Schichten; bestätigte Zusagen bleiben an ihre Version gebunden.
  - Datenbank: `config_revision` (Statusspalte, Verweis auf zurückgenommene Revision, Änderungsgrund); Invariante „Nicht überlappende aktive Tarif-/Policy-/Dienstversion“ (Kap. 04 §4.5).
  - API: `/v1/config/revisions`, `/v1/config/revisions/{id}`, `/v1/config/revisions/{id}/validate`, `/v1/config/revisions/{id}/impact`, `/v1/config/revisions/{revision_id}/publish` (`publishConfig`, `PublishRequest{effective_from,reason,impact_report_id}`); Befehle Freigabe und Rücknahme (Detailvertrag).
  - Oberfläche: UI-17 – Konfigurationseditor mit aktuellem und vorgeschlagenem Wert, Herkunft, Wirksamkeitsdatum, Validator, Prüfer und Vorschau; Statusleiste des Lebenszyklus; Aktion „Auf vorherige Version zurücksetzen“ mit Pflichtbegründung; Zustand „konkurrierende Version“.
  - Ereignisse/Outbox: `config.revision_published`, `config.revision_rolled_back`; Audit.
- **Detailverträge vorab:** fortgeschriebener Zustandsautomat der Revision; OpenAPI Freigabe/Rücknahme; Abgrenzung „Simulation/Vorschau“ gegen das Szenariomodul SIM (P3) (OP-04).
- **Akzeptanzkriterien:**
  1. Ein fehlerhafter veröffentlichter Regelstand wird über „Rücknahme“ auf die vorherige Version zurückgesetzt: Es entsteht eine neue Revision mit den Werten der Vorversion, `/v1/config/effective` liefert danach den vorherigen Wert, die fehlerhafte Revision bleibt lesbar. (CFG-008)
  2. Keine Revision wird wirksam, ohne die Zustände Entwurf, Vorschau (Wirkungsbericht vorhanden), Freigabe und Veröffentlichung in dieser Reihenfolge durchlaufen zu haben; ein übersprungener Schritt ergibt 409/422. (CFG-008)
  3. Die Vorschau nennt betroffene zukünftige Angebote/Fahrten/Schichten; bereits bestätigte Zusagen sind als „unverändert gebundene Version“ ausgewiesen. (CFG-008)
  4. Veröffentlichung ohne `If-Match` ergibt 428, mit veralteter Revision 412; Wiederholung mit gleichem `Idempotency-Key` liefert dasselbe Ergebnis.
  5. Der Lebenszyklus gilt für jede in M8d eingeführte Regelgruppe (WP-M8d-09 bis WP-M8d-16) ohne Sonderpfad.
  6. Nach einer Rücknahme bleiben Buchungen, die unter dem fehlerhaften Regelstand bestätigt wurden, an ihre Zusageversion gebunden und werden nicht still umgerechnet; die Vorschau der Rücknahme listet sie, und das Audit enthält Akteur, Grund, zurückgenommene und wiederhergestellte Revision (E2E-098: „Audit und Buchungszusage bleiben korrekt“).
  7. Eine veröffentlichte Revision ist unveränderlich (Kap. 04 §4.4); ein Änderungsversuch an `values_json` oder Wirksamkeit einer publizierten Revision wird abgewiesen.
  8. UI-17 zeigt die Zustände „keine Revision vorhanden“, Ladefehler, „kein Änderungsrecht“ (lesend) und „konkurrierende Version“ (412) jeweils mit Text; der gesamte Durchlauf einschließlich Rücknahme ist per Tastatur bedienbar.
- **Tests:** positiv: vollständiger Durchlauf und Rücknahme. negativ: Veröffentlichung ohne Vorschau, ohne Freigabe, Rücknahme einer nie veröffentlichten Revision, Rücknahme ohne Begründung. konkurrierend (Sicherheit): Rücknahme und neue Veröffentlichung desselben Scopes parallel – genau eine wirksame Version, keine Lücke im Wirksamkeitsintervall. Ermöglicht PH-AT-018 (Regression); E2E-096, E2E-097 und E2E-098 bleiben grün (Regression des M1-Verhaltens).
- **Abhängigkeiten:** intern: WP-M8d-02.
  - extern: WP-M1-20, WP-M1-22, WP-M1-23 (Konfigurationsrevisionen, Publikation, Rollback, Wirkungsanalyse)
  - extern: WP-M6b-17 (zeitlich aktivierte Konfiguration in lokaler Betriebszeit)
  - extern: WP-M8b-28 (Verantwortliche und Rollback-Anweisung produktionskritischer Regeln) – Reihenfolgekonflikt (Zyklus über WP-M8b-28), siehe OP-35
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Freigabemodus/-schwellen (WP-M8d-06); Szenariosimulation mit eigenen Workern (SIM, M9); Paketexport (WP-M8d-17).

### WP-M8d-06 – Zweistufenmodus und Freigabeschwellen je Mandant
- **Ziel:** Jeder Mandant legt fest, welche Änderungen als kritisch gelten und eine Vier-Augen-Freigabe benötigen; im Zweistufenmodus kann der Entwurfsautor seine Änderung nicht allein freigeben.
- **Anforderungen:** `LH-I.7-CFG-009`
- **Liefergegenstände:**
  - Backend: Modul `config` – Freigabepolicy je Mandant (Modus, Kritikalitätsklassen, Schwellen); Prüfung „Freigebender ≠ Entwurfsautor“ serverseitig aus der signierten Identität; systemische Mindestkritikalität (Sicherheitsparameter immer zweistufig) nicht absenkbar – Vorschlag dieses Plans, im Pflichtenheft nicht beziffert (OP-05).
  - Datenbank: `config_revision` (`created_by`, `approved_by`, Freigabemodus zum Zeitpunkt der Freigabe); `policy_schema` (Kritikalität); Freigabepolicy als eigene Konfigurationsrevision.
  - API: Freigabebefehl an `/v1/config/revisions/{id}` (Detailvertrag); Fehlercode für Selbstfreigabe (403 mit Problem-Typ).
  - Oberfläche: UI-17 – Anzeige des geltenden Freigabemodus, des zuständigen zweiten Prüfers und des Grundes einer Ablehnung.
  - Ereignisse/Outbox: Sicherheitsereignis bei abgelehnter Selbstfreigabe.
- **Detailverträge vorab:** Definition „kritische Änderung“ und Form der „Freigabeschwellen“ (OP-05); Verhältnis zum in WP-M1-22 vorgesehenen CHECK `approved_by <> created_by` (OP-05).
- **Akzeptanzkriterien:**
  1. Im gewählten Zweistufenmodus wird die Freigabe einer Revision durch ihren Entwurfsautor abgelehnt; die Revision bleibt unveröffentlicht, der Versuch ist protokolliert. (CFG-009)
  2. Eine zweite berechtigte Person kann dieselbe Revision freigeben; `approved_by` und `created_by` unterscheiden sich. (CFG-009)
  3. Freigabeschwellen sind je Mandant getrennt einstellbar; Mandant A und B haben nachweislich unterschiedliche Schwellen. (CFG-009)
  4. Die Freigabepolicy selbst kann nur zweistufig geändert werden; ein Mandant kann die Zweistufigkeit für sicherheitsklassifizierte Parameter nicht abschalten.
- **Tests:** positiv: Freigabe durch Zweiten. negativ: Selbstfreigabe, Freigabe durch Rolle ohne `config:publish`, Freigabe mit fremdem Mandantenkontext. konkurrierend (Sicherheit): Autor ändert den Entwurf, während der Prüfer freigibt – Freigabe schlägt mit 412 fehl, keine Freigabe einer ungesehenen Fassung; zwei parallele Freigaben – genau eine wirksam.
- **Abhängigkeiten:** intern: WP-M8d-05.
  - extern: WP-M1-07, WP-M1-08, WP-M1-12, WP-M1-22 (Rollen- und Objektberechtigung, Audit, Vier-Augen-Freigabe)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Freigabestufen für Belege (WP-M8a-11); Parameter-Änderungsrechte (WP-M8d-07).

### WP-M8d-07 – Berechtigungsmatrix der Parameter
- **Ziel:** Für jeden Parameter ist festgelegt und in der Oberfläche sichtbar, ob ihn der Betreiber selbst, der Konzernadmin oder nur der Plattformbetreiber ändern darf; Rollen ohne Recht sehen kritische globale Parameter ausschließlich lesend.
- **Anforderungen:** `LH-I.7-CFG-018`
- **Liefergegenstände:**
  - Backend: Modul `config` – Änderungsberechtigung je Parameter (Betreiber, Konzernadmin, Plattformbetreiber) als Teil des Parameterregisters; serverseitige Durchsetzung beim Anlegen/Ändern eines Entwurfs; lesende Projektion für nicht berechtigte Rollen.
  - Datenbank: `policy_schema` (Änderungsberechtigungsebene, Kennzeichen „kritisch global“).
  - API: `/v1/config/effective` und Katalogressource liefern je Parameter `editable` und die zuständige Ebene; Schreibversuch ohne Recht 403.
  - Oberfläche: UI-17 – Matrixansicht Parameter × Rolle; gesperrte Felder mit Text- und Symbolkennzeichnung (nicht nur Farbe), Hinweis auf zuständige Stelle.
- **Detailverträge vorab:** Matrix für alle 80 Parameter aus `kataloge.csv`; Definition der Rolle „Konzernadmin“ im Rollenkatalog (OP-06).
- **Akzeptanzkriterien:**
  1. Eine Rolle ohne Änderungsrecht sieht kritische globale Parameter nur lesend: Wert, Einheit und Herkunft sind sichtbar, das Feld ist nicht editierbar. (CFG-018)
  2. Ein direkter API-Schreibversuch derselben Rolle auf einen solchen Parameter wird mit 403 abgewiesen und protokolliert; es entsteht kein Entwurf. (CFG-018)
  3. Die Matrix unterscheidet für jeden Parameter die drei Ebenen Betreiber, Konzernadmin und Plattformbetreiber und ist in der Oberfläche einsehbar. (CFG-018)
  4. Eine Änderung der Matrix ist selbst eine versionierte, freigabepflichtige Konfiguration des Plattformbetreibers.
- **Tests:** positiv: je Ebene ein erlaubter Schreibvorgang. negativ: Betreiber schreibt Plattformparameter, Konzernadmin schreibt in Mandanten außerhalb seiner Mitgliedschaft, manipuliertes `editable` im Request. konkurrierend (Sicherheit): Recht wird entzogen, während ein Entwurf offen ist – Freigabe/Veröffentlichung prüft erneut und lehnt ab.
- **Abhängigkeiten:** intern: WP-M8d-03, WP-M8d-05.
  - extern: WP-M1-06, WP-M1-07, WP-M1-08, WP-M1-19 (Rollen-/Objektberechtigung, Mitgliedschaft, Parameterkatalog)
  - extern: WP-M6b-16, WP-M6b-19 (delegierte Rollenvergabe, Konfigurationshierarchie mit Herkunftsnachweis)
  - extern: UNGEDECKT – erweiterte IAM-Rollen (Konzern-/Mehrmandantenrollen); in M8e kein lieferndes WP
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Feldschutz für PII/HR/Finanzdaten (M1/M8e); Freigabemodus (WP-M8d-06).

### WP-M8d-08 – Zeitzone, Feiertagskalender und überlappende Zeiträume
- **Ziel:** Konfigurationswerte mit Beginn, Ende, lokaler Zeitzone und Feiertagsbezug werden eindeutig aufgelöst; Sommer-/Winterzeitwechsel erzeugen weder doppelte noch fehlende Betriebsschichten.
- **Anforderungen:** `LH-I.7-CFG-021`
- **Liefergegenstände:**
  - Backend: Modul `config` – Zeitauflösung mit IANA-Zone je Mandant/Region; links geschlossene, rechts offene Intervalle (Kap. 04 §4.1); Kalenderdienst (Feiertag, Ausnahmetag, lokaler Betriebstag) als interner Port; Überlappungsprüfung bei Veröffentlichung; Behandlung doppelter und nicht existenter Ortszeiten (explizite Auswahl mit UTC-Offset bzw. Korrekturpflicht, Kap. 06 §6.2).
  - Datenbank: `operating_calendar`/`calendar_exception_day` (Arbeitsnamen); `config_revision.effective_from/effective_to` als `timestamptz` plus gespeicherte Zone; Invariante „Nicht überlappende aktive Tarif-/Policy-/Dienstversion“.
  - API: Kalenderpflege (Detailvertrag); `/v1/config/effective` mit Zeitpunkt und lokalem Betriebstag in der Antwort.
  - Oberfläche: UI-17 – Eingabe von Wirksamkeitszeiten mit Zonenanzeige; Warnung bei überlappenden Zeiträumen; Auswahl mit UTC-Offset bei mehrdeutiger Ortszeit. UI-12 – Schichten über den Zeitwechsel mit absoluter Dauer.
  - Konfigurationsparameter: `reporting_timezone`, `daily_report_time`, `notification_quiet_hours`.
- **Detailverträge vorab:** Kalenderdatenmodell und Quelle der Feiertage (OP-07); Regel für Konfigurationswechsel innerhalb eines laufenden Betriebstags; Regel für Betriebszeiten, die in die Zeitumstellung fallen.
- **Akzeptanzkriterien:**
  1. Über den Sommer-/Winterzeitwechsel erzeugte Betriebsschichten aus einer Betriebszeitkonfiguration sind vollständig und eindeutig: keine Schicht doppelt, keine fehlt; die Schichtdauer ergibt sich aus absoluten Zeitpunkten (23- bzw. 25-Stunden-Tag). (CFG-021)
  2. Eine Wirksamkeitsgrenze um 02:30 Ortszeit am 25.10.2026 verlangt die Auswahl des UTC-Offsets; ohne Auswahl wird nicht gespeichert. Eine nicht existente Ortszeit wird abgewiesen. (CFG-021)
  3. Zwei Konfigurationen desselben Scopes mit überlappendem Zeitraum werden vor Veröffentlichung beanstandet; die Auflösung liefert zu jedem Zeitpunkt genau einen Wert. (CFG-021)
  4. Ein Feiertag laut Kalender aktiviert die dafür hinterlegten Werte nur am lokalen Betriebstag der betroffenen Region.
  5. Die aktuelle Ansicht zeigt verwendete Version und lokalen Betriebstag (PH-AT-018).
- **Tests:** positiv: Herbst- und Frühjahrsumstellung, Feiertag, Fahrt über Mitternacht. negativ: mehrdeutige Zeit ohne Offset, nicht existente Zeit, Überlappung, Ende vor Beginn. konkurrierend (Datentrennung/Sicherheit): zwei Veröffentlichungen mit angrenzenden Intervallen parallel – keine Überlappung und keine Lücke. Besteht den Konfigurationsteil von PH-AT-018; trägt zu E2E-102 bei.
- **Abhängigkeiten:** intern: WP-M8d-02, WP-M8d-05.
  - extern: WP-M4-03, WP-M4-10 (Zeitrechnung über Mitternacht, Zeitzone und DST; Schichtmodell)
  - extern: WP-M1-09 (Organisationsstruktur Regionen)
  - extern: WP-M8c-33 (Berichtsanteil des Tests E2E-102 (Berichte über Mehrregion))
  - extern: UNGEDECKT – Führung des Gesamttests E2E-102 (Kalender, Logs, Berichte über Mehrregion); M8c liefert nur den Berichtsanteil, eine führende Einheit ist nicht benannt
- **Entscheidungen:** D-05 (Regionen, Zeitzonen des Pilots) – nur Produktivwerte.
- **Größe:** M
- **Nicht im Umfang:** Arbeitszeitberechnung (M4/M7d); Berichtsperioden (M8c).

### WP-M8d-09 – Dienstspezifische Parameter für Reserve, Rollstuhlplätze, Vorlauf und Pooling
- **Ziel:** Kapazitätsreserve, Rollstuhlplätze, Buchungsvorlauf und Pooling werden je Dienst getrennt gesetzt und in Angebotsbildung und Disposition nachweislich dienstspezifisch angewendet, auch wenn zwei Dienste dieselbe Flotte nutzen.
- **Anforderungen:** `LH-I.7-CFG-012`
- **Liefergegenstände:**
  - Backend: Modul `config` – Scope „Dienst“ für die genannten Parameter; Modul `booking-dispatch` – Auflösung der Parameter über die `service`-Referenz der Anfrage, nicht über das Fahrzeug; Protokollierung der verwendeten `config_revision` je Angebot.
  - Datenbank: `config_revision` (`scope_kind` Dienst), `service`, `vehicle_run`, `capacity_hold`, `travel_attempt` (verwendete Revision am Versuch).
  - API: `/v1/config/effective` (Kontext Dienst), `/v1/config/revisions`.
  - Oberfläche: UI-17 – Parameter je Dienst im Vergleich nebeneinander; Vorschau auf betroffene zukünftige Angebote.
  - Konfigurationsparameter: `min_capacity_reserve`, `min_booking_lead_min`, `max_booking_horizon_days`, `pooling_allowed`, `max_detour_pct`; Rollstuhlplatz-Parameter (im Katalog nicht vorhanden, OP-08).
- **Detailverträge vorab:** Parameter für dienstbezogene Rollstuhlplätze (OP-08); Regel, wie `min_capacity_reserve` zweier Dienste auf demselben `vehicle_run` zusammenwirkt.
- **Akzeptanzkriterien:**
  1. Zwei Dienste auf derselben Flotte verwenden nachweislich unterschiedliche Parameter: Dienst 1 (`pooling_allowed` = false, `min_booking_lead_min` = 60) lehnt eine Anfrage mit 30 Minuten Vorlauf ab und poolt nicht; Dienst 2 (`pooling_allowed` = true, `min_booking_lead_min` = 15) nimmt dieselbe Anfrage auf demselben Fahrzeug an; beide Angebote/Ablehnungen referenzieren ihre jeweilige `config_revision`. (CFG-012)
  2. Kapazitätsreserve und Rollstuhlplätze sind je Dienst getrennt setzbar; die Reserve eines Dienstes mindert nicht stillschweigend die buchbare Kapazität des anderen über die konfigurierte Grenze hinaus. (CFG-012)
  3. `min_capacity_reserve` größer als die tatsächlich verfügbare Kapazität wird vor Veröffentlichung abgewiesen (Katalogvalidierung).
  4. Eine Änderung wirkt nur auf Angebote ab `effective_from`; bestehende Buchungen behalten ihre Zusageversion.
- **Tests:** positiv: zwei Dienste, je Parameter unterschiedliche Werte. negativ: Reserve über Kapazität, Dienst eines fremden Mandanten, Parameter auf nicht erlaubter Ebene. konkurrierend (Kapazität): parallele Holds beider Dienste auf den letzten freien Platz desselben `vehicle_run` bei unterschiedlicher Reserve – kein Überbuchen, Reserve je Dienst eingehalten.
- **Abhängigkeiten:** intern: WP-M8d-03, WP-M8d-05.
  - extern: WP-M2-11, WP-M2-13, WP-M2-21, WP-M2-22, WP-M2-23 (Angebotsbildung, Hold/Confirm, `travel_attempt` mit Ablehnungsgrund)
  - extern: WP-M3-04 (Kapazitäts- und Stellplatzkonfiguration der Fahrzeuge)
  - extern: WP-M1-21, WP-M1-25 (Dienstkonfiguration und Präzedenzkette)
- **Entscheidungen:** D-05 (Dienste des Pilots) – nur Produktivwerte.
- **Größe:** M
- **Nicht im Umfang:** Optimiererlogik selbst (M2/M7b); externes Fahrzeugkontingent `sharing_vehicle_quota` (M6).

### WP-M8d-10 – Preis-, Rabatt- und Erstattungsregeln mit Gültigkeit und Ausnahmetagen
- **Ziel:** Preis-, Rabatt- und Erstattungsregeln werden als versionierte Regeln mit Gültigkeitsintervall und Ausnahmetagen gepflegt; vergangene Fahrten werden nie mit einem neuen Regelstand nachgerechnet.
- **Anforderungen:** `LH-I.7-CFG-011`
- **Liefergegenstände:**
  - Backend: Modul `fare-payment` – Auswahl der Regelrevision nach Leistungszeitpunkt und Kalender (Ausnahmetag vor Regelintervall); Modul `config` – Lebenszyklus und Vorschau für Tarifregeln; Erstattungen und Stornos rechnen mit dem im `fare_quote` eingefrorenen Regelstand.
  - Datenbank: `tariff_revision`, `tariff_rule`, `fare_component`, `refund_case`; `fare_quote` (unveränderlicher Snapshot mit Policy-/Tarifversion); Ausnahmetage über `calendar_exception_day` (Arbeitsname); Invariante „Nicht überlappende aktive Tarif-/Policy-/Dienstversion“.
  - API: `/v1/config/revisions` (Regelgruppe Tarif), `/v1/config/revisions/{id}/impact`; lesend in bestehenden Preis-/Stornoantworten die verwendete Regelversion.
  - Oberfläche: UI-17 (Gebiete/Stops/Tarife) – Gültigkeitsintervall, Ausnahmetage, Vorschau „betroffene zukünftige Angebote“; Zustand „konkurrierende Version“.
- **Detailverträge vorab:** Datenmodell der Rabatt- und Erstattungsregeln (in Kap. 04 §4.4 nicht eigens benannt, OP-09); Vorrang Ausnahmetag gegenüber Regelintervall; Steuerbezug bei Regelwechsel.
- **Akzeptanzkriterien:**
  1. Nach Veröffentlichung eines neuen Preises behalten vergangene und bereits bestätigte Fahrten ihren ursprünglichen Preis; ein erneuter Abruf, ein Bericht und eine nachträgliche Erstattung rechnen mit dem eingefrorenen Regelstand. (CFG-011)
  2. Preis-, Rabatt- und Erstattungsregeln besitzen je ein versioniertes Gültigkeitsintervall; zu jedem Zeitpunkt ist genau eine Regelrevision je Scope wirksam. (CFG-011)
  3. Ein Ausnahmetag (z. B. Feiertagstarif) überlagert den Regelstand nur an diesem lokalen Betriebstag und ist an Angebot und Beleg ausgewiesen. (CFG-011)
  4. Ein Regelstand mit rückwirkendem `effective_from` für bereits bestätigte Zusagen wird nicht auf diese angewendet; die Vorschau weist sie als unverändert aus.
- **Tests:** positiv: Preiswechsel zum Stichtag, Ausnahmetag, Erstattung nach Regelwechsel. negativ: überlappende Intervalle, Ausnahmetag ohne Kalenderbezug, Bearbeitung einer veröffentlichten Regel. konkurrierend (Geld): Veröffentlichung eines neuen Preises während laufendem Checkout – der Kunde zahlt exakt den Betrag seines `fare_quote`; paralleler Storno und Regelwechsel – Erstattung nach eingefrorenem Stand. Besteht den Tarifteil von PH-AT-018; trägt zu E2E-098 (Regression) bei.
- **Abhängigkeiten:** intern: WP-M8d-05, WP-M8d-08.
  - extern: WP-M2-07, WP-M2-08, WP-M2-26 (Tarifrevision und Preisregeln mit Gültigkeit, Tarifengine, Storno mit Fristen)
  - extern: WP-M6c-06, WP-M6c-07, WP-M6c-09, WP-M6c-14 (Rabatt-/Gutschein- und Erstattungslogik im Mehrparteien-Checkout)
- **Entscheidungen:** D-03 (Capturezeitpunkt, Erstattungsregeln) – blockiert nur die Produktivschaltung der Erstattungsregeln.
- **Größe:** M
- **Nicht im Umfang:** neue Tarifmodelle; Clearing-Preisanteile (M6c).

### WP-M8d-11 – Kooperationsregeln: Daten-, Service-, Tarif- und Zahlungsfreigaben
- **Ziel:** Regeln einer Partnerkooperation unterscheiden vier unabhängige Freigabearten; eine reine Auskunftskooperation verleiht keinerlei externe Buchungsrechte.
- **Anforderungen:** `LH-I.7-CFG-010`
- **Liefergegenstände:**
  - Backend: Modul `journey-federation` – Freigabearten Daten, Service, Tarif, Zahlung als getrennte Aktionsklassen im Policy-Gate; Mengen werden geschnitten, nie vereinigt (Kap. 04 §4.3); Modul `config` – Lebenszyklus für Kooperationsregeln.
  - Datenbank: `cooperation_grant` (`actions`, `service_refs`, `allowed_fields`, `purpose`, `valid_from`/`valid_to`, `revoked_at`), `partner_contract_version`; Invariante „Grantwiderruf sofort wirksam“ (Kap. 04 §4.5).
  - API: Kooperationspflege (Pfade aus M6b); `/v1/federation/journeys/plan` liefert für nicht buchungsberechtigte Partnersegmente keine `PLATFORM_BOOKABLE`-Fähigkeit; `/v1/federation/offers/{offer_id}/hold` lehnt ab.
  - Oberfläche: UI-18 – vier getrennte Freigabebereiche mit erlaubten Diensten/Aktionen/Feldern; kein pauschales Kontrollkästchen „alle Mandantendaten teilen“ (Kap. 06 §6.8); Zustand „Scopekonflikt“.
  - Konfigurationsparameter: `sharing_visibility_level`, `sharing_service_ids`, `interoperator_bookings`.
- **Detailverträge vorab:** Abbildung der vier Freigabearten auf Werte von `cooperation_grant.actions` (OP-10); Matrix Freigabeart × Endpunkt.
- **Akzeptanzkriterien:**
  1. Bei aktiver reiner Auskunftskooperation (nur Datenfreigabe) sind Partnersegmente sichtbar, aber nicht buchbar: Hold/Confirm für diese Segmente werden serverseitig abgelehnt, `interoperator_bookings` ist false, es entsteht keine Fremdbuchung. (CFG-010)
  2. Daten-, Service-, Tarif- und Zahlungsfreigabe sind einzeln erteil- und entziehbar; das Erteilen einer Art aktiviert keine andere. (CFG-010)
  3. Eine Zahlungsfreigabe ohne Servicefreigabe erlaubt keinen gemeinsamen Checkout für diesen Partner.
  4. Der Entzug einer Freigabeart wirkt bei der nächsten Anfrage, auch mit altem Token oder Cache.
- **Tests:** positiv: je Freigabeart ein erlaubter Zugriff. negativ: Buchung bei reiner Auskunft, Tarifabruf ohne Tariffreigabe, Zahlung ohne Zahlungsfreigabe, manipulierte Partner-ID. konkurrierend (Datentrennung/Sicherheit): Widerruf der Servicefreigabe parallel zu Hold – kein Hold nach wirksamem Widerruf; alter Cacheeintrag gewährt keinen Zugriff.
- **Abhängigkeiten:** intern: WP-M8d-03, WP-M8d-05.
  - extern: WP-M6b-04, WP-M6b-05, WP-M6b-08 (Kooperationsvertrag, Cooperation Grants und Policy-Gate, UI-18)
  - extern: WP-M6a-04 (Vertragsgate und Kooperationsfilter in der Reiseplanung)
  - extern: WP-M6c-07 (Mehrparteien-Checkout als Ziel der Zahlungsfreigabe)
- **Entscheidungen:** D-01, D-03, D-08 – blockieren die Produktivschaltung von Service-/Tarif-/Zahlungsfreigaben mit realen Partnern, nicht den Bau in der Sandbox.
- **Größe:** M
- **Nicht im Umfang:** Vertragslebenszyklus, Pause, Widerruf (M6b); Clearingmodell `partner_settlement_model` (M6c).

### WP-M8d-12 – Finanzschwellen durch Finanzrollen mit Prüfworkflow
- **Ziel:** Finanzrollen stellen Schwellwerte für Tankverbrauch, Kostenzuschüsse, Reparaturbudget und Freigabelevel selbst ein; jede Überschreitung führt zu einem zugewiesenen Prüfworkflow.
- **Anforderungen:** `LH-I.7-CFG-013`
- **Liefergegenstände:**
  - Backend: Modul `config` – Regelgruppe „Finanzschwellen“ mit Änderungsrecht ausschließlich für Finanzrollen; Modul `documents-finance` – Schwellenprüfung bei Kostenereignis/Beleg/Budgetfortschreibung, Erzeugen eines Prüfcase über den Prüfcase-Kern (WP-M8d-33) mit Zuständigem aus der Kostenverantwortung.
  - Datenbank: `config_revision` (Scope Mandant/Kostenstelle/Fahrzeugtyp), `review_case` (Arbeitsname), `approval_step`, `economic_event`, `cost_object`, `business_alert`.
  - API: `/v1/config/revisions`, `/v1/config/effective`; Prüfcase-Arbeitsliste (Detailvertrag); bestehend `/v1/expenses/{expense_id}/approve` liefert bei Überschreitung `WAITING_SECOND_APPROVAL` (Kap. 05 §5.5).
  - Oberfläche: UI-17 – Finanzschwellen nur für Finanzrollen editierbar; UI-14/UI-15 – Prüfcase mit Schwelle, Istwert, Berechnungsbasis und Zuständigem.
  - Konfigurationsparameter: `fuel_use_alert_pct`, `fuel_price_alert_pct`, `fuel_max_liters`, `approval_stage_thresholds`, `expense_claim_limit`, `cost_overrun_alert_pct`; Schwellen für Kostenzuschüsse und Reparaturbudget (im Katalog nicht vorhanden, OP-11).
  - Kennzahlen: `Kraftstoff L/100 km`, `Budgetabweichung`, `Zuschuss pro Fahrgastfahrt` (als Berechnungsbasis der Schwellen, Definition aus M8c).
- **Detailverträge vorab:** Parameterdefinition für Kostenzuschuss- und Reparaturbudgetschwellen (OP-11); Zuweisungsregel je Schwellenart (Rolle/Person, Vertretung) als Eingang des Prüfcase-Kerns (WP-M8d-33).
- **Akzeptanzkriterien:**
  1. Überschreitet ein Istwert eine eingestellte Schwelle (je ein Fall für Tankverbrauch, Kostenzuschuss, Reparaturbudget, Freigabelevel), entsteht genau ein Prüfworkflow mit zugewiesenem Zuständigen, Schwelle, Istwert und verwendeter Regelversion. (CFG-013)
  2. Nur Finanzrollen können diese Schwellen ändern; ein Änderungsversuch von Leitstelle oder Mandantenadministrator ohne Finanzrolle ergibt 403, erzeugt keinen Entwurf und ist protokolliert. Ob diese Rollen die Werte lesend sehen, legt der Feldschutz im Detailvertrag fest. (CFG-013)
  3. Eine Schwellenänderung wirkt ab `effective_from`; bereits geprüfte Vorgänge werden nicht neu bewertet.
  4. `fuel_use_alert_pct` berücksichtigt die Stichprobengröße laut Katalogvalidierung; bei zu kleiner Stichprobe entsteht ein gekennzeichneter Hinweis statt eines Prüfcase.
- **Tests:** positiv: je Schwellenart Überschreitung und Unterschreitung. negativ: Änderung durch Nicht-Finanzrolle, negative Schwelle, Fremdmandant. konkurrierend (Geld): dasselbe Kostenereignis wird doppelt zugestellt – genau ein Prüfcase; Schwellenänderung parallel zur Prüfung – der Fall trägt eindeutig eine Regelversion.
- **Abhängigkeiten:** intern: WP-M8d-03, WP-M8d-07, WP-M8d-22, WP-M8d-33.
  - extern: WP-M8a-11, WP-M8a-34, WP-M8a-35, WP-M8a-36 (Freigabestufen, Vertragsbudgets, Kostenverantwortliche und Weiterleitung, Kontrollberichte Kraftstoff)
  - extern: WP-M4-31, WP-M4-34 (Freigabe-Workflow mit Vier-Augen-Schwelle, Tankplausibilität)
  - extern: WP-M8c-15, WP-M8c-22 (ausführbare Definitionen `Kraftstoff L/100 km`, `Budgetabweichung`)
- **Entscheidungen:** D-06, D-07 – blockieren die Produktivwerte (Kostenstellen, Freigaberegeln), nicht den Bau.
- **Größe:** M
- **Nicht im Umfang:** Budgetführung selbst (M8a); Tanklimit-Automation (WP-M8d-25); Finanzalarme zum Abschluss (WP-M8d-30).

### WP-M8d-13 – Belegfelder, Dokumentenpflichten und Freigabegrenzen je Belegart
- **Ziel:** Pflichtfelder, Dokumentenpflichten und Freigabegrenzen werden je Belegart konfiguriert und bei Erfassung und Freigabe durchgesetzt.
- **Anforderungen:** `LH-I.7-CFG-015`
- **Liefergegenstände:**
  - Backend: Modul `documents-finance` – Validierung eines Belegs gegen die zur Belegart wirksame Feldmenge und Dokumentenpflicht; Freigabegrenze je Belegart als Eingang der Stufenermittlung; steuerlich notwendige Felder sind nicht abwählbar (Katalogvalidierung).
  - Datenbank: `config_revision` (Scope Mandant/Belegart), `expense_document`, `document_revision`, `extracted_field`, `supplier_invoice`, `fuel_transaction`, `approval_step`, `evidence_link`.
  - API: `/v1/expenses/receipts`, `/v1/expenses/{id}`, `/v1/expenses/{expense_id}/approve` (422 mit Feldliste bei fehlendem Pflichtfeld); `/v1/config/revisions`.
  - Oberfläche: UI-14 – Pflichtfelder je Belegart gekennzeichnet; „Freigeben“ nur bei vollständiger Validierung (Kap. 06 §6.6); UI-17 – Editor der Feldmengen je Belegart. Driver-Belegerfassung (UI-10) zeigt dieselben Pflichtfelder.
  - Konfigurationsparameter: `receipt_mandatory_fields`, `approval_stage_thresholds`, `fuel_receipt_required`.
- **Detailverträge vorab:** Katalog der Belegarten und konfigurierbaren Felder (inkl. „Literzahl“, „Arbeitsauftrag“); Liste der geschützten steuerlichen Pflichtfelder (OP-13); Form der Dokumentenpflicht (welcher Anhang je Belegart); Regel, gegen welche Feldmengenrevision ein offline erfasster Beleg geprüft wird (OP-34).
- **Akzeptanzkriterien:**
  1. Ein Tankbeleg ohne Literzahl wird nicht freigegeben (Feldfehler „Literzahl“); eine Werkstattrechnung ohne Arbeitsauftrag wird nicht freigegeben (Feldfehler „Arbeitsauftrag“); der Tankbeleg benötigt keinen Arbeitsauftrag, die Werkstattrechnung keine Literzahl. (CFG-015)
  2. Freigabegrenzen sind je Belegart unterschiedlich einstellbar und führen nachweislich zu unterschiedlichen Freigabestufen bei gleichem Betrag. (CFG-015)
  3. Eine konfigurierte Dokumentenpflicht (fehlender Pflichtanhang) verhindert die Freigabe mit benanntem Grund. (CFG-015)
  4. Das Entfernen eines steuerlich geschützten Pflichtfelds wird vor Veröffentlichung abgewiesen.
  5. Bereits freigegebene Belege bleiben nach einer Feldmengenänderung gültig; die Änderung wirkt ab `effective_from`.
- **Tests:** positiv: vollständiger Tankbeleg, vollständige Werkstattrechnung. negativ: fehlendes Pflichtfeld je Belegart, unbekannte Belegart, geschütztes Feld entfernt. konkurrierend (Geld): Feldmengenänderung parallel zur Freigabe – Freigabe prüft gegen genau eine Revision; doppelter Freigabe-POST erhöht keine Summe.
- **Abhängigkeiten:** intern: WP-M8d-03, WP-M8d-05.
  - extern: WP-M4-22, WP-M4-30, WP-M4-34, WP-M4-38 (Belegaggregat, Tankbeleg-Fachdaten, Freigabe-Workflow, UI-14)
  - extern: WP-M8a-04, WP-M8a-06, WP-M8a-11 (Kostenarten Werkstatt, Belegstatusautomat, Freigabestufen)
  - extern: WP-M7c-35 (Werkstattauftrag/`repair_order` als Bezug des Feldes „Arbeitsauftrag“)
- **Entscheidungen:** D-06 (Steuerregeln, zulässige E-Rechnungsprofile, Aufbewahrung) – blockiert nur die Produktivfeldmengen.
- **Größe:** M
- **Nicht im Umfang:** OCR-Extraktion (M4); XRechnung/KoSIT-Validierung (M8a).

### WP-M8d-14 – Rechtsraumvorlagen für Schichtplanungsregeln
- **Ziel:** Regeln für Vorlauf, Arbeitszeit, Pausen, Ruhezeiten und Qualifikation liegen als Vorlagen je Rechtsraum vor, werden vom Mandanten nur innerhalb der rechtlichen Grenzen verschärft und beanstanden fehlerhafte Zuweisungen vor der Veröffentlichung des Dienstplans.
- **Anforderungen:** `LH-I.7-CFG-014`
- **Liefergegenstände:**
  - Backend: Modul `config` – Scope „Rechtsraum“ mit Vorlagen als globale, nur vom Plattformbetreiber änderbare Revisionen; Mandantenwerte dürfen nur enger sein; Modul `staff-time` – Konfliktprüfung des Dienstplans liest die wirksame Regelrevision.
  - Datenbank: `config_revision` (Scope Rechtsraum, Mandant), `policy_schema` (Rechtsraumbezug), `roster_revision`, `staff_shift`, `qualification_revision`, `legal_entity` (Land als Rechtsraumbezug).
  - API: `/v1/config/revisions`, `/v1/config/effective`; `/v1/rosters/{roster_id}/publish` (`publishRoster`) liefert blockierende Konflikte mit Regel und Regelversion.
  - Oberfläche: UI-17 – Auswahl der Rechtsraumvorlage, Anzeige der nicht unterschreitbaren Grenzen; UI-12 – Konfliktliste vor Veröffentlichung mit Regelbezug (Zustände Ruhezeit, Doppelbelegung, unbesetzter Dienst).
  - Konfigurationsparameter: `shift_publish_notice_hours`, `max_daily_work_minutes`, `min_rest_hours`, `shift_swap_approval`; Pausenregel (im Katalog nicht vorhanden, OP-14).
- **Detailverträge vorab:** Inhalt der Rechtsraumvorlagen (Werte je Land sind im Pflichtenheft nicht angegeben, OP-14); Einordnung des Scopes „Rechtsraum“ in die Präzedenzkette aus Kap. 01 §1.6 (OP-14); fachliche Freigabe der Vorlagen durch Arbeitsrecht.
- **Akzeptanzkriterien:**
  1. Eine betrieblich fehlerhafte Zuweisung (je ein Fall: Ruhezeit unterschritten, Tagesarbeitszeit überschritten, Pause fehlt, Qualifikation fehlt, Veröffentlichungsvorlauf unterschritten) wird vor Veröffentlichung des Dienstplans mit Regel, Regelversion und betroffener Schicht beanstandet. (CFG-014)
  2. Für jeden Rechtsraum existiert eine Vorlage mit den fünf Regelgruppen; ein Mandant übernimmt sie und kann nur engere Werte setzen. (CFG-014)
  3. Ein Mandantenwert, der die Rechtsraumgrenze lockert (z. B. `min_rest_hours` unter der Vorlage), wird abgewiesen (Katalogvalidierung „rechtlich erforderliche Mindestdauer“).
  4. Eine Aktualisierung der Vorlage erzeugt eine neue Version mit Wirksamkeitsdatum; bereits veröffentlichte Dienstpläne bleiben mit ihrer Regelversion nachvollziehbar.
- **Tests:** positiv: regelkonformer Plan je Rechtsraum. negativ: jede der fünf Regelverletzungen, gelockerter Mandantenwert, Vorlagenänderung durch Mandantenrolle. konkurrierend (Sicherheit): Vorlagenwechsel parallel zur Planveröffentlichung – Veröffentlichung prüft gegen genau eine Version.
- **Abhängigkeiten:** intern: WP-M8d-03, WP-M8d-05.
  - extern: WP-M4-07, WP-M4-13, WP-M4-14 (Konfliktprüfung, Hinweise zu Pausen/Ruhezeit, Dienstplanveröffentlichung)
  - extern: WP-M7d-04, WP-M7d-20 (Schichtvorlagen und Publikationsvorschau)
- **Entscheidungen:** keine D-Entscheidung benannt; die fachlich-rechtliche Freigabe der Vorlagenwerte blockiert die Produktivschaltung (OP-14).
- **Größe:** M
- **Nicht im Umfang:** Arbeitszeitkonten und Lohnübergabe (M7d); tarifvertragliche Sonderregeln je Betrieb.

### WP-M8d-15 – Versionierte SLA-Ziele und Ausnahmeregeln im Reporting
- **Ziel:** SLA-Zielwerte und Ausnahmeregeln werden versioniert konfiguriert; jeder Bericht zeigt neben der berechneten Kennzahl den angewandten Grenzwert und dessen genaue Gültigkeit.
- **Anforderungen:** `LH-I.7-CFG-016`
- **Liefergegenstände:**
  - Backend: Modul `config` – Regelgruppe „SLA-Ziele/Ausnahmen“ je Dienst; Modul `analytics` – Berichte lösen Grenzwert und Ausnahmeregel zeitpunktbezogen (As-of der Leistungsperiode) auf und liefern sie mit Revisions-ID.
  - Datenbank: `config_revision` (Scope Dienst), `metric_definition_revision`, `aggregate_snapshot` (gespeicherte Grenzwert-/Regelversion), `dataset_watermark`.
  - API: `/v1/analytics/metrics`, `/v1/reports/jobs`, `/v1/reports/jobs/{id}` (Felder Grenzwert, `effective_from`/`effective_to`, Regelversion, Ausnahmeregel); `/v1/config/revisions`.
  - Oberfläche: UI-19 – Grenzwert und Gültigkeit neben der Kennzahl; Zustände „keine Daten“, „geringe Qualität“; UI-17 – Pflege der SLA-Ziele.
  - Konfigurationsparameter: `on_time_pickup_tolerance_min`, `on_time_arrival_tolerance_min`, `late_arrival_alert_pct`, `kpi_aggregation_min_n`, `reporting_timezone`.
  - Kennzahlen: `Abholung pünktlich`, `Ankunft pünktlich`, `Mittlere Abholverspätung`, `Mittlere Ankunftsverspätung`, `95%-Abholverspätung`.
- **Detailverträge vorab:** Form der „Ausnahmeregeln“ (z. B. höhere Gewalt, angekündigte Sperrung; im Pflichtenheft nicht definiert, OP-15); Darstellung bei Grenzwertwechsel innerhalb einer Berichtsperiode.
- **Akzeptanzkriterien:**
  1. Ein Bericht zeigt zu jeder SLA-Kennzahl den angewandten Grenzwert und dessen genaue Gültigkeit (von/bis, Regelversion) unmittelbar neben dem berechneten Wert. (CFG-016)
  2. SLA-Ziele und Ausnahmeregeln sind versioniert; nach Änderung der Pünktlichkeitsschwelle für die nächste Saison liefert der Bericht der alten Periode unverändert den alten Grenzwert und Wert. (CFG-016)
  3. Liegt ein Grenzwertwechsel in der Berichtsperiode, weist der Bericht beide Teilzeiträume mit jeweiligem Grenzwert aus statt eines gemischten Werts.
  4. Eine angewandte Ausnahmeregel ist am Bericht mit Anzahl ausgeschlossener Fälle und Regelversion ausgewiesen; Nenner 0 ergibt „nicht berechenbar“.
- **Tests:** positiv: Bericht vor und nach Schwellenwechsel. negativ: rückwirkende Schwellenänderung für abgeschlossene Periode wird nicht auf den historischen Stand angewendet; Bericht für fremden Mandanten. konkurrierend (Datentrennung): Berichtslauf parallel zur Veröffentlichung einer neuen Schwelle – jeder Bericht ist konsistent zu genau einer Version je Zeitraum. Trägt zu E2E-103 und PH-AT-018 bei.
- **Abhängigkeiten:** intern: WP-M8d-03, WP-M8d-05.
  - extern: WP-M4-46, WP-M4-53, WP-M4-54 (Kennzahlregister mit Definitionsversion, Pünktlichkeitskennzahlen)
  - extern: WP-M8c-22, WP-M8c-27, WP-M8c-33 (vollständiger KPI-Katalog, Berichtsjobs, Führung von E2E-103)
- **Entscheidungen:** D-05 (SLA-Zielwerte des Pilots) – nur Produktivwerte.
- **Größe:** M
- **Nicht im Umfang:** KPI-Berechnung selbst (M4/M8c); vertragliche SLA mit Partnern (M6).

### WP-M8d-16 – Benachrichtigungskanäle, Vorlagen, Empfängerrollen und Eskalationsketten
- **Ziel:** Je Ereignistyp sind Kanal, Vorlage, Empfängerrolle und Eskalationskette konfigurierbar; ein Störungsfall erreicht ausschließlich die definierte Bereitschaftsrolle.
- **Anforderungen:** `LH-I.7-CFG-017`
- **Liefergegenstände:**
  - Backend: Modul `communication-support` – Routing Ereignistyp → Empfängerrolle → Kanal → Vorlage; Eskalationskette mit Stufen und Verzögerung; Auflösung der Rolle auf aktuell diensthabende Personen; Ruhezeiten mit Ausnahme für Sicherheitsalarme.
  - Datenbank: `notification`, `notification_attempt`, `channel_template_revision`, `alert_policy_revision` (Eskalationsregel), `membership` (Rolle), `config_revision` (Regelgruppe Benachrichtigung).
  - API: `/v1/notifications` (Zustellstatus), `/v1/config/revisions` (Regelgruppe Benachrichtigung); Vorlagenpflege (Detailvertrag).
  - Oberfläche: UI-17 – Matrix Ereignistyp × Empfängerrolle × Kanal, Vorlagenvorschau, Eskalationsstufen; UI-07 – Anzeige, wer benachrichtigt wurde.
  - Ereignisse/Outbox: Benachrichtigungsauftrag über Outbox; ein fachlicher Auftrag, n Zustellversuche.
  - Konfigurationsparameter: `alert_escalation_delay_min`, `notification_quiet_hours`, `vehicle_unavailability_alert_min`, `customer_notify_before_pickup_min`.
  - Adapter/Worker: Kanaladapter Push/SMS/E-Mail aus `adapters/notification` (bestehend).
- **Detailverträge vorab:** Rollenkatalog mit „Bereitschaftsrolle“ und Bereitschaftsplan-Bezug (OP-16); Vorlagenvariablen ohne unnötige Personendaten; Zustandsautomat Eskalation.
- **Akzeptanzkriterien:**
  1. Ein Störungsfall benachrichtigt nur Personen mit der definierten Bereitschaftsrolle des betroffenen Mandanten/Gebiets; Personen anderer Rollen, Gebiete oder Mandanten erhalten keine Nachricht (Nachweis über `notification`/`notification_attempt`). (CFG-017)
  2. Kanal, Vorlage, Empfängerrolle und Eskalationskette sind je Ereignistyp getrennt konfigurierbar und versioniert. (CFG-017)
  3. Eine ausgelöste Eskalationsstufe n erreicht ausschließlich die für Stufe n konfigurierte Empfängerrolle über den dafür konfigurierten Kanal (Auslösung im Test direkt gesetzt; der Zeitablauf nach `alert_escalation_delay_min` gehört zu WP-M8d-23). Eine Konfiguration, die für einen kritischen Ereignistyp eine Verzögerung größer 0 setzt, wird vor Veröffentlichung abgewiesen (Katalogvalidierung „Kritische Ereignisse ohne Verzögerung“).
  4. Innerhalb `notification_quiet_hours` werden nur Sicherheitsalarme zugestellt; übrige Nachrichten werden nachgeholt oder verworfen laut Regel und nie doppelt versandt.
  5. Eine Vorlagenänderung ändert bereits versandte Nachrichten nicht; jede Nachricht referenziert ihre `channel_template_revision`.
- **Tests:** positiv: Störung → Bereitschaft; Zustellung je Eskalationsstufe an die richtige Rolle. negativ: Empfängerrolle ohne Mitglieder (sichtbarer Konfigurationsfehler statt stiller Nichtzustellung), Vorlage mit unbekannter Variable, Kanal ohne Einwilligung für nicht betriebliche Nachricht. konkurrierend (Sicherheit/Datentrennung): Rollenentzug parallel zur Zustellung – keine Zustellung nach wirksamem Entzug; doppelt zugestelltes Auslöseereignis – eine Nachricht je Empfänger.
- **Abhängigkeiten:** intern: WP-M8d-03, WP-M8d-05.
  - extern: WP-M2-33, WP-M2-34 (Nachrichtenvorlagen und Auslöser)
  - extern: WP-M3-52 (Alarm-Grundlage mit Quittierung als Bezug der Eskalationsstufen)
  - extern: WP-M7b-29, WP-M7b-34 (getrennter Fahrer-/Leitstellenkanal mit quittierbarem Betriebsalarm, Löschung von Benachrichtigungsprotokollen)
  - extern: WP-M6b-29 (austauschbare Provider-Adapter SMS/Push/E-Mail)
- **Entscheidungen:** D-11 (Notbetriebs-/Kontaktprozess, Freigaberollen) – blockiert die Produktivbesetzung der Bereitschaftsrolle, nicht den Bau.
- **Größe:** M
- **Nicht im Umfang:** Fahrgast-Massenkommunikation (M7b); Alarm-Deduplikation und zeitgesteuerte Auslösung der Eskalationsstufen (WP-M8d-23); Zustellung nicht unterdrückbarer Alarme in Ruhezeiten (WP-M8d-24).

### WP-M8d-17 – Konfigurationspaket: Export und Import zwischen Umgebungen
- **Ziel:** Die vollständige Konfiguration einer Test- oder Produktionsumgebung wird als geprüftes Paket exportiert und in eine andere Umgebung importiert; nach Reimport sind wirksame Parameter und Prüfberichte identisch.
- **Anforderungen:** `LH-I.7-CFG-019`
- **Liefergegenstände:**
  - Backend: Modul `config` – Export aller publizierten Revisionen, Regelgruppen, Schemaversionen und Prüfberichte eines Mandanten als Paket mit Manifest, Schema-/Policyversion und Checksumme (Kap. 04 §4.7); Import als Entwurf mit Validierung, Freigabe und Veröffentlichung über den regulären Lebenszyklus; Abbildung umgebungsabhängiger Referenzen (Dienst, Gebiet) über stabile fachliche Schlüssel; keine Secretwerte im Paket.
  - Datenbank: `config_package` (Arbeitsname), `export_job`, `config_revision`, `policy_schema`.
  - API: Paketexport/-import als asynchrone Jobs mit `/v1/operations/{operation_id}` (Pfad im Detailvertrag); Vergleichsbericht „wirksame Parameter vor/nach“.
  - Oberfläche: UI-17 – Export starten, Paket prüfen, Importvorschau mit Differenzliste, Importergebnis.
  - Ereignisse/Outbox: Audit für Export und Import mit Paket-Checksumme.
- **Detailverträge vorab:** Paketformat und Manifest (JSON-Schema), Signatur/Integritätsnachweis, Regel für Referenzabbildung zwischen Umgebungen, Definition „Prüfbericht“ (OP-17).
- **Akzeptanzkriterien:**
  1. Export aus Umgebung 1 und Reimport in eine leere Umgebung 2 ergibt identische wirksame Parameter (feldweiser Vergleich von `/v1/config/effective` über alle Scopes) und identische Prüfberichte; der Vergleichsbericht weist null Abweichungen aus. (CFG-019)
  2. Export → Import → erneuter Export liefert ein Paket mit identischer inhaltlicher Checksumme. (CFG-019)
  3. Ein manipuliertes oder unvollständiges Paket (Checksumme, fehlendes Schema, höhere Schemaversion) wird vollständig abgewiesen; es wird nichts teilweise importiert.
  4. Ein Import verletzt keine systemischen Sicherheitsgrenzen der Zielumgebung und durchläuft Freigabe laut Zielmandant.
  5. Das Paket enthält weder Secrets noch personenbezogene Daten.
- **Tests:** positiv: Rundlauf Test → Produktion (Sandbox) → Test. negativ: Paket eines anderen Mandanten, manipulierte Checksumme, unbekannter Parameter, Import durch Rolle ohne Recht. konkurrierend (Datentrennung/Sicherheit): zwei parallele Importe in denselben Mandanten – genau einer läuft, der andere erhält 409; Import parallel zu manueller Veröffentlichung – keine überlappende wirksame Version. Der Paketexport ist ein Exportendpunkt im Sinn von PH-AT-001 (Zugriff auf bekannte Paket-UUID eines fremden Mandanten liefert weder Daten noch Existenzdetails) und wird in dessen Regressionslauf aufgenommen.
- **Abhängigkeiten:** intern: WP-M8d-05, WP-M8d-07.
  - extern: WP-M6b-20 (Mandantenexport/Datenportabilität)
  - extern: WP-M1-13, WP-M1-15 (`operation`-Ressource, Idempotenzregister)
- **Entscheidungen:** D-02 (Isolationsstufe) – beeinflusst nur die Abbildung globaler Vorlagen, blockiert den Bau nicht.
- **Größe:** M
- **Nicht im Umfang:** Export fachlicher Bewegungsdaten; zentrale Verteilung von Unternehmensvorlagen (`LH-I.7-CFG-023`, P3, M9).

### WP-M8d-18 – Plausibilitätsvorschläge für Stellgrößen
- **Ziel:** Die Oberfläche zeigt aus historischer Nachfrage abgeleitete Vorschläge für Stellgrößen, die erklärt, abgelehnt oder über die reguläre Freigabe übernommen werden können und sich niemals selbst aktivieren.
- **Anforderungen:** `LH-I.7-CFG-020`
- **Liefergegenstände:**
  - Backend: Modul `config` – Vorschlagsobjekt mit Parameter, aktuellem und vorgeschlagenem Wert, Datengrundlage (Zeitraum, Stichprobe, Datenstand) und Begründung; Übernahme erzeugt ausschließlich einen Entwurf im Lebenszyklus; Modul `analytics` – regelbasierte Ableitung aus aggregierten Nachfragedaten.
  - Datenbank: `config_suggestion` (Arbeitsname), `aggregate_snapshot`, `travel_attempt`, `rejection_reason`, `config_revision`.
  - API: Vorschlagsliste, Erklärung, Ablehnen, Übernehmen (Detailvertrag).
  - Oberfläche: UI-17 – Vorschlagskarte mit „Erklären“, „Ablehnen“ (mit Grund), „Über Freigabe übernehmen“; Kennzeichnung als Vorschlag, nicht als wirksamer Wert.
  - Konfigurationsparameter (mögliche Stellgrößen, Auswahl im Detailvertrag): `max_wait_min`, `min_booking_lead_min`, `min_capacity_reserve`, `capacity_reject_alert_count`, `kpi_aggregation_min_n` (Mindestgruppengröße der Datengrundlage).
  - Kennzahlen: `Anfragen`, `Kapazitätsablehnungen`, `Zeitfensterablehnungen`, `Angebotsquote`.
- **Detailverträge vorab:** Vorschlagsregeln (Verfahren im Pflichtenheft nicht beschrieben, OP-18); Mindeststichprobe; Abgrenzung zu lernbasierten Verfahren (AI, P3).
- **Akzeptanzkriterien:**
  1. Ein Optimierungsvorschlag kann erklärt werden: Die Erklärung nennt Datengrundlage, Zeitraum, Stichprobe und die auslösende Regel. (CFG-020)
  2. Derselbe Vorschlag kann abgelehnt werden; Ablehnung und Grund sind protokolliert, der wirksame Wert bleibt unverändert. (CFG-020)
  3. Derselbe Vorschlag kann übernommen werden; dabei entsteht ein Entwurf, der Validierung, Vorschau und Freigabe durchläuft – ohne Freigabe wird nichts wirksam. (CFG-020)
  4. Kein Vorschlag ändert einen wirksamen Wert selbsttätig; ein Test weist nach, dass ohne Benutzeraktion keine Revision entsteht.
  5. Bei Unterschreitung der Mindeststichprobe entsteht kein Vorschlag.
- **Tests:** positiv: Vorschlag aus Fixture-Nachfrage, alle drei Aktionen. negativ: Vorschlag außerhalb des Schemabereichs wird nicht angeboten; Übernahme durch Rolle ohne Änderungsrecht; Vorschlag für fremden Mandanten. konkurrierend (Sicherheit): zwei Benutzer übernehmen denselben Vorschlag parallel – genau ein Entwurf.
- **Abhängigkeiten:** intern: WP-M8d-05, WP-M8d-06.
  - extern: WP-M4-45, WP-M4-51 (Analytics-Faktenbasis und Nachfragetrichter)
  - extern: WP-M8c-11, WP-M8c-22 (vollständige KPI-Definitionen und Kleingruppenschutz)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Nachfrageprognose und lernbasierte Optimierung (AI, M9); Szenariosimulation (SIM, M9).

### WP-M8d-19 – Notfallmodus mit begrenzten, auditierten Soforteingriffen
- **Ziel:** Besonders Berechtigte greifen im Notfall sofort und innerhalb fest definierter Grenzen ein; eine sicherheitsbedingte Sperrzone wirkt unmittelbar und ist später vollständig nachvollziehbar.
- **Anforderungen:** `LH-I.7-CFG-022`
- **Liefergegenstände:**
  - Backend: Modul `config` – Notfalleingriff als eigene, sofort wirksame Revision mit Pflichtgrund, Befristung und Pflicht zur nachträglichen Kontrolle; Katalog zulässiger Notfalleingriffe (Vorschlag dieses Plans, da das Pflichtenheft nur die Sperrzone nennt: ausschließlich verschärfende Maßnahmen wie Sperrzone, Dienst pausieren, Buchungsstopp; verbindlich erst nach D-11, OP-19); automatisches Ende nach Ablauf der Befristung und Überwachung der Nachkontrollfrist über den Ausführungskern (WP-M8d-34); systemische Sicherheitsgrenzen bleiben unüberschreibbar; Modul `service-geo`/`booking-dispatch` – Sperrzone wirkt sofort auf neue Angebote und markiert betroffene bestätigte Fahrten zur Leitstellenbearbeitung.
  - Datenbank: `emergency_intervention` (Arbeitsname), `config_revision` (Kennzeichen Notfall), `service_area`, `audit_event`, `business_alert`.
  - API: Notfalleingriff aktivieren/beenden/nachkontrollieren (Detailvertrag); lesend in `/v1/service-areas` und `/v1/config/effective`.
  - Oberfläche: UI-07 – „Sperrzone sofort aktivieren“ mit Karte und gleichwertiger Listeneingabe, Grund, Befristung; dauerhaft sichtbares Banner „Notfalleingriff aktiv“; UI-20 – Nachvollzug (wer, wann, warum, Wirkung, Nachkontrolle).
  - Ereignisse/Outbox: Ereignis Notfalleingriff aktiviert/beendet; Alarm an definierte Rollen.
- **Detailverträge vorab:** Katalog der zulässigen Eingriffe und ihrer Grenzen; Rolle „besonders Berechtigte“; Frist und Rolle der Nachkontrolle; Verhältnis zur Vier-Augen-Pflicht (nachgelagerte Zweitkontrolle) und zu den temporären Gebietssperren aus WP-M7b-09 (OP-19).
- **Akzeptanzkriterien:**
  1. Eine sicherheitsbedingte Sperrzone lässt sich durch eine besonders berechtigte Rolle sofort aktivieren: Unmittelbar nach der Bestätigung liefern neue Anfragen mit Start oder Ziel in der Zone kein Angebot mit dem Ablehnungsgrund Gebiet/Sperre. (CFG-022)
  2. Der Eingriff ist später nachvollziehbar: Akteur, Zeitpunkt, Grund, Geometrie, Befristung, betroffene Fahrten, Beendigung und Nachkontrolle sind im Audit und in UI-20 abrufbar und unveränderlich. (CFG-022)
  3. Eine Rolle ohne Notfallberechtigung kann den Eingriff nicht auslösen; ein Eingriff außerhalb des Katalogs (z. B. Lockerung einer Sicherheitsgrenze, Abschalten der Pflichtprüfung) wird abgewiesen. (CFG-022)
  4. Nach Ablauf der Befristung endet der Eingriff automatisch; eine fehlende Nachkontrolle erzeugt einen Alarm.
  5. Bereits bestätigte Fahrten in der Zone werden nicht still storniert, sondern der Leitstelle als Arbeitsposten vorgelegt.
  6. Die Sperrzone lässt sich ohne Karte vollständig über die Listeneingabe und per Tastatur aktivieren; das Banner „Notfalleingriff aktiv“ ist für Screenreader als Statusmeldung erkennbar.
  7. Wiederholung des Aktivierungsbefehls mit gleichem `Idempotency-Key` erzeugt keinen zweiten Eingriff und keinen zweiten Auditeintrag.
- **Tests:** positiv: Sperrzone aktivieren, wirken lassen, beenden, nachkontrollieren. negativ: unberechtigte Rolle, fehlender Grund, unzulässiger Eingriffstyp, ungültige Geometrie, Fremdmandant. konkurrierend (Sicherheit/Kapazität): Hold läuft parallel zur Aktivierung – kein Confirm eines Angebots in der Zone nach Wirksamkeit; zwei Berechtigte aktivieren dieselbe Zone parallel – ein Eingriff.
- **Abhängigkeiten:** intern: WP-M8d-05, WP-M8d-07, WP-M8d-34.
  - extern: WP-M7b-09 (temporäre Gebietssperren, verbotene Straßen, Sonderbetriebspläne)
  - extern: WP-M2-04, WP-M2-12 (Zulässigkeitsprüfung Gebiet und Bedienzeit, Bediengebiete als valide Polygone)
  - extern: WP-M6b-19 (Break-glass-Supportzugriff als Vorbild für Anlass, Dauer, Nachkontrolle)
  - extern: WP-M1-12 (Audit append-only)
- **Entscheidungen:** D-11 (Notbetriebsprozess, berechtigte Freigaberollen) – blockiert die Produktivschaltung, nicht den Bau.
- **Größe:** M
- **Nicht im Umfang:** Notbetrieb der Gesamtplattform und Wiederanlauf (M5); Break-glass-Datenzugriff (M6b).

### WP-M8d-20 – Regel-Scope: Mandant, Gebiet, Dienst, Ereignistyp, Schweregrad, Zeitraum, Zuständigkeit
- **Ziel:** Jede Automationsregel ist auf Mandant, Gebiet, Dienst, Ereignistyp, Schweregrad, Zeitraum und Zuständigkeit eingegrenzt; ein Ereignis löst ausschließlich Maßnahmen für tatsächlich betroffene Dienste aus.
- **Anforderungen:** `LH-I.8-AUT-003`
- **Liefergegenstände:**
  - Backend: Regelmaschine des Alarm-/Workflowbausteins (Fortschreibung WP-M3-53) – Scope-Abgleich Ereignis ↔ Regel über alle sieben Dimensionen; Ermittlung betroffener Dienste aus dem Ereignis (Gebiet, Fahrzeug, Dienstbezug), nicht aus der Regel allein; jede Ausführung protokolliert Auslöser, Regelversion, Entscheidung und Folgeaktionen (Fortschreibung WP-M3-55).
  - Datenbank: `alert_policy_revision` (Scope-Felder), `business_alert`, `inbox` (Ereignisdeduplikation), `service`, `service_area`; Incident-Ablage aus M3 (OP-03).
  - API: Regelpflege (Detailvertrag; in WP-M7b-16 über `/v1/config/revisions` geführt), `/v1/operator/incidents`.
  - Oberfläche: UI-17 – Regeleditor mit den sieben Scope-Dimensionen und Vorschau „welche Dienste wären betroffen“; UI-07 – Ablauf am Vorfall.
  - Ereignisse/Outbox: Folgeaktionen nur über Outbox mit expliziter Empfängeradressierung (Kap. 02 §4).
- **Detailverträge vorab:** Regelschema (JSON-Schema) mit Bedingungs- und Aktionskatalog; Modell „Zuständigkeit“ (Rolle, Organisationseinheit, Bereitschaft); Katalog der Ereignistypen und Schweregrade (OP-20).
- **Akzeptanzkriterien:**
  1. Eine Störung in Gebiet A erzeugt Maßnahmen ausschließlich für betroffene Dienste in Gebiet A; für nicht betroffene Dienste in Gebiet B entstehen weder Alarm noch Incident noch Benachrichtigung noch Folgeaktion (Nachweis über leere Ergebnismengen in B). (AUT-003)
  2. Eine Regel berücksichtigt nachweislich jede der sieben Dimensionen: Für jede Dimension existiert ein Testfall, in dem ein nicht passender Wert die Ausführung verhindert. (AUT-003)
  3. Eine Regel von Mandant 1 reagiert nie auf Ereignisse von Mandant 2, auch nicht bei gleichem Gebietsnamen oder geteilter Kooperation.
  4. Ein doppelt zugestelltes Ereignis führt zu genau einer Regelausführung.
  5. Ein Dienst, der beide Gebiete bedient, wird nur für den betroffenen Teil mit Maßnahmen belegt bzw. als „teilweise betroffen“ ausgewiesen (Regel im Detailvertrag).
- **Tests:** positiv: Störung A → Maßnahmen A. negativ: je Dimension ein Nichttreffer; Regel ohne Scope wird nicht veröffentlicht; Ereignis mit manipulierter `tenant_id`. konkurrierend (Datentrennung): 50 parallele Ereignisse aus A und B – keine Maßnahme im falschen Gebiet/Mandanten; Replay desselben Ereignisses – keine zweite Wirkung.
- **Abhängigkeiten:** intern: WP-M8d-02.
  - extern: WP-M3-52, WP-M3-53, WP-M3-55 (Alarm-Grundlage, regelbasierter Workflow, Ablaufprotokoll)
  - extern: WP-M7b-12, WP-M7b-16 (Betriebsstörung Stammdaten/Lebenszyklus, automatische Alarme)
  - extern: WP-M1-09, WP-M1-14 (Outbox/Inbox, Organisationsstruktur Regionen/Dienste)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** einzelne Fachregeln (WP-M8d-25 bis WP-M8d-30); Regel-Freigabeprozess (WP-M8d-21).

### WP-M8d-21 – Regeländerung mit Dry-Run, Freigabe und Versionswechsel
- **Ziel:** Keine Regeländerung wird produktiv, bevor sie einen Test mit Beispieldaten bestanden hat, freigegeben wurde und als neue Version zum definierten Zeitpunkt wirksam wird.
- **Anforderungen:** `LH-I.8-AUT-010`
- **Liefergegenstände:**
  - Backend: Dry-Run-Ausführung einer Regelrevision gegen Beispieldaten bzw. aufgezeichnete Ereignisse ohne Nebenwirkung (keine Outbox, keine Benachrichtigung, keine Sperre); Dry-Run-Bericht mit erwarteten Alarmen/Aktionen; Freigabe und Versionswechsel über den Konfigurationslebenszyklus; laufende Vorgänge bleiben an ihre Regelversion gebunden.
  - Datenbank: `alert_policy_revision` (Status, Dry-Run-Nachweis mit Hash der getesteten Fassung), `config_revision`, `automation_execution` (Arbeitsname; Kennzeichen Dry-Run).
  - API: Dry-Run-Start und -Ergebnis (Detailvertrag); Freigabe/Veröffentlichung analog `/v1/config/revisions/{revision_id}/publish`.
  - Oberfläche: UI-17 – „Dry-Run ausführen“, Ergebnisvergleich alte/neue Regelversion, Freigabestatus; Veröffentlichung gesperrt ohne bestandenen Dry-Run.
  - Ereignisse/Outbox: Ereignis Regelversion gewechselt; Audit.
- **Detailverträge vorab:** Format der Beispieldaten und Erwartungswerte; Kriterium „Dry-Run bestanden“ (OP-21); Zustandsautomat der Regelrevision.
- **Akzeptanzkriterien:**
  1. Eine geänderte Störungskette wird erst produktiv aktiv, nachdem ein Dry-Run mit Beispieldaten bestanden wurde; ein Veröffentlichungsversuch ohne bestandenen Dry-Run der exakt gleichen Fassung wird abgewiesen. (AUT-010)
  2. Nach bestandenem Dry-Run sind zusätzlich Freigabe und Versionswechsel erforderlich; die neue Version trägt Versionsnummer, Freigebenden und Wirksamkeitsbeginn. (AUT-010)
  3. Der Dry-Run erzeugt keinerlei produktive Wirkung (keine Benachrichtigung, kein Incident, keine Sperre, keine Outbox-Zeile).
  4. Wird die Regel nach dem Dry-Run verändert, verfällt der Nachweis.
  5. Ein vor dem Versionswechsel begonnener Vorgang zeigt weiterhin die alte Regelversion.
- **Tests:** positiv: Dry-Run bestanden → Freigabe → aktiv. negativ: Veröffentlichung ohne Dry-Run, mit fehlgeschlagenem Dry-Run, nach Änderung; Selbstfreigabe im Zweistufenmodus. konkurrierend (Sicherheit): Versionswechsel während laufender Ereignisverarbeitung – jedes Ereignis wird von genau einer Regelversion verarbeitet, keine Doppel- und keine Nullverarbeitung.
- **Abhängigkeiten:** intern: WP-M8d-20, WP-M8d-05.
  - extern: WP-M8b-28 (Konfigurationsreview und Rücknahme produktionskritischer Regeln)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Szenariosimulation ganzer Betriebstage (SIM, M9).

### WP-M8d-22 – Alert-Center: Bündelung nach Priorität, Quittieren, Delegieren, Erledigen
- **Ziel:** Ein zentrales Alert-Center zeigt Fahrzeug-, Kosten-, Nachfrage-, Personal- und Schnittstellenalarme nach Priorität; die Leitstelle kann jeden Alarm quittieren, delegieren und erledigen.
- **Anforderungen:** `LH-I.8-AUT-004`
- **Liefergegenstände:**
  - Backend: Alarmaggregat mit Kategorie (Fahrzeug, Kosten, Nachfrage, Personal, Schnittstelle), Priorität, Zustand und Zuständigem; Zustandsübergänge offen → quittiert → delegiert → erledigt (mit Ursache); einheitlicher Eingangsport für alle Fachmodule; rollenabhängige Sicht (Kostenalarme nur für Finanzrollen, Personalalarme ohne Diagnosen).
  - Datenbank: `business_alert`, `alert_acknowledgement`, `alert_policy_revision`; Index `(tenant_id,state,updated_at)`.
  - API: Alarmliste mit Filter/Sortierung nach Priorität, Detail, Befehle Quittieren/Delegieren/Erledigen mit `If-Match` (Pfade im Detailvertrag; M3 nutzt `/v1/operator/incidents`).
  - Oberfläche: UI-07 – Alert-Center als priorisierte Arbeitsliste mit Kategorie-Filtern, Alter, Zuständigem, Regelversion, Aktionen; Status nie nur als Farbe; Live-Aktualisierung über zurückhaltende Live-Region; Rechtezustand „keine Fremdrechte“. Finanzsicht in UI-14/UI-15 verlinkt.
  - Ereignisse/Outbox: Alarm erstellt/quittiert/delegiert/erledigt.
  - Kennzahlen: keine Katalogkennzahl; Bearbeitungszeiten nur als Betriebsmetrik.
- **Detailverträge vorab:** Zustandsautomat Alarm; Prioritätsmodell (Ableitung aus Schweregrad und Kategorie, OP-22); OpenAPI der Alarmpfade; Feldschutz je Kategorie; Verhältnis Alarm ↔ Incident (OP-03).
- **Akzeptanzkriterien:**
  1. Die Leitstelle kann einen Alarm quittieren, an eine andere Person/Rolle delegieren und erledigen; jeder Schritt ist mit Akteur, Zeitpunkt und (bei Erledigung) Ursache in `alert_acknowledgement` unveränderlich gespeichert. (AUT-004)
  2. Das Alert-Center bündelt Alarme der fünf Kategorien Fahrzeug, Kosten, Nachfrage, Personal und Schnittstelle in einer Liste und sortiert sie nach Priorität. (AUT-004)
  3. Ein delegierter Alarm erscheint beim neuen Zuständigen und bleibt für den Delegierenden nachvollziehbar; Delegation an eine Person ohne Recht auf die Kategorie wird abgewiesen.
  4. Ein Benutzer sieht nur Alarme seines Mandanten und seiner berechtigten Kategorien; Kostenalarme enthalten keine fremden Partnerkosten.
  5. Veralteter Stand beim Quittieren ergibt 412 ohne stilles Überschreiben.
  6. UI-07 zeigt die Zustände „keine offenen Alarme“, Ladefehler/Verbindungsabbruch (letzter Datenstand mit Zeitpunkt, keine scheinbar leere Liste), „keine Fremdrechte“ und veralteter Stand jeweils mit Text; Quittieren, Delegieren und Erledigen sind vollständig per Tastatur und Screenreader bedienbar, Priorität und Zustand sind nie nur farblich codiert.
  7. Personalalarme zeigen keine Diagnosen oder Abwesenheitsgründe; Alarmtexte und Benachrichtigungen enthalten keine Fahrgastdaten über das für die Bearbeitung nötige Maß hinaus.
- **Tests:** positiv: je Kategorie ein Alarm durch alle Zustände. negativ: Erledigen ohne Ursache, Delegation an Fremdmandant, unberechtigte Kategorie, Rücksprung erledigt → offen ohne neuen Auslöser. konkurrierend (Sicherheit): zwei Disponenten quittieren denselben Alarm parallel – genau eine Quittierung, der zweite erhält 412; Erledigen parallel zu neuem Vorkommen – Alarm wird nicht fälschlich geschlossen; wiederholter Befehl mit gleichem `Idempotency-Key` erzeugt genau eine Zeile in `alert_acknowledgement`. Ermöglicht E2E-099 (zentraler Alert).
- **Abhängigkeiten:** intern: WP-M8d-02, WP-M8d-20.
  - extern: WP-M3-36, WP-M3-52 (Alarm-Grundlage mit Dedupe und Quittierung, UI-07 Arbeitsliste)
  - extern: WP-M7b-16 (automatische Alarme GPS-Ausfall/überfällige Abholung/Ablehnungshäufung)
  - extern: WP-M6a-15 (Leitstellenalarm aus Reconciliation als Quelle der Kategorie Schnittstelle)
  - extern: WP-M8a-35 (Kostenüberschreitungen als Quelle der Kategorie Kosten)
  - extern: WP-M7d-17 (ungeplanter Ausfall/Ersatzbedarf als Quelle der Kategorie Personal)
- **Entscheidungen:** keine
- **Größe:** L
- **Nicht im Umfang:** Dedupe/Unterdrückung/Eskalation (WP-M8d-23); technische Betriebsalarme der Plattform (WP-M5-04), soweit nicht fachlich relevant.

### WP-M8d-23 – Deduplikation, Unterdrückungsfenster und Eskalationsregel
- **Ziel:** Gleichartige Warnmeldungen werden zu einem Alarmfall zusammengeführt, innerhalb konfigurierter Fenster unterdrückt und bei ausbleibender Bearbeitung nach Regel eskaliert.
- **Anforderungen:** `LH-I.8-AUT-008`
- **Liefergegenstände:**
  - Backend: Deduplikation über fachlichen Fingerprint (Regel, Objekt, Ursache) zusätzlich zum Schlüssel `(policy,event,recipient)` aus Kap. 04 §4.4; Zähler und erstes/letztes Auftreten am Alarmfall; Unterdrückungsfenster je Regel; Eskalationsstufen mit Verzögerung als Zustandsübergang am Alarm, zeitgesteuert über den Ausführungskern (WP-M8d-34); die Zustellung je Stufe an Empfängerrolle und Kanal erfolgt über WP-M8d-16; Wiedereröffnungsregel nach Erledigung.
  - Datenbank: `business_alert` (Fingerprint-Unique je offenem Fall, Vorkommenszähler), `alert_policy_revision` (Fenster, Eskalationsregel), `alert_acknowledgement`.
  - API: Alarmdetail mit Vorkommensliste; Regelpflege der Fenster/Eskalation.
  - Oberfläche: UI-07 – ein Alarmfall mit Zähler „100 Vorkommen“, Zeitspanne, Eskalationsstufe; UI-17 – Fenster und Eskalation je Regel.
  - Konfigurationsparameter: `alert_escalation_delay_min`, `notification_quiet_hours`.
- **Detailverträge vorab:** Fingerprint-Definition je Alarmtyp (OP-12); Zustandsautomat Eskalation; Verhalten bei Ende des Unterdrückungsfensters.
- **Akzeptanzkriterien:**
  1. Hundert gleiche GPS-Fehler desselben Fahrzeugs erzeugen genau einen offenen Alarmfall mit Vorkommenszähler 100, nicht hundert ungeklärte Fälle. (AUT-008)
  2. Innerhalb des Unterdrückungsfensters entsteht nach Quittierung kein neuer Alarmfall und keine neue Benachrichtigung für denselben Fingerprint; nach Fensterende entsteht bei erneutem Auftreten ein neuer bzw. wiedereröffneter Fall. (AUT-008)
  3. Bleibt ein Alarm länger als `alert_escalation_delay_min` unquittiert, wird er an die nächste Stufe eskaliert; die Eskalation ist am Alarm sichtbar. (AUT-008)
  4. Deduplizierte Vorkommen gehen nicht verloren: Jedes Vorkommen ist mit Zeitpunkt nachvollziehbar.
  5. Gleiche Fehler unterschiedlicher Fahrzeuge werden nicht zu einem Fall verschmolzen, sofern die Regel dies nicht ausdrücklich vorsieht.
- **Tests:** positiv: 100 Ereignisse → 1 Fall; Eskalation nach Frist. negativ: Fenster mit negativer Dauer, Eskalationskette ohne Empfänger, Fingerprint-Kollision zwischen Mandanten. konkurrierend (Sicherheit): 100 parallele Ereignisse desselben Fingerprints – genau ein Fall, Zähler exakt 100; Quittierung parallel zur Eskalation – konsistenter Endzustand, keine Eskalationsnachricht nach wirksamer Quittierung. Entspricht dem Contract-Test „100 identische Webhooks“ aus Kap. 05 §5.8 auf Alarmebene.
- **Abhängigkeiten:** intern: WP-M8d-16, WP-M8d-20, WP-M8d-22, WP-M8d-34.
  - extern: WP-M3-52 (Dedupe-Grundlage)
  - extern: WP-M7b-16 (GPS-Ausfall-Alarm ohne Duplikatflut)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Schutz kritischer Ausfälle vor Unterdrückung (WP-M8d-24); Kanalzustellung (WP-M8d-16).

### WP-M8d-24 – Kritische technische Ausfälle sind nicht unterdrückbar
- **Ziel:** Kritische technische Ausfälle bleiben unabhängig von Deduplikation, Unterdrückungsfenstern, Ruhezeiten und Rauschfiltern jederzeit sichtbar und werden zugestellt.
- **Anforderungen:** `LH-I.8-AUT-009`
- **Liefergegenstände:**
  - Backend: Klasse „nicht unterdrückbar“ für definierte kritische technische Alarmtypen; Umgehung von Unterdrückungsfenster, `notification_quiet_hours` und Eskalationsverzögerung; systemische Regel, vom Mandanten nicht abschaltbar; Anbindung der technischen Überwachung (Dispositionsausfall, Outbox-Stau, Partner-/PSP-Ausfall) als Alarmquelle.
  - Datenbank: `alert_policy_revision` (Kennzeichen „nicht unterdrückbar“, nur global änderbar), `business_alert`.
  - API: Alarmliste liefert das Kennzeichen; Versuch, eine solche Regel zu unterdrücken oder zu deaktivieren, 422.
  - Oberfläche: UI-07 – kritischer Ausfall als fest angehefteter Eintrag oberhalb aller Filter; Filter „Rauschen ausblenden“ blendet ihn nicht aus.
  - Konfigurationsparameter: `alert_escalation_delay_min` (Katalog: „kritische Ereignisse ohne Verzögerung“), `notification_quiet_hours` (Katalog: „Sicherheitsalarme ausgenommen“), `vehicle_unavailability_alert_min` (Katalog: „kritische Ausfälle sofort melden“).
- **Detailverträge vorab:** abschließende Liste der kritischen technischen Ausfalltypen (OP-23); Schnittstelle zur Plattformüberwachung aus M5.
- **Akzeptanzkriterien:**
  1. Ein systemischer Dispositionsausfall bleibt bei aktivem Rauschfilter und aktivem Unterdrückungsfenster im Alert-Center sichtbar und wird der Bereitschaft zugestellt. (AUT-009)
  2. Eine Mandantenkonfiguration, die einen kritischen technischen Alarmtyp unterdrückt, stummschaltet oder dessen Regel deaktiviert, wird vor Veröffentlichung abgewiesen. (AUT-009)
  3. Der Alarm wird auch innerhalb `notification_quiet_hours` und ohne Eskalationsverzögerung zugestellt.
  4. Wiederholte Vorkommen werden gezählt, aber der Fall bleibt bis zur Erledigung mit Ursache sichtbar.
  5. Fällt die Alarmzustellung selbst aus (Kanaladapter nicht erreichbar), bleibt der Fall im Alert-Center angeheftet und der Zustellfehler ist sichtbar; es gibt keinen stillen Erfolg.
- **Tests:** positiv: Dispositionsausfall bei aktivem Filter. negativ: Unterdrückungsregel auf kritischen Typ, Deaktivierung durch Mandantenadmin, Quittieren ohne Berechtigung. konkurrierend (Sicherheit): Aktivierung eines Rauschfilters parallel zum Ausfallereignis – Alarm bleibt sichtbar.
- **Abhängigkeiten:** intern: WP-M8d-16, WP-M8d-23.
  - extern: WP-M5-04 (Alerting für kritische Fehlerszenarien und Monitoring)
  - extern: UNGEDECKT – erweiterte NFR-/Betriebsüberwachung; in M8e kein lieferndes WP
- **Entscheidungen:** D-11 (Notbetriebs-/Eskalationsprozess) – blockiert nur die Produktivbesetzung der Empfänger.
- **Größe:** S
- **Nicht im Umfang:** technische Ursachenbehebung, Failover (M5/M8e).

### WP-M8d-25 – Automatischer Prüfauftrag bei Tankung über Limit
- **Ziel:** Eine Tankung über dem konfigurierten Limit erzeugt automatisch einen offenen Prüfcase; der Beleg bleibt unverändert erhalten und wird weder verworfen noch still gebucht.
- **Anforderungen:** `LH-I.8-AUT-005`
- **Liefergegenstände:**
  - Backend: Automationsregel „Tanklimit“ – Auslöser Tankereignis (Beleg, Kartenimport); Vergleich mit Limit je Fahrzeug/Fahrzeugtyp; Erzeugen eines Prüfcase über den Prüfcase-Kern (WP-M8d-33) mit Berechnungsbasis; Beleg wechselt in einen Prüfstatus, Original und Versionen bleiben erhalten; keine automatische Ablehnung.
  - Datenbank: `review_case` (Arbeitsname), `fuel_transaction`, `expense_document`, `document_revision`, `evidence_link`, `business_alert`, `vehicle`.
  - API: `/v1/expenses/{id}` (Prüfstatus, Verweis auf Prüfcase), Prüfcase-Arbeitsliste (Detailvertrag); Fahrerseite: Meldung „zur Prüfung“ statt Fehler.
  - Oberfläche: UI-14 – Prüfcase mit Original links, Limit, Istmenge, Tankgröße, Berechnungsbasis; UI-07/Alert-Center – Kostenalarm.
  - Konfigurationsparameter: `fuel_max_liters`, `fuel_use_alert_pct`, `fuel_price_alert_pct`, `fuel_receipt_required`.
  - Kennzahlen: `Kraftstoff L/100 km`, `Tankbeleg Vollständigkeit`.
- **Detailverträge vorab:** Kopplung des Prüfcase (Zustandsautomat aus WP-M8d-33) an den Belegstatusautomaten (WP-M8a-06); Regel bei fehlender Tankgröße am Fahrzeug.
- **Akzeptanzkriterien:**
  1. Ein Tankbeleg mit Literzahl über `fuel_max_liters` des Fahrzeugs führt zu genau einem offenen Prüfcase; der Beleg samt Original (Hash unverändert) bleibt erhalten und ist am Prüfcase verknüpft. (AUT-005)
  2. Der Beleg wird weder verworfen noch automatisch freigegeben oder gebucht; ohne Entscheidung im Prüfcase entsteht kein gebuchtes Kostenereignis. (AUT-005)
  3. Der Prüfcase zeigt Limit, Istwert, Berechnungsbasis und Regelversion (E2E-070: „Warnung mit Berechnungsbasis, kein unbemerkter Datenverlust“).
  4. Ein Tankkartenimport und ein Belegscan derselben Tankung erzeugen zusammen nur einen Prüfcase.
  5. Die Entscheidung im Prüfcase (plausibel/korrigiert/abgelehnt) ist begründet und protokolliert.
  6. Ein vom Fahrer offline erfasster Tankbeleg über Limit geht bei der Synchronisierung nicht verloren und wird nicht abgewiesen: Er erzeugt denselben Prüfcase, der Fahrer sieht „zur Prüfung“ statt einer Fehlermeldung.
- **Tests:** positiv: Tankung unter und über Limit. negativ: Limit fehlt (Hinweis statt Blockade), Fremdfahrzeug, Prüfcase-Entscheidung durch den Einreicher selbst. konkurrierend (Geld): doppelte Zustellung desselben Tankereignisses – ein Prüfcase, keine doppelte Kostenwirkung; Freigabe-POST parallel zur Prüfcase-Erzeugung – keine Buchung vor Entscheidung. Trägt zu E2E-070 bei; PH-AT-012 (ein Kostenursprung trotz Tankkarte und Belegscan) bleibt grün.
- **Abhängigkeiten:** intern: WP-M8d-20, WP-M8d-22, WP-M8d-33.
  - extern: WP-M4-23, WP-M4-30, WP-M4-31 (Tankbeleg-Fachdaten und Tankplausibilität, unveränderliches Original)
  - extern: WP-M8a-06, WP-M8a-14, WP-M8a-36 (Tankkartenimport, Belegstatusautomat, Kontrollberichte Kraftstoff und Führung von E2E-070)
- **Entscheidungen:** D-06 – nur Produktivwerte/Prüfzuständigkeit.
- **Größe:** M
- **Nicht im Umfang:** Verbrauchsberechnung über Perioden (M8a/M8c); Betrugsbewertung.

### WP-M8d-26 – Kapazitätsempfehlung bei wiederholten Ablehnungen
- **Ziel:** Häufen sich Kapazitätsablehnungen in einem Gebiet, erzeugt das System einen zentralen Alarm und eine prüfbare Empfehlung für zusätzliche Fahrzeuge oder verlängerte Schichten als Szenario – ohne selbst umzuplanen.
- **Anforderungen:** `LH-I.8-AUT-006`
- **Liefergegenstände:**
  - Backend: Automationsregel „Kapazitätsablehnungen“ – Zählung primärer Kapazitätsablehnungen je Gebiet/Zeitfenster aus `travel_attempt` (technische Retries zählen nicht neu); bei Schwellenerreichung Alarm der Kategorie Nachfrage und Empfehlungsobjekt mit Auslöserdaten, Szenario (zusätzliches Fahrzeug bzw. Schichtverlängerung), erwarteter Wirkung und Datenstand; Empfehlung ist Vorschlag, Umsetzung nur durch Leitstelle/Planung.
  - Datenbank: `capacity_recommendation` (Arbeitsname), `travel_attempt`, `rejection_reason`, `business_alert`, `service_area`, `vehicle`, `staff_shift`.
  - API: Empfehlungsliste/-detail, Annehmen/Ablehnen mit Grund (Detailvertrag).
  - Oberfläche: UI-07/Alert-Center – Nachfragealarm mit verknüpfter Empfehlung; Prüfansicht mit den auslösenden Ablehnungen, Gebiet, Zeitfenster, Szenariovergleich.
  - Konfigurationsparameter: `capacity_reject_alert_count`, `min_capacity_reserve`.
  - Kennzahlen: `Kapazitätsablehnungen`, `Personalmangelablehnungen`, `Anfragen`.
- **Detailverträge vorab:** Zähllogik „aufeinanderfolgend“ gegenüber „Anzahl im Zeitfenster“ und Mindeststichprobe (OP-24); Inhalt des Szenarios ohne Simulationsmodul (OP-24); Abgrenzung zur Ablehnungshäufung aus WP-M7b-16.
- **Akzeptanzkriterien:**
  1. Vier aufeinanderfolgende Kapazitätsablehnungen im definierten Gebiet (Fixture: `capacity_reject_alert_count` = 4) lösen genau eine Kapazitätsempfehlung aus, die prüfbar ist: Sie nennt die vier auslösenden Versuche, Gebiet, Zeitfenster, Regelversion und das vorgeschlagene Szenario. (AUT-006)
  2. Die Empfehlung benennt als Szenario zusätzliche Fahrzeuge oder die Ausdehnung von Schichten und führt keine Änderung selbsttätig aus; Annahme oder Ablehnung erfolgt durch einen Menschen und ist protokolliert. (AUT-006)
  3. Gleichzeitig entsteht ein zentraler Alarm im Alert-Center (E2E-099).
  4. Drei Ablehnungen, Ablehnungen aus anderem Grund (z. B. Gebiet, Zeitfenster) oder Ablehnungen in einem anderen Gebiet lösen keine Empfehlung aus.
  5. Weitere Ablehnungen im selben Fenster erhöhen den Zähler der bestehenden Empfehlung statt neue zu erzeugen.
  6. Empfehlung und Alarm zeigen die auslösenden Versuche ohne Fahrgastidentität (Zeit, Gebiet, Primärgrund, Versuchs-ID); der Drilldown zum Versuch setzt das Recht auf die Buchungsdaten voraus.
- **Tests:** positiv: vier Ablehnungen → Alarm + Empfehlung. negativ: drei Ablehnungen, unterbrochene Folge, anderer Primärgrund, Fremdgebiet, technischer Retry. konkurrierend (Kapazität/Datentrennung): vier Ablehnungen treffen parallel ein – genau eine Empfehlung; parallele Folgen in zwei Mandanten – getrennte Empfehlungen. Besteht E2E-099.
- **Abhängigkeiten:** intern: WP-M8d-20, WP-M8d-22.
  - extern: WP-M2-11, WP-M2-13 (`travel_attempt` mit primärem Ablehnungsgrund)
  - extern: WP-M4-48, WP-M4-49, WP-M4-51 (Ablehnungscodes Kapazität/Fahrzeug/Fahrer, Nachfragetrichter)
  - extern: WP-M7b-16 (Alarm Ablehnungshäufung)
  - extern: WP-M7d-23 (Schichtangebote als Umsetzungspfad der angenommenen Empfehlung)
  - extern: UNGEDECKT – Schichtverlängerung als Umsetzungspfad der angenommenen Empfehlung; in M7d kein lieferndes WP
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** automatische Umsetzung der Empfehlung; Nachfrageprognose und Szenariosimulation (M9).

### WP-M8d-27 – Erinnerungen für HU-, Werkstatt- und Leasingfristen
- **Ziel:** Bevorstehende HU-, Werkstatt- und Leasingfristen erzeugen rechtzeitig laut Mandantenregel Erinnerungen mit benannter Zuständigkeit.
- **Anforderungen:** `LH-I.8-AUT-007`
- **Liefergegenstände:**
  - Backend: kalendergesteuerte Automationsregel „Fristen“ – täglicher Lauf je Mandant in dessen Zeitzone über den Ausführungskern (WP-M8d-34; das Nachholprotokoll aus WP-M8d-29 ist SOLL und keine Voraussetzung); Ermittlung fälliger Fristen aus Pflichtnachweisen, Wartungsregeln/Werkstattterminen und Leasingverträgen; Erinnerung als Alarm der Kategorie Fahrzeug mit Zuständigem; einmalig je Frist und Vorwarnstufe.
  - Datenbank: `vehicle`, `vehicle_lease`, `repair_order`, Pflichtnachweis-/Wartungsablagen aus M8a, `business_alert`, `automation_execution` (Arbeitsname).
  - API: Alert-Center-Pfade; lesend Fristübersicht am Fahrzeug (bestehend UI-11-Endpunkte).
  - Oberfläche: UI-11 – Fristliste mit Vorwarnstatus, Zustand „Frist überschritten“; UI-07/Alert-Center – Erinnerung mit Zuständigem.
  - Konfigurationsparameter: `inspection_warning_days`, `maintenance_reminder_km`, `alert_escalation_delay_min`, `reporting_timezone`.
- **Detailverträge vorab:** Vorwarnparameter für Leasing- und Werkstattfristen (im Katalog nur `inspection_warning_days` und `maintenance_reminder_km`, OP-25); Zuständigkeitsregel je Fristart; Abgrenzung zu den Eskalationen aus WP-M8a-25.
- **Akzeptanzkriterien:**
  1. Bei Mandantenregel `inspection_warning_days` = 30 wird eine HU-Frist genau 30 Tage vorher signalisiert: Es entsteht eine Erinnerung mit Fahrzeug, Fristart, Fristdatum und Zuständigem; am Tag davor existiert sie noch nicht. (AUT-007)
  2. Für Werkstatt- und Leasingfristen entstehen Erinnerungen nach derselben Mandantenregel-Logik mit jeweils eigener Zuständigkeit. (AUT-007)
  3. Zwei Mandanten mit unterschiedlicher Vorwarnzeit erhalten ihre Erinnerungen zu unterschiedlichen Zeitpunkten.
  4. Je Frist und Vorwarnstufe entsteht genau eine Erinnerung, auch bei wiederholtem oder nachgeholtem Lauf.
  5. `maintenance_reminder_km` löst die Wartungsvorwarnung kilometerbasiert aus und unterschreitet keine gesetzliche Frist (Katalogvalidierung).
- **Tests:** positiv: Frist in 30 Tagen je Fristart. negativ: Frist ohne Zuständigen (sichtbarer Konfigurationsfehler), bereits erledigte Frist, Fremdmandant, negativer Vorwarnwert. konkurrierend (Sicherheit): zwei Schedulerinstanzen führen denselben Lauf parallel aus – eine Erinnerung je Frist. E2E-071 (fällige HU vor Fahrzeugschicht; führend WP-M8a-25) bleibt grün: Die Erinnerung ersetzt weder Warnung noch Sperre aus M8a.
- **Abhängigkeiten:** intern: WP-M8d-20, WP-M8d-22, WP-M8d-34.
  - extern: WP-M8a-24, WP-M8a-25 (Wartungsregeln, Pflichtnachweise mit Fristen und Eskalation)
  - extern: WP-M7c-35, WP-M7c-50 (Werkstatttermine, Wartungsverträge)
  - extern: WP-M8a-22 (`vehicle_lease` – Leasingverträge mit Vertragsfortschritt; in M7c kein lieferndes WP, geliefert in M8a)
  - extern: WP-M3-03 (Fahrzeugstamm, UI-11)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Freigabesperre bei abgelaufenem Nachweis (WP-M8a-25); Werkstattauftrag erzeugen (WP-M8a-24).

### WP-M8d-28 – Ersatzfahrzeugvorschläge mit Eignungsprüfung
- **Ziel:** Bei Fahrzeugausfall schlägt das System automatisch nur Ersatzfahrzeuge vor, die nach Bedienberechtigung, Fahrerschicht, Checkfreigabe und Standort tatsächlich geeignet sind.
- **Anforderungen:** `LH-I.8-AUT-011`
- **Liefergegenstände:**
  - Backend: Vorschlagsdienst im Störungsablauf – Kandidatenfilter: Bedienberechtigung für Dienst/Gebiet, Ausstattung entsprechend der betroffenen Buchungen (Rollstuhlplatz), gültige Fahrerschicht mit Qualifikation, aktuelle Checkfreigabe (`resource_release`), keine Sperre, Standort/Anfahrtszeit; Begründung je Kandidat und je ausgeschlossenem Fahrzeug; Vorschlag ohne automatische Zuweisung.
  - Datenbank: `vehicle`, `vehicle_assignment`, `staff_shift`, `qualification_revision`, `resource_release`, `inspection_run`, `position_sample`, `vehicle_run`, `booking`.
  - API: `/v1/operator/incidents` (Vorschlagsliste am Vorfall), `/v1/operator/vehicles`.
  - Oberfläche: UI-07 – „Ersatzoptionen prüfen“ mit Eignungsgründen, Standortalter, Anfahrtszeit; Liste gleichwertig zur Karte.
  - Konfigurationsparameter: `vehicle_readiness_require_checks`, `vehicle_unavailability_alert_min`, `workshop_auto_block_severity`.
- **Detailverträge vorab:** Rangfolge der Kandidaten; maximales Standortalter für die Bewertung; Umgang mit Kandidaten ohne aktuelle Position.
- **Akzeptanzkriterien:**
  1. Für ein ausgefallenes Rollstuhlfahrzeug mit betroffener Rollstuhlbuchung enthält die Vorschlagsliste ausschließlich geeignete Kandidaten: rollstuhlgeeignet, bedienberechtigt, mit gültiger Fahrerschicht, aktueller Checkfreigabe und ohne Sperre; ein nicht rollstuhlgeeignetes oder gesperrtes Fahrzeug erscheint nicht. (AUT-011)
  2. Jeder Kandidat nennt die vier geprüften Kriterien Bedienberechtigung, Fahrerschicht, Checkfreigabe und Fahrzeugstandort mit Ergebnis. (AUT-011)
  3. Gibt es keinen geeigneten Kandidaten, zeigt die Liste dies mit Ausschlussgründen statt eines ungeeigneten Vorschlags.
  4. Der Vorschlag weist nicht selbst zu; die Aktivierung erfolgt durch die Leitstelle über den bestehenden Ablauf und prüft die Kriterien im Commit erneut.
  5. Fahrzeuge anderer Mandanten erscheinen nur über einen vertrags-/capability-geprüften Partnervorgang, nie als direkter Kandidat.
- **Tests:** positiv: ein geeigneter, drei ungeeignete Kandidaten. negativ: Kandidat mit abgelaufener Checkfreigabe, ohne Schicht, außerhalb Bedienberechtigung, mit veralteter Position, Fremdmandant. konkurrierend (Kapazität/Sicherheit): Kandidat wird zwischen Vorschlag und Aktivierung gesperrt – Aktivierung wird abgelehnt; zwei Vorfälle beanspruchen denselben Kandidaten parallel – genau eine Zuweisung. PH-AT-020 (sichere Fahrzeugfreigabe vor und während Disposition; Bezug AUT) bleibt grün: Kein Kandidat ohne technische Freigabe, erforderlichen Folgecheck und Fahrer-/Schichtvoraussetzung.
- **Abhängigkeiten:** intern: WP-M8d-20.
  - extern: WP-M3-08, WP-M3-54 (Sperr- und Freigabekern, Störungsablauf mit Ersatzvorschlägen)
  - extern: WP-M7c-38 (Ersatzfahrzeug aus dem Schadenfall aktivieren)
  - extern: WP-M4-17 (Fahrerschicht, Fahrzeugbereitstellung und Disponierbarkeit)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Partnerübernahme (E2E-100, M7/M6); automatische Umbuchung ohne Leitstellenentscheidung.

### WP-M8d-29 – Kalender- und ereignisgesteuerte Regelausführung mit Nachholprotokoll
- **Ziel:** Regeln laufen kalender- oder ereignisgesteuert; versäumte Ausführungen werden protokolliert und nach Wiederanlauf kontrolliert genau einmal nachgeholt.
- **Anforderungen:** `LH-I.8-AUT-013`
- **Liefergegenstände:**
  - Backend: aufbauend auf dem Ausführungskern (WP-M8d-34: persistierte Sollausführung, Lease/Fencing) – Zeitplanpflege je Regel, Erkennung versäumter Ausführungen beim Wiederanlauf; Nachholpolicy je Regel (nachholen, überspringen, zusammenfassen) mit Begrenzung; ereignisgesteuerter Pfad über Inbox.
  - Datenbank: `automation_execution` (Arbeitsname; Zustände geplant/läuft/erfolgreich/versäumt/nachgeholt/übersprungen), `operation` (Lease/Fencing), `alert_policy_revision`, `inbox`.
  - API: Ausführungsprotokoll je Regel (Detailvertrag); Berichtsjobs über `/v1/reports/jobs`, `/v1/reports/jobs/{id}`.
  - Oberfläche: UI-17 – Ausführungsprotokoll mit „versäumt“/„nachgeholt“, Zeitpunkt, Ursache; UI-07 – Hinweis bei versäumter Ausführung.
  - Konfigurationsparameter: `daily_report_time`, `reporting_timezone`.
  - Adapter/Worker: Nachhollauf im Scheduler-Worker aus WP-M8d-34.
- **Detailverträge vorab:** Nachholpolicy und Obergrenzen (OP-26); Zeitplanformat; Verhalten am Sommer-/Winterzeitwechsel (übersprungene bzw. doppelte Ortszeit).
- **Akzeptanzkriterien:**
  1. Ein wegen Ausfall nicht gelaufener nächtlicher Report ist als versäumte Ausführung protokolliert und wird nach Wiederanlauf kontrolliert genau einmal ausgeführt; das Protokoll zeigt Sollzeit, Nachholzeit und Ursache, der Bericht trägt die ursprüngliche Berichtsperiode. (AUT-013)
  2. Regeln lassen sich sowohl kalender- als auch ereignisgesteuert ausführen; beide Auslöserarten sind am Ausführungsprotokoll unterscheidbar. (AUT-013)
  3. Nach längerem Ausfall werden nicht unbegrenzt viele versäumte Läufe gleichzeitig gestartet; die Nachholpolicy begrenzt Anzahl und Reihenfolge.
  4. Eine um 02:30 Ortszeit geplante Regel läuft am Tag der Zeitumstellung genau einmal.
- **Tests:** positiv: planmäßiger Lauf, Ereignisauslösung, Nachholen nach simuliertem Ausfall. negativ: Regel deaktiviert während Ausfall (kein Nachholen), ungültiger Zeitplan, Nachholen über Obergrenze. konkurrierend (Sicherheit): zwei Workerinstanzen starten nach Wiederanlauf parallel – genau eine Ausführung je Solltermin (Lease/Fencing).
- **Abhängigkeiten:** intern: WP-M8d-08, WP-M8d-20, WP-M8d-34.
  - extern: WP-M1-14, WP-M1-15 (`operation`-Ressource mit Lease/Fencing, Outbox/Inbox)
  - extern: WP-M8c-27, WP-M8c-29 (Berichtsjobs und wiederkehrende Berichte mit eigener Nachholregel – nächtlicher Report als ausgeführte Nutzlast; Abgrenzung der beiden Scheduler siehe OP-31)
  - extern: WP-M4-37 (ERP-Export als wiederholsicherer Job als Muster)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Inhalt der Berichte (M8c); Plattform-Wiederanlauf (M5); planmäßige Ausführung ohne Nachholen (WP-M8d-34).

### WP-M8d-30 – Finanzalarme und Liste ungeklärter Buchungen zum Monatsabschluss
- **Ziel:** Finanzalarme entstehen aus offenen Kostenfällen, Budgetüberschreitungen und fehlenden Belegen; der Monatsabschluss zeigt eine vollständige Liste aller ungeklärten Buchungen.
- **Anforderungen:** `LH-I.8-AUT-012`
- **Liefergegenstände:**
  - Backend: Automationsregeln „offener Kostenfall“, „Budgetüberschreitung“, „fehlender Beleg“ als Alarmquellen der Kategorie Kosten; Abfrage „ungeklärte Buchungen der Periode“ (offene Prüfcases, Belege ohne Freigabe, Tankereignisse ohne Beleg, nicht zugeordnete Kosten, offene Differenzen) je Rechtsträger; Verknüpfung in den Abschlussablauf, kein automatisches Wegbuchen (Kap. 03).
  - Datenbank: `business_alert`, `review_case` (Arbeitsname), `fiscal_period`, `economic_event`, `expense_document`, `fuel_transaction`, `journal`, `approval_step`, `cost_object`.
  - API: Liste ungeklärter Buchungen je Periode/Rechtsträger (Detailvertrag); Alert-Center-Pfade.
  - Oberfläche: UI-15/Finanzen – Reiter „Abschluss“ mit Liste ungeklärter Buchungen, Betrag, Alter, Grund, Zuständigem, Drilldown zum Ursprung; Kennzeichnung „3 ungeklärte Belege“ (Kap. 06 §6.6); Finanzalarme im Alert-Center nur für Finanzrollen.
  - Konfigurationsparameter: `cost_overrun_alert_pct`, `finance_close_day`, `fuel_receipt_required`, `approval_stage_thresholds`.
  - Kennzahlen: `Budgetabweichung`, `Tankbeleg Vollständigkeit`, `Offene Rechnungswerte`, `Belegfreigabezeit`.
- **Detailverträge vorab:** abschließende Definition „ungeklärte Buchung“ (OP-27); Verhalten des Abschlusses bei nicht leerer Liste (Hinweis oder Sperre, Abstimmung mit WP-M8a-18).
- **Akzeptanzkriterien:**
  1. Der Monatsabschluss zeigt eine Liste aller ungeklärten Buchungen der Periode; ein Abgleich gegen die Fixture (je ein offener Kostenfall, eine Budgetüberschreitung, ein fehlender Beleg) weist Vollständigkeit nach – kein Fall fehlt, keiner ist doppelt. (AUT-012)
  2. Ein offener Kostenfall, eine Budgetüberschreitung über `cost_overrun_alert_pct` und ein Tankereignis ohne Beleg erzeugen jeweils einen Finanzalarm mit Bezug zum Ursprungsobjekt. (AUT-012)
  3. Wird ein Fall geklärt, verschwindet er aus der Liste und der Alarm wird mit Ursache erledigt; die Summe der Liste stimmt mit den Einzelposten überein (Beträge in kleinster Einheit, kein Gleitkomma).
  4. Die Liste ist je Rechtsträger und Währung getrennt; Nutzer ohne Finanzrolle erhalten keinen Zugriff.
  5. Kein Eintrag wird durch den Abschluss still ausgebucht.
- **Tests:** positiv: drei Alarmarten, Liste zum Abschluss. negativ: Fremdmandant/fremder Rechtsträger, Budget mit Soll 0 (absolute Ausweisung), geschlossene Periode. konkurrierend (Geld): Beleg wird parallel zum Abschlusslauf freigegeben – Liste und Abschlussstand sind konsistent zum selben Stand; doppeltes Budgetereignis – ein Alarm. E2E-073 und PH-AT-015 (geschlossene Periode, späte Gutschrift; führend M8a) bleiben grün: Die Liste verändert keinen abgeschlossenen Stand.
- **Abhängigkeiten:** intern: WP-M8d-12, WP-M8d-22, WP-M8d-33.
  - extern: WP-M8a-14, WP-M8a-18, WP-M8a-34, WP-M8a-35 (Periodenabschluss mit Abschluss-Snapshot und Modul-API „offene Posten der Periode“, Vertragsbudgets, Tankkartenabgleich mit fehlenden Belegen, Kostenüberschreitungen)
  - extern: WP-M4-33, WP-M4-36 (Fiskalperiode und Periodenabschluss, Kostenereignis und Journal)
  - extern: WP-M8c-22 (ausführbare Definitionen `Budgetabweichung`, `Tankbeleg Vollständigkeit`, `Offene Rechnungswerte`)
- **Entscheidungen:** D-06, D-07 – blockieren die Produktivregeln des Abschlusses, nicht den Bau.
- **Größe:** M
- **Nicht im Umfang:** Abschlusslogik und Restatement (WP-M8a-18); Clearingdifferenzen (M6c).

### WP-M8d-31 – Deaktivierbare Automationen mit manuellem Ersatzablauf
- **Ziel:** Jede Automation kann abgeschaltet werden, ohne den manuellen Ablauf zu behindern; die Deaktivierung ist für die Betroffenen sichtbar, und zu jeder Automation existiert ein dokumentierter sicherer manueller Ersatzablauf.
- **Anforderungen:** `LH-I.8-AUT-015`
- **Liefergegenstände:**
  - Backend: Aktiv/Deaktiviert je Regel und Scope über den Regel-Lebenszyklus (Grund, Akteur, optionale Befristung); Architekturregel „manueller Pfad ist nie von der Automation abhängig“ mit Architekturtest; Hinweisobjekt für alle Stellen, an denen die Automation sonst wirkt; Pflichtfeld „manueller Ersatzablauf“ an jeder Regel; nicht deaktivierbare systemische Sicherheitsregeln (z. B. Sperre bei kritischem Mangel) sind ausgenommen und gekennzeichnet.
  - Datenbank: `alert_policy_revision` (Status, Verweis auf Ersatzablauf), `business_alert` (Hinweis), `audit_event`.
  - API: Deaktivieren/Aktivieren einer Regel (Detailvertrag); betroffene Fachendpunkte, z. B. `/v1/vehicles/{id}/release`, `/v1/defects/{id}/triage`, `/v1/expenses/{expense_id}/approve`, liefern den Hinweis „Automation deaktiviert“.
  - Oberfläche: UI-17 – Schalter je Automation mit Grund und Link zum Ersatzablauf; UI-07, UI-11, UI-14 – sichtbarer Hinweis „Automatischer Ablauf deaktiviert – manuell bearbeiten“ mit Verweis auf den Ersatzablauf.
  - Adapter/Worker: Betriebshandbuch-Abschnitt je Automation (dokumentierter manueller Ersatzablauf), versioniert mit der Regel.
- **Detailverträge vorab:** Liste aller Automationen mit Ersatzablauf; Liste der nicht deaktivierbaren Sicherheitsregeln; Bedeutung von „Schadenfreigabe“ in der Abnahme (OP-28).
- **Akzeptanzkriterien:**
  1. Bei deaktiviertem Workflow kann ein berechtigter Mensch die Schadenfreigabe vollständig manuell durchführen; der Vorgang wird nicht blockiert und nicht verzögert. (AUT-015)
  2. An der betroffenen Stelle erscheint ein sichtbarer Hinweis, dass die Automation deaktiviert ist, mit Verweis auf den manuellen Ersatzablauf. (AUT-015)
  3. Für jede aktivierbare Automation ist ein dokumentierter manueller Ersatzablauf hinterlegt; eine Regel ohne Ersatzablauf kann nicht veröffentlicht werden. (AUT-015)
  4. Deaktivierung und Reaktivierung sind mit Akteur, Zeitpunkt und Grund im Audit; während der Deaktivierung aufgelaufene Ereignisse werden nach Reaktivierung nicht unkontrolliert nachgeholt.
  5. Systemische Sicherheitsregeln lassen sich durch Mandantenrollen nicht deaktivieren.
  6. Der Hinweis „Automation deaktiviert“ ist als Text vorhanden (nicht nur Symbol oder Farbe), per Screenreader erreichbar und verlinkt den Ersatzablauf; er erscheint auch in der Arbeitsliste der Rolle, die den manuellen Schritt ausführen muss.
- **Tests:** positiv: Workflow deaktivieren, Schadenfreigabe manuell, Hinweis sichtbar. negativ: Deaktivierung durch unberechtigte Rolle, Deaktivierung einer Sicherheitsregel, Regel ohne Ersatzablauf. konkurrierend (Sicherheit): Deaktivierung während laufender Regelausführung – laufender Vorgang endet definiert (abgeschlossen oder als manuell zu übernehmen markiert), keine halbe Wirkung. PH-AT-020 bleibt grün: Eine deaktivierte Automation hebt keine Sicherheitssperre auf, und ein Reparaturvermerk allein entsperrt weiterhin nicht.
- **Abhängigkeiten:** intern: WP-M8d-20, WP-M8d-21.
  - extern: WP-M3-45, WP-M3-51 (Reparaturauftrag und Wiederfreigabe mit Rollenprüfung, Schadenklassifikation und Triage)
  - extern: WP-M7c-34, WP-M7c-36 (technische Freigabe nach Reparatur, Statusautomat der Schadenakte)
  - extern: WP-M5-26 (Betriebshandbuch und Notbetrieb)
- **Entscheidungen:** D-11 (Notbetriebsprozess, Freigaberollen) – blockiert die fachliche Freigabe der Ersatzabläufe, nicht den Bau.
- **Größe:** M
- **Nicht im Umfang:** Abschalten von Sicherheitsinvarianten; lernbasierte Anomaliehinweise (`LH-I.8-AUT-014`, P3, M9).

### WP-M8d-32 – M8d-Gate-Abnahmesuite
- **Ziel:** Die M8d zugeordneten Referenztests laufen automatisiert gegen einen Build und sind mit Evidenz in `e2e.csv` eingetragen; alle 24 MUSS-Anforderungen der Einheit haben einen grünen Test.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker (Tests): Szenarien in `tests/acceptance` für E2E-099 (führend); Beitragsszenarien für E2E-070 (Prüfcase mit Berechnungsbasis), E2E-102 (Konfiguration und Schichten über Sommerzeitwechsel), E2E-103 (alte SLA-Schwelle reproduzierbar); Regression PH-AT-018, E2E-096, E2E-097, E2E-098 nach Ausbau des Konfigurationsregisters; Regression PH-AT-001 (Isolation über Detail-, Listen-, Such- und Exportendpunkte) für alle in M8d neu eingeführten Endpunkte und Tabellen; Regression PH-AT-020 (Bezug AUT) nach Einführung von Ersatzvorschlägen und deaktivierbaren Automationen; Regression E2E-071, E2E-073, PH-AT-012 gemeinsam mit M8a; UI-Abnahmelauf Tastatur/Screenreader und 412-Konflikt (Kap. 06 §6.9) für UI-07 Alert-Center und UI-17 Lebenszyklus; Nachweisprotokoll je Lauf (`source_key[]`, Build, Migrationsstand, Konfigurationsrevision, Seed, Rollen, Soll/Ist, Trace-IDs; Kap. 08 §8.3).
  - Datenbank: keine neuen Tabellen.
- **Detailverträge vorab:** Testspezifikation je Szenario mit Fixture und Erwartung; Zuordnung der Beitragsszenarien zu den führenden Einheiten M8a und M8c.
- **Akzeptanzkriterien:**
  1. E2E-099 besteht: gehäufte Kapazitätsablehnungen in einem Gebiet erzeugen einen zentralen Alert und einen Kapazitätsvorschlag.
  2. Die Beitragsszenarien zu E2E-070, E2E-102 und E2E-103 bestehen und sind in den führenden Suiten referenziert.
  3. PH-AT-018 sowie E2E-096 bis E2E-098 bestehen weiterhin (keine Regression des M1-Verhaltens); PH-AT-001 besteht für jeden neuen M8d-Endpunkt und jede neue M8d-Tabelle unter der Runtime-Rolle; PH-AT-020 besteht weiterhin.
  4. Jede der 24 MUSS-Anforderungen dieser Einheit ist in `arbeitspakete.csv` mit mindestens einem grünen Testfall verknüpft; die drei SOLL-Anforderungen sind getestet oder mit Beschluss zurückgestellt.
  5. Jede Invariante aus Kap. 04 §4.5, die M8d berührt (keine Cross-Tenant-Referenz, kein Kontext → keine Zeile, nicht überlappende aktive Policy-/Dienstversion, Grantwiderruf sofort wirksam, genau eine Kostenwirkung), ist durch mindestens einen Negativtest mit zwei parallelen Transaktionen nachgewiesen und im Nachweisprotokoll dem WP zugeordnet.
  6. Der UI-Abnahmelauf (Tastatur, Screenreader, Zustände leer/Fehler/keine Rechte/veraltet) besteht für Alert-Center und Konfigurationslebenszyklus.
  7. `e2e.csv` wird ausschließlich aus dem Testlauf mit Build-ID befüllt; Evidenz enthält keine unnötigen Personen- oder Personaldaten.
- **Tests:** positiv: vollständiger Suite-Lauf. negativ: absichtlich verletzte Erwartung markiert das Gate rot. konkurrierend: Suite läuft mit zwei Mandanten parallel ohne gegenseitige Beeinflussung.
- **Abhängigkeiten:** intern: WP-M8d-04 bis WP-M8d-31, WP-M8d-33, WP-M8d-34.
  - extern: WP-M8a-36, WP-M8a-38 (führende Suite E2E-070)
  - extern: WP-M8c-33 (führende Suite E2E-103; Berichtsanteil E2E-102)
  - extern: UNGEDECKT – führende Suite E2E-102; M8c liefert nur den Berichtsanteil, eine führende Einheit ist nicht benannt
  - extern: WP-M8b-26 (Ende-zu-Ende-Testkatalog mit automatischer CI-Protokollierung)
- **Entscheidungen:** keine für den Bau; D-06, D-07, D-11 für die Produktivschaltung der betroffenen Regeln.
- **Größe:** M
- **Nicht im Umfang:** Gate-Tests der übrigen M8-Einheiten; Last- und Restore-Nachweise (M8e).

### WP-M8d-33 – Prüfcase-Kern: Aggregat, Arbeitsliste, Zuweisung, Entscheidung
- **Ziel:** Ein einheitlicher Prüfcase steht allen Regeln dieser Einheit zur Verfügung: Er entsteht idempotent, ist einem Zuständigen zugewiesen, hält den Bezug zu seinem Ursprungsobjekt und wird mit begründeter Entscheidung geschlossen. Ohne dieses WP müssten WP-M8d-12, WP-M8d-25 und WP-M8d-30 den Prüfcase je für sich bauen.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: Modul `documents-finance` – Prüfcase-Aggregat mit Zuständen offen → in Prüfung → entschieden (plausibel/korrigiert/abgelehnt) → geschlossen; interner Port „Prüfcase eröffnen“ mit fachlichem Eindeutigkeitsschlüssel (Ursprungsobjekt, Regel, Regelversion); Zuweisung an Rolle/Person mit Vertretung; Trennung Einreicher ≠ Entscheider; Verknüpfung zum Alarm im Alert-Center.
  - Datenbank: `review_case` (Arbeitsname; Ursprungsbezug über `evidence_link`, Regelversion, Schwelle, Istwert, Berechnungsbasis, Zuständiger, Entscheidung, Begründung), `approval_step`, `business_alert`, `audit_event`; Unique auf dem fachlichen Schlüssel; Index `(tenant_id,state,updated_at)`.
  - API: Prüfcase-Arbeitsliste, Detail, Zuweisen, Entscheiden mit `If-Match` und `Idempotency-Key` (Pfade im Detailvertrag; in Kap. 05 §5.7 nicht benannt, OP-03).
  - Oberfläche: UI-14 – Arbeitsliste „Prüfcases“ mit Alter, Zuständigem, Schwelle/Istwert, Drilldown zum Ursprung; Zustände leer, Ladefehler, kein Recht, veralteter Stand.
  - Ereignisse/Outbox: Prüfcase eröffnet/zugewiesen/entschieden (Arbeitsnamen).
- **Detailverträge vorab:** Zustandsautomat Prüfcase mit verbotenen Übergängen; OpenAPI der Prüfcase-Pfade; Abgrenzung zu `approval_step` (Freigabestufe des Belegs), zum Abgleich-Klärfall aus M8a und zum Korrekturfall des Belegstatusautomaten (WP-M8a-06), damit keine zweite Freigabelogik entsteht (OP-30).
- **Akzeptanzkriterien:**
  1. Zwei Eröffnungen mit gleichem fachlichem Schlüssel ergeben genau einen Prüfcase; eine andere Regelversion oder ein anderes Ursprungsobjekt ergibt einen eigenen.
  2. Jeder Prüfcase hat einen Zuständigen; fehlt die Zuweisungsregel, entsteht ein sichtbarer Konfigurationsfehler statt eines unzugewiesenen Falls.
  3. Die Entscheidung verlangt eine Begründung, ist unveränderlich protokolliert und durch den Einreicher des Ursprungsobjekts nicht möglich (403).
  4. Ein Prüfcase bucht, verwirft oder ändert das Ursprungsobjekt nie selbst; die Kostenwirkung entsteht ausschließlich über den bestehenden Freigabepfad („Genau eine Kostenwirkung“, Kap. 04 §4.5).
  5. Prüfcases sind nur im eigenen Mandanten und Rechtsträger und nur für Finanzrollen bzw. den Zuständigen sichtbar; fremde und nicht vorhandene IDs werden gleichartig beantwortet.
- **Tests:** positiv: Eröffnen, Zuweisen, Entscheiden je Entscheidungsart. negativ: Entscheidung ohne Begründung, durch Einreicher, durch Rolle ohne Finanzrecht, Fremdmandant, manipulierte `tenant_id`, Rücksprung geschlossen → offen. konkurrierend (Geld/Datentrennung): zwei parallele Eröffnungen desselben Schlüssels – ein Fall; zwei parallele Entscheidungen – genau eine wirksam, die zweite erhält 412; Entscheidung parallel zur Belegfreigabe – keine doppelte Kostenwirkung.
- **Abhängigkeiten:** intern: WP-M8d-02.
  - extern: WP-M4-22, WP-M4-34, WP-M4-38 (Belegaggregat und Freigabe-Workflow, UI-14)
  - extern: WP-M8a-06, WP-M8a-11, WP-M8a-35 (Belegstatusautomat, Freigabestufen, Kostenverantwortliche und Weiterleitung)
  - extern: WP-M1-07, WP-M1-08, WP-M1-12, WP-M1-13 (Rollen-/Objektberechtigung und Audit, Idempotenzregister)
- **Entscheidungen:** D-06, D-07 – blockieren nur die Produktivzuständigkeiten, nicht den Bau.
- **Größe:** M
- **Nicht im Umfang:** die auslösenden Regeln (WP-M8d-12, WP-M8d-25, WP-M8d-30); Freigabestufen des Belegs (M8a); Clearing-Klärfälle (M6c).

### WP-M8d-34 – Zeitgesteuerter Ausführungskern für Regeln, Fristen und Eskalationen
- **Ziel:** Zeitabhängige Wirkungen dieser Einheit (täglicher Fristenlauf, Eskalation nach Verzögerung, Ende eines befristeten Notfalleingriffs, Ablauf eines Unterdrückungsfensters) laufen über einen gemeinsamen, mehrinstanzfähigen Ausführungskern genau einmal je Solltermin. Damit hängen die MUSS-Pakete nicht am SOLL-Paket WP-M8d-29.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: persistierte Sollausführung je Regel/Objekt und Zeitpunkt (Mandantenzeitzone, gespeichert als absoluter Zeitpunkt); Lease/Fencing gegen Doppelausführung; injizierbare Uhr; Ausführung im Mandantenkontext der Regel; Zustände geplant → läuft → erfolgreich | fehlgeschlagen; Fehlschläge sichtbar, kein stiller Erfolg.
  - Datenbank: `automation_execution` (Arbeitsname; Unique je Regel/Objekt und Solltermin), `operation` (Lease/Fencing), `alert_policy_revision`.
  - API: keine fachliche; lesender Status für Betrieb (Detailvertrag).
  - Adapter/Worker: Scheduler-Worker im Kern-Deployable; Betriebsmetriken Rückstand und Fehlläufe.
- **Detailverträge vorab:** Schlüssel der Sollausführung; Zeitplanformat; Verhalten bei doppelter bzw. nicht existenter Ortszeit (Abstimmung mit WP-M8d-08); Abgrenzung zum Berichtsscheduler aus WP-M8c-29 und zur zeitgesteuerten Schwellenauswertung aus M3 (OP-31).
- **Akzeptanzkriterien:**
  1. Je Solltermin entsteht genau eine Ausführung, auch bei zwei Workerinstanzen und bei Neustart während der Ausführung.
  2. Jede Ausführung läuft im Mandantenkontext ihrer Regel; ohne Kontext wird nichts gelesen oder geschrieben.
  3. Die steuerbare Uhr aus WP-M8d-04 löst Ausführungen deterministisch aus; eine um 02:30 Ortszeit geplante Ausführung läuft am Tag der Zeitumstellung genau einmal.
  4. Eine fehlgeschlagene Ausführung ist mit Ursache sichtbar und wird nach definierter Regel wiederholt, ohne die fachliche Wirkung zu verdoppeln.
  5. Eine deaktivierte Regel oder ein beendeter Vorgang (quittierter Alarm, beendeter Notfalleingriff) führt zu keiner Ausführung mehr.
- **Tests:** positiv: planmäßige Ausführung mit simulierter Uhr. negativ: ungültiger Zeitplan, Regel eines fremden Mandanten, Ausführung nach Deaktivierung. konkurrierend (Sicherheit/Datentrennung): zwei Workerinstanzen übernehmen denselben Solltermin – genau eine Ausführung (Fencing weist den alten Worker ab, analog PH-AT-009); Ausführungen zweier Mandanten parallel – keine Kontextvermischung.
- **Abhängigkeiten:** intern: WP-M8d-02.
  - extern: WP-M1-01, WP-M1-13, WP-M1-14, WP-M1-15 (`operation`-Ressource mit Lease/Fencing, Outbox/Inbox, RLS-Kontext für Worker)
  - extern: WP-M5-04 (Monitoring und Alerting für Workerrückstand)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Erkennen und Nachholen versäumter Ausführungen mit Protokoll in der Oberfläche (WP-M8d-29); Berichtspläne (WP-M8c-29); die fachlichen Regeln selbst.

## Offene Punkte

1. **OP-01 Zuständigkeit für Parameterschemas.** Der Umsetzungsplan nennt für M8 „restliche Parameter“; welche der 80 Parameter aus `kataloge.csv` von M1, M3, M4, M6, M7, M8a, M8b oder M8d mit Schema geliefert werden, ist nirgends festgelegt (WP-M1-19 verweist pauschal auf spätere Meilensteine). WP-M8d-03 liefert die in dieser Einheit verwendeten Schemas und einen Abdeckungsbericht; die verbindliche Zuordnung ist einheitenübergreifend zu beschließen.
2. **OP-02 Fehlende UI-IDs.** Kap. 06 §6.9 enthält keine eigene ID für Alert-Center (`LH-I.8-AUT-004`), Regelpflege/Dry-Run (`LH-I.8-AUT-010`), Konfigurationspaket (`LH-I.7-CFG-019`), Berechtigungsmatrix (`LH-I.7-CFG-018`), Benachrichtigungskonfiguration (`LH-I.7-CFG-017`) und Notfallmodus (`LH-I.7-CFG-022`). Die Zuordnung zu UI-07, UI-17 und UI-20 ist eine Annahme dieses Plans; der Konfigurationseditor ist nur in Kap. 06 §6.8 beschrieben und trägt dort keine ID.
3. **OP-03 Fehlende Endpunkte und Tabellen für Alarme, Regeln, Incident.** Kap. 05 §5.7 nennt nur `/v1/operator/incidents`; Pfade für Alarmliste, Quittieren/Delegieren/Erledigen, Regelrevisionen, Dry-Run, Empfehlungen, Prüfcases, Konfigurationspakete, Notfalleingriffe und Kalender fehlen. Kap. 04 §4.4 nennt für „Alerts“ nur `alert_policy_revision`, `business_alert`, `alert_acknowledgement`; eine Incident-, Workflow-, Prüfcase- oder Empfehlungstabelle fehlt (Baustein laut Matrix: „Workflow; Alert; Incident; Rule Revision“). Verhältnis Alarm ↔ Incident und die Modulzuordnung des Alarm-/Workflowbausteins (in `umsetzungsplan.md` §3 kein eigenes Modul) sind zu klären; betrifft `LH-I.8-AUT-003` bis `LH-I.8-AUT-015`.
4. **OP-04 „Simulation/Vorschau“ in `LH-I.7-CFG-008`.** Das Szenario-/Simulationsmodul (SIM) ist P3. Angenommen wird, dass „Simulation/Vorschau“ den Wirkungsbericht (`/impact`, „Vorschau auf betroffene zukünftige Angebote“, Kap. 06 §6.8) meint und keine Betriebssimulation.
5. **OP-05 „Kritische Änderung“ und „Freigabeschwellen“ in `LH-I.7-CFG-009`.** Weder Kritikalitätsklassen noch die Form der Schwellen (Betrag, Wertänderung in Prozent, Parameterklasse) sind definiert. Zudem Spannungsverhältnis: `LH-I.7-CFG-009` spricht von einem „gewählten Zweistufenmodus“ (also wählbar), während `kernschema.sql` `CHECK(published_at IS NULL OR approved_by IS NOT NULL)` fordert und WP-M1-22 zusätzlich `approved_by <> created_by` als festen CHECK vorsieht. Ob ein einstufiger Modus mit Selbstfreigabe für unkritische Parameter zulässig ist, muss entschieden werden.
6. **OP-06 Rolle „Konzernadmin“ in `LH-I.7-CFG-018`.** Das Pflichtenheft definiert keinen Rollenkatalog mit Konzern-/Mehrmandantenrolle; Kap. 04 §4.1 betont, dass gleicher Rechtsträger keinen Zugriff auf andere Mandanten begründet. Umfang und technische Abbildung (Mitgliedschaft je Mandant oder delegierte Rolle) sind offen. Ebenso fehlt die Klassifikation „kritischer globaler Parameter“ je Katalogeintrag.
7. **OP-07 Feiertagskalender in `LH-I.7-CFG-021` und `LH-I.7-CFG-011`.** Kap. 04 §4.4 enthält keine Kalendertabelle; Quelle (manuelle Pflege, Import), Geltungsbereich (Land, Bundesland, Region) und Verantwortlicher sind nicht angegeben. Die Abnahme von `LH-I.7-CFG-021` bezieht sich auf Betriebsschichten; ob damit aus Betriebszeiten erzeugte Dienstschichten oder Personalschichten gemeint sind, ist nicht eindeutig.
8. **OP-08 Rollstuhlplätze je Dienst in `LH-I.7-CFG-012`.** `kataloge.csv` enthält keinen Parameter für dienstbezogene Rollstuhlplätze oder eine Rollstuhlreserve (nur `wheelchair_boarding_sec` auf Ebene Fahrzeugtyp und `min_capacity_reserve` in „Plätzen“). Ein neuer Parameter wäre eine Katalogerweiterung. Auch das Zusammenwirken der Reserven zweier Dienste auf demselben Fahrzeug ist nicht geregelt.
9. **OP-09 Rabatt- und Erstattungsregeln in `LH-I.7-CFG-011`.** Kap. 04 §4.4 nennt `tariff_revision`, `tariff_rule`, `fare_component`, `refund_case`, aber keine eigene Regeltabelle für Rabatte und Erstattungen und keine Struktur für Ausnahmetage. Vorrangregel Ausnahmetag/Intervall fehlt.
10. **OP-10 Vier Freigabearten in `LH-I.7-CFG-010`.** `cooperation_grant` kennt `actions`, `service_refs`, `allowed_fields`, `purpose`; eine Unterscheidung nach Daten-, Service-, Tarif- und Zahlungsfreigabe ist dort nicht modelliert. Die Parameter `sharing_visibility_level` und `interoperator_bookings` decken nur Daten- und Buchungsfreigabe ab; für Tarif- und Zahlungsfreigabe existiert kein Katalogparameter. Abgrenzung zu WP-M6b-05 ist abzustimmen.
11. **OP-11 Kostenzuschüsse und Reparaturbudget in `LH-I.7-CFG-013`.** Für „Kostenzuschüsse“ und „Reparaturbudget“ gibt es keinen Katalogparameter (vorhanden: `fuel_use_alert_pct`, `approval_stage_thresholds`, `expense_claim_limit`, `cost_overrun_alert_pct`). Bezugsgröße, Ebene und Zuständiger des „zugewiesenen Prüfworkflows“ sind nicht beschrieben.
12. **OP-12 Deduplikationsschlüssel in `LH-I.8-AUT-008`.** Kap. 04 §4.4 definiert Dedupe `(policy,event,recipient)`. Hundert GPS-Fehler sind hundert verschiedene Ereignisse; mit diesem Schlüssel allein entstünden hundert Alarme. Ein fachlicher Fingerprint (Regel, Objekt, Ursache, Zeitfenster) ist nötig, im Pflichtenheft aber nicht spezifiziert. Dauer der Unterdrückungsfenster und Aufbau der Eskalationsregel fehlen ebenfalls.
13. **OP-13 Geschützte Pflichtfelder und Dokumentenpflichten in `LH-I.7-CFG-015`.** Der Katalog verlangt bei `receipt_mandatory_fields` „steuerlich notwendige Felder geschützt“, nennt die Felder aber nicht; ein Katalog der Belegarten und der Begriff „Dokumentenpflichten“ (Parameter fehlt) sind nicht definiert. Hängt an D-06.
14. **OP-14 Rechtsraumvorlagen in `LH-I.7-CFG-014`.** Das Pflichtenheft enthält keine Werte je Rechtsraum und keinen Verantwortlichen für deren Pflege und rechtliche Freigabe. „Rechtsraum“ ist Ebene der Parameter `max_daily_work_minutes` und `min_rest_hours`, kommt aber in der Präzedenzkette aus Kap. 01 §1.6 (global → Mandant → Region → Dienst → Produkt → Fahrzeug) nicht vor. Für Pausen und Qualifikationsregeln gibt es keinen Katalogparameter. Keine der Entscheidungen D-01 bis D-12 deckt die arbeitsrechtliche Freigabe ausdrücklich ab.
15. **OP-15 „Ausnahmeregeln“ in `LH-I.7-CFG-016`.** Art und zulässiger Umfang von SLA-Ausnahmen (z. B. höhere Gewalt) sowie deren Wirkung auf Zähler und Nenner sind nicht definiert; Abstimmung mit den KPI-Ausschlüssen aus `kataloge.csv` und mit M8c erforderlich.
16. **OP-16 „Bereitschaftsrolle“ in `LH-I.7-CFG-017`.** Es fehlen Rollenkatalog, Bereitschaftsplan (wer hat wann Bereitschaft) und Regel bei unbesetzter Rolle. Abgrenzung der Vorlagenpflege zu WP-M2-33 und WP-M7b-22 ist abzustimmen.
17. **OP-17 Paketumfang in `LH-I.7-CFG-019`.** „Vollständige Test- und Produktionsumgebungen“ und „Prüfberichte“ sind nicht definiert (Validierungs-/Wirkungsberichte oder Abnahmeprotokolle). Offen sind Paketformat, Integritätsnachweis, Umgang mit umgebungsabhängigen IDs (Dienste, Gebiete, Partnerreferenzen), mit Secrets und mit Kooperationsverträgen, sowie ob ein Import in die Produktion die Vier-Augen-Freigabe erneut durchläuft (hier angenommen: ja).
18. **OP-18 Vorschlagsverfahren in `LH-I.7-CFG-020`.** Das Verfahren zur Ableitung der Vorschläge, die betroffenen Stellgrößen und die Mindestdatenbasis sind nicht beschrieben. Abgrenzung zu den P3-Modulen AI/SIM (M9) ist festzulegen; angenommen wird ein regelbasiertes, erklärbares Verfahren ohne Lernanteil.
19. **OP-19 Grenzen des Notfallmodus in `LH-I.7-CFG-022`.** „In Grenzen“ und „besonders Berechtigte“ sind nicht konkretisiert; unklar ist, welche Eingriffe außer der Sperrzone zulässig sind, ob die Vier-Augen-Freigabe durch nachgelagerte Kontrolle ersetzt wird und wie sich der Notfallmodus zu den temporären Gebietssperren aus WP-M7b-09 und zum Break-glass-Zugriff (MUL-018) verhält. D-11 verlangt vom Betreiber den Notbetriebsprozess und die berechtigten Rollen. Zusätzlich fordert `kernschema.sql` `CHECK(published_at IS NULL OR approved_by IS NOT NULL)`: Eine sofort wirksame Notfallrevision braucht entweder einen technisch gesetzten Freigebenden oder eine eigene Ablage außerhalb von `config_revision`; das ist im Detailvertrag (WP-M8d-01) zu entscheiden. Der in WP-M8d-19 genannte Katalog „nur verschärfende Maßnahmen“ ist ein Vorschlag dieses Plans.
20. **OP-20 Ereignistypen, Schweregrade, Zuständigkeit in `LH-I.8-AUT-003`.** Ein Katalog der Ereignistypen und Schweregrade sowie ein Datenmodell für „Zuständigkeit“ fehlen. Unklar ist die Behandlung von Diensten, die mehrere Gebiete bedienen.
21. **OP-21 Dry-Run-Kriterium in `LH-I.8-AUT-010`.** Herkunft der „Beispieldaten“ (synthetisch, anonymisierte Echtdaten) und das Bestehenskriterium sind nicht festgelegt; bei Echtdaten greifen Datenschutzvorgaben.
22. **OP-22 Prioritätsmodell und Kategorien in `LH-I.8-AUT-004`.** Prioritätsstufen und deren Ableitung sind nicht definiert; `business_alert` hat laut Kap. 04 §4.4 weder Kategorie noch Delegationsziel. Abgrenzung zu WP-M3-52 (Quittierung) und WP-M7b-16 (automatische Alarme) ist abzustimmen, damit Quittierlogik nicht doppelt entsteht.
23. **OP-23 Kritische technische Ausfälle in `LH-I.8-AUT-009`.** Eine abschließende Liste fehlt (genannt wird nur der systemische Dispositionsausfall). Unklar ist die Schnittstelle zwischen technischer Plattformüberwachung (WP-M5-04) und fachlichem Alert-Center.
24. **OP-24 Zähllogik und Szenario in `LH-I.8-AUT-006`.** Die Abnahme spricht von „vier aufeinanderfolgenden Ablehnungen“, der Parameter `capacity_reject_alert_count` gilt je „Gebiet/Zeitfenster“ mit „Mindeststichprobe festlegen“; E2E-099 spricht von „häufen sich“. Ob aufeinanderfolgend, im Zeitfenster oder als Quote gezählt wird, ist zu entscheiden. Inhalt des „Szenarios“ ohne das P3-Simulationsmodul ist nicht beschrieben. Überschneidung mit dem Alarm „Ablehnungshäufung“ aus WP-M7b-16.
25. **OP-25 Fristarten in `LH-I.8-AUT-007`.** Der Katalog kennt nur `inspection_warning_days` (HU/Prüffrist, Ebene Mandant) und `maintenance_reminder_km`; für Leasing- und kalenderbasierte Werkstattfristen fehlt ein Vorwarnparameter. Die „30 Tage“ der Abnahme sind ein Beispielwert. Überschneidung mit den Eskalationen aus WP-M8a-25 und den Wartungsregeln aus WP-M8a-24 ist abzugrenzen.
26. **OP-26 Nachholpolicy in `LH-I.8-AUT-013`.** „Kontrolliert ausgeführt“ ist nicht definiert: automatisch oder nach Bestätigung, in welcher Reihenfolge, mit welcher Obergrenze, mit welchem Datenstand. Der „nächtliche Report“ selbst gehört zu M8c.
27. **OP-27 „Ungeklärte Buchungen“ in `LH-I.8-AUT-012`.** Der Begriff ist nicht definiert (offene Prüfcases, nicht freigegebene Belege, fehlende Belege, unzugeordnete Kosten, Clearingdifferenzen?). Ob eine nicht leere Liste den Abschluss sperrt oder nur dokumentiert wird, ist mit WP-M8a-18 und D-06 abzustimmen.
28. **OP-28 „Schadenfreigabe“ in `LH-I.8-AUT-015`.** Unklar, ob die technische Wiederfreigabe des Fahrzeugs nach Schaden, die Freigabe der Schadenmeldung oder die Kostenfreigabe einer Reparatur gemeint ist; angenommen wird die Wiederfreigabe (`/v1/vehicles/{id}/release`). Form und Ablageort des „dokumentierten sicheren manuellen Ersatzablaufs“ sind nicht vorgegeben.
29. **OP-29 Gate-Zuordnung.** Von den M8-Gate-Tests ist nur E2E-099 eindeutig dieser Einheit zuzuordnen. Kein Referenztest deckt Paketexport (`LH-I.7-CFG-019`), Notfallmodus (`LH-I.7-CFG-022`), Berechtigungsmatrix (`LH-I.7-CFG-018`), Dry-Run (`LH-I.8-AUT-010`) oder Nachholausführung (`LH-I.8-AUT-013`) ab; deren Nachweis erfolgt ausschließlich über die Akzeptanzkriterien der jeweiligen WPs.
30. **OP-30 Prüfcase/„Prüfworkflow“ ohne Modell.** `LH-I.7-CFG-013` („zugewiesener Prüfworkflow“) und `LH-I.8-AUT-005` („offener Prüfcase“) setzen ein Prüfobjekt voraus, das weder Kap. 04 §4.4 noch Kap. 05 §5.7 kennt. WP-M8d-33 liefert es als Enabler mit dem Arbeitsnamen `review_case`. Zu klären ist die Abgrenzung zu `approval_step`, zum Abgleich-Klärfall aus M8a (WP-M8a-14) und zum Korrekturfall des Belegstatusautomaten (WP-M8a-06) sowie die Modulzuordnung (`documents-finance` ist eine Annahme).
31. **OP-31 Mehrere Scheduler und MUSS-auf-SOLL-Abhängigkeit.** `LH-I.8-AUT-013` (SOLL) ist die einzige Anforderung, die kalendergesteuerte Ausführung ausdrücklich fordert; die MUSS-Anforderungen `LH-I.8-AUT-007` (Fristen), `LH-I.8-AUT-008` (Eskalation) und `LH-I.7-CFG-022` (Befristung) brauchen sie jedoch. WP-M8d-34 liefert deshalb die planmäßige Ausführung als Enabler, WP-M8d-29 nur Versäumniserkennung und Nachholen. Daneben baut WP-M8c-29 einen eigenen „Scheduler mit Lease; Nachholregel nach Ausfall“ für Berichte, und M3 enthält eine zeitgesteuerte Schwellenauswertung. Ob ein gemeinsamer Ausführungskern entsteht und welche Einheit ihn liefert, ist einheitenübergreifend zu beschließen; der „nächtliche Report“ der Abnahme von `LH-I.8-AUT-013` wäre sonst von zwei Nachholregeln betroffen.
32. **OP-32 Scope-Arten und globale Zeilen in `config_revision`.** `kernschema.sql` definiert `scope_id uuid NOT NULL` und `tenant_id uuid NOT NULL`. Die Ebenen laut `kataloge.csv` (Rechtsraum, Belegart, Mandant/Rolle, Alerttyp, Gebiet/Zeitfenster) haben teils keine UUID-Entität; Rechtsraumvorlagen (`LH-I.7-CFG-014`) und systemische Regeln sind mandantenübergreifend. Abbildung über den Plattformmandanten (M1 OP-08) und über Stammdatenzeilen je Scope-Art ist eine Annahme und im Detailvertrag festzulegen; betrifft auch `LH-I.7-CFG-015`, `LH-I.7-CFG-013`, `LH-I.8-AUT-009`.
33. **OP-33 Aufbewahrung und Löschung.** Für Alarme, Quittierungen, Ausführungsprotokolle, Notfalleingriffe, Vorschläge, Empfehlungen und Konfigurationspakete nennt das Pflichtenheft keine Fristen. Personalalarme und Delegationen enthalten Beschäftigtendaten, Kapazitätsempfehlungen verweisen auf `travel_attempt`. Aufbewahrungsklassen, Legal Hold und Auskunftsumfang sind mit M8e (WP-M8e-15) und D-10 abzustimmen; betrifft `LH-I.8-AUT-004`, `LH-I.8-AUT-006`, `LH-I.7-CFG-022`.
34. **OP-34 Offline erfasste Belege und Pflichtfelder.** Für `LH-I.7-CFG-015` und `LH-I.8-AUT-005` ist nicht geregelt, gegen welche Feldmengen- bzw. Limitrevision ein offline erfasster Beleg geprüft wird (Erfassungszeitpunkt oder Synchronisierung) und was der Fahrer bei nachträglich fehlendem Pflichtfeld sieht. WP-M8d-25 legt nur fest, dass der Beleg nicht verloren geht; die Revisionsregel gehört in den Detailvertrag von WP-M8d-13.
35. **OP-35 Reihenfolgekonflikt: Zyklus WP-M8d-05 ↔ WP-M8b-28 innerhalb von M8.** Reihenfolgekonflikt: WP-M8d-05 hängt extern von WP-M8b-28 (Verantwortliche und Rollback-Anweisung produktionskritischer Regeln) ab, während WP-M8b-28 seinerseits von WP-M8d-05 (Entwurf, Vorschau, Freigabe, Rücknahme und Wirkungsbericht der Konfiguration) abhängt (siehe M8b, Offener Punkt 27); über WP-M8d-05 ist auch WP-M8d-21 → WP-M8b-28 betroffen. Die Abhängigkeit wurde bei der Auflösung nicht verbogen; welche Einheit die Verantwortlichen-/Rollback-Felder zuerst liefert bzw. wie die Lieferung geschnitten wird, ist einheitenübergreifend zu beschließen.
