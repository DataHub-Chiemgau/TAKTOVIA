# LH-6.10-PAY-003 – Tarifrevision und Preisregeln mit Gültigkeit

- **Verantwortlicher:** Rolle Fachmodul `fare-payment`
- **Arbeitspaket:** WP-M2-07
- **Status:** Vertrag in Arbeit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Preisregeln besitzen Gültigkeitszeitraum, Dienst und Tarifklasse.

**Originalabnahme (unverändert):** Eine abgelaufene Regel wird nicht mehr angewendet.

## Vertrag

- **Tabellen / DDL:** neue Migration mit `tariff_revision` und `tariff_rule` (Kap. 04 §4.4 „Tarif-/Steuerstand“; im Kern-DDL nicht vorhanden): je `(tenant_id,id)` als PK, FK `(tenant_id,service_id)` auf `service`, Tarifklasse (Wertemenge OP-12), `valid_from`/`valid_to` (links geschlossen, rechts offen), Revisionszähler, Freigabe mit Ersteller und Prüfer (Vier-Augen, WP-M1-22), Steuerregelrevision je Regel, Geldwerte `bigint` minor mit Währung aus Katalog `currency`, RLS `tenant_isolation`. Invariante „nicht überlappende aktive Tarifversion“ je Dienst und Tarifklasse (Kap. 04 §4.5) per Exclusion Constraint oder Scope-Sperre. Veröffentlichte Revision unveränderlich (Reject-Trigger nach Muster `reject_mutation`); Korrektur nur als neue Revision. `fare_quote.tariff_revision_id` erhält FK auf `tariff_revision`.
- **API:** Tarifpflege-Endpunkte; im Endpunktkatalog nicht benannt, Pfadvorschlag in WP-M2-01 (OP-11). Anlegen, Prüfen, Veröffentlichen; ohne Gültigkeitszeitraum, Dienst oder Tarifklasse keine Freigabe (422). Überlappende aktive Revision → 409. Veraltete Version → 412 `REVISION_MISMATCH`, fehlendes `If-Match` → 428 `PRECONDITION_REQUIRED` (Kap. 05 §5.4). Fehler als `application/problem+json` mit `violations[].pointer`. Fremder Mandant → 404 `NOT_FOUND`, ohne Stammdatenrecht → 403, Änderungen auditiert, Veröffentlichung mit `If-Match` (412 bei veralteter Version).
- **Zustandsautomat:** Tarifrevision `ENTWURF → GEPRUEFT → VEROEFFENTLICHT` (Namen im Bau; Datei `vertraege/zustaende/tarifrevision.md` aus WP-M2-01); keine Änderung nach `VEROEFFENTLICHT`.
- **JSON-Schema / Ereignisse:** Outbox-Ereignis „Tarifrevision veröffentlicht“ (`api/events/`).
- **Parameter und Kennzahlen:** keine; Währungskatalog `currency` (Tabelle V1).
- **Oberfläche:** UI-17 „Gebiete/Stops/Tarife“, Aktion „Revision freigeben“: Tarifrevision anlegen, prüfen, freigeben; Zustand „konkurrierende Version“; ohne Recht nur lesend.
- **Entscheidungen:** D-05 (realer Pilottarif) blockiert nur Produktivdaten.
- **Offene Punkte:** OP-11 (Pfad der Tarifpflege; Vorschlag `/v1/tariffs` bleibt Entwurf), OP-12 (Preisstichtag: Anfrage- oder Fahrtzeitpunkt, und Tarifklassen in P1), Steuersätze vom Auftraggeber. **Blockiert den Bau:** OP-12 (Preisstichtag) muss in WP-M2-01 festgelegt werden, weil die Auswahl der wirksamen Revision davon abhängt; bis dahin bleibt der Vertrag in Arbeit.

## Tests

- **Positiv:** Regel innerhalb Gültigkeit (Zeitpunkt exakt `valid_from` zählt, exakt `valid_to` nicht), passender Dienst und passende Tarifklasse wird gefunden; Veröffentlichung einer vollständigen Revision erfolgreich.
- **Negativ:** abgelaufene Regel (ab `valid_to`) wird nicht angewendet, Anfrage erhält Preis der wirksamen Nachfolgeregel oder begründete Ablehnung „kein Tarif“; noch nicht gültige Regel; Regel eines fremden Dienstes; Regel ohne Gültigkeitszeitraum, Dienst oder Tarifklasse nicht freigebbar (API 422, Datenbank NOT NULL bzw. CHECK `valid_to > valid_from`); Ersteller prüft/veröffentlicht nicht selbst (Vier-Augen gemäß WP-M1-22); direktes UPDATE/DELETE einer veröffentlichten Revision unter der Runtime-Rolle schlägt in der Datenbank fehl; Update einer veröffentlichten Revision abgewiesen; überlappende aktive Revision je Dienst und Tarifklasse abgewiesen; fremder Mandant → 404 (RLS in der Datenbank); ohne Recht → 403; Änderung ohne Audit nicht möglich.
- **Konkurrierend (Geld):** zwei parallele Veröffentlichungen mit überlappendem Zeitraum für denselben Dienst und dieselbe Tarifklasse: genau eine erfolgreich; Veröffentlichung parallel zur Preisberechnung nutzt vollständig die alte oder die neue Revision.
- **Referenztests:** keine Katalog-ID im WP-Block genannt (E2E-098 betrifft Konfigurations-Rollback in WP-M1-/M2-09).
- Testfälle: geplant:db/tests/test_tarif_revision.py::test_ueberlappende_aktive_revision_abgewiesen; geplant:backend/modules/fare-payment/src/test/kotlin/de/taktovia/farepayment/TariffRevisionTest.kt::abgelaufene_regel_wird_nicht_angewendet; geplant:backend/modules/fare-payment/src/test/kotlin/de/taktovia/farepayment/TariffRevisionTest.kt::veroeffentlichte_revision_ist_unveraenderlich; geplant:backend/modules/fare-payment/src/test/kotlin/de/taktovia/farepayment/TariffRevisionTest.kt::parallele_veroeffentlichung_ueberlappend_genau_eine_erfolgreich; geplant:db/tests/test_tarif_revision.py::test_regel_ohne_zeitraum_dienst_tarifklasse_abgewiesen_und_veroeffentlichte_revision_unveraenderlich; geplant:db/tests/test_tarif_revision.py::test_parallele_ueberlappende_veroeffentlichung_genau_eine; geplant:backend/modules/fare-payment/src/test/kotlin/de/taktovia/farepayment/TariffRevisionTest.kt::regel_ohne_zeitraum_dienst_tarifklasse_ergibt_422; geplant:backend/modules/fare-payment/src/test/kotlin/de/taktovia/farepayment/TariffRevisionTest.kt::regel_innerhalb_gueltigkeit_wird_gefunden_noch_nicht_gueltige_nicht; geplant:backend/modules/fare-payment/src/test/kotlin/de/taktovia/farepayment/TariffRevisionTest.kt::fremder_mandant_404_ohne_recht_403_audit_vier_augen

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
