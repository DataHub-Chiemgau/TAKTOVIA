# Dokumentationspaket (WP-M5-27, LH-6.20-QAT-006)

| Dokument | Inhalt |
|---|---|
| [paket.json](paket.json) | Liste der Dokumente, Abnahme-Build, Bezugs-Commit |
| [uebergabeprotokoll.md](uebergabeprotokoll.md) | Versionsstand je Dokument, Stand der Lieferung |
| [administratorhandbuch.md](administratorhandbuch.md) | Mandant, Benutzer und Rollen, Dienste, Gebiete, Haltepunkte, Tarife, Fahrzeuge, Checklisten, Konfigurationsrevisionen |
| [fahrerschulung.md](fahrerschulung.md) | Fahrer-App, Übungsablauf, Sicherheitsabläufe |
| [datenschutzdokumentation.md](datenschutzdokumentation.md) | Dateninventar, Löschregeln, DSFA, Rollen- und Feldzugriff, Minimierung, Protokollierung, Maskierung |
| [standardbetrieb.md](standardbetrieb.md) | Abläufe der Demonstration und Protokoll (Vorlage in `vorlagen/`) |
| [../betrieb/betriebshandbuch.md](../betrieb/betriebshandbuch.md) | Betriebshandbuch (WP-M5-26) |

Prüfung: `python3 tools/check_dokumentationspaket.py`; Test: `tests/acceptance/test_dokumentationspaket.py`. Regeln: Sprache Deutsch, Versionierung in den Kopfzeilen, jede Oberflächenreferenz als `**Beschriftung**{{route|nav|app|src:Ziel}}` bzw. `{{api:operationId}}`, keine echten Personendaten.
