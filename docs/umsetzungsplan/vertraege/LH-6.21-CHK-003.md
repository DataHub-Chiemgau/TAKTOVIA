# LH-6.21-CHK-003 – Checklistenvorlagen und Prüfelemente

- **Verantwortlicher:** Rolle Flotte/Sicherheit (Modul `fleet-safety`, Checklist)
- **Arbeitspaket:** WP-M3-10
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Jedes Prüfelement besitzt einen Titel, Hilfetext, Eingabetyp, Pflichtstatus und optional ein Beispielbild; Antworten unterstützen OK, Mangel und Nicht anwendbar.

**Originalabnahme (unverändert):** Ungültige oder fehlende Antworten blockieren den Abschluss; Nicht anwendbar erfordert bei definierten Punkten eine Begründung.

## Vertrag

- **Tabellen / DDL:** `checklist_template_revision` (Entwurf, freigegeben, abgelöst; freigegebene Revision unveränderlich auch auf Datenbankebene, Änderung erzeugt eine neue Revision) und `checklist_step` (Titel, Hilfetext, Eingabetyp, Pflichtstatus, optionales Beispielbild, Kennzeichen „sicherheitskritisch", Kennzeichen „Begründung bei Nicht anwendbar erforderlich"); Titel, Eingabetyp, Pflichtstatus NOT NULL. Die Antwortwerte `OK`, `Mangel`, `Nicht anwendbar` sind die einzigen zulässigen Statuswerte (CHECK auf der Antworttabelle `inspection_answer`, Spaltenname im DDL aus WP-M3-01/WP-M3-02). Beispielbild nur über den mandantengetrennten Objektspeicher (WP-M1-11). RLS wie V1, Mandant über zusammengesetzte Schlüssel.
- **API:** Vorlagenverwaltung unterhalb `/v1/inspections` bzw. Konfiguration (Pfad im OpenAPI-Entwurf festzulegen). Validierungsfunktion für Antworten: fehlende Pflichtantwort oder Wert außerhalb des Eingabetyps blockiert den Abschluss mit Verweis auf das Element; „Nicht anwendbar" ohne Begründung bei markierten Elementen abgewiesen; ein vierter oder unbekannter Statuswert abgewiesen; Antwort auf ein Element, das nicht zur festen Vorlagenrevision des Laufs gehört, abgewiesen. Die Blockade am Submit (`POST /v1/inspections/{id}/submit`) wird in WP-M3-14 nachgewiesen. Freigabe der Revision mit Vier-Augen-Muster (WP-M1-22). Fremdes Objekt: 404 `NOT_FOUND`.
- **Zustandsautomat:** Vorlagenrevision Entwurf, freigegeben, abgelöst (`vertraege/zustaende/` noch festzulegen).
- **JSON-Schema / Ereignisse:** JSON-Schema Eingabetypen (`vertraege/schemas/`, noch anzulegen; Teil der Bauvorbereitung in WP-M3-10/WP-M3-01, der Typkatalog steht im Pflichtenheft nicht fest); Audit der Freigabe im `audit_event`.
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** UI-11 Vorlageneditor (Grundform) mit Freigabe; Leer-, Fehler- und Rechtezustand.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-16 (Ob „Nicht prüfbar" aus dem Bildschirmkonzept Kap. 06 §6.7 eine vierte Antwort ist und zu „technisch ungeklärt" führt; „nicht prüfbar" ist laut Kap. 06 kein automatisches Ja). Blockiert den Bau der Antwortwerte über drei hinaus nicht; die Originalabnahme gilt mit drei Werten. Ebenfalls nicht im WP festgelegt: ob ein leerer Hilfetext zulässig ist (WP-M3-10 Kriterium 1 nennt als Pflicht nur Titel, Eingabetyp, Pflichtstatus; der Originaltext nennt den Hilfetext als Eigenschaft jedes Prüfelements); der Vertrag erzwingt hier keine Pflicht, speichert und liefert den Hilfetext aber unverändert; im Fachreview zu bestätigen. Blockiert nicht.

## Tests

- **Positiv:** Vorlage mit allen definierten Eingabetypen speichern und freigeben; Prüfelement mit Hilfetext und Beispielbild speichern, Beispielbild über den Objektspeicher des eigenen Mandanten ausliefern; Antworten `OK`, `Mangel`, `Nicht anwendbar` werden akzeptiert; „Nicht anwendbar" mit Begründung bei markiertem Punkt akzeptiert; Freigabe erzeugt Audit-Eintrag.
- **Negativ:** Element ohne Titel, Eingabetyp oder Pflichtstatus nicht speicherbar (API und DB); fehlende Pflichtantwort und ungültiger Wert blockieren den Abschluss mit Elementverweis (Originalabnahme); „Nicht anwendbar" ohne Begründung bei markierten Punkten abgewiesen; vierter/unbekannter Statuswert abgewiesen (auch per SQL, CHECK); Antwort auf Element einer anderen Revision abgewiesen; Änderung einer freigegebenen Revision abgewiesen (API und Datenbank); Freigabe durch den Ersteller selbst abgewiesen (Vier-Augen-Muster WP-M1-22); Beispielbild nicht über fremden Mandanten auslieferbar; Vorlage eines anderen Mandanten: 404.
- **Konkurrierend:** zwei gleichzeitige Freigaben derselben Vorlage: genau eine wirksame Revision; Freigabe parallel zu einer Änderung des Entwurfs: freigegeben wird genau ein konsistenter Stand.
- **Referenztests:** keine Katalog-ID im WP-M3-10 genannt; die Abschlussblockade wird mit WP-M3-14 nachgewiesen (AT-27 ist deren positiver Gegenpart), hier nicht beansprucht.
- Testfälle: geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/ChecklistTemplateTest.kt::pruefelement_ohne_pflichtfelder_nicht_speicherbar; geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/ChecklistTemplateTest.kt::nicht_anwendbar_ohne_begruendung_abgewiesen; geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/ChecklistTemplateTest.kt::fehlende_oder_ungueltige_antwort_blockiert_mit_elementverweis; geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/ChecklistTemplateTest.kt::gleichzeitige_freigaben_genau_eine_revision; geplant:db/tests/test_checklistenvorlage.py::test_freigegebene_revision_unveraenderlich_und_statuswerte

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
