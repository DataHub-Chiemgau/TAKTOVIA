# LH-6.20-QAT-001 – Zentraler Anforderungs-Backlog mit Besitzer und Testfall

- **Verantwortlicher:** Rolle QA (Nachverfolgung, `check_tracking.py`/`check_wp.py`)
- **Arbeitspaket:** WP-M5-21
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Anforderungen sind in einem zentralen Backlog mit ID, Besitzer, Priorität und Testfall referenziert.

**Originalabnahme (unverändert):** Stichprobe jeder Pflichtanforderung führt zu mindestens einem Testfall.

## Vertrag

- **Tabellen / DDL:** keine Datenbank. Backlog ist `docs/umsetzungsplan/arbeitspakete.csv` mit den Spalten `source_key`, `id_original`, `quellabschnitt`, `phase_original`, `prioritaet_original`, `meilenstein`, `arbeitspaket`, `wp_titel`, `wp_groesse`, `wp_datei`, `detailvertrag`, `testfall`, `status`; neue Spalte „Besitzer“ (heute nicht vorhanden). Quellkatalog: `docs/pflichtenheft/anforderungen.csv` (672 Zeilen, davon 202 P1/MUSS).
- **API:** keine. CI-Prüfschritt (Adapter/Worker): jede MUSS-Zeile des freizugebenden Release hat Besitzer und mindestens einen existierenden, ausführbaren Testfall; der Testfall referenziert die `source_key` zurück (bidirektional).
- **Zustandsautomat:** Statuswerte nach vertragsprozess.md.
- **JSON-Schema / Ereignisse:** Spaltenvertrag des Backlogs; Regel für doppelte Original-IDs (`COM-001` bis `COM-012`: ausschließlich `source_key`, Kap. 08 §8.1); Definition „Testfall“ (ausführbar, mit ID und Version).
- **Regeln:** jede der 672 Anforderungen genau einmal mit ID, Besitzer, Priorität; die 202 P1-MUSS zusätzlich mit mindestens einem Testfall; Originalphase und -priorität stimmen mit dem Quellkatalog überein (Änderung nur mit dokumentiertem AG-Beschluss, Kap. 01 §1.8). Stichprobenwerkzeug gibt zu zufällig gezogenen Pflichtanforderungen Testfall, letzten Lauf, Build und Evidenz aus.
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** keine.
- **Entscheidungen:** D-12 (Quellbereinigung doppelter COM-IDs) blockiert nichts; bis dahin gilt `source_key`.
- **Offene Punkte:** OP-16 (ob `arbeitspakete.csv` als zentraler Backlog gilt oder ein Werkzeug des Auftraggebers verlangt wird; Besitzer-Spalte; Umfang und Ziehung der Abnahmestichprobe nicht beschrieben).

## Tests

- **Positiv:** Vollständigkeitslauf: alle 672 Anforderungen genau einmal mit ID, Besitzer, Priorität; alle 202 P1-MUSS mit mindestens einem Testfall; Stichprobe liefert je gezogener Anforderung Testfall mit letztem Laufergebnis.
- **Negativ:** Testfall verweist nicht auf die `source_key` zurück (bidirektionale Prüfung) wird gemeldet; Anforderung des Quellkatalogs fehlt im Backlog oder steht doppelt (`COM-001` bis `COM-012` nur über `source_key` unterscheidbar); Probezeile „MUSS, umgesetzt, ohne Besitzer“ bzw. „ohne Testfall“ lässt die Pipeline fehlschlagen; Testfall mit nicht existierender `source_key` wird gemeldet; doppelte Zuordnung einer Anforderung wird gemeldet; abweichende Phase/Priorität gegenüber Quellkatalog lässt die Pipeline fehlschlagen.
- **Konkurrierend:** nicht einschlägig (keine Sicherheits-/Geld-/Kapazitätsfunktion).
- **Referenztests:** keine Katalog-ID im WP genannt.
- Testfälle: geplant:tests/acceptance/tracking/test_backlog_vollstaendigkeit.py::test_alle_anforderungen_genau_einmal_mit_besitzer; geplant:tests/acceptance/tracking/test_backlog_vollstaendigkeit.py::test_alle_p1_muss_mit_testfall_der_source_key_zurueckverweist; geplant:tests/acceptance/tracking/test_backlog_vollstaendigkeit.py::test_doppelte_zuordnung_und_fehlende_anforderung_gemeldet; geplant:tests/acceptance/tracking/test_backlog_vollstaendigkeit.py::test_abweichende_phase_oder_prioritaet_gegenueber_quellkatalog_schlaegt_fehl; geplant:tests/acceptance/tracking/test_stichprobe.py::test_stichprobe_liefert_testfall_letzten_lauf_build_evidenz; geplant:tests/acceptance/tracking/test_backlog_vollstaendigkeit.py::test_muss_ohne_besitzer_oder_testfall_schlaegt_fehl; geplant:tests/acceptance/tracking/test_backlog_vollstaendigkeit.py::test_testfall_mit_unbekannter_source_key_gemeldet

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
