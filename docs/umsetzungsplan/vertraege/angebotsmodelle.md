# Dienstkonfiguration und Angebotsmodelle (WP-M1-25)

Bau-Detailvertrag zu `LH-6.1-SYS-003`. **Alle Modellregeln sind Planungsannahme PA-01 / OP-28 und nicht fachlich freigegeben**: das Pflichtenheft nennt die drei Modelle, beschreibt ihre
Regeln aber nicht. Die Tabelle unten ist deshalb eine begründete Annahme, die der Auftraggeber bestätigen oder ersetzen muss (Änderung: Regeltabelle in `OfferModelRules` bzw. neue Schema-Version).

## Modelle (technische Enum-Werte `traffic_type` der Dienstkonfiguration `service_config`)

| Wert | Bezeichnung | Idee nach PA-01 |
|---|---|---|
| `AREA_ON_DEMAND` | Flächenbedarfsverkehr | Start und Ziel in Zonen, Abholung an Haltepunkt oder Adresse |
| `LINE_ON_DEMAND` | linienbasierter Bedarfsverkehr | feste Haltepunktfolge, Fahrt nur bei Anmeldung |
| `STATION_FEEDER` | Bahnhofszubringer | ein Bahnhofshaltepunkt als fester Start oder Ziel |

## Modellregeln (Pflicht / unzulässig / optional je Parameter der Dienstrevision)

| Parameter (Ebene Dienst) | `AREA_ON_DEMAND` | `LINE_ON_DEMAND` | `STATION_FEEDER` | Begründung der Annahme |
|---|---|---|---|---|
| `max_detour_pct` | **Pflicht** | **unzulässig** | optional | Flächenverkehr braucht eine Umwegbegrenzung für gebündelte Fahrten; eine feste Haltepunktfolge kennt keinen Umweg |
| `stop_walk_max_m` | optional | **Pflicht** | optional | feste Haltepunkte: der Fußweg zum Haltepunkt muss begrenzt sein; im Flächenverkehr auch Adressabholung |
| `advance_arrival_buffer_min` | **unzulässig** | **unzulässig** | **Pflicht** | Ankunftspuffer vor der Bahnabfahrt gibt es nur beim Bahnhofszubringer |
| `pooling_allowed` | optional | optional | optional | Bündelung ist modellunabhängig |

`transfer_min_time_min` ist im Parameterkatalog nur auf Mandant und Region zulässig (Katalog J.1, Region/Transfer) und kann nicht Teil der Dienstrevision sein; er gilt für den Bahnhofszubringer über die Präzedenzkette
(Region/Mandant). Eine Aufnahme auf Dienstebene wäre eine neue Schema-Version.

## Prüfung

- **Wann:** `ServiceConfigRevisionValidator` (Port `RevisionValidator` aus WP-M1-22) läuft bei `validate` **und** bei `publish` für jede Revision der Ebene `SERVICE`. Verstöße sind Feldverletzungen mit Pointer
  `/values/<parameter>` (`REQUIRED` bei fehlender Pflicht, `UNKNOWN_FIELD` bei modellfremdem Parameter). Der Katalog (Datentyp, Bereich, Enum der drei Modelle) prüft schon beim Anlegen des Entwurfs (422, `NOT_IN_ENUM` bei unbekanntem Modell).
- **Betreiber:** `service_config.operator_id` muss eine Organisation **des Mandanten** sein und dem Betreiber des Dienstes entsprechen; sonst Verstoß `/values/service_config/operator_id` (`INVALID_VALUE`). Ein Dienst mit
  Betreiber eines fremden Mandanten ist schon beim Anlegen 404 (zusammengesetzter FK `(tenant_id, operator_id)`).
- **Wirksame Modellregeln:** `GET /v1/services/{service_id}/configuration?at=` liefert Modell, Regeln und die fünf modellabhängigen Werte aus der wirksamen (publizierten) Revision über den Konfigurations-Port
  (`ConfigApi.effective`); `GET /v1/service-offer-models` liefert die Regeltabelle für die Oberfläche.
- **Fixtures für M2:** je Modell eine publizierte Konfiguration (`ServiceOfferFixtures`); Bediengebiet, Haltepunkte und Tarifverknüpfung bleiben Bezüge ohne Auflösung bis M2 (WP-M2-04, -05, -07, -21, -23).

## Offen

OP-28 (Auftraggeber legt die Modellregeln fest); die Testfahrt je Modell (Originalabnahme „gemäß Modellregeln erfolgreich gebucht“) folgt mit M2.
