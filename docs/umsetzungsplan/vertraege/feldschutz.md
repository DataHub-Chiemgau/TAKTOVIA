# Feldschutz für Mobilitäts- und Unterstützungsmerkmale (WP-M1-31, LH-6.18-SEC-005)

**Status:** Bau-Detailvertrag zu [LH-6.18-SEC-005](LH-6.18-SEC-005.md). Fachlich nicht freigegeben: Rollen×Feld-Matrix (OP-10), Katalog der transportrelevanten Unterstützung (OP-24), Zweck/Rechtsgrundlage/Löschfrist (D-11, blockiert nur die Produktivschaltung). Ohne ausdrückliches Feldrecht gilt **fail-closed**.

## Bausteine

| Ebene | Umsetzung |
|---|---|
| Datenbank | `passenger_private.support_ciphertext` / `contact_ciphertext` (bytea, Ruhezustand verschlüsselt). `taktovia_runtime` hat **Spaltenrechte ohne** diese Spalten (`R__runtime_grants_m1_31.sql`); `SELECT *`, Filter auf die Spalte und jedes Schreiben scheitern mit `permission denied`. Nur der Modulzugang `taktovia_fields` (eigene Anmeldung, kein Superuser, kein `BYPASSRLS`, ohne Zugriff auf andere Tabellen) liest/ändert sie; RLS (FORCE, `tenant_id`) gilt auch für ihn. Spaltenmatrix: `db/role_matrix_spalten.d/m1-31.csv`, geprüft von `db/tests/test_08_feldschutz.py`. Schema-Lint: keine sensible Spalte (Namensmuster support_need, diagnos, disabilit, health …) außerhalb von `passenger_private`. |
| Verschlüsselung | `platform.fieldprotection.EnvelopeCipher`: je Wert zufälliger DEK (AES-256-GCM), umhüllt mit dem KEK aus der Secret-Verwaltung (`secret://<domäne>/field_kek_<kennung>`, Base64 von 32 Byte, `SecretKekProvider`). AAD = `<tenant>|<tabelle>.<spalte>|<zeile>`: ein kopierter Wert lässt sich in keiner anderen Zeile, Spalte oder keinem anderen Mandanten entschlüsseln. Blob: `0x01 | len(keyId) | keyId | wrapNonce | wrappedDek | nonce | ciphertext+tag`. |
| Rotation | Neue Schlüsselkennung (z. B. `v2`) wird für neue Werte aktiv, ältere Kennungen bleiben zum Lesen konfiguriert; `EnvelopeCipher.rewrap` stellt Werte ohne Klartext-Zwischenschritt auf den aktuellen KEK um. Eine Kennung ohne konfigurierten KEK führt zu `FieldDecryptionException`, nie zu Klartext. |
| Zugriff | `FieldAccess` (einziger Lesepfad): Entscheidung aus Matrix, Feldscope und Zweck; Audit `passenger.support_needs.read` mit Akteur und Zweck (ohne Wert) in der Fachtransaktion (`ContextTransactions`, Mitgliedschaft `FOR SHARE`), danach Lesen über die Verbindung des Modulzugangs. Verweigerung: `security.access.denied` mit Feld und Grund. |
| Keine Diagnosen | `SupportNeeds`: nur Auswahl aus festem Katalog (BOARDING_HELP, AUDIO_GUIDANCE, LOW_FLOOR, WHEELCHAIR_SPACE, ASSISTANCE_ANIMAL, ACCOMPANYING_PERSON; die ersten drei = `assistance_codes` in `api/federation.yaml`), kein Freitext, Fehlertext wiederholt die Eingabe nicht. |
| Ausgabe | API: `GET /v1/passengers/{passenger_id}/support-needs` (`getPassengerSupportNeeds`, Schema `PassengerSupportNeeds`). Kein anderes Schema enthält das Merkmal; Standardantworten (`Passenger`) bleiben ohne (belegt `tests/acceptance/security/test_feldschutz_api.py`). Der Endpunkt ist mit WP-M1-29 gebaut (`PassengerController`, Verdrahtung `PassengerConfiguration`: Modulzugang, Schlüssel, `FieldAccess`; Test `PassengerApiTest`). Für den eigenen Fahrgast gibt es getrennt `GET/PUT /v1/me/transport-support` (Schema `MyTransportSupport`, nur eigene Daten); ohne Feldscope antwortet der Anfragefilter 403, mit Scope ohne Recht/Zweck oder im fremden Mandanten 404. |
| Keine Weitergabe | Logs: kein Wert in `FieldAccess`; Metrik `taktovia.field.access` trägt nur Feldcode und Ergebnis; Outbox: Standardprojektion (`FieldRights.project` ohne Zweck) liefert das Feld nicht. Test `merkmal_nicht_in_logs_metriken_outbox`. |

## Fehlerabbildung (Kap. 05 §5.1)

| `FieldAccessException.Kind` | HTTP | Bedeutung |
|---|---|---|
| `DENIED` | 404 | Rolle ohne Recht, Feldscope fehlt, Zweck nicht für die Rolle freigegeben; ununterscheidbar von „gibt es nicht“ |
| `NOT_FOUND` | 404 | Fahrgast existiert im Mandanten nicht (auch Fremdmandant); es entsteht kein „gelesen“-Auditeintrag |
| `PURPOSE_REQUIRED` | 422 | Rolle berechtigt, aber Zweck fehlt; wird erst nach der Berechtigungsprüfung gemeldet |
| `ContextRevokedException` | 403 | Mitgliedschaft entzogen (nach Commit des Entzugs scheitert jeder Zugriff) |

## Rollen×Feld-Matrix (Entwurf)

Quelle `backend/platform/src/main/resources/fieldprotection/feldrechte.json`, Schema [feldrechte.v1.schema.json](schemas/feldrechte.v1.schema.json). Ein Feld ist nur lesbar, wenn Feld in der Matrix **und** Feldscope im Kontext **und** Rolle (bzw. Dienstkonto) mit Recht **und** Zweck für dieses Recht gegeben sind.

| Feld | Klasse | Feldscope | Rolle → Zweck |
|---|---|---|---|
| `passenger.support_needs` | SUPPORT | `passengers:support:read` | callcenter → BOOKING_ASSISTANCE; support → SUPPORT_CASE; dispatcher → TRANSPORT_PLANNING |
| `passenger.contact` | PII | `passengers:contact:read` (noch keine Operation, daher in M1 für niemanden erreichbar) | callcenter → BOOKING_ASSISTANCE; support → SUPPORT_CASE |
| `payment.provider_ref` | PAYMENT_TOKEN | `payments:reference:read` (kommt mit M2) | finance → PAYMENT_PROCESSING; Dienstkonto (Zahlungsadapter) → PAYMENT_PROCESSING |

Alle übrigen Rollen (Fahrgast anderer Fahrgäste, Fahrer, Planer, Administrator ohne gesonderte Zuweisung, Personalplanung, Werkstatt) haben kein Recht. Der Administrator erhält Zugriff nur durch eine **zusätzliche Mitgliedschaft** in einer berechtigten Fachrolle (Rollen vereinigen sich je Mandant, vgl. [rechtematrix.md](rechtematrix.md)). Der Fahrgast selbst liest und ändert sein Merkmal über das Profil (WP-M1-29, Objektbindung WP-M1-08); der Fahrer erhält es nur als minimierten Betreiberpayload der Buchung (WP-M2-23).

## Zweck-Katalog

BOOKING_ASSISTANCE (Buchung/Beförderung unterstützen), SUPPORT_CASE (Supportfall), TRANSPORT_PLANNING (Disposition), PAYMENT_PROCESSING (Zahlungsabwicklung). Der Header `X-Access-Purpose` der API und der Matrixkatalog werden per Test synchron gehalten.

## Konfiguration und Betrieb

- Zwei Datenbankverbindungen: Runtime (`taktovia_runtime`) und Modulzugang (`taktovia_fields`, Passwort `TAKTOVIA_DB_FIELDS_PASSWORD`; in Staging/Produktion ausdrücklich zu setzen, sonst bricht `db.py bootstrap` ab; in Entwicklung/Test wird es aus dem Runtime-Passwort abgeleitet). `FieldAccessRoleGuard.problems` prüft beim Start beide Verbindungen.
- KEK-Secret `secret://taktovia-<umgebung>-secrets/field_kek_<kennung>` ist in `infra/deploy/geheimnisse.csv` zu führen; **nicht ausgeführt:** Anbindung an die Secret-Verwaltung der Umgebung (D-05), Verdrahtung als Spring-Bean in `app` (mit dem Controller in WP-M1-29).
- **Nicht im Umfang:** HR-/Gesundheitsdaten des Personals (M4), Schadensfotos (M3), paarweise Partner-Aliasse (M6); minimierter Betreiberpayload (WP-M2-23), Arbeitszeit/Abwesenheit (WP-M4-06, -08).
