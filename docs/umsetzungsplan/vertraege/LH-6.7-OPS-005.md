# LH-6.7-OPS-005 – Operative Fahrzeugsperre durch die Leitstelle

- **Verantwortlicher:** Rolle Leitstelle/Dispatch mit Fahrzeugsicherheit (Modul `fleet-safety`, Sicherheitsprüfung WP-M3-08)
- **Arbeitspaket:** WP-M3-41
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Ein gestörtes Fahrzeug kann gesperrt und aus zukünftigen Zuweisungen genommen werden.

**Originalabnahme (unverändert):** Neue Anfragen werden dem gesperrten Fahrzeug nicht zugewiesen.

## Vertrag

- **Tabellen / DDL:** Operative Sperre getrennt von sicherheitskritischen Sperren mit unterschiedlichen Aufhebungsrechten. `vehicle.active_block boolean` und `safety_revision` (V1) bilden mehrere gleichzeitige Gründe nicht ab (OP-01); der Sperrdatensatz (Arbeitsname `vehicle_block`: `tenant_id`, `vehicle_id`, Quelle `OPERATIONS`/`SAFETY`, Grund, Akteur, gesetzt_am, aufgehoben_am/-von) wird mit WP-M3-02 festgelegt. Aufheben der operativen Sperre ändert eine bestehende sicherheitskritische Sperre nicht. Sperre und Aufhebung erhöhen `safety_revision`, damit Hold-/Commit-Pfad im selben Commit prüft. Protokoll in `audit_event` (Akteur, Grund, Zeitpunkt). RLS (V1).
- **API:** Sperraktion an `/v1/operator/vehicles/{id}` (Pfad im OpenAPI-Entwurf, Arbeitsname `POST /v1/operator/vehicles/{id}/blocks` bzw. `DELETE …/blocks/{block_id}`) mit `If-Match`, Pflichtgrund (Sperrgrundkatalog der Leitstelle im Detailvertrag) und `Idempotency-Key`; ohne Grund abgewiesen; ohne Rolle 403; fremdes oder nicht berechtigtes Fahrzeug 404; veraltete Revision 412. Wiederholtes Sperren mit demselben Idempotenzschlüssel erzeugt keinen zweiten Sperrgrund. Liste/Detail zeigen Sperre mit Grund und Urheber.
- **Zustandsautomat:** Sperrzustand je Quelle (operativ: gesetzt → aufgehoben); Fahrzeug ist zuweisbar nur, wenn keine Sperre irgendeiner Quelle besteht. Verweis `zustaende/` wird mit WP-M3-08 angelegt.
- **JSON-Schema / Ereignisse:** Ereignis „Fahrzeug gesperrt" (Quelle Leitstelle) über Outbox; Auslöser für WP-M3-53/WP-M3-54 (Behandlung zugesagter Fahrten); Schemaname im Bau unter `api/events/`.
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** UI-07 – Aktion „Fahrzeug sperren" mit Pflichtgrund; Anzeige „gesperrt" mit Grund und Urheber in Liste und Detail; bereits zugesagte Fahrten bleiben sichtbar dem Fahrzeug zugeordnet.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-01 (Sperrgründe im Schema, Bedeutung von `active_block DEFAULT true`), Sperrgrundkatalog der Leitstelle und wer eine operative Sperre aufheben darf (WP „Detailverträge vorab"; Rechte hängen an OP-34/D-11). Beides blockiert den Bau nicht, der Aufhebungsweg ist bis zur Entscheidung auf die Rolle Leitstelle mit Pflichtgrund beschränkt (Annahme, im Review zu bestätigen).

## Tests

- **Positiv:** Nach dem Sperren geht eine neue Anfrage an ein anderes Fahrzeug; Sperre mit Akteur, Grund und Zeitpunkt protokolliert und in der Fahrzeugliste sichtbar; Ereignis „Fahrzeug gesperrt" entsteht.
- **Negativ:** Sperre ohne Grund und ohne Rolle abgewiesen; fremdes Fahrzeug (anderer Dienst/Mandant) 404; Aufhebungsversuch der sicherheitskritischen Sperre über diesen Weg scheitert, und Aufheben der operativen Sperre lässt eine gleichzeitige kritische Sperre bestehen; wiederholtes Sperren mit demselben Idempotenzschlüssel erzeugt keinen zweiten Sperrgrund; bereits zugesagte Fahrten bleiben dem Fahrzeug zugeordnet bis zum Störungsablauf.
- **Konkurrierend:** Sperre parallel zu einem Hold-Commit – keine Zusage auf das gesperrte Fahrzeug (Prüfung im Commit gegen `safety_revision`); gleichzeitige Sperre durch zwei Disponenten ergibt genau einen aktiven Sperreintrag je Idempotenzschlüssel bzw. 412 für den Verlierer.
- **Referenztests:** keine Katalog-ID im WP genannt.
- Testfälle: geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/OperationalBlockTest.kt::gesperrtes_fahrzeug_erhaelt_keine_neue_zuweisung; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/OperationalBlockTest.kt::aufheben_operativer_sperre_laesst_kritische_sperre_bestehen; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/OperationalBlockTest.kt::sperre_parallel_zu_hold_commit_keine_zusage; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/OperationalBlockTest.kt::sperre_ohne_grund_ohne_rolle_oder_fremdes_fahrzeug_abgewiesen; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/OperationalBlockTest.kt::aufhebungsversuch_kritischer_sperre_ueber_operativen_weg_scheitert; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/OperationalBlockTest.kt::idempotente_wiederholung_und_zwei_disponenten_ein_aktiver_sperreintrag; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/OperationalBlockTest.kt::sperre_protokolliert_akteur_grund_zeitpunkt_und_erzeugt_ereignis_zugesagte_fahrten_bleiben_zugeordnet

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
