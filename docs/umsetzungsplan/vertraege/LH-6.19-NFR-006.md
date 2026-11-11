# LH-6.19-NFR-006 – Referenzlastprofil und Skalierungstest

- **Verantwortlicher:** Rolle Plattform/Performance (WP-M5-10)
- **Arbeitspaket:** WP-M5-10
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Die Zielreferenzlast umfasst initial 250 Fahrzeuge je Mandant, 30.000 Buchungen/Tag und 5.000 parallele Sessions.

**Originalabnahme (unverändert):** Skalierungstest dokumentiert Performance unter vereinbartem Referenzprofil.

## Vertrag

- **Tabellen / DDL:** keine Änderung. Befüllung nach WP-M5-02 (mindestens zwei Mandanten, je 250 Fahrzeuge, Buchungsbestand mehrerer Betriebstage bei 30.000 Buchungen/Tag; deterministischer Seed, keine Produktionsdaten). Nach dem Lauf Invariantenabfrage (Kap. 04 §4.5): kein Kapazitätsabschnitt (`capacity_hold`) über Kapazität, keine doppelte Bestätigung, keine Buchung im fremden Mandanten.
- **API:** Lastszenarien unter `tests/performance` (Profile unter `tests/performance/profiles`): Fahrgastsitzungen (Suche `/v1/ride-offers`, Hold, Confirm, Storno, Buchungsstatus `/v1/bookings`), Fahrersitzungen für 250 Fahrzeuge je Mandant (Positionsmeldungen, `/v1/driver/events`, Planabruf), Leitstellensitzungen (Listen, Livekanal), Hintergrundlast (Outbox, Benachrichtigungen, KPI-Projektion). Skalierungsstufen 50 %, 100 %, Überlast; Tagesganglinie mit Peak. Überlast: kontrollierte Degradation mit `429` und `Retry-After`, keine Datenkorruption.
- **Zustandsautomat:** entfällt.
- **JSON-Schema / Ereignisse:** Messbericht nach Berichtsformat WP-M0-25: Perzentile, Fehlerrate, Durchsatz, Sättigung (CPU, Speicher, DB-Verbindungen, Sperrwartezeiten, Queue-Tiefe), Hardware, Datenvolumen, Build, Migrationsstand, Seed; Werte je Mandant und gesamt.
- **Parameter und Kennzahlen:** Gegenprobe, dass Lasttestverkehr `Anfragen`, `Technische Anfragefehler`, `Angebotsquote` der Performance-Umgebung plausibel füllt (keine Abnahme der KPI-Definition).
- **Oberfläche:** keine.
- **Entscheidungen:** D-05 (Peaklast, SLA, Hosting, Wartungsregel): alle Zielwerte sind vorläufige Lastenheftziele (Kap. 08 §8.6), Status je Zielwert im Protokoll „vorläufig, D-05 offen“ oder „vom Auftraggeber bestätigt am …“. D-05 blockiert die Abnahmewirkung, nicht den Bau. Ohne Beschluss wird mit dem Arbeitsprofil aus WP-M5-01 gemessen; das Ergebnis gilt dann nicht als Abnahme des Betriebs-Gates.
- **Offene Punkte:** OP-01 (Peak-Anfragen/s, gleichzeitige Suchen/Buchungen, Mandantenzahl, Peakfaktor, Definition „parallele Session“; unklar, ob 30.000 Buchungen/Tag und 5.000 Sessions je Mandant oder insgesamt gelten; Kap. 01 §1.8 spricht für je Mandant, im Arbeitsprofil wird das als Annahme geführt und im Bericht ausgewiesen). Blockiert den Bau nicht.

## Tests

- **Positiv:** Profil 100 % mit mindestens zwei Mandanten gleichzeitig, 250 Fahrzeugen je Mandant, Buchungsrate entsprechend 30.000 Buchungen/Tag inklusive Peak und 5.000 parallelen Sessions; Bericht dokumentiert Performance je Mandant und gesamt.
- **Negativ:** Überlaststufe liefert `429` mit `Retry-After` ohne Datenkorruption; Lauf mit kaltem Cache; Abweichung vom Profil oder Ziel im Bericht mit Ursache, Engpass, Maßnahme; Invariantenabfrage nach dem Lauf ohne Überbuchung.
- **Konkurrierend (Kapazität, Datentrennung):** 100 parallele Bestätigungen auf den letzten Platz während der Grundlast (PH-AT-004 unter Last): genau eine wirksame Bestätigung; zwei Mandanten mit identischen Objektnamen parallel ohne Fremdreferenzen.
- **Referenztests:** PH-AT-004.
- Testfälle: PH-AT-004; geplant:tests/performance/test_referenzprofil.py::test_skalierungslauf_zwei_mandanten_referenzprofil; geplant:tests/performance/test_referenzprofil.py::test_ueberlast_liefert_429_ohne_datenkorruption; geplant:tests/performance/test_referenzprofil.py::test_keine_ueberbuchung_nach_lauf_invariantenabfrage; geplant:tests/performance/test_referenzprofil.py::test_100_parallele_bestaetigungen_letzter_platz_unter_grundlast; geplant:tests/performance/test_referenzprofil.py::test_gleiche_objektnamen_zweier_mandanten_ohne_fremdreferenzen

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
