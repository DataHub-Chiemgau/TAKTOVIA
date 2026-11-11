# LH-6.17-INT-003 – API-Grundgerüst: Fehlermodell, Validierung, ETag, Listen, Tracing

- **Verantwortlicher:** Rolle Plattform (Modul `app`/`platform`)
- **Arbeitspaket:** WP-M1-02
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Schreibende APIs validieren Eingaben und liefern konsistente Fehlercodes mit maschinenlesbarem Typ.

**Originalabnahme (unverändert):** Feldfehler ist eindeutig ohne Auswertung freier Fehlermeldung erkennbar.

## Vertrag

- **Tabellen / DDL:** keine neuen; DB-Constraint-Fehler (z. B. SQLSTATE 23514 aus `reject_mutation()`) werden auf Problem-Codes abgebildet, DB-Meldungen gelangen nie roh zur API (Kap. 02 §4.2).
- **API:** globaler Fehlerabbildner auf Schema `Problem` (`api/common.yaml`): `type`, `title`, `status`, `code`, `detail`, `correlation_id`, `violations[pointer,code,message]`, Medientyp `application/problem+json`. Pflichtfelder laut `Problem` in `api/common.yaml`: `type` (URI), `title`, `status` (400-599), `code`, `correlation_id` (UUID); `detail` höchstens 2000 Zeichen; `violations[]` verlangt `pointer`, `code` und `message`, `additionalProperties:false`. Der maschinenlesbare Typ ist die Kombination aus stabilem `code` und `type`-URI je Fehlerart (Muster `https://api.taktovia.example/problems/<code-kebab>`, Beispiel im Schema); `code` und `type` ändern sich nie ohne neue Major-Version. Schreibende Requests werden gegen OpenAPI/JSON Schema validiert (`additionalProperties:false`); Feldfehler -> 422 mit `violations[].pointer` (JSON-Pointer) und `violations[].code`, so dass Feld und Fehlerart ohne Auswertung von `detail`/`message` erkennbar sind. Fehlercode-Katalog in den OpenAPI-Komponenten (`NOT_FOUND`, `REVISION_MISMATCH`, `PRECONDITION_REQUIRED`, `IDEMPOTENCY_CONFLICT`, `OPERATION_IN_PROGRESS` u. a., Kap. 05 §5.4; dort außerdem `OFFER_EXPIRED`, `HOLD_EXPIRED`, `REQUOTE_REQUIRED`, `CAPACITY_UNAVAILABLE`, `COOPERATION_NOT_ALLOWED`, `PROVIDER_UNAVAILABLE`, die erst mit M2 entstehen). Weitere Gerüstteile: ETag (`"7"`), fehlender `If-Match` 428 `PRECONDITION_REQUIRED`, veraltet 412 `REVISION_MISMATCH`; Listen `{items,next_cursor,as_of}`, `limit` 1-100 (Default 50), signierter Cursor, manipulierter Cursor 400, Sortierung `(created_at,id)`; `traceparent`, bei ungültigem Header neue `correlation_id`; 429 mit `Retry-After`, Quote je Mandant und Akteur; 404 für nicht sichtbare Ressourcen, 403 für fehlenden Funktionsscope; Geldtypen `Money`/`SignedMoney` (`amount_minor` Ganzzahl, `currency`, `exponent`, Exponent gegen den Währungskatalog (Tabelle `currency(code, exponent)`) geprüft, kein Float, Feldverletzung bei Verstoß; `Money` nichtnegativ, nur `SignedMoney` darf negativ sein; `Money` ist dafür von `federation.yaml` nach `common.yaml` zu verlagern, siehe LH-6.17-INT-001). Keine Stacktraces, keine fremden Objektbezeichnungen in Fehlern; keine Personen-/Ortssuchen als Metriklabels.
- **Zustandsautomat:** keine.
- **JSON-Schema / Ereignisse:** Schema `Problem` und Katalog der Feldverletzungscodes (Detailvertrag vorab).
- **Parameter und Kennzahlen:** keine; Rate-Limit-Quoten sind nicht beziffert.
- **Oberfläche:** keine.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-20 (Quoten, maximale Body-Größe, Verschachtelungstiefe nicht festgelegt; Planungsdefaults im Detailvertrag kennzeichnen, blockiert nicht); Cursor-Format und Signaturschlüssel als Detailvertrag vorab; vollständiger Fehlercode-Katalog mit HTTP-Status vorab.

## Tests

- **Positiv:** gültiger Request; Folgeseite per Cursor mit stabiler Reihenfolge; Feldfehler liefert 422 mit `violations[].pointer` und `violations[].code`, Client erkennt Feld und Fehlerart ohne `detail`/`message`.
- **Negativ:** unbekanntes Feld (`additionalProperties:false`), Typfehler, zu langer String, manipulierter Cursor (400), fehlender `If-Match` (428), veraltete Revision (412), Quotenüberschreitung (429 mit `Retry-After`, andere Mandanten/Akteure unbeeinflusst), ungültiger `traceparent` (neue `correlation_id`), Betrag als Gleitkommazahl, falscher `exponent`, Betrag außerhalb des sicheren Integerbereichs (±9.007.199.254.740.991), negativer Betrag in `Money`, unbekannte Währung; Cursor ohne gültige Signatur (400) und Cursor eines anderen Akteurs/Mandanten (kein Datenzugriff über fremden Cursor); `limit` 0 und 101; Schreibrequest ohne `Content-Type: application/json`; Request über OP-20-Planungsgrenze (Body-Größe/Verschachtelungstiefe) wird abgewiesen; jede Fehlerantwort validiert gegen `Problem` ohne Stacktrace/fremde Objektbezeichnung; rohe DB-Meldungen erscheinen nie (z. B. SQLSTATE 23514 aus `reject_mutation()` -> Problem-Code, kein Meldungstext der Datenbank); 403 für fehlenden Funktionsscope ohne Daten, 404 für nicht sichtbare Ressource identisch zu "nicht vorhanden"; `type` ist je Fehlercode eine stabile URI.
- **Konkurrierend:** zwei parallele Updates derselben Revision – genau eines gewinnt, das andere erhält 412; semantischer Contract-Fall "412-Konflikt ohne stilles Überschreiben" (Kap. 06 §6.9); Quotenzähler zweier Mandanten/Akteure unter paralleler Last bleiben getrennt (Tenant-/Akteurschlüssel im Rate-Limit-Speicher); die Verliererin des 412 hat keine Teilwirkung hinterlassen.
- **Referenztests:** keine Katalog-ID im WP genannt.
- Testfälle: geplant:backend/app/src/test/kotlin/de/taktovia/app/ProblemModelTest.kt::feldfehler_liefert_422_mit_pointer_und_code; geplant:backend/app/src/test/kotlin/de/taktovia/app/ProblemModelTest.kt::alle_fehlerantworten_validieren_gegen_problem_schema; geplant:backend/app/src/test/kotlin/de/taktovia/app/ProblemModelTest.kt::db_constraint_fehler_erscheinen_nie_roh; geplant:backend/app/src/test/kotlin/de/taktovia/app/EtagTest.kt::paralleles_update_gleiche_revision_genau_ein_412; geplant:backend/app/src/test/kotlin/de/taktovia/app/EtagTest.kt::fehlender_if_match_428_veraltet_412; geplant:backend/app/src/test/kotlin/de/taktovia/app/ListCursorTest.kt::stabile_reihenfolge_und_manipulierter_cursor_400; geplant:backend/app/src/test/kotlin/de/taktovia/app/RateLimitTest.kt::quote_429_retry_after_ohne_einfluss_auf_andere; geplant:backend/app/src/test/kotlin/de/taktovia/app/TraceparentTest.kt::ungueltiger_traceparent_neue_correlation_id; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/money/MoneyValidationTest.kt::gleitkomma_exponent_waehrung_negativ_und_integerbereich_werden_abgewiesen

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Sicherheit, Datentrennung, Geldtyp).
