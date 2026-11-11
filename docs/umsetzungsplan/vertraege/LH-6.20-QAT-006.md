# LH-6.20-QAT-006 – Dokumentationspaket und Demonstration des Standardbetriebs

- **Verantwortlicher:** Rolle Betrieb/Dokumentation (WP-M5-27, Betriebshandbuch aus WP-M5-26)
- **Arbeitspaket:** WP-M5-27
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Betriebshandbuch, Administratorhandbuch, Fahrerschulung und Datenschutzdokumentation werden geliefert.

**Originalabnahme (unverändert):** Abnehmer kann Standardbetrieb mit gelieferten Materialien demonstrieren.

## Vertrag

- **Tabellen / DDL:** keine. **API:** keine neue; das Paket bezieht sich auf bestehende Oberflächen und Schnittstellen des Abnahme-Builds.
- **Liefergegenstände:** (1) Betriebshandbuch (aus WP-M5-26, Teil des Pakets); (2) Administratorhandbuch: Mandant und Organisation, Benutzer und Rollen, Dienste, Gebiete, Haltepunkte, Tarife, Fahrzeuge, Checklistenvorlagen, Konfigurationsrevisionen mit Vier-Augen-Freigabe und Rollback (UI-17, UI-20, UI-11, UI-12); (3) Fahrerschulung: Unterlagen und Übungsablauf in Schulungsumgebung mit synthetischen Daten (Dienststart, Pflichtprüfung, Stoppliste, Check-in/Ausstieg/No-Show, Schadenmeldung, Offlineverhalten, Beleg; UI-09, UI-10), einschließlich Pflichtprüfung mit kritischem Mangel, Fahrzeugsperre, Offline und App-Ausfall; (4) Datenschutzdokumentation aus Dateninventar (WP-M1-32), Löschregeln (WP-M1-33), DSFA (WP-M1-42), Rollen-/Feldzugriffskonzept, Standort- und Medienminimierung (WP-M5-20), Protokollierung und Maskierung; (5) Übergabeprotokoll mit Versionsstand je Dokument.
- **Dokumentationsstandard:** Sprache Deutsch, versioniert im Repository, Bezug auf den Abnahme-Build; zugängliche Dokumente (strukturierte Überschriften, Alternativtexte); keine echten Personendaten, Screenshots aus der Schulungsumgebung.
- **Standardbetrieb (zu demonstrierende Abläufe):** Benutzer und Fahrzeug anlegen, Dienst und Tarif prüfen, Fahrer startet Dienst mit Pflichtprüfung, Buchung wird disponiert und gefahren, Leitstelle überwacht, Alarm wird nach Runbook behandelt; Abweichungen werden protokolliert.
- **Zustandsautomat / JSON-Schema:** keine. **Parameter und Kennzahlen:** keine neuen. **Oberfläche:** UI-07, UI-09, UI-10, UI-11, UI-12, UI-17, UI-20 als Gegenstand der Dokumentation; jede beschriebene Oberfläche und jeder Schritt muss im Abnahme-Build existieren (Abgleich Screenshots/Schritte gegen Build).
- **Entscheidungen:** D-11 (Aufbewahrung, Löschfristen) und D-10 (Fahrerdaten, Mitbestimmung) blockieren die Endfassung der Datenschutzdokumentation und die Produktivfreigabe, nicht die Erstellung; davon abhängige Fristen stehen als „durch Auftraggeber festzulegen“.
- **Offene Punkte:** OP-19 (Umfang des „Standardbetriebs“, Form und Sprache der Unterlagen, Umfang der Fahrerschulung als Unterlagen oder Durchführung, Abgrenzung der Datenschutzdokumentation zu Dateninventar und DSFA; hier als Unterlagen mit Übungsablauf ausgelegt, zu bestätigen). Blockiert den Bau nicht.

## Tests

- **Positiv:** Demonstration des Standardbetriebs durch den Abnehmer allein mit den gelieferten Materialien, ohne Unterstützung des Entwicklungsteams, Abweichungen protokolliert; vier Dokumente versioniert und auf den Abnahme-Build bezogen geliefert.
- **Sicherheitsrelevanz Fahrerschulung:** Prüfung, dass die Schulungsunterlagen Pflichtprüfung mit kritischem Mangel, Fahrzeugsperre, Offline und App-Ausfall enthalten (Inhaltsabgleich gegen WP-M5-26 Notbetrieb); Maßstab für Zugänglichkeit offen (OP-19).
- **Negativ:** Durchlauf eines Handbuchkapitels durch eine projektfremde Person deckt fehlende Schritte auf (Befunde werden behoben); Abgleich Handbuch gegen Build schlägt an, wenn eine beschriebene Oberfläche oder ein Schritt im Build fehlt; Prüfung auf echte Personendaten und offene Platzhalter in der Datenschutzdokumentation schlägt bei Treffer an.
- **Konkurrierend:** nicht zutreffend (kein Geld-, Kapazitäts-, Sicherheits- oder Datentrennungsfall).
- **Referenztests:** keine Katalog-ID im WP genannt (keine erfunden).
- Testfälle: geplant:tests/acceptance/test_dokumentationspaket.py::test_handbuchschritte_und_oberflaechen_existieren_im_abnahme_build; geplant:tests/acceptance/test_dokumentationspaket.py::test_dokumente_versioniert_ohne_personendaten_und_platzhalter; geplant:tests/acceptance/test_dokumentationspaket.py::test_standardbetrieb_demonstration_mit_protokoll; geplant:tests/acceptance/test_dokumentationspaket.py::test_fahrerschulung_deckt_sicherheitsablaeufe_ab; geplant:tests/acceptance/test_dokumentationspaket.py::test_dokumente_zugaenglich_ueberschriften_alternativtexte

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
