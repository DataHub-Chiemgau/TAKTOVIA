# Zustandsautomat Konfigurationsrevision

- **Arbeitspaket:** WP-M1-22 (`LH-I.7-CFG-006`), Grundlage WP-M1-20 (`LH-I.7-CFG-003`); Code: `config.internal.JdbcConfigPublication` (Befehle), `platform.approval.FourEyes` (Vier-Augen-Baustein); Datenbankregeln: `state_transition_rule` (Automat `config_revision`, Version 1, `V1200`), Wächter `config_revision_guard` (`V1200`, `V10220`).
- **Zustandsspalte:** `config_revision.state` (CHECK und Übergangsregeln, `harden_state_column`).
- **Status:** Festlegung für den Bau (OP-19); die Fachfreigabe der Regeln steht aus (Review Fachanalyse).

## Zustände

| Zustand | Bedeutung |
|---|---|
| `DRAFT` (Entwurf) | änderbar (`PATCH`), nicht wirksam |
| `VALIDATED` (geprüft) | gegen Schema-Version, Systemgrenzen, Sicherheitsinvarianten und Modellregeln der Module geprüft, durch `validate` |
| `APPROVED` (freigegeben) | durch eine **zweite** Person freigegeben (`approve`), Prüfer ≠ Ersteller, nur Menschen |
| `PUBLISHED` (publiziert) | wirksam ab `effective_from`; unveränderlich bis auf das Schließen des Intervalls (`effective_to`) |
| abgelöst | **nicht gespeichert**, sondern abgeleitet: `PUBLISHED` mit gesetztem und erreichtem `effective_to` (API zeigt `SUPERSEDED`). Eine publizierte Zeile ändert sich nie bis auf `effective_to`. |

## Übergänge

| von | nach | Auslöser | Voraussetzungen | Fehler |
|---|---|---|---|---|
| `DRAFT` | `VALIDATED` | `POST .../validate` (`config:write`) | alle Werte gültig gegen die jetzt gültige Schema-Version; Modellregeln (`RevisionValidator`) ohne Verstoß | bei Verstoß bleibt der Entwurf `DRAFT`, Antwort 200 mit `valid=false` und allen Feldverletzungen, Audit trotzdem |
| `VALIDATED` | `APPROVED` | `POST .../approve` (`config:publish`) | Freigeber ≠ Ersteller, Freigeber ist ein Mensch | 403 `FORBIDDEN` (Selbstfreigabe, Dienstkonto, fehlendes Recht); Datenbank: CHECK `approved_by <> created_by` |
| `APPROVED` | `PUBLISHED` | `POST .../publish` (`config:publish`) | erneute Prüfung ohne Verstoß (422), Wirkungsbericht zum unveränderten Stand (422 fehlt/unbekannt, 409 geändert), `effective_from` = freigegebener Beginn und nicht rückwirkend (422), keine Überlappung (409) | 409 `INVALID_STATE_TRANSITION` ohne Validierung/Freigabe, 412/428 bei `If-Match` |
| `VALIDATED`/`APPROVED` | `DRAFT` | (Datenbank erlaubt es; kein API-Weg in M1) | – | – |

Alle Befehle: `If-Match` = Zeilenversion (428 fehlt, 412 veraltet), `Idempotency-Key` (gleicher Schlüssel, gleicher Inhalt: dasselbe Ergebnis ohne zweite Wirkung), Mandant nur aus dem Kontext (fremde Revision: 404).

## Entscheidungen (OP-19, für den Bau)

1. **`effective_from` in der Vergangenheit:** unzulässig. Eine Publikation darf nur ab jetzt wirken (Toleranz 60 s gegen Uhrabweichung und Laufzeit, ein Beginn „jetzt“ aus dem Client ist zulässig); rückwirkende Änderungen bestätigter Zusagen sind ausgeschlossen (Kap. 05 §5.5). Der Beginn der Anfrage muss dem freigegebenen Beginn der Revision gleichen; eine Datumsänderung ist eine Änderung des Entwurfs und braucht neue Prüfung und Freigabe.
2. **Überlappungsverbot (§4.5):** Je Scope höchstens eine publizierte Revision zu jedem Zeitpunkt. Intervalle sind halboffen `[effective_from, effective_to)`. Die Publikation sperrt den Scope (`pg_advisory_xact_lock` auf Mandant, Art, Kennung), prüft das Intervall und **schließt eine früher beginnende, offene Vorgängerrevision** auf den Beginn der neuen. Überlappt die neue Revision eine später beginnende oder eine früher beginnende mit festem Ende, ist die Publikation 409 `CONFLICT`. Zweite Absicherung: Exclusion Constraint `config_revision_published_no_overlap` (`btree_gist`, nur `state = 'PUBLISHED'`, ohne `TENANT_PROFILE`), Verstoß wird als 409 gemeldet. Zwei parallele Publikationen mit überlappendem Intervall: genau eine gelingt.
3. **Unveränderlichkeit und Vorgänger schließen (Widerspruch im WP):** Der Wächter lässt an einer publizierten Zeile genau eine Änderung zu: `effective_to` setzen oder vorziehen, nur auf den Beginn einer freigegebenen oder publizierten Nachfolgerevision desselben Scopes; alle übrigen Spalten bleiben fest, auch für die Administration. Jede Änderung erhöht `row_version`. Der Zustand „abgelöst“ wird deshalb nicht gespeichert.
4. **Rollback als neue Revision:** `POST .../rollback` (`config:publish`) auf die fehlerhafte **publizierte** Revision legt eine neue Revision (Entwurf, Nummer + 1) mit den Werten der Vorgängerrevision bzw. der gewählten früheren Revision desselben Scopes an (`rolled_back_from`). Sie geht denselben Weg (validieren, freigeben, publizieren): die Vier-Augen-Regel ist nicht umgehbar. Die fehlerhafte Revision bleibt unverändert lesbar (nur das Intervall wird beim Publizieren der Rücknahme geschlossen); bestehende Zusagen mit gebundener Revisionsreferenz behalten ihre Ursprungsversion. Audit: `config.revision.reverted` (Rücknahme angelegt) und `config.revision.published` (Rücknahme veröffentlicht); Ereignisse `config.revision_published` und zusätzlich `config.revision_rolled_back`.
5. **Prüfregeln der Module:** `RevisionValidator` (Port in `config.api`) läuft bei `validate` und noch einmal bei `publish` (fail-closed: fällt eine Regel aus, 503). Erste Regel: Modellregeln der Dienstkonfiguration (WP-M1-25).

## Ereignisse und Audit

- Outbox (je (Aggregat, Zeilenversion, Typ) höchstens eines): `config.revision_published` ([Schema](../../../../api/events/config.revision_published.v1.schema.json)), `config.revision_rolled_back` ([Schema](../../../../api/events/config.revision_rolled_back.v1.schema.json)), davor `config.revision_created`.
- Audit je Schritt mit Akteur, Zeitpunkt, Grund: `config.revision.validated` (auch mit `valid=false`), `config.revision.approved`, `config.revision.published` (`audit_event_id` in der Antwort), `config.revision.reverted`.
