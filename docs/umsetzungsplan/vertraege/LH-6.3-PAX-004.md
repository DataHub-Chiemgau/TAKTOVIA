# LH-6.3-PAX-004 – Karte mit Bediengebiet, Haltepunkten und Textzugang

- **Verantwortlicher:** Rolle Passenger-Web (Karte/Liste), fachlich Service/Geo
- **Arbeitspaket:** WP-M2-40
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Karte zeigt Bediengebiet und zulässige Ein- und Ausstiegspunkte mit alternativem Textzugang.

**Originalabnahme (unverändert):** Fahrgast kann Abholpunkt auch ohne Karteninteraktion auswählen.

## Vertrag

- **Tabellen / DDL:** keine neue Tabelle. Lesen aus `service_area` (`area`, `valid_from`, `valid_to`; zum gewählten Zeitpunkt wirksam) und `stop_revision` (`stop_id`, `point`, `name`, `accessibility`; nur aktive, dienstzulässige Revisionen; Aktivstatus/Dienstzuordnung per Migration aus WP-M2-05, OP-07; eine Tabelle `stop` existiert nicht). Fahrgastsicht ohne interne Felder (z. B. `approved_by`); Gebietsgeometrie generalisiert.
- **API:** `GET /v1/service-areas` (Lesesicht Fahrgast), `GET /v1/stops` (Umkreissuche, nur aktive und dienstzulässige). Karten und Liste beziehen dieselbe Menge. Kartenkacheln über konfigurierbare Quelle (Adapter).
- **Zustandsautomat:** keiner. UI-Zustände: Karte verfügbar, Kartenquelle ausgefallen (Liste bleibt voll funktionsfähig), kein Haltepunkt in Reichweite.
- **JSON-Schema / Ereignisse:** Lesesicht-Schemas Fahrgast aus WP-M2-01.
- **Parameter und Kennzahlen:** `stop_walk_max_m` (Maximaler Fußweg in Metern, Katalog J.1; Reichweite der Umkreissuche, soweit im Bau verwendet; Verwendung im WP nicht ausdrücklich für die Umkreissuche genannt).
- **Oberfläche:** Kartenkomponente in UI-01/UI-02 mit wirksamem Bediengebiet und buchbaren Haltepunkten; gleichwertige Haltestellenliste (Name, Entfernung/Gehzeit, Barrierefreiheitsangabe) mit Auswahlfunktion; Textbeschreibung des Bediengebiets; Auswahl per Tastatur und Screenreader (Kap. 06 §6.1).
- **Entscheidungen:** D-09 (Kartenquelle/Lizenz, blockiert nur Produktivschaltung).
- **Offene Punkte:** Sortierung und Umfang der Liste sowie Verhalten bei Kartenausfall legt der Bau im Lesesicht-Vertrag fest (WP-Detailvertrag); OP-07 (`stop_revision` ohne Aktivstatus und Dienstzuordnung) und OP-41 (Schema `accessibility`) betreffen die Datengrundlage; Annahmen siehe WP-M2-05, blockieren den Bau nicht.

## Tests

- **Positiv:** Auswahl des Abholpunkts über die Karte und über die Textliste; Fahrgast wählt den Abholpunkt und schließt die Buchung ausschließlich über die Liste per Tastatur und Screenreader ab (ohne Karteninteraktion); Karte und Liste zeigen dieselbe Menge Haltepunkte; Karte zeigt das zum Zeitpunkt wirksame Bediengebiet.
- **Negativ:** Kartenquelle nicht erreichbar (Liste voll funktionsfähig); kein Haltepunkt in Reichweite (verständlicher Leerzustand); inaktive oder dienstfremde Haltepunkte erscheinen nicht; Lesesicht enthält keine internen Felder; fremder Mandant sieht keine Haltepunkte und Bediengebiete (Datentrennung, 404 auf fremde Haltepunkt-ID); Gebietsgeometrie in der Fahrgastsicht ist generalisiert.
- **Konkurrierend:** Haltepunkt wird deaktiviert, während der Fahrgast die Liste geöffnet hat: Auswahl wird mit Hinweis abgewiesen (Angebot enthält die Revision vollständig oder den Haltepunkt nicht).
- **Referenztests:** AT-19 (Teil).
- Testfälle: AT-19; geplant:web/passenger/src/booking/PickupSelection.test.tsx::abholpunkt_ueber_textliste_ohne_karte_waehlbar; geplant:web/passenger/src/booking/PickupSelection.test.tsx::karte_und_liste_zeigen_gleiche_haltepunktmenge; geplant:web/passenger/src/booking/PickupSelection.test.tsx::kartenausfall_laesst_liste_funktionsfaehig; geplant:web/passenger/src/booking/PickupSelection.test.tsx::kein_haltepunkt_in_reichweite_zeigt_leerzustand; geplant:web/passenger/src/booking/PickupSelection.test.tsx::liste_per_tastatur_und_screenreader_bedienbar; geplant:web/passenger/src/booking/PickupSelection.test.tsx::deaktivierter_haltepunkt_wird_mit_hinweis_abgewiesen; geplant:web/passenger/src/booking/PickupSelection.test.tsx::textbeschreibung_des_bediengebiets_vorhanden; geplant:backend/modules/service-geo/src/test/kotlin/de/taktovia/servicegeo/StopReadViewTest.kt::inaktive_dienstfremde_haltepunkte_und_interne_felder_nie_in_fahrgastsicht; geplant:backend/modules/service-geo/src/test/kotlin/de/taktovia/servicegeo/StopReadViewTest.kt::fremder_mandant_sieht_keine_haltepunkte

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Datentrennung).
