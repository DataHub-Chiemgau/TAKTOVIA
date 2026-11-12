# LH-6.24-TIM-003 – Offline-Stempel mit einmaliger Übernahme

- **Verantwortlicher:** Rolle Personal/Zeit (Modul `staff-time`, Fahrer-App `mobile/driver`)
- **Arbeitspaket:** WP-M4-12
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Die Stempelfunktion darf bei gestörter Verbindung lokal Zeitereignisse puffern; Synchronisierung markiert Herkunft, Gerätezeit und möglichen Konflikt.

**Originalabnahme (unverändert):** Offline-Stempel wird nach Wiederverbindung mit eigener Ereignis-ID genau einmal übernommen.

## Vertrag

- **Tabellen / DDL:** `time_event` (neue Migration ab V4, Modul `staff-time`; in `db/migrations/V1__kernschema.sql` V1 nicht enthalten, Kap. 04 §4.5 Familie TIM): `tenant_id uuid`, `id uuid`, `person_ref`/`driver_ref uuid` (wie `staff_shift.driver_ref`), `device_event_id uuid` (stabile Ereignis-ID), `kind` (Kommen, Pausenbeginn, Pausenende, Gehen), `occurred_at timestamptz`, `timezone text` (IANA), `origin` (online/offline), `device_time timestamptz`, `received_at timestamptz`, `conflict_flag`, `shift_id` optional; `PRIMARY KEY(tenant_id,id)`, `UNIQUE(tenant_id,device_event_id)`; RLS nach `tenant_id` wie bei den Kerntabellen; Runtime-Rolle nur INSERT/SELECT, keine stille Änderung (Korrekturen sind `time_correction`, TIM-007, M7). Exakte Spaltennamen werden mit dem Migrationsentwurf festgelegt (WP-M4-08). Für diese Anforderung maßgeblich: `origin` = offline, `device_time` (Gerätezeit), `received_at` (Serverempfangszeit), `conflict_flag` (möglicher Konflikt), dauerhaft eindeutige Ereignis-ID `UNIQUE(tenant_id,device_event_id)`; die Deduplikation gilt dauerhaft und damit über den 72-h-Response-Cache hinaus (Kap. 05 §5.1). Nie stillschweigend korrigieren oder verwerfen.
- **API:** `POST /v1/time-events` (Sync-Annahme), Ergebnis `APPLIED`, `DUPLICATE` oder `REVIEW_REQUIRED` (analog `/v1/driver/events`, `api/driver.yaml`: Ereignis-ID dedupliziert dauerhaft); Idempotency-Key und Traceparent nach `api/common.yaml`; Statuscodes 200, 400, 401, 403, 409, 422, 429, 503 (`api/driver.yaml` nennt für `DriverEventResult` die Gründe `STALE_PLAN`, `OUTSIDE_STOP`, `CLOCK_SKEW`, `INVALID_ORDER`; welche davon für Stempel gelten, ist offen). Wiederholte Sync-Läufe liefern `DUPLICATE` ohne zweite Zeile.
- **Zustandsautomat:** Zustand des Stempels in der lokalen Queue: lokal („nicht synchronisiert“), synchronisiert; serverseitig Ergebnis APPLIED, DUPLICATE, REVIEW_REQUIRED. Datei `vertraege/zustaende/time_event.md` (siehe TIM-002).
- **JSON-Schema / Ereignisse:** Outbox-Ereignis bei Übernahme; Audit WP-M1-46; Idempotenzregister WP-M1-13.
- **Parameter und Kennzahlen:** keine neuen; zulässige Abweichung Gerätezeit/Serverzeit ist im Pflichtenheft nicht beziffert (Offene Punkte).
- **Oberfläche:** Fahrer-App: verschlüsselte lokale Queue (Kap. 01 §1.5) mit `device_event_id`, Erfassungszeit und Status „nicht synchronisiert“; Anzeige markierter Konflikte als „zu prüfen“ (Klärung erst über Korrekturantrag TIM-007, M7).
- **Entscheidungen:** keine.
- **Offene Punkte:** Konfliktregeln (zulässige Abweichung Gerätezeit/Serverzeit, überlappende Intervalle) und Ergebniscodes sind im Pflichtenheft nicht festgelegt und vor dem Bau von WP-M4-12 vorab zu vertiefen (Detailverträge vorab laut WP); Endpunktwahl siehe WP-M4 Offener Punkt 36; AT-46 und die Anforderung liegen in M4, das Gate steht in M7 (Offener Punkt 35, Vorziehen vorgeschlagen). Abhängig von Spike-Ergebnis WP-M0-28 und Offline-Queue WP-M3-24.

## Tests

- **Positiv:** offline Kommen/Gehen erfassen, nach Wiederverbindung Sync: Ereignis genau einmal mit eigener Ereignis-ID übernommen (Originalabnahme); `origin`, `device_time` und `received_at` am Ereignis gespeichert; mehrfache Sync-Wiederholung ergibt `DUPLICATE` und keine zweite Zeile.
- **Negativ:** manipulierte Gerätezeit wird als möglicher Konflikt (`REVIEW_REQUIRED`) markiert, nicht korrigiert oder verworfen; Stempel für fremde Person wird abgewiesen; Wiederholung nach Ablauf des 72-h-Response-Caches wird weiterhin dedupliziert.
- **Konkurrierend:** derselbe Stempel parallel von zwei Sync-Läufen ergibt genau eine Zeile (Datentrennung); Sync-Lauf parallel zu einem online erfassten Stempel derselben Person verliert keinen Stempel.
- **Referenztests:** AT-46 (Gate M7, fachlich M4).
- Testfälle: AT-46; db/tests/test_time_event.py::test_ereignis_id_eindeutig_je_mandant; db/tests/test_time_event.py::test_herkunft_und_konfliktmarke; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/TimeEventOfflineSyncTest.kt::offline_stempel_genau_einmal_uebernommen; backend/app/src/test/kotlin/de/taktovia/app/staff/TimeEventStempelnApiTest.kt::offline_stempel_genau_einmal_ergebniscodes_und_dauerhafte_deduplikation; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/TimeEventOfflineSyncTest.kt::manipulierte_geraetezeit_review_required_ohne_korrektur; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/TimeEventOfflineSyncTest.kt::widerspruechliche_reihenfolge_wird_markiert_und_nicht_verworfen; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/TimeEventOfflineSyncTest.kt::offline_stempel_fremde_person_wird_abgewiesen; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/TimeEventOfflineSyncTest.kt::paralleler_sync_gleiche_ereignis_id_genau_eine_zeile; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/TimeEventOfflineSyncTest.kt::sync_parallel_zu_online_stempel_derselben_person_verliert_keinen_stempel; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/TimeEventOfflineSyncTest.kt::wiederholung_nach_72h_cache_ablauf_weiter_duplicate; backend/app/src/test/kotlin/de/taktovia/app/staff/TimeEventStempelnApiTest.kt::paralleler_sync_ueber_http_ergibt_genau_eine_zeile; mobile/driver/test/offline_stempel_queue_test.dart::queue_haelt_device_event_id_stabil (nicht ausgeführt, Flutter fehlt)

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung nicht gefordert; Datentrennung und Idempotenz durch konkurrierende Tests abgedeckt.
