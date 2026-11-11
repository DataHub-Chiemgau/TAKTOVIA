# Zahlungsdatentrennung: keine Kartendaten im System (WP-M1-37, LH-6.18-SEC-008)

**Status:** Nachweisdokument „PSP-konforme Trennung“ (Datenfluss, Systemgrenzen) zu [LH-6.18-SEC-008](LH-6.18-SEC-008.md). Erkennungsmuster und Maskierungsregel sind im Pflichtenheft nicht festgelegt und hier als Vorschlag mit dem Bau bestimmt. Die abschließende Bestätigung am echten PSP-Adapter erfolgt mit M2 (WP-M2-29); die Auswahl des PSP (D-03) blockiert nur die Produktivschaltung. Der PCI-Nachweis des PSP ist nicht Umfang.

## Datenfluss und Systemgrenze

```
Fahrgast-App/-Web ──(1) Karteneingabe──▶ PSP-gehostete Komponente (iframe/SDK des PSP)   ◀── Systemgrenze PCI: nur hier Kartendaten
        │                                         │
        │                                (2) PSP liefert Token/Referenz
        ◀─────────────────────────────────────────┘
        │ (3) nur der Referenzwert (payment_method_token)
        ▼
TAKTOVIA-API ──▶ payment_intent.provider_ref (einzige PSP-Referenz) ──▶ PSP-Adapter (M2) ──(4) Referenz + Betrag──▶ PSP
```

- **Eingabe:** Zahlungsdaten werden ausschließlich in PSP-gehosteten Komponenten erfasst (Vorgabe an das Designsystem, siehe unten). Weder Web noch App noch Backend sehen Kartennummer, CVC, Ablaufdatum oder Karteninhaber.
- **API:** „PSP-Token bleibt ein PSP-Referenzwert; rohe Zahlungsdaten erreichen diese API nie“ (Kap. 05 §5.3). Kein Schema und kein Parameter in `api/*.yaml` trägt ein Kartendatenfeld; `payment_method_token` ist ein Referenzwert.
- **Speicherung:** `payment_intent.provider_ref text NOT NULL` ist die einzige PSP-Referenz; es gibt keine Spalte für PAN, CVC, Ablaufdatum oder Karteninhaber. Die Referenz gilt als geschütztes Feld der Klasse **PAYMENT_TOKEN** im Feldschutzbaustein (Schutzklasse: wie sensible Felder; die Zuordnung zu den fünf Klassen aus Kap. 01 §1.7 bleibt offen). Lesbar nur für Rollen mit Zahlungsbezug (`finance`) und den Zahlungsadapter (Dienstkonto), jeweils mit Zweck `PAYMENT_PROCESSING` und Feldscope `payments:reference:read` ([feldschutz.md](feldschutz.md)).
- **Verarbeitung/Logs/Audit/Ereignisse:** Der Eingangsfilter weist kartennummernähnliche Eingaben ab, Log, Audit und Outbox maskieren oder weisen ab.

## Erkennungsmuster und Maskierungsregel (Vorschlag)

1. Kandidat: Ziffernfolge mit 13 bis 19 Ziffern, einzeln gegliedert durch Leerzeichen, Bindestrich oder Punkt; UUIDs werden vorher ausgeblendet.
2. Treffer nur mit **Luhn-Prüfsumme** und Länge/Präfix eines Kartennetzwerks (Visa, Mastercard inkl. 2-Serie, American Express, Discover, Diners, JCB, UnionPay, Maestro). Von längeren Ziffernfolgen zählt jedes passende Teilstück (Anhängen von Ziffern verschleiert nicht).
3. **Eingabe (Body, Query, Header, Schlüsselnamen, Zahlenwerte):** Abweisen mit 422 `VALIDATION_FAILED`, Pointer der Fundstelle (`/…`, `/$query/<name>`, `/$header/<name>`), nie der Wert. Der Vorfall wird asynchron als Logzeile und Sicherheitsereignis `security.input.card_data_rejected` (nur Fundstellen) gemeldet.
4. **Maskierung (Defensive in der Tiefe):** Logzeilen (`%safeMsg`/`%safeEx`, `logback-spring.xml`) ersetzen Treffer durch `[card-data-removed]`; `AuditRedactor` maskiert Texte, Zahlenwerte und Schlüssel (`[redacted]`); `Outbox.append` weist Payloads mit Treffer ab.
5. **Bewusst nicht erkannt:** Ablaufdatum oder CVC ohne Nummer (kein Treffer möglich; beide Felder sind in Schema und API verboten). Bekannte Grenze: Nummern in mehrere Felder zerlegt oder in anderer Kodierung (z. B. Wörter) erkennt kein Mustervergleich; die Systemgrenze (PSP-gehostete Eingabe) bleibt die eigentliche Absicherung.

## Nachweise

| Nachweis | Test |
|---|---|
| Schema-Lint: keine Kartendatenspalte (Migration mit Kartendatenspalte scheitert) | `db/tests/test_11_zahlungsdaten.py::test_keine_kartendatenspalte_im_schema` |
| OpenAPI-Lint: kein Schema/Parameter mit Kartendatenfeld (`api/tools/lint.py`, SEC-008) | `tests/acceptance/security/test_zahlungsdatentrennung.py::test_openapi_ohne_kartendatenfeld` |
| Freitext, Body, Query, Header abgewiesen/maskiert, auch in `audit_event.redacted_changes` | `CardDataFilterTest` (`kartennummer_in_freitext_wird_abgewiesen_oder_maskiert`, `kartennummer_in_body_query_header_wird_abgewiesen_oder_maskiert`) |
| PSP-Referenz nur mit Zahlungsbezug | `CardDataFilterTest::psp_referenz_nur_fuer_rolle_mit_zahlungsbezug` |
| 100 parallele Requests, kein Treffer in Logs/Audit (Bausteinebene gegen Datenbank, und 100 HTTP-Requests gegen die Anwendung) | `CardDataFilterTest::hundert_parallele_requests_ohne_treffer_in_logs_und_audit`, `CardDataIntegrationTest` |
| Datenflussdokument | dieses Dokument |

Offen für M2 (WP-M2-29): Bestätigung am echten PSP-Adapter und Webhook `/v1/payments/provider-events/{provider}` (Signatur, keine Kartendaten in Ereignissen des PSP).

## Vorgabe an das Designsystem

Zahlungsdateneingabe (Kartennummer, Ablaufdatum, Prüfziffer, Karteninhaber) erfolgt **ausschließlich in PSP-gehosteten Komponenten** (iframe/Hosted Fields des PSP). Das Designsystem (`web/design-system`) stellt dafür nur einen Rahmen mit Beschriftung, Zustand und Fehlertext bereit und enthält **keine** Eingabefelder für diese Daten; eigene `<input>`-Felder mit `autocomplete="cc-*"` sind verboten (Test in `web/design-system`: `psp-vorgabe.test.ts`). Die App erhält vom PSP nur den Referenzwert.
