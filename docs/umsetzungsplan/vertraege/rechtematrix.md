# Rollenmodell und Rechtematrix (WP-M1-07, LH-6.2-IAM-004)

**Status: Entwurf, fachlich nicht freigegeben (OP-10).** Das Pflichtenheft enthält keine Matrix Rolle x Tätigkeit und keine Werteliste für `membership.role_code`. Die hier festgelegten Rollencodes und Zellen sind ein Vorschlag des Plans für den Bau; sie sind durch die Fachanalyse zu prüfen und freizugeben, bevor Produktivdaten entstehen. Die Mechanik (Matrix als einzige Rechtequelle, generierter Test, Build-Sperre, Protokoll des Ausrollens) ist davon unabhängig und fertig.

## Rollenkatalog

Codes (`membership.role_code`, DB-Tabelle `role_catalog`, Kotlin `RoleCatalog`, OpenAPI-Enum in `MembershipCreate.role_code`; ein Test hält alle vier synchron):

| Code | Rolle | Art |
|---|---|---|
| `passenger` | Fahrgast | Pflichtrolle (IAM-004) |
| `driver` | Fahrer | Pflichtrolle |
| `callcenter` | Callcenter | Pflichtrolle |
| `support` | Support | Pflichtrolle |
| `dispatcher` | Disponent | Pflichtrolle |
| `planner` | Verkehrsplaner | Pflichtrolle |
| `tenant_admin` | Administrator | Pflichtrolle |
| `finance` | Finanzen | Platzhalter (Kap. 06, M3/M4) |
| `staff_planning` | Personalplanung | Platzhalter |
| `workshop` | Werkstatt/Freigabe | Platzhalter |

Die DB erzwingt die Werteliste per CHECK `membership_role_code_catalog` (kein Fremdschlüssel: die Konvention „Fremdschlüssel beginnen mit `tenant_id`“ gilt auch für globale Ziele außer `tenant` und `currency`). Neue Rollen entstehen durch Vorwärtsmigration von Katalog und CHECK zugleich. Die Rolle einer Mitgliedschaft ist unveränderlich (Entzug und neue Mitgliedschaft statt Umhängen).

## Rechtematrix (`backend/modules/identity/src/main/resources/rechtematrix/rechtematrix.csv`)

Eine Zeile je `operationId` der OpenAPI-Verträge (`api/*.yaml`, derzeit 51), Spalten `operation_id;scope;datenklasse;meilenstein;<ein Rollencode je Spalte>`, Zellen `J` (erlaubt) / `N` (verweigert). Die Matrix ist die **einzige Quelle** der Rechte:

- **Funktionsscopes einer Rolle** = Scopes aller für sie erlaubten Tätigkeiten (`RightsMatrix.scopesFor`); `/v1/me` liefert sie als `scopes`. Alle Tätigkeiten mit gleichem Scope haben gleiche Rollenzellen (Strukturprüfung), damit Scope und Tätigkeit nie auseinanderlaufen.
- **Zentrale Autorisierung** (`Authorizer`, Modul `identity`; Aufruf im `RequestContextFilter`): Menschen dürfen eine Tätigkeit, wenn sie in der Matrix steht, ihr Scope im Vertrag dem der Matrix entspricht und mindestens eine Rolle des Kontexts (Vereinigung der aktiven Mitgliedschaften des Mandanten) `J` hat. Dienstkonten: Scope-Schnitt aus Token und Adapter-Obergrenze (WP-M1-04). Sonst 403 ohne Details; Grund (`not_in_matrix`, `role_not_allowed`, `scope_missing`, …) nur im Sicherheitsereignis `security.access.denied`.
- **Operationen ohne Menschen-/Dienstscope** (Partnersignatur, `receivePartnerEvent`) haben Zeilen mit `scope = -` und allen Zellen `N`: bis M6 ist jede Anmeldung verweigert.
- **Datenklassen** `PII`, `HR`, `FINANZ`: der Administrator (`tenant_admin`) ist dort nie erlaubt (Kap. 01 §1.7, IAM-004 AK 3); eine gesonderte Zuweisung erfolgt durch eine zusätzliche Mitgliedschaft in der Fachrolle (`callcenter`/`support`/`dispatcher` für Fahrgastdaten, `finance`, `staff_planning`); die Rollen vereinigen sich je Mandant. Die Strukturprüfung bricht, wenn eine solche Zeile dem Administrator `J` gibt. Nur Zeilen mit Datenklasse sind so geschützt; Feldrechte innerhalb einer Operation folgen mit WP-M1-31.
- **Vorläufige Zuordnung der Fachfunktionen aus M2–M4** (Zeilen mit `meilenstein` M2 bis M6) steht bereits in der Matrix, damit eine neu gebaute Route nicht ohne Eintrag scheitert; die fachliche Bestätigung und Feinschnitt liefern die jeweiligen Meilensteine.

## Änderung und Ausrollen (AK 4)

- **Versioniert im Repository:** `# matrix_version: N` in der Matrixdatei und `rechtematrix.lock` (`version`, `sha256` der Datei). `RechtematrixTest.matrixaenderung_nachvollziehbar_und_auditiert` scheitert, wenn Matrix und Sperrdatei auseinanderlaufen: jede Änderung zeigt sich im Pull Request als Diff der Matrix **und** der Sperrdatei (Version erhöhen, `sha256` erneuern; der Test nennt den neuen Wert).
- **Auditiert beim Ausrollen:** `RightsMatrixRolloutRecorder` schreibt beim Start der Anwendung genau dann eine Zeile in die globale, nur anhängbare Tabelle `rights_matrix_rollout` (Version, `matrix_sha256`, `previous_sha256`, Anzahl Rollen/Tätigkeiten, Zeitpunkt, `service:<anwendung>`), wenn die mitgelieferte Matrix von der zuletzt protokollierten abweicht; mehrere Instanzen serialisiert eine Advisory-Sperre. Die Tabelle ist für alle Rollen unveränderlich (Trigger). Ist die Matrix strukturell ungültig oder das Protokoll nicht schreibbar, startet die Anwendung nicht.

## Build-Sperre (AK 2)

`RechtematrixApiTest.route_ohne_matrixeintrag_scheitert` gleicht alle Operationen der Verträge (nicht nur die gebauten) mit der Matrix ab (`RightsMatrix.verifyContract`): Operation ohne Zeile, Zeile ohne Operation und abweichender Scope lassen den Build scheitern; zusätzlich steht jede tatsächlich registrierte Route in der Matrix. Zur Laufzeit gilt deny-by-default.

## Generierter Matrixlauf (AK 1)

`RechtematrixApiTest.matrix_alle_rollen_erlaubt_und_verweigert` erzeugt aus der Matrixdatei je Rolle (10) und je Tätigkeit (51) einen echten Aufruf gegen die laufende Anwendung mit einer Mitgliedschaft in dieser Rolle (510 Fälle): erlaubt = nie 401/403, bei den gebauten Operationen (`getMe`, `switchTenant`, `listMemberships`, `createMembership`, `revokeMembership`) mit gültigen Eingaben der erwartete Erfolg (200/201/204); verweigert = 403 ohne Details. Für noch nicht gebaute Tätigkeiten (Routen folgen mit M1–M4) belegt der Lauf die **Autorisierungsentscheidung**, nicht die Fachfunktion; sobald die Route existiert, deckt derselbe Lauf sie ab.

## Operator-Web

Die Navigation leitet ihre Bereiche aus `scopes` von `/v1/me` ab (`SCOPE_RIGHTS` in `web/api-client`), nicht aus Rollennamen; der Test `rollennavigation.test.tsx` liest dieselbe Matrixdatei und prüft je Rolle, dass genau die erlaubten Bereiche sichtbar sind. Anzeige ist keine Rechtequelle (Kap. 06 §6.1); ein Direktaufruf zeigt den Rechtezustand, der Server verweigert unabhängig davon.

## Offen

OP-10 (fachliche Freigabe von Rollencodes und Zellen), OP-36 (Mandantenanlage und erste Administrator-Mitgliedschaft), Anmeldung von Fahrgästen vor der ersten Mitgliedschaft (`registerPassenger`, WP-M1-28: die Zeile erlaubt `passenger` und `callcenter`), Break-glass-Zugriff (MUL-018, M6), Feldrechte (WP-M1-31).
