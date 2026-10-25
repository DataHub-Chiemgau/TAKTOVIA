# 08 – Anforderungsabdeckung und Abnahme

Version 0.1 · 25.10.2026 · Technischer Entwurf; keine Softwareabnahme.

## 8.1 Quellenstand und festgestellte Widersprüche

Grundlage ist die hochgeladene Datei `Lastenheft_On_Demand_Mobilitaetsplattform_V1_2.docx` vom 25.10.2026. SHA-256: `9f799a0617b5e9a487041c9fe4475957be48fafacf216196bb088f555ea49165`. Die Werte unten wurden aus den tatsächlichen Anforderungs- und Katalogzeilen ermittelt und mit den DOCX-Tabellen abgeglichen.

**Die Quelle enthält 672 Anforderungszeilen, aber nur 660 eindeutige Anforderungs-IDs.** `COM-001` bis `COM-012` kommen jeweils zweimal mit verschiedenen Bedeutungen vor: §6.12 „Kommunikation, Push, SMS und Informationsbanner“ und Anhang I.9 „Kooperationsverträge, Anbieterabrechnung und Revenue Clearing“. Es gibt 35 Fachabschnitte mit 34 verschiedenen Modulpräfixen. Die Anforderungen werden weder zusammengeführt noch still umnummeriert. Als technische Referenz dient der eindeutige `source_key`, etwa `LH-6.12-COM-001` und `LH-I.9-COM-001`. Im Fließtext sind diese Anforderungen mit Abschnitt zu zitieren. Ein späterer bereinigter Lastenheftstand muss eine explizite Alias-Tabelle liefern.

Die Summen in der Einleitung von §6 (`P1=152`, `P2=219`, `P3=54`; `MUSS=265`, `SOLL=145`, `KANN=15`) zählen noch den früheren Umfang von 425 Anforderungen. Nur die Gesamtsumme wurde dort auf 672 geändert. Maßgeblich für diesen Entwurf sind die Prioritäten und Phasen der einzelnen Zeilen:

| Phase | MUSS | SOLL | KANN | Gesamt |
|---|---:|---:|---:|---:|
| P1 | 202 | 0 | 0 | 202 |
| P2 | 263 | 135 | 0 | 398 |
| P3 | 9 | 46 | 17 | 72 |
| Gesamt | 474 | 181 | 17 | 672 |

Die 106 Referenztests heißen in der Quelle **AT-01 bis AT-63** (§11.2) und **E2E-064 bis E2E-106** (Anhang N). Es existieren keine Originaltests „E2E-001 bis E2E-063“. Zusätzlich wurden 80 eindeutige Parameter und 48 KPI-Definitionen gezählt. Letztere haben in Anhang K Namen, aber keine eigenständigen numerischen Quell-IDs; ihre `source_key`-Werte im Export sind technische Fundstellen, keine erfundenen Lastenheft-IDs.

## 8.2 Lieferumfang der Nachverfolgbarkeit

- [anforderungen.csv](anforderungen.csv): 672 Zeilen, jeweils Original-ID, eindeutige Fundstelle, Modul, abgeleiteter Kurztitel, unveränderte Phase/Priorität, Originalbeschreibung und Originalakzeptanz, technischer Baustein, zugehörige Pflichtenheftdatei und Prüfstrategie.
- [e2e.csv](e2e.csv): alle 106 Originaltests mit Testansatz und Soll-Ergebnis. Status zunächst `Nicht ausgeführt`; Build, Evidenz und Abnahmeentscheidung sind leer.
- [kataloge.csv](kataloge.csv): alle 80 Parameter und 48 Kennzahldefinitionen einschließlich Originaltypen, Ebenen, Nennern und Ausschlüssen.

Alle Exporte sind UTF-8-CSV mit Semikolon als Trennzeichen und standardmäßig quotierten Feldern. Die Quelle führt keine Anforderungstitel als eigenes Feld; `titel_abgeleitet` ist deshalb nur eine aus dem Text gekürzte Navigationshilfe. `beschreibung_original` und `akzeptanz_original` sind die fachliche Referenz.

**Abdeckung ist nicht gleich Detailfreigabe:** Jede Anforderung ist einem technischen Baustein zugeordnet. Die Schwerpunktkapitel konkretisieren Mandanten, Föderation und Kosten sowie ausgewählte Kernverträge. Die Matrix behauptet keine vollständige Einzelentwurfs- oder Testabdeckung sämtlicher 672 Anforderungen. Für noch nicht spezifizierte Einzelendpunkte, Felder, Randfälle und P2/P3-Oberflächen sind Detailverträge vor ihrer Implementierung zu ergänzen. Kein Eintrag ist als implementiert oder getestet markiert. Dies betrifft insbesondere die vollständigen 80 Konfigurationsschemas, 48 ausführbaren Kennzahldefinitionen, alle Standardadapter und die jeweiligen Geräte-/Offline-Sonderfälle.

Originalphasen bleiben erhalten. Die technische Grundlage für Mandantenisolation, revisionsfeste Zusagen, Decimal-Arithmetik und einmalige Geschäftswirkung gehört in P1; die überwiegend als P2 eingeordnete Mehrbetreiberbuchung wird hier bereits vertieft spezifiziert. Ein früherer Produktivtermin für Föderation/Clearing ist ein expliziter Releasebeschluss, keine stillschweigende Umpriorisierung.

## 8.3 Modulzuordnung ohne Scope-Verlust

In der Phasenspalte stehen Anzahlen P1 / P2 / P3. Die konkrete Artefaktzuordnung und Prüfstrategie stehen zusätzlich in jeder CSV-Zeile.

| Quellabschnitt | Modul | Anzahl | P1 / P2 / P3 | Technischer Baustein |
|---|---|---:|---:|---|
| 6.1 | SYS – Plattform, Mandanten und Betriebsmodelle | 12 | 7 / 4 / 1 | Tenant-/Service-Konfiguration; Zustandsautomaten |
| 6.2 | IAM – Identität, Konten und Berechtigungen | 13 | 8 / 5 / 0 | IAM; OIDC; Rollen-/Objektberechtigung |
| 6.3 | PAX – Passenger App: Suche, Buchungsübersicht und Reisebegleitung | 18 | 9 / 7 / 2 | Passenger Web/App; Reisebegleitung |
| 6.4 | BKG – Buchung, Serien, Gruppen und Kapazitäten | 19 | 8 / 10 / 1 | Booking; Kapazität; Angebotsrevision |
| 6.5 | OPT – Ridepooling, Routing und automatisierte Disposition | 23 | 11 / 8 / 4 | Dispatch; Routing; Constraint Validator |
| 6.6 | DRV – Vehicle App, Fahrerarbeitsplatz und Navigation | 20 | 8 / 11 / 1 | Driver App; Outbox; Navigation |
| 6.7 | OPS – Operator: Leitstelle, Disposition und operative Steuerung | 18 | 6 / 11 / 1 | Operator; Disposition; Incident |
| 6.8 | FLT – Flotte, Fahrer, Schichten und E-Mobilität | 15 | 4 / 5 / 6 | Fleet; Einsatzfreigabe; Schichten; Energie |
| 6.9 | GEO – Bediengebiete, Haltestellen und Straßenrestriktionen | 13 | 3 / 8 / 2 | Geo; Servicegebiete; Haltepunkte |
| 6.10 | PAY – Tarife, Guthaben, Zahlung, Rabatte und Trinkgeld | 17 | 4 / 12 / 1 | Fare; Payment; Wallet; Refund |
| 6.11 | PTI – ÖPNV-Integration und Anschlussmanagement | 13 | 0 / 11 / 2 | Journey Planning; ÖPNV-Adapter |
| 6.12 | COM – Kommunikation, Push, SMS und Informationsbanner | 13 | 3 / 9 / 1 | Notification; Push; SMS; In-App Banner |
| 6.13 | SUP – Callcenter, Kundenservice und Störungsmanagement | 13 | 0 / 10 / 3 | Callcenter; Supportfälle; Incident |
| 6.14 | ANA – Reporting, Controlling und Mobilitätsdaten | 14 | 3 / 9 / 2 | Analytics; Exporte; Mobilitätsdaten |
| 6.15 | AI – KI-Nachfrageprognose und Betriebsoptimierung | 9 | 0 / 0 / 9 | Forecast; Model Registry; Monitoring |
| 6.16 | SIM – Szenariosimulation und Wirtschaftlichkeitsplanung | 9 | 0 / 0 / 9 | Simulation; isolierte Daten-/Rechenumgebung |
| 6.17 | INT – APIs, Datenimporte, Webhooks und externe Systeme | 15 | 4 / 10 / 1 | Integration; API Gateway; Outbox/Inbox |
| 6.18 | SEC – Informationssicherheit, Datenschutz und Compliance | 22 | 13 / 9 / 0 | Security; Privacy; Audit |
| 6.19 | NFR – Nichtfunktionale Anforderungen und Betrieb | 20 | 11 / 8 / 1 | Runtime; Observability; Backup; Accessibility |
| 6.20 | QAT – Qualitätssicherung, Einführung und Abnahme | 12 | 6 / 5 / 1 | CI/CD; Testmanagement; Betriebsübergabe |
| 6.21 | CHK – verpflichtende Fahrer- und Fahrzeugchecks vor Schichtbeginn | 30 | 16 / 13 / 1 | Checklist; Inspection; Readiness |
| 6.22 | DMG – Schäden, Mängel, Werkstatt- und Wiederfreigabeprozess | 29 | 12 / 15 / 2 | Defect; Workshop; Vehicle Release |
| 6.23 | SCH – Schicht- und Personalplanung nach Crewmeister-Funktionsprinzipien | 38 | 10 / 26 / 2 | Roster; Shift; Qualification |
| 6.24 | TIM – Zeiterfassung, Pausen, Urlaub und Abwesenheiten | 20 | 6 / 13 / 1 | Time Tracking; Absence; Time Account |
| I.1 | DOC – Digitale Belege, Tankungen, OCR, Eingangsrechnungen und Buchhaltungsintegration | 30 | 13 / 16 / 1 | Expense; OCR; Supplier Invoice; ERP Export |
| I.2 | FCO – Fuhrpark-, Ressourcen- und Gesamtkostencontrolling | 24 | 5 / 17 / 2 | Cost Ledger; Allocation; TCO; Budgets |
| I.3 | ETA – Lernfähige und sichere fahrerindividuelle Ankunftsprognosen | 27 | 2 / 23 / 2 | ETA; sichere Baseline; geschützte Profile |
| I.4 | MUL – Mehrmandantenfähigkeit, Firmenrollen und Gebietsorganisation | 25 | 7 / 16 / 2 | Tenant; Legal Entity; Cooperation; Grants |
| I.5 | FED – Interoperabilität, Rufbus-Föderation und durchgängige Reiseketten | 33 | 0 / 29 / 4 | Journey Orchestrator; Partner Adapter; Saga |
| I.6 | KPI – Betriebsqualität, Ablehnungsstatistik und Business Intelligence | 35 | 14 / 19 / 2 | Metrics; Demand Funnel; BI; Data Quality |
| I.7 | CFG – Selbstverwaltung und versionierbarer Parameterkatalog | 23 | 7 / 15 / 1 | Configuration Registry; Revision; Policy Validator |
| I.8 | AUT – Automatisierung, Alarme und operative Empfehlungen | 15 | 2 / 12 / 1 | Workflow; Alert; Incident; Rule Revision |
| I.9 | COM – Kooperationsverträge, Anbieterabrechnung und Revenue Clearing | 12 | 0 / 11 / 1 | Cooperation Contract; Clearing; Dispute |
| I.10 | AST – Inventar, Betriebshöfe, Werkstatt und sonstige Betriebsmittel | 11 | 0 / 10 / 1 | Asset; Inventory; Depot; Workshop |
| I.11 | GOV – Governance, Datenschutz, KI-Kontrolle und Qualitätssicherung | 12 | 0 / 11 / 1 | Governance; Privacy; Model Approval |

## 8.4 Abnahmeregeln je Release

Vor Implementierung eines Release werden für **jede enthaltene MUSS-Anforderung** Verantwortlicher, Detailvertrag und mindestens ein positiver ausführbarer Test verknüpft. Sicherheits-, Geld-, Kapazitäts- und Datentrennungsregeln erhalten zusätzlich negative und konkurrierende Tests. P1 umfasst nach dem tatsächlichen Quellkatalog 202 MUSS-Anforderungen; spätere Erweiterungen entbinden nicht von diesen Basispflichten. SOLL-Abweichungen werden mit Begründung und AG-Freigabe dokumentiert; KANN-Funktionen benötigen eine ausdrückliche Aufnahme ins Release.

Die 106 Ausgangstests sind Referenzszenarien und ersetzen die Zuordnung zu allen Einzelanforderungen nicht. Jeder ausgeführte Test erfasst: `source_key[]`, Test-ID/Version, Software-Build, Migrationsstand, Konfigurations-/Tarif-/Vertragsrevision, Seed und Datenstand, Rollen und Mandanten, Soll/Ist, Ereignis-/Trace-IDs, Ergebnis, Mangelklasse, Verantwortlichen und Abnahmeentscheidung. Evidenz darf keine unnötigen Fahrgast- oder Personaldaten enthalten.

| Gate | Erforderlicher Nachweis | Sperrwirkung |
|---|---|---|
| Fachmodell | Review von Zustandsautomaten, Entitäten, Preis-/Kostenregeln und offenen Geschäftsentscheidungen | Ungeklärte Geld- oder Beförderungsrolle verhindert deren produktive Aktivierung |
| CI | Unit-, Datenbankintegrations- und API-Contract-Tests; Migration frisch und aus freigegebenem Vorstand | Fehlgeschlagene Pflichtprüfung verhindert Release |
| Partnerfreigabe | Sandbox-Nachweise je unterstützter Operation; Idempotenz, Statusabfrage, Storno, Erstattung, Ausfall | Nicht geprüfte Fähigkeit bleibt in Capability Registry deaktiviert |
| Sicherheit | RLS-/Rollen-/Objektprüfung, Upload-/Exportberechtigungen, Pentest und behandelte kritische Befunde | Datenoffenlegung, Sicherheits-/Verkehrsgefährdung: keine Freigabe |
| UX | Rollenbezogene Kernreisen mit Tastatur und Screenreader; Pflichtaktionen ohne Karten- oder Drag-and-drop-Zwang | Blockierte Kernreise vor Freigabe beheben |
| Betrieb | Messbares Lastprofil, Restore/Failover, Notbetrieb, Monitoring und Eskalation | Fehlender Wiederanlauf- oder Kernbetriebsnachweis verhindert Pilot |
| Pilot | End-to-End-Betrieb mit freigegebenem Gebiet, Personal, Tarif, Flotte und Prozessverantwortlichen | Abbruchkriterien und Rückfallbetrieb vor Pilotbeginn festlegen |

Die Mangelklassen aus §11.3 gelten weiter: A (u. a. Verkehrssicherheit, Datenoffenlegung, Doppelbuchung, Ausfall Kernbuchung) blockiert Produktion; B benötigt einen freigegebenen Workaround und verbindlichen Behebungstermin; C einen Maßnahmenplan. Ein freigegebener Entwurf ersetzt diese Nachweise nicht.

## 8.5 Ergänzende konkrete Schwerpunktprüfungen

Die folgenden **PH-AT**-IDs sind neu vorgeschlagene technische Abnahmetests. Sie werden nicht als vorhandene oder bereits bestandene Tests dargestellt. Testpartner sind deterministische Sandbox-Adapter; reale Partner erhalten dieselben Vertragsprüfungen soweit ihre Fähigkeiten dies erlauben. AG-freigegebene Produktionsregeln werden separat als versionierte Fixtures hinterlegt.

### PH-AT-001 – Isolation in Datenbank, API, Suche und Export

Bezug: SYS-001, IAM-005, MUL, SEC; AT-18, AT-49, E2E-091.

Fixture: Mandanten A/B mit je einem Fahrgast, Fahrzeug, Beleg und identisch benannter Kostenstelle. Akteur A besitzt nur A-Rechte. Test unter der echten nicht privilegierten Anwendungsrolle, nicht als Tabellenbesitzer/Superuser. Aktionen: Zugriff auf bekannte B-UUID über Detail-, Listen-, Such-, Export- und Medienendpunkt; UPDATE mit fremdem FK; manipulierten Mandantenheader senden. Erwartung: keine B-Daten oder B-Existenzdetails; keine Änderung; gleichartige HTTP-Antwort für fremdes/nicht vorhandenes Objekt gemäß API-Vertrag; Datenbank-FK und RLS verhindern tenantfremde Referenzen; autorisierter A-Zugriff bleibt möglich. Logs enthalten Sicherheitsereignis ohne B-Personen-/Beleginhalt. Test mit leerem Transaktionskontext und nach Connection-Pool-Wiederverwendung wiederholen: kein vorheriger Tenantkontext darf fortwirken.

### PH-AT-002 – Erlaubte Kooperation bei minimaler Datenprojektion

Bezug: MUL, FED-026, FED-027; E2E-085, E2E-101.

Fixture: Vertrag A↔B erlaubt Dienst S, Reise J und Übergabezeit, aber weder Personal-, Einkaufspreis- noch vollständige Fahrgastakte. Erwartung: A kann für J das freigegebene B-Angebot buchen und dessen Segmentstatus sehen; B sieht ausschließlich die für sein Segment notwendigen Kontaktdaten/Unterstützungsmerkmale und Übergabeprognose. Ein Zugriff auf A-Rohdaten, andere Reisen oder B-HR schlägt fehl. Nicht zum Vertrag gehörende Dienste bleiben unbuchbar. Derselbe Test gilt für Cache, Event-Subscription und Report-Download.

### PH-AT-003 – Vertragswiderruf mit laufender Reise

Bezug: I.9/COM-011, MUL; E2E-096, E2E-104.

Fixture: J1 ist vor Wirksamwerden des Widerrufs bestätigt; J2 wird danach angefragt. Die eingefrorene Vertragsrevision enthält eine freigegebene Auslaufregel für J1. Erwartung: J2 erhält keine neue Fremdbuchung. J1 bleibt mit der ursprünglichen Haftungs-/Stornoregel nachvollziehbar und wird ausschließlich über den begrenzten Abwicklungszweck behandelt. Ein widerrufener allgemeiner Zugriff wird auch mit altem Token/Cache nicht wiederhergestellt; ein noch zulässiger Abwicklungszugriff ist explizit auf J1 und erlaubte Felder begrenzt. Gesonderter Test: Datenschutz-/Sicherheitsnotfall entzieht auch Abwicklungsrechte und eröffnet den dokumentierten manuellen Klärprozess.

### PH-AT-004 – Letzter Platz unter Konkurrenz

Bezug: BKG-003, OPT-004, OPT-009; AT-03.

Fixture: Route mit genau einem freien Sitz auf dem überlappenden Abschnitt. 100 parallele Bestätigungen mit unterschiedlichen fachlichen Anfragen. Erwartung: höchstens eine neue bestätigte Sitzreservierung; kein Streckenabschnitt über Kapazität, keine negative Belegung; alle Abweisungen haben eindeutige Gründe. Wiederholung mit freiem Sitz, aber belegtem Rollstuhlplatz und mit veränderter Routenrevision. Ein freier Sitz ersetzt keine fehlende Rollstuhlkapazität.

### PH-AT-005 – Erfolgreiche Zweibetreiberreise

Bezug: FED, I.9/COM-002/003/005; E2E-086, E2E-093.

Fixture: A-Segment 10,00 EUR, B-Segment 15,00 EUR, freigegebener Vertrag, zugänglicher Hub mit fünf Minuten Fußweg plus fünf Minuten Sicherheit. Ankunft A 10:00, Abfahrt B 10:10; beide Adapter können Hold, Confirm, Status und Cancel. Erwartung: zwei eindeutige Betreiberreservierungen, eine Reise und eingefrorene Preispositionen 25,00 EUR; eine Zahlung nach dem freigegebenen PSP-Modell; Endbestätigung erst wenn beide buchbaren Segmente und Zahlungsbedingung bestätigt sind. Gegenprobe B 10:09 ist unzulässig. Kundenbeleg, Segmentansprüche und Clearingpositionen stimmen ohne Vermischung von Beförderungsumsatz und Vermittlungsprovision.

### PH-AT-006 – Nur auskunftsfähiger Linienbus

Bezug: FED; E2E-087.

Fixture: buchbarer Rufbus A plus Linienbus ohne Ticket-/Buchungs-API. Erwartung: Angebot zeigt „nur Auskunft / separat zu buchen“ am Linienbussegment; dessen Preis ist, soweit bekannt, separat als nicht im Checkout enthalten ausgewiesen. Kein Hold, keine erfundene Partnerreservierung, kein Ticket und keine PSP-Belastung für dieses Segment. Eine bestätigte Rufbusbuchung wird nicht als durchgehend gebuchte/garantierte Gesamtreise bezeichnet.

### PH-AT-007 – Partner bestätigt, Antwort geht verloren

Bezug: BKG-012, FED; AT-15, AT-24, E2E-088, E2E-090, E2E-104.

Fixture: Partner B persistiert Confirm, Netzwerk verwirft die Antwort. Erwartung: Segment-/Saga-Schritt bleibt als Ergebnis unbekannt markiert; kein blindes Anlegen einer neuen Buchung und keine definitive Fehlermeldung mit unbewiesenem Storno. Statusabfrage mit ursprünglicher fachlicher Referenz findet die eine Reservierung. Später eintreffende Bestätigung und wiederholte Webhooks erzeugen zusammen genau eine Zustandswirkung. Fehlt die Statusfähigkeit, wird der definierte manuelle Klärfall eröffnet; die UI zeigt keine sichere Endbestätigung. Ein Timeout wird nie allein als Ablehnung interpretiert.

### PH-AT-008 – Zweites Segment scheitert, vollständige Kompensation

Bezug: FED-024; E2E-089.

Fixture: Preis 25,00 EUR; beide Holds vorhanden; Zahlung autorisiert, A bestätigt, B lehnt definitiv ab; Testvertrag gestattet kostenfreies Aufheben von A. Erwartung: A storniert, B-Hold freigegeben, Autorisierung aufgehoben; keine endgültige Kundenzahlung und keine offene Leistungsforderung. Saga, Providerreferenzen, Kompensationsversuche und Kundennachricht bleiben nachvollziehbar. Varianten: Capture ist bereits erfolgt → genau eine passende Erstattung; nicht stornierbarer A-Vertrag → expliziter Klärfall mit ausgewiesener finanzieller Exposition gemäß Haftungspolicy, niemals falsche Erfolgsmeldung.

### PH-AT-009 – Workerabsturz zwischen Fremdaufruf und lokaler Speicherung

Bezug: FED, INT, GOV-008/009; E2E-104.

Fixture: Prozessabsturz direkt nach erfolgreichem externen Confirm bzw. Refund, bevor lokales Ergebnis committed ist. Erwartung: durable Saga-Schritt wird nach Lease-Ende übernommen; vorhandener Idempotenzschlüssel und Statusabfrage verhindern neue Buchung/zweite Erstattung. Fencing-/Versionsprüfung verhindert paralleles Fortschreiben durch alten und neuen Worker. Outbox-Replay verändert keine bereits abschließend verarbeiteten Geschäftsvorfälle.

### PH-AT-010 – Hold läuft während Checkout aus

Bezug: BKG-002, BKG-018, FED.

Fixture: B-Hold hat bei Zahlungsrückkehr seine Frist überschritten; A-Hold ist noch gültig. Erwartung: kein Bestätigen gegen abgelaufene Kapazität; A freigeben und Zahlungsautorisierung abwickeln oder neue Reiseoption mit ausdrücklicher neuer Zustimmung anbieten. Preis-/Umstiegsänderung darf nicht still in die alte Zusage einfließen. Fristen werden gegen Serverzeit mit definiertem Sicherheitsabstand geprüft.

### PH-AT-011 – Unterschiedlicher Request bei gleichem Idempotenzschlüssel

Bezug: BKG-012, PAY, INT.

Fixture: erster Request umfasst Angebot Q1 und Betrag 25,00 EUR; zweiter Request verwendet denselben Schlüssel mit Q2 oder anderem Betrag. Erwartung: Konfliktfehler gemäß API-Vertrag, keine zweite Ausführung und keine Wiederverwendung eines unpassenden Ergebnisses. Gleicher Key/gleicher Body liefert denselben Operation-Identifier. Schlüssel kollidieren nicht zwischen Mandanten, Akteuren und Operationstypen.

### PH-AT-012 – Ein Kostenereignis trotz OCR, Tankkarte und Rechnung

Bezug: DOC, FCO; E2E-064 bis 067, E2E-070.

Fixture: Tankkartenimport und Belegscan betreffen dieselbe Tankung; brutto 119,00 EUR, netto 100,00 EUR, Vorsteuer im Testfall 19,00 EUR; vollständig abzugsfähig laut Test-Steuerregel. Erwartung: genau ein freigegebener Kostenursprung mit 100,00 EUR Kostenbasis und 19,00 EUR Vorsteuer; Zahlung/Verbindlichkeit 119,00 EUR. OCR korrigierbar, Original und Versionen erhalten. Ein zweiter ähnlich aussehender Beleg wird als möglicher Treffer zur Prüfung markiert, nicht ohne Beweis gelöscht oder zusammengeführt. Nochmaliger Freigabe-POST erhöht keine Summe.

### PH-AT-013 – Centgenaue Allokation und getrennte Rechtsträger

Bezug: FCO, I.9/COM-006/008; E2E-072, E2E-094.

Fixture: 100,01 EUR verteilbarer Kostenbetrag und drei gleiche Anteile. Bei dokumentierter Largest-Remainder-Regel und stabiler Schlüsselreihenfolge A/B/C lautet das Ergebnis A=33,34, B=33,34, C=33,33 EUR. Erwartung: Summe exakt 100,01 EUR; erneuter Lauf identisch; kein binärer Float. Referenzfall Eigentümer X / Betreiber Y: Allokation darf weder eine Verbindlichkeit noch eine unberechtigte X-Stammdatenfreigabe erzeugen; eine vertragliche X→Y-Leistung benötigt eigenen Forderungs-/Verbindlichkeitsbezug. Journal muss je Rechtsträger und Währung ausgeglichen sein; ein nur global ausgeglichener mandantenübergreifender Buchungssatz wird abgewiesen.

### PH-AT-014 – Teilstorno nach Zahlung und Auszahlung

Bezug: PAY, FED-024, I.9/COM-005; E2E-093.

Fixture: Reise A=10,00, B=15,00 EUR bezahlt; B wird storniert; Testvertrag erlaubt 12,00 EUR Kundenerstattung und 3,00 EUR Stornoentgelt; Vertrag legt die Rückrechnung der Provision explizit fest. Erwartung: kumulierte Kundenerstattung exakt 12,00 EUR, verbleibender Kundenbetrag 13,00 EUR; A-Leistung bleibt 10,00 EUR. Partnerkorrektur und Provisionskorrektur folgen der eingefrorenen Vertragsrevision und werden separat ausgewiesen. Bereits ausgezahlte Mittel erzeugen eine dokumentierte Rückforderung/Verrechnung statt einer unzulässigen negativen erneuten Auszahlung. Zweiter identischer PSP-Webhook ändert weder Anspruch noch Geldsumme; parallele Erstattungen überschreiten nicht den erstattungsfähigen Betrag.

### PH-AT-015 – Geschlossene Periode, späte Gutschrift, keine Doppelzählung

Bezug: DOC, FCO, KPI-027, I.9/COM-012; E2E-073.

Fixture: Septemberkosten 100,00 EUR abgeschlossen, Gutschrift 20,00 EUR im Oktober. Erwartung: Septemberjournal unverändert, Oktoberkorrektur −20,00 EUR mit Ursprungsbezug; eine Restatement-Ansicht wird ausdrücklich als solche versioniert. Planbudget 90,00 EUR und Istkosten 100,00 EUR werden als zwei Größen dargestellt, nicht zu 190,00 EUR Kosten addiert. Kostenereignis plus seine Allokationen ergibt in der Gesamtansicht weiterhin 100,00 EUR, nicht 200,00 EUR.

### PH-AT-016 – Unzulässige Nutzung fremder Fahrzeuge oder Belege

Bezug: MUL, AST, CHK, GOV-007; E2E-092, E2E-094/095.

Fixture: Fahrer A versucht einen Beleg B-Fahrzeug zuzuweisen. Erwartung: Ablehnung und sicherer A-Entwurf. Mit expliziter aktiver Einsatzfreigabe für B-Fahrzeug in Dienst A ist die freigegebene Ressourcennutzung möglich; Eigentümer, Betriebsträger und Kostenpflichtiger bleiben getrennt. Einsatz ohne passende Qualifikation, Schicht oder bestätigten Check wird trotz Partnervertrag abgewiesen.

### PH-AT-017 – Reihenfolge und Verspätung von Events

Bezug: SYS-007, INT; AT-24, E2E-090/104.

Fixture: `completed` mit Revision 8 trifft vor `in_progress` Revision 7 ein, Revision 8 wird dreifach zugestellt. Erwartung: Deduplikation je Event und Consumer; keine Zustandsrücksetzung auf Revision 7; Lücke/ungewöhnliche Reihenfolge löst Nachlesen aus. Geschäftliche Ereigniszeit, Empfangszeit und Aggregate-Version bleiben getrennt. Technisch at-least-once zugestellte Events haben genau eine fachliche Wirkung, ohne eine globale Exactly-once-Zustellung zu behaupten.

### PH-AT-018 – Konfigurationswechsel, Zusage und DST

Bezug: SYS-006, CFG-003/007/011/021, KPI-006/020; AT-47, E2E-096/098/102/103.

Fixture: am 25.10.2026 in `Europe/Berlin` kommt die lokale Zeit 02:30 zweimal vor; Anfragen werden einmal mit UTC+02 und einmal mit UTC+01 erfasst. Erwartung: zwei eindeutige Zeitpunkte, keine stillschweigende Auswahl; Schichtdauer berechnet sich aus absoluten Zeitpunkten. Eine nachträgliche Tarif- oder Pünktlichkeitsschwelle ändert weder gespeicherte Zusage noch historischen Berichtstand. Die aktuelle Ansicht zeigt die verwendete Version und den lokalen Betriebstag.

### PH-AT-019 – Belegfreigabe und Archivintegrität

Bezug: DOC, SEC, GOV; E2E-067/068.

Fixture: Rechnung über Vier-Augen-Schwelle, abweichendes strukturiertes XML und PDF-Darstellung, infizierter Upload. Erwartung: Selbstfreigabe blockiert; XML/PDF-Abweichung wird sichtbar zur Prüfung gestellt; Original unverändert mit Hash/Version archiviert; Schadsoftware in Quarantäne, keine unberechtigte Auslieferung oder OCR-Verarbeitung. Steuerliche Behandlung und finale Sachprüfung sind getrennt von der technischen XML-Validierung.

### PH-AT-020 – Sichere Fahrzeugfreigabe vor und während Disposition

Bezug: CHK, DMG, OPT, AUT; AT-26 bis 35, AT-50/51/60.

Fixture: S0-Schaden wird gleichzeitig mit neuer Zuweisung gemeldet. Erwartung: synchroner Readiness-/Versionscheck verhindert neue Fahrtzusage mit unsicherem Fahrzeug; bestehende betroffene Fahrten gehen in einen nachvollziehbaren Störungsablauf. Reparaturvermerk allein entsperrt nicht. Offline-Prüfung erzeugt keine operative Serverfreigabe; technische Freigabe, erforderlicher Folgecheck und Fahrer-/Schichtvoraussetzungen müssen gemeinsam erfüllt sein.

### PH-AT-021 – KPI-Nenner, Datenlücke und Aggregationsschutz

Bezug: KPI, GOV-012; E2E-080 bis 084, E2E-101/103.

Fixture: zehn gültige Nachfrageversuche, drei Ablehnungen mit deterministischen Primärgründen, zwei davon mit zusätzlichen Sekundärgründen. Erwartung: zehn Versuche, drei Ablehnungen, Summe der Primärgründe drei; Sekundärgründe erzeugen keine zusätzlichen Ablehnungen. Fehlender Nenner ergibt „nicht berechenbar“. Bei 20 % fehlenden GPS-Daten wird die Vollständigkeit ausgewiesen. Kleine Gruppen unter freigegebener Schwelle werden auch durch Differenzbildung benachbarter Filter nicht offengelegt.

### PH-AT-022 – Sichere ETA und nicht freigegebene Personalisierung

Bezug: ETA-001/002/004/008/013/017/027, GOV-003/004; E2E-074 bis 079, E2E-105/106.

Fixture: neues Modell mit künstlich zu schnellen Beobachtungen, fehlender Aktivierungsfreigabe und nicht genügend Samples. Erwartung: fahrerneutrale regelkonforme Referenz bleibt aktiv; keine engere Zusage aufgrund unzulässiger Geschwindigkeit, kein Umgehen der Ruhezeit. Nach Freigabe gelten Qualitätsgrenzen und Fallback; Finanz-/HR-Rollen erhalten keine persönlichen Fahrkurven. Widerruf der Modellaktivierung unterbricht den Fahrbetrieb nicht.

## 8.6 Leistungs- und Wiederanlaufnachweise

Die Zahlen aus §12 und NFR-001 bis NFR-007 sind **vorläufige Lastenheftziele**. Sie sind keine gemessene Leistung dieses Entwurfs und bedürfen der Last-/Betriebsentscheidung in [07-entscheidungen.md](07-entscheidungen.md).

| Nachweis | Referenzziel | Zu protokollieren |
|---|---|---|
| Nicht optimierende API | p95 ≤ 1 s serverseitig | Endpointmix, Fehlerrate, Datenvolumen, Hardware und Parallelität |
| Fahrtangebot | p95 ≤ 5 s | Lokale/Partneranteile, Kandidaten-/Flottengröße, Routingdatenstand, Timeouts |
| Fahrerplan | p95 ≤ 10 s bei bestehender Verbindung | Commit bis tatsächlich dargestellte Clientrevision |
| Standortfrische | höchstens 30 s im Normalbetrieb | Erfassungs-/Empfangszeit, Lücken und sichtbares Veraltet-Flag |
| Referenzlast | 250 Fahrzeuge/Mandant, 30.000 Buchungen/Tag, 5.000 parallele Sessions | Separat vereinbarte Spitzen-Requests/s und aktive Mandanten; Tagesdurchschnitt allein genügt nicht |
| Verfügbarkeit | mindestens 99,9 % pro Monat | Externe funktionale Buchungs-/Operatorprobes, Messgrenzen und Wartungsregel |
| Wiederanlauf | RPO ≤ 15 min, RTO ≤ 2 h | Ausfallzeitpunkt, letzter wiederherstellbarer Commit, Zeitpunkt funktionsfähiger End-to-End-Probe |

Zusätzliche Fault-Injection deckt Ausfall von Datenbank, Worker, Redis/Cache, PSP, Partner, Kartenquelle und Messaging ab. Nach Restore werden externe Buchungen/Zahlungen mit Providerreferenzen abgeglichen, bevor unsichere Schritte wiederholt oder Kunden als ungebucht markiert werden. Ein konsistentes Backup allein beweist keine konsistente Abwicklung bereits extern vollzogener Geschäftsvorgänge.

## 8.7 Stand dieses Dokuments

Ausgeführt wurden die dokumentarische Extraktion und Vollständigkeitsprüfungen: 672 Quellzeilen, 672 eindeutige technische Fundstellen, 660 Original-IDs, 35 Fachabschnitte, 106 Referenztests, 80 Parameter und 48 KPI-Definitionen. Originalbeschreibungen und Akzeptanztexte wurden mit den entsprechenden DOCX-Tabellen abgeglichen. Die beschriebenen Anwendungs-, Last-, Sicherheits-, Partner- und Abnahmetests wurden **nicht ausgeführt**, da dieses Lieferpaket die Spezifikation und keine implementierte Mobilitätsplattform ist.
