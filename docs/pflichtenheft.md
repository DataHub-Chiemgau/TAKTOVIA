# Pflichtenheft TAKTOVIA

**Version 0.1 · Technischer Entwurf zur Freigabe · 25. Oktober 2026**

Grundlage: [Lastenheft On-Demand-Mobilitätsplattform V1.2](lastenheft/Lastenheft_On_Demand_Mobilitaetsplattform_V1_2.docx). Das hochgeladene Original wurde unverändert ins Repository übernommen. Dieses Pflichtenheft beschreibt die vorgeschlagene Umsetzung, überprüfbare Kernverträge und die noch erforderlichen Auftraggeberentscheidungen. Es stellt keine fertige oder bereits abgenommene Software dar.

[Zusammenhängende Word-Ausgabe herunterladen](pflichtenheft/Pflichtenheft_TAKTOVIA_V0_1.docx). Die Markdown-Kapitel und maschinenlesbaren Anhänge sind die bearbeitbaren Quelldokumente.

## Ziel und zentrale Festlegungen

TAKTOVIA verbindet Fahrgast-Web/App, Fahrer-App, Leitstelle, Personal-/Fuhrparkverwaltung und Finanzen. Jeder Betreiber behält die Hoheit über eigene Kapazitäten und geschützte Stammdaten. Eine Reise kann mehrere Betreiber und reine Informationsabschnitte enthalten. Ein gemeinsamer Checkout und ein nachvollziehbares Kosten-/Clearingmodell verbinden die Leistungen, ohne Rechtsträger, Zahlungen und betriebliche Kosten zu vermischen.

1. **Datenarchitektur:** Mandant, Organisation und Rechtsträger sind getrennte Begriffe. PostgreSQL mit RLS und mandantenbezogenen Schlüsseln schützt lokale Daten; föderierte Reisen nutzen zweckgebundene, minimierte Projektionen und widerrufbare Kooperationsrechte.
2. **Buchung mehrerer Betreiber:** Ein persistenter Journey-Orchestrator hält und bestätigt Segmente über ihre jeweiligen Betreiber. Unbekannte Antworten werden abgeglichen; bestätigte Teilsegmente werden bei Fehlern nach Vertrag kompensiert. Es wird keine globale Atomarität fremder Systeme versprochen.
3. **Einheitliches Kostenmodell:** Ein wirtschaftlicher Ursprung, mehrere belegende Nachweise, getrennte Ist-/Planbewertung, centgenaue Umlagen, ausgeglichene Journale je Rechtsträger/Währung und eigener Partnerausgleich. Keine Addition von Originalkosten und ihrer Umlage oder von Kundenzahlung und Clearing als doppeltem Umsatz.
4. **Technik:** Modularer Kotlin/Spring-Kern, PostgreSQL/PostGIS, React-Weboberflächen, Flutter für mobile Apps, getrennte Dispatch-/Routing-/Dokumentworker und versionierte Integrationsadapter.
5. **Kaufmännischer Favorit:** Vermittlungsmodell mit Betreiber als Verkäufer/Beförderer seines Segments, gemeinsamer Checkout über einen vertraglich geeigneten Mehrparteien-PSP und operatives Nebenbuch mit ERP-Export. Diese Rollen benötigen AG-Freigabe.

## Dokumentstruktur

| Kapitel | Inhalt |
|---|---|
| [01 Softwarearchitektur](pflichtenheft/01-gesamtarchitektur.md) | Technologie, Komponenten, Domänen, Disposition, Sicherheit, Offlinebetrieb, Betrieb/SLOs und Lieferung |
| [02 Mandanten und Reiseketten](pflichtenheft/02-mandanten-und-reiseketten.md) | Rechte-/Datenarchitektur, Betreiberhoheit, Kooperationswiderruf, Saga, Fristen, Teilfehler und Partnerfähigkeiten |
| [03 Kosten und Clearing](pflichtenheft/03-kosten-und-clearing.md) | Einheitliche Kostenereignisse, Belege, Journale, Umlagen, zwei vollständige Rechenbeispiele, Erstattungen und Abschluss |
| [04 Datenmodell](pflichtenheft/04-datenmodell.md) | Relationen, Schlüsselfelder, Invarianten, RLS, Indizes und Erweiterungstabellen aller Fachmodule |
| [05 API-Verträge](pflichtenheft/05-api-vertraege.md) | Konkrete HTTP-/JSON-Verträge, Idempotenz, Zeitfenster, Zahlung, Autorisierung, Events und Fehlermodell |
| [06 Bildschirmkonzepte](pflichtenheft/06-bildschirmkonzepte.md) | Wireframes und Bedienabläufe für Fahrgast, Callcenter, Fahrer, Leitstelle, Finanzen, Personal und Administration |
| [07 Entscheidungen](pflichtenheft/07-entscheidungen.md) | Zwölf Entscheidungspakete, Varianten, Favoriten, Auswirkungen und benötigte Beschlüsse |
| [08 Abdeckung und Abnahme](pflichtenheft/08-abdeckung-und-abnahme.md) | Vollständige Quellzuordnung, Phasen, Nachweise und zusätzliche konkrete Schwerpunkt-Abnahmetests |

## Prüffähige Anhänge

- [SQL-Kernschema](pflichtenheft/schema/kernschema.sql): 38 Tabellen mit Schlüsseln, RLS-Policies, Finanz-/Unveränderlichkeitstriggern; ergänzende Domänentransaktionen sind ausdrücklich ausgewiesen.
- [OpenAPI 3.1](pflichtenheft/api/openapi.yaml): 14 Kernpfade und 42 Schemas einschließlich Fahrgastvertretung, verbindlichen Zeitfenstern, asynchronen Operationen und signierten Ereignissen.
- [Anforderungsmatrix](pflichtenheft/anforderungen.csv): alle 672 Quellzeilen mit Originaltext, Priorität/Phase, technischer Zuordnung und Detaillierungsstatus.
- [Originaltests](pflichtenheft/e2e.csv): 106 Testfälle; noch nicht ausgeführt.
- [Parameter und KPIs](pflichtenheft/kataloge.csv): 80 Parameter und 48 Kennzahldefinitionen.
- [Recherche Föderation](pflichtenheft/recherche-federation.md) und [Recherche Kosten](pflichtenheft/recherche-kosten.md): offizielle Quellen, Abrufstand, Erkenntnisse und Grenzen.
- [Dokumentprüfung](pflichtenheft/09-pruefbericht.md): tatsächlich ausgeführte Artefaktprüfungen und klare Abgrenzung zu späteren Anwendungstests.

## Quellbefund und Geltungsumfang

Das Lastenheft enthält **672 Anforderungen in 35 Fachabschnitten, aber nur 660 eindeutige IDs**: COM-001 bis COM-012 sind für Kommunikation (§6.12) und Clearing (I.9) doppelt vergeben. Die Matrix verwendet deshalb zusätzliche eindeutige Fundstellenschlüssel, ohne den Quelltext zu verändern. Die tatsächlichen Zeilenzählungen sind P1:202, P2:398, P3:72 sowie MUSS:474, SOLL:181, KANN:17; die alte Summenübersicht in §6 ist nicht vollständig aktualisiert.

Alle Anforderungen sind zugeordnet. Die drei Schwerpunkte und ihre Kern-API-/Datenverträge sind vertieft spezifiziert. Vollständige produktive DDL-/OpenAPI-Verträge aller übrigen Einzelanforderungen, ausführbare KPI-Abfragen, vollständige Konfigurationsschemas, konkrete Adapterversionen und freigegebene Produktionswerte bleiben Arbeitspakete der Detailimplementierung. Die Ausarbeitungsgrenze ist je Artefakt sichtbar; Architekturzuordnung wird nicht als implementierte Funktion ausgegeben.

Die Originalphasen bleiben erhalten. Eine vorgezogene produktive Mehrbetreiberbuchung erfordert einen bewussten Releasebeschluss. Rechtliche Rollen, Zahlungs-/Haftungsmodell, Pilotpartner, reale Last und Finanz-/Aufbewahrungsregeln stehen im Entscheidungsregister. Technische Empfehlung und Projektfreigabe sind getrennt.
