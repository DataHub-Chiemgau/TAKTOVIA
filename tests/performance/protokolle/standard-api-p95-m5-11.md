# Lasttestprotokoll Standard-API (WP-M5-11, LH-6.19-NFR-002)

Beginn 2026-11-15T05:13:07.619195+00:00 · Build 11eae2a50d7e38c0b80eb05b46307b4672a6d24a+lokal · Status Zielwert: **vorläufig, D-05 offen**

- Ziel: p95 <= 1000 ms je Endpunktklasse; Messpunkt: Clientzeit des Lastgenerators auf derselben Maschine (obere Schranke der serverseitigen Zeit; Gateway nicht vorhanden, OP-04); 202 zählt bis zur Annahme
- Hardware: Intel(R) Xeon(R) Processor @ 2.10GHz · 4 logische CPUs · 16087 MB RAM · Linux-6.18.44-fc-v470-x86_64-with-glibc2.39
- Migrationsstand: V50200__standortminimierung_parameter.sql (261 Skripte), PostgreSQL 16.15 (Ubuntu 16.15-0ubuntu0.24.04.1), Seed 20261201
- Datenvolumen (mittel): Mandant 1: vehicle 250, staff_shift 750, passenger_private 2000, travel_attempt 4500, booking 4500, position_sample 15000; Mandant 2: vehicle 250, staff_shift 750, passenger_private 2000, travel_attempt 4500, booking 4500, position_sample 15000
- Rolle und RLS: taktovia_runtime (Superuser False, BYPASSRLS False), 177 Tabellen mit erzwungener RLS, zwei Mandanten
- Last: 24 Sitzungen je Mandant, Denkzeit 0.3 s, 8229 Anfragen in 45.0 s (182.9 req/s)

| Klasse | n | p50 ms | p95 ms | p99 ms | max ms | Fehler | Urteil |
|---|---:|---:|---:|---:|---:|---:|---|
| GET /v1/analytics/metrics | 222 | 11.762 | 37.296 | 56.874 | 97.697 | 0 | erfüllt |
| GET /v1/bookings (limit 100) | 267 | 16.053 | 47.666 | 85.172 | 111.722 | 0 | erfüllt |
| GET /v1/bookings/{id} | 267 | 9.169 | 29.944 | 41.917 | 50.036 | 0 | erfüllt |
| GET /v1/config/effective | 324 | 6.681 | 26.031 | 115.919 | 182.503 | 0 | erfüllt |
| GET /v1/defects | 298 | 6.506 | 20.355 | 59.071 | 78.307 | 0 | erfüllt |
| GET /v1/driver/shifts/{id}/run | 437 | 8.883 | 27.557 | 72.675 | 153.478 | 0 | erfüllt |
| GET /v1/expenses/{id} (404-Pfad) | 124 | 6.306 | 25.34 | 84.003 | 122.669 | 0 | erfüllt |
| GET /v1/inspections/{id} (404-Pfad) | 157 | 6.631 | 24.128 | 79.852 | 124.502 | 0 | erfüllt |
| GET /v1/me | 1010 | 5.814 | 20.827 | 32.421 | 153.25 | 0 | erfüllt |
| GET /v1/operations/{id} (404-Pfad) | 134 | 5.899 | 23.679 | 67.774 | 82.453 | 0 | erfüllt |
| GET /v1/operator/bookings (limit 100) | 683 | 10.112 | 27.234 | 95.063 | 225.451 | 0 | erfüllt |
| GET /v1/operator/bookings (limit 100, Folgeseiten) | 287 | 10.663 | 39.392 | 117.058 | 179.786 | 0 | erfüllt |
| GET /v1/operator/vehicles | 574 | 69.422 | 127.601 | 356.917 | 520.272 | 0 | erfüllt |
| GET /v1/rosters | 212 | 6.624 | 19.735 | 58.627 | 74.078 | 0 | erfüllt |
| GET /v1/service-areas | 291 | 6.41 | 24.103 | 34.028 | 67.657 | 0 | erfüllt |
| GET /v1/services | 595 | 5.416 | 22.485 | 78.588 | 152.567 | 0 | erfüllt |
| GET /v1/stops | 560 | 6.565 | 22.788 | 53.756 | 157.857 | 0 | erfüllt |
| GET /v1/time-events | 267 | 6.22 | 22.559 | 52.977 | 89.841 | 0 | erfüllt |
| POST /v1/bookings (Confirm) | 267 | 16.253 | 40.018 | 59.992 | 98.23 | 0 | erfüllt |
| POST /v1/bookings/{id}/cancel | 268 | 20.744 | 47.349 | 68.716 | 164.036 | 0 | erfüllt |
| POST /v1/driver/events | 452 | 9.797 | 36.292 | 92.571 | 138.843 | 0 | erfüllt |

Zeitseitenkanal (fremde UUID gegen eigene unbekannte UUID, je 300): Median 4.002 ms gegen 4.024 ms (Differenz 0.022 ms), p95 12.499 gegen 11.741 ms, Status [404] und [404].
Cache-Probe: 1680 identische Pfadanfragen beider Mandanten parallel, 0 Antworten mit Kennungen des anderen Mandanten.

**Urteil: erfüllt**

Nicht gemessen: POST /v1/inspections: Prüflauf-Start braucht Checklistenvorlage und Freigabekette (kein Teil des Messaufbaus); gemessen wird der 404-Pfad von GET /v1/inspections/{id}

Hinweis: Angebotene Last des Generators auf geteilter Maschine; die Referenzspitze (Kap. 08 §8.6, Annahme OP-01) wird nicht erreicht, wenn durchsatz_rps darunter liegt
