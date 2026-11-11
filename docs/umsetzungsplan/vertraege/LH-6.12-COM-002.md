# LH-6.12-COM-002 – Nachrichtenvorlagen je Sprache, Verkehrsprodukt, Ereignistyp

- **Verantwortlicher:** Rolle Kommunikation (Modul `communication-support`)
- **Arbeitspaket:** WP-M2-33
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Nachrichten besitzen Vorlagen je Sprache, Verkehrsprodukt und Ereignistyp.

**Originalabnahme (unverändert):** Sprachwechsel verwendet richtige Vorlage ohne Verlust der Buchungsreferenz.

## Vertrag

- **Tabellen / DDL:** neue Tabelle `channel_template_revision` (Migration nach V3, nächste freie Versionsnummer, Kap. 04 §4.4), Spalten mindestens: `tenant_id`, `id`, `event_type`, `language` (BCP-47), `service_id` bzw. Verkehrsprodukt (nullable für Rückfall), `channel`, `revision bigint`, `body_template`, `valid_from`, `valid_to`; Primärschlüssel `(tenant_id, id)`, Unique `(tenant_id, event_type, language, service_id, channel, revision)`, Check `valid_to IS NULL OR valid_to > valid_from`; da `service_id` nullbar ist, muss die Unique-Regel NULL-Werte als gleich behandeln (z. B. `NULLS NOT DISTINCT`), sonst sind Dubletten ohne Verkehrsprodukt möglich; Trigger/Grant verbieten UPDATE und DELETE (nur anfügen); RLS `tenant_isolation`. Revisionen sind unveränderlich (Änderung = neue Revision). `notification` speichert die verwendete Vorlagenrevision (`template_revision_id`).
- **Vorlagenauflösung:** nach (Ereignistyp, Verkehrsprodukt/Dienst, Sprache, Kanal) mit dokumentierter Rückfallkette: 1. exakt; 2. gleiche Sprache ohne Verkehrsprodukt; 3. Standardsprache Deutsch (Kap. 06 §6.1) mit Verkehrsprodukt; 4. Deutsch ohne Verkehrsprodukt; sonst Kanalfehler am Auftrag, keine leere Nachricht.
- **Platzhalterkatalog je Ereignistyp** (typisiert): Buchungsreferenz (Pflicht bei buchungsbezogenen Ereignistypen), Buchungsnummer, Treffpunkt, Zeitfenster, ETA. Werte werden kanalgerecht maskiert (HTML/Markup-Escaping E-Mail, Klartext SMS/Push); fehlender Platzhalterwert -> Rendering-Fehler -> Kanalfehler.
- **Aktivierungsregel:** Eine Revision ohne Pflichtplatzhalter Buchungsreferenz kann für buchungsbezogene Ereignistypen nicht aktiviert werden (Validierung beim Anlegen/Aktivieren, Fehler 422).
- **API:** Vorlagenpflege unter `/v1/notifications` (Unterpfad, z. B. `/v1/notifications/templates`) oder Seed-basierte Pflege in P1; Entscheidung in OP 29. Lesen/Pflege nur berechtigte Rollen des eigenen Mandanten, fremde ID -> 404.
- **Zustandsautomat:** keine eigene Revisionsmaschine; Revision ist "gültig ab valid_from" und unveränderlich.
- **JSON-Schema / Ereignisse:** keine eigenen; Sprachpräferenz stammt vom Fahrgastkonto (WP-M1-29, `passenger_preference`; die Spalte ist im Plan nicht festgelegt, Abgleich mit WP-M1-29 im Bau).
- **Parameter und Kennzahlen:** keine Katalogparameter.
- **Oberfläche:** Sprachwahl des Fahrgasts in UI-05 (WP-M2-36); Zustand: Wechsel wirkt auf folgende Nachrichten, die Buchungsreferenz bleibt unverändert.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP 29 (WP-Dokument): welche zweite Sprache neben Deutsch in P1 (die Abnahme setzt mindestens zwei voraus; Bau und Test nutzen Deutsch und Englisch als Testsprachen, die Auslieferungssprache ist fachlich zu bestätigen) und ob die Pflege über Oberfläche/API oder nur Seed erfolgt. Verhältnis Dienst und Verkehrsprodukt ist im Kern-DDL nicht modelliert (OP 6): bis zur Klärung dient `service.id` als Träger des Verkehrsprodukts. Beides blockiert den Bau nicht.

- **Umsetzungsabweichungen (WP-M2-33, Vertragskorrektur beim Bau):** (1) Pflege in P1 ohne HTTP-Schnittstelle (OP 29, Entscheidung des Baus): Dienstoperationen `TemplateService.create|get|list|seedDefaults` (Modul-API) und Seed der Standardvorlagen Deutsch und Englisch; der Test „fremde Vorlage ergibt 404“ prüft daher die Modul-API (`TemplateAccessTest`), nicht eine HTTP-Route. (2) `valid_to` endet die Gültigkeit einer Revision; eine neuere Revision löst die ältere sofort ab (höchste zum Zeitpunkt gültige Revision), die alte Zeile bleibt unverändert. (3) Zusätzlich Spalte `subject_template` (E-Mail-Betreff, Push-Titel). (4) Der Aktivierungsfehler 422 trägt die Verletzungen `/body_template` (`REQUIRED` fehlender Pflichtplatzhalter, `NOT_IN_ENUM` unbekannter Platzhalter). Auflösung „gleiche Sprache“: voller Tag, dann Hauptteil, dann andere Region derselben Sprache. Die Sprache des Fahrgasts kommt aus `passenger_private.locale` (WP-M1-28, Abgleich mit WP-M1-29: die Sprache liegt am Konto).

## Tests

- **Positiv:** zwei Sprachen x zwei Verkehrsprodukte x Ereignistypen; Sprachwechsel des Fahrgasts bei gleicher Buchung und gleichem Ereignistyp -> Vorlage der neuen Sprache, Buchungsreferenz identisch in beiden Nachrichten; zwei Dienste mit unterschiedlichem Verkehrsprodukt erhalten unterschiedliche Texte; versendete Nachricht behält ihre Vorlagenrevision nach Anlage einer neuen Revision.
- **Negativ:** fehlender Platzhalterwert -> Kanalfehler; fehlende Vorlage ohne Rückfall -> Kanalfehler statt leerer Nachricht; Aktivierung ohne Buchungsreferenz -> 422; Markup im Platzhalterwert wird maskiert; Vorlage eines fremden Mandanten nicht lesbar (404).
- **Konkurrierend:** Sprachwechsel parallel zur Nachrichtenerzeugung -> genau eine definierte Sprache je Nachricht, Buchungsreferenz nie verloren; Aktivierung einer neuen Revision parallel zum Rendering -> Nachricht verweist auf eine konsistente Revision.
- **Referenztests:** keine Katalog-ID im WP genannt (WP-M2-33 nennt keine); Baustein für AT-01/AT-07 (Nachrichtenteil) über COM-001.
- Testfälle: backend/modules/communication-support/src/test/kotlin/de/taktovia/communicationsupport/TemplateResolutionTest.kt::sprachwechsel_nutzt_richtige_vorlage_mit_buchungsreferenz; backend/modules/communication-support/src/test/kotlin/de/taktovia/communicationsupport/TemplateResolutionTest.kt::aktivierung_ohne_buchungsreferenz_wird_abgelehnt; backend/modules/communication-support/src/test/kotlin/de/taktovia/communicationsupport/TemplateResolutionTest.kt::rueckfallkette_und_fehlende_vorlage_ergibt_kanalfehler; backend/modules/communication-support/src/test/kotlin/de/taktovia/communicationsupport/TemplateResolutionTest.kt::zwei_dienste_mit_verschiedenem_verkehrsprodukt_unterschiedliche_texte; backend/modules/communication-support/src/test/kotlin/de/taktovia/communicationsupport/TemplateResolutionTest.kt::platzhalterwert_markup_wird_maskiert_und_fehlender_wert_ergibt_kanalfehler; backend/modules/communication-support/src/test/kotlin/de/taktovia/communicationsupport/TemplateResolutionTest.kt::versendete_nachricht_behaelt_vorlagenrevision; backend/modules/communication-support/src/test/kotlin/de/taktovia/communicationsupport/TemplateResolutionTest.kt::sprachwechsel_und_aktivierung_parallel_zum_rendering_konsistent; backend/modules/communication-support/src/test/kotlin/de/taktovia/communicationsupport/TemplateResolutionTest.kt::katalog_und_datenbank_pruefung_stimmen_ueberein; backend/modules/communication-support/src/test/kotlin/de/taktovia/communicationsupport/TemplateResolutionTest.kt::seed_ist_idempotent_und_liefert_deutsch_und_englisch; backend/modules/communication-support/src/test/kotlin/de/taktovia/communicationsupport/TemplateAccessTest.kt::fremde_vorlage_ergibt_404; db/tests/test_channel_template_revision.py::test_revision_unveraenderlich_und_rls; db/tests/test_channel_template_revision.py::test_pflichtplatzhalter_buchungsreferenz_in_der_datenbank; db/tests/test_channel_template_revision.py::test_unique_behandelt_null_verkehrsprodukt_als_gleich

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Datentrennung, Maskierung).

