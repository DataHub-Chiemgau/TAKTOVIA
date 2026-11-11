# Spike (e) WP-M0-30: Streckenbezogener Routingvergleich (D-09)

Stand 11.11.2026. **Status: nicht ausführbar im Kern (Anbietervergleich); Port, Messwerkzeug, Teststreckenkatalog und Bewertungsraster liegen vor und sind gegen
eine Referenzimplementierung und einen Stub geprüft.** Keine der folgenden Zahlen ist eine Messung von Valhalla oder einem anderen Routinganbieter.

## 1. Akzeptanzkriterien

| Nr. | Kriterium | Status | Grund |
|---|---|---|---|
| 1 | Beide Varianten auf demselben Teststreckenkatalog gemessen (Fahrzeit, Distanz, Antwortzeit je Strecke) | nicht ausführbar | Es gibt keine zweite verfügbare Variante (Benennung und Testkonto sind Beistellung des Auftraggebers, D-09) und keine realen Karten- und Referenzdaten der Pilotregion (D-05). Valhalla und Kartenextrakte sind in der Umgebung nicht verfügbar (kein Docker-Daemon, externe Hosts außer Paketregistern nicht erreichbar). |
| 2 | Matrixlatenz für die Referenzgröße aus WP-M0-27 gemessen und dem 1-s-Budget gegenübergestellt | nicht ausführbar | gleiche Gründe. Die Größen sind festgelegt (`matrix-instanzen.json`, Referenz M-2x250); das Messwerkzeug ist vorhanden (`measure.py`). |
| 3 | Fuß-/Rollstuhlwege und Restriktionen an benannten Beispielstrecken geprüft, Fehlrouten dokumentiert | nicht ausführbar | Der Katalog (`teststrecken.json`, 12 Strecken) hat synthetische Koordinaten; reale Straßen, Zugänge und Referenzfahrzeiten fehlen (Beistellung). |
| 4 | Lizenz- und Datenspeicherungsbedingungen beider Varianten schriftlich bewertet | nicht ausführbar | Lizenztexte und Nutzungsbedingungen der Anbieter waren nicht zugänglich und werden hier nicht aus dem Gedächtnis bewertet. Das Raster nimmt die Bewertung als K.O.-Kriterium KO1 auf. |
| 5 | Entscheidungsvorlage mit begründeter Empfehlung und Unsicherheiten | teilweise | Die Vorlage steht unten (Abschnitt 6). Eine Empfehlung ohne Messung wäre erfunden; der Arbeitsstand aus D-09 bleibt Valhalla/OSM. |

## 2. Was gebaut und ausgeführt wurde (Referenz- und Stub-Ergebnisse)

- **Adapter-Schnittstelle** `spike_e/routing_port.py`: `route`, `matrix`, `data_version`; Profile `auto`, `pedestrian`, `wheelchair`; Fehlerklassen `OutOfCoverage`,
  `NoRoute`, `ProviderTimeout`, `ProviderUnavailable`; `cache_key(Start, Ziel, Profil, Datenstand)` nach Kap. 04 §4.4; `TimeoutGuard` erzwingt eine Frist je Aufruf.
- **Referenzimplementierung** `grid_router.py`: rechnerisches Gitternetz, **kein Anbieter**. Sie dient nur dazu, Port, Fehlerverhalten und Messwerkzeug zu prüfen.
- **Valhalla-Adapter** `valhalla_adapter.py` und **Stub** `fake_valhalla.py`: Der Adapter bildet `/route` und `/sources_to_targets` ab und übersetzt Fehler. Der Test
  läuft gegen den Stub, dessen Antwortformen aus der Valhalla-Dokumentation stammen, wie sie dem Autor bekannt war; sie wurden **nicht** gegen einen laufenden Valhalla
  geprüft. Ein Abweichen der echten API vom Stub fällt erst beim ersten echten Lauf auf.
- **Tests** (15, `python -m unittest` in `spike_e/`): Port positiv und negativ (außerhalb des Datenbestands, keine Verbindung), Cache-Schlüssel, Timeout-Verhalten des Adapters
  (langsamer und nicht erreichbarer Anbieter), Adaptervertrag gegen den Stub, Katalogvollständigkeit, Rechenproben des Rasters.
- **Messwerkzeug** `measure.py --provider grid|valhalla`; der Lauf `--provider grid` zeigt, dass Katalog und Matrixmessung funktionieren (Verhalten aller 12 Strecken wie erwartet,
  `behaviour_as_expected`). Dessen Zahlen sind rechnerische Referenzwerte und stehen deshalb nicht in diesem Bericht.

## 3. Teststreckenkatalog (D-09-Kriterien)

12 Strecken in `teststrecken.json`: Fahrzeug kurz/mittel/lang (R01 bis R03), Einbahn/Abbiegeverbot, Fußgängerzone, Gewicht/Höhe (R04 bis R06), Fußweg Haltepunkt zum Eingang und mit Treppe
(R07, R08), Rollstuhl barrierefrei und Treppenalternative (R09, R10), außerhalb des Datenbestands (R11), keine Verbindung (R12). Je Strecke Profil, Start, Ziel, Hinweis, `reference_duration_s`
(leer bis zur Beistellung) und die erwartete Fehlerklasse.

## 4. Matrixgrößen (gemeinsam mit WP-M0-27, ANNAHME)

| ID | Quellen × Ziele | Bedeutung |
|---|---|---|
| M-1x25, M-1x250 | 1 × 25, 1 × 250 | ein Abholpunkt gegen Fahrzeugpositionen |
| **M-2x250** | 2 × 250 | **Referenz:** Abhol- und Zielpunkt gegen 250 Fahrzeuge, Budget 1 s |
| M-25x25, M-100x100 | 25 × 25, 100 × 100 | Haltepunkte der Pläne von 5 bzw. 20 Kandidatenfahrzeugen |
| M-250x250 | 250 × 250 | Obergrenze, Cache-Aufwärmung |

Die Größen sind eine Annahme, bis Flottengröße und Auftragsdichte (D-05) vorliegen. WP-M0-27 muss dieselben Größen verwenden.

## 5. Bewertungsraster (vor der Messung festgelegt, `bewertungsraster.json`)

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

Skala 0 bis 4. K.O.: KO1 (Speicherung/Zweck lizenzrechtlich untersagt), KO2 (Rollstuhlprofil führt über Treppen ohne Warnung). Empfehlung nur ohne K.O. und ab 60 %; bei weniger als 10 Prozentpunkten
Abstand bleiben beide Varianten offen. `scoring.py` rechnet das Raster; die Bewertung setzt das Team nach der Messung.

## 6. Entscheidungsvorlage „Routinganbieter“ (für WP-M0-34; Messwerte offen)

| Punkt | Inhalt |
|---|---|
| Entscheidung | D-09: Routinganbieter und Karten für den Pilot |
| Varianten | A: Valhalla/OSM im Eigenbetrieb (Favorit laut D-09), B: zweite Variante (benennt der Auftraggeber) |
| Messgrundlage | Katalog `teststrecken.json` mit realen Koordinaten, Matrixgrößen `matrix-instanzen.json`, Referenzfahrzeiten des Auftraggebers |
| Ergebnis je Variante | Punktwerte K1 bis K8, K.O.-Prüfung, Prozentwert (offen) |
| Empfehlung | offen; bis zur Messung bleibt Valhalla/OSM Arbeitsstand ohne Produktivschaltung |
| Verbleibende Unsicherheiten | Datenqualität barrierefreier Wege in der Pilotregion, Lizenz der Kartendaten, Aktualisierungsrhythmus, Verkehrsdaten (nur Variante B), Betriebsaufwand |

## 7. Nächste Schritte zur Ausführung

1. Auftraggeber benennt die zweite Variante und liefert Zugang, reale Haltepunkte, barrierefreie Zugänge, Referenzfahrzeiten (Beistellung).
2. Valhalla mit Kartenextrakt der Pilotregion bereitstellen (Container; in dieser Umgebung nicht möglich), Datenstand festhalten.
3. `teststrecken.json` mit realen Koordinaten und Referenzzeiten füllen, `measure.py --provider valhalla --url ... --data-version ...` ausführen, dasselbe für Variante B (zweiter Adapter hinter `RoutingPort`).
4. Raster bewerten, Lizenzbedingungen schriftlich bewerten, Vorlage ausfüllen, in WP-M0-34 übernehmen.
