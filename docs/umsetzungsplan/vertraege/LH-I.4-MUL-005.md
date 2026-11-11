# LH-I.4-MUL-005 – Zeitlich begrenzte Fahrerfreigabe mit minimierter Projektion

- **Verantwortlicher:** Rolle Plattform (Module `staff-time` Stammteil und `identity`)
- **Arbeitspaket:** WP-M1-35
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Fahrer können zeitlich begrenzt für zulässige Dienste mehrerer Mandanten freigegeben werden.

**Originalabnahme (unverändert):** Fahrerdaten werden nur im notwendigen vertraglich erlaubten Umfang angezeigt.

## Vertrag

- **Tabellen / DDL:** `driver` und `qualification_revision` (Kap. 04 §4.4 DRV/FLT, Stammteil) beim Eigentümermandanten; neue Tabelle für Freigabe/Projektion (im Pflichtenheft als `ResourceAssignment` beschrieben, ohne Tabellennamen, OP-26), Vorschlag `driver_release`: `tenant_id` (Eigentümer), `id`, `driver_id`, `recipient_tenant_id`, `service_ids uuid[]`, `valid_from`, `valid_to`, `revoked_at`, `created_by`, `created_at`, `CHECK(valid_to>valid_from)`. Die Projektion wird beim Eigentümer erzeugt und unter Empfängerkontext gespeichert (Kap. 02 §4.2); Tabelle `driver_release_projection` unter `tenant_id` = Empfänger mit ausschließlich den Feldern Qualifikationen, zeitliche Einsatzberechtigung (Einsatzzeitraum), Kontaktkanal (Kap. 02 §2). Keine Spalte und kein View mit Personalakte, Arbeitszeiten, Abwesenheiten oder Gehalt im Empfängerschema; Runtime-Rolle des Empfängers hat keine Rechte auf Eigentümertabellen (RLS V1, Kap. 04 §4.5 „Grantwiderruf sofort wirksam"). Ablauf (`valid_to`) und Widerruf werden bei jedem Zugriff geprüft, nicht gecacht ohne Berechtigungsrevision (Cache-Invalidierung bei Widerruf).
- **API:** Freigabe erteilen/entziehen und Projektion lesen (Pfade im Pflichtenheft nicht festgelegt; Vorschlag `POST /v1/driver-releases`, `DELETE /v1/driver-releases/{id}` im Eigentümerkontext, `GET /v1/driver-releases/{id}/projection` im Empfängerkontext; mit `Idempotency-Key`, `If-Match`). Fehler: 404 außerhalb des Zeitraums, nach Widerruf, für nicht freigegebenen Dienst und fremden Mandanten (gleichartige Antwort); 403 ohne Recht; Projektion enthält nur die feste Feldliste (Schema, `additionalProperties: false`).
- **Zustandsautomat:** Freigabe: geplant (vor `valid_from`) → aktiv → abgelaufen | widerrufen (Zustand aus Zeit und `revoked_at` abgeleitet; Detail in `zustaende/driver-release.md` mit dem Bau).
- **JSON-Schema / Ereignisse:** Projektionsschema mit fester Feldliste (`schemas/driver-release-projection.v1.schema.json`); Ereignisse `driver.release_granted`, `driver.release_revoked` mit expliziter Empfängeradressierung; Audit über WP-M1-46.
- **Parameter und Kennzahlen:** keine. **Oberfläche:** UI-18 Basis, Freigabe mit Diensten, Frist und sichtbarer Feldliste; Zustände „Vertragsablauf", „Scopekonflikt", fehlendes Recht.
- **Entscheidungen:** D-10 (Fahrerdaten, Mitbestimmung) und D-01 – blockieren nur Produktivschaltung.
- **Offene Punkte:** OP-26: Tabellenname/Feldliste der Freigabe und Projektion; vertragliche Grundlage in P1 ohne `cooperation_grant`/`partner_contract_version` (M6) und ob die Freigabe im Einbetreiber-Pilot genutzt wird. Die feste Feldliste (Qualifikationen, zeitliche Einsatzberechtigung, Kontaktkanal) folgt dem WP und Kap. 02 §2; die konkrete Detailtiefe je Feld (z. B. welche Qualifikationsmerkmale) ist fachlich zu bestätigen. Vollnachweis mit WP-M3-05, WP-M6b-04, WP-M6b-05; Einsatzprüfung/gesetzliche Grenzen mit M3.

## Tests

- **Positiv:** Fahrer aus A wird für Dienst in B von T1 bis T2 freigegeben; B sieht im Zeitraum nur Qualifikationen, zeitliche Einsatzberechtigung und Kontaktkanal (Abnahme: nur notwendiger, vertraglich erlaubter Umfang); derselbe Fahrer gleichzeitig für Dienste zweier Empfängermandanten B und C freigegeben („mehrerer Mandanten"): jeder sieht nur die eigene Projektion; Nachweis in M1 an Fixture-Fahrern des Stammteils (WP-M1-35 AK 6).
- **Negativ:** Zugriff vor T1, nach T2, nach Widerruf; nicht freigegebener Dienst; nicht freigegebenes Feld (Personalakte, Arbeitszeit, Abwesenheit, Gehalt) per API und per SQL unter Runtime-Rolle von B; Freigabe/Änderung/Widerruf durch unberechtigte Rolle oder durch den Empfängermandanten (403, kein Audit-Eintrag als Erfolg); Pflichtprüfung und gesetzliche Einsatzgrenzen bleiben von der Freigabe unberührt (Nachweis mit M3); Empfängermandant nicht der benannte; Projektion mit zusätzlichem Feld wird vom Schema abgewiesen.
- **Konkurrierend (Datentrennung):** Widerruf parallel zu Lesezugriffen in B – nach Commit des Widerrufs kein Zugriff mehr; Ablauf `valid_to` parallel zu Lesezugriff.
- **Referenztests:** keine Katalog-ID im WP genannt; Baustein für PH-AT-016 erst mit M6.
- Testfälle: db/tests/test_driver_release.py::test_empfaenger_sieht_keine_personalakte; db/tests/test_driver_release.py::test_projektion_nicht_lesbar_ausserhalb_zeitraum_und_nach_widerruf; db/tests/test_driver_release.py::test_widerruf_parallel_zu_lesezugriffen_nach_commit_kein_zugriff; db/tests/test_driver_release.py::test_ablauf_parallel_zu_lesezugriff; backend/modules/identity/src/test/kotlin/de/taktovia/identity/DriverReleaseTest.kt::nicht_freigegebener_dienst_und_fremder_mandant_gleichartig_404; backend/modules/identity/src/test/kotlin/de/taktovia/identity/DriverReleaseTest.kt::freigabe_nur_fuer_berechtigte_rolle_des_eigentuemers; backend/modules/identity/src/test/kotlin/de/taktovia/identity/DriverReleaseTest.kt::projektion_mit_zusatzfeld_wird_vom_schema_abgewiesen; backend/modules/identity/src/test/kotlin/de/taktovia/identity/DriverReleaseTest.kt::projektion_nur_feste_feldliste_im_zeitraum; backend/modules/identity/src/test/kotlin/de/taktovia/identity/DriverReleaseTest.kt::widerruf_parallel_zu_lesezugriff; backend/app/src/test/kotlin/de/taktovia/app/identity/DriverReleaseApiTest.kt::erteilen_lesen_widerrufen_ueber_die_api; web/operator/src/personal/Fahrerfreigabe.test.tsx (UI-18 Basis)

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Datentrennung und Personendaten).
