# Zustandsautomat Tarifrevision (`tariff_revision.state`)

- **Arbeitspaket:** WP-M2-01 (Vertrag), Umsetzung WP-M2-07; Datenbank: `tariff_revision.state CHECK IN ('ENTWURF','GEPRUEFT','VEROEFFENTLICHT')` (Migration V2070), Übergänge und Unveränderlichkeit im Trigger `tariff_revision_guard`.
- **Quelle:** Kap. 04 §4.4 (Tarif-/Steuerstand), §4.5 (nicht überlappende aktive Tarifversion), Detailvertrag [LH-6.10-PAY-003](../LH-6.10-PAY-003.md), Planungsannahme PA-05.
- **Regeln:** Vier-Augen: Prüfer und Veröffentlichender sind nie der Ersteller (403, zusätzlich CHECK in der Datenbank); ein Dienstkonto kann nie die zweite Person sein. Eine Revision ohne Regel oder mit mehr als einem Regeltyp ist nicht prüfbar. Die Veröffentlichung scheitert mit 409, wenn der Gültigkeitszeitraum `[valid_from, valid_to)` mit einer veröffentlichten Revision desselben Dienstes und derselben Tarifklasse überlappt (Exclusion Constraint); bei zwei parallelen Veröffentlichungen gewinnt genau eine. Nach `VEROEFFENTLICHT` ist die Revision samt Regeln vollständig unveränderlich; Korrektur nur als neue Revision (Nummer + 1). Ein Entwurf ist änderbar (If-Match), eine geprüfte Revision nicht mehr.

Zustände: `ENTWURF`, `GEPRUEFT`, `VEROEFFENTLICHT`
Anfangszustand: `ENTWURF`
Endzustände: `VEROEFFENTLICHT`

| von | nach | Auslöser | Akteur | Fehler bei unzulässigem Übergang |
|---|---|---|---|---|
| `ENTWURF` | `GEPRUEFT` | `POST /v1/tariffs/{id}/review` mit `If-Match` | zweite Person (nicht der Ersteller) | 403 (Ersteller, Dienstkonto), 409 `INVALID_STATE_TRANSITION` (nicht im Entwurf), 412/428 (Revision) |
| `GEPRUEFT` | `VEROEFFENTLICHT` | `POST /v1/tariffs/{id}/publish` mit `If-Match` | zweite Person (nicht der Ersteller) | 403, 409 `INVALID_STATE_TRANSITION` (nicht geprüft), 409 `CONFLICT` (überlappende veröffentlichte Revision), 412/428 |
