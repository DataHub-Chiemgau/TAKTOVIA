# Umfang der M4-Gate-Abnahmesuite (WP-M4-63)

Die Suite `tests/acceptance/m4_gate/` führt 24 Katalogtests (PH-AT-012, PH-AT-013, PH-AT-015, PH-AT-019, PH-AT-021, AT-37, AT-39, AT-40, AT-41, AT-45, AT-47, AT-48, E2E-064 bis E2E-068, E2E-072, E2E-073, E2E-080 bis E2E-084),
die fünf Kostenszenarien COST-01, -02, -05, -07, -08 (Kap. 03 §7, als `TECH-COST-xx`, weil sie keine Katalog-ID tragen) und fünf Querschnittsszenarien (Live-Dashboard, Invarianten mit parallelen Transaktionen, Contract-Fälle
aus Kap. 05 §5.8, Isolation der M4-Tabellen und -Endpunkte, Barrierefreiheit) unter der Runtime-Rolle mit **beiden** Mandanten der Fixture aus und schreibt Evidenz nach `tests/acceptance/evidence/m4/` (Kap. 08 §8.4:
`source_key[]`, Build, Migrationsstand, Konfigurationsrevision, Seed, Rollen, Soll/Ist, Ergebnis). Die Abnahmeentscheidung bleibt `PENDING` (Auftraggeber, D-12). Aufruf nach `./gradlew build`:
`python tests/acceptance/m4_gate/run.py [--write-e2e]`; `--write-e2e` trägt Build, Nachweis und Entscheidung der Katalogtests ausschließlich aus dem Lauf in `docs/pflichtenheft/e2e.csv` ein.

Nachweisarten: JUnit-Ergebnisse des Backends (Klasse und Methode müssen PASSED sein; Zwei-Mandanten-Fixtures und echte parallele Transaktionen sind Teil dieser Tests), **im Lauf ausgeführte** Datenbanktests (`db/tests`),
Vitest-Läufe der Operator-Oberflächen, ein Browserlauf der Barrierefreiheitsprüfung (axe-core, Reflow 320 px, 200 % Textgröße; nur mit Chrome) und SQL-Prüfungen unter der Runtime-Rolle. Ein Szenario mit ausgewiesenen offenen
Teilschritten (Schritt mit Soll `offen`, Ist `offen (nicht geprüft)`) gilt als „Teilweise bestanden (M4-Umfang, offene Teilschritte ausgewiesen)“.

**Behandlung der nur teilweise erfüllbaren Tests (Akzeptanzkriterium 4, Offene Punkte 1 bis 7 des WP-Dokuments):** AT-41, AT-48, E2E-067 sowie der Tankkarten- und Restatement-Teil von PH-AT-012 und PH-AT-015 sind in M4 nur
im ausgewiesenen Teilumfang geprüft; der Rest steht als offener Teilschritt in der Evidenz. **Die mit dem Auftraggeber zu vereinbarende Behandlung (Teilabnahme oder Verschiebung) ist noch nicht entschieden (D-12);** bis dahin
ist hier der Vorschlag „Teilabnahme in M4 mit dokumentiertem Rest, Rest im Zielmeilenstein“ unverbindlich dokumentiert, die Entscheidung steht aus.

**Mängelklasse A (Akzeptanzkriterium 5):** kein offener Mangel der Klasse A ist bekannt (Mängelklassen: `mangelklassen.md`); ein roter Lauf der Suite schreibt eine Mängelklasse in die Evidenz.

| Test | Geprüfter Umfang in M4 | Nicht geprüft (Offene Punkte) | Zielmeilenstein |
|---|---|---|---|
| PH-AT-012 | genau ein Kostenereignis mit 100,00 Kostenbasis, 19,00 Vorsteuer, 119,00 Verbindlichkeit; Dublette per Foto und Importkanal verknüpft, ähnlicher Beleg als möglicher Treffer ohne Löschung; OCR korrigierbar, Original und Versionen erhalten; erneute Freigabe erhöht keine Summe, parallel genau ein Ereignis | Tankkartenimport als eigener Kanal und Rechnung als dritte Evidenz, E2E-070 (OP 5) | M8 (Teilabnahme M4 offen, D-12) |
| PH-AT-013 | Largest-Remainder-Allokation 100,01 EUR in 33,34/33,34/33,33, Determinismus, Summenpflicht, kein Zusatzjournal, parallele Revisionen genau eine aktiv, Rechtsträgerbezug je Kostenobjekt | Referenzfall Eigentümer X / Betreiber Y mit Forderungs-/Verbindlichkeitsbezug, Journal je Rechtsträger mandantenübergreifend (E2E-094) | M6 |
| PH-AT-015 | Septemberabschluss unverändert, Oktoberkorrektur mit Ursprungsbezug, Original plus Umlage nie doppelt, Abschluss parallel zu Posting genau eine Reihenfolge | Restatement-Ansicht und Plan/Ist-Vergleich mit Planbudget (FCO-017, OP 6) | M8 |
| PH-AT-019 | Selbstfreigabe blockiert, Vier-Augen-Schwelle, Schadsoftware in Quarantäne ohne Auslieferung und OCR, Original mit Hash unverändert, Integritätsverletzung sperrt Posting | abweichendes XML gegen PDF (E-Rechnung, OP 4) | M8 |
| PH-AT-021 | zehn Versuche, drei Ablehnungen, Summe der Primärgründe drei, Nullnenner nicht berechenbar, 20 % fehlende Daten mit Abdeckungsgrad, Kleingruppen auch per Differenzbildung nicht offengelegt, drei Benutzer gleiche Werte | – | M4 |
| AT-37 | Entwurf getrennt vom veröffentlichten Plan, Fahrer sieht nur die veröffentlichte Revision, Publikation parallel genau eine Gewinnerin, Meldung nur an geänderte Personen, frühere Fassung abrufbar, UI-12 | Inhalt der Fahrermeldung und Wirkung von `shift_publish_notice_hours` (OP 9, SCH-014 P2) | M4/M7 |
| AT-39 | Doppelbelegung und fehlende Qualifikation mit Konfliktursache, Sperre der Veröffentlichung (409), parallele Zuweisung höchstens eine, Abwesenheit als Konfliktquelle | – | M4 |
| AT-40 | Warnhinweise Ruhezeit (Grenze genau und eine Minute darunter), Tagesarbeitszeit, Pflichtpause als Planungsannahme, Regeln konfigurierbar, Hinweise ändern keine Daten | freigegebene Regelmatrix je Beschäftigungsmodell, Sperre statt Hinweis (OP 3, SCH-017/-035 P2) | M7 |
| AT-41 | genehmigte Abwesenheit als Verfügbarkeitsrestriktion und Konfliktquelle, Rücknahme, Planer sieht nur „nicht verfügbar“, Mandantentrennung, parallele Rücknahme | offene Ersatzschicht und Änderungsworkflow nach Urlaubsgenehmigung (OP 1, TIM-008/SCH-013/SCH-020 P2) | M7 (Teilabnahme M4 offen, D-12) |
| AT-45 | Vorbereitungszeit vor der Fahrzeugprüfung als erfasste Arbeitszeit, Pausen abgezogen, Soll und Ist getrennt, fehlende Ist-Buchung gekennzeichnet, Offline-Stempel einmalig, parallele Stempel | Stempeln in der Fahrer-App (Flutter, nicht ausführbar) | M4 (Flutter-Lauf in der CI der App) |
| AT-47 | Nachtschicht über Herbst- und Frühjahrsumstellung aus absoluten Zeitpunkten (9 und 7 Stunden), doppelte Ortszeit eindeutig, Lücke abgewiesen, Ruhezeit über DST | – | M4 |
| AT-48 | Ist-Zeiten unveränderlich, Nacherfassung als eigene Quelle mit Akteur im Audit ohne Überschreiben, gleiche Ereignis-ID mit anderem Inhalt 409, manipulierte Gerätezeit als Klärfall | Korrekturantrag, Genehmigung/Ablehnung, Historie (`time_correction`, OP 2, TIM-007/-014 P2) | M7 (Teilabnahme M4 offen, D-12) |
| E2E-064 | Original unverändert (Hash), OCR als unbestätigter Vorschlag, Bestätigungspflicht, Zuordnung, Kosten nach Freigabe einmal gebucht, Offline-Beleg einmal übernommen, UI-14 | Kamera in der Fahrer-App (Flutter, nicht ausführbar) | M4 (Flutter-Lauf in der CI der App) |
| E2E-065 | Dublettenprüfung verknüpft, möglicher Treffer entscheidbar mit Begründung, keine doppelte Kostenwirkung, Mandanten ohne Auskunft über fremde Treffer | echte Tankkartentransaktion aus dem Kartenimport (OP 5) | M8 |
| E2E-066 | 89,00 statt 39,00 korrigierbar, OCR-Vorschlag nachvollziehbar, Korrektur parallel zur Freigabe 412, Original nie überschrieben | – | M4 |
| E2E-067 | nur Teilumfang: Summenabweichung wird markiert und blockiert die Freigabe, Original unverändert | XML führend, XML/PDF-Differenz, ungültiges XML (DOC-014/-015 P2, OP 4) | M8 (Teilabnahme M4 offen, D-12) |
| E2E-068 | Vier-Augen-Freigabe über der Schwelle mit zwei verschiedenen Personen, Selbstfreigabe 403, kein Export ohne beide Freigaben, Ablehnung mit eigenem Code | – | M4 |
| E2E-072 | 100-%-Zuordnung auf zwei Kostenobjekte, Summen ausgeglichen, Summe ungleich Basis blockiert, Korrektur hebt exakt auf | – | M4 |
| E2E-073 | Abschluss unverändert, Gutschrift in neuer Periode mit Ursprungsbezug, Posting in geschlossene Periode abgewiesen, Abschluss endgültig | Endpunkt für den Periodenabschluss in Kap. 05 §5.7 (OP 6); FCO-023/DOC-030 P2 | M8 |
| E2E-080 | fünf Ressourcenfälle mit fünf Primärgründen in einer Berichtsspalte, sieben Gruppen einzeln auswertbar, Nachfrage- und Ablehnungsstatistik sichtbar und filterbar (UI) | – | M4 |
| E2E-081 | freies Fahrzeug ohne einsatzbereiten Fahrer: Primärgrund Personalmangel ohne Zusage, Sekundärgründe nur als Label | – | M4 |
| E2E-082 | Zusage 08:10–08:15, Abholung 08:22: sieben Minuten, getrennt von der Ankunft, Zusageversion bleibt gebunden | – | M4 |
| E2E-083 | Ankunftsverspätung trotz pünktlicher Abholung als eigene Kennzahl, Toleranzgrenze, Ursache getrennt erfasst | – | M4 |
| E2E-084 | GPS-Lücke: Vollständigkeit und Abdeckungsgrad ausgewiesen, nicht berechenbar statt 0, Live-Datenalter bei Stau, UI zeigt Zustand statt Scheingenauigkeit | – | M4 |
| TECH-COST-01 | COST-01: Foto, Import und Wiederholung des Freigabejobs liefern genau ein Tankereignis | Kartenimport als eigener Kanal (OP 5) | M8 |
| TECH-COST-02 | COST-02: 10.000 Minor Units im Drittelsplit, Korrektur hebt exakt auf, falsche Summe blockiert Posting | – | M4 |
| TECH-COST-05 | COST-05: Periodenschluss gegen Freigabe genau eine Reihenfolge, Gutschrift in neuer Periode, Abschluss unverändert | – | M4 |
| TECH-COST-07 | COST-07: zwei Freigaben derselben Person erfüllen keine Vier-Augen-Freigabe | ungültiges XML und PDF/XML-Abweichung (OP 4) | M8 |
| TECH-COST-08 | COST-08: fehlende Energiedaten markiert statt null Kosten, Original plus Umlage nie doppelt | Kosten je Fahrgast bei null Fahrgästen (FCO-027/KPI-027 P2) | M8 |
| TECH-M4-LIVE | Live-Dashboard (WP-M4-60): vier getrennte Werte, Fahrzeugsperre innerhalb der Aktualisierungsfrist, Datenalter bei Ereignisstau, fremder Dienst und Mandant nie sichtbar, zwei Mandanten parallel | Aktualisierungsfrist und Definitionen sind Planungsannahmen bis D-05 (OP 32) | M4 (D-05) |
| TECH-M4-INVARIANTEN | Invarianten Kap. 04 §4.5 (M4): Gegenereignis, Journal, eine Kostenwirkung, Umlage, Export, Umklassifikation je mit mindestens zwei parallelen Transaktionen | – | M4 |
| TECH-M4-CONTRACT | Contract-Fälle Kap. 05 §5.8: EUR-Rundungsrest, geschlossene Periode, Allokationssumme ≠ Basis, Manipulation tenant_id, unzulässige Fremd-ID | – | M4 |
| TECH-M4-ISOLATION | M4-Anteile von PH-AT-001, AT-49 und E2E-091 (nachgelaufen über WP-M4-64): Tabellenkatalog, Modulzugänge, RLS aller M4-Tabellen, fremde Objekte über alle Endpunkte gleichartig abgewiesen | – | M4 |
| TECH-M4-A11Y | UI-12 bis UI-15 und Kennzahlenansicht: Vitest mit axe (Tastatur, Namen, Rollen), Browserlauf axe-core, Reflow 320 px, 200 % Textgröße | Prüfung mit echtem Screenreader (nicht ausführbar) | M4 (manuelles Protokoll) |
