# LH-6.23-SCH-006 – Konfliktprüfung: Doppelbelegung, Qualifikation, Abwesenheit

- **Verantwortlicher:** Rolle Personal/Zeit (Modul `staff-time`)
- **Arbeitspaket:** WP-M4-07
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Schichtkonflikte durch Doppelbelegung derselben Person, fehlende Qualifikationen oder genehmigte Abwesenheiten sind vor Veröffentlichung erkennbar.

**Originalabnahme (unverändert):** Planer erhält konfliktbezogene Meldung einschließlich betroffener Schichten.

## Vertrag

- **Tabellen / DDL:** lesend `staff_shift` (`driver_ref`, `starts_at`, `ends_at`, `timezone`, `roster_revision`, `state`), `absence_request` (Person, Zeitraum, Status, neutrale Art ohne Diagnosefeld; WP-M4-06), Qualifikationen über Leseport (`qualification_revision`, WP-M3-05). Invariante „Qualifikation: Revisionen vergleichen“ (Kap. 04 §4.5). Keine eigene Tabelle zwingend; Konfliktergebnis wird berechnet und im Lesemodell geliefert.
- **API:** Konfliktliste im Dienstplan-Lesemodell von `/v1/rosters`; bei `POST /v1/rosters/{roster_id}/publish` Problem-Details mit stabilen Konfliktcodes (409). Ergebnisobjekt: Konfliktart, Person, betroffene Schicht-IDs, Ursache.
- **Konfliktarten (Vorschlag dieses Plans, mit WP-M4-01 zu bestätigen):** Doppelbelegung derselben Person (beide Schicht-IDs genannt), fehlende oder zum Schichtzeitpunkt nicht gültige Qualifikation (fehlende Qualifikation genannt), Schicht im Zeitraum einer genehmigten Abwesenheit (Meldung ohne Abwesenheitsart). Konkrete Codestrings sind im Detailvertrag WP-M4-01 festzulegen.
- **Überlappungsregel:** Intervall links geschlossen, rechts offen; angrenzende Schichten (Ende = Beginn) sind kein Konflikt.
- **Prüfzeitpunkte:** beim Speichern im Entwurf als Hinweis (Entwurf bleibt speicherbar), bei Publikation als Sperre (Plan mit Konflikt nicht veröffentlichbar, Ursache genannt; AT-39).
- **Zustandsautomat / Schema / Ereignisse:** keine eigenen.
- **Parameter und Kennzahlen:** keine (Ruhezeit/Pausen siehe WP-M4-13).
- **Oberfläche:** UI-12 Konfliktanzeige (Doppelbelegung, unbesetzter Dienst) mit Verweis auf betroffene Schichten; Planerrolle; leer: keine Konflikte.
- **Entscheidungen:** keine.
- **Offene Punkte:** Geltung des M3-Überlappungsverbots (WP-M3-07) nur für verbindliche Schichten, damit Konflikte im Entwurf speicherbar bleiben (OP-38); Unterscheidung Sperre/Hinweis/Empfehlung ist SCH-035 (P2) und nicht Teil dieses Vertrags.

## Tests

- **Positiv:** konfliktfreier Plan ergibt leere Konfliktliste; je Konfliktart ein Fall: zwei überlappende Schichten derselben Person -> Meldung mit beiden Schicht-IDs; Zuweisung ohne gültige Qualifikation -> Meldung mit fehlender Qualifikation; Schicht im Zeitraum genehmigter Abwesenheit -> Meldung ohne Abwesenheitsart; Planer sieht die konfliktbezogene Meldung einschließlich betroffener Schichten (Originalabnahme); beim Speichern im Entwurf erscheint der Konflikt als Hinweis und der Entwurf bleibt speicherbar.
- **Negativ:** abgelaufene Qualifikation zum Schichtzeitpunkt -> Konflikt; angrenzende, nicht überlappende Schichten -> kein Konflikt; Abwesenheitsart und Kommentar erscheinen in keiner Konfliktmeldung; Publikation eines Plans mit einem dieser Konflikte -> 409 mit Ursache (AT-39); nicht genehmigte Abwesenheit erzeugt keinen Konflikt; Konfliktliste enthält nur Schichten des eigenen Mandanten und der berechtigten Depots (SCH-009), fremde Schicht-IDs erscheinen nie in einer Meldung.
- **Konkurrierend:** zwei Planer weisen parallel dieselbe Person überlappend zu: spätestens die Publikation erkennt den Konflikt; Abwesenheitsgenehmigung parallel zur Publikation: Publikation unter Sperre wertet den committeten Stand.
- **Referenztests:** AT-39; Teil von AT-41 (nur Abwesenheit als Konfliktquelle; AT-41 insgesamt in M4 nur teilweise erfüllbar, OP-1).
- Testfälle: AT-39; AT-41; geplant:backend/staff-time/src/test/kotlin/de/taktovia/stafftime/ShiftConflictCheckTest.kt::doppelbelegung_meldung_nennt_beide_schichten; geplant:backend/staff-time/src/test/kotlin/de/taktovia/stafftime/ShiftConflictCheckTest.kt::angrenzende_schichten_kein_konflikt; geplant:backend/staff-time/src/test/kotlin/de/taktovia/stafftime/ShiftConflictCheckTest.kt::parallele_zuweisung_wird_spaetestens_bei_publikation_erkannt; geplant:backend/staff-time/src/test/kotlin/de/taktovia/stafftime/ShiftConflictCheckTest.kt::fehlende_oder_abgelaufene_qualifikation_meldung_nennt_qualifikation; geplant:backend/staff-time/src/test/kotlin/de/taktovia/stafftime/ShiftConflictCheckTest.kt::abwesenheit_meldung_ohne_abwesenheitsart

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
