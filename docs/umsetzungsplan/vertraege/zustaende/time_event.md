# Zustandsautomat Arbeitszeit aus Zeitereignissen (`time_event`)

- **Arbeitspakete:** WP-M4-11 (Stempeln in der Fahrer-App), WP-M4-12 (Offline-Stempel); Verträge `LH-6.24-TIM-002`, `LH-6.24-TIM-003` (Grundlage WP-M4-08, `LH-6.24-TIM-001`).
- **Modul / Tabelle:** `staff-time`, `time_event` (append-only, Trigger `time_event_immutable`). Der Zustand wird nie gespeichert, sondern aus der Ereignisfolge je Person abgeleitet (`TimeIntervals`, `WorkState`).
- **Planungsannahme:** Toleranzen (`TOLERANCE` 5 Minuten, `MAX_OFFLINE_AGE` 7 Tage) sind Planungsdefaults; das Pflichtenheft beziffert sie nicht (Offener Punkt des Vertrags TIM-003).

## Arbeitszeit

| Zustand | Bedeutung |
|---|---|
| `IDLE` | nicht gestartet oder beendet |
| `WORKING` | Arbeit läuft |
| `ON_BREAK` | Pause |

| Von | Ereignis | Nach |
|---|---|---|
| `IDLE` | `WORK_START` (Kommen) | `WORKING` |
| `WORKING` | `BREAK_START` | `ON_BREAK` |
| `ON_BREAK` | `BREAK_END` | `WORKING` |
| `WORKING`, `ON_BREAK` | `WORK_END` (Gehen) | `IDLE` |

Jedes andere Ereignis ist im Zustand nicht vorgesehen. Das Stempeln hängt an **keinem** Fahrzeug-, Prüf- oder Fahrtzustand (`resource_release`, `vehicle_run`); das Einstempeln erteilt keine Freigabe.

## Behandlung nicht vorgesehener Ereignisse

| Herkunft | Ereignis im Zustand nicht vorgesehen | Folge |
|---|---|---|
| online (`origin` `ONLINE`) | zweites `WORK_START` bei laufender Arbeitszeit | 409 `INVALID_STATE_TRANSITION`, nichts gespeichert; die App hält den Stempel als „zu prüfen“ |
| online | übrige (z. B. `WORK_END` ohne Beginn) | angenommen; die Intervallableitung kennzeichnet (`END_WITHOUT_START`, `MISSING_END`, Anomalien), nie verworfen |
| offline (`origin` `OFFLINE`) | jedes nicht vorgesehene Ereignis oder ein Ereignis, nach dem das zeitlich nächste vorhandene nicht mehr passt (überlappende Intervalle) | übernommen, `conflict_flag` `INVALID_ORDER`, Ergebnis `REVIEW_REQUIRED` |
| online und offline | Ereigniszeit oder Gerätezeit mehr als 5 Minuten in der Zukunft der Serverzeit | übernommen, `conflict_flag` `CLOCK_SKEW`, `REVIEW_REQUIRED` |
| offline | Ereigniszeit älter als 7 Tage, oder Gerätezeit und Ereigniszeit weichen um mehr als 5 Minuten ab | übernommen, `conflict_flag` `CLOCK_SKEW`, `REVIEW_REQUIRED` |

Ein markiertes Ereignis wird nie korrigiert oder verworfen; die Klärung erfolgt erst über den Korrekturantrag (TIM-007, M7).

## Annahme eines Stempels (`POST /v1/time-events`)

| Ergebnis `result` | Status | Bedeutung |
|---|---|---|
| `APPLIED` | 201 | neu übernommen, unauffällig |
| `REVIEW_REQUIRED` | 201 | neu übernommen, als möglicher Konflikt markiert |
| `DUPLICATE` | 200 | bekannte Ereignis-ID; das gespeicherte Ereignis, keine zweite Zeile |

Die Ereignis-ID (`event_id` = `device_event_id` der App) dedupliziert **dauerhaft** über `PRIMARY KEY (tenant_id, id)`, unabhängig vom 72-h-Response-Cache. Dieselbe ID mit anderem Inhalt: 409 `IDEMPOTENCY_CONFLICT`; ID einer anderen Person: 404; fremde Personen-ID im Body: 403. Die Annahme ist je Mandant und Person serialisiert (Advisory-Lock), sodass parallele Sync-Läufe und Wiederholungen genau eine Zeile ergeben.

## Stempel in der lokalen Queue der App

`pending` („nicht synchronisiert“) → `inFlight` (Sync-Lauf) → `synced` (APPLIED/DUPLICATE) oder `reviewRequired` („zu prüfen“, auch bei Abweisung durch den Server). Die `deviceEventId` ändert sich auf diesem Weg nie; die Erfassungszeit (Geräteuhr) wird nie überschrieben.
