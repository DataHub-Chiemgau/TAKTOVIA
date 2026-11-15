# Fahrer-Planrevision: Commit bis Darstellung (WP-M5-13, LH-6.19-NFR-004)

- Zielwert: p95 <= 10 s, Status **vorläufig, D-05 offen**; Urteil dieses Laufs (Messumgebung, keine Abnahme): **erfüllt**
- Last: 2 Mandanten x 250 verbundene Geräte (Live-Kanal offen), Grundlast: Positionsmeldung je Fahrer alle 15 s (POST /v1/driver/positions)
- gestartet 2026-11-15T04:23:07.221851+00:00, Aufbau 12.0 s; Uhrenabgleich: {"anfang": {"versatz_ms": 0.001, "hin_rueck_ms": 0.025, "runden": 25, "bemerkung": "gleiche Maschine; Messzeiten stammen alle aus der Wanduhr des Messprozesses"}, "ende": {"versatz_ms": -0.0, "hin_rueck_ms": 0.02, "runden": 25, "bemerkung": "gleiche Maschine; Messzeiten stammen alle aus der Wanduhr des Messprozesses"}}

## A Serie unter Referenzlast (Zielmenge)

Stichprobe 600; Zielmenge 594; Latenz {"n": 594, "min_s": 0.021, "p50_s": 0.5554, "p90_s": 0.9475, "p95_s": 0.9908, "p99_s": 1.0326, "max_s": 1.3336, "mean_s": 0.5497}
- Verteilung: {"0-0.25s": 122, "0.25-0.5s": 135, "0.5-1s": 313, "1-2s": 24, "2-5s": 0, "5-10s": 0, ">=10s": 0}
- über Ziel: 0, nachgeladen (Ausreißer): 0, nicht dargestellt: 0, Anteil innerhalb Ziel: 1.0, p95 inkl. Ausreißer: 0.9908
- unterbrochen (getrennt): {"n": 6, "nicht_dargestellt": 0, "latenz": {"n": 6, "min_s": 0.2706, "p50_s": 0.4041, "p90_s": 0.9017, "p95_s": 0.9017, "p99_s": 0.9017, "max_s": 0.9017, "mean_s": 0.4977}}
- Teilstrecken: {"commit_bis_empfang": {"n": 594, "min_s": 0.0034, "p50_s": 0.5268, "p90_s": 0.9253, "p95_s": 0.9661, "p99_s": 1.0037, "max_s": 1.029, "mean_s": 0.52}, "empfang_bis_darstellung": {"n": 594, "min_s": 0.0098, "p50_s": 0.0199, "p90_s": 0.0421, "p95_s": 0.0576, "p99_s": 0.2741, "max_s": 0.7573, "mean_s": 0.0297}}

## B Konkurrenz (zwei Revisionen kurz nacheinander, Bestätigung parallel)

Stichprobe 60; Zielmenge 60; Latenz {"n": 60, "min_s": 0.1162, "p50_s": 0.6041, "p90_s": 0.9974, "p95_s": 1.0322, "p99_s": 1.0941, "max_s": 1.0941, "mean_s": 0.6361}
- Verteilung: {"0-0.25s": 8, "0.25-0.5s": 8, "0.5-1s": 38, "1-2s": 6, "2-5s": 0, "5-10s": 0, ">=10s": 0}
- über Ziel: 0, nachgeladen (Ausreißer): 0, nicht dargestellt: 0, Anteil innerhalb Ziel: 1.0, p95 inkl. Ausreißer: 1.0322
- unterbrochen (getrennt): {"n": 0, "nicht_dargestellt": 0, "latenz": {"n": 0}}
- Teilstrecken: {"commit_bis_empfang": {"n": 60, "min_s": 0.0966, "p50_s": 0.5962, "p90_s": 0.9801, "p95_s": 1.0158, "p99_s": 1.0686, "max_s": 1.0686, "mean_s": 0.6198}, "empfang_bis_darstellung": {"n": 60, "min_s": 0.0, "p50_s": 0.0135, "p90_s": 0.0362, "p95_s": 0.0437, "p99_s": 0.0907, "max_s": 0.0907, "mean_s": 0.0163}}

## C Verbindung kurz unterbrochen (getrennt ausgewiesen)

Stichprobe 50; Zielmenge 0; Latenz {"n": 0}
- Verteilung: {"0-0.25s": 0, "0.25-0.5s": 0, "0.5-1s": 0, "1-2s": 0, "2-5s": 0, "5-10s": 0, ">=10s": 0}
- über Ziel: 0, nachgeladen (Ausreißer): 0, nicht dargestellt: 0, Anteil innerhalb Ziel: None, p95 inkl. Ausreißer: None
- unterbrochen (getrennt): {"n": 50, "nicht_dargestellt": 0, "latenz": {"n": 50, "min_s": 2.333, "p50_s": 2.5705, "p90_s": 2.633, "p95_s": 2.6405, "p99_s": 2.6895, "max_s": 2.6895, "mean_s": 2.5612}}
- Teilstrecken: {"commit_bis_empfang": {"n": 0}, "empfang_bis_darstellung": {"n": 0}}

## D Verteilweg angehalten und freigegeben (Ausreißer)

Stichprobe 40; Zielmenge 40; Latenz {"n": 24, "min_s": 5.056, "p50_s": 7.1067, "p90_s": 9.1645, "p95_s": 9.5861, "p99_s": 9.7724, "max_s": 9.7724, "mean_s": 7.4093}
- Verteilung: {"0-0.25s": 0, "0.25-0.5s": 0, "0.5-1s": 0, "1-2s": 0, "2-5s": 0, "5-10s": 24, ">=10s": 0}
- über Ziel: 0, nachgeladen (Ausreißer): 16, nicht dargestellt: 0, Anteil innerhalb Ziel: 0.6, p95 inkl. Ausreißer: unendlich
- unterbrochen (getrennt): {"n": 0, "nicht_dargestellt": 0, "latenz": {"n": 0}}
- Teilstrecken: {"commit_bis_empfang": {"n": 24, "min_s": 5.0287, "p50_s": 7.0971, "p90_s": 9.1396, "p95_s": 9.5599, "p99_s": 9.7523, "max_s": 9.7523, "mean_s": 7.3834}, "empfang_bis_darstellung": {"n": 24, "min_s": 0.0096, "p50_s": 0.0249, "p90_s": 0.0415, "p95_s": 0.044, "p99_s": 0.0511, "max_s": 0.0511, "mean_s": 0.0258}}

## Serverseitige Kette (Datenbank)

```
{
 "ereignisse_run_revised": 750,
 "outbox_bis_live_event_p95_s": 0.0006,
 "outbox_bis_live_event_max_s": 0.1109,
 "unveroeffentlicht": 786,
 "aeltester_unveroeffentlichter_eintrag_s": 158.8,
 "relay": "nicht verdrahtet: `published_at` bleibt leer, Relay-Verzug nicht messbar (die Zustellung an Fahrer läuft über `live_event`, geschrieben vom Trigger in der Commit-Transaktion)"
}
```

## Sättigung

```
{"proben": 73, "app_cpu_pct_von_einem_kern_max": 169.0, "maschine_cpu_pct_max": 99.0, "app_rss_mb_max": 691, "db_verbindungen_max": 22, "sperrwartende_max": 0, "outbox_wartend_max": 786}
```

## Zustellung und Datentrennung

```
{"fremdzustellung": {"fremde_ereignisse_gesamt": 0, "rueckschritte_gesamt": 0, "ereignisse_gesamt": 750, "geraete": 500, "geraete_fehler": [], "wiederverbindungen_gesamt": 1050, "http_wiederholungen_veraltete_verbindung": 167}, "negativ": {"fremder_mandant": [404, 404, 404, 404, 404], "anderer_fahrer": [404, 404, 404, 404, 404]}, "stichprobe_je_mandant": {"B": 384, "A": 366}}
```

## Grenzen

- Gerätesimulation statt Flutter-App und Zielgerät (OP-27): `on_displayed` ersetzt den Testmodus-Meldepunkt von UI-10 (`display_probe.dart`, nicht ausgeführt). Die Aussage gilt für das simulierte Gerät.
- Planschreiber: Nachbildung von `JdbcRunPlans.appendIn` unter der Runtime-Rolle (Zeilenlock, Revision +1, Stopps, `plan_change`, Outbox `dispatch.run_revised`); Plan-Validator und Buchungsdiff entfallen, die Pläne enthalten keine Buchungen.
- Der Relay (Outbox -> Broker) ist in der Anwendung nicht verdrahtet (`published_at` bleibt leer); der Live-Kanal liest `live_event`, das der Trigger `outbox_live` in der Commit-Transaktion schreibt. Relay-Verzug ist daher nicht messbar, Outbox-Alter = Alter unveröffentlichter Zeilen.
- Phase D hält den Verteilweg real an (offene Transaktion hält `pg_snapshot_xmin`, der Live-Kanal liefert nur Zeilen darunter); das ist ein Ersatz für „Relay gestoppt und wieder gestartet“, kein Test eines Relays.
- Push-Weckruf (geschlossene App, OP-29) und Offline-Sync sind nicht Teil der Messung (Nicht im Umfang); „bestehende Verbindung“ = Live-Kanal-Strom offen (SSE), „bestätigte Planrevision“ = committete Revision des Umlaufs (OP-06 offen).
- Zeit: Server, Datenbank und Gerätesimulation laufen auf derselben Maschine, alle Zeitstempel stammen aus der Wanduhr des Messprozesses (Uhrenabgleich gegen die Datenbankuhr protokolliert). Der Start der Messung liegt vor dem ersten Befehl des Commits (konservativ).
- Geteilte Maschine (Generator, Anwendung, PostgreSQL, Issuer); Zielhardware und Spitzenlast offen (D-05). Grundlast nur Fahrerpositionen (15 s je Gerät); keine gleichzeitige Buchungs-, Lese- und Leitstellenlast.
- Authentifizierung über lokalen Ersatz-Issuer (JWKS, RS256) statt Keycloak; Umgebung `local` ohne TLS.
