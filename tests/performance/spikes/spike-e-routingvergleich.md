# Spike (e) WP-M0-30: Streckenbezogener Routingvergleich (D-09)

Stand 11.11.2026. **Status: Der Anbietervergleich ist nicht ausführbar. Ausgeführt wurde ein Probelauf mit einer lokalen Valhalla-Engine auf einem künstlichen Straßennetz (eine Variante, keine reale Region).**
Port, Adapter, Messwerkzeug, Teststreckenkataloge und Bewertungsraster liegen vor. Die Zahlen unten sind Zahlen dieses Probelaufs und keine Aussage über Valhalla in einer realen Region
und keinen Vergleich zweier Varianten.

## 1. Akzeptanzkriterien

| Nr. | Kriterium | Status | Grund und Stand |
|---|---|---|---|
| 1 | Beide Varianten auf demselben Teststreckenkatalog gemessen (Fahrzeit, Distanz, Antwortzeit je Strecke) | nicht ausführbar | Es gibt keine zweite verfügbare Variante (Benennung und Testkonto sind Beistellung des Auftraggebers, D-09) und keine realen Karten- und Referenzdaten der Pilotregion (D-05). Valhalla selbst lief lokal (Abschnitt 3), aber nur auf einem künstlichen Netz. |
| 2 | Matrixlatenz für die Referenzgröße aus WP-M0-27 gemessen und dem 1-s-Budget gegenübergestellt | teilweise | Gemessen für Valhalla auf dem künstlichen Netz (Abschnitt 4): Referenzgröße M-2x250, p95 über den Adapter 6711.245 ms gegenüber 1.000 ms Budget. Nicht übertragbar auf eine reale Region; keine zweite Variante. |
| 3 | Fuß-/Rollstuhlwege und Restriktionen an benannten Beispielstrecken geprüft, Fehlrouten dokumentiert | teilweise | 11 von 11 konstruierten Strecken auf dem künstlichen Netz verhalten sich wie erwartet (Abschnitt 5). Reale Beispielstrecken, Zugänge und Referenzfahrzeiten fehlen (Beistellung). |
| 4 | Lizenz- und Datenspeicherungsbedingungen beider Varianten schriftlich bewertet | nicht ausführbar | Lizenztexte und Nutzungsbedingungen der Anbieter und der Kartendaten lagen nicht vor und werden hier nicht aus dem Gedächtnis bewertet. Das Raster nimmt die Bewertung als K.O.-Kriterium KO1 auf. |
| 5 | Entscheidungsvorlage mit begründeter Empfehlung und Unsicherheiten | teilweise | Vorlage in Abschnitt 8. Eine Empfehlung ohne Messung in einer realen Region wäre erfunden; der Arbeitsstand aus D-09 bleibt Valhalla/OSM. |

## 2. Was gebaut wurde

- **Adapter-Schnittstelle** `spike_e/routing_port.py`: `route`, `matrix`, `data_version`; Profile `auto`, `pedestrian`, `wheelchair`; Fehlerklassen `OutOfCoverage`, `NoRoute`, `ProviderTimeout`, `ProviderUnavailable`;
  `cache_key(Start, Ziel, Profil, Datenstand)` nach Kap. 04 §4.4; `TimeoutGuard` erzwingt eine Frist je Aufruf.
- **Valhalla-Adapter** `valhalla_adapter.py` (`/route`, `/sources_to_targets`, Fehlerabbildung) mit Vertragstest gegen einen Stub (`fake_valhalla.py`, Antwortformen aus der Valhalla-Dokumentation) **und** gegen die echte Engine (Abschnitt 3).
- **Referenzimplementierung** `grid_router.py`: rechnerisches Gitternetz, **kein Anbieter**; sie prüft Port, Fehlerverhalten und Messwerkzeug (`measure.py --provider grid`). Ihre Laufzeiten stehen nicht in diesem Bericht.
- **Tests:** `spike_e/test_spike_e.py` (15 Tests: Port, Cache-Schlüssel, Timeout-Verhalten, Adaptervertrag gegen den Stub, Katalog, Raster) und `test_valhalla_local.py` (5 Tests gegen die echte Engine, nur mit pyvalhalla; sonst übersprungen).

## 3. Valhalla-Probelauf (lokal, künstliches Netz)

Valhalla 3.9.1 aus dem PyPI-Paket `pyvalhalla` (enthält die Programme zum Kachelbau und die Python-Anbindung); Kartendaten gibt es in der Umgebung nicht (externe Hosts nicht erreichbar). Deshalb erzeugt `osm_pbf.py` ein **künstliches Gitternetz** als OSM-PBF-Datei
(120 × 120 Knoten, ca. 14.400 Knoten, 2799 Byte): Wohn- und Hauptstraßen, Einbahnstraßen, eine Fußgängerzone, eine Treppe (`wheelchair=no`) als einzige Verbindung zweier Knoten, eine nicht angebundene Insel.
Der Kachelbau dauerte 0.64 s (frischer Bau in einem gesonderten Lauf). Ein dünner HTTP-Wrapper (`valhalla_local.py`) stellt `/route` und `/sources_to_targets` mit den Fehlerformen der Valhalla-API bereit; Valhalla-HTTP-Server (prime_server) ist im Wheel nicht enthalten.

Belegt ist damit, dass der Adapter die **echten** Antwortformen (`trip.summary.time`, `sources_to_targets[i][j].time`) und Fehlercodes (171 „No suitable edges“, 442 „No path“) richtig abbildet. Nicht belegt: Verhalten in einer realen Region, Datenqualität, Lizenz, Betrieb im Container, Verhalten unter Parallelität.

**Befund zum Snapping (Fehlroutengefahr):** Mit dem Standard-Snapping von Valhalla wird ein Ort auf einem nicht angebundenen Netzteil stillschweigend mit dem nächsten erreichbaren Netz verbunden (Strecke V08b: 555 m vom Ziel entfernt),
und die Antwort nennt trotzdem die angefragten Koordinaten als Ziel: keine Fehlermeldung, aber eine Route, die das Ziel nicht erreicht. Der Adapter bindet Orte deshalb standardmäßig streng (`minimum_reachability` 0, Radius 100 m); dann meldet Valhalla `No path could be found` (Fehlercode 442, V08).
Eine Abwägung bleibt: Streng gebundene Orte können auf kleinen Insel-Wegstücken landen und dann als „keine Verbindung“ gelten; für die Disposition ist das die sichere Richtung. In einer realen Region zu prüfen.

Korrektur während des Probelaufs, ausdrücklich: Die erste Fassung der Einbahnstrecke (V04) war falsch konstruiert (die Engine nutzte in beiden Richtungen die schnellere Hauptstraße, beide Wege gleich lang), die erste Erwartung für V08 („Fehler“) ging
vom Standard-Snapping aus. Beides wurde nach der Beobachtung im Katalog geändert (V04 kurze Einbahnstrecke, V08 streng gegen V08b Standard); die Ergebnisse unten gehören zur geänderten Fassung, die Matrixmessung stammt aus dem ersten vollständigen Lauf (`routes_rerun_note` in der Ergebnisdatei).

Befund zur Konfiguration: Valhalla begrenzt Matrizen standardmäßig auf 2.500 Ortspaare (`max_matrix_location_pairs`); die Referenzgröße 2 × 250 (500 Paare) passt, 100 × 100 und 250 × 250 nicht. Für die Messung wurde die Grenze auf 100.000 angehoben; der Betreiber muss sie bewusst festlegen.

## 4. Matrixlatenz (Valhalla, künstliches Netz, ein Aufruf zur Zeit)

Messumgebung: Intel(R) Xeon(R) Processor @ 2.10GHz, 4 logische CPUs, 16086 MB RAM, Linux-6.18.44-fc-v378-x86_64-with-glibc2.39; geteilter Rechner (andere Arbeiten liefen zeitweise parallel), Datenstand: künstliches Netz, Punkte zufällig im Netz (Seed 1), Profil `auto`.
Beide Spalten messen dieselben Matrizen: direkt an der Engine und über Adapter und HTTP-Wrapper.

| Größe | Zellen | Wiederholungen | Engine p50 ms | Engine p95 ms | über Adapter p50 ms | über Adapter p95 ms | p95 ≤ 1.000 ms |
|---|---:|---:|---:|---:|---:|---:|---|
| M-100x100 | 10000 | 5 | 7076.876 | 8674.121 | 7160.696 | 7885.537 | nein |
| M-1x25 | 25 | 10 | 238.693 | 396.075 | 236.817 | 364.498 | ja |
| M-1x250 | 250 | 10 | 3377.155 | 4945.541 | 3545.582 | 4300.446 | nein |
| M-250x250 | 62500 | 3 | 28830.236 | 30241.238 | 28439.75 | 30124.428 | nein |
| M-25x25 | 625 | 10 | 1272.168 | 1553.82 | 1372.464 | 1648.762 | nein |
| M-2x250 | 500 | 10 | 5350.656 | 6679.303 | 5222.488 | 6711.245 | nein |

Einordnung: Auf diesem Netz überschreitet die Referenzgröße das 1-s-Budget. Das ist **keine** belastbare Aussage über eine reale Region (das künstliche Netz besteht fast nur aus Wohnstraßen und hat vermutlich deshalb keine nutzbare Straßenhierarchie, das ist nicht geprüft; die Last des Rechners war nicht kontrolliert), sie zeigt aber, dass die
Matrixberechnung für 250 Fahrzeuge nicht selbstverständlich im Budget liegt. Das spricht dafür, Kandidaten vor der Matrix zu verengen (Luftlinie, Radius) und den Cache `route_matrix_cache` früh zu nutzen; WP-M0-27 und M2 müssen das mit realen Daten klären.
Rohdaten: `spike-e-data/messung-valhalla-synthetisch.json`.

## 5. Konstruierte Restriktionsfälle (Valhalla, künstliches Netz)

Katalog `teststrecken-valhalla-synthetisch.json`; die Erwartungen sind konstruiert und zeigen nur, dass die Engine die modellierten Restriktionen einhält.

| ID | Kategorie | Profil | Erwartung | Ergebnis | wie erwartet |
|---|---|---|---|---|---|
| V01 | fahrzeug_kurz | auto | route | 186 s / 2470 m | ja |
| V02 | fahrzeug_mittel | auto | route | 1028 s / 13850 m | ja |
| V03 | fahrzeug_lang | auto | route | 1727 s / 23170 m | ja |
| V04a | restriktion_einbahn_mit_richtung | auto | route | 12 s / 98 m | ja |
| V04b | restriktion_einbahn_gegen_richtung | auto | umweg_laenger_als:V04a | 35 s / 298 m | ja |
| V05 | restriktion_fussgaengerzone | auto | laenge_km_mindestens:2.05 | 160 s / 2160 m | ja |
| V06a | fuss_treppe | pedestrian | route | 71 s / 100 m | ja |
| V06b | rollstuhl_treppe_meiden | wheelchair | umweg_laenger_als:V06a | 266 s / 296 m | ja |
| V07 | ausserhalb_datenbestand | auto | error:OutOfCoverage | Fehler OutOfCoverage | ja |
| V08 | keine_verbindung | auto | error:NoRoute | Fehler NoRoute | ja |
| V08b | keine_verbindung_standard_snapping | auto | route | 410 s / 5655 m | ja |

## 6. Teststreckenkatalog für die reale Messung (D-09-Kriterien)

12 Strecken in `teststrecken.json`: Fahrzeug kurz/mittel/lang (R01 bis R03), Einbahn/Abbiegeverbot, Fußgängerzone, Gewicht/Höhe (R04 bis R06), Fußweg Haltepunkt zum Eingang und mit Treppe (R07, R08), Rollstuhl barrierefrei und Treppenalternative (R09, R10),
außerhalb des Datenbestands (R11), keine Verbindung (R12). Die Koordinaten sind synthetisch (Stellvertreterregion); reale Haltepunkte, barrierefreie Zugänge und Referenzfahrzeiten (`reference_duration_s`, bisher leer) stellt der Auftraggeber bei.

Matrixgrößen (gemeinsam mit WP-M0-27, **Annahme** bis D-05 geklärt): M-1x25, M-1x250, **M-2x250 (Referenz: Abhol- und Zielpunkt gegen 250 Fahrzeuge, Budget 1 s)**, M-25x25, M-100x100, M-250x250 (`matrix-instanzen.json`).

## 7. Bewertungsraster (vor der Messung festgelegt, `bewertungsraster.json`)

| Kriterium | Gewicht |
|---|---:|
| K1 Abweichung der Fahrzeiten zu Referenzfahrzeiten | 20 |
| K2 Matrixlatenz p95 für M-2x250 gegen 1-s-Budget | 20 |
| K3 Fuß-/Rollstuhlwege und Restriktionen (Fehlrouten) | 20 |
| K4 Lizenz und Datenspeicherung (route_matrix_cache) | 15 |
| K5 Betriebsaufwand | 10 |
| K6 Kosten | 5 |
| K7 Datenstand und Versionierbarkeit | 5 |
| K8 Verfügbarkeit und Timeout-Verhalten | 5 |

Skala 0 bis 4. K.O.: KO1 (Speicherung/Zweck lizenzrechtlich untersagt), KO2 (Rollstuhlprofil führt über Treppen ohne Warnung). Empfehlung nur ohne K.O. und ab 60 %; bei weniger als 10 Prozentpunkten Abstand bleiben beide Varianten offen.
`scoring.py` rechnet das Raster; die Bewertung setzt das Team nach der Messung.

## 8. Entscheidungsvorlage „Routinganbieter“ (für WP-M0-34; Messwerte offen)

| Punkt | Inhalt |
|---|---|
| Entscheidung | D-09: Routinganbieter und Karten für den Pilot |
| Varianten | A: Valhalla/OSM im Eigenbetrieb (Favorit laut D-09), B: zweite Variante (benennt der Auftraggeber) |
| Messgrundlage | Katalog `teststrecken.json` mit realen Koordinaten, Matrixgrößen `matrix-instanzen.json`, Referenzfahrzeiten des Auftraggebers |
| Ergebnis je Variante | Punktwerte K1 bis K8, K.O.-Prüfung, Prozentwert (offen) |
| Zwischenstand aus dem Probelauf | Adapter und Valhalla-Anbindung funktionieren gegen die echte Engine; Matrixgrenze (2.500 Paare) ist zu konfigurieren; Matrixlatenz für 250 Fahrzeuge braucht Kandidatenverengung und Cache (auf künstlichem Netz) |
| Empfehlung | offen; bis zur Messung bleibt Valhalla/OSM Arbeitsstand ohne Produktivschaltung |
| Verbleibende Unsicherheiten | Datenqualität barrierefreier Wege in der Pilotregion, Lizenz der Kartendaten, Aktualisierungsrhythmus, Verkehrsdaten (nur Variante B), Betriebsaufwand, Verhalten unter Parallelität |

## 9. Nächste Schritte zur Ausführung des Vergleichs

1. Auftraggeber benennt die zweite Variante und liefert Zugang, reale Haltepunkte, barrierefreie Zugänge, Referenzfahrzeiten (Beistellung).
2. Valhalla mit dem Kartenextrakt der Pilotregion bereitstellen (Container oder `pyvalhalla`), Datenstand festhalten.
3. `teststrecken.json` mit realen Koordinaten und Referenzzeiten füllen, `measure.py --provider valhalla --url ... --data-version ...` ausführen, dasselbe für Variante B (zweiter Adapter hinter `RoutingPort`).
4. Raster bewerten, Lizenzbedingungen schriftlich bewerten, Vorlage ausfüllen, in WP-M0-34 übernehmen.

## 10. Reproduktion des Probelaufs

```
python -m venv /tmp/venv-valhalla && /tmp/venv-valhalla/bin/pip install pyvalhalla
/tmp/venv-valhalla/bin/python tests/performance/spikes/spike_e/measure_valhalla.py --reps 10       # schreibt spike-e-data/messung-valhalla-synthetisch.json
/tmp/venv-valhalla/bin/python -m unittest test_valhalla_local                                      # im Verzeichnis spike_e
python tests/performance/spikes/spike_e/build_report.py                                           # erzeugt diesen Bericht
```

`pyvalhalla` ist bewusst nicht in `tests/requirements.txt` (nur für diesen Probelauf); ohne das Paket werden die zugehörigen Tests übersprungen.
