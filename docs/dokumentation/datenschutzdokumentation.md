---
dokument_version: 0.1
abnahme_build: ENTWURF-W18-9d035b7
sprache: de
stand: 2026-11-15
arbeitspaket: WP-M5-27
---

# Datenschutzdokumentation TAKTOVIA

**Zweck:** Zusammenstellung des Datenschutzes der Plattform für Auftraggeber, Datenschutzbeauftragte und Betriebsrat: Dateninventar, Löschregeln, Datenschutz-Folgenabschätzung, Rollen- und Feldzugriffskonzept, Standort- und Medienminimierung, Protokollierung und Maskierung. Das Dokument verweist auf die maßgeblichen Quellen im Repository und wiederholt sie nicht vollständig.
**Bezug:** Repository-Stand `9d035b7` (siehe [Übergabeprotokoll](uebergabeprotokoll.md)).
**Rechtlicher Status:** Dieses Dokument ist **kein Rechtsgutachten** und **nicht freigegeben**. Rechtsgrundlagen, Zwecke und Löschklassen sind Vorschläge, bis die verantwortliche Stelle sie freigibt. Zwei Entscheidungen des Auftraggebers blockieren die Endfassung und die Produktivfreigabe, nicht die Erstellung: **D-11** (Aufbewahrung und Löschfristen) und **D-10** (Fahrerdaten, Mitbestimmung, Standortdaten). Jede davon abhängige Frist steht hier als „durch Auftraggeber festzulegen“.

## Inhalt

1. [Dateninventar](#1-dateninventar)
2. [Löschregeln](#2-löschregeln)
3. [Datenschutz-Folgenabschätzung](#3-datenschutz-folgenabschätzung)
4. [Rollen- und Feldzugriffskonzept](#4-rollen--und-feldzugriffskonzept)
5. [Standort- und Medienminimierung](#5-standort--und-medienminimierung)
6. [Protokollierung](#6-protokollierung)
7. [Maskierung von Diagnosedaten](#7-maskierung-von-diagnosedaten)
8. [Betroffenenrechte und Betrieb](#8-betroffenenrechte-und-betrieb)
9. [Offene Entscheidungen](#9-offene-entscheidungen)

## 1. Dateninventar

- **Quelle:** `db/dateninventar.d/*.csv` (je Spalte: Zweck, Rechtsgrundlage, Schutzklasse, Löschklasse, Personenbezug) und `web/operator/src/datenschutz/dateninventar.json`. Im Operator-Web als **Dateninventar**{{src:web/operator/src/datenschutz/DateninventarExport.tsx}} unter **Datenschutz, Audit, Support**{{nav:/admin/datenschutz}} exportierbar.
- **Prüfung:** `db/tests` melden jede Tabelle ohne Inventareintrag und jede personenbezogene Spalte ohne Zweck, Rechtsgrundlage und Löschklasse; neue Spalten ohne Eintrag lassen die Prüfung fehlschlagen.
- **Zwecke des Katalogs** (Auszug): Mandantentrennung, Zugang und Rollen, Konfiguration, Buchungsabwicklung, Zahlungsabwicklung, Buchhaltung und Belege, Personaleinsatz, Sicherheitsprotokoll, Kontakt und Unterstützung, Kommunikationseinwilligung, Nachfrageanalyse.
- **Stand:** Der Katalog ist vorläufig und **nicht freigegeben** (OP-24); die Oberfläche zeigt „Katalog nicht freigegeben (OP-24)“. Zwecke und Rechtsgrundlagen sind durch Auftraggeber festzulegen und freizugeben.

## 2. Löschregeln

- **Quelle:** `db/loeschmatrix.csv` (Datenklasse und Aktion), `retention_rule`, `erasure_job`, `erasure_journal` (WP-M1-33). Die Oberfläche **Aufbewahrungsregeln und Löschprotokoll**{{src:web/operator/src/datenschutz/RetentionOverview.tsx}} zeigt Regeln als Entwurf oder freigegeben.
- **Datenklassen und vorgesehene Aktion:**

| Datenklasse | Aktion | Frist |
|---|---|---|
| `LK_PII` (Kontakt und Unterstützung des Fahrgastkontos) | anonymisieren (Tombstone ohne Personenbezug) | Löschfrist durch Auftraggeber festzulegen (D-11) |
| `LK_STANDORT` (Position, ETA) | löschen | Löschfrist durch Auftraggeber festzulegen (D-11); kurzlebig laut Kap. 01 §1.7 |
| `LK_BETRIEB` (Buchung, Fahrt, Schicht, Sitzung) | anonymisieren | Aufbewahrungsfrist durch Auftraggeber festzulegen (D-11) |
| `LK_BELEG` (Belege, Finanzarchiv) | archivieren, nie löschen | Aufbewahrungsfrist durch Auftraggeber festzulegen (D-11, handels- und steuerrechtlich) |
| `LK_AUDIT` (Sicherheitsprotokoll) | archivieren | Aufbewahrungsfrist durch Auftraggeber festzulegen (D-11) |
| `LK_TECHNISCH` (Idempotenz, Zustellung) | löschen | Löschfrist durch Auftraggeber festzulegen (D-11) |
| `LK_STAMM` (Stammdaten) | anonymisieren bei Mandantenende | Löschzeitpunkt durch Auftraggeber festzulegen (D-11) |

- **Technik, die schon gilt:** Der Löschlauf schreibt jedes Ergebnis in ein unveränderliches Journal; archivierte Daten sind für operative Rollen unsichtbar; eine **Wiederherstellung belebt Gelöschtes nicht wieder** (das Journal wird beim Restore erneut eingespielt, `replay_erasure_journal`). Bestätigte Buchungen und Belege werden nie physisch gelöscht, sondern archiviert und von entbehrlicher Personenbeziehung getrennt.
- **Offen:** Ohne die Fristen der Auftraggeberin läuft kein Löschlauf mit Fristen; der Wächter meldet jede personenbezogene Datenklasse ohne Eintrag.

## 3. Datenschutz-Folgenabschätzung

- **Quelle:** [DSFA Version 1](../datenschutz/dsfa.md): acht Verarbeitungen (Fahrgastkonto, Unterstützungsbedarf, Reise- und Buchungsdaten, Nachfrageanalyse, Fahrer- und Schichtdaten, Positionserfassung, ETA-Prognose, Sicherheitsprotokoll), sieben Risiken, zwölf Maßnahmen.
- **Gate:** `tools/check_dsfa.py` verlangt, dass jedes als Bewegungs- oder Mobilitätsdatum gekennzeichnete Inventarfeld in der DSFA referenziert ist; neue Standort- oder ETA-Felder ohne Nachtrag lassen das Release-Gate fehlschlagen.
- **Stand:** **ENTWURF, nicht freigegeben.** Restrisiko niedrig bis mittel; mittel bleiben die Bewegungsprofile (R-03) und die Leistungs- und Verhaltenskontrolle von Fahrern (R-05), bis Zulässigkeit der Auswertung, Speicherfrist und Mitbestimmung beschlossen sind (D-10, D-11, OP-32). Freigabe durch die verantwortliche Stelle steht aus; die Fristen der DSFA sind durch Auftraggeber festzulegen.

## 4. Rollen- und Feldzugriffskonzept

- **Rollen:** `passenger`, `driver`, `callcenter`, `support`, `dispatcher`, `planner`, `tenant_admin`, `finance`, `staff_planning`, `workshop`. Die Rechtematrix (Rolle mal Tätigkeit) liegt in `backend/modules/identity/src/main/resources/rechtematrix/rechtematrix.csv`; die Datenklassen PII, HR und FINANZ sind markiert. Die Administration (`tenant_admin`) hat auf diese Klassen **keinen automatischen Zugriff**, eine Fachrolle ist gesondert zuzuweisen.
- **Mandantentrennung:** Zeilenschutz in der Datenbank (Row-Level-Security), zusammengesetzte Schlüssel, transaktionslokaler Mandantenkontext, Laufzeitrolle ohne Superuser und ohne Tabelleneigentum; Isolation auch für Objektspeicher, Cache, Export und Ereignisse.
- **Feldschutz:** Kontakt- und Unterstützungsdaten liegen nur verschlüsselt vor; der Zugriff läuft über einen getrennten Modulzugang mit Rolle, Zweck und Audit. Die Fahrerfreigabe wird minimiert projiziert (`driver_release_projection`); die App kennt keine Freitexte zur Einsatzbereitschaft, es gibt nur Ja oder Nein.
- **Nachweise:** Isolationssuite und Feldzugriffsmatrix aus WP-M5-17 (`docs/umsetzungsplan/nachweise/WP-M5-17-sicherheitsabnahme.md`); der Penetrationstest selbst ist noch nicht durchgeführt.

## 5. Standort- und Medienminimierung

- **Standort:** Die Fahrer-App erfasst den Fahrzeugstandort nur während des zugeordneten Dienstes und übermittelt ihn an die Leitstelle; außerhalb des Dienstes entstehen keine Positionsdatensätze (`position_sample` nur mit zugeordneter Schicht, Standorthinweis in der App). Das standortgestützte Stempeln ist in Phase 1 **aus**: Der Parameter `clock_in_geofence_enabled` ist deaktiviert, die Konfigurationsvalidierung weist die Aktivierung ohne dokumentierte Freigabe ab, und der Server verwirft Koordinaten, die ein Client beim Stempeln dennoch sendet. Die Aktivierung braucht die Mitbestimmung (D-10, D-11).
- **Standortalter:** Die Leitstelle sieht das Alter der Position und ein Qualitätskennzeichen („Standort veraltet“); es gibt keine Hintergrundverfolgung außerhalb des Dienstes.
- **Medien:** Belegansichten und Schadensfotos werden von Standort- und Gerätemetadaten (EXIF) bereinigt ausgeliefert; das Original bleibt mit Hash als Nachweis erhalten (Verhältnis zum Original: OP-15). Der Test AT-59 prüft den Mindestumfang.
- **Nicht belegt:** die Standortberechtigung der App beim Stempeln und die Prüfung der Offline-Warteschlange der App auf verworfene Koordinaten (keine Flutter-Umgebung); die EXIF-Minimierung im Gerät und im Browser ist P2.
- **Fristen:** Speicherfrist der Positions- und Medienfassungen: durch Auftraggeber festzulegen (D-11).

## 6. Protokollierung

- **Sicherheitsprotokoll (Audit):** Zugriffe auf geschützte Felder, Konfigurationsänderungen, Freigaben, Sperren und Rechteänderungen werden unveränderlich mit Akteur, Zeitpunkt, Objekt und Korrelationskennung protokolliert (append-only, `audit_event`). Die Abfrage ist zweckgebunden und selbst protokolliert (**Audit des Mandanten**{{src:web/operator/src/audit/AuditList.tsx}} mit **Zweck der Abfrage**{{src:web/operator/src/audit/AuditList.tsx}}).
- **Betriebsprotokolle:** Logs, Metriken und Traces tragen Mandanten- und Korrelationsbezug, aber keine Personendaten (siehe Abschnitt 7). Die Telemetrie beschreibt `docs/betrieb/telemetrie.md`.
- **Aufbewahrung von Protokollen und Telemetrie:** Aufbewahrungsfrist durch Auftraggeber festzulegen (D-11, OP-08).

## 7. Maskierung von Diagnosedaten

- **Regel:** Logs, Trace-Attribute, Fehlerberichte und Alarmtexte enthalten keine ungeschützten Zahlungsdetails und keine unnötigen Kontaktdaten; Karten, IBAN, E-Mail, Telefon, Adresse, Token und Boarding-Codes werden ersetzt (`docs/betrieb/maskierung.md`, Referenzimplementierung `Maskierung.kt`, gleiche Regeln in den Workern und im Web-Client).
- **Prüfung:** `tools/log_stichprobe.py` durchsucht Logquellen aller Komponenten unabhängig von der Implementierung nach den Feldklassen und liefert bei einem Treffer Exit-Code 1; Teil der Betriebsprüfung.
- **Grenze:** Die Maskierung ist eine Arbeitsannahme (OP-09), bis das Pflichtenheft Regeln festlegt; Koordinaten werden vom Prüfskript noch nicht erkannt.

## 8. Betroffenenrechte und Betrieb

1. Auskunft, Berichtigung und Löschung eines Fahrgastkontos laufen über die Anwendung (Anonymisierung des Fahrgastkontos nach der Löschregel, Sperre eines Zugangs über **Konten und Sitzungen sperren**{{src:web/operator/src/identity/AccountAccessAdmin.tsx}}); kein Schritt im Regelbetrieb verlangt Datenbankzugriff.
2. Meldung einer Datenpanne: Ablauf in `docs/betrieb/sicherheitsvorfall.md` (Auslöser und Schweregrad, Eskalationsstufen, Untersuchung, Eindämmung, Entscheidungsvorlage zur Meldung); Übungsdrehbuch `docs/betrieb/uebungsdrehbuch-sicherheitsvorfall.md`.
3. Test- und Schulungsdaten sind synthetisch; Produktionsdaten gelangen nie in Test oder Schulung (Trennung von Test- und Produktionsdaten und -zugängen).

## 9. Offene Entscheidungen

| Nr. | Gegenstand | Wirkung | Stand |
|---|---|---|---|
| D-10 | Fahrerdaten, Standortdaten, Mitbestimmung (Betriebsvereinbarung) | blockiert Endfassung und Produktivfreigabe, Aktivierung der Geozone | offen, durch Auftraggeber festzulegen |
| D-11 | Aufbewahrung, Löschfristen, Verantwortliche, Notbetrieb | blockiert Endfassung, Löschläufe mit Fristen, Produktivfreigabe personenbezogener Daten | offen, Fristen durch Auftraggeber festzulegen |
| OP-24 | Freigabe des Dateninventars | Katalog bleibt vorläufig | offen |
| OP-32 | verantwortliche und freigebende Stelle der DSFA | DSFA bleibt Entwurf | offen |
