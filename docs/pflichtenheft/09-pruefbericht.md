# 09 – Prüfbericht der Dokumentartefakte

Stand 25.10.2026. Die nachstehenden Prüfungen betreffen das Pflichtenheft und seine Vertragsdateien. Anwendung, Datenbankbetrieb und Partnerintegration sind damit nicht abgenommen.

## Prüfverfahren

Die wiederholbare Prüfung liegt in [validate_artifacts.py](validate_artifacts.py). Benötigte Python-Pakete: `PyYAML`, `jsonschema`, `openapi-spec-validator`, `pglast`. Installation in eine lokale virtuelle Umgebung außerhalb des Repositorys; anschließend `python docs/pflichtenheft/validate_artifacts.py` im Repository ausführen. Der Prüfer löst Pfade relativ zur eigenen Datei auf und verändert keine Artefakte.

Die SQL-Datei wird mit einem PostgreSQL-Parser analysiert; dieser führt SQL, RLS und PL/pgSQL-Trigger nicht aus. Die semantische OpenAPI-Prüfung validiert Struktur und Schema-Referenzen, die JSON-Schema-Prüfung prüft die enthaltenen Schema-Beispiele. Fachliche Latenzen, Kapazitäts-/Geldflüsse und Berechtigungswirkung benötigen spätere Implementierungs-/Integrationstests.

## Ausgeführte Ergebnisse

| Prüfung | Ergebnis |
|---|---|
| Original-DOCX unverändert | SHA-256 `9f799a0617b5e9a487041c9fe4475957be48fafacf216196bb088f555ea49165` bestätigt |
| Originaltext gegen Matrix | 672 Zeilen: ID, Phase, Priorität, Beschreibung und Akzeptanz stimmen in Quellreihenfolge überein; Layout-Leerraum normalisiert |
| Referenzen/Umfang | 672 eindeutige Fundstellenschlüssel, 660 Quell-IDs, 106 Originaltests, 80 Parameter, 48 KPIs bestätigt |
| Lokale Markdown-/CSV-Verweise | Alle referenzierten Repository-Dateien vorhanden |
| OpenAPI 3.1 | Semantische Spezifikationsprüfung bestanden: 14 Kernpfade, 42 Schemas, eindeutige Operationsnamen |
| Schema-Beispiele | Alle 9 enthaltenen Schema-Beispiele erfolgreich validiert; ungültige Vertretung ohne Nachweis wird vom Schema abgewiesen |
| SQL | 61 Top-Level-Anweisungen einschließlich 38 CREATE TABLE-Anweisungen erfolgreich geparst |
| Kostenbeispiele | Exakte Dezimalprüfungen für 32,10/21,40-EUR-Reise, Partnerverteilung, 1.680-EUR-Fahrzeugkosten und 1.280-EUR-Konsolidierung bestanden |
| Fachliche Konsistenz | Zustandsnamen, signierte Korrekturbeträge, Zeitfenster, Fahrgastvertretung und Planungsbudget zwischen Kapiteln abgeglichen |

Nicht ausgeführt: produktive Migration, SQL-/RLS-/Trigger-Laufzeittests, Parallelitätsprüfung, tatsächliche Buchung/Zahlung, UI-/Barrierefreiheitstest, Last-/Restoretest, Partner-Conformance und die 106 Original-E2E-Tests. Diese werden durch dieses Dokument spezifiziert und bleiben Implementierungs-/Abnahmearbeit. Forschungsergebnisse belegen dokumentierte Standardmechanismen; sie ersetzen keine Partner-, Steuer- oder Vertragsfreigabe.

Das geprüfte Repository enthält Dokumentations- und Vertragsartefakte. Es liegt kein Anwendungsbuild vor.

## Word-Ausgabe

[render_docx.py](render_docx.py) erzeugt die zusammenhängende Word-Ausgabe aus 12 Markdown-Quelldateien. Voraussetzungen: `python-docx`, `Pillow`, Graphviz `dot`. Enthalten sind alle Kapitel einschließlich Recherche und Prüfbericht, 41 Tabellen sowie drei eingebettete Architektur-/Daten-/Ablaufdiagramme. Die 672-Zeilen-Matrix und maschinenlesbaren SQL-/OpenAPI-Artefakte bleiben eigenständige Anhänge im Repository. Die DOCX-Datei wurde erneut geöffnet und auf Paketintegrität und eingebettete Diagramme geprüft; eine manuelle Seitenlayoutabnahme in Microsoft Word ist nicht erfolgt.
