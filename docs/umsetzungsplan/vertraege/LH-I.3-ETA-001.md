# LH-I.3-ETA-001 – Fahrerneutrale Referenzfahrzeit

- **Verantwortlicher:** Rolle Disposition (ETA-Grundmodell; Modulzuordnung laut WP-M4-01, OP-34)
- **Arbeitspaket:** WP-M4-61
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Für jede Strecke wird eine fahrerneutrale, verkehrs- und regelkonforme Referenzfahrzeit berechnet.

**Originalabnahme (unverändert):** Ein Referenzlauf mit identischen Straßendaten ergibt unabhängig von der Fahrer-ID dieselbe Grundzeit.

## Vertrag

- **Tabellen / DDL:** neue Tabelle `eta_model_revision` (Kap. 04 §4.4, neue Migration nach V3; Schema Vorschlag, in WP-M4-01 festzulegen, OP-34): `(tenant_id,id)`, `version`, `parameters jsonb`, `road_data_version`, `approved_at`/Freigabe, RLS, Revision/Audit; Parameter einer Revision sind nach Freigabe unveränderlich. Keine Fahrerspalte.
- **API:** interne Fachoperation (keine eigene Route in P1): Eingaben ausschließlich Strecke, Zeit, Fahrzeugprofil und Straßendatenversion; die Fahrer-ID ist kein Parameter der Schnittstelle. Referenzfahrzeit = rechtmäßige Routingzeit des Routingadapters (WP-M2-10). Jede Prognose liefert Modellversion, Straßendatenversion und Fallbackgrund. Determinismus: gleiche Eingaben und gleiche Datenversion ergeben dieselbe Grundzeit. Nicht in P1: `/v1/eta/models/{id}/quality`, `/v1/eta/profiles/{id}/challenge` (M8).
- **Zustandsautomat / Schema / Ereignisse:** keine; Architekturregel (ArchUnit): das Grundmodell hat keinen Zugriff auf Fahrerprofile.
- **Parameter und Kennzahlen:** `eta_personalization_enabled` (Mandant; Katalog: nur nach genehmigter Risiko-/Mitbestimmungsprüfung; in P1 nicht wirksam aktivierbar, ob Schreibzugriff abgewiesen oder Wert ignoriert wird, legt WP-M4-01 fest), `eta_fallback_policy` (Enum, Mandant; sichere fahrerneutrale Referenz verpflichtend; Enum-Werte im Pflichtenheft nicht festgelegt). Keine Kennzahlen.
- **Oberfläche:** keine eigene; Konfigurationsansicht zeigt `eta_personalization_enabled` schreibgeschützt `false` in P1.
- **Entscheidungen:** D-10 (Favorit: P1 ohne individuelles Fahrerprofil; Bau nach Favorit, Personalisierung bleibt aus), D-09 (Routinganbieter; Valhalla/OSM Arbeitsstand, blockiert nur Produktivschaltung).
- **Offene Punkte:** OP-34 (Modulzuordnung, Schnittstelle zur Disposition, ob M2 bis zur Lieferung mit reiner Routingzeit arbeitet; Pfad der Tests folgt dem Aufrufer `booking-dispatch` und ändert sich bei anderer Modulzuordnung); OP-41 (Umfang laufender ETA); Quelle für „verkehrskonform“ (Verkehrslagedaten) im Pflichtenheft nicht benannt, in P1 gilt die Routingzeit der Straßendaten; Werte von `eta_fallback_policy`. Alle blockieren den Bau nicht; Schnittstelle und Schema `eta_model_revision` werden in WP-M4-01 festgelegt.

## Tests

- **Positiv:** Referenzstrecken mit Sollzeiten ergeben bei identischen Straßendaten für unterschiedliche Fahrer-IDs dieselbe Grundzeit (die Schnittstelle nimmt keine Fahrer-ID entgegen; der Test ruft mit verschiedenen Fahrerkontexten im Aufrufer auf); für jede Strecke der Referenzmenge liegt eine Referenzfahrzeit vor, nie unterhalb der rechtmäßigen Mindestfahrzeit; jede Prognose trägt Modell- und Straßendatenversion.
- **Negativ:** Aktivierungsversuch von `eta_personalization_enabled` wirkt in P1 nicht, fahrerneutrale Referenz bleibt aktiv; Routingdienst nicht erreichbar ergibt definierten Fallbackgrund statt geschätztem Wert; Architekturtest verbietet Zugriff auf Fahrerprofile und eine Fahrer-ID im Schnittstellenvertrag; Änderung freigegebener Revisionsparameter wird abgewiesen.
- **Konkurrierend:** parallele Berechnungen derselben Strecke mit verschiedenen Fahrer-IDs liefern bitgleiche Ergebnisse; Wechsel der Straßendatenversion während eines Laufs wirkt erst für neue Aufrufe, jede Prognose trägt genau eine Version; Mandant A und B parallel ohne Vermischung der Revisionen (RLS).
- **Referenztests:** Grundlage für PH-AT-022 (Gate M8, hier nur Teilaspekt fahrerneutrale Referenz); WP nennt keine weiteren Katalog-IDs.

Testfälle: PH-AT-022; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/EtaReferenceTest.kt::grundzeit_unabhaengig_von_fahrer_id; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/EtaReferenceTest.kt::referenzzeit_nie_unter_rechtmaessiger_mindestfahrzeit; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/EtaReferenceTest.kt::prognose_traegt_modell_und_strassendatenversion; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/EtaReferenceTest.kt::personalisierung_in_p1_nicht_aktivierbar; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/EtaReferenceTest.kt::routingdienst_ausfall_liefert_fallbackgrund; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/EtaReferenceTest.kt::strassendatenwechsel_waehrend_lauf_genau_eine_version; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/EtaArchitectureTest.kt::grundmodell_hat_keinen_zugriff_auf_fahrerprofile_und_keine_fahrer_id; db/tests/test_eta_model_revision.py::test_freigegebene_revision_unveraenderlich_und_mandantengetrennt

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
