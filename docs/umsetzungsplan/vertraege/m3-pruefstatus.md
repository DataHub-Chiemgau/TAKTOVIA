# Detailvertrag Prüfstatus (WP-M3-23, LH-6.21-CHK-016)

- **Arbeitspaket:** WP-M3-23
- **Status:** Vorschlag dieses Plans; Fachanalyse und Auftraggeber bestätigen vor Abnahme (OP-05, OP-16, OP-17).
- **Grundsatz:** Der Prüfstatus ist eine **Projektion** des Serverzustands je (Fahrzeug, Schicht), kein eigener Zustand und keine eigene Tabelle. Fahrer-App und Leitstelle zeigen exakt dieselben sechs Werte; die Ableitung steht allein im Server (`platform.inspection.InspectionStatusDerivation`), beide Lesepfade (`fleet-safety` für die Leitstelle, `staff-time` für die Fahrerprojektion) benutzen sie mit denselben Fakten.

## Ableitungstabelle (erste zutreffende Zeile gilt)

| Zeile | Fakten (Server) | Status | Anzeige |
|---|---|---|---|
| 1 | aktive Fahrzeugsperre (`vehicle_block`), aktive Dienstsperre der Schicht (`shift_block`) oder Fahrzeug inaktiv | `BLOCKED` | Gesperrt |
| 2 | Prüflauf der Schicht `IN_PROGRESS` (nicht die Prüfung vor Fahrgastaufnahme) | `IN_PROGRESS` | In Bearbeitung |
| 3 | gültige, nicht widerrufene `resource_release` mit aktueller `safety_revision`, Prüflauf ohne entfallene Dimension | `PASSED` | Bestanden |
| 4 | wie 3, aber `inspection_run.restriction.unavailable_dimensions` nicht leer (`PASSED_RESTRICTED`; Wirkung OP-17) | `RESTRICTED` | Eingeschränkt |
| 5 | zuletzt gestarteter Lauf `COMPLETED` mit Ergebnis `CLARIFICATION_REQUIRED`, keine Freigabe (OP-16) | `UNCLEAR` | Technisch ungeklärt |
| 6 | zuletzt gestarteter Lauf `COMPLETED` mit Ergebnis `BLOCKED` | `BLOCKED` | Gesperrt |
| 7 | sonst (keine Prüfung, abgelaufen, Freigabe widerrufen, Sicherheitsrevision überholt, keine Schicht) | `PENDING` | Ausstehend |

## Revision und Reihenfolge

`inspection_status_revision` = `vehicle.safety_revision` + Summe `inspection_run.revision` der Schicht + Summe `vehicle_block.revision` des Fahrzeugs + Summe `shift_block.revision` der Schicht + je `resource_release` der Schicht 1 (Erteilung) und 1 weitere bei Widerruf. Jede Zustandsänderung, die den Status ändern kann, erhöht mindestens einen Summanden; die Revision ist daher monoton und je Fahrzeug und Schicht vergleichbar. Ein Client übernimmt **nie** einen Stand mit kleinerer Revision als den angezeigten (`mergeInspectionStatus`, `PruefstatusStand.adopt`); bei zwei Wechseln in kurzer Folge (bestanden -> gesperrt) endet die Anzeige so auf dem letzten Serverstand.

## Schnittstellen und Zustellung

- `GET /v1/operator/vehicles` und `/{vehicle_id}`: Felder `inspection_status`, `inspection_status_revision` (Dienstscope wie bisher, nicht berechtigter Dienst: 404 bzw. nicht in der Liste).
- `GET /v1/driver/shifts/{shift_id}/run`: dieselben Felder; fremde Schicht, fremdes Fahrzeug und anderer Mandant: einheitlich 404.
- Zustellung Leitstelle: Kanal aus WP-M3-35 stößt das Nachladen an (Ereignisse `inspection.*`, `fleet.*`, `vehicle.*`, `defect.*`); zusätzlich lädt die Ansicht im Aktualisierungsintervall (`INSPECTION_REFRESH_MS` = 15 s, Planungsannahme), weil der Start eines Prüflaufs kein Kanalereignis hat. Der Kanal trägt nie den Status selbst (Positivliste), nur Hinweis und Revision des Aggregats.
- Zustellung Fahrer: der Fahrerkanal liefert nur `dispatch.run_revised`; die App erhält den Prüfstatus beim Abruf des Runs (bedingter Abruf im Aktualisierungsintervall). Ein Fahrerereignis für Prüfstatuswechsel ist nicht gebaut (siehe Offenes).
- Kanalabbruch: die Ansicht behält den zuletzt gezeigten Status mit Datenstand und kennzeichnet ihn als „möglicherweise veraltet“ (Text, nicht nur Farbe).

## Offenes

- OP-05: Wert des Aktualisierungsintervalls und Technik Push/Polling sind nicht entschieden; die Zeitmessung der Originalabnahme („im vorgesehenen Aktualisierungsintervall“) ist vor Abnahme am festgelegten Wert zu wiederholen. Der Bau ist davon nicht blockiert.
- OP-16, OP-17: Wirkung von „nicht prüfbar“ auf `UNCLEAR` und von `RESTRICTED` auf die Disposition sind Annahmen dieses Vertrags.
- Browser-/Gerätenachweis (Flutter-Widget-Test, Zeitmessung auf Gerät) nicht ausgeführt.
