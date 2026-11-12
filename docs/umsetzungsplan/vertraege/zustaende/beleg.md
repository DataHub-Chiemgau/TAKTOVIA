# Zustandsautomat Beleg (`expense_document.state`, WP-M4-21 bis -24)

| Zustand | Bedeutung | Übergänge |
|---|---|---|
| `QUARANTINED` | Datei angenommen (Ticket verbraucht), Malware-Prüfung offen oder Prüfer nicht verfügbar; nicht ausgeliefert, nicht extrahiert, nicht bearbeitbar | `STORED`, `QUARANTINE_REJECTED` |
| `STORED` | Prüfung bestanden, Original schreibgeschützt abgelegt; **Entwurf** im Sinne der Fachprozesse: Felder bearbeitbar, OCR möglich | spätere Pakete (Freigabe, Buchung) setzen auf `STORED` auf |
| `QUARANTINE_REJECTED` | Schadsoftware erkannt; endgültig, nie freigegeben | keine |
| spätere Zustände (z. B. `APPROVED`, `POSTED`; WP-M4-34, -33) | freigegeben bzw. gebucht; Felder nicht mehr frei bearbeitbar (409 `IMMUTABLE_RECORD`) | Datenbank: kein Rückweg in die Bearbeitung |

Orthogonal dazu `integrity_state` (`UNCHECKED` → `VERIFIED` | `VIOLATED`; `VIOLATED` endgültig, sperrt Freigabe und Posting) und `sender_verified` (E-Mail-Eingang; `false` = nicht freigabefähig). Der Entwurfszustand beginnt mit `STORED`; ein eigener Zustand `DRAFT` wurde nicht eingeführt (Abweichung vom Vertragstext „beginnt als Entwurf“).
