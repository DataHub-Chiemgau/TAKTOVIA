# LH-6.3-PAX-002 – Ortssuche mit fünf Eingabearten

- **Verantwortlicher:** Rolle Buchung/Disposition (BFF Ortssuche) und Passenger-Web
- **Arbeitspaket:** WP-M2-38
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Adresssuche akzeptiert Adresse, POI, Haltestellenname, Favorit und Kartenpunkt.

**Originalabnahme (unverändert):** Mindestens alle fünf Eingabearten liefern validierte Geokoordinaten.

## Vertrag

- **Tabellen / DDL:** keine neue Tabelle. Lesen aus `stop_revision` (`stop_id`, `point`, `name`; nur aktive, dienstzulässige Revisionen; Aktivstatus und Dienstzuordnung fehlen im Kern-DDL und kommen mit der Migration aus WP-M2-05, OP-07; eine Tabelle `stop` existiert nicht), `passenger_preference` (Favoriten; angelegt durch WP-M1-29, Erweiterung WP-M2-36), `service_area` (`area`, `valid_from`, `valid_to`; Bediengebietsprüfung zum Zeitpunkt). Suchtexte werden nicht persistiert.
- **API:** Ortssuch-Endpunkt (Pfad im Endpunktkatalog nicht benannt, Vorschlag in WP-M2-01, OP-33); `GET /v1/stops` (Haltestellensuche); Antwortschema Ortstreffer: Typ (`address|poi|stop|favorite|map_point`), Bezeichnung, Koordinate (WGS84), optional `stop_id`, Quelle/Datenstand. Validierung: Koordinate im Wertebereich, Zuordnung zum nächsten buchbaren Haltepunkt bzw. Hinweis „außerhalb Bediengebiet“; nie Koordinate 0/0. Rate-Limit; Eingaben nie als Markup interpretiert. Treffer sind als Start/Ziel in `POST /v1/ride-offers` akzeptiert. Kartenpunkt: Rückwärtsauflösung zu Koordinate (und ggf. nächstem Haltepunkt). Favorit: Koordinate aus `passenger_preference`; Favorit mit inzwischen inaktivem Haltepunkt bleibt als Ort nutzbar, wird aber nicht als buchbarer Haltepunkt vorgeschlagen (WP-M2-36 AK 5). Modulzuordnung der Ortssuch-Fachoperation (BFF) ist im WP nicht benannt; Annahme `service-geo`.
- **Zustandsautomat:** keiner. Geocoder-Schnittstelle (anbieterneutral, Arbeitsstand OSM) als Port; Ausfall des Geocoders lässt Haltestelle, Favorit und Kartenpunkt nutzbar.
- **JSON-Schema / Ereignisse:** Ortstreffer-Schema in WP-M2-01.
- **Parameter und Kennzahlen:** keine Katalogparameter; Rate-Limit-Wert offen. Suchtexte erscheinen nie als Metriklabels und nicht in Logs oberhalb der festgelegten Stufe (WP-M2-38 AK 5; die Stufe ist im WP nicht beziffert, Kap. 05 §5.1).
- **Oberfläche:** Eingabefeld mit Vorschlagsliste (Typ gekennzeichnet), Favoritenauswahl, „Punkt auf Karte wählen“ neben der Texteingabe, bestätigter Treffpunkt (Kap. 06 §6.2); verständliche Meldung bei nicht auflösbarem Text.
- **Entscheidungen:** D-09 (Karten-/Geocodingquelle und Lizenz; Bau mit OSM-Arbeitsstand, blockiert nur Produktivschaltung).
- **Offene Punkte:** OP-33 (Endpunkt und Datenquelle für Adresse/POI, Pfadvorschlag aus WP-M2-01 übernehmen); Regel für Mehrdeutigkeit und Rate-Limit-Wert vom Bau zu definieren (Detailvertrag-Anteil des WP), blockiert den Bau nicht.

## Tests

- **Positiv:** je ein Test für Adresse, POI, Haltestellenname, Favorit und Kartenpunkt liefert validierte Geokoordinaten, die in `POST /v1/ride-offers` akzeptiert werden.
- **Negativ:** leere/zu kurze Eingabe; nicht auflösbarer Text ergibt verständliche Meldung ohne Koordinate 0/0; Koordinate außerhalb des Wertebereichs wird abgewiesen; Treffer außerhalb Gebiet zeigt „außerhalb Bediengebiet“; Geocoder-Timeout (Haltestelle/Favorit/Kartenpunkt bleiben nutzbar); inaktive Haltestelle nicht in Vorschlägen; fremdes Konto sieht fremde Favoriten nie; Markup in Eingabe wird nicht interpretiert; Rate-Limit greift; Suchtext nicht in Metriklabels/Logs.
- **Konkurrierend:** parallele Suchanfragen desselben Fahrgasts liefern unabhängige, konsistente Ergebnisse; Favoritenänderung (ETag, 412 bei veraltetem Stand) parallel zur Suche; Deaktivierung einer Haltestelle parallel zur Suche: Vorschlag enthält die Haltestelle entweder als aktive Revision oder gar nicht.
- **Referenztests:** keine Katalog-ID im WP genannt.
- Testfälle: geplant:backend/modules/service-geo/src/test/kotlin/de/taktovia/servicegeo/PlaceSearchTest.kt::fuenf_eingabearten_liefern_validierte_koordinaten; geplant:backend/modules/service-geo/src/test/kotlin/de/taktovia/servicegeo/PlaceSearchTest.kt::geocoder_ausfall_laesst_haltestelle_favorit_kartenpunkt_nutzbar; geplant:backend/modules/service-geo/src/test/kotlin/de/taktovia/servicegeo/PlaceSearchTest.kt::unaufloesbarer_text_ergibt_keine_nullkoordinate; geplant:backend/modules/service-geo/src/test/kotlin/de/taktovia/servicegeo/PlaceSearchTest.kt::inaktive_haltestelle_nicht_in_vorschlaegen; geplant:backend/modules/service-geo/src/test/kotlin/de/taktovia/servicegeo/PlaceSearchTest.kt::fremde_favoriten_nie_sichtbar; geplant:backend/modules/service-geo/src/test/kotlin/de/taktovia/servicegeo/PlaceSearchTest.kt::markup_in_eingabe_wird_nicht_interpretiert; geplant:backend/modules/service-geo/src/test/kotlin/de/taktovia/servicegeo/PlaceSearchTest.kt::rate_limit_greift; geplant:backend/modules/service-geo/src/test/kotlin/de/taktovia/servicegeo/PlaceSearchTest.kt::suchtext_nicht_in_metriklabels_und_logs; geplant:backend/modules/service-geo/src/test/kotlin/de/taktovia/servicegeo/PlaceSearchTest.kt::treffer_ausserhalb_gebiet_wird_gekennzeichnet; geplant:backend/modules/service-geo/src/test/kotlin/de/taktovia/servicegeo/PlaceSearchTest.kt::treffer_wird_in_ride_offers_als_start_akzeptiert

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Datentrennung, Sicherheit).
