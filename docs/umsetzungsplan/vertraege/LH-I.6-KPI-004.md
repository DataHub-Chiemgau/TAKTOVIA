# LH-I.6-KPI-004 – Ablehnungscodes für Gebiet, Zeit, Grenzen, Partner und Technik

- **Verantwortlicher:** Rolle Analytik (Modul `analytics`)
- **Arbeitspaket:** WP-M4-50
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Weitere Gründe umfassen Bediengebiet, Betriebszeit, Mindestvorlauf, Maximalwartezeit, Umweggrenze, Partnerausfall und technischer Fehler.

**Originalabnahme (unverändert):** Ein Testsatz aller Gründe ergibt keine undefinierten Fehlertexte.

## Vertrag

- **Tabellen / DDL:** `rejection_reason` (Code, Gruppe, Text je Sprache, gültig von/bis; Seed per Migration) mit den sieben Codes dieses Vertrags; `travel_attempt.primary_rejection` und `secondary_rejections` (V1) verweisen auf Codes, ein nicht katalogisierter Code wird beim Schreiben abgewiesen; Text ist NOT NULL, Deutsch Pflicht. Die Runtime-Rolle darf Seeds nicht ändern. Alle Faktentabellen im Modul `analytics` tragen `tenant_id` mit RLS wie in `db/migrations/V1__kernschema.sql`; kein Schreibzugriff auf Tabellen anderer Module (Architekturtest); keine Fahrgast-Klartextdaten in Fakten.
- **API:** `GET /v1/analytics/metrics` (Ablehnungen nach Grund mit Code und lokalisiertem Text; unbekannter Code als Filter liefert 400; WP-M4-46); `api/analytics.yaml` existiert noch nicht und ist mit dem Bau anzulegen.
- **Zustandsautomat:** keiner (Katalog).
- **JSON-Schema / Ereignisse:** Fehlerantworten im Format von `api/common.yaml`; keine neuen Ereignisschemas.
- **Parameter und Kennzahlen:** Codes (Arbeitsnamen, Abstimmung mit dem M2-Katalog WP-M2-13): `AREA_NOT_SERVED` (Bediengebiet), `OUTSIDE_OPERATING_HOURS` (Betriebszeit), `MIN_LEAD_NOT_MET` (Mindestvorlauf; Parameter `min_booking_lead_min`), `MAX_WAIT_EXCEEDED` (Maximalwartezeit; `max_wait_min`), `MAX_DETOUR_EXCEEDED` (Umweggrenze; `max_detour_pct`), `PARTNER_UNAVAILABLE` (Partnerausfall), `TECHNICAL_ERROR` (technischer Fehler). Kennzahlen: `Gebietsablehnungen`, `Zeitfensterablehnungen` (Trennung Betrieb geschlossen und Frist), `Technische Anfragefehler`; `Technische Partnerablehnungen` nur als Definition (keine Partner in P1).
- **Oberfläche:** Normtext des Grundes in der Ablehnungsstatistik (UI-19, Zuordnung offen, OP 23); ein Fahrgastdialog ist im WP-Dokument nicht genannt und nicht Teil dieses Vertrags; ein Code ohne Text darf nie erscheinen (CI-Vollständigkeitstest statt Laufzeit-Platzhalter).
- **Entscheidungen:** keine.
- **Offene Punkte:** Codeliste ist Arbeitsvorschlag und mit WP-M2-13 abzustimmen. OP 26: `PARTNER_UNAVAILABLE` kann ohne Partner (M6) nicht real auftreten, nur katalogisiert und per Testinjektion geprüft; blockiert den Bau nicht. OP 25: Rangfolge bei mehreren zutreffenden Gründen liegt bei KPI-002/003. Auslösung der Gründe (Wirkung der Parameter) liegt in M2 (WP-M2-12, WP-M2-18).

## Tests

- **Positiv:** Testsatz erzeugt je einen Versuch für jeden der sieben Gründe; jeder hat genau einen Code und einen nicht leeren deutschen Text; jeder Code ist in `/v1/analytics/metrics` einzeln auswertbar (WP-Kriterium 2).
- **Positiv (Kennzahlen):** `Zeitfensterablehnungen` weist `OUTSIDE_OPERATING_HOURS` (Betrieb geschlossen) und `MIN_LEAD_NOT_MET`/`MAX_WAIT_EXCEEDED` (Frist) getrennt aus; `Technische Anfragefehler` (`TECHNICAL_ERROR`) zählt nicht in den operativen Ablehnungen; `Technische Partnerablehnungen` liefert in P1 nur die Definition, keinen Wert.
- **Negativ:** Katalogeintrag ohne Text bricht den CI-Vollständigkeitstest; vom Buchungskern gelieferter unbekannter Grund lässt den Vertragstest scheitern; Schreiben eines nicht katalogisierten Codes wird abgewiesen; Filter mit unbekanntem Code liefert 400; ein Code ohne deutschen Text (NULL oder leer) wird von der Datenbank abgewiesen (NOT NULL/CHECK) und kann nicht per Runtime-Rolle angelegt oder geändert werden.
- **Konkurrierend:** Zwei parallele Versuche mit verschiedenen Gründen im selben Mandanten zählen je genau einmal; Versuch eines fremden Mandanten erscheint nicht in der Auswertung (Datentrennung).
- **Referenztests:** Keine Katalog-IDs im WP-Dokument genannt; Bezug PH-AT-021 nur mittelbar (Teil von WP-M4-48/49).
- Testfälle: db/tests/test_rejection_reason.py::test_alle_sieben_codes_haben_deutschen_text; backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/RejectionCatalogTest.kt::unbekannter_grund_scheitert_vertragstest; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/RejectionResourceCodesTest.kt::codeliste_entspricht_dem_katalog_der_datenbank_mit_gruppe_kennzahl_und_text; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/RejectionResourceCodesTest.kt::grenzverletzungen_des_kerns_haben_einen_ablehnungscode; backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/RejectionCatalogTest.kt::jeder_der_sieben_gruende_ist_einzeln_auswertbar

Umsetzung (wave13-c6, WP-M4-50): Die Arbeitsnamen entsprechen den Codes des M2-Katalogs (`AREA_NOT_SERVED` = `OUTSIDE_AREA`, `OUTSIDE_OPERATING_HOURS` = `OUTSIDE_HOURS`, `MIN_LEAD_NOT_MET` = `LEAD_TIME`, `TECHNICAL_ERROR` = `TECHNICAL`); neu sind `MAX_WAIT_EXCEEDED`, `MAX_DETOUR_EXCEEDED`, `PARTNER_UNAVAILABLE` (Kennzahl PARTNER, in P1 nur Definition). Die Auslösung von `MAX_WAIT_EXCEEDED`/`MAX_DETOUR_EXCEEDED` im Anfragepfad (Einfügeheuristik) und von `PARTNER_UNAVAILABLE` liegt in M2/M6; `RejectionCode.of(WindowViolationCode)` ordnet die Verletzungen des Kerns zu. Pfad der Tests: `backend/modules/analytics/` (nicht `backend/analytics/`).

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
