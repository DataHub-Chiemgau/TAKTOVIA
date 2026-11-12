# Zustandsautomat Beleg (`expense_document.state`, WP-M4-21 bis -24)

| Zustand | Bedeutung | Übergänge |
|---|---|---|
| `QUARANTINED` | Datei angenommen (Ticket verbraucht), Malware-Prüfung offen oder Prüfer nicht verfügbar; nicht ausgeliefert, nicht extrahiert, nicht bearbeitbar | `STORED`, `QUARANTINE_REJECTED` |
| `STORED` | Prüfung bestanden, Original schreibgeschützt abgelegt; **Entwurf** im Sinne der Fachprozesse: Felder bearbeitbar, OCR möglich | spätere Pakete (Freigabe, Buchung) setzen auf `STORED` auf |
| `QUARANTINE_REJECTED` | Schadsoftware erkannt; endgültig, nie freigegeben | keine |
| `IN_REVIEW` | Erfassung abgeschlossen (Schritt `CAPTURE`), wartet auf die fachliche Prüfung; nicht mehr bearbeitbar | `REVIEWED`, `STORED` (Ablehnung) |
| `REVIEWED` | fachliche Prüfung bestanden (Schritt `REVIEW`), wartet auf die Budgetfreigabe | `WAITING_SECOND_APPROVAL`, `APPROVED`/`POSTED`, `STORED` (Ablehnung) |
| `WAITING_SECOND_APPROVAL` | Budgetfreigabe (Schritt `BUDGET`) erteilt, Betrag über der Vier-Augen-Schwelle: eine zweite Person muss freigeben | `POSTED` (Schritt `SECOND_APPROVAL`), `STORED` (Ablehnung) |
| `APPROVED` | endgültig freigegeben (`approved_by` gesetzt); in der Freigabetransaktion nur kurz, danach `POSTED` (bis dahin setzen Tests und Administration den Zustand direkt) | `POSTED` |
| `POSTED` | gebucht: Ereignis, Journal und Outbox sind atomar entstanden (WP-M4-33); Felder nicht mehr bearbeitbar (409 `IMMUTABLE_RECORD`) | keine; Exportstatus (`export_state`) nur hier setzbar |

Orthogonal dazu `integrity_state` (`UNCHECKED` → `VERIFIED` | `VIOLATED`; `VIOLATED` endgültig, sperrt Freigabe und Posting) und `sender_verified` (E-Mail-Eingang; `false` = nicht freigabefähig). Der Entwurfszustand beginnt mit `STORED`; ein eigener Zustand `DRAFT` wurde nicht eingeführt (Abweichung vom Vertragstext „beginnt als Entwurf“).

**Freigabe-Workflow (WP-M4-34, WP-M4-30 bis -32).** Jede Ablehnung (fachliche Prüfung, Budgetfreigabe, zweite Freigabe) führt mit Begründung nach `STORED` zurück, ohne Kostenereignis und ohne Journal; die Schritte stehen unveränderlich in `approval_step`. Die Freigabe bindet sich an die exakte Dokumentrevision (412). Rollentrennung: der Erfasser gibt nie selbst frei, die zweite Freigabe erteilt nie der Erstfreigeber (Anwendung und Datenbank). Blockaden vor der Freigabe, je mit eigenem Code: Integrität 409, Dublette 409 (`DUPLICATE_REVIEW_REQUIRED`, `DUPLICATE_EVIDENCE_LINKED`), Pflichtfelder 422 `UNCONFIRMED_FIELDS`, Kostenzuordnung 422 `COST_ASSIGNMENT_REQUIRED`, Tankwerte 422 `FUEL_FIELDS_MISSING`, Aufteilung 422 `SPLIT_INCOMPLETE` (WP-M4-35: Beleg mit mehreren Kostenobjekten ohne vollständigen, centgenauen Plan), Plausibilitätshinweis 422 `HINT_NOT_ACKNOWLEDGED`. Orthogonal: `export_state` (`NOT_EXPORTED` -> `EXPORTED` = bereitgestellt -> `CONFIRMED` = Import bestätigt, nur für `POSTED`, nur im Exportjob und in der Bestätigung per transaktionslokalem Schalter `app.export_job`, WP-M4-37).
