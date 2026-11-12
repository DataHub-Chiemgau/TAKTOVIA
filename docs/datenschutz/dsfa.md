---
dsfa_version: 1
status: ENTWURF
datum: 2026-11-11
inventar_stand: Dateninventar WP-M1-32 (Katalog vorläufig, OP-24)
freigabe_stelle:
freigabe_name:
freigabe_datum:
freigabe_vermerk:
---

# Datenschutz-Folgenabschätzung (DSFA) TAKTOVIA, Version 1

**Status: ENTWURF, nicht freigegeben.** Die Kopfzeilen `freigabe_*` sind bewusst leer: die Freigabe erfolgt durch die verantwortliche Stelle (Datenschutzbeauftragte des Auftraggebers bzw. der Betreiber, OP-32) und kann nicht durch Software oder durch dieses Dokument ersetzt werden. Das Release-Gate (`python3 tools/check_dsfa.py --release`) schlägt deshalb absichtlich fehl, bis ein Freigabevermerk mit Stelle, Person, Datum und Vermerk eingetragen ist. Die rechtliche Bewertung selbst liegt beim Auftraggeber (WP-M1-42, Nicht im Umfang); dieses Dokument ordnet die Verarbeitungen, Risiken und technischen und organisatorischen Maßnahmen und weist die Maßnahmen den umsetzenden Arbeitspaketen zu. Alle Rechtsgrundlagen sind Vorschläge des Dateninventars (Katalog vorläufig und nicht freigegeben, OP-24, D-11).

- **Gegenstand:** Verarbeitungen personenbezogener Daten der Plattform in Meilenstein M1 und die laut Pflichtenheft geplanten Standort-, Fahrer- und ETA-Daten (Kap. 01 §1.7: Standort/Driver-ETA, Betriebsrat/DSFA, zulässige Auswertung und Frist vor Produktion festzulegen).
- **Quellen:** Dateninventar (`db/dateninventar.d`, WP-M1-32), Löschmatrix (`db/loeschmatrix.csv`, WP-M1-33), Feldschutz (WP-M1-31), Isolationsnachweise (WP-M1-01, WP-M1-11).
- **Fortschreibung:** Mit den Verarbeitungen aus WP-M3-34 (Positionserfassung), WP-M4-61 und WP-M4-62 (Fahrzeit und Prognose) erzwingt das Gate den Nachtrag am realen Datenfluss: jedes neue, als Bewegungs-/Mobilitätsdatum gekennzeichnete Inventarfeld braucht eine Referenz in Abschnitt 6.

## 1. Kennzeichnung Bewegungs-/Mobilitätsdatum

Ein Feld des Dateninventars ist ein **Bewegungs-/Mobilitätsdatum**, wenn es `personenbezogen = ja` trägt und die Schutzklasse `STANDORT_ETA` hat (Kap. 01 §1.7: Standort/Driver-ETA). Das Gate prüft zusätzlich, dass kein personenbezogenes Feld mit Orts- oder Bewegungsbezug im Namen (z. B. `position`, `origin`, `eta`) ungekennzeichnet bleibt.

## 2. Verarbeitungen

| ID | Verarbeitung | Datenkategorien (Inventar) | Betroffene | Zweck (Katalog) | Rechtsgrundlage (Vorschlag) | Stand |
|---|---|---|---|---|---|---|
| V-01 | Fahrgastkonto und Kontakt | `passenger_private`, `passenger_contact`, `contact_verification`, `passenger_preference`, `consent_revision` (verschlüsselte Kontaktdaten, Pseudonym, Präferenzen, Einwilligungsnachweis) | Fahrgäste | Kontakt und Unterstützung, Einwilligung | Art. 6 Abs. 1 lit. b (Vertrag), lit. a (Marketing) | M1 gebaut |
| V-02 | Unterstützungsbedarf | `passenger_private.support_ciphertext`, `passenger_trip.assistance_codes` (Katalogcodes, keine Diagnose, verschlüsselt) | Fahrgäste mit Unterstützungsbedarf | Transportrelevante Unterstützung | Art. 6 Abs. 1 lit. b; bei Gesundheitsbezug Art. 9 Abs. 2 zu klären (Auftraggeber) | M1 gebaut, M2 erweitert |
| V-03 | Reise-, Buchungs- und Angebotsdaten mit Ort und Zeit | `journey_leg` (Start, Ziel, Zeitfenster), `booking.promise_snapshot`, `fare_quote.snapshot` | Fahrgäste | Buchungsabwicklung | Art. 6 Abs. 1 lit. b | Tabellen M1, Fluss ab M2 |
| V-04 | Nachfrageanalyse abgelehnter Anfragen | `travel_attempt.coarse_origin`, `coarse_destination` (nur grobe Auflösung) | Fahrgäste (pseudonym) | Angebotsplanung | Art. 6 Abs. 1 lit. f | M1 Tabelle |
| V-05 | Fahrer- und Schichtdaten, Geräteanmeldung, Fahrerereignisse | `driver`, `staff_shift`, `driver_device`, `driver_event`, `driver_release_projection` | Fahrer (Beschäftigte) | Personaleinsatz | § 26 BDSG, Betriebsvereinbarung | M1/M3 teilweise |
| V-06 | Positionserfassung Fahrzeuge und Fahrer (geplant) | `position_sample` (WP-M3-34): Position, Zeit, Genauigkeit, Fahrzeug-/Fahrerbezug | Fahrer, indirekt Fahrgäste an Bord | Leitstelle, Standortalter, Disposition | Art. 6 Abs. 1 lit. b/f, § 26 BDSG (Betriebsrat) | **geplant (M3)**, noch kein Datenfluss |
| V-07 | Fahrzeit- und ETA-Prognose (geplant) | Referenzfahrzeit (WP-M4-61), Prognose mit Haltezeiten und Sicherheitsreserve (WP-M4-62), Ankunftszeiten je Halt | Fahrgäste, Fahrer | Fahrgastinformation, Planung | Art. 6 Abs. 1 lit. b/f | **geplant (M4)**, noch kein Datenfluss |
| V-08 | Sicherheitsprotokoll und Zugang | `audit_event`, `session_register`, `account_access`, `membership`, `idempotency_record` | Nutzer und Beschäftigte | Sicherheitsprotokoll, Zugang | Art. 6 Abs. 1 lit. f, lit. b | M1 gebaut |

## 3. Risiken und Restrisiko

Bewertung qualitativ (E = Eintrittswahrscheinlichkeit, S = Schwere des Schadens für Betroffene; niedrig/mittel/hoch) vor und nach den Maßnahmen aus Abschnitt 5. Die Einstufung ist ein Vorschlag für die Freigabeprüfung der verantwortlichen Stelle.

| ID | Risiko | Verarbeitung | E/S vor Maßnahmen | Maßnahmen | Restrisiko |
|---|---|---|---|---|---|
| R-01 | Zugriff auf Daten eines anderen Mandanten (Fremdmandant) | alle | mittel / hoch | M-01, M-02, M-08 | niedrig |
| R-02 | Unbefugter Zugriff auf Kontakt- und Unterstützungsdaten durch Rollen mit allgemeinem Zugriff | V-01, V-02 | mittel / hoch | M-03, M-04, M-08 | niedrig |
| R-03 | Bewegungsprofil einer Person aus Reise- oder Positionsdaten (Orts- und Zeitbezug) | V-03, V-04, V-06, V-07 | mittel / hoch | M-05, M-06, M-07 | mittel (bis Positionsdaten und Frist beschlossen sind) |
| R-04 | Daten bleiben über den Zweck hinaus gespeichert; Gelöschtes kehrt durch Wiederherstellung zurück | alle | mittel / mittel | M-06, M-09 | niedrig, Fristen offen (D-11) |
| R-05 | Leistungs- und Verhaltenskontrolle von Fahrern über Position und Fahrzeit | V-05, V-06, V-07 | mittel / hoch | M-05, M-10 | mittel (Betriebsvereinbarung offen) |
| R-06 | Personendaten gelangen in Entwicklung, Logs oder Metriken | alle | mittel / mittel | M-11, M-12 | niedrig |
| R-07 | Verlust oder Manipulation von Sicherungen | alle | niedrig / hoch | M-09 | niedrig |

**Restrisiko:** insgesamt niedrig bis mittel. Mittel bleiben R-03 und R-05, weil Positions- und ETA-Daten erst mit M3/M4 entstehen: Zulässigkeit der Auswertung, Speicherfrist und Mitbestimmung (Betriebsrat) sind offen (D-10, D-11, OP-32). Die Produktivschaltung personenbezogener Daten bleibt bis zum Beschluss der Löschmatrix und zur Freigabe dieser DSFA blockiert.

## 4. Bewertung der Bewegungs- und Mobilitätsdaten

Bewegungs- und Mobilitätsdaten sind Angaben, die Aufenthaltsorte und Fahrten einer Person zu bestimmten Zeiten erkennen lassen. Sie sind in der Plattform von zentraler Bedeutung und zugleich besonders schutzbedürftig, weil sie Gewohnheiten, Wohn- und Arbeitsorte und gegebenenfalls Unterstützungsbedarfe erkennen lassen.

### 4.1 Reise- und Buchungsdaten der Fahrgäste

Start- und Zielpunkt sowie die Zeitfenster einer Fahrt (`journey_leg`, Angebots- und Zusage-Momentaufnahmen) sind für die Vertragserfüllung erforderlich und werden nur im Mandantenkontext verarbeitet (RLS und zusammengesetzte Schlüssel). Notwendig ist die konkrete Auflösung nur während der Buchung und Ausführung; für die Nachfrageanalyse genügt eine grobe Auflösung (`travel_attempt.coarse_*`, feinere Auflösung wird nicht angelegt, SEC-004). Risiko: Bewegungsprofile aus der Fahrtenhistorie (R-03). Maßnahmen: Isolation (M-01, M-02), Löschregel je Datenklasse mit Archivierung für operative Rollen unsichtbar (M-06), Rechte nach Rolle und Dienst (M-08), kein Standort in Logs (M-11).

### 4.2 Standortdaten von Fahrzeugen und Fahrern (`position_sample`, geplant)

Das Pflichtenheft sieht Positionserfassung mit Standortalter vor (WP-M3-34, `position_sample`). Die Daten erlauben die Rekonstruktion von Fahrwegen und von Aufenthalten von Fahrern und, über die Fahrzeugzuordnung, von Fahrgästen an Bord. Kap. 01 §1.7 verlangt Pseudonymisierung, kurzlebige operative Rohdaten und getrennte Modelle sowie die Festlegung von Betriebsrat/DSFA, zulässiger Auswertung und Frist vor Produktion. Bewertung: erforderlich ist die aktuelle Position für Disposition und Standortalter; die Speicherung von Rohdaten über die operative Nutzung hinaus ist nicht erforderlich. Vorgesehene Maßnahmen: kurze Lebensdauer der Rohdaten mit Löschregel (Löschmatrix, Klasse `LK_STANDORT`, Frist offen D-11), Pseudonymisierung beim Fahrerbezug, Auswertung nur aggregiert und mit Mindestzellgrößen, Zugriff nur für zugeordnetes Personal. Es besteht noch kein Datenfluss; sobald `position_sample` gebaut ist, erzwingt das Gate den Nachtrag der Felder in Abschnitt 6.

### 4.3 Fahrerdaten (Schicht, Geräte, Ereignisse, Freigabe)

Schichtzeiten, Geräteanmeldung und Fahrerereignisse (`staff_shift`, `driver_device`, `driver_event`) sind Beschäftigtendaten (§ 26 BDSG). Sie erlauben Rückschlüsse auf Arbeitsverhalten (R-05). Maßnahmen: Trennung in eigene Tabellen und Datenklasse `PERSONAL_HR`, kein automatischer Zugriff für Mandantenadministratoren (Rechtematrix), Fahrerfreigabe an andere Mandanten nur zeitlich begrenzt und minimiert (WP-M1-35, Projektion ohne Klarname), Auditierung des Zugriffs. Offen: Betriebsvereinbarung und Mitbestimmung (Betriebsrat), Zulässigkeit der Auswertung von Fahrerereignissen.

### 4.4 ETA-Daten (Ankunftsprognosen)

Fahrzeit- und Ankunftsprognosen (WP-M4-61 fahrerneutrale Referenzfahrzeit, WP-M4-62 Prognose mit Haltezeiten und Sicherheitsreserve) sind fahrgastbezogene Zeitangaben und, wenn sie fahrerindividuell berechnet würden, Leistungsdaten. Die Anforderung ETA-001 verlangt eine **fahrerneutrale** Referenzfahrzeit; das verringert R-05. Bewertung: erforderlich für die Fahrgastinformation und Planung; eine Zuordnung zu einzelnen Fahrern ist nicht erforderlich. Maßnahmen: fahrerneutrale Berechnung als Anforderung, Speicherung nur als Planungs- und Zusagedaten mit Löschregel, keine Weitergabe an Dritte ohne Kooperationsfreigabe (Zweck und Feldliste je Freigabe). Es besteht noch kein Datenfluss; das Gate erzwingt den Nachtrag.

## 5. Maßnahmen

| ID | Maßnahme | Umsetzende Arbeitspakete | Nachweis |
|---|---|---|---|
| M-01 | Mandantenisolation in der Datenbank: RLS, zusammengesetzte Schlüssel, transaktionslokaler Tenantkontext, Runtime-Rolle ohne Superuser/BYPASSRLS/Eigentum | WP-M1-01 | `db/tests/test_03_rls.py`, `test_isolation_alle_tabellen.py`, Backend-Isolationstests |
| M-02 | Isolation außerhalb der Datenbank: Objektspeicher, Cache, Suche, Export, Ereignisse nur mit Mandantenbezug | WP-M1-11 | Isolationstests der Plattform |
| M-03 | Feldschutz: Kontakt und Unterstützungsbedarf nur verschlüsselt, Zugriff über getrennten Modulzugang mit Rolle, Zweck und Audit | WP-M1-31 | `FieldProtectionTest`, `db/tests/test_08_feldschutz.py` |
| M-04 | Rollen und Rechtematrix, Dienst- und Objektberechtigung; Administrator ohne automatischen PII-/HR-Zugriff | WP-M1-07, WP-M1-08 | Rechtematrixtest |
| M-05 | Datensparsamkeit und Trennung: grobe Auflösung für die Nachfrageanalyse, Fahrerfreigabe mit minimierter Projektion, fahrerneutrale Referenzfahrzeit | WP-M1-32, WP-M1-35, WP-M4-61 | Dateninventar-Prüfung, Projektionsvertrag |
| M-06 | Aufbewahrungs-, Archivierungs- und Löschregeln je Datenklasse, Löschlauf mit Journal, Archiv für operative Rollen unsichtbar | WP-M1-33 | `ErasureJobTest`, `PassengerRetentionTest`, `db/tests/test_10_retention.py` |
| M-07 | Zugriff auf Auswertungen von Positions- und Reisedaten nur aggregiert oder zweckgebunden, Auditabfrage mit Zweckangabe | WP-M1-12, WP-M3-34 | Auditabfrage; Aggregation mit M3 |
| M-08 | Authentisierung und Sitzungsschutz: OIDC, MFA für privilegierte Konten, serverseitige Sitzungssperre, Tenantwechsel nur über geprüfte Mitgliedschaft | WP-M1-04, WP-M1-06, WP-M1-10 | Sicherheitstests des Ressourcenservers |
| M-09 | Verschlüsselte, veränderungsgeschützte Backups mit Restore-Test; Lösch-/Sperrjournal wird beim Restore wieder eingespielt | WP-M1-39, WP-M1-33 | `tests/performance/test_restore_drehbuch.py` |
| M-10 | Revisionssicheres Audit (append-only) für Zugriffe auf geschützte Felder und Konfigurationsänderungen; Mitbestimmung vor Nutzung von Fahrerdaten (organisatorisch) | WP-M1-46, WP-M1-12 | Audit-Tests; Betriebsvereinbarung offen |
| M-11 | Keine Personendaten in Logs, Metriken und Warnungen; Kartendaten und Secrets nicht im System | WP-M1-36, WP-M1-37, WP-M1-47 | Log- und Label-Tests |
| M-12 | Trennung von Test- und Produktionsdaten; Produktionsdumps nie in Nicht-Produktion | WP-M1-40 | `db/tests/test_umgebungstrennung.py` |

## 6. Feldreferenz: Bewegungs-/Mobilitätsfelder des Dateninventars

Jedes im Dateninventar als Bewegungs-/Mobilitätsdatum gekennzeichnete Feld ist hier referenziert (Gate-Prüfung). Neue Felder werden mit der Verarbeitung (Spalte V-ID) nachgetragen.

| Feld | V-ID | Hinweis | Aufbewahrung |
|---|---|---|---|
| `booking.promise_snapshot` | V-03 | Ort bzw. Zeitfenster der Fahrt des Fahrgasts | Löschklasse laut Inventar; Frist offen (D-11) |
| `fare_quote.snapshot` | V-03 | Ort bzw. Zeitfenster der Fahrt des Fahrgasts | Löschklasse laut Inventar; Frist offen (D-11) |
| `journey_leg.arrival_earliest` | V-03 | Ort bzw. Zeitfenster der Fahrt des Fahrgasts | Löschklasse laut Inventar; Frist offen (D-11) |
| `journey_leg.arrival_latest` | V-03 | Ort bzw. Zeitfenster der Fahrt des Fahrgasts | Löschklasse laut Inventar; Frist offen (D-11) |
| `journey_leg.destination_snapshot` | V-03 | Ort bzw. Zeitfenster der Fahrt des Fahrgasts | Löschklasse laut Inventar; Frist offen (D-11) |
| `journey_leg.origin_snapshot` | V-03 | Ort bzw. Zeitfenster der Fahrt des Fahrgasts | Löschklasse laut Inventar; Frist offen (D-11) |
| `journey_leg.pickup_earliest` | V-03 | Ort bzw. Zeitfenster der Fahrt des Fahrgasts | Löschklasse laut Inventar; Frist offen (D-11) |
| `journey_leg.pickup_latest` | V-03 | Ort bzw. Zeitfenster der Fahrt des Fahrgasts | Löschklasse laut Inventar; Frist offen (D-11) |
| `journey_leg.planned_arrival` | V-03 | Ort bzw. Zeitfenster der Fahrt des Fahrgasts | Löschklasse laut Inventar; Frist offen (D-11) |
| `journey_leg.planned_departure` | V-03 | Ort bzw. Zeitfenster der Fahrt des Fahrgasts | Löschklasse laut Inventar; Frist offen (D-11) |
| `position_sample.accuracy_m` | V-06 | Genauigkeit der Standortmessung (Tabelle seit WP-M3-02 angelegt, noch kein Datenfluss) | Löschklasse `LK_STANDORT`; Frist offen (D-11) |
| `position_sample.measured_at` | V-06 | Messzeitpunkt der Position (Tabelle seit WP-M3-02 angelegt, noch kein Datenfluss) | Löschklasse `LK_STANDORT`; Frist offen (D-11) |
| `position_sample.position` | V-06 | Position von Fahrzeug bzw. Fahrer (Tabelle seit WP-M3-02 angelegt, noch kein Datenfluss) | Löschklasse `LK_STANDORT`; Frist offen (D-11) |
| `travel_attempt.coarse_destination` | V-04 | Auflösung grob (Zone), kein Zeitpunkt | Löschklasse laut Inventar; Frist offen (D-11) |
| `travel_attempt.coarse_origin` | V-04 | Auflösung grob (Zone), kein Zeitpunkt | Löschklasse laut Inventar; Frist offen (D-11) |

## 7. Entscheidungen und offene Punkte

- **D-11** (Löschfristen, Löschmatrix, Verantwortliche): Fristen sind nicht festgelegt; `db/loeschmatrix.csv` führt nur Aktionsvorschläge. Produktivschaltung blockiert.
- **D-10** (Hostingregion und Standortdaten, Zulässigkeit der Auswertung) und **D-05** (Hosting): offen.
- **OP-32:** DSFA-Vorlage, Verantwortliche und freigebende Stelle sind nicht benannt.
- Betriebsrat und Betriebsvereinbarung für Fahrerdaten (V-05 bis V-07): offen.

## 8. Freigabe

Freigabevermerk der verantwortlichen Stelle (Kopfzeilen `freigabe_stelle`, `freigabe_name`, `freigabe_datum`, `freigabe_vermerk`; derzeit leer): **keine Freigabe erteilt.** Mit der Freigabe wird `status` auf `FREIGEGEBEN` gesetzt; spätere Änderungen an Verarbeitungen oder Feldern erzeugen eine neue Version.
