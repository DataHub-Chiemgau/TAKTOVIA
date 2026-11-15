---
dokument_version: 0.1
abnahme_build: ENTWURF-W18-9d035b7
sprache: de
stand: 2026-11-15
arbeitspaket: WP-M5-27
---

# Übergabeprotokoll Dokumentationspaket

Das Dokumentationspaket (`LH-6.20-QAT-006`, WP-M5-27) besteht aus vier Dokumenten und den Unterlagen zur Demonstration des Standardbetriebs. Dieses Protokoll hält den Versionsstand je Dokument und den Bezug auf den Abnahme-Build fest. `tools/check_dokumentationspaket.py` prüft, dass jedes Dokument mit derselben Version hier geführt wird.

## Bezug auf den Build

- **Abnahme-Build:** `ENTWURF-W18-9d035b7` (kein signierter Abnahme-Build; ein solcher entsteht erst mit WP-M5-28 und WP-M5-31).
- **Bezugs-Commit:** `9d035b762f6482000b381604f2240e0fa13d376a` (Welle 17 integriert). Alle Oberflächen, Beschriftungen und Schnittstellen der Dokumente sind gegen diesen Stand abgeglichen.
- **Hinweis:** Wird der Build erneuert, sind `paket.json`, die Kopfzeilen aller Dokumente und dieses Protokoll gemeinsam auf den neuen Stand zu heben und der Abgleich zu wiederholen (kein Mischen von Ständen).

## Dokumente und Versionsstand

| Dokument | Titel | Version | Stand | Zielgruppe |
|---|---|---|---|---|
| `docs/betrieb/betriebshandbuch.md` | Betriebshandbuch (aus WP-M5-26) | 0.1 | 2026-11-15 | Betriebspersonal |
| `docs/dokumentation/administratorhandbuch.md` | Administratorhandbuch | 0.1 | 2026-11-15 | Mandantenadministration, Planung |
| `docs/dokumentation/fahrerschulung.md` | Fahrerschulung | 0.1 | 2026-11-15 | Fahrer, Schulungsleitung |
| `docs/dokumentation/datenschutzdokumentation.md` | Datenschutzdokumentation | 0.1 | 2026-11-15 | Auftraggeber, Datenschutz, Betriebsrat |
| `docs/dokumentation/standardbetrieb.md` | Standardbetrieb und Demonstration | 0.1 | 2026-11-15 | Abnehmer |
| `docs/dokumentation/uebergabeprotokoll.md` | Übergabeprotokoll | 0.1 | 2026-11-15 | Auftraggeber |

## Stand der Lieferung

| Punkt | Stand |
|---|---|
| Vier Dokumente versioniert, auf den Build bezogen | geliefert (Entwurf 0.1) |
| Abgleich Oberflächen und Schritte gegen den Build | maschinell durchgeführt und grün; drei bekannte Abweichungen sind in den Dokumenten ausgewiesen (Administratorhandbuch A-01, Fahrerschulung F-01 und F-02) |
| Keine echten Personendaten, keine offenen Platzhalter | maschinell geprüft; Fristen der Datenschutzdokumentation stehen als „durch Auftraggeber festzulegen“ (D-11) |
| Zugängliche Dokumentstruktur (Überschriften, Alternativtexte) | maschinell geprüft; keine Bilder enthalten; Prüfung mit Screenreader nicht durchgeführt (Maßstab offen, OP-19) |
| Demonstration des Standardbetriebs durch den Abnehmer | **nicht durchgeführt** |
| Durchlauf eines Kapitels durch eine projektfremde Person | **nicht durchgeführt** |
| Fahrerschulung in der Schulungsumgebung | **nicht durchgeführt** (keine Schulungsumgebung, kein Flutter) |
| Endfassung der Datenschutzdokumentation | blockiert durch D-10 und D-11 |

## Bestätigung

Die Bestätigung der Übergabe durch den Auftraggeber (Name, Datum) ist **nicht erfolgt**; sie wird nach der Demonstration im Protokoll nach `vorlagen/demonstrationsprotokoll.md` vermerkt.
