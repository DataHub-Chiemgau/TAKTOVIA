# M7b – Leitstelle, Support und Kommunikation

- **Meilenstein:** M7 – Betriebsausbau: Fahrgast, Leitstelle, Fahrer, Personal
- **Phase:** P2 (13 MUSS, 17 SOLL; Originalphase und -priorität unverändert)
- **Anforderungen:** 30 (OPS 11: `OPS-007` bis `OPS-014`, `OPS-016` bis `OPS-018`; COM (6.12) 9: `COM-004` bis `COM-012`; SUP 10: `SUP-001` bis `SUP-010`)
- **Arbeitspakete:** 36 (30 mit Anforderungen, 6 Enabler)

**Ziel:** Nach M7b disponiert die Leitstelle Fahrten kontrolliert auf andere Fahrzeuge um, sieht vorab die Auswirkungen, aktiviert befristete Sperren und führt Störungen in einem Incident-Posteingang von der Eröffnung bis zum Abschluss. Das Callcenter (UI-06) findet verifizierte Fahrgäste, bucht über denselben Regel- und Kapazitätskern wie App und Web und bearbeitet Supportfälle samt Erstattung mit vollständiger Ereignisfolge. Die Kommunikation erreicht Zielgruppen nach Dienst, Gebiet und Betroffenheit, trennt Kunden- von Fahrerkanälen, weist jeden Zustellstatus aus und hält Einwilligung, Ruhezeiten und Aufbewahrungsregeln automatisiert ein.

**Hinweis zum Zuschnitt:** M7b baut auf dem P1-Stand auf: Livebetrieb, Auftragsliste, manuelle Umplanung mit Plan-CAS, Alarm-Grundlage und Incident bei Fahrzeugausfall aus M3 sowie Nachrichtenversand, Vorlagen und Auslöser aus M2. Serienbuchung, Buchung für Dritte (`/v1/delegations`), Sperrbereiche und Routingrestriktionen liefert M7a, Notfallknopf und Fahrer-Push M7c, Dienstplandaten M4/M7d. M6 und M8 sind laut Umsetzungsplan von M7 unabhängige Parallelströme; M7b darf deshalb von M6a (`transfer_hub`) und M8e (Gastbuchung, dokumentierter Vertretungszugriff) nicht hart abhängen – die betroffenen WPs (WP-M7b-10, WP-M7b-17, WP-M7b-19) liefern den für ihre Abnahme nötigen Mindestumfang selbst bzw. weisen einen definierten Leerzustand aus (siehe OP-10, OP-23). Wo eine Originalabnahme diese Fähigkeiten braucht, ist das als EXTERN-Abhängigkeit ausgewiesen. Kap. 04 §4.4 enthält für Incident, Kampagne, Banner, Betriebsjournal, temporäre Sperre und Relay-Sitzung keine Tabellen; die hier verwendeten Bezeichnungen dafür sind ausdrücklich Arbeitsnamen, die in WP-M7b-01 verbindlich festgelegt werden (siehe Offene Punkte). Ereignisnamen sind ebenfalls Arbeitsnamen.

## Übersicht

| WP-ID | Titel | Anforderungen (Anzahl) | Größe | hängt ab von |
|---|---|---:|---|---|
| WP-M7b-01 | Detailverträge Leitstelle, Support, Kommunikation | – (Enabler) | M | WP-M1-03, WP-M1-17, WP-M1-07, WP-M2-01, WP-M3-01 |
| WP-M7b-02 | Migration der M7b-Tabellen mit RLS | – (Enabler) | M | WP-M7b-01, WP-M0-16, WP-M0-15, WP-M0-18, WP-M1-01 |
| WP-M7b-03 | Referenzfixtures und Kommunikations-Sandbox | – (Enabler) | S | WP-M7b-01, WP-M0-22, WP-M2-32 |
| WP-M7b-04 | Kontrollierte Umdisposition auf ein anderes Fahrzeug | 1 | M | WP-M7b-01, WP-M2-16, WP-M2-22, WP-M2-23, WP-M2-14, WP-M3-40, WP-M3-08 |
| WP-M7b-05 | Auswirkungsvorschau einer manuellen Routenänderung | 1 | M | WP-M7b-04 |
| WP-M7b-06 | Audit-Trail kritischer Dispositionsentscheidungen | 1 | S | WP-M7b-04, WP-M1-12 |
| WP-M7b-07 | Schicht- und Dienstplanübersicht der Leitstelle | 1 | M | WP-M7b-01, WP-M3-07, WP-M4-03, WP-M4-04, WP-M4-07, WP-M4-16, WP-M7d-10, WP-M7d-12 |
| WP-M7b-08 | Buchungsdetail mit Statushistorie, Fahrzeugereignissen und Kommunikation | 1 | M | WP-M7b-24, WP-M2-25, WP-M2-32, WP-M3-29, WP-M3-31, WP-M3-37 |
| WP-M7b-09 | Temporäre Gebietssperren, verbotene Straßen und Sonderbetriebspläne | 1 | M | WP-M7b-02, WP-M1-22, WP-M1-18, WP-M1-15, WP-M7a-21, WP-M7a-22 |
| WP-M7b-10 | Getrennt schaltbare Kartenlayer | 1 | S | WP-M7b-09, WP-M1-09, WP-M2-04, WP-M2-05, WP-M3-36, WP-M6a-08, WP-M6b-02, WP-M6b-10 |
| WP-M7b-11 | Betriebsjournal je Schicht mit Export | 1 | M | WP-M7b-02, WP-M7b-06, WP-M7b-12, WP-M1-12, WP-M1-11, WP-M8c-27 |
| WP-M7b-12 | Betriebsstörung: Stammdaten und Lebenszyklus | 1 | M | WP-M7b-02, WP-M3-53, WP-M3-52 |
| WP-M7b-13 | Incident-Posteingang mit Zuweisung | 1 | M | WP-M7b-12, WP-M3-35, WP-M3-52 |
| WP-M7b-14 | Fahrzeugausfall: Sperre, betroffene Fahrten, Wiederherstellung | 1 | M | WP-M7b-04, WP-M7b-12, WP-M2-26, WP-M2-31, WP-M2-27, WP-M3-54, WP-M3-08 |
| WP-M7b-15 | Notfallmeldung aus der Fahrer-App: priorisierte Anzeige und Eskalation | 1 | M | WP-M7b-12, WP-M7b-13, WP-M3-52, WP-M3-29, WP-M3-35, WP-M7c-06 |
| WP-M7b-16 | Automatische Alarme: GPS-Ausfall, überfällige Abholung, Ablehnungshäufung | 1 | M | WP-M7b-12, WP-M7b-13, WP-M1-21, WP-M1-14, WP-M3-52, WP-M3-34, WP-M3-42, WP-M4-48 |
| WP-M7b-17 | Callcenter: Fahrgast auffinden oder Gastbuchung anlegen | 1 | M | WP-M7b-01, WP-M7b-02, WP-M7b-03, WP-M7b-35, WP-M1-28, WP-M1-07, WP-M1-31, WP-M1-12, WP-M1-02, WP-M7a-11 |
| WP-M7b-18 | Callcenter-Buchung über den gemeinsamen Regel- und Kapazitätskern | 1 | M | WP-M7b-17, WP-M1-13, WP-M1-02, WP-M2-21, WP-M2-22, WP-M2-23, WP-M2-24, WP-M2-08, WP-M3-39, WP-M7a-12 |
| WP-M7b-19 | Callcenter: Serien, Vertretungsbuchungen und Sonderbedarfe | 1 | M | WP-M7b-18, WP-M1-07, WP-M1-31, WP-M7a-07, WP-M7a-11, WP-M7a-10 |
| WP-M7b-20 | Supportfall mit Verknüpfungen und Ereignisfolge | 1 | M | WP-M7b-02, WP-M7b-08, WP-M7b-12, WP-M1-07, WP-M1-08, WP-M1-12, WP-M1-31 |
| WP-M7b-21 | Zentraler Supportlink zwischen Beschwerde, Buchung und Incident | 1 | S | WP-M7b-08, WP-M7b-13, WP-M7b-20 |
| WP-M7b-22 | Supportvorlagen für Beschwerden, Verspätungen und Zahlungen | 1 | S | WP-M7b-20 |
| WP-M7b-23 | Erstattungsfall: berechtigen, prüfen, genehmigen, ausführen | 1 | M | WP-M7b-02, WP-M7b-20, WP-M0-23, WP-M1-13, WP-M1-15, WP-M1-07, WP-M2-29, WP-M2-30, WP-M2-31, WP-M4-33, WP-M4-36 |
| WP-M7b-24 | Kommunikationsauftrag: Zweck, Einwilligungs-Gate, Empfängerauflösung | – (Enabler) | M | WP-M7b-02, WP-M7b-03, WP-M1-30, WP-M1-28, WP-M1-14, WP-M2-32, WP-M2-33, WP-M2-34 |
| WP-M7b-25 | Kommunikationsstatus und Provider-Rückmeldungen | 1 | M | WP-M7b-24, WP-M1-16, WP-M1-14, WP-M2-32 |
| WP-M7b-26 | Massen-Push nach Dienst, Gebiet und Fahrgastgruppe | 1 | M | WP-M7b-24, WP-M7b-25, WP-M1-08, WP-M1-30, WP-M2-03, WP-M2-04, WP-M2-32 |
| WP-M7b-27 | In-App-Banner mit Priorität, Beginn und Ende | 1 | M | WP-M7b-24, WP-M2-35, WP-M2-37, WP-M2-43, WP-M7a-37, WP-M7a-38 |
| WP-M7b-28 | SMS-Kundenkontakt für verifizierte Nummern | 1 | M | WP-M7b-24, WP-M7b-25, WP-M1-28, WP-M1-30, WP-M2-32 |
| WP-M7b-29 | Getrennter Fahrer- und Leitstellenkanal mit quittierbarem Betriebsalarm | 1 | M | WP-M7b-02, WP-M7b-24, WP-M7b-25, WP-M3-24, WP-M3-29, WP-M7c-04, WP-M7c-03 |
| WP-M7b-30 | Anonymisierte, relay-basierte Buchungskommunikation | 1 | M | WP-M7b-24, WP-M1-35, WP-M3-26, WP-M3-32 |
| WP-M7b-31 | Ruhezeiten für nichtkritische Meldungen | 1 | S | WP-M7b-24, WP-M1-21, WP-M1-18 |
| WP-M7b-32 | Betroffene Fahrgäste gezielt informieren | 1 | M | WP-M7b-12, WP-M7b-24, WP-M7b-25, WP-M3-54, WP-M3-37 |
| WP-M7b-33 | Vorformulierte Incident-Information mit Empfängerprüfung | 1 | S | WP-M7b-32 |
| WP-M7b-34 | Begrenzte Protokollierung und automatische Löschung von Benachrichtigungen | 1 | M | WP-M7b-24, WP-M7b-25, WP-M1-33, WP-M1-32 |
| WP-M7b-35 | Callcenter-Arbeitsplatz UI-06: Rahmen, Rollennavigation und Zustände | – (Enabler) | S | WP-M7b-01, WP-M7b-02, WP-M1-07, WP-M1-06, WP-M2-35, WP-M3-36 |
| WP-M7b-36 | M7b-Gate-Abnahmesuite | – (Enabler) | M | WP-M7b-04 bis WP-M7b-35, WP-M0-22, WP-M0-24, WP-M0-23, WP-M7a-07, WP-M7a-12 |

## Arbeitspakete

### WP-M7b-01 – Detailverträge Leitstelle, Support, Kommunikation
- **Ziel:** Alle Verträge, Zustandsautomaten und Ereigniskataloge der Einheit liegen geprüft vor, sodass die Fach-WPs vertragsgetrieben beginnen können (Leitlinie „Vertrag vor Code", Kap. 08 §8.4).
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - API: OpenAPI-3.1-Ergänzungen im Modul `communication-support` und `booking-dispatch` für `/v1/operator/incidents`, `/v1/operator/vehicles`, `/v1/runs/{id}/revisions` (Vorschau- und Umdispositionsmodus), `/v1/notifications`, `/v1/support/cases`, `/v1/passengers` (Callcenter-Suche), `/v1/delegations`; Unterressourcen (Kampagne, Banner, Empfängervorschau, Erstattungsfall, Journalexport, temporäre Sperre) als Vertragsvorschlag mit Negativbeispielen nach Kap. 05 §5.1.
  - Backend: Zustandsautomaten Betriebsstörung (eröffnet/eskaliert/gelöst/geschlossen), Supportfall, Erstattungsfall (berechtigt/geprüft/genehmigt/ausgeführt), Kommunikationsauftrag und Zustellversuch (erstellt/versendet/zugestellt/fehlgeschlagen/unbekannt), Kampagne, Banner, temporäre Sperre.
  - Ereignisse/Outbox: Ereigniskatalog der Einheit mit Schemaversion, Dedupe-Schlüsseln und PII-Klassifikation.
  - Datenbank: DDL-Entwurf für `support_case`, `refund_case`, `notification`, `notification_attempt`, `channel_template_revision`, `alert_policy_revision`, `business_alert`, `alert_acknowledgement` sowie Benennung und DDL der in Kap. 04 §4.4 fehlenden Ablagen (Incident, Kampagne, Banner, Betriebsjournal, temporäre Sperre, Relay-Sitzung).
  - Backend (Rechte): Rechtematrix Rolle × Aktion × Objektbereich für alle neuen Aktionen der Einheit (Umdisposition, temporäre Sperre, Störung eskalieren/schließen, Posteingang zuweisen, Fahrgastsuche Callcenter, Gastanlage, Supportfall lesen/verknüpfen, Erstattung prüfen/genehmigen/ausführen, Kampagne freigeben, Banner veröffentlichen, SMS senden, Betriebsalarm senden, Journal exportieren, Löschregeln einsehen) mit den Rollen Callcenter, Support, Disponent, Schichtleitung, Leitung, Prüfer; Modulzuordnung der Schreibrechte (`refund_case` führt laut Kap. 01 §1.3 das Modul `fare-payment`, nicht `communication-support`).
  - Oberfläche: Zustandskatalog je neuer Ansicht in UI-06, UI-07, UI-20 (leer, lädt, Fehler, keine Rechte, veralteter Datenstand, 412-Konflikt) und Liste der Pflichtaktionen mit Tastaturalternative (Kap. 06 §6.1).
  - Konfigurationsparameter: JSON-Schemas bzw. Bestätigung der vorhandenen Schemas für `notification_quiet_hours`, `alert_escalation_delay_min`, `capacity_reject_alert_count`, `vehicle_unavailability_alert_min`, `eta_max_data_age_sec`, `late_arrival_alert_pct`, `max_reassignments`, `protected_stop_horizon_min`, `min_capacity_reserve`.
- **Detailverträge vorab:** dieses WP erzeugt sie; Eingang sind Kap. 05 §5.7, Kap. 04 §4.4, Kap. 06 §6.5/§6.9 und die Offenen Punkte dieser Datei.
- **Akzeptanzkriterien:**
  1. Die ergänzte OpenAPI ist als 3.1 parsbar, alle `$ref` lösen auf, `operationId`s sind eindeutig, Beispiele schemagültig (Kap. 05 §5.8).
  2. Jede der 13 MUSS-Anforderungen der Einheit ist in `arbeitspakete.csv` mit Detailvertrag und mindestens einem positiven Testfall verknüpft.
  3. Jeder Zustandsautomat nennt erlaubte Übergänge, Rolle, Pflichtbegründung und verbotene Übergänge.
  4. Die Arbeitsnamen der nicht im Pflichtenheft benannten Tabellen sind durch freigegebene Namen ersetzt oder als Abweichung dokumentiert.
  5. Für jeden Offenen Punkt dieser Datei liegt eine dokumentierte Entscheidung oder eine benannte Annahme mit Verantwortlichem vor.
  6. Die Rechtematrix deckt jede schreibende Operation der ergänzten OpenAPI ab; keine Operation ist ohne benannte Rolle und ohne Mandanten-/Dienstbezug.
  7. Jede der 17 SOLL-Anforderungen ist entweder mit Detailvertrag eingeplant oder als SOLL-Abweichung mit Begründung zur AG-Freigabe vorgemerkt (Kap. 08 §8.4).
- **Tests:** Vertragsprüfung in CI (`validate_artifacts.py`, OpenAPI-Lint); Negativ: Beispiel mit fremder `tenant_id` und fehlendem Idempotenzschlüssel ist im Vertrag als Fehlerfall beschrieben.
- **Abhängigkeiten:** intern: keine.
  - extern: WP-M1-03, WP-M1-17, WP-M1-07 (OpenAPI je Modul, Contract-Test-Pipeline, Zustandsautomaten-Rahmen, Rollenmodell)
  - extern: WP-M2-01 (bestehende Verträge Nachrichtenversand, Vorlagen, Zahlung/Erstattung nach Storno)
  - extern: WP-M3-01 (bestehende Verträge Incident, Alarm und manuelle Umplanung)
- **Entscheidungen:** D-11 (Aufbewahrung, zulässiger Supportzugriff) und D-01 (Supportverantwortung) fließen ein; blockieren nicht den Bau der Verträge, nur die Produktivschaltung der betroffenen Funktionen.
- **Größe:** M
- **Nicht im Umfang:** Implementierung; Verträge für Serienbuchung, Sperrbereiche und Fahrer-Notfallknopf (M7a, M7c).

### WP-M7b-02 – Migration der M7b-Tabellen mit RLS
- **Ziel:** Alle Tabellen der Einheit sind per Flyway migriert, mandantengetrennt und unter der Laufzeitrolle geprüft.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Datenbank: Neuanlage bzw. Erweiterung von `support_case`, `refund_case`, `notification`, `notification_attempt`, `channel_template_revision`, `alert_policy_revision`, `business_alert`, `alert_acknowledgement` (soweit durch M2/M3 noch nicht vollständig); neue Ablagen mit Arbeitsnamen für Incident-Erweiterung, Kampagne, Banner, Betriebsjournal, temporäre Sperre, Relay-Sitzung; je Tabelle Primärschlüssel `(tenant_id,id)`, zusammengesetzte FKs zu `booking`, `vehicle`, `vehicle_run`, `staff_shift`, `driver`, `passenger_private`, `payment_intent`, `service`, `service_area`; RLS USING/WITH CHECK, Revision, Audit. `refund_case` wird im Schema des Moduls `fare-payment` geführt (Kap. 01 §1.3); `support_case` verweist darauf, schreibt aber nicht hinein.
  - Backend (Rechte): Anlage der Berechtigungen und Rollenzuordnungen aus der Rechtematrix (WP-M7b-01) als migrierte Stammdaten; DML-Rechte der Laufzeitrolle je Modulzugang nur für die eigenen Tabellen (Kap. 04 §4.6).
  - Invarianten (Kap. 04 §4.5): keine lokale Cross-Tenant-Referenz; kein Mandantenkontext → keine Zeile; Dedupe `(policy,event,recipient)` für `business_alert`; Erstattung als Gegenereignis statt Änderung.
- **Detailverträge vorab:** DDL aus WP-M7b-01; Index- und Partitionierungsvorschlag für `notification` und `notification_attempt` (Kap. 04 §4.7).
- **Akzeptanzkriterien:**
  1. Migration läuft auf leerer Datenbank und aus dem freigegebenen Vorstand fehlerfrei.
  2. Unter der Laufzeitrolle ohne Tenantkontext liefert jede neue Tabelle keine Zeile; Einfügen mit fremder `tenant_id` scheitert.
  3. Eine Verknüpfung eines Supportfalls oder einer Benachrichtigung mit einer Buchung eines anderen Mandanten scheitert am zusammengesetzten FK.
  4. Der Architekturtest bestätigt: nur das besitzende Modul schreibt die jeweilige Tabelle.
  5. Jede Berechtigung der Rechtematrix existiert nach der Migration; die Laufzeitrolle besitzt auf den neuen Tabellen weder TRUNCATE noch DDL noch Tabelleneigentum.
  6. Journal-, Audit- und Statushistorien-Ablagen der Einheit lehnen UPDATE und DELETE ab (append-only).
- **Tests:** Positiv: Migration frisch/aus Vorstand. Negativ: fehlender Tenantkontext, Cross-Tenant-FK, UPDATE/DELETE auf append-only-Ablage, Zugriff nach Wiederverwendung der Poolverbindung ohne neuen Kontext (PH-AT-001-Muster). Konkurrierend: zwei parallele Transaktionen fügen denselben Alarm-Dedupe-Schlüssel ein → genau eine Zeile.
- **Abhängigkeiten:** intern: WP-M7b-01.
  - extern: WP-M0-16, WP-M0-15, WP-M0-18 (Flyway-Basis, getrennte DB-Rollen, Testharness)
  - extern: WP-M1-01 (Tenantkontext, RLS-Laufzeitbasis, Isolations-Testharness)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** `booking_series`, `series_occurrence` (M7a); `delegated_access`, `consent_revision` (M1/M8e); `retention_rule`, `erasure_job` (M1, Nutzung in WP-M7b-34).

### WP-M7b-03 – Referenzfixtures und Kommunikations-Sandbox
- **Ziel:** Deterministische Testdaten und steuerbare Kanalsimulatoren stehen für alle WPs der Einheit bereit.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: Push- und SMS-Providersimulator im `notification`-Adapter mit steuerbaren Antworten (zugestellt, Fehler, Timeout, verspätete und doppelte Rückmeldung); Relay-Simulator.
  - Testfixtures in `tests/acceptance/`: zwei Mandanten, zwei Dienste, zwei Bediengebiete, Fahrgäste mit/ohne verifizierte Nummer und mit/ohne Marketing-Einwilligung, Callcenter-, Support-, Disponenten- und Leitungsrolle, Buchungen auf zwei Fahrzeugen mit letztem freien Platz, ein Fahrzeug mit Buchungen für den Ausfalltest.
- **Detailverträge vorab:** Fixture-Katalog mit Seeds; Simulator-Steuerschnittstelle.
- **Akzeptanzkriterien:**
  1. Jede Fixture ist per Seed reproduzierbar und in der Testevidenz referenzierbar (Kap. 08 §8.4).
  2. Der Simulator kann je Nachricht die Rückmeldung und deren Verzögerung festlegen.
  3. Fixtures enthalten keine Produktionsdaten.
- **Tests:** Positiv: Seed erzeugt identischen Datenstand. Negativ: Simulatorfehler wird als Kanalfehler, nicht als Zustellung gemeldet.
- **Abhängigkeiten:** intern: WP-M7b-01.
  - extern: WP-M0-22 (Akzeptanztestgerüst)
  - extern: WP-M2-32 (Nachrichtenversand mit Zustellversuchen (Adapter-Schnittstelle))
- **Entscheidungen:** keine
- **Größe:** S
- **Nicht im Umfang:** echte Provideranbindung (Auswahl siehe Offene Punkte).

### WP-M7b-04 – Kontrollierte Umdisposition auf ein anderes Fahrzeug
- **Ziel:** Ein Operator verschiebt eine Fahrt bzw. Buchung kontrolliert auf ein anderes eigenes freigegebenes Fahrzeug; vor dem Commit werden Zeit-, Kapazitäts- und Regelkonflikte geprüft.
- **Anforderungen:** `LH-6.7-OPS-007`
- **Liefergegenstände:**
  - Backend: Modul `booking-dispatch` – Umdispositionsbefehl über zwei `vehicle_run`-Revisionen (Quelle und Ziel) in einer Transaktion; Konfliktprüfung auf Zusagefenster, Ressourcenvektor (Sitz, Rollstuhl, Ausstattung), Schichtgrenzen, Fahrzeugfreigabe (`resource_release`), geschützten Stopphorizont; Sperren der Kapazitätsabschnitte in stabiler Reihenfolge; Plan-CAS auf beiden Revisionen.
  - Datenbank: `vehicle_run`, `route_stop`, `booking`, `booking_change`, `capacity_hold`, `resource_release`; Invariante „Kapazität/Qualifikation/Fahrzeugfreigabe" (Kap. 04 §4.5).
  - API: `/v1/runs/{id}/revisions` (Umdisposition mit Zielfahrzeug, Begründung, `If-Match`), Konfliktantwort mit Konfliktliste; `/v1/operator/vehicles` für die Kandidatenanzeige.
  - Oberfläche: UI-07 – Aktion „auf anderes Fahrzeug umdisponieren", Konfliktliste vor Bestätigung, Tastaturalternative zu Drag-and-drop; 412-Konflikt ohne stilles Überschreiben; die Bestätigung erklärt Folgen, Preis und erforderliche Zustimmung (Kap. 06 §6.5); Zustände „Zielfahrzeug gesperrt", „veralteter Standort", „keine Fremdrechte" (UI-07, Kap. 06 §6.9).
  - Ereignisse/Outbox: Planrevision geändert (beide Fahrzeuge), Buchung umdisponiert; daraus folgen die neue Stoppliste beider Fahrer-Clients (M3) und die Fahrgastbenachrichtigung über die vorhandenen Auslöser (M2) – beides außerhalb der Fachtransaktion.
  - Datenbank (Audit): `audit_event` mit Akteur, Begründung, Quell- und Zielfahrzeug (Detailnachweis alt/neu in WP-M7b-06).
  - Konfigurationsparameter: `max_reassignments`, `protected_stop_horizon_min`, `min_capacity_reserve`, `max_wait_min`, `max_detour_pct`.
- **Detailverträge vorab:** Erweiterung des Revisionsvertrags um fahrzeugübergreifende Änderung; Konfliktcode-Katalog (Zeit, Kapazität, Regel); Regel, welche Konflikte sperren und welche nur warnen (siehe Offene Punkte).
- **Akzeptanzkriterien:**
  1. Vor dem Commit werden Zeit-, Kapazitäts- und Regelkonflikte geprüft und dem Operator angezeigt; ein sperrender Konflikt verhindert den Commit (OPS-007).
  2. Eine erfolgreiche Umdisposition erzeugt je betroffenem Fahrzeug eine neue Planrevision; die Buchung hängt an genau einem Fahrzeug.
  3. Ein gesperrtes oder nicht freigegebenes Zielfahrzeug wird im Commit abgelehnt, auch wenn die Sperre nach der Anzeige eintrat.
  4. Jede Umdisposition verlangt eine Begründung und ist idempotent wiederholbar.
  5. Bereits zugesagte Zeitfenster aller Buchungen auf Quell- und Zielfahrzeug bleiben harte Grenzen (Kap. 01 §1.4); eine Umdisposition, die eine fremde Zusage verletzen würde, wird nicht committet.
  6. Die Grenze `max_reassignments` je Buchung wird im Commit geprüft; ihre Überschreitung ist ein ausgewiesener Regelkonflikt.
  7. Nach dem Commit erhalten beide Fahrer die neue Stoppliste; ein offline auf der alten Planrevision erfasster Check-in wird als historische Beobachtung erhalten und erzeugt einen Klärfall statt eines Zustandsrücksprungs (Kap. 01 §1.5).
  8. Nur eigene, freigegebene Fahrzeuge des Mandanten sind als Ziel wählbar; Fahrzeuge fremder Betreiber erscheinen nicht als Kandidaten.
- **Tests:** Positiv: Umdisposition ohne Konflikt; Fahrgast erhält genau eine Änderungsnachricht. Negativ: Zielfahrzeug ohne Rollstuhlplatz, Zusagefenster verletzt, geschützter Stopphorizont, fremdes Fahrzeug, `max_reassignments` überschritten, fehlende Begründung, Rolle ohne Dispositionsrecht, Offline-Check-in auf alter Planrevision (Kap. 05 §5.8), Zielfahrzeug ohne gültige Freigabe (PH-AT-020-Muster). Konkurrierend: Umdisposition parallel zu neuer App-Buchung auf den letzten Platz des Zielfahrzeugs → keine Überbelegung; zwei Disponenten verschieben dieselbe Buchung → genau eine gewinnt (412).
- **Abhängigkeiten:** intern: WP-M7b-01.
  - extern: WP-M2-16, WP-M2-22, WP-M2-23, WP-M2-14 (Fahrtenplanmodell, Hold/Confirm-Sperrreihenfolge, Ressourcenvektor)
  - extern: WP-M3-40, WP-M3-08 (manuelle Umplanung mit Begründung und Plan-CAS (WP-M3-40), Sperr- und Freigabekern)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Vorschau ohne Commit (WP-M7b-05); Partnerübernahme (M6); automatische Neuoptimierung (M7a OPT).

### WP-M7b-05 – Auswirkungsvorschau einer manuellen Routenänderung
- **Ziel:** Der Disponent sieht vor einer manuellen Routenänderung, welche Buchungen betroffen sind und wie sich deren Zeiten verschieben, ohne dass der Plan verändert wird.
- **Anforderungen:** `LH-6.7-OPS-011`
- **Liefergegenstände:**
  - Backend: Trockenlauf der Planänderung gegen die aktuelle Planrevision über denselben Prüfer wie WP-M7b-04; Ergebnis mit betroffenen Buchungen, Zeitabweichung je Stopp gegenüber Zusagefenster und bisheriger Prognose, Konfliktliste; kein Schreibzugriff auf `vehicle_run`.
  - API: Vorschaumodus an `/v1/runs/{id}/revisions` (Vertrag aus WP-M7b-01) mit Bezug auf die geprüfte Basisrevision.
  - Oberfläche: UI-07 – Simulationsansicht: Liste betroffener Buchungen, Zeitabweichungen, Verletzungen von Zusagefenstern hervorgehoben; Übernahme nur mit unveränderter Basisrevision.
- **Detailverträge vorab:** Vorschau-Antwortschema; Gültigkeit der Vorschau (Bindung an Planrevision).
- **Akzeptanzkriterien:**
  1. Die Vorschau zeigt mindestens die betroffenen Buchungen und deren Zeitabweichungen (OPS-011).
  2. Die Vorschau verändert weder Plan noch Kapazität noch erzeugt sie Nachrichten.
  3. Hat sich die Planrevision seit der Vorschau geändert, wird die Übernahme mit Konflikt abgelehnt und eine neue Vorschau verlangt.
- **Tests:** Positiv: Stopp verschieben → zwei betroffene Buchungen mit Minutenabweichung. Negativ: Vorschau auf fremdem Fahrzeug; Übernahme einer veralteten Vorschau. Konkurrierend: Vorschau während paralleler Buchungsbestätigung → Übernahme erkennt Revisionswechsel.
- **Abhängigkeiten:** intern: WP-M7b-04.
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Szenariosimulation mit isolierten Ressourcen (SIM, M9; AT-22).

### WP-M7b-06 – Audit-Trail kritischer Dispositionsentscheidungen
- **Ziel:** Jede kritische Dispositionsentscheidung ist mit vorherigem und neuem Wert, Grund, Akteur und Zeitpunkt nachvollziehbar.
- **Anforderungen:** `LH-6.7-OPS-018`
- **Liefergegenstände:**
  - Backend: Katalog kritischer Dispositionsentscheidungen (Umdisposition, manuelle Stoppänderung, Fahrzeugsperre, Buchungsänderung/-storno durch Disposition, temporäre Sperre, Ausnahme von Regeln); Schreiben des Audit-Eintrags in derselben Transaktion wie die Fachänderung.
  - Datenbank: `audit_event` (append-only) mit Vorher-/Nachher-Wert und Grund; Verweis auf `vehicle_run`-Revision bzw. `booking_change`.
  - API: Audit-Abfrage je Buchung, Fahrzeug und Zeitraum (Pfad im Detailvertrag).
  - Oberfläche: UI-07 – Verlauf „Dispositionsentscheidungen" am Fahrzeug und an der Buchung; UI-20 – Abfrage für Prüfer.
- **Detailverträge vorab:** Katalog „kritische Entscheidung" (siehe Offene Punkte); Audit-Payload-Schema ohne unnötige PII.
- **Akzeptanzkriterien:**
  1. Änderungen zeigen vorherigen und neuen Wert sowie den Grund (OPS-018).
  2. Eine kritische Entscheidung ohne Grund wird abgelehnt.
  3. Audit-Einträge sind nicht änder- oder löschbar und nur für berechtigte Rollen des eigenen Mandanten lesbar.
- **Tests:** Positiv: Umdisposition → Eintrag mit alt/neu/Grund. Negativ: UPDATE/DELETE auf Audit scheitert; Fremdmandant liest nicht. Konkurrierend: Fachänderung wird zurückgerollt → kein verwaister Audit-Eintrag.
- **Abhängigkeiten:** intern: WP-M7b-04.
  - extern: WP-M1-12 (Audit append-only (WP-M1-12))
- **Entscheidungen:** keine
- **Größe:** S
- **Nicht im Umfang:** Ablaufprotokoll automatischer Vorgänge (WP-M3-55); Betriebsjournal (WP-M7b-11).

### WP-M7b-07 – Schicht- und Dienstplanübersicht der Leitstelle
- **Ziel:** Die Leitstelle sieht je Schicht Fahrer, Fahrzeug, Pause und mögliche Konflikte; eine überlappende Zuweisung wird sichtbar abgelehnt oder mit Warnung versehen.
- **Anforderungen:** `LH-6.7-OPS-009`
- **Liefergegenstände:**
  - Backend: Leseprojektion aus veröffentlichter `roster_revision`, `staff_shift`, `vehicle_assignment` und Pausenplan; Konfliktermittlung über die vorhandene Konfliktprüfung (Doppelbelegung Fahrer/Fahrzeug, Pause gegen belegte Fahrt).
  - API: Lesezugriff über `/v1/rosters` (Leitstellensicht ohne HR-Details) und `/v1/operator/vehicles`.
  - Oberfläche: UI-07 – Ansicht „Schichten heute/morgen" mit Fahrer, Fahrzeug, Pausenfenster, Konfliktkennzeichen; Tabellenalternative; Abwesenheit nur als „nicht verfügbar" ohne Diagnose.
- **Detailverträge vorab:** Projektion der Leitstellensicht (Feldliste, keine HR-/Abwesenheitsgründe); Regel „ablehnen oder warnen" je Konfliktart.
- **Akzeptanzkriterien:**
  1. Die Übersicht zeigt je Schicht Fahrer, Fahrzeug, Pause und Konflikte (OPS-009).
  2. Der Versuch einer überlappenden Zuweisung desselben Fahrers oder Fahrzeugs wird sichtbar abgelehnt oder mit Warnung quittierungspflichtig angezeigt (OPS-009).
  3. Die Leitstelle sieht nur Schichten ihres berechtigten Dienstes bzw. Depots.
- **Tests:** Positiv: Schicht mit Pause wird korrekt dargestellt. Negativ: überlappende Zuweisung; Rolle ohne Depotberechtigung. Konkurrierend: zwei gleichzeitige Zuweisungen desselben Fahrzeugs → höchstens eine wirksam.
- **Abhängigkeiten:** intern: WP-M7b-01.
  - extern: WP-M3-07 (Fahrer-/Fahrzeugzuweisung mit eindeutigen Schichtgrenzen)
  - extern: WP-M4-03, WP-M4-04, WP-M4-07, WP-M4-16 (Dienstplan, Pausenplan, Konfliktprüfung, Depotberechtigung)
  - extern: WP-M7d-10, WP-M7d-12 (Planungswarnungen (harte Sperre/Hinweis/Empfehlung), Pause als Kapazitätsrestriktion)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Dienstplanbearbeitung (UI-12, M4/M7d); Schichttausch und -angebote (M7d).

### WP-M7b-08 – Buchungsdetail mit Statushistorie, Fahrzeugereignissen und Kommunikation
- **Ziel:** Support und Leitstelle rekonstruieren einen Fehlfall einer Buchung vollständig in der Oberfläche, ohne direkten Datenbankzugriff.
- **Anforderungen:** `LH-6.7-OPS-010`
- **Liefergegenstände:**
  - Backend: zusammengeführte Zeitleiste je Buchung aus Statushistorie, `booking_change`, Planrevisionen, Fahrerereignissen (Ankunft, Einstieg, No-Show, Ausstieg), Fahrzeugereignissen (Sperre, Ausfall, Standortalter), Zahlungsstatus (Projektion) und `notification`/`notification_attempt`; redigierte Darstellung ohne Rohpayloads mit PII.
  - API: Detail unter `/v1/bookings` (Operator-/Supportprojektion) mit Zeitleiste; Filter nach Ereignisart.
  - Oberfläche: UI-07/UI-06 – Buchungsdetail mit chronologischer Zeitleiste, Ereignis- und Trace-ID, Kanal und Zustellstatus je Nachricht.
- **Detailverträge vorab:** Zeitleisten-Schema; Redaktionsregeln je Rolle (Kap. 05 §5.7 „redigierte Fallhistorie").
- **Akzeptanzkriterien:**
  1. Die Detailansicht liefert Statushistorie, Fahrzeugereignisse und Kommunikation einer Buchung; ein vorbereiteter Fehlfall (z. B. verspätete Abholung mit fehlgeschlagener SMS) ist ohne Datenbankzugriff nachvollziehbar (OPS-010).
  2. Ereignisse sind zeitlich geordnet und tragen Quelle und Zeitstempel (Ereignis- vs. Empfangszeit).
  3. Rollen ohne Berechtigung sehen keine Kontaktdaten und keine Unterstützungsmerkmale.
  4. Jeder Lesezugriff des Supports auf das Buchungsdetail ist mit Akteur, Zweck und Zeit protokolliert; leere Abschnitte (z. B. keine Kommunikation) werden als leer ausgewiesen, nicht ausgeblendet.
- **Tests:** Positiv: Fehlfall-Fixture wird vollständig angezeigt; verspätet eingetroffenes Fahrerereignis wird nach Ereigniszeit einsortiert (PH-AT-017-Muster). Negativ: Buchung eines anderen Mandanten → gleichartige Antwort wie für nicht vorhandenes Objekt (AT-18, PH-AT-001-Muster); Supportrolle ohne Zweck sieht redigierte Sicht; Zeitleiste enthält keine Rohpayloads mit PII.
- **Abhängigkeiten:** intern: WP-M7b-24.
  - extern: WP-M2-25, WP-M2-32 (Statushistorie der Buchung (WP-M2-25), Nachrichtenversand)
  - extern: WP-M3-29, WP-M3-31, WP-M3-37 (Fahrerereignisse, Fahrzeug- und Dienststatus, Auftragsliste)
- **Entscheidungen:** D-11 (zulässiger Supportzugriff) – blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Saga-Klärarbeitsplatz für Mehrbetreiberreisen (UI-08, M6a).

### WP-M7b-09 – Temporäre Gebietssperren, verbotene Straßen und Sonderbetriebspläne
- **Ziel:** Die Leitstelle aktiviert befristet Gebietssperren, verbotene Straßen und Sonderbetriebspläne; eine befristete Sperre wird zum Endzeitpunkt automatisch zurückgenommen.
- **Anforderungen:** `LH-6.7-OPS-012`
- **Liefergegenstände:**
  - Backend: temporäre Aktivierung mit Beginn, Ende, Grund, Geltung (Dienst/Gebiet); Zeitsteuerung für automatische Rücknahme (wiederanlaufsicher, idempotent); Wirkung auf neue Angebote und Routing über die Restriktionsschnittstelle aus M7a; Sonderbetriebsplan als befristete Betriebszeit-/Dienstregel.
  - Datenbank: Ablage temporäre Sperre (Arbeitsname) mit Wirksamkeitsintervall; Bezug zu `service`, `service_area`; Invariante „nicht überlappende aktive Policy-/Dienstversion" (Kap. 04 §4.5) für Sonderbetriebspläne.
  - API: Aktivieren/Beenden/Liste (Pfad im Detailvertrag; lesend eingebunden in `/v1/service-areas`).
  - Oberfläche: UI-07 – Dialog „Sperre aktivieren" mit Pflicht-Endzeit oder ausdrücklich unbefristet, Liste aktiver Sperren mit Restlaufzeit; Anzeige betroffener bestehender Buchungen.
  - Ereignisse/Outbox: Sperre aktiviert, Sperre abgelaufen/zurückgenommen.
- **Detailverträge vorab:** Zustandsautomat der temporären Sperre; Abgrenzung zu versionierten Sperrbereichen aus M7a; Definition „Sonderbetriebsplan" (siehe Offene Punkte); Behandlung bereits zugesagter Buchungen im gesperrten Gebiet.
- **Akzeptanzkriterien:**
  1. Eine befristete Sperre wird zum Endzeitpunkt automatisch zurückgenommen; danach sind Angebote im Gebiet wieder möglich (OPS-012).
  2. Während der Laufzeit entstehen im gesperrten Gebiet bzw. über die verbotene Straße keine neuen Angebote/Routen für den betroffenen Dienst.
  3. Aktivierung und Rücknahme sind mit Akteur, Grund und Zeit protokolliert; die Rücknahme geschieht genau einmal, auch nach Worker-Neustart.
  4. Bestehende Zusagen werden nicht still geändert, sondern als betroffene Buchungen angezeigt.
- **Tests:** Positiv: Sperre 10 Minuten → automatisch aufgehoben. Negativ: Endzeit vor Beginn; Aktivierung ohne Rolle; Sperre für fremdes Gebiet. Konkurrierend: Angebot wird während Aktivierung gebildet → Confirm prüft erneut; Worker-Absturz zum Endzeitpunkt → Rücknahme genau einmal.
- **Abhängigkeiten:** intern: WP-M7b-02.
  - extern: WP-M1-22, WP-M1-18, WP-M1-15 (Wirksamkeitsintervalle der Konfiguration, Zeit-/DST-Baustein, `operation` mit Lease/Fencing)
  - extern: WP-M7a-21, WP-M7a-22 (Sperrbereiche nach Zeit/Raum/Dienst und Berücksichtigung verbotener Strecken im Routing)
- **Entscheidungen:** D-09 (Routingdienst und Restriktionsfähigkeit) – blockiert nur die Produktivschaltung der Straßensperre.
- **Größe:** M
- **Nicht im Umfang:** dauerhafte, versionierte Zonen und Restriktionen mit geplanter Aktivierung (M7a GEO); Fahrermeldung von Straßenhindernissen (M7c).

### WP-M7b-10 – Getrennt schaltbare Kartenlayer
- **Ziel:** Der Operator schaltet und filtert die Kartenlayer Haltestellen, Zonen, Depots, Sperrungen und ÖPNV-Hubs jeweils getrennt.
- **Anforderungen:** `LH-6.7-OPS-016`
- **Liefergegenstände:**
  - Backend: Layer-Lesezugriffe mit räumlichem und fachlichem Filter (Dienst, Status, Gültigkeit).
  - API: `/v1/stops`, `/v1/service-areas`, `/v1/transfer-hubs`, Depotliste aus der Organisationsstruktur, aktive Sperrungen aus WP-M7b-09 (GeoJSON/SRID-Konvention nach Kap. 05 §5.7).
  - Oberfläche: UI-07 – Layersteuerung mit fünf unabhängigen Schaltern und je eigenem Filter; gleichwertige Listenansicht je Layer; Auswahl je Benutzer gemerkt.
- **Detailverträge vorab:** Layerkatalog mit Datenquelle, Filterfeldern und Aktualität.
- **Akzeptanzkriterien:**
  1. Jeder der fünf Layer (Haltestellen, Zonen, Depots, Sperrungen, ÖPNV-Hubs) kann getrennt ein-/ausgeschaltet und gefiltert werden, ohne die anderen zu verändern (OPS-016).
  2. Jeder Layer ist ohne Karte als Liste erreichbar (Tastatur/Screenreader).
  3. Es erscheinen nur Objekte des eigenen Mandanten bzw. ausdrücklich freigegebene gemeinsame Hubs.
  4. Ein Layer ohne Daten oder mit nicht erreichbarer Quelle zeigt einen eigenen Leer- bzw. Fehlerzustand, ohne die übrigen Layer zu beeinträchtigen; der Layerzustand ist nie nur über Farbe erkennbar.
- **Tests:** Positiv: Layer einzeln schalten und filtern; abgelaufene Sperrung verschwindet aus dem Layer „Sperrungen". Negativ: Objekte eines fremden Mandanten erscheinen nicht; ungültige Geometrie bricht die Karte nicht.
- **Abhängigkeiten:** intern: WP-M7b-09.
  - extern: WP-M1-09 (Stammobjekt Betriebshof der Organisationsstruktur (Depot-Layer))
  - extern: WP-M2-04, WP-M2-05 (Bediengebiete, Haltepunktrevisionen)
  - extern: WP-M3-36 (Leitstelle Livebetrieb (Kartenbasis))
  - extern: WP-M6a-08, WP-M6b-02, WP-M6b-10 (`transfer_hub` als ÖPNV-Hub-Quelle (Parallelstrom, keine harte Reihenfolge; bis zur Lieferung zeigt der Layer den Leerzustand „keine Hubs erfasst", siehe OP-23))
- **Entscheidungen:** D-09 (Kartenlizenz) – blockiert nur die Produktivschaltung.
- **Größe:** S
- **Nicht im Umfang:** Partnerpositionen auf der Karte (nur mit Grant, M6b).

### WP-M7b-11 – Betriebsjournal je Schicht mit Export
- **Ziel:** Für jede Leitstellenschicht werden wesentliche Abweichungen und Eingriffe protokolliert; das Journal ist nach Zeitraum und Mandant exportierbar.
- **Anforderungen:** `LH-6.7-OPS-014`
- **Liefergegenstände:**
  - Backend: Journalprojektion aus Dispositionsentscheidungen (WP-M7b-06), Störungen (WP-M7b-12), Alarmen und temporären Sperren, ergänzt um manuelle Journaleinträge der Schichtleitung; Schichtbezug; Export als wiederholsicherer Job.
  - Datenbank: Betriebsjournal (Arbeitsname), append-only; `export_job`.
  - API: Journal lesen/Eintrag ergänzen (Pfad im Detailvertrag); Export über `/v1/reports/jobs` und `/v1/reports/jobs/{id}`.
  - Oberfläche: UI-07 – Journalansicht je Schicht mit Filter Zeitraum; Export-Schaltfläche mit Fortschritt.
- **Detailverträge vorab:** Katalog „wesentliche Abweichung/Eingriff"; Definition der Leitstellenschicht (siehe Offene Punkte); Exportformat und Empfängerrechte.
- **Akzeptanzkriterien:**
  1. Das Journal kann nach Zeitraum und Mandant exportiert werden; der Export enthält genau die Einträge des Zeitraums des eigenen Mandanten (OPS-014).
  2. Wesentliche Abweichungen und Eingriffe einer Schicht erscheinen automatisch mit Zeit, Akteur und Bezug.
  3. Einträge sind nachträglich nicht änderbar; Korrekturen erfolgen als Nachtrag.
  4. Ein wiederholter Exportauftrag mit gleichem Schlüssel liefert denselben Job.
- **Tests:** Positiv: Export eines Tages. Negativ: Export für fremden Mandanten abgelehnt; Rolle ohne Exportrecht. Konkurrierend: Eintrag während laufendem Export → Export bleibt konsistent zum Datenstand.
- **Abhängigkeiten:** intern: WP-M7b-02, WP-M7b-06, WP-M7b-12.
  - extern: WP-M1-12, WP-M1-11 (Audit, Exportberechtigung)
  - extern: WP-M8c-27 (Report-/Exportjobs; `/v1/reports/jobs` liefert nicht M4, sondern der Parallelstrom M8)
- **Entscheidungen:** D-11 (Aufbewahrungsfrist des Journals) – blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Kennzahlenberichte (M8c); PBefG-Datenexport (AT-25).

### WP-M7b-12 – Betriebsstörung: Stammdaten und Lebenszyklus
- **Ziel:** Jede Betriebsstörung besitzt Typ, Schweregrad, Betroffenheit, Verantwortlichen und Status und kann eröffnet, eskaliert, gelöst und geschlossen werden.
- **Anforderungen:** `LH-6.13-SUP-005`
- **Liefergegenstände:**
  - Backend: Modul `communication-support` – Incident-Aggregat (Erweiterung des M3-Incidents) mit Typkatalog, Schweregrad, Betroffenheit (Buchungen, Fahrzeuge, Dienst, Gebiet), Verantwortlichem, Status; Zustandsübergänge eröffnen → (eskalieren) → lösen → schließen mit Rollenprüfung, Pflichtgrund und optimistischer Sperre; manuelle Eröffnung.
  - Datenbank: Incident-Ablage (Arbeitsname, Erweiterung aus M3); Verknüpfungstabellen zu `booking`, `vehicle`, `vehicle_run`; `business_alert` als Auslöserbezug.
  - API: `/v1/operator/incidents` (anlegen, Detail, Statusübergang mit `If-Match`).
  - Oberfläche: UI-07 – Störungsdetail mit Stammdaten, Statusleiste, Aktionen Eskalieren/Lösen/Schließen.
  - Ereignisse/Outbox: Störung eröffnet/eskaliert/gelöst/geschlossen.
  - Konfigurationsparameter: `alert_escalation_delay_min`.
- **Detailverträge vorab:** Zustandsautomat; Typ- und Schweregradkatalog (siehe Offene Punkte); Eskalationsziel je Schweregrad.
- **Akzeptanzkriterien:**
  1. Eine Störung enthält Typ, Schweregrad, Betroffenheit, Verantwortlichen und Status; ohne Typ und Schweregrad ist die Eröffnung nicht möglich (SUP-005).
  2. Jede Störung kann eröffnet, eskaliert, gelöst und geschlossen werden; jeder Übergang ist mit Akteur, Zeit und Grund historisiert (SUP-005).
  3. Unzulässige Übergänge (z. B. Schließen ohne Lösung) werden serverseitig abgelehnt.
  4. Eine geschlossene Störung bleibt lesbar und unverändert.
  5. Eine Störung ohne Bearbeitung eskaliert nach `alert_escalation_delay_min` (je Alerttyp) automatisch genau einmal; kritische Ereignisse eskalieren laut Katalogvalidierung ohne Verzögerung. Die genaue Auslösebedingung („unbearbeitet") legt der Detailvertrag fest.
  6. Ein Wechsel des Verantwortlichen ist mit vorherigem und neuem Wert historisiert.
- **Tests:** Positiv: vollständiger Durchlauf eröffnen–eskalieren–lösen–schließen; automatische Eskalation mit steuerbarer Uhr. Negativ: unzulässiger Übergang; fremder Mandant; Rolle ohne Recht. Konkurrierend: zwei Bearbeiter ändern gleichzeitig den Status → einer erhält 412.
- **Abhängigkeiten:** intern: WP-M7b-02.
  - extern: WP-M3-53, WP-M3-52 (Incident bei Fahrzeugausfall (WP-M3-53), Alarm-Grundlage (WP-M3-52))
- **Entscheidungen:** D-05 (Supportzeiten, Eskalationsorganisation) – blockiert nur die Produktivschaltung der Eskalationsziele.
- **Größe:** M
- **Nicht im Umfang:** Posteingang und Zuweisung (WP-M7b-13); föderierte Störungsadressierung an Partner (M6b).

### WP-M7b-13 – Incident-Posteingang mit Zuweisung
- **Ziel:** Der Operator arbeitet Meldungen und Eskalationen in einem zentralen Posteingang ab und weist sie einem Verantwortlichen mit Bearbeitungsstatus zu.
- **Anforderungen:** `LH-6.7-OPS-013`
- **Liefergegenstände:**
  - Backend: Posteingangsprojektion über Störungen, eskalierte Alarme und eingehende Meldungen (Fahrer, Callcenter, automatische Regeln); Zuweisung an Person oder Rolle, Bearbeitungsstatus (offen/zugewiesen/in Bearbeitung/erledigt), Übernahme und Weitergabe.
  - API: `/v1/operator/incidents` (Liste mit Filter Status, Schweregrad, Verantwortlicher, Dienst; Zuweisung).
  - Oberfläche: UI-07 – Posteingang mit Sortierung nach Priorität und Alter, Filter „Offene Störungen", Zuweisungsdialog, Live-Aktualisierung.
  - Kennzahlen: Datengrundlage für `Kundenkontakt-Auflösung` (Bearbeitungszeiten).
- **Detailverträge vorab:** Listen-/Filterschema; Abgrenzung Posteingang gegenüber Alert-Center (M8d, siehe Offene Punkte).
- **Akzeptanzkriterien:**
  1. Eine Meldung kann einem Verantwortlichen zugewiesen werden und trägt danach einen sichtbaren Bearbeitungsstatus (OPS-013).
  2. Der Posteingang zeigt Meldungen und Eskalationen aller Quellen des berechtigten Dienstes an einer Stelle.
  3. Zuweisung an eine Person ohne Berechtigung für den Dienst wird abgelehnt.
- **Tests:** Positiv: Meldung zuweisen, Status wechseln. Negativ: fremder Dienst nicht sichtbar. Konkurrierend: zwei Disponenten übernehmen dieselbe Meldung → genau eine Zuweisung.
- **Abhängigkeiten:** intern: WP-M7b-12.
  - extern: WP-M3-35, WP-M3-52 (Live-Aktualisierungskanal, Arbeitsliste „Offene Störungen")
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Alert-Center mit Kosten-, Personal- und Schnittstellenalarmen (M8d).

### WP-M7b-14 – Fahrzeugausfall: Sperre, betroffene Fahrten, Wiederherstellung
- **Ziel:** Ein Fahrzeugausfall führt zur Sperrung, zur vollständigen Ermittlung betroffener Fahrten und zu einem vorgeschlagenen Wiederherstellungsprozess; keine betroffene Buchung bleibt ohne bearbeitbaren Status.
- **Anforderungen:** `LH-6.13-SUP-006`
- **Liefergegenstände:**
  - Backend: Ausbau des P1-Störungsablaufs: Vollständigkeitsprüfung „jede betroffene Buchung hat einen Bearbeitungszustand" (umdisponiert, storniert, in Klärung, wartet auf Fahrgastentscheidung); vorgeschlagener Wiederherstellungsprozess als geführte Schrittfolge (Ersatzfahrzeug über WP-M7b-04, Verschiebung, Storno mit Erstattung, Fahrgastinformation); Sperre der Schließung des Incidents, solange Buchungen offen sind; Anzeige einer vertraglich zulässigen Partnerübernahme als Option, sofern bereitgestellt.
  - Datenbank: Incident-Verknüpfungen, `booking_change`, `resource_release`.
  - API: `/v1/operator/incidents` (Detail mit betroffenen Buchungen, Bearbeitungszustand, Vorschlägen).
  - Oberfläche: UI-07 – Störungsansicht mit Fortschritt „n von m Buchungen bearbeitet", Schritte „Ersatzoptionen prüfen", „Fahrgast informieren", „Fall eröffnen".
  - Kennzahlen: Datengrundlage für `Buchungsstornoquote Betreiber`, `Ausfallminuten kritisch`.
  - Konfigurationsparameter: `vehicle_unavailability_alert_min`, `max_reassignments`.
- **Detailverträge vorab:** Katalog der Bearbeitungszustände je betroffener Buchung; Regel für bereits eingestiegene Fahrgäste; Reihenfolge der Wiederherstellungsschritte.
- **Akzeptanzkriterien:**
  1. Der Ausfall sperrt das Fahrzeug, ermittelt alle betroffenen Fahrten und schlägt einen Wiederherstellungsprozess vor (SUP-006).
  2. Keine betroffene Buchung bleibt ohne bearbeitbaren Status; der Incident lässt sich nicht schließen, solange eine Buchung unbearbeitet ist (SUP-006).
  3. Buchungen, die nach Erkennung des Ausfalls noch auf das Fahrzeug disponiert würden, werden im Commit verhindert.
  4. Eine Partnerübernahme erscheint höchstens als Option und wird nie ohne Autorisierung zugewiesen.
- **Tests:** Positiv: Ausfall mit fünf Buchungen → alle mit Zustand; AT-09 und AT-50 bleiben grün; Beitrag zu E2E-100 (Option statt unautorisierter Zuweisung). Negativ: Schließen mit offener Buchung. Konkurrierend: neue Buchung parallel zur Sperre; Fahrgaststorno parallel zur Umdisposition.
- **Abhängigkeiten:** intern: WP-M7b-04, WP-M7b-12.
  - extern: WP-M2-26, WP-M2-31, WP-M2-27 (Storno- und Erstattungsfluss, Stornoursache Fahrzeugausfall)
  - extern: WP-M3-54, WP-M3-08 (Störungsablauf P1 (WP-M3-54), Sperr- und Freigabekern)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Partnerübernahme selbst (M6a/M6b, E2E-100); Schichtplanreaktion auf Fahrzeugausfall (M7d); Fahrgastinformation (WP-M7b-32).

### WP-M7b-15 – Notfallmeldung aus der Fahrer-App: priorisierte Anzeige und Eskalation
- **Ziel:** Eine Notfallmeldung aus der Fahrer-App erscheint in der Leitstelle sofort priorisiert mit Ereignis-ID und zuständigem Dienst und kann eskaliert werden.
- **Anforderungen:** `LH-6.13-SUP-007`
- **Liefergegenstände:**
  - Backend: Konsument des Notfallereignisses; Erzeugung eines Incidents höchster Priorität mit Fahrzeug, Fahrer, Dienst, letzter Position und Standortalter; Umgehung von Eskalationsverzögerung und Ruhezeiten; Eskalationsaktion an definierte Rollen/Kontakte; Testalarm-Kennzeichnung.
  - Datenbank: `business_alert`, `alert_acknowledgement`, Incident-Ablage; `inbox` für Dedupe über `device_event_id`.
  - API: Eingang über `/v1/driver/events`; Anzeige und Quittierung über `/v1/operator/incidents`.
  - Oberfläche: UI-07 – nicht übersehbare priorisierte Anzeige (visuell und für Screenreader), Ereignis-ID, zuständiger Dienst, Aktionen Quittieren/Eskalieren.
- **Detailverträge vorab:** Ereignisschema Notfall inkl. Testalarm; Eskalationskette und Kontakte (siehe Offene Punkte); Abgrenzung zum amtlichen Notruf.
- **Akzeptanzkriterien:**
  1. Ein Testalarm aus der Fahrer-App erreicht die Leitstelle mit Ereignis-ID und zuständigem Dienst (SUP-007).
  2. Die Anzeige ist gegenüber allen anderen Meldungen priorisiert und bietet eine Eskalationsmöglichkeit (SUP-007).
  3. Ein wiederholt zugestelltes Notfallereignis erzeugt keinen zweiten Alarm; Quittierung speichert Akteur und Zeit.
  4. Nur die für den Dienst definierten Leitstellenrollen sehen den Alarm.
  5. Ein Testalarm ist als solcher gekennzeichnet und löst keine reale Eskalation an externe Kontakte aus.
  6. Der Notfallalarm wird weder durch `alert_escalation_delay_min` noch durch `notification_quiet_hours` verzögert (Katalogvalidierung „Kritische Ereignisse ohne Verzögerung", „Sicherheitsalarme ausgenommen").
- **Tests:** Positiv: Testalarm Ende-zu-Ende; Alarm während Ruhezeit wird sofort angezeigt. Negativ: Alarm für fremden Dienst nicht sichtbar; Quittierung ohne Rolle. Konkurrierend: 100-fache Wiederzustellung nach Offline-Phase → ein Alarm; zwei gleichzeitige Quittierungen → eine.
- **Abhängigkeiten:** intern: WP-M7b-12, WP-M7b-13.
  - extern: WP-M3-52, WP-M3-29, WP-M3-35 (Alarm-Grundlage, Fahrerereignis-Eingang mit `device_event_id`, Live-Aktualisierungskanal)
  - extern: WP-M7c-06 (Fahrer-Notfallknopf in der Driver-App; Unfall-/Notfallprozess)
- **Entscheidungen:** D-11 (Notbetriebs-/Kontaktprozess, berechtigte Rollen) – blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Notfallknopf, Notrufhinweise in der App und Rückmeldeworkflow an den Fahrer (WP-M7c-06, `LH-6.6-DRV-012`); Weiterleitung an amtliche Notrufstellen (ausdrücklich ausgeschlossen). Die Aufteilung der Leitstellenanzeige zwischen diesem WP und WP-M7c-06 ist vor Baubeginn festzulegen (OP-24).

### WP-M7b-16 – Automatische Alarme: GPS-Ausfall, überfällige Abholung, Ablehnungshäufung
- **Ziel:** Bei ausbleibendem GPS, überfälliger Abholung oder ungewöhnlich vielen Ablehnungen entsteht automatisch ein Incident, ohne Duplikatflut.
- **Anforderungen:** `LH-6.13-SUP-010`
- **Liefergegenstände:**
  - Backend: drei Alarmregeln als `alert_policy_revision`; Auswertung über Standortalter, Zusagefenster/Abholstatus und Ablehnungszählung je Gebiet/Dienst und Zeitfenster; Dedupe und Zusammenfassung je (Regel, Objekt, offener Zeitraum); Wiederöffnungs- und Entwarnungsregel.
  - Datenbank: `alert_policy_revision`, `business_alert`, `alert_acknowledgement`; Lesen von `position_sample`, `travel_attempt`, `rejection_reason`, `booking`.
  - API: Anzeige über `/v1/operator/incidents`; Regelpflege über `/v1/config/revisions`.
  - Oberfläche: UI-07 – automatische Incidents im Posteingang mit Auslöser, Schwellwert und Messwert.
  - Konfigurationsparameter: `capacity_reject_alert_count` (Ablehnungshäufung, Ebene Gebiet/Zeitfenster, Mindeststichprobe), `alert_escalation_delay_min`; als Kandidaten ohne festgelegte Zuordnung (OP-17): `eta_max_data_age_sec` (GPS-Ausfall), `vehicle_unavailability_alert_min`, `late_arrival_alert_pct`, `no_show_grace_min`. Für „überfällige Abholung" existiert kein Katalogparameter – ein neuer Parameter ist als zu ergänzen zu kennzeichnen, nicht stillschweigend einzuführen.
  - Kennzahlen: Lesebezug auf `Kapazitätsablehnungen`.
- **Detailverträge vorab:** Regeldefinition je Alarmtyp inkl. Messfenster; Schwelle „überfällige Abholung" und „ungewöhnlich viele Ablehnungen" (siehe Offene Punkte); Dedupe-Schlüssel.
- **Akzeptanzkriterien:**
  1. Eine simulierte Schwellwertverletzung je Regel (GPS bleibt aus, Abholung überfällig, Ablehnungshäufung) erzeugt einen Incident (SUP-010).
  2. Anhaltende oder wiederholt gemeldete Verletzung desselben Objekts erzeugt keinen weiteren Incident, sondern aktualisiert den bestehenden (SUP-010).
  3. Der Incident nennt Regelrevision, Schwellwert und gemessenen Wert.
  4. Nach Entwarnung und erneuter Verletzung entsteht ein neuer Incident gemäß Wiederöffnungsregel.
- **Tests:** Positiv: je Regel eine Simulation; AT-10 bleibt grün. Negativ: Wert knapp unter Schwelle → kein Incident; fremder Mandant. Konkurrierend: 1.000 Positionslücken-Ereignisse eines Fahrzeugs und parallele Regelauswertung in zwei Workern → ein Incident.
- **Abhängigkeiten:** intern: WP-M7b-12, WP-M7b-13.
  - extern: WP-M1-21, WP-M1-14 (wirksame Konfiguration, Inbox-Deduplikation)
  - extern: WP-M3-52, WP-M3-34, WP-M3-42 (Alarm-Grundlage, Positionserfassung und Standortalter, Überfälligkeit (WP-M3-42))
  - extern: WP-M4-48 (Ablehnungen mit normiertem Primärgrund)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Nachfragealarm mit Kapazitätsvorschlag (E2E-099, M8d AUT); Finanz- und Personalalarme (M8d).

### WP-M7b-17 – Callcenter: Fahrgast auffinden oder Gastbuchung anlegen
- **Ziel:** Ein Callcenter-Mitarbeiter findet einen Fahrgast anhand verifizierter Identifikatoren oder legt eine Gastbuchung an, ohne private Zugangsdaten des Fahrgasts zu benötigen.
- **Anforderungen:** `LH-6.13-SUP-001`
- **Liefergegenstände:**
  - Backend: Fahrgastsuche über verifizierte Identifikatoren (verifizierte Telefonnummer, verifizierte E-Mail, Kundennummer – Katalog im Detailvertrag) mit exaktem Treffer und Suchprotokoll; Callcenter-Legitimation als Grundlage für `booking_subject` DELEGATED (Kap. 05 §5.9) mit Zweck, Dienst und Laufzeit; Anlage eines Gastdatensatzes ohne permanentes Konto durch den Bediener mit verifiziertem Kontaktkanal und eigener Aufbewahrungsmarke (Mindestumfang, den dieses WP selbst liefert; das Gast-Selbstbedienungstoken aus `LH-6.2-IAM-007` ist nicht Voraussetzung); Callcenter-Legitimation als eigener, befristeter Eintrag mit Zweck, Dienst und Laufzeit; Begrenzung der Suchfrequenz je Bediener; Bediener bleibt eigener Akteur im Audit.
  - Datenbank: `passenger_private`, `delegated_access`, `consent_revision`, `audit_event`.
  - API: `/v1/passengers` (Callcenter-Suche, Gastanlage), `/v1/delegations` (Callcenter-Legitimation).
  - Oberfläche: UI-06 – Suchmaske, Trefferkarte mit minimalen Daten, Zustände „Vertretungsumfang, Zustimmung und Kontaktkanal fehlen", „kein Treffer", „mehrdeutig – weiteren Identifikator erfragen", Einstieg „Gastbuchung"; vollständig per Tastatur und Screenreader bedienbar (Rahmen aus WP-M7b-35).
- **Detailverträge vorab:** Katalog zulässiger Identifikatoren und Verifikationsgrad; Ablauf der Zustimmung am Telefon (siehe Offene Punkte); Projektion der Fahrgastdaten für das Callcenter; Aufbewahrung von Gastdaten.
- **Akzeptanzkriterien:**
  1. Ein Mitarbeiter findet einen Fahrgast anhand verifizierter Identifikatoren oder erstellt eine Gastbuchung und kann buchen, ohne private Zugangsdaten (Passwort, Einmalcode der Fahrgast-Anmeldung) zu kennen oder einzugeben (SUP-001).
  2. Eine Suche über nicht verifizierte oder unscharfe Merkmale liefert keine Personenliste.
  3. Jede Suche und jeder Zugriff ist mit Bediener, Zweck und Zeit protokolliert; Treffer anderer Mandanten sind ausgeschlossen.
  4. Fehlen Vertretungsumfang, Zustimmung oder Kontaktkanal, zeigt UI-06 den Zustand an und verhindert die Buchung.
  5. Die Zustimmung des Fahrgasts bzw. Anrufers ist mit Art, Zeitpunkt und Bediener dokumentiert; die Legitimation endet automatisch mit ihrer Laufzeit.
  6. Antworten für „nicht vorhanden" und „fremder Mandant" sind gleichartig; massenhaftes Durchprobieren von Identifikatoren wird begrenzt und als Sicherheitsereignis protokolliert.
  7. Das Callcenter sieht vom Fahrgast nur die für die Buchung nötige Projektion; Zahlungsmittel, Passwörter und Einmalcodes sind in keiner Antwort enthalten.
- **Tests:** Positiv: Suche per verifizierter Nummer; Gastbuchung. Negativ: unverifizierte Nummer; Callcenter eines anderen Betreibers (AT-18, PH-AT-001-Muster); abgelaufene Legitimation; Enumeration über viele Identifikatoren (Begrenzung greift); manipulierte `tenant_id` (Kap. 05 §5.8). Konkurrierend: zwei Agenten legen parallel denselben Gast an → kein Dublettenkonto bzw. definierte Zusammenführung.
- **Abhängigkeiten:** intern: WP-M7b-02, WP-M7b-03, WP-M7b-35.
  - extern: WP-M1-28, WP-M1-07, WP-M1-31, WP-M1-12, WP-M1-02 (Fahrgastregistrierung mit Kontaktverifikation, Rollenmodell, Feldschutz, Audit, Rate-Limit)
  - extern: WP-M7a-11 (`delegated_access` und `/v1/delegations` aus der Buchung für Dritte)
  - Hinweis (keine Abhängigkeit): `LH-6.2-IAM-007` und `LH-6.2-IAM-008` (M8e, Parallelstrom) erweitern Gastbuchung und Vertretungsdokumentation später; die Verträge sind in WP-M7b-01 so zu schneiden, dass M8e sie wiederverwendet (OP-10).
- **Entscheidungen:** D-11 (zulässiger Supportzugriff, Löschfristen für Gastdaten) – blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Buchungsablauf selbst (WP-M7b-18); Vertretung durch Angehörige in der Fahrgast-App (UI-05, M7a/M8e); Gastzugriff per Buchungstoken und Aktivierungsschalter je Betreiber (`LH-6.2-IAM-007`, M8e).

### WP-M7b-18 – Callcenter-Buchung über den gemeinsamen Regel- und Kapazitätskern
- **Ziel:** Buchungen im Callcenter nutzen dieselben Regeln und Kapazitäten wie App und Web; gleichzeitige Buchungen beider Kanäle verursachen keine Überbelegung.
- **Anforderungen:** `LH-6.13-SUP-002`
- **Liefergegenstände:**
  - Backend: Callcenter als weiterer Kanal derselben Fach-API (Kap. 01 §1.1) – Angebot, Hold und Confirm über den unveränderten Kern mit `booking_subject` DELEGATED; Kanal- und Bedienerkennzeichen an `travel_attempt` und `booking`; keine kanalspezifische Sonderlogik für Tarif oder Kapazität; Zahlart im Callcenter gemäß Detailvertrag.
  - Datenbank: `travel_attempt`, `fare_quote`, `capacity_hold`, `booking`, `idempotency_record`.
  - API: `/v1/ride-offers`, `/v1/bookings` (identische Verträge wie Passenger-Kanal).
  - Oberfläche: UI-06 – Buchungsstrecke für verifizierten Fahrgast: Suche, Angebot, Hold-Restzeit, Bestätigung, Ablehnungsgrund mit nächster Handlung; vollständig per Tastatur bedienbar.
  - Kennzahlen: Kanal als Dimension für `Anfragen`, `Angebotsquote`, `Buchungskonversion`.
- **Detailverträge vorab:** Kanalattribut; Zahlungs- und Bestätigungsweg für telefonische Buchungen (siehe Offene Punkte); Hold-Dauer (Planungsdefault des Pflichtenhefts, unverändert aus M2 übernommen).
- **Akzeptanzkriterien:**
  1. Gleichzeitige Buchungen aus Callcenter und App auf den letzten Platz führen zu höchstens einer bestätigten Buchung; Kapazität und Status sind zentral konsistent (SUP-002).
  2. Für identische Anfrage liefern Callcenter und App dasselbe Angebot, denselben Preis und dieselben Ablehnungsgründe.
  3. Server prüft Legitimation, Zweck, Dienst und Laufzeit bei Planung, Hold und Confirm erneut; Quote und Buchung speichern die aufgelöste Person, der Bediener steht im Audit.
  4. Ein Subjektwechsel während der Buchung erfordert ein neues Angebot.
  5. Die Callcenter-Buchung erscheint sofort in der Fahrgastansicht (UI-04) und in der Leitstelle (UI-07) mit Kanal- und Bedienerkennzeichen; der Fahrgast erhält die Buchungsbestätigung über seinen verifizierten Kontaktkanal.
  6. Die Buchungsstrecke in UI-06 ist ohne Karte und ohne Maus vollständig ausführbar (Kap. 08 §8.4, Gate UX).
- **Tests:** Positiv: Callcenter-Buchung Ende-zu-Ende. Negativ: Confirm nach abgelaufenem Hold (PH-AT-010-Muster); Legitimation zwischen Hold und Confirm entzogen; gleicher Idempotenzschlüssel mit anderem Request (PH-AT-011-Muster); manipulierte `tenant_id` und unzulässige Fremd-ID (Kap. 05 §5.8). Konkurrierend: AT-12 (Callcenter kontra App), Muster PH-AT-004 mit gemischten Kanälen und 100 parallelen Transaktionen; Doppelklick/Idempotenz.
- **Abhängigkeiten:** intern: WP-M7b-17.
  - extern: WP-M1-13, WP-M1-02 (Idempotenzregister, Fehlermodell)
  - extern: WP-M2-21, WP-M2-22, WP-M2-23, WP-M2-24, WP-M2-08 (Angebotsbildung, Hold, Confirm, Idempotenz der Buchungsbefehle, Tarif)
  - extern: WP-M3-39 (Buchung durch die Disposition (WP-M3-39) als Bedienmuster)
  - extern: WP-M7a-12 (ein Buchungsbestand für Web, native App und Callcenter (WP-M7a-12); AT-12 wird gemeinsam nachgewiesen (API-Anteil M7a, Oberflächenanteil UI-06 hier))
- **Entscheidungen:** D-03 (Geldfluss/Zahlart bei telefonischer Buchung) – blockiert nur die Produktivschaltung bezahlter Callcenter-Buchungen.
- **Größe:** M
- **Nicht im Umfang:** Serien, Vertretung, Sonderbedarf (WP-M7b-19); Mehrbetreiberreise im Callcenter (M6a/M6c, UI-Abnahme Kap. 06 §6.9).

### WP-M7b-19 – Callcenter: Serien, Vertretungsbuchungen und Sonderbedarfe
- **Ziel:** Das Callcenter bearbeitet Serien, Vertretungsbuchungen und Sonderbedarfe gemäß Rolle; jede Anpassung erscheint konsistent im zentralen Buchungsbestand.
- **Anforderungen:** `LH-6.13-SUP-003`
- **Liefergegenstände:**
  - Backend: Callcenter-Zugriff auf Serienanlage/-änderung/-ausnahme, auf Buchung für Dritte (buchende Person und Fahrgast getrennt) und auf Sonderbedarfe (Rollstuhl, Begleitperson, Unterstützungshinweise) über die vorhandenen Kernbefehle; Rollenprüfung je Aktion; Feldschutz für Unterstützungsmerkmale.
  - Datenbank: `booking_series`, `series_occurrence`, `passenger_trip`, `booking_change`, `delegated_access`, `passenger_preference`.
  - API: `/v1/booking-series`, `/v1/bookings`, `/v1/delegations`.
  - Oberfläche: UI-06 – Serienübersicht mit Ergebnis je Datum, Ausnahme bearbeiten, Sonderbedarf erfassen; rollenabhängig ausgeblendete Aktionen.
- **Detailverträge vorab:** Rechtematrix Callcenter-Rollen × Aktionen; Katalog „Sonderbedarf" (siehe Offene Punkte).
- **Akzeptanzkriterien:**
  1. Eine im Callcenter vorgenommene Anpassung an Serie, Vertretungsbuchung oder Sonderbedarf erscheint unmittelbar und identisch im zentralen Buchungsbestand, in der Fahrgastansicht und in der Leitstelle (SUP-003).
  2. Aktionen außerhalb der Rolle werden serverseitig abgelehnt (SUP-003).
  3. Serienänderungen zeigen das Ergebnis je Datum; fehlgeschlagene Termine werden nicht pauschal zugesagt.
  4. Unterstützungsmerkmale sind nur für berechtigte Callcenter-Rollen sichtbar.
  5. Bei Vertretungsbuchungen sind buchende Person, beförderte Person und Bediener getrennt gespeichert und in Buchungshistorie und Audit ausgewiesen.
  6. Ein Sonderbedarf, der die Kapazität betrifft (z. B. Rollstuhlplatz), wird über denselben Ressourcenvektor geprüft wie in App und Web; ist er nicht erfüllbar, wird die Änderung mit Grund abgelehnt und die bestehende Buchung bleibt unverändert.
- **Tests:** Positiv: Serie im Callcenter ändern → App zeigt denselben Stand; AT-06 über den Callcenter-Kanal. Negativ: Rolle ohne Serienrecht; Vertretung abgelaufen. Konkurrierend: Fahrgast storniert Termin in der App, während das Callcenter die Serie ändert → konsistenter Endstand ohne verlorene Änderung.
- **Abhängigkeiten:** intern: WP-M7b-18.
  - extern: WP-M1-07, WP-M1-31 (Rollenmodell, Feldschutz für Unterstützungsmerkmale)
  - extern: WP-M7a-07, WP-M7a-11, WP-M7a-10 (Serienbuchung, Buchung für Dritte mit `delegated_access` und `/v1/delegations`, Gruppen-/Kapazitätsanforderungen)
  - Hinweis (keine Abhängigkeit): die erweiterte Vertretungsdokumentation aus `LH-6.2-IAM-008` (M8e) ist nicht Voraussetzung (OP-10).
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Serienlogik selbst (M7a); Vertretungsverwaltung durch den Fahrgast (UI-05).

### WP-M7b-20 – Supportfall mit Verknüpfungen und Ereignisfolge
- **Ziel:** Supportfälle lassen sich mit Buchung, Fahrer, Fahrzeug und Incident verknüpfen; der Sachbearbeiter erreicht aus der Fallansicht die zugehörige Ereignisfolge.
- **Anforderungen:** `LH-6.13-SUP-004`
- **Liefergegenstände:**
  - Backend: Modul `communication-support` – Aggregat Supportfall mit Kategorie, Status, Bearbeiter, Fallhistorie; Verknüpfungen zu Buchung/Journey, Fahrer, Fahrzeug, Incident mit Mandantenprüfung; redigierte Fallhistorie; begründeter, protokollierter Zugriff.
  - Datenbank: `support_case` mit Fallreferenzen zu `journey`/`booking`; Verknüpfungen zu `vehicle`, `driver` (nur Referenz und Anzeigename, keine Personaldaten), Incident; `audit_event` für Zugriffe.
  - API: `/v1/support/cases` (anlegen, lesen, verknüpfen, Status, Notiz).
  - Oberfläche: UI-06/UI-20 – Fallansicht mit Verknüpfungsleiste und eingebetteter Ereignisfolge (aus WP-M7b-08), Zustände „fehlender Zweck", „abgelaufener Zugriff".
  - Kennzahlen: Datengrundlage für `Kundenkontakt-Auflösung`.
- **Detailverträge vorab:** Zustandsautomat Supportfall; Kategorienkatalog; Redaktionsregeln; SLA-Definition für die Kennzahl (siehe Offene Punkte).
- **Akzeptanzkriterien:**
  1. Ein Supportfall kann mit Buchung, Fahrer, Fahrzeug und Incident verknüpft werden; aus der Fallansicht ist die zugehörige Ereignisfolge erreichbar (SUP-004).
  2. Verknüpfungen auf Objekte anderer Mandanten werden abgelehnt.
  3. Die Fallhistorie ist nachvollziehbar und enthält keine Rohpayloads mit PII; Fahrerbezug zeigt keine Personalakte.
  4. Zugriff ohne Zweck bzw. nach Ablauf wird verweigert und protokolliert.
  5. Ein Fall unter Legal Hold ist als solcher ausgewiesen und von der Löschung ausgenommen (UI-20-Zustand „Legal Hold").
  6. Jeder Statuswechsel und jede Notiz ist mit Bearbeiter und Zeit historisiert; Lösungszeitpunkt und Ursachentyp werden für `Kundenkontakt-Auflösung` gespeichert.
- **Tests:** Positiv: Fall anlegen und vierfach verknüpfen. Negativ: fremde Buchungs-ID (AT-18, E2E-091-Muster); Zugriff ohne Zweck. Konkurrierend: zwei Sachbearbeiter ändern den Fall gleichzeitig → 412 für einen.
- **Abhängigkeiten:** intern: WP-M7b-02, WP-M7b-08, WP-M7b-12.
  - extern: WP-M1-07, WP-M1-08, WP-M1-12, WP-M1-31 (Rollen- und Objektberechtigung, Audit, Feldschutz)
- **Entscheidungen:** D-11 (zulässiger Supportzugriff, Aufbewahrung von Fällen) und D-01 (Supportverantwortung je Dienst) – blockieren nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Vorlagen (WP-M7b-22); Erstattung (WP-M7b-23); Datenschutzanfragen `/v1/privacy/requests` (M8).

### WP-M7b-21 – Zentraler Supportlink zwischen Beschwerde, Buchung und Incident
- **Ziel:** Von einer Beschwerde ist die zugehörige Buchung und der Incident mit einem Klick erreichbar und umgekehrt; es entsteht kein Medienbruch.
- **Anforderungen:** `LH-6.7-OPS-017`
- **Liefergegenstände:**
  - Backend: stabile, berechtigungsgeprüfte Deep-Links für Buchung, Incident und Supportfall; Rückverweise (Buchung → Fälle, Incident → Fälle).
  - API: Verweisfelder in `/v1/support/cases`, `/v1/bookings` und `/v1/operator/incidents`.
  - Oberfläche: UI-07/UI-06 – Aktion „Fall eröffnen" aus Buchung und Incident mit Vorbelegung; Verknüpfungschips in allen drei Ansichten.
- **Detailverträge vorab:** Link-Schema; Verhalten bei fehlender Berechtigung auf das Zielobjekt.
- **Akzeptanzkriterien:**
  1. Von einer Beschwerde ist die zugehörige Buchung direkt erreichbar (OPS-017).
  2. Aus Buchung und Incident sind verknüpfte Supportfälle sichtbar und aufrufbar.
  3. Ein Link auf ein Objekt ohne Berechtigung zeigt einen Rechtezustand, keine Daten.
- **Tests:** Positiv: Beschwerde → Buchung → Incident und zurück. Negativ: Link auf fremden Mandanten; Link nach Zugriffsende.
- **Abhängigkeiten:** intern: WP-M7b-08, WP-M7b-13, WP-M7b-20.
- **Entscheidungen:** keine
- **Größe:** S
- **Nicht im Umfang:** Verknüpfung zu Partnerfällen (M6b).

### WP-M7b-22 – Supportvorlagen für Beschwerden, Verspätungen und Zahlungen
- **Ziel:** Der Support wählt Vorlagen für Beschwerden, Verspätungen und Zahlungen; die Auswahl übernimmt die definierten Vorgangsdaten korrekt.
- **Anforderungen:** `LH-6.13-SUP-008`
- **Liefergegenstände:**
  - Backend: versionierte Fallvorlagen je Kategorie mit Platzhaltern für Vorgangsdaten (Buchungsnummer, Zusagefenster, tatsächliche Zeiten, Betrag, Zahlungsstatus); Übernahme in Falltext und Antwortnachricht; Vorlagenrevision am Fall gespeichert.
  - Datenbank: `channel_template_revision` (Antworttexte); Vorlagenbezug an `support_case`.
  - API: Vorlagenliste und -anwendung unter `/v1/support/cases` (Vertrag aus WP-M7b-01).
  - Oberfläche: UI-06/UI-20 – Vorlagenauswahl mit Vorschau der eingesetzten Daten vor Übernahme.
- **Detailverträge vorab:** Platzhalterkatalog je Vorlagentyp; Verhalten bei fehlendem Wert.
- **Akzeptanzkriterien:**
  1. Die Auswahl einer Vorlage übernimmt die definierten Vorgangsdaten korrekt aus der verknüpften Buchung bzw. Zahlung (SUP-008).
  2. Für Beschwerden, Verspätungen und Zahlungen existiert je mindestens eine Vorlage.
  3. Ein fehlender Pflichtwert wird sichtbar markiert und nicht leer oder falsch eingesetzt.
  4. Spätere Vorlagenänderungen verändern bestehende Fälle nicht.
- **Tests:** Positiv: Verspätungsvorlage setzt Zusagefenster und Ist-Zeit korrekt ein. Negativ: Fall ohne Buchung; Platzhalter mit fremdem Objekt.
- **Abhängigkeiten:** intern: WP-M7b-20.
- **Entscheidungen:** keine
- **Größe:** S
- **Nicht im Umfang:** Incident-Massennachricht (WP-M7b-33); Vorlagenverwaltung der Kanäle als Selbstverwaltung (M8d CFG).

### WP-M7b-23 – Erstattungsfall: berechtigen, prüfen, genehmigen, ausführen
- **Ziel:** Ein Erstattungsfall durchläuft Berechtigung, Prüfung, Genehmigung und Ausführung; die Erstattung ist an Ursprungstransaktion und Bearbeiter gebunden.
- **Anforderungen:** `LH-6.13-SUP-009`
- **Liefergegenstände:**
  - Backend: Erstattungsfall aus dem Supportfall mit Bezug auf die Ursprungszahlung; Aggregat RefundCase im Modul `fare-payment` (Kap. 01 §1.3), vom Modul `communication-support` nur über eine explizite Fachoperation angesprochen; Zustandsübergänge berechtigt → geprüft → genehmigt → ausgeführt (bzw. abgelehnt); Rollentrennung zwischen Prüfung und Genehmigung; Ausführung als `operation` über den PSP-Adapter außerhalb der SQL-Transaktion mit eindeutiger PSP-Kommandoreferenz; unbestimmtes Ergebnis bleibt `UNKNOWN` bis zum Abgleich; Kostenwirkung als Gegenereignis.
  - Datenbank: `refund_case` mit FK auf `payment_intent`/`payment_event`, Bearbeiter je Schritt; `economic_event` (Gegenereignis), `journal`, `journal_line`; `idempotency_record`; Invarianten „Gegenereignis statt Änderung" und „Bestätigung/PSP-Retry exakt gleiche Operation" (Kap. 04 §4.5).
  - API: Erstattungsfall unter `/v1/support/cases` (Vertrag aus WP-M7b-01); Fortschritt über `/v1/operations/{operation_id}`.
  - Oberfläche: UI-06/UI-20 – Erstattungsdialog mit Ursprungsbetrag, bereits erstattetem Betrag, Höchstbetrag, Statusleiste; „erstattet" erst nach PSP-Bestätigung.
- **Detailverträge vorab:** Zustandsautomat Erstattungsfall; Rechtematrix und Genehmigungsschwellen; Höchstbetragsregel; Abgrenzung zur automatischen Storno-Erstattung aus M2.
- **Akzeptanzkriterien:**
  1. Ein Erstattungsfall kann berechtigt, geprüft, genehmigt und ausgeführt werden; jeder Schritt speichert Bearbeiter und Zeit (SUP-009).
  2. Jede Erstattung ist an die Ursprungstransaktion und die Bearbeiter gebunden; eine Erstattung ohne Ursprungstransaktion ist nicht möglich (SUP-009).
  3. Die Summe aller Erstattungen überschreitet den eingezogenen Betrag der Ursprungstransaktion nie.
  4. Prüfer und Genehmiger sind verschiedene Personen, wenn die Schwelle dies verlangt; Selbstgenehmigung ist ausgeschlossen.
  5. PSP-Timeout führt zu Abgleich, nicht zu einer zweiten Erstattung; Beträge sind `bigint` in kleinster Einheit.
  6. Die Kostenwirkung entsteht als Gegenereignis mit ausgeglichenem Journalposten; liegt die Ursprungsbuchung in einer geschlossenen Periode, wird diese nicht verändert.
  7. Ablehnung in jedem Schritt verlangt einen Grund und beendet den Fall ohne Geldbewegung; der Fahrgast sieht „erstattet" erst nach PSP-Bestätigung, davor „Erstattung beauftragt" (Kap. 06 §6.4).
- **Tests:** Positiv: Teilerstattung Ende-zu-Ende gegen PSP-Simulator. Negativ: Betrag über Rest; fremde Ursprungstransaktion; fehlende Genehmigerrolle; Selbstgenehmigung; Ursprungszahlung eines anderen Mandanten; Erstattung zu geschlossener Periode (PH-AT-015-Muster); gleicher Idempotenzschlüssel mit anderem Betrag (PH-AT-011-Muster). Konkurrierend: zwei parallele Erstattungen auf dieselbe Zahlung, deren Summe den Rest übersteigt → höchstens eine; PSP-Antwort geht verloren und Wiederholung → genau eine Erstattung (AT-15-Muster); Erstattung parallel zu automatischer Storno-Erstattung.
- **Abhängigkeiten:** intern: WP-M7b-02, WP-M7b-20.
  - extern: WP-M0-23 (PSP-Simulator)
  - extern: WP-M1-13, WP-M1-15, WP-M1-07 (Idempotenzregister, `operation` mit Lease/Fencing, Rollenmodell)
  - extern: WP-M2-29, WP-M2-30, WP-M2-31 (PSP-Adapter, Zahlungsautomat, Void und Erstattung nach Storno)
  - extern: WP-M4-33, WP-M4-36 (Kostenereignis, Journal, Gegenereignis, Fiskalperiode)
- **Entscheidungen:** D-03 (Geldfluss, Refunds im Mehrparteienmodell) und D-06 (Finanzumfang) – blockieren nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Erstattung über mehrere Betreiber und Clearing-Rückabwicklung (M6c, PH-AT-014); Gutscheine/Guthaben.

### WP-M7b-24 – Kommunikationsauftrag: Zweck, Einwilligungs-Gate, Empfängerauflösung
- **Ziel:** Jede ausgehende Nachricht der Einheit läuft über einen einheitlichen Kommunikationsauftrag mit Zweck, Kanal, Empfängerauflösung und Einwilligungsprüfung.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: Modul `communication-support` – Erweiterung des Nachrichtenauftrags um Nachrichtenzweck (betrieblich/sicherheitsrelevant/Service/Marketing), Kritikalität, Empfängerklasse (Fahrgast/Fahrer/Leitstelle), Absenderkontext (Buchung, Incident, Kampagne, Supportfall); zentrales Gate vor dem Versand: Kanalfreigabe des Mandanten, verifizierter Kontakt, zweckbezogene Einwilligung, Ruhezeit-Hook; Empfängerauflösung nach Dienst, Gebiet, Buchungsbezug mit Vorschau (Anzahl, keine Massenausgabe von PII); Dedupe je (Auftrag, Empfänger, Kanal).
  - Datenbank: `notification`, `notification_attempt`, `channel_template_revision`, `consent_revision`, `passenger_preference`.
  - API: `/v1/notifications` (Auftrag anlegen, Empfängervorschau, Status lesen).
  - Ereignisse/Outbox: Auftrag erstellt; Versand über Outbox, nie in der Fachtransaktion.
- **Detailverträge vorab:** Zweck- und Kritikalitätskatalog; Entscheidungstabelle Zweck × Einwilligung × Kanal; Empfängerfilter-Schema; Liste „freigegebener Kanäle" je Mandant.
- **Akzeptanzkriterien:**
  1. Kein Versandpfad der Einheit umgeht das Gate (Architekturtest).
  2. Die Empfängervorschau liefert dieselbe Menge wie der anschließende Versand bei unverändertem Datenstand.
  3. Ein Empfänger erhält je Auftrag und Kanal höchstens eine Nachricht, auch bei Wiederholung.
  4. Retry und Zustellstatus ändern keine Buchungszusage (Kap. 01 §1.3).
  5. Jede Gate-Entscheidung (zugelassen, ausgeschlossen mit Grund) ist je Empfänger nachvollziehbar gespeichert, ohne Nachrichteninhalt oder Kontaktdaten in technischen Logs.
  6. Einwilligung und Kontaktverifikation werden zum Versandzeitpunkt geprüft, nicht zum Zeitpunkt der Auftragsanlage.
- **Tests:** Positiv: Auftrag mit Filter Dienst+Gebiet. Negativ: nicht freigegebener Kanal; Empfänger eines anderen Mandanten; Marketingzweck ohne Einwilligung. Konkurrierend: derselbe Auftrag wird doppelt eingereicht und von zwei Workern verarbeitet → eine Nachricht je Empfänger.
- **Abhängigkeiten:** intern: WP-M7b-02, WP-M7b-03.
  - extern: WP-M1-30, WP-M1-28, WP-M1-14 (Einwilligung Betriebsmitteilung getrennt von Marketing (WP-M1-30), Kontaktverifikation, Outbox-Relay)
  - extern: WP-M2-32, WP-M2-33, WP-M2-34 (Asynchroner Nachrichtenversand, Vorlagen, Auslöser (WP-M2-32 bis WP-M2-34))
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** konfigurierbare Kanäle, Empfängerrollen und Eskalationsketten als Selbstverwaltung (M8d CFG); Schichtmitteilungen an Mitarbeiter (M7d).

### WP-M7b-25 – Kommunikationsstatus und Provider-Rückmeldungen
- **Ziel:** Jede Nachricht zeigt ihren Status erstellt, versendet, zugestellt, fehlgeschlagen oder unbekannt; eine Provider-Fehlermeldung ist dem ursprünglichen Ereignis zugeordnet.
- **Anforderungen:** `LH-6.12-COM-009`
- **Liefergegenstände:**
  - Backend: Zustandsautomat je Zustellversuch mit den fünf Zuständen; Verarbeitung asynchroner Provider-Rückmeldungen (Zustellquittung, Fehler) mit Signaturprüfung und Dedupe; Zuordnung über Nachrichten-ID und Provider-Referenz zum auslösenden Fachereignis; Timeout ohne Rückmeldung → „unbekannt", nicht Erfolg oder Fehler.
  - Datenbank: `notification`, `notification_attempt` (Status, Provider-Referenz, Fehlercode, Zeitpunkte), `inbox`.
  - API: Statusabfrage unter `/v1/notifications`; Provider-Eingang (Pfad im Detailvertrag, je Adapter).
  - Oberfläche: UI-07/UI-06 – Statusanzeige je Nachricht in Buchungsdetail, Incident und Kampagne mit Fehlergrund.
  - Adapter/Worker: `notification`-Adapter – Rückmeldungsabbildung je Provider auf die fünf Zustände.
- **Detailverträge vorab:** Zustandsautomat; Abbildungstabelle Provider-Codes → Status; Signaturverfahren des Provider-Eingangs.
- **Akzeptanzkriterien:**
  1. Der Kommunikationsstatus zeigt erstellt, versendet, zugestellt, fehlgeschlagen beziehungsweise unbekannt (COM-009).
  2. Eine Test-Providerfehlermeldung ist dem ursprünglichen Ereignis (Buchung, Incident, Kampagne) und dessen Nachricht zugeordnet (COM-009).
  3. Verspätete, doppelte oder in falscher Reihenfolge eintreffende Rückmeldungen führen zu genau einem konsistenten Endstatus; „zugestellt" wird nicht durch späteres „versendet" überschrieben.
  4. Fehlende Rückmeldung wird als „unbekannt" ausgewiesen.
- **Tests:** Positiv: Zustellquittung. Negativ: Rückmeldung mit ungültiger Signatur; Rückmeldung zu unbekannter Nachricht; Rückmeldung für fremden Mandanten. Konkurrierend: 100 identische Rückmeldungen und gleichzeitiger Retry → ein Statusübergang (AT-24-Muster).
- **Abhängigkeiten:** intern: WP-M7b-24.
  - extern: WP-M1-16, WP-M1-14 (Webhook-Grundlage (signierter Eingang), Inbox-Deduplikation)
  - extern: WP-M2-32 (Zustellversuche und Nachrichten-ID)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Auswahl des Push-/SMS-Providers; Partner-Webhooks (M6b).

### WP-M7b-26 – Massen-Push nach Dienst, Gebiet und Fahrgastgruppe
- **Ziel:** Ein Massen-Push erreicht genau die zugelassenen Empfänger des gewählten Dienstes, Gebiets und der betroffenen Fahrgastgruppe.
- **Anforderungen:** `LH-6.12-COM-004`
- **Liefergegenstände:**
  - Backend: Kampagne (Arbeitsname) mit Zielgruppendefinition Dienst, Gebiet, Fahrgastgruppe; Entwurf, Empfängervorschau, Freigabe, Versand in Stapeln, Abbruch; Zuordnung Fahrgast ↔ Gebiet/Dienst nach Regel des Detailvertrags; Zweckprüfung über WP-M7b-24.
  - Datenbank: Kampagnenablage (Arbeitsname), `notification`, `notification_attempt`; Lesen von `service`, `service_area`, `booking`, `passenger_preference`.
  - API: Kampagne unter `/v1/notifications` (Vertrag aus WP-M7b-01).
  - Oberfläche: UI-07 – Kampagnendialog: Zielgruppe wählen, Empfängerzahl prüfen, senden, Versandergebnis je Status.
- **Detailverträge vorab:** Definition „Fahrgast gehört zu Gebiet/Dienst" und „betroffene Fahrgastgruppe" (siehe Offene Punkte); Freigaberolle; Stapelgröße und Abbruchverhalten.
- **Akzeptanzkriterien:**
  1. Eine Testkampagne an ein Gebiet und einen Dienst erreicht nur zugelassene Empfänger im gewählten Gebiet; keine andere Gruppe erhält sie (COM-004, AT-17).
  2. Empfänger ohne zweckbezogene Einwilligung oder ohne Push-Kanal sind ausgeschlossen und werden gezählt, nicht benachrichtigt.
  3. Jeder Empfänger erhält die Kampagne höchstens einmal; ein abgebrochener Versand ist ohne Doppelversand fortsetzbar.
  4. Empfänger anderer Mandanten sind technisch ausgeschlossen.
  5. Freigabe, Versandstart und Abbruch einer Kampagne sind mit Akteur, Zielgruppendefinition und Empfängerzahl auditiert.
  6. Eine als nichtkritisch eingestufte Kampagne unterliegt `notification_quiet_hours` (WP-M7b-31); das Versandergebnis weist zurückgehaltene Empfänger getrennt aus.
- **Tests:** Positiv: AT-17. Negativ: leere Zielgruppe; Kampagne ohne Freigaberolle; Fahrgast in Nachbargebiet. Konkurrierend: zwei Worker versenden dieselbe Kampagne; Fahrgast widerruft Einwilligung während des Versands → nach Widerruf kein Versand an ihn.
- **Abhängigkeiten:** intern: WP-M7b-24, WP-M7b-25.
  - extern: WP-M1-08, WP-M1-30 (Dienst- und Objektberechtigung, Einwilligungsrevision)
  - extern: WP-M2-03, WP-M2-04, WP-M2-32 (Dienste, Bediengebiete, Push-Kanal)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Incident-Betroffene (WP-M7b-32); Marketingkampagnen-Planung und -Auswertung.

### WP-M7b-27 – In-App-Banner mit Priorität, Beginn und Ende
- **Ziel:** Betreiber veröffentlichen In-App-Banner mit Priorität, Inhalt, Beginn und Ende; nach Ablauf ist ein Banner nicht mehr sichtbar.
- **Anforderungen:** `LH-6.12-COM-005`
- **Liefergegenstände:**
  - Backend: Banner (Arbeitsname) mit Priorität, mehrsprachigem Inhalt, Beginn, Ende, Geltung (Dienst/Gebiet); serverseitige Gültigkeitsprüfung zum Abrufzeitpunkt; Sortierung nach Priorität; Cache-Invalidierung zum Ende.
  - Datenbank: Bannerablage (Arbeitsname) mit Wirksamkeitsintervall und Revision.
  - API: Banner pflegen und abrufen unter `/v1/notifications` (Vertrag aus WP-M7b-01); Abruf für Passenger-Clients.
  - Oberfläche: UI-07 – Bannerverwaltung; UI-01/UI-04 – Banneranzeige in Passenger-Web und nativer Passenger-App, für Screenreader zugänglich.
- **Detailverträge vorab:** Bannerschema; Prioritätsstufen; Verhalten bei mehreren gleichzeitigen Bannern; Offline-/Cacheverhalten der Clients.
- **Akzeptanzkriterien:**
  1. Ein Banner besitzt Priorität, Inhalt, Beginn und Ende (COM-005).
  2. Nach Ablauf ist das Banner nicht mehr sichtbar, auch in einem bereits geöffneten Client und ohne erneute Anmeldung (COM-005).
  3. Vor Beginn ist das Banner nicht sichtbar; Zeiten sind zeitzonen- und DST-fest.
  4. Bei mehreren Bannern bestimmt die Priorität die Reihenfolge.
- **Tests:** Positiv: Banner mit Ende in zwei Minuten verschwindet. Negativ: Ende vor Beginn; Banner eines anderen Mandanten; Client mit abweichender Geräteuhr zeigt abgelaufenes Banner nicht.
- **Abhängigkeiten:** intern: WP-M7b-24.
  - extern: WP-M2-35, WP-M2-37, WP-M2-43 (Passenger-PWA (UI-01, UI-04))
  - extern: WP-M7a-37, WP-M7a-38 (native Passenger-App (Banneranzeige))
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Offlinebanner der Driver-App (M3); Banner in der Fahrer-App.

### WP-M7b-28 – SMS-Kundenkontakt für verifizierte Nummern
- **Ziel:** SMS-Kundenkontakt ist für hinterlegte und verifizierte Nummern nutzbar; abgemeldete Marketingkontakte erhalten keine Marketing-SMS.
- **Anforderungen:** `LH-6.12-COM-006`
- **Liefergegenstände:**
  - Backend: SMS als Kanal für manuellen und ereignisgesteuerten Kundenkontakt; Versand nur an hinterlegte, verifizierte Nummern; Marketing-SMS nur mit gültiger Marketing-Einwilligung; Abmeldung (Opt-out) wirkt sofort; betriebliche SMS bleiben von der Marketing-Abmeldung unberührt.
  - Datenbank: `notification`, `notification_attempt`, `consent_revision`, `passenger_private` (verifizierte Nummer).
  - API: `/v1/notifications` (SMS-Auftrag); Abmeldeeingang (Vertrag aus WP-M7b-01).
  - Oberfläche: UI-06/UI-07 – „SMS senden" am Fahrgast/an der Buchung mit Anzeige Verifikations- und Einwilligungsstatus.
  - Adapter/Worker: `notification`-Adapter – SMS-Provider mit Absenderkennung und Zustellquittung.
- **Detailverträge vorab:** Abmeldeweg (Stichwort-Antwort, Link, Konto); Entscheidungstabelle Zweck × Einwilligung; Zeichensatz/Längenregeln.
- **Akzeptanzkriterien:**
  1. SMS werden nur an hinterlegte und verifizierte Nummern versendet; eine unverifizierte Nummer wird abgelehnt und als Kanalfehler dokumentiert (COM-006).
  2. Ein abgemeldeter Marketingkontakt erhält keine Marketing-SMS (COM-006).
  3. Derselbe Kontakt erhält weiterhin betriebliche Fahrtinformationen per SMS.
  4. Die Abmeldung ist versioniert und wirkt auf bereits geplante, noch nicht versendete Marketing-SMS.
- **Tests:** Positiv: betriebliche SMS an verifizierte Nummer. Negativ: Marketing-SMS an abgemeldeten Kontakt; unverifizierte Nummer; Nummer eines fremden Mandanten. Konkurrierend: Abmeldung trifft während des Kampagnenversands ein → kein Versand nach dem Abmeldezeitpunkt.
- **Abhängigkeiten:** intern: WP-M7b-24, WP-M7b-25.
  - extern: WP-M1-28, WP-M1-30 (Kontaktverifikation, Marketing-Einwilligung getrennt von Betriebsmitteilung)
  - extern: WP-M2-32 (SMS-Versandweg der Betriebsnachrichten)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** eingehender SMS-Dialog/Chat; Rufnummernrelay (WP-M7b-30).

### WP-M7b-29 – Getrennter Fahrer- und Leitstellenkanal mit quittierbarem Betriebsalarm
- **Ziel:** Fahrer-Push und Nachrichten der Leitstelle werden getrennt von der Kundenkommunikation betrieben; ein Betriebsalarm ist im Fahrerclient auffindbar und quittierbar.
- **Anforderungen:** `LH-6.12-COM-007`
- **Liefergegenstände:**
  - Backend: eigene Empfängerklasse, Vorlagen, Warteschlange und Berechtigung für Fahrer-/Leitstellennachrichten; kein gemeinsamer Auftrag mit Fahrgastempfängern; Betriebsalarm an Fahrer einer Schicht, eines Fahrzeugs oder Dienstes; Quittierung mit Zeit und Fahrer; Leitstelle sieht Quittierungsstand.
  - Datenbank: `notification`, `notification_attempt` (Empfängerklasse), `alert_acknowledgement`.
  - API: `/v1/notifications` (Betriebsalarm senden, Quittierungsstand); Quittierung über `/v1/driver/events`.
  - Oberfläche: UI-07 – „Betriebsalarm an Fahrer" mit Quittierungsübersicht; UI-10 – Nachrichtenliste im Fahrerclient mit auffindbarem, quittierbarem Alarm (auch nach verpasstem Push).
- **Detailverträge vorab:** Trennregel Kunden-/Betriebskanal (Vorlagen, Absender, Speicher, Rechte); Ereignisschema Quittierung; Offline-Verhalten.
- **Akzeptanzkriterien:**
  1. Ein Betriebsalarm ist im Fahrerclient auffindbar und quittierbar; die Quittierung erscheint in der Leitstelle (COM-007).
  2. Fahrer- und Leitstellennachrichten laufen über getrennte Aufträge, Vorlagen und Berechtigungen; ein Auftrag kann nicht gleichzeitig Fahrgäste und Fahrer adressieren (COM-007).
  3. Eine Störung oder ein Rückstau im Kundenkanal verzögert Betriebsalarme nicht.
  4. Eine offline erfasste Quittierung wird nach Synchronisation genau einmal übernommen.
  5. Der Betriebsalarm ist während der Fahrbewegung sichtbar, verlangt aber keinen Erfassungsdialog (Kap. 06 §6.7); er bleibt bis zur Quittierung in der Nachrichtenliste auffindbar.
- **Tests:** Positiv: Alarm senden, im Client finden, quittieren. Negativ: Fahrgast als Empfänger eines Betriebsalarms abgelehnt; Fahrer eines anderen Mandanten/Dienstes. Konkurrierend: Rückstau von 10.000 Kundennachrichten während eines Betriebsalarms; doppelte Quittierung nach Offline-Phase → eine.
- **Abhängigkeiten:** intern: WP-M7b-02, WP-M7b-24, WP-M7b-25.
  - extern: WP-M3-24, WP-M3-29 (Driver-App-Basis mit Outbox/Synchronisation, Fahrerereignis-Eingang)
  - extern: WP-M7c-04, WP-M7c-03 (Driver-Push mit priorisierten Betriebsinformationen und Lesebestätigung; geschützte Nachrichten des Fahrers)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Nachrichten vom Fahrer an Fahrgäste und Leitstelle (WP-M7c-03); Prioritätsstufen und Lesebestätigung je Schicht (WP-M7c-04, `LH-6.6-DRV-010`) – dieses WP liefert die Kanaltrennung und den quittierbaren Betriebsalarm, die Abgrenzung Quittierung/Lesebestätigung regelt OP-25; Schichtmitteilungen (M7d SCH); Notfallmeldung (WP-M7b-15).

### WP-M7b-30 – Anonymisierte, relay-basierte Buchungskommunikation
- **Ziel:** Buchungsbezogene Kommunikation zwischen Fahrer und Fahrgast erfolgt anonymisiert oder über ein Relay; die persönliche Telefonnummer des Fahrgasts bleibt dem Fahrer verborgen.
- **Anforderungen:** `LH-6.12-COM-008`
- **Liefergegenstände:**
  - Backend: Relay-Sitzung (Arbeitsname) je Buchung mit Laufzeit vom Vorlauf bis kurz nach Fahrtende; Vermittlung über Relay-Nummer bzw. In-App-Nachricht; Fahrerprojektion ohne Telefonnummer; Beenden der Sitzung bei Storno/Abschluss.
  - Datenbank: Relay-Sitzung (Arbeitsname) mit Bezug auf `booking`; keine Speicherung der Fahrgastnummer in Fahrer- oder Fahrzeugdaten.
  - API: Kontaktaufnahme über `/v1/notifications` bzw. Relay-Endpunkt (Vertrag aus WP-M7b-01); Stoppliste `/v1/driver/shifts/{id}/run` liefert nur Relay-Kontakt.
  - Oberfläche: UI-10 – „Fahrgast kontaktieren" ohne Nummernanzeige; UI-04 – „Fahrer kontaktieren" über Relay.
  - Adapter/Worker: `notification`-Adapter – Relay-Provider (Nummernmaskierung) hinter Adapterinterface.
- **Detailverträge vorab:** Relay-Variante (maskierte Rufnummer, In-App-Nachricht oder beides, siehe Offene Punkte); Sitzungslaufzeit; Protokollumfang (keine Inhalte).
- **Akzeptanzkriterien:**
  1. Die persönliche Telefonnummer des Fahrgasts ist in keiner Fahreransicht, keiner Fahrer-API-Antwort, keinem Push und keinem Geräteprotokoll enthalten (COM-008).
  2. Fahrer und Fahrgast können während der Sitzungslaufzeit buchungsbezogen Kontakt aufnehmen (COM-008).
  3. Nach Sitzungsende ist kein Kontakt über das Relay mehr möglich.
  4. Nur der zugeordnete Fahrer der Buchung kann die Sitzung nutzen.
  5. Umgekehrt sieht der Fahrgast keine private Telefonnummer des Fahrers; protokolliert werden nur Metadaten (Zeit, Richtung, Ergebnis), keine Inhalte.
- **Tests:** Positiv: Kontakt über Relay-Simulator. Negativ: API-Antworten und Logs des Fahrerclients werden auf Telefonnummernmuster geprüft; nicht zugeordneter Fahrer; Kontakt nach Sitzungsende. Konkurrierend: Fahrzeugwechsel während aktiver Sitzung → alter Fahrer verliert, neuer erhält Zugang.
- **Abhängigkeiten:** intern: WP-M7b-24.
  - extern: WP-M1-35 (zeitlich begrenzte Fahrerfreigabe mit minimierter Projektion (WP-M1-35))
  - extern: WP-M3-26, WP-M3-32 (Stoppliste des Fahrers, Unterstützungshinweise nur für zugeordnetes Personal)
- **Entscheidungen:** D-11 (minimale Kontakthaltung, Löschfristen) – blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Kontaktrelay gegenüber Partnerbetreibern (Kap. 02 §3, M6a/M6b); In-App-Textnachricht des Fahrers an den Fahrgast (WP-M7c-03, `LH-6.6-DRV-009`) – dieses WP stellt die Sitzung und die nummernfreie Projektion bereit, auf die WP-M7c-03 aufsetzt (OP-14).

### WP-M7b-31 – Ruhezeiten für nichtkritische Meldungen
- **Ziel:** Nichtkritische Meldungen werden außerhalb des erlaubten Zeitfensters zurückgehalten, ohne notwendige Fahrtinformationen zu blockieren.
- **Anforderungen:** `LH-6.12-COM-010`
- **Liefergegenstände:**
  - Backend: Ruhezeitprüfung im Gate aus WP-M7b-24 anhand Nachrichtenzweck/Kritikalität und Ortszeit des Mandanten bzw. Dienstes; Verhalten für zurückgehaltene Nachrichten (verwerfen oder nach Ruhezeit zustellen) gemäß Detailvertrag; Kennzeichnung im Status.
  - Datenbank: `notification` (Zurückhaltungsgrund, geplanter Versandzeitpunkt).
  - Konfigurationsparameter: `notification_quiet_hours`, `reporting_timezone` (nur falls als Zeitbasis festgelegt), `customer_notify_before_pickup_min` (bleibt als notwendige Fahrtinformation ausgenommen).
  - Oberfläche: UI-07 – Hinweis „in Ruhezeit zurückgehalten" am Auftrag.
- **Detailverträge vorab:** Zuordnung Ereignistyp → kritisch/nichtkritisch; Zeitbasis (siehe Offene Punkte); Verhalten nach Ende der Ruhezeit.
- **Akzeptanzkriterien:**
  1. Ein Werbehinweis bleibt außerhalb des erlaubten Zeitfensters aus (COM-010).
  2. Notwendige Fahrtinformationen (Bestätigung, ETA-Änderung, Ankunft, Storno, Ausfall) und Sicherheitsalarme (Katalogvalidierung „Sicherheitsalarme ausgenommen") werden auch in der Ruhezeit zugestellt (COM-010).
  3. Die Ruhezeit ist DST-fest und über Mitternacht korrekt.
  4. Eine Änderung von `notification_quiet_hours` wirkt ab ihrem Wirksamkeitszeitpunkt, nicht rückwirkend.
- **Tests:** Positiv: Werbehinweis um 23:00 bei Ruhezeit 22–07 Uhr wird nicht zugestellt. Negativ: Fahrtinformation zur selben Zeit wird zugestellt; ungültige Zeitspanne wird vom Schema abgelehnt. Grenzfälle: DST-Umstellungsnacht, Fenster über Mitternacht.
- **Abhängigkeiten:** intern: WP-M7b-24.
  - extern: WP-M1-21, WP-M1-18 (wirksame Konfiguration, Zeit-/DST-Baustein)
- **Entscheidungen:** keine
- **Größe:** S
- **Nicht im Umfang:** individuelle Ruhezeiten je Fahrgast; fachliche Einstufung der Mitarbeiter-Mitteilungen (WP-M7d-21) und der Fahrer-Betriebsinformationen (WP-M7c-04) – beide nutzen die hier gelieferte Ruhezeitprüfung als Abnehmer.

### WP-M7b-32 – Betroffene Fahrgäste gezielt informieren
- **Ziel:** Die Leitstelle informiert die von einer Störung oder Umdisposition betroffenen Fahrgäste gezielt über einen freigegebenen Kanal; Versandresultat und Empfänger sind nachvollziehbar protokolliert.
- **Anforderungen:** `LH-6.7-OPS-008`
- **Liefergegenstände:**
  - Backend: Ermittlung der Betroffenen aus Incident, Fahrzeug, Fahrt oder Auswahl in der Auftragsliste; Auftrag über WP-M7b-24 mit Zweck „betrieblich"; Kanalwahl nur aus freigegebenen Kanälen und verifizierten Kontakten; Versandprotokoll je Empfänger mit Status.
  - Datenbank: `notification`, `notification_attempt`; Verknüpfung zum Incident; `audit_event` für die Versandauslösung.
  - API: `/v1/notifications` (Auftrag mit Incident-/Buchungsbezug), `/v1/operator/incidents` (Kommunikationsverlauf).
  - Oberfläche: UI-07 – „Fahrgast informieren" an Fahrt, Fahrzeug und Incident: Empfängerliste, Kanal, Text, Ergebnisübersicht (versendet/zugestellt/fehlgeschlagen/unbekannt je Empfänger).
- **Detailverträge vorab:** Betroffenheitsregel je Auslöser; Protokollschema; Rolle, die versenden darf.
- **Akzeptanzkriterien:**
  1. Die Leitstelle kann betroffene Fahrgäste gezielt über einen freigegebenen Kanal informieren; nicht freigegebene Kanäle sind nicht wählbar (OPS-008).
  2. Versandresultat und Empfänger sind je Nachricht nachvollziehbar protokolliert und am Incident bzw. an der Buchung einsehbar (OPS-008).
  3. Fahrgäste ohne erreichbaren Kanal werden ausgewiesen, nicht still übergangen.
  4. Nur Fahrgäste mit Bezug zur Störung erhalten die Nachricht; kein Fahrgast erhält sie doppelt.
  5. Die Betriebsinformation ist kein Marketing: sie setzt keine Marketing-Einwilligung voraus, wird aber nur an verifizierte Kontakte und nicht an Fahrgäste anderer Mandanten gesendet.
  6. Die Auslösung ist mit Akteur, Anlass (Incident/Fahrt/Fahrzeug) und Empfängerzahl auditiert.
- **Tests:** Positiv: Incident mit drei Buchungen → drei protokollierte Nachrichten; Fahrgastkommunikation in AT-50 bleibt konsistent. Negativ: Fahrgast ohne verifizierten Kontakt; Versand ohne Rolle; fremder Mandant. Konkurrierend: zwei Disponenten lösen dieselbe Information gleichzeitig aus → ein Versand je Empfänger.
- **Abhängigkeiten:** intern: WP-M7b-12, WP-M7b-24, WP-M7b-25.
  - extern: WP-M3-54, WP-M3-37 (Störungsablauf mit betroffenen Fahrten, Auftragsliste)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** vorformulierte Texte und Empfängerprüfung als eigener Schritt (WP-M7b-33); Information über Partnerabschnitte (M6a).

### WP-M7b-33 – Vorformulierte Incident-Information mit Empfängerprüfung
- **Ziel:** Der Support sendet aus einem Incident eine vorformulierte Information an alle betroffenen Fahrgäste und prüft vor dem Versand die Empfängermenge.
- **Anforderungen:** `LH-6.12-COM-011`
- **Liefergegenstände:**
  - Backend: Incident-Vorlagen je Störungstyp und Sprache mit Platzhaltern (Dienst, Gebiet, erwartete Dauer, Alternative); Empfängervorschau mit Anzahl, Kanalverteilung und Ausschlüssen (kein Kanal, keine Einwilligung); Bestätigungsschritt vor Versand.
  - Datenbank: `channel_template_revision`; Vorlagenrevision am `notification`-Auftrag.
  - API: Vorschau und Versand unter `/v1/notifications` mit Incident-Bezug.
  - Oberfläche: UI-07/UI-06 – Dialog „Alle Betroffenen informieren": Vorlage wählen, Empfängermenge prüfen, einzelne Empfänger ausschließen, senden.
- **Detailverträge vorab:** Vorlagenkatalog je Störungstyp; Vorschauschema; Gültigkeit der Vorschau bei geänderter Betroffenheit.
- **Akzeptanzkriterien:**
  1. Die Empfängermenge kann vor dem Versand geprüft werden; ohne bestätigte Vorschau ist kein Versand möglich (COM-011).
  2. Der Versand erreicht genau die in der bestätigten Vorschau enthaltenen Betroffenen; ändert sich die Betroffenheit zwischenzeitlich, wird eine neue Bestätigung verlangt.
  3. Die Nachricht verwendet die Vorlage in der Sprache des Fahrgasts und behält die Buchungsreferenz.
- **Tests:** Positiv: Vorlage „Verspätung" an fünf Betroffene nach Vorschau. Negativ: Versand ohne Vorschau; Vorlage ohne Übersetzung → definierte Rückfallsprache. Konkurrierend: neue betroffene Buchung zwischen Vorschau und Versand → erneute Bestätigung.
- **Abhängigkeiten:** intern: WP-M7b-32.
- **Entscheidungen:** keine
- **Größe:** S
- **Nicht im Umfang:** Supportfall-Vorlagen (WP-M7b-22); Massen-Push ohne Incident (WP-M7b-26).

### WP-M7b-34 – Begrenzte Protokollierung und automatische Löschung von Benachrichtigungen
- **Ziel:** Alle Benachrichtigungen werden nur im datenschutzrechtlich zulässigen Umfang protokolliert; Lösch- und Aufbewahrungsregeln werden automatisiert eingehalten.
- **Anforderungen:** `LH-6.12-COM-012`
- **Liefergegenstände:**
  - Backend: Protokollklassen für Benachrichtigungen (Metadaten, Inhalt, Empfängerkontakt, Providerantwort) mit je eigener Aufbewahrungsregel; Minimierung (kein Volltext/keine Kontaktdaten in technischen Logs und Traces); automatischer Lösch-/Anonymisierungslauf mit Nachweis; Beachtung von Legal Hold.
  - Datenbank: `retention_rule`, `erasure_job`; `notification`, `notification_attempt`, Kampagnen-, Banner- und Relay-Ablagen als Löschziele; Partitionierung als Löschmechanik (keine Sicherheitsgrenze, Kap. 02 §4.2).
  - API: Regelpflege und Löschnachweis über die vorhandenen Konfigurations- und Datenschutzwege (`/v1/config/revisions`).
  - Oberfläche: UI-20 – Anzeige der geltenden Aufbewahrungsregeln und des letzten Löschlaufs für Benachrichtigungen.
  - Adapter/Worker: Löschjob über DB und Provider-Protokolle, soweit vom Provider unterstützt.
- **Detailverträge vorab:** Löschmatrix Benachrichtigungen (Datenklasse × Frist × Löschart) – Fristen sind Auftraggeberentscheidung (siehe Offene Punkte); Feldliste der begrenzten Protokollierung.
- **Akzeptanzkriterien:**
  1. Ein Testdatensatz jeder Benachrichtigungsklasse wird nach Ablauf der freigegebenen Frist automatisch gelöscht oder anonymisiert; der Lauf ist nachgewiesen (COM-012).
  2. Technische Logs und Traces enthalten weder Nachrichteninhalt noch Kontaktdaten (COM-012).
  3. Ein Datensatz unter Legal Hold bleibt erhalten und wird ausgewiesen.
  4. Nach der Löschung bleibt die Zustellstatistik ohne Personenbezug auswertbar.
  5. Ein Restore spielt das Löschjournal vor Freigabe wieder ein (Kap. 01 §1.7).
- **Tests:** Positiv: Fristablauf mit steuerbarer Uhr → Löschung und Nachweis. Negativ: Löschlauf im Kontext eines Mandanten berührt keine Daten eines anderen; Legal Hold. Konkurrierend: Löschlauf parallel zu eintreffender Provider-Rückmeldung für eine zu löschende Nachricht → kein Wiederaufleben personenbezogener Daten.
- **Abhängigkeiten:** intern: WP-M7b-24, WP-M7b-25.
  - extern: WP-M1-33, WP-M1-32 (Aufbewahrungs-, Archivierungs- und Löschregeln (WP-M1-33), Dateninventar)
- **Entscheidungen:** D-11 (Löschfristen, Legal Holds) – blockiert nur die Produktivschaltung; der Bau erfolgt mit parametrierbaren Fristen.
- **Größe:** M
- **Nicht im Umfang:** Auskunfts-/Löschanfragen einzelner Personen (`/v1/privacy/requests`, M8); Aufbewahrung von Supportfällen und Betriebsjournal (jeweiliges WP).

### WP-M7b-35 – Callcenter-Arbeitsplatz UI-06: Rahmen, Rollennavigation und Zustände
- **Ziel:** Der Callcenter-Arbeitsplatz UI-06 existiert als eigener, rollengetrennter Bereich der Operator-Webanwendung mit einheitlichen Zuständen, auf dem die Callcenter- und Support-WPs ihre Ansichten aufbauen.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Oberfläche: UI-06 – Bereich „Callcenter" in `web/` (Operator-Anwendung, gemeinsames Designsystem) mit getrennter Rollennavigation (Kap. 01 §1.1), Kopf mit Mandant/Dienst, fachlichem Status und Datenstand (Kap. 06 §6.1); wiederverwendbare Zustandsbausteine leer, lädt, Fehler, keine Rechte, veralteter Datenstand, 412-Konflikt; Zustände „Vertretungsumfang, Zustimmung und Kontaktkanal fehlen" (Kap. 06 §6.9) als feste Bausteine; unabhängiger Kontext je Browser-Tab und Rücksetzen offener Formulare bei Mandantenwechsel.
  - Backend: BFF-Routen und Sitzungskontext für die Rollen Callcenter und Support; Navigation zeigt nur Einträge, für die die Sitzung eine Berechtigung aus der Rechtematrix (WP-M7b-01) besitzt – die Anzeige ist keine Rechtequelle, jede API prüft erneut.
  - API: `/v1/me` (Rollen und Berechtigungen der Sitzung für die Navigation).
- **Detailverträge vorab:** Navigationsstruktur UI-06; Zustandskatalog aus WP-M7b-01; Barrierefreiheits-Checkliste (WCAG 2.2 AA als Abnahmeziel, Reflow bei 320 CSS-Pixeln, 200-%-Textvergrößerung, Status nie nur als Farbe, Live-Region für asynchrone Meldungen).
- **Akzeptanzkriterien:**
  1. Eine Callcenter-Rolle sieht den Bereich UI-06 und keine Leitstellen-, Finanz- oder Personalnavigation; eine Rolle ohne Callcenter-Recht erhält in UI-06 den Rechtezustand und von der API eine Ablehnung.
  2. Jede Ansicht in UI-06 zeigt Mandant/Dienst und Datenstand; die sechs Zustandsbausteine sind in einer Musteransicht abnehmbar.
  3. Zwei Tabs mit unterschiedlichen Mandanten führen ihren Kontext unabhängig; nach Mandantenwechsel bleibt kein fremdes offenes Formular bestehen (Kap. 06 §6.9, UI-Abnahme „Mandantenwechsel mit zwei Tabs").
  4. Der Rahmen ist vollständig per Tastatur und Screenreader bedienbar; der Fokus ist sichtbar.
- **Tests:** Positiv: Anmeldung als Callcenter-Rolle, Navigation, Zustandsbausteine. Negativ: Rolle ohne Recht; Direktaufruf einer UI-06-Route mit Sitzung eines anderen Mandanten (AT-18); manipulierter Mandantenkontext im Client ändert die Serverberechtigung nicht. Automatisierte Barrierefreiheitsprüfung des Rahmens.
- **Abhängigkeiten:** intern: WP-M7b-01, WP-M7b-02.
  - extern: WP-M1-07, WP-M1-06 (Rollenmodell, `/v1/me`, serverseitiger Mandantenkontext)
  - extern: WP-M2-35 (Designsystem und Webgrundgerüst (Passenger-PWA))
  - extern: WP-M3-36 (Operator-Webanwendung UI-07 als Rahmen)
- **Entscheidungen:** keine
- **Größe:** S
- **Nicht im Umfang:** Fachansichten (WP-M7b-17 bis WP-M7b-23); Leitstellenansichten UI-07; native Apps.

### WP-M7b-36 – M7b-Gate-Abnahmesuite
- **Ziel:** Die Referenztests der Einheit laufen automatisiert und liefern die Evidenz für das M7-Gate.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Tests in `tests/acceptance/`: AT-12 (Callcenter kontra App; Oberflächenanteil UI-06, API-Anteil gemeinsam mit WP-M7a-12/WP-M7a-40), AT-17 (Massen-Push Zielgruppe) als ausführbare Szenarien; AT-06 (Serienbuchung mit Ausnahme) zusätzlich über den Callcenter-Kanal; Regressionsläufe AT-07, AT-09, AT-10, AT-15, AT-18, AT-24, AT-50 mit den Erweiterungen der Einheit; Beitrag zu E2E-100 (Anzeige der Partnerübernahme als Option im Incident) und zu AT-44 (Umplanung der von einem Fahrerausfall betroffenen Fahrten über WP-M7b-04); Muster PH-AT-001 (Isolation über Detail-, Listen-, Such- und Exportendpunkte der Einheit) und PH-AT-004 (letzter Platz mit gemischten Kanälen); UI-Abnahme „gleicher Test mit Callcenter" per Tastatur/Screenreader für den Einbetreiberfall, „412-Konflikt ohne stilles Überschreiben" und „Mandantenwechsel mit zwei Tabs" (Kap. 06 §6.9).
  - Nachweisliste: je Anforderung der Einheit (30) die zugeordneten Testfälle; für SOLL-Anforderungen ohne grünen Test die dokumentierte SOLL-Abweichung mit Begründung zur AG-Freigabe (Kap. 08 §8.4).
  - Evidenz: Eintrag in `e2e.csv` mit Build, Migrationsstand, Konfigurationsrevision, Seed, Rollen/Mandanten, Soll/Ist, Trace-IDs (Kap. 08 §8.4); Pflege von `arbeitspakete.csv` (Spalten `detailvertrag`, `testfall`).
- **Detailverträge vorab:** Testdrehbuch je Referenztest mit Fixture und erwarteten Messpunkten.
- **Akzeptanzkriterien:**
  1. AT-12 und AT-17 sind grün und mit vollständiger Evidenz eingetragen.
  2. Jede der 13 MUSS-Anforderungen der Einheit hat mindestens einen grünen positiven Test; Geld-, Kapazitäts-, Sicherheits- und Datentrennungsregeln zusätzlich negative und konkurrierende Tests.
  3. Die Callcenter-Kernreise (UI-06) ist vollständig per Tastatur und Screenreader ausführbar.
  4. Kein Test der Einheit benötigt Superuser, BYPASSRLS oder Produktionsdaten.
  5. Jeder Detail-, Listen-, Such- und Exportendpunkt der Einheit ist im Isolationslauf enthalten: bekannte UUID eines fremden Mandanten liefert weder Daten noch Existenzhinweis.
  6. Jede neue Ansicht der Einheit in UI-06, UI-07 und UI-20 zeigt Leer-, Fehler- und Rechtezustand; Pflichtaktionen sind ohne Karte und ohne Drag-and-drop ausführbar (Kap. 08 §8.4, Gate UX).
  7. Jede der 17 SOLL-Anforderungen hat einen grünen Test oder eine freigegebene SOLL-Abweichung.
  8. Die Evidenz enthält keine unnötigen Fahrgast- oder Personaldaten.
- **Tests:** AT-12, AT-17; AT-06 über Callcenter; Regression AT-07, AT-09, AT-10, AT-15, AT-18, AT-24, AT-50; Beitrag E2E-100 und AT-44; PH-AT-001- und PH-AT-004-Muster; UI-Abnahmen nach Kap. 06 §6.9. Negativ und konkurrierend: Zusammenstellung der in den Fach-WPs genannten Fälle für Geld (WP-M7b-23), Kapazität (WP-M7b-04, WP-M7b-18), Sicherheit (WP-M7b-15, WP-M7b-17, WP-M7b-30) und Datentrennung (WP-M7b-02, WP-M7b-24, WP-M7b-26).
- **Abhängigkeiten:** intern: WP-M7b-04 bis WP-M7b-35.
  - extern: WP-M0-22, WP-M0-24, WP-M0-23 (Akzeptanztestgerüst, Sandbox-Partner, PSP-Simulator)
  - extern: WP-M7a-07, WP-M7a-12 (Serienbuchung für AT-06 über Callcenter; API-Anteil von AT-12)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** übrige M7-Gate-Tests (AT-06, AT-38, AT-42 bis AT-44, AT-46, AT-52, AT-56, AT-57, AT-62, AT-63, E2E-069, E2E-071) – M7a, M7c, M7d.

## Offene Punkte

- **OP-01 Incident ohne Tabelle** (`LH-6.13-SUP-005`, `LH-6.7-OPS-013`, `LH-6.13-SUP-006`): Kap. 05 §5.7 nennt `/v1/operator/incidents`, Kap. 04 §4.4 enthält nur `business_alert`, `alert_policy_revision`, `alert_acknowledgement`. Ablage, Typ- und Schweregradkatalog sowie die Betroffenheitsverknüpfungen der Betriebsstörung sind zu spezifizieren (gleicher Punkt wie OP-02 in M3).
- **OP-02 Fehlende Tabellen der Kommunikation** (`LH-6.12-COM-004`, `LH-6.12-COM-005`, `LH-6.12-COM-008`): Kap. 04 §4.4 nennt für Kommunikation nur `notification`, `notification_attempt`, `channel_template_revision`. Kampagne/Zielgruppe, Banner und Relay-Sitzung haben keine Tabelle und keinen Endpunkt außer `/v1/notifications`.
- **OP-03 Betriebsjournal und temporäre Sperre ohne Datenmodell** (`LH-6.7-OPS-014`, `LH-6.7-OPS-012`): weder Tabelle noch Endpunkt im Pflichtenheft. Unklar ist auch, was eine „Schicht" des Journals ist (Leitstellenschicht oder Fahrerschicht) und welche Abweichungen „wesentlich" sind.
- **OP-04 „Sonderbetriebsplan"** (`LH-6.7-OPS-012`): Begriff im Pflichtenheft nicht definiert (abweichende Betriebszeiten, Sonderdienst, Ersatzfahrplan?). Die Abnahme prüft nur die automatische Rücknahme einer befristeten Sperre.
- **OP-05 Abgrenzung zu M7a GEO** (`LH-6.7-OPS-012`): Sperrbereiche und verbotene Strecken mit Versionen liefert M7a (`LH-6.9-GEO-004`, `LH-6.9-GEO-005`, `LH-6.9-GEO-006`). Ob die temporäre Aktivierung der Leitstelle eine eigene Ablage oder eine befristete Revision derselben Objekte ist, ist nicht festgelegt.
- **OP-06 Sperren oder warnen** (`LH-6.7-OPS-007`, `LH-6.7-OPS-009`): Das Pflichtenheft legt nicht fest, welche Konflikte bei Umdisposition bzw. überlappender Zuweisung hart sperren und welche nur warnen („abgelehnt oder warnt"). Harte Grenzen aus M2 (Kapazität, Zusagefenster, Fahrzeugfreigabe) werden als sperrend angenommen; diese Annahme ist zu bestätigen.
- **OP-07 Katalog „kritische Dispositionsentscheidung"** (`LH-6.7-OPS-018`): nicht aufgezählt.
- **OP-08 Kartenlayer Depots** (`LH-6.7-OPS-016`): Für Depots/Betriebshöfe existiert in Kap. 04 keine Geo-Tabelle und in Kap. 05 §5.7 kein Endpunkt; Quelle ist die Organisationsstruktur aus M1 bzw. Inventar/Betriebshöfe aus M7c.
- **OP-09 Verifizierte Identifikatoren und Zustimmung am Telefon** (`LH-6.13-SUP-001`): Das Pflichtenheft nennt weder die zulässigen Identifikatoren noch das Verfahren, mit dem ein Anrufer verifiziert und seine Zustimmung dokumentiert wird (UI-06 nennt nur die Fehlerzustände „Vertretungsumfang, Zustimmung und Kontaktkanal fehlen").
- **OP-10 Reihenfolge Gastbuchung/Vertretung** (`LH-6.13-SUP-001`, `LH-6.13-SUP-003`): Gastbuchung (`LH-6.2-IAM-007`) und Vertretungszugriff (`LH-6.2-IAM-008`) sind SOLL in M8e, Buchung für Dritte (`LH-6.4-BKG-010`) SOLL in M7a; die abhängigen Callcenter-Anforderungen sind MUSS in M7. Da M8 ein von M7 unabhängiger Parallelstrom ist, liefert WP-M7b-17 den Mindestumfang (bedienerseitig angelegter Gastdatensatz, befristete Callcenter-Legitimation) selbst; M8e baut darauf auf. Offen bleibt: (a) Wenn `LH-6.4-BKG-010` (SOLL) in M7a als SOLL-Abweichung entfällt, fehlen `delegated_access` und `/v1/delegations` – dann muss WP-M7b-02/WP-M7b-17 sie liefern; (b) ob die Gastanlage im Callcenter ebenfalls je Betreiber abschaltbar sein muss, sagt nur `LH-6.2-IAM-007`, nicht `LH-6.13-SUP-001`.
- **OP-11 Zahlung bei telefonischer Buchung** (`LH-6.13-SUP-002`): Zahlungsweg und SCA im Callcenter-Kanal sind nicht beschrieben (keine Kartendaten im System, WP-M1-37).
- **OP-12 Katalog „Sonderbedarfe"** (`LH-6.13-SUP-003`): nicht definiert; angenommen werden die Kapazitäts- und Unterstützungsmerkmale des Ressourcenvektors und des Fahrgastprofils.
- **OP-13 Zielgruppendefinition** (`LH-6.12-COM-004`): Unklar, wie ein Fahrgast einem Gebiet/Dienst zugeordnet wird (aktive Buchung, Buchungshistorie, Wohn-/Favoritenort, aktueller Standort) und was „betroffene Fahrgastgruppe" bezeichnet; ebenso, ob ein Massen-Push betrieblich oder einwilligungspflichtig ist.
- **OP-14 Relay-Variante und Provider** (`LH-6.12-COM-008`, `LH-6.12-COM-006`): „anonymisiert oder relay-basiert" lässt maskierte Rufnummer und In-App-Nachricht offen; Push-, SMS- und Relay-Provider sind nicht benannt (kein D-Eintrag in Kap. 07).
- **OP-15 Zeitbasis und Verhalten der Ruhezeit** (`LH-6.12-COM-010`): `notification_quiet_hours` ist als Zeitspanne katalogisiert; offen sind Zeitzone (Mandant, Dienst oder Empfänger), Geltungsebene, die Einstufung kritisch/nichtkritisch je Ereignistyp und ob zurückgehaltene Nachrichten verworfen oder nachgeholt werden.
- **OP-16 Aufbewahrungsfristen** (`LH-6.12-COM-012`, `LH-6.13-SUP-004`, `LH-6.7-OPS-014`): Fristen für Benachrichtigungsprotokolle, Supportfälle und Betriebsjournal fehlen; sie hängen an D-11.
- **OP-17 Schwellwerte der automatischen Alarme** (`LH-6.13-SUP-010`): Für „überfällige Abholung" gibt es keinen eigenen Parameter in `kataloge.csv`; „ungewöhnlich viele Ablehnungen" hat `capacity_reject_alert_count` ohne Messfenster; für GPS wird `eta_max_data_age_sec` angenommen. Abgrenzung zum Nachfragealarm E2E-099 (M8d) ist festzulegen.
- **OP-18 Abgrenzung Incident-Posteingang zu Alert-Center** (`LH-6.7-OPS-013`): `LH-I.8-AUT-004` (M8d) fordert ein Alert-Center für alle Alarmarten; ob beide dieselbe Ansicht sind, ist nicht beschrieben.
- **OP-19 Eskalationskette und Kontakte im Notfall** (`LH-6.13-SUP-007`): „zuständiger Dienst" und Eskalationsziele sind nicht definiert; Betreiber müssen den Notbetriebs-/Kontaktprozess liefern (D-11). Der Auslöser (`LH-6.6-DRV-012`) liegt in M7c.
- **OP-20 Erstattungsregeln** (`LH-6.13-SUP-009`): Berechtigungskriterien, Genehmigungsschwellen und die Rollen für „berechtigt/geprüft/genehmigt" sind nicht festgelegt; `approval_stage_thresholds` ist für Belege katalogisiert, nicht für Erstattungen.
- **OP-21 SLA für Supportfälle** (`LH-6.13-SUP-004`): Die Kennzahl `Kundenkontakt-Auflösung` setzt eine SLA-Definition voraus, die im Pflichtenheft fehlt (D-05 nennt Supportzeiten als offene Angabe).
- **OP-22 E2E-100 im M7-Gate**: Die vertraglich zulässige Partnerübernahme setzt Föderationsfähigkeiten aus M6 voraus; M7b liefert nur die Anzeige als Option im Störungsablauf (`LH-6.13-SUP-006`). Die verantwortliche Einheit für den Test ist festzulegen.
- **OP-23 Abhängigkeit auf Parallelstrom M6** (`LH-6.7-OPS-016`, `LH-6.13-SUP-006`): Der Layer „ÖPNV-Hubs" braucht `transfer_hub` bzw. `/v1/transfer-hubs` aus M6a, die Option „Partnerübernahme" Föderationsfähigkeiten aus M6; M6 und M7 sind laut Umsetzungsplan unabhängig. Ohne M6a kann die Abnahme „jeden Layer getrennt filtern" für ÖPNV-Hubs nur mit leerem Layer oder mit Testdaten gezeigt werden. Festzulegen ist, ob M7b eine minimale Hub-Stammdatenpflege liefern soll oder die Abnahme dieses Layers bis M6a zurückgestellt wird.
- **OP-24 Überschneidung Notfallalarm mit M7c** (`LH-6.13-SUP-007`): WP-M7c-06 (`LH-6.6-DRV-012`, `LH-6.22-DMG-021`) plant bereits Alarm, UI-07-Anzeige „oben in der Arbeitsliste", Eskalation und Rückmeldeworkflow. WP-M7b-15 plant Konsument, priorisierte Anzeige und Eskalation. Vor Baubeginn ist festzulegen, welche Einheit Konsument, Incident-Erzeugung und Leitstellenanzeige baut; sonst entsteht Doppelarbeit oder eine Lücke bei Ereignis-ID und „zuständigem Dienst".
- **OP-25 Überschneidung Fahrerkanal mit M7c** (`LH-6.12-COM-007`, `LH-6.12-COM-008`): WP-M7c-04 (`LH-6.6-DRV-010`) liefert priorisierte Betriebsinformationen mit Lesebestätigung, WP-M7c-03 (`LH-6.6-DRV-009`) nummernfreie Fahrernachrichten. Ob „Quittierung des Betriebsalarms" (COM-007) und „Lesebestätigung" (DRV-010) derselbe Mechanismus und dieselbe Ablage sind (`alert_acknowledgement` gegenüber einer eigenen Lesebestätigung in M7c), ist nicht festgelegt; ebenso die Baureihenfolge zwischen WP-M7b-29/WP-M7b-30 und WP-M7c-03/WP-M7c-04.
- **OP-26 Zuständigkeit AT-12 und AT-06**: M7a führt AT-12 als eigenen Gate-Anteil und verweist für den Oberflächenanteil auf UI-06 aus M7b; WP-M7b-36 weist AT-12 ebenfalls nach. Die Evidenzverantwortung (ein Eintrag in `e2e.csv`) ist einer Einheit zuzuordnen. AT-06 über den Callcenter-Kanal ist ein Zusatzlauf, nicht der Gate-Nachweis von M7a.
- **OP-27 Modulzuordnung Erstattungsfall** (`LH-6.13-SUP-009`): Kap. 01 §1.3 führt RefundCase im Modul Fare/Payment, Kap. 04 §4.4 `refund_case` unter PAY; die Anforderung steht im Supportabschnitt 6.13. Geplant ist: Aggregat in `fare-payment`, Auslösung und Fallbezug in `communication-support`. Zu bestätigen ist außerdem, ob M2 `refund_case` bereits für die Storno-Erstattung anlegt (dann erweitert WP-M7b-02 nur).
- **OP-28 Fremde Nutzer des Supportfalls** (`LH-6.13-SUP-004`): M7c erwägt Fundsachen als `support_case` (dort OP-15, AT-62), M7a nennt eine redigierte Feedback-Liste in der Supportsicht. Beide Verwendungen sind im Pflichtenheft nicht beschrieben; der Kategorienkatalog des Supportfalls (WP-M7b-20) muss sie entweder aufnehmen oder ausdrücklich ausschließen.
- **OP-29 Automatische Eskalation der Störung** (`LH-6.13-SUP-005`): `alert_escalation_delay_min` ist je Alerttyp katalogisiert („Kritische Ereignisse ohne Verzögerung"); das Pflichtenheft sagt nicht, ob die Verzögerung ab Eröffnung, ab letzter Bearbeitung oder ab fehlender Quittierung läuft und an wen eskaliert wird.
- **OP-30 Umdisposition und Fahrgastzustimmung** (`LH-6.7-OPS-007`): Kap. 06 §6.5 verlangt, dass verbindliche Änderungen „Folgen, Preis und erforderliche Zustimmung" erklären. Wann eine Umdisposition die Zustimmung des Fahrgasts braucht (z. B. anderes Fahrzeugmerkmal bei unverändertem Zusagefenster), ist nicht festgelegt; angenommen wird: keine Zustimmung, solange Zusagefenster, Preis und zugesagte Ausstattung unverändert bleiben.
