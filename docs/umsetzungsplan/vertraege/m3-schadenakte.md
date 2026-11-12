# Detailvertrag Schadenakte (WP-M3-43, -44, -47, -48)

Ergänzt [m3-detailvertraege.md](m3-detailvertraege.md) und die Verträge `LH-6.22-DMG-001/-002/-004/-007/-008`. Alle Festlegungen, die das Pflichtenheft nicht trifft, sind **Annahmen dieses Plans**
(Vertragsprozess: [vertragsprozess.md](../vertragsprozess.md)); kein menschlicher Review ist erfolgt (D-12). Verantwortlicher: Rolle Flotte/Sicherheit, Modul `fleet-safety`.

## 1. Fallnummer (DMG-001/-002, OP-22 unberührt)

- Format `SCH-<Jahr>-<laufende Nummer, mindestens sechsstellig>`, z. B. `SCH-2026-000123` (passt zum Ereignisschema `defect.reported`).
- Vergabe je Mandant und Kalenderjahr (UTC) aus `defect_case_counter` durch `taktovia.next_defect_case_number()` **in der Transaktion der Anlage**: Zeilensperre des Zählers serialisiert
  parallele Meldungen desselben Mandanten, ein Rollback nimmt die Vergabe zurück. Ergebnis: je Mandant und Jahr **lückenlos und doppelfrei** (beantwortet die Frage „Dublettenfreiheit oder
  Lückenfreiheit“ mit dem strengeren Fall; die Gegenprobe sind `db/tests/test_defect_case.py::test_parallele_vergabe_lueckenlos_und_eindeutig` und `::test_rollback_hinterlaesst_keine_luecke`).
  Der Zähler steigt per Trigger nur um 1; `UNIQUE (tenant_id, case_number)` bleibt die zweite Schicht.
- Die Nummer enthält keine Personendaten. Gleichlautende Nummern verschiedener Mandanten verraten den Mandanten nicht: der Zugriff läuft über die mandantengebundene Akten-ID (fremd: 404).

## 2. Kataloge (Planungsannahmen, nicht freigegeben; Erweiterung additiv)

| Feld | Werte |
|---|---|
| `category_code` (Kategorie) | `BODY`, `GLASS`, `TIRES_WHEELS`, `LIGHTS`, `BRAKES`, `DRIVETRAIN`, `STEERING_SUSPENSION`, `ELECTRICAL_HV`, `INTERIOR`, `ACCESSIBILITY_EQUIPMENT`, `SAFETY_EQUIPMENT`, `CLEANLINESS`, `OTHER` |
| `kind` (Art) | `SCRATCH`, `DENT`, `CRACK_BREAK`, `MALFUNCTION`, `MISSING_PART`, `LEAK`, `WEAR`, `CONTAMINATION`, `OTHER` |
| `vehicle_area` (Fahrzeugbereich) | `FRONT`, `REAR`, `LEFT_SIDE`, `RIGHT_SIDE`, `ROOF`, `UNDERBODY`, `WHEELS_TIRES`, `INTERIOR_PASSENGER`, `INTERIOR_DRIVER`, `LUGGAGE`, `WHEELCHAIR_AREA`, `ENGINE_BATTERY`, `OTHER` |
| `observed_effect` (beobachtete Auswirkung) | `NONE_VISIBLE`, `COSMETIC_ONLY`, `FUNCTION_LIMITED`, `NOT_ROADWORTHY`, `FLUID_LEAK`, `WARNING_LIGHT`, `NOISE_VIBRATION`, `OTHER` |
| `location_kind` (Ereignisort, OP-22) | `COORDINATE`, `ADDRESS_TEXT`, `FREE_TEXT`, `DEPOT` mit `location_value` (höchstens 200 Zeichen); beide oder keines. Der Ort ist keine Pflichtangabe der Meldung (das WP nennt als Pflichtdaten Fahrzeug, Kategorie, Beschreibung, Ereigniszeit). |
| Freitext | `description` 1 bis 2000 Zeichen; keine Unfall-/Gesundheitsangaben (OP-23). Alle Auswahlfelder sind Codes. |

## 3. Meldung (WP-M3-43)

- `POST /v1/defects`: Pflicht `vehicle_id`, `occurred_at` (mit UTC-Offset, nicht später als jetzt + 60 s: Toleranz der Gerätezeit; Datenbanktrigger spiegelt die Regel), `category_code`, `description`.
  Mandant, Meldender, Meldezeitpunkt, Fallnummer und Status `NEW` setzt der Server; solche Felder im Body sind unbekannte Felder (422 `UNKNOWN_FIELD`), ein **fremder** `tenant_id` ist 404 mit Sicherheitsereignis (Plattformregel).
- Idempotenz: `Idempotency-Key` (Filter) und `device_event_id` (Modul, `UNIQUE (tenant_id, device_event_id)`): Wiederholung liefert dieselbe Akte (200), auch bei paralleler Wiederholung; dieselbe ID von anderem Meldenden/Fahrzeug: 409.
- Berechtigung: Rollen `driver`, `dispatcher`, `workshop` (Rechtematrix; zweite Schicht im Modul). **Fahrerbezug** (Kriterium 7, Annahme, OP-34): ein Konto, das nur als Fahrer auftritt, meldet für ein Fahrzeug, für das es eine
  Schicht hat, die läuft, in höchstens 2 h beginnt oder vor höchstens 24 h endete (`staff_shift.driver_ref` = IdP-Subject, nur lesend); sonst 404. Die Meldung verlangt weder Buchung noch Prüflauf. Ein Fahrer sieht nur eigene Akten (DMG-011).
- Ereignis `defect.reported` (Schema `api/events/defect.reported.v1.schema.json`), Audit `fleet.defect.reported` ohne Beschreibungstext.

## 4. Fotoevidenz (WP-M3-44, DMG-004, OP-13, OP-31)

- **Ablauf** (die Datei geht nie durch die JSON-API, Grenze 1 MiB): (1) `POST /v1/defect-evidence-uploads` stellt ein Ticket aus (MIME `image/jpeg|png`, Größe höchstens 10 MiB, SHA-256 als base64url, Aufnahmezeit);
  (2) der Client lädt direkt in die Quarantäne unter `upload_key` (vorsignierter Upload: Objektspeicher-Adapter S3/MinIO **nicht ausgeführt**, WP-M0-08; in den Tests schreibt der „Client“ in den prozesslokalen Objektspeicher);
  (3) `POST …/complete` mit `If-Match` prüft Größe, Hash und tatsächlichen Typ am gespeicherten Objekt (abweichend: 422 mit Feldverweis, Ticket `REJECTED`, nie an die Akte gehängt) und führt die Medienprüfung aus;
  (4) mit `POST /v1/defects` (`evidence_ids`) werden Tickets des Meldenden an die Akte gebunden.
- **Medienprüfung** (außerhalb jeder SQL-Transaktion): Prüfer aus WP-M4-21 (ClamAV `clamd`, in der Entwicklung nur EICAR; ohne Prüfer bleibt jedes Foto in Quarantäne, fail-closed), dazu Typ- und Bildprüfung und **Neu-Kodierung ohne Metadaten**
  (EXIF inklusive Standort entfällt, OP-31; die Aufnahmezeit steht neben dem Bild, nicht eingebrannt). Gespeichert wird die bereinigte Fassung; `defect_evidence.sha256` ist ihr Hash, `original_sha256` der vom Gerät deklarierte.
  Nicht verfügbarer Prüfer: Foto bleibt `IN_REVIEW` und wird mit `reviewPending(tenant)` erneut geprüft (Prüfauftrag je Mandant; **Zeitplan und Mandantenaufzählung des Workers nicht ausgeführt**).
- **Sichtbarkeit:** erst mit `RELEASED` entsteht die unveränderliche Zeile `defect_evidence` (Objekt unter `tenants/<mandant>/defect-evidence-<id>`); bis dahin zeigt die Akte `evidence_pending` („in Prüfung“), kein Link möglich.
- **Abruf:** `GET /v1/defects/{id}/evidence/{eid}/download` liefert einen signierten Link (HMAC-SHA256 über Mandant, Objekt, Zweck, Ablauf; **höchstens 60 s**, Kap. 02 §4.3) nach Objekt- und Zweckprüfung; `GET /v1/objects/{tenant}/{name}` prüft Signatur,
  Ablauf und Mandantenbezug (fremd oder abgelaufen: 404). Schlüssel `taktovia.fleet.evidence-link-secret` (Pflicht außerhalb von `local`/`development`).
- **Integrität:** `verifyIntegrity(tenant)` vergleicht jedes freigegebene Objekt mit dem gespeicherten Hash; Abweichung oder fehlendes Objekt ergibt das Sicherheitsereignis `security.defect_evidence.integrity_violation` (Periodik: Betriebsauftrag, nicht ausgeführt).
- **Wiederholung:** dieselbe Datei desselben Urhebers liefert dasselbe Ticket (`UNIQUE` über Urheber und Hash, solange nicht abgewiesen); eine Datei ist je Akte höchstens einmal Evidenz (`UNIQUE (tenant_id, defect_id, sha256)`).
- **Geführte Erfassung (UI-10, `capture_mode=GUIDED`):** Pflicht sind `kind`, `vehicle_area`, `observed_effect` und mindestens ein Eintrag in `evidence_ids` (fehlt eines: 422 mit Feldverweis). Musterfall: **höchstens 5 Schritte** (Art, Bereich, Auswirkung, Foto, Senden; Vorschlag des Vertrags,
  nicht aus dem Pflichtenheft). Freitext höchstens 300 Zeichen in der App (Server: 2000), keine Namen oder Gesundheitsangaben. Anzeigen „Entwurf“, „synchronisiert“, „angenommen“ getrennt, mit Symbol und Text (nicht nur Farbe).
  Die Flutter-Umsetzung (`mobile/driver/lib/schaden/`, Tests unter `mobile/driver/test/`) ist **nicht ausgeführt** (kein Flutter).
- **Offen:** Aufbewahrung der Schadensbilder (D-11) blockiert nur die Produktivschaltung; echte Malware-Prüfung gegen ClamAV und der S3-Direktupload sind nicht ausgeführt.

## 5. Statusautomat (WP-M3-47, DMG-007)

Quelle der Übergangstabelle ist [zustaende/defect_case.md](zustaende/defect_case.md) (unverändert); hier stehen die Festlegungen, die sie offen lässt.

- **Ausführung:** `POST /v1/defects/{id}/transitions` mit `If-Match` (428 fehlt, 412 veraltet) und Body `{to, reason?, reopen?}`; der Zielzustand ist das Ereignis, `NEW` ist kein Ziel. Reihenfolge der Prüfung: Rolle überhaupt (403), Akte im Mandanten (404), Revision (412),
  Tabelle (409), Rolle für diesen Übergang (403), Begründung (422), Vorbedingung (409). Zustand, Revision, `dismissed_reason`, `reopen_count`, Statushistorie, Audit `fleet.defect.state_changed` und Ereignis `defect.state_changed` entstehen in **einer** Transaktion unter Zeilensperre
  (`FOR UPDATE`): bei zwei gleichzeitigen Übergängen derselben Revision gewinnt genau einer, der andere erhält 412. Die Datenbank erzwingt die Tabelle zusätzlich per Trigger (`V30470`), Revisionszählung und den sicherheitskritischen Abschluss.
- **Rollen** (Arbeitsnamen, OP-34): Disposition `dispatcher` und Werkstatt `workshop` für `NEW -> REVIEWED` und `REVIEWED -> IN_PROGRESS`; nur Werkstatt für Verwerfen, Abschluss ohne Reparatur, `IN_PROGRESS -> REPAIR` und zurück; Werkstatt und Betriebsleitung `tenant_admin` für `REPAIR -> CLOSED`.
- **Vorbedingungen:** `DISMISSED` nur mit Begründung; Abschluss ohne Reparatur nur klassifiziert und nicht `SAFETY_CRITICAL` (aus `REVIEWED` nur `COSMETIC`/`OTHER`); `REPAIR` nur mit nicht storniertem Reparaturauftrag (WP-M3-51 legt ihn an); `REPAIR -> CLOSED` nur mit Reparaturnachweis (`PROOF_RECORDED`) und, bei
  `SAFETY_CRITICAL`, mit Freigabeentscheidung `RELEASED`; `REPAIR -> IN_PROGRESS` nur, wenn alle Aufträge storniert sind.
- **Wiedereröffnung** (Annahme; die Tabelle nannte sie nur als Negativbeispiel): ein Endzustand (`CLOSED`, `DISMISSED`) führt nur mit `reopen=true`, Begründung, Rolle Werkstatt oder Betriebsleitung nach `REVIEWED` zurück; `reopen_count` steigt um 1, die Historie kennzeichnet den Eintrag (`reopening`).
  Ohne dieses Kennzeichen ist jeder Übergang aus einem Endzustand 409.
- **Sperre:** das Statusmodul schreibt weder `vehicle`, `vehicle_block` noch `resource_release`; `CLOSED` und `DISMISSED` heben eine bestehende Sperre nie auf (Test mit aktiver Sperre). Entsperrung ausschließlich über WP-M3-51.
- **Historie:** `defect_status_history` (append-only) enthält schon die Anlage (`from_status` = null); `GET /v1/defects/{id}/status-history` für Disposition, Werkstatt und Betriebsleitung (Scope `defects:state`, neu), Fahrer: 403.
- **Nicht im Umfang:** Fristüberwachung je Status (M7). Der `versionierte CHECK` des WP-Textes ist die Spalte `status` mit CHECK (V3020) plus Trigger; weitere Zustände kämen additiv mit einer neuen Migration.

## 6. Unveränderliche Historie (WP-M3-48, DMG-008, D-11)

- **DDL:** `defect_case_revision` (`tenant_id`, `id`, `defect_id`, `version` fortlaufend ab 1, `snapshot` jsonb = voller Inhalt dieser Fassung, `author`, `created_at`, `change_reason` NOT NULL, `UNIQUE (tenant_id, defect_id, version)`), append-only per Trigger
  (UPDATE/DELETE scheitern auch für die Administration, SQLSTATE 23514) und Rechte (die Runtime-Rolle darf nur anhängen und lesen). `defect_evidence` ist unveränderlich (V3020), neu `replaces_evidence_id` (Folgeversion verweist auf den Vorgänger, ein Anhang wird höchstens einmal ersetzt) und
  `defect_version`. `defect_case` trägt die **aktuelle** Fassung; der Trigger `defect_case_content_guard` lässt eine Inhaltsänderung nur zu, wenn `content_version` um 1 steigt **und** die Revisionszeile dieser Version existiert; Identität (Fallnummer, Fahrzeug, Meldender, Meldezeit) ist unveränderlich,
  die Akte nicht per DELETE löschbar. Löschfristen: nur über den kontrollierten Lifecycleprozess (WP-M1-33, Fristen D-11 offen), der dafür eine eigene, protokollierte Ausnahme braucht; sie ist **nicht** Teil dieses WP.
- **Abgrenzung zu `audit_event`:** die fachliche Historie (`defect_case_revision`, `defect_status_history`, Evidenz) trägt die **Inhalte** und ist für Prüfer lesbar; `audit_event` (append-only, WP-M1-12) trägt **wer wann was getan hat** (`fleet.defect.amended` mit Version und geänderten Feldnamen,
  `fleet.defect.history_read` bei jedem Lesen der Historie) und nie Beschreibungs- oder Begründungstext. „Vorherige Inhalte bleiben im Audit lesbar“ heißt hier: der Audit-Eintrag verweist auf die Version, deren Inhalt die Historie unverändert vorhält.
- **Nachtrag:** `POST /v1/defects/{id}/amendments` (Patch-Semantik auf dem Stand unter Zeilensperre; **ohne** `If-Match`, weil gleichzeitige Nachträge nicht abgewiesen, sondern fortlaufend versioniert werden: Ausnahme `amendDefect` in `api/tools/lint.py`). `change_reason` ist Pflicht (422), ein Nachtrag ohne jede Änderung ebenso;
  geänderte Felder: `occurred_at`, Ort (nur als Paar), `category_code`, `kind`, `vehicle_area`, `observed_effect`, `description`; Anhänge: `add_evidence_ids` und `replace_evidence` (der Ersatz muss freigegeben sein, der Vorgänger bleibt in der Historie und per Link nur für Prüfer abrufbar).
  Berechtigung: Fahrer, Disposition, Werkstatt (Fahrer nur an eigenen Akten), Audit `fleet.defect.amended`, Ereignis `defect.amended` (Schema `api/events/defect.amended.v1.schema.json`).
- **Lesen:** `GET /v1/defects/{id}/history` (alle Fassungen mit Anhängen) und `GET /v1/defects/{id}/as-of?at=<Zeit>` (Fassung, damals sichtbare Anhänge, Status zum Zeitpunkt) nur für **Prüfer** (Annahme, OP-34: Werkstatt und Betriebsleitung; Scope `defects:history`, neu), sonst 403, fremd 404.
- **Nicht im Umfang:** Export der Akte (AT-53, M5).
