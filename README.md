# TAKTOVIA
Eine Plattform. Alle Wege. Die intelligente Mobilitäts- und Verkehrsmanagementplattform

Technische Spezifikation einer mandantenfähigen On-Demand-Mobilitätsplattform auf Grundlage des Lastenhefts V1.2.

**[Pflichtenheft lesen](docs/pflichtenheft.md)** · **[Word-Ausgabe](docs/pflichtenheft/Pflichtenheft_TAKTOVIA_V0_1.docx)** · **[Entscheidungen mit Empfehlungen](docs/pflichtenheft/07-entscheidungen.md)** · **[Umsetzungsplan](docs/umsetzungsplan.md)**

Die Dokumentation enthält Architektur, Mehrbetreiber-Reiseketten, gemeinsames Kostenmodell, relationales Kernschema, OpenAPI-Verträge, Bildschirmkonzepte und eine quellentreue Anforderungsmatrix. Status: Pflichtenheft als technischer Entwurf zur Freigabe; die Umsetzung von Phase 1 hat mit M0 begonnen ([Fortschritt](docs/umsetzungsplan/fortschritt.md), [Umsetzungsplan](docs/umsetzungsplan.md)). Es gibt noch keine Fachfunktionen.

## Entwicklung

- Backend (Kotlin/Spring Boot, Gradle): [backend/README.md](backend/README.md)
- Datenbank (PostgreSQL/PostGIS, Migrationen, Tests): [db/README.md](db/README.md)
- Regeln und Arbeitsweise: [CONTRIBUTING.md](CONTRIBUTING.md)
- Prüfungen: `python tools/check_repo.py`, `python docs/umsetzungsplan/check_wp.py`, `python docs/pflichtenheft/validate_artifacts.py`
