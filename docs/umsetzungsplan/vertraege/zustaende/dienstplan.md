# Zustandsautomat Dienstplan (`roster_revision.state`) und Schichten im Dienstplan

- **Arbeitspakete:** WP-M4-04 (Entwurf getrennt vom veröffentlichten Plan), WP-M4-05 (offene Schichten), WP-M4-07 (Konfliktprüfung), WP-M4-16 (Depotberechtigung); Veröffentlichung selbst ist WP-M4-14; Verträge `LH-6.23-SCH-004`, `-008`, `-006`, `-009`.
- **Modul / Tabellen:** `staff-time`, `roster_revision` (V40040), `staff_shift` (V40030/V40040), `roster_depot_grant` (V40160). Planungsannahme (Vorschlag des Plans, WP-M4-01 offen): **ein Dienstplan ist der Plan eines Depots für eine Kalenderwoche** (`week_start` = Montag, lokales Datum); `roster_id` bleibt über alle Revisionen gleich.

## Revisionen

| Zustand | Bedeutung | Sichtbar für |
|---|---|---|
| `DRAFT` | nicht veröffentlichter Entwurf; je Dienstplan höchstens einer; `entity_revision` (ETag des Dienstplans) steigt mit jeder Änderung seiner Schichten | nur Planer mit `rosters:read` **und** Depotrecht (zusätzlich RLS: restriktive Policy, Schalter `app.roster_draft_access`) |
| `PUBLISHED` | veröffentlicht, verbindlich, unveränderlich; je Dienstplan höchstens eine | Planer (beide Sichten); verbindliche Sicht (`view=published`) |
| `SUPERSEDED` | durch eine neuere veröffentlichte Revision ersetzt, unveränderlich | Planer (Historie) |

| Von | Nach | Auslöser | Wer | Prüfungen und Fehlerfälle |
|---|---|---|---|---|
| (neu) | `DRAFT` (Revision 1) | `POST /v1/rosters` | Planer (`rosters:write`, Depotrecht) | Depot im Mandanten, sonst 404; `week_start` Montag, sonst 422; Depot und Woche einmalig, sonst 409 |
| `PUBLISHED` | neuer `DRAFT` (Revision n+1) | `POST /v1/rosters/{id}/draft` (`If-Match` = Stand des Dienstplans) | Planer | Kopie der veröffentlichten Schichten mit neuen Kennungen; schon ein Entwurf oder keine veröffentlichte Revision: 409; veraltet 412, fehlend 428 |
| `DRAFT` | `PUBLISHED` | Veröffentlichung (WP-M4-14) | Rolle mit `rosters:publish` | `Rosters.requirePublishable` (Konfliktprüfung unter Sperre, 409 mit Konfliktcodes); Datenbank: Revisionsnummern lückenlos, Zustandswächter `roster_revision_guard`, Schichten werden `PUBLISHED` und unterliegen dann dem Überlappungsverbot |
| `PUBLISHED` | `SUPERSEDED` | Veröffentlichung der Folgerevision (WP-M4-14) | – | – |

Veröffentlichte und ersetzte Revisionen sind unveränderlich (Trigger). Entwurfsänderungen erzeugen **keine** Outbox-Ereignisse (Fahrer-App und Disposition erfahren nur von Veröffentlichungen); `staff_shift`-Zeilen im Zustand `DRAFT` sind für operative Rollen auch per Direktabfrage unsichtbar.

## Sichtbarkeitsmatrix Rolle × Zustand

| Rolle | Entwurf | veröffentlicht |
|---|---|---|
| Planer, Personalrolle (Scope `rosters:read`, Depotrecht) | ja | ja |
| Fahrer, Disposition, übrige Rollen (kein `rosters:*`-Scope) | 403 (kein Scope) | über die Fahrer-/Dispositionsschnittstellen (WP-M4-17), nicht über `/v1/rosters` |
| Planer ohne Depotrecht, anderer Mandant | 404 | 404 |

## Schichten im Dienstplan

- Eine Schicht gehört zum Entwurf (`staff_shift.roster_revision` = Entwurfsnummer), Zustand `DRAFT`; ihr Depot ist das Depot des Dienstplans (sonst 422), ihr Betriebstag liegt in seiner Woche (sonst 422). Ohne Entwurf: 409.
- **Offen** (WP-M4-05): Schicht ohne zugeordnete Person (`driver_ref IS NULL`); eine offene Schicht trägt kein Fahrzeug (CHECK `staff_shift_open_without_vehicle`, 422 in der Anwendung). **Unbesetzter Bedarf** = `staffing_need` minus zugeordnete Personen (jede Zeile trägt höchstens eine Person), je Tag, Depot (Dienstplan) und Funktion; Zähler `open_shifts` und `unstaffed_need` je Revision im Dienstplan-Lesemodell, Filter `open=true` an der Schichtliste. Die Zuweisung ist eine Änderung der Schicht (`If-Match`): zwei gleichzeitige Zuweisungen derselben offenen Schicht ergeben genau eine Besetzung (die zweite: 412).
- **Überlappungsverbot** der Schichtzuweisung (V3070) gilt nur für **verbindliche** Schichten (`PLANNED`, `STARTED`, `ENDED`, `PUBLISHED`); Entwürfe und `SUPERSEDED` blockieren nichts und werden nicht geprüft (OP-38), damit Konflikte im Entwurf speicherbar bleiben. Beim Übergang nach `PUBLISHED` prüft die Datenbank wie bei einer Neuanlage (zweite Linie hinter der Konfliktprüfung).

## Konfliktprüfung (WP-M4-07)

| Konfliktart | Stabiler Code (`ViolationCode`) | Meldung nennt |
|---|---|---|
| Doppelbelegung derselben Person | `ROSTER_DOUBLE_BOOKING` | beide Schicht-IDs (Entwurf gegen Entwurf und Entwurf gegen verbindliche Schichten anderer Dienstpläne/M3) |
| fehlende oder zum Schichtzeitpunkt nicht gültige Qualifikation | `ROSTER_QUALIFICATION_MISSING` | Schicht-ID und fehlende Qualifikationsart; die Qualifikation muss zum Beginn freigegeben und gültig sein und darf nicht vor dem Schichtende ablaufen |
| Schicht im Zeitraum einer genehmigten Abwesenheit | `ROSTER_ABSENCE_OVERLAP` | Schicht-ID, nie Art oder Kommentar der Abwesenheit; zurückgenommene Abwesenheit zählt nicht |

Überlappung: `[Beginn, Ende)`, angrenzende Schichten sind kein Konflikt. Die Person einer Schicht ist `driver_ref` (Fahrerstamm `driver.id`; im Altbestand das Konto-Subject); die Abwesenheit wird über das Subject der Mitgliedschaft und über `driver_ref` selbst gefunden. Prüfzeitpunkte: beim Speichern im Entwurf als **Hinweis** (`conflicts` an der Schicht, der Entwurf bleibt gespeichert); im Lesemodell `GET /v1/rosters/{id}` als Konfliktliste des Entwurfs; bei der Veröffentlichung als **Sperre** (`Rosters.requirePublishable`, 409 `CONFLICT` mit `violations[]`: Pointer `/shifts/<id>`, Code, Ursache). Die Publikationsprüfung nimmt eine transaktionsweite Absperrung je Mandant (Advisory-Lock, exklusiv); die Abwesenheitserfassung nimmt sie geteilt: eine gleichzeitige Genehmigung wartet bis zum Ende der Publikationstransaktion, die Prüfung wertet den committeten Stand. Die Unterscheidung Sperre/Hinweis/Empfehlung (SCH-035), Ruhezeit und Pausen (WP-M4-13) und Fahrzeugkonflikte (SCH-038) sind nicht Teil.

## Depotberechtigung (WP-M4-16)

- **Zweite Schicht** hinter RLS (Mandantentrennung bleibt ausnahmslos Sache der Datenbank): `roster_depot_grant` (Subject, `DEPOT` mit Depot oder `ALL_DEPOTS`, vergeben von/am, `revoked_at`). Ohne wirksame Vergabe ist der Depotscope **leer: nichts lesbar, nichts änderbar (fail-closed, 404)**.
- **Ausdrückliches depotübergreifendes Recht** = Vergabe `ALL_DEPOTS` (eigene Vergabe, nicht aus Rolle oder Funktionsscope ableitbar); jede Dienstplan-Schreiboperation, deren Recht allein daraus folgt, schreibt `staff.roster.cross_depot_edit` ins `audit_event`.
- Ein nicht sichtbarer Dienstplan (fremdes Depot, fremder Mandant) ist 404, ein fehlender Funktionsscope 403; Listenfilter schränken den Depotscope nur ein; `tenant_id` im Payload wird nie übernommen (Schema `additionalProperties: false`, Mandant nur aus dem Kontext).
- **Rechteentzug**: schreibende Operationen lesen die Vergabe in der Fachtransaktion mit `FOR SHARE`; ein Entzug, der vor dem Schreibbefehl committet, wird gesehen (404), ein gleichzeitiger Entzug wartet bis zum Ende der Transaktion; die Veröffentlichungsprüfung nimmt dieselbe Sperre.
- Mandantenübergreifende Bearbeitung von Dienstplänen gibt es in P1 nicht (kein Pfad).
- **Nicht gebaut:** Verwaltungsschnittstelle (HTTP/UI) für die Vergabe von Depotrechten; vorhanden ist der Port `StafftimeApi.rosterDepotAccess` (nur Rolle mit `organization:write`, auditiert). Das Verfahren der Vergabe ist fachlich offen (analog OP-04 aus WP-M1-08).
