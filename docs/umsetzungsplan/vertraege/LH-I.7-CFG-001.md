# LH-I.7-CFG-001 – Unternehmensprofil des Mandanten

- **Verantwortlicher:** Rolle Plattform (Module `config`/`identity`)
- **Arbeitspaket:** WP-M1-24
- **Status:** Bereit (unter Planungsannahme PA-11, siehe planungsannahmen.md; keine Entscheidung des Auftraggebers)
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Jeder Mandant verwaltet ein eigenständiges konfigurierbares Unternehmensprofil mit Kontakt, Abrechnung, Betriebsart und Rechtsinformationen.

**Originalabnahme (unverändert):** Ein Administrator ändert Kontakt und Standardwährung ohne Eingriff des Plattformbetreibers.

## Vertrag

- **Tabellen / DDL:** vorhanden: `tenant` (`id`, `name`, `status`), `legal_entity` (`name`, `country`, `tax_reference_token`), `currency` (`code`, `exponent`). Profilfelder (Kontakt, Abrechnung, Betriebsart, Rechtsinformationen, Standardwährung) haben im Pflichtenheft weder Tabelle noch Feldliste (OP-27). Zwei Varianten: (a) versionierte Konfiguration `config_revision` mit `scope_kind` Mandant, (b) eigene Profiltabelle mit Revision. Die WP-Vorgabe fordert Aggregat „Unternehmensprofil je Mandant mit Revision"; Standardwährung referenziert `currency.code` (Validierung gegen `currency`).
- **API:** Profil lesen/ändern (im Pflichtenheft kein Pfad in Kap. 05 §5.7); mit ETag/`If-Match` (veraltet: 412), 403 ohne Administratorrecht, 404 für fremden Mandanten, 422 bei Währung außerhalb `currency` mit Feldfehler (`application/problem+json`).
- **Zustandsautomat / JSON-Schema:** Feldliste als Schema mit dem Bau (Pflichtfelder offen, siehe OP-27). **Ereignisse:** `tenant.profile_changed`; Audit mit Akteur und Zeitpunkt über WP-M1-46.
- **Parameter und Kennzahlen:** keine. **Oberfläche:** UI-18 Basis, Formular Unternehmensprofil; Zustände fehlendes Recht, 412-Konflikt, Feldfehler Währung.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-27 – Datenmodell und Feldliste des Profils (Kontakt, Abrechnung, Betriebsart, Rechtsinformationen, Pflichtfelder), Ablage (Variante a/b) und Standardwährung sind nicht festgelegt. Das blockiert den Bau (Migration und Schema) bis zur Entscheidung; der Vertrag bleibt deshalb „in Arbeit". Abrechnung/Kontenrahmen gehören nach M4 (D-06).

- **Planungsannahme:** PA-11 in [planungsannahmen.md](../planungsannahmen.md) gilt als Arbeitsstand; offene Punkte oben bleiben bis zur Entscheidung des Auftraggebers bestehen.

- **Umsetzung (Stand WP-M1-24, Variante a der Ablage):** Profil als Konfigurationsscope `TENANT_PROFILE` in `config_revision` (`scope_id` = Mandant, Revision = ETag, frühere Revisionen bleiben, Veröffentlichung durch den Administrator selbst), Modul `config` (`ConfigApi.companyProfile`), Endpunkte `GET`/`PUT /v1/company-profile` (`company:read`/`company:write`; `If-Match` 0 legt das erste Profil an). Feldliste: [tenant-profile.v1.schema.json](schemas/tenant-profile.v1.schema.json) (Pflicht: `legal_name`, `contact_email`, `default_currency`; optional Anschrift, Telefon, Abrechnungsadresse und -E-Mail, `operating_mode` als Code ohne festen Katalog, `legal_notice`). Die Mandantenanlage (WP-M1-05) legt Revision 1 in derselben Transaktion an. Ereignis `tenant.profile_changed` (Schema `api/events/tenant.profilechanged.v1.schema.json`) nennt nur geänderte Feldnamen und die Währung; Audit `config.tenant_profile.changed` ohne Personendaten.

## Tests

- **Positiv:** Mandantenadministrator ändert Kontakt und Standardwährung ohne Plattformbetreiber-Rolle, Änderung nach Neuladen sichtbar (Abnahme); Audit enthält Akteur und Zeitpunkt.
- **Negativ:** Nicht-Administrator; Währung nicht im Katalog `currency` (Feldfehler); Profil eines fremden Mandanten (404); veralteter ETag (412).
- **Konkurrierend (Datentrennung):** zwei parallele Änderungen – eine erhält 412; Änderung in A lässt Profil von B unverändert.
- **Referenztests:** keine Katalog-ID im WP genannt.
- Testfälle: backend/modules/config/src/test/kotlin/de/taktovia/config/TenantProfileTest.kt::administrator_aendert_kontakt_und_waehrung_ohne_plattformrolle; backend/modules/config/src/test/kotlin/de/taktovia/config/TenantProfileTest.kt::unbekannte_waehrung_wird_abgewiesen; backend/modules/config/src/test/kotlin/de/taktovia/config/TenantProfileTest.kt::aenderung_in_a_laesst_profil_b_unveraendert_und_ist_auditiert; backend/modules/config/src/test/kotlin/de/taktovia/config/TenantProfileTest.kt::veraltete_revision_und_fehlendes_profil; backend/modules/config/src/test/kotlin/de/taktovia/config/TenantProfileTest.kt::paralleler_schreibzugriff_liefert_412; backend/app/src/test/kotlin/de/taktovia/app/identity/TenantProfileApiTest.kt::administrator_aendert_kontakt_und_waehrung_ohne_plattformrolle; backend/app/src/test/kotlin/de/taktovia/app/identity/TenantProfileApiTest.kt::ungueltige_waehrung_ergibt_feldfehler_und_aendert_nichts; backend/app/src/test/kotlin/de/taktovia/app/identity/TenantProfileApiTest.kt::nicht_administrator_403_und_fremdes_profil_404; backend/app/src/test/kotlin/de/taktovia/app/identity/TenantProfileApiTest.kt::aenderung_in_a_laesst_profil_b_unveraendert_und_etag_regeln; backend/app/src/test/kotlin/de/taktovia/app/identity/TenantProfileApiTest.kt::paralleler_schreibzugriff_ueber_http_liefert_genau_einen_erfolg_und_sonst_412; web/operator/src/identity/CompanyProfileForm.test.tsx::Speichern sendet die gelesene Revision und keinen Mandanten, danach wird neu geladen

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
