# Zustandsautomat Löschlauf (`erasure_job`) und Aufbewahrungsregel (`retention_rule`)

- **Arbeitspaket:** WP-M1-33 (`LH-6.18-SEC-007`); Code: `platform.retention.ErasureEngine`, `RetentionRules`; Datenbank: `erasure_job_guard`, `retention_rule_guard`, CHECK-Constraints (`V10330`).
- **Status:** Entwurf der Umsetzung. Zustandsnamen sind im Pflichtenheft nicht festgelegt (Vertrag SEC-007: „läuft/fortsetzbar/abgeschlossen/abgebrochen“); sie sind hier vorgeschlagen und vor Abnahme fachlich zu bestätigen.

## Löschlauf (`erasure_job.status`)

| Zustand | Bedeutung |
|---|---|
| `RESUMABLE` | angelegt oder unterbrochen; fortsetzbar. Die Fortsetzungsmarke `resume_after` und die Zähler zeigen den Stand. |
| `RUNNING` | ein Lauf hält die Lease (`lease_until`); ein anderer Lauf lässt den Job unberührt, bis die Lease abgelaufen ist (hart beendeter Worker). |
| `COMPLETED` | alle fälligen Objekte der Datenklasse bearbeitet (Endzustand). |
| `ABORTED` | nicht ausgeführt: Regel nicht (mehr) freigegeben (`RULE_NOT_APPROVED`), Regel seit dem Anlegen geändert (`RULE_CHANGED`), kein Ziel für die Datenklasse (`NO_TARGET`), Aktion vom Ziel nicht unterstützt (`ACTION_NOT_SUPPORTED`) (Endzustand). |

| von | erlaubte Folgezustände | Auslöser |
|---|---|---|
| `RESUMABLE` | `RUNNING`, `ABORTED` | Lauf nimmt den Job auf (Claim mit Zeilensperre); Regelprüfung schlägt fehl |
| `RUNNING` | `RESUMABLE`, `COMPLETED`, `ABORTED` | Unterbrechung (Ausnahme), keine weiteren fälligen Objekte, Regelprüfung |
| `COMPLETED`, `ABORTED` | – | unveränderlich (Trigger `erasure_job_guard`) |

Regelwerte (Regel, Revision, Aktion, Fristen) sind im Job festgehalten und unveränderlich; Zähler dürfen nicht sinken.

## Aufbewahrungsregel (`retention_rule.status`)

`DRAFT` → `APPROVED` (Freigabe durch **andere** Person als `changed_by`, CHECK `retention_rule_four_eyes`); jede inhaltliche Änderung (Frist, Aktion, Archivdauer) setzt auf `DRAFT` zurück (Trigger `retention_rule_guard` verbietet die Änderung in `APPROVED`). Je Mandant und Datenklasse höchstens eine `APPROVED`-Regel (Unique-Index).

## Je Objekt (ohne Doppelverarbeitung)

Ein Objekt wird je Aktion genau einmal protokolliert (`erasure_journal`, `UNIQUE (tenant_id, object_ref, action)`); parallele Läufe serialisieren je Objekt über einen Advisory-Lock und überspringen Objekte, die das Journal schon kennt. Fahrgastkonto: `ARCHIVE` (Archivzustand bis `archive_until`, für operative Abfragen unsichtbar) → nach Ablauf `ANONYMIZE` (Löschzustand `erased_at`, Tombstone ohne Personenangabe).
