# Lasttestprotokoll Standard-API (WP-M5-11, LH-6.19-NFR-002)

Beginn 2026-11-15T05:08:47.817535+00:00 · Build 11eae2a50d7e38c0b80eb05b46307b4672a6d24a+lokal · Status Zielwert: **vorläufig, D-05 offen**

- Ziel: p95 <= 1000 ms je Endpunktklasse; Messpunkt: Clientzeit des Lastgenerators auf derselben Maschine (obere Schranke der serverseitigen Zeit; Gateway nicht vorhanden, OP-04); 202 zählt bis zur Annahme
- Hardware: Intel(R) Xeon(R) Processor @ 2.10GHz · 4 logische CPUs · 16087 MB RAM · Linux-6.18.44-fc-v470-x86_64-with-glibc2.39
- Migrationsstand: V50200__standortminimierung_parameter.sql (260 Skripte), PostgreSQL 16.15 (Ubuntu 16.15-0ubuntu0.24.04.1), Seed 20261201
- Datenvolumen (mittel): Mandant 1: vehicle 266, staff_shift 766, passenger_private 2000, travel_attempt 4500, booking 4500, position_sample 15000; Mandant 2: vehicle 266, staff_shift 766, passenger_private 2000, travel_attempt 4500, booking 4500, position_sample 15000
- Rolle und RLS: taktovia_runtime (Superuser False, BYPASSRLS False), 177 Tabellen mit erzwungener RLS, zwei Mandanten
- Last: 24 Sitzungen je Mandant, Denkzeit 0.3 s, 7637 Anfragen in 45.0 s (169.7 req/s)

| Klasse | n | p50 ms | p95 ms | p99 ms | max ms | Fehler | Urteil |
|---|---:|---:|---:|---:|---:|---:|---|
| GET /v1/analytics/metrics | 210 | 24.985 | 76.8 | 113.652 | 142.78 | 0 | erfüllt |
| GET /v1/bookings (limit 100) | 223 | 23.895 | 79.428 | 112.939 | 133.591 | 0 | erfüllt |
| GET /v1/bookings/{id} | 225 | 15.569 | 66.105 | 120.51 | 218.746 | 0 | erfüllt |
| GET /v1/config/effective | 324 | 13.719 | 71.775 | 114.35 | 139.824 | 0 | erfüllt |
| GET /v1/defects | 282 | 15.184 | 65.96 | 153.919 | 186.944 | 0 | erfüllt |
| GET /v1/driver/shifts/{id}/run | 417 | 17.766 | 75.213 | 140.472 | 203.118 | 0 | erfüllt |
| GET /v1/expenses/{id} (404-Pfad) | 116 | 12.963 | 85.403 | 98.751 | 105.732 | 0 | erfüllt |
| GET /v1/inspections/{id} (404-Pfad) | 150 | 13.864 | 69.942 | 159.819 | 188.915 | 0 | erfüllt |
| GET /v1/me | 966 | 12.27 | 65.169 | 118.179 | 218.524 | 0 | erfüllt |
| GET /v1/operations/{id} (404-Pfad) | 124 | 14.133 | 73.679 | 106.887 | 125.746 | 0 | erfüllt |
| GET /v1/operator/bookings (limit 100) | 645 | 37.246 | 115.868 | 159.447 | 228.976 | 0 | erfüllt |
| GET /v1/operator/bookings (limit 100, Folgeseiten) | 271 | 34.49 | 83.16 | 122.065 | 152.424 | 0 | erfüllt |
| GET /v1/operator/vehicles | 549 | 102.385 | 214.331 | 312.752 | 384.406 | 0 | erfüllt |
| GET /v1/rosters | 197 | 12.72 | 71.228 | 96.61 | 105.066 | 0 | erfüllt |
| GET /v1/service-areas | 288 | 14.47 | 68.006 | 129.87 | 156.88 | 0 | erfüllt |
| GET /v1/services | 558 | 11.693 | 49.202 | 109.843 | 155.088 | 0 | erfüllt |
| GET /v1/stops | 524 | 13.759 | 59.596 | 122.273 | 216.862 | 0 | erfüllt |
| GET /v1/time-events | 254 | 11.794 | 46.254 | 101.461 | 255.168 | 0 | erfüllt |
| POST /v1/bookings (Confirm) | 223 | 28.67 | 98.905 | 143.188 | 266.054 | 0 | erfüllt |
| POST /v1/bookings/{id}/cancel | 225 | 34.014 | 123.48 | 235.484 | 282.915 | 0 | erfüllt |
| POST /v1/driver/events | 420 | 21.99 | 82.782 | 186.565 | 255.069 | 0 | erfüllt |

Zeitseitenkanal (fremde UUID gegen eigene unbekannte UUID, je 300): Median 4.102 ms gegen 4.139 ms (Differenz 0.037 ms), p95 5.968 gegen 6.615 ms, Status [404] und [404].
Cache-Probe: 1680 identische Pfadanfragen beider Mandanten parallel, 0 Antworten mit Kennungen des anderen Mandanten.

**Urteil: erfüllt**

Nicht gemessen: POST /v1/inspections: Prüflauf-Start braucht Checklistenvorlage und Freigabekette (kein Teil des Messaufbaus); gemessen wird der 404-Pfad von GET /v1/inspections/{id}

Hinweis: Angebotene Last des Generators auf geteilter Maschine; die Referenzspitze (Kap. 08 §8.6, Annahme OP-01) wird nicht erreicht, wenn durchsatz_rps darunter liegt
