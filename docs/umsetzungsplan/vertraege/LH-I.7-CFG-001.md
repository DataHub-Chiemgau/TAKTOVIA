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

## Tests

- **Positiv:** Mandantenadministrator ändert Kontakt und Standardwährung ohne Plattformbetreiber-Rolle, Änderung nach Neuladen sichtbar (Abnahme); Audit enthält Akteur und Zeitpunkt.
- **Negativ:** Nicht-Administrator; Währung nicht im Katalog `currency` (Feldfehler); Profil eines fremden Mandanten (404); veralteter ETag (412).
- **Konkurrierend (Datentrennung):** zwei parallele Änderungen – eine erhält 412; Änderung in A lässt Profil von B unverändert.
- **Referenztests:** keine Katalog-ID im WP genannt.
- Testfälle: geplant:backend/config/src/test/kotlin/de/taktovia/config/TenantProfileTest.kt::administrator_aendert_kontakt_und_waehrung_ohne_plattformrolle; geplant:backend/config/src/test/kotlin/de/taktovia/config/TenantProfileTest.kt::nicht_administrator_403_und_fremdes_profil_404; geplant:backend/config/src/test/kotlin/de/taktovia/config/TenantProfileTest.kt::aenderung_in_a_laesst_profil_b_unveraendert_und_ist_auditiert; geplant:backend/config/src/test/kotlin/de/taktovia/config/TenantProfileTest.kt::unbekannte_waehrung_wird_abgewiesen; geplant:backend/config/src/test/kotlin/de/taktovia/config/TenantProfileTest.kt::paralleler_schreibzugriff_liefert_412

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
