# Review-Protokoll Gate Fachmodell (WP-M5-31)

Erzeugt von `tools/nachweis_p1.py fachmodell --write` am 2026-11-15T10:56:02+00:00. Gegenstand nach Kap. 08 §8.4: Review der in M1 bis M4 umgesetzten Zustandsautomaten, Entitäten sowie Preis- und Kostenregeln gegen die Detailverträge, mit Liste der offenen Geschäftsentscheidungen und der dadurch gesperrten produktiven Aktivierungen.

**Grenze (ehrlich):** Dies ist eine **Werkzeug- und Agentenprüfung** der Rückverfolgbarkeit (Vertrag, ausführbarer Test, Entscheidungsbezug), **kein Fachreview** durch benannte Personen: Reviewer sind nicht benannt (D-12). Das Werkzeug prüft nicht, ob ein Zustandsautomat fachlich richtig ist, sondern dass der Vertrag ihn nennt und ein Test ihn sichert. Das Gate Fachmodell gilt deshalb als **teilweise**.

## 1. Zustandsautomaten

96 P1-Anforderungen nennen im Detailvertrag einen Zustandsautomaten oder ein Schema. Je Zeile: Vertrag, Arbeitspaket, Automat (Auszug) und die Tests der Testfallliste, deren Name einen Zustand oder Übergang benennt (existiert in der Datei).

| Anforderung | Arbeitspaket | Zustandsautomat (Auszug aus dem Detailvertrag) | Test mit Zustandsbezug | Status |
|---|---|---|---|---|
| LH-6.1-SYS-002 | WP-M1-20 | Revision: Entwurf → geprüft → publiziert (Publikation WP-M1-22), publizierte Revision unveränderlich. | **keiner benannt** | Umgesetzt |
| LH-6.1-SYS-003 | WP-M1-25 | Revision Entwurf → geprüft → publiziert (WP-M1-22); kein eigener Automat. | **keiner benannt** | In Umsetzung |
| LH-6.1-SYS-007 | WP-M1-17 | fünf eigenständige Definitionen unter `vertraege/zustaende/` (<aggregat>.md): Fahrt (Journey-/Segment-Ausführung), Buchung (Journey-Buchung/Segment-Buchung, Kap | `backend/platform/src/test/kotlin/de/taktovia/platform/statemachine/StateMachineTransitionTest.kt::unerlaubter_uebergang_wird_mit_409_abgewiesen`; `backend/platform/src/test/kotlin/de/taktovia/platform/statemachine/StateMachineTransitionTest.kt::widerspruechliche_uebergaenge_genau_einer_gewinnt`; `backend/platform/src/test/kotlin/de/taktovia/platform/statemachine/StateMachineTransitionTest.kt::jeder_definierte_uebergang_erlaubt_mit_genau_einem_state_changed` | In Umsetzung |
| LH-6.2-IAM-001 | WP-M1-28 | Kanalstatus `UNVERIFIED` -> `VERIFIED`, Rücksprung nach `UNVERIFIED` bei Kontaktänderung. Kein eigenes Dokument unter `vertraege/zustaende/`; wird bei Bau in di | **keiner benannt** | In Umsetzung |
| LH-6.2-IAM-009 | WP-M1-10 | Sitzung `ACTIVE` -> `REVOKED` (Sperre, Abmeldung); Konto gesperrt/entsperrt. Details mit dem DDL-Vertrag. | `backend/modules/identity/src/test/kotlin/de/taktovia/identity/SessionRevocationTest.kt::gleichzeitige_sperre_und_entsperrung_definierter_endzustand` | Umgesetzt |
| LH-6.3-PAX-003 | WP-M2-39 | UI-Zustände je Browserstatus: erlaubt, abgelehnt, dauerhaft blockiert, nicht verfügbar; zusätzlich Zeitüberschreitung und ungenaue Position. Jeder Zustand mit v | **keiner benannt** | In Umsetzung |
| LH-6.3-PAX-005 | WP-M2-41 | Angebot (UI-Sicht): gültig, abgelaufen (`OFFER_EXPIRED`), geändert (`REQUOTE_REQUIRED`); Zustand „Abgelaufen“ mit erneuter Suche. Offen, ob der Angebotszustand  | **keiner benannt** | Umgesetzt |
| LH-6.3-PAX-006 | WP-M2-43 | Buchungszustand siehe OP-04 (nicht definiert); UI-Zustand „Daten veraltet“ bei unterbrochenem Strom, nach Wiederverbindung aktueller Stand. | **keiner benannt** | Umgesetzt |
| LH-6.3-PAX-008 | WP-M2-45 | `booking.state` aus WP-M2-01 (lokale Buchung, OP-4) und Ausführungszustände aus M3. Serverseitige Zuordnungstabelle (Arbeitsstand, im Detailvertrag der Zustands | `web/passenger/src/booking/BookingHistory.test.tsx::vier_bereiche_mit_anzahl_sortierung_und_leerzustand`; `web/passenger/src/booking/BookingHistory.test.tsx::leerzustand_je_bereich_und_fehlerzustand_beim_laden` | In Umsetzung |
| LH-6.3-PAX-009 | WP-M2-46 | `booking.state` (WP-M2-01, OP-4): bestätigt → storniert; Zahlung nach Kap. 03 §4.2 (Void bei nur autorisiert, Erstattung bei captured); Erstattungshinweis in zw | **keiner benannt** | Umgesetzt |
| LH-6.4-BKG-002 | WP-M2-21 | Angebot: gültig → abgelaufen/verbraucht (Hold gesetzt); Dokument in WP-M2-01 (`vertraege/zustaende/`); Angebot verspricht keine Kapazität. | `db/tests/test_ride_offer.py::test_angebot_inhalt_unveraendert_und_zustandsautomat` | Umgesetzt |
| LH-6.4-BKG-003 | WP-M2-23 | Hold `HELD → CONSUMED / RELEASED / EXPIRED`; lokale Buchung `booking.state` (OP-4), beide in WP-M2-01 unter `vertraege/zustaende/`. | **keiner benannt** | Bereit |
| LH-6.4-BKG-005 | WP-M2-26 | `booking.state` (OP-4, WP-M2-01): bestätigt → storniert; Hold `CONSUMED`/Kapazität wird freigegeben; unzulässige Übergänge mit Fehlercode. | **keiner benannt** | Bereit |
| LH-6.4-BKG-013 | WP-M2-25 | `booking.state` ist freier Text und ohne Automaten (OP-4); der Automat entsteht in WP-M2-01 (`vertraege/zustaende/booking.md`); die Historie verwendet dessen Zu | **keiner benannt** | Bereit |
| LH-6.4-BKG-014 | WP-M2-27 | Übergang nach `STORNIERT` (Name vorläufig) laut `vertraege/zustaende/booking.md` (WP-M2-01); Auslöser und Berechtigung je Verursacherklasse als Tabelle im Autom | **keiner benannt** | Bereit |
| LH-6.5-OPT-004 | WP-M2-16 | `vehicle_run.state`: neu in `vertraege/zustaende/vehicle_run.md` zu definieren (Werte im Pflichtenheft nicht festgelegt, OP-4/OP-48). | `backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/RunPlanValidatorTest.kt::zustandsuebergaenge_wie_datenbank`; `db/tests/test_route_stop.py::test_zustand_nur_ueber_erlaubte_uebergaenge` | Umgesetzt |
| LH-6.5-OPT-009 | WP-M2-22 | `capacity_hold.state`: `HELD -> CONSUMED / RELEASED / EXPIRED` (Zustandsdatei `vertraege/zustaende/capacity_hold.md` ist mit dem Bau anzulegen, das Verzeichnis  | `db/tests/test_capacity_hold_state.py::test_zustandsautomat_und_unveraenderliche_kapazitaet`; `db/tests/test_capacity_hold_state.py::test_je_referenz_hoechstens_ein_hold_und_mandantentrennung` | Umgesetzt |
| LH-6.6-DRV-001 | WP-M3-25 | [zustaende/staff_shift.md](zustaende/staff_shift.md) (Zustände geplant, angetreten, beendet, entzogen; Auslöser Dienstantritt, Dienstende, Entzug der Zuweisung; | **keiner benannt** | Umgesetzt |
| LH-6.6-DRV-004 | WP-M3-27 | Revisionsstatus der Fahrersicht (aktuell, neu unbestätigt, bestätigt, vergangen) in `vertraege/zustaende/plan_revision_fahrer.md` (zu erstellen), inklusive Rege | **keiner benannt** | Umgesetzt |
| LH-6.6-DRV-005 | WP-M3-29 | [fahrerereignis.md](zustaende/fahrerereignis.md) (erstellt mit WP-M3-29): Übergangstabelle Ereignis zu Buchungs- und Fahrtstatus (Ankunft, Einstieg, Nicht-Ersch | `backend/app/src/test/kotlin/de/taktovia/app/driver/DriverEventEffectsHttpTest.kt::checkin_parallel_zu_storno_klaerfall_kein_zustandsruecksprung`; `backend/app/src/test/kotlin/de/taktovia/app/driver/DriverEventEffectsHttpTest.kt::ausfuehrungszustand_der_fahrt_kennt_keinen_ruecksprung_auch_nicht_per_sql` | Umgesetzt |
| LH-6.6-DRV-006 | WP-M3-30 | [boarding_code.md](zustaende/boarding_code.md) (erstellt mit WP-M3-30): ausgestellt, entwertet, gesperrt, ungültig. | **keiner benannt** | Umgesetzt |
| LH-6.6-DRV-007 | WP-M3-31 | `vertraege/zustaende/fahrzeug_dienststatus.md` (zu erstellen): Übergänge und Auslöser; Anfahrt und Wartezeit aus Fahrerereignissen (DRV-005) abgeleitet, Pause/S | `backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/VehicleServiceStatusTest.kt::unzulaessiger_uebergang_abgewiesen` | In Umsetzung |
| LH-6.7-OPS-004 | WP-M3-39 | Buchungszustände aus M2 (Verweis auf M2-Automat; hier kein neuer Automat). | **keiner benannt** | Umgesetzt |
| LH-6.7-OPS-005 | WP-M3-41 | Sperrzustand je Quelle (operativ: gesetzt → aufgehoben); Fahrzeug ist zuweisbar nur, wenn keine Sperre irgendeiner Quelle besteht. Verweis `zustaende/` wird mit | **keiner benannt** | Bereit |
| LH-6.7-OPS-006 | WP-M3-42 | Hinweis: offen → erledigt (Ursache entfallen); derselbe Sachverhalt erzeugt genau einen offenen Hinweis (Dedupe). Überfälligkeit (Verspätung) und ausstehende In | `backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/operator/OverdueAlertTest.kt::ausstehende_information_ist_eigener_zustand`; `web/operator/src/alerts/OverdueFlags.test.tsx::verspaetung_und_information_ausstehend_getrennt_in_liste_und_detail_leerzustand` | Umgesetzt |
| LH-6.8-FLT-003 | WP-M3-07 | `staff_shift.state`: Zustandswerte `PLANNED`, `STARTED`, `ENDED`, `CANCELLED` (Annahme des Plans), siehe [zustaende/staff_shift.md](zustaende/staff_shift.md); n | **keiner benannt** | Umgesetzt |
| LH-6.10-PAY-003 | WP-M2-07 | Tarifrevision `ENTWURF → GEPRUEFT → VEROEFFENTLICHT` (Namen im Bau; Datei `vertraege/zustaende/tarifrevision.md` aus WP-M2-01); keine Änderung nach `VEROEFFENTL | **keiner benannt** | Umgesetzt |
| LH-6.10-PAY-004 | WP-M2-30 | `vertraege/zustaende/zahlung.md` (aus WP-M2-01) nach Kap. 03 §4.2: `CREATED → REQUIRES_ACTION → AUTHORIZED → CAPTURE_PENDING → CAPTURED`; `AUTHORIZATION_FAILED` | `backend/modules/fare-payment/src/test/kotlin/de/taktovia/farepayment/PaymentAutomatonTest.kt::zahl_und_buchungsstatus_getrennt_gespeichert_und_abrufbar`; `backend/modules/fare-payment/src/test/kotlin/de/taktovia/farepayment/PaymentAutomatonTest.kt::capture_fehler_laesst_buchung_bestaetigt`; `backend/modules/fare-payment/src/test/kotlin/de/taktovia/farepayment/PaymentAutomatonTest.kt::psp_timeout_unbestimmt_genau_eine_belastung` | Umgesetzt |
| LH-6.12-COM-001 | WP-M2-34 | `vertraege/zustaende/notification.md` (Auftrag/Versuch, COM-003); dieser Vertrag erzeugt nur den Zustand "angelegt" oder direkt "Kanalfehler". | **keiner benannt** | Umgesetzt |
| LH-6.12-COM-003 | WP-M2-32 | `vertraege/zustaende/notification.md` (anzulegen): Auftrag `angelegt -> versendet / wiederholt / endgültig fehlgeschlagen`; Versuch `gestartet -> erfolgreich /  | `backend/modules/communication-support/src/test/kotlin/de/taktovia/communicationsupport/NotificationDispatchTest.kt::zustandsuebergaenge_werden_von_der_datenbank_erzwungen_und_versuche_sind_lueckenlos`; `db/tests/test_notification.py::test_zustandsautomat_per_trigger` | Umgesetzt |
| LH-6.18-SEC-007 | WP-M1-33 | `erasure_job` (läuft/fortsetzbar/abgeschlossen/abgebrochen) und Regel (Entwurf/freigegeben) – Detail in `vertraege/zustaende/erasure_job.md` mit dem Bau; Zustan | **keiner benannt** | Umgesetzt |
| LH-6.19-NFR-001 | WP-M5-05 | Probenergebnis je Lauf: `ok`, `fehler_plattform`, `fehler_provider` (z. B. PSP-Simulator down, getrennt ausgewiesen), `keine_messung` (Probe selbst ausgefallen  | **keiner benannt** | Umgesetzt |
| LH-6.19-NFR-004 | WP-M5-13 | Verbindungszustände aus WP-M5-09; Zielmenge der Messung nur „bestehende Verbindung“; kurze Unterbrechung wird getrennt ausgewiesen. | **keiner benannt** | In Umsetzung |
| LH-6.19-NFR-005 | WP-M5-08 | Standortzustand `frisch` (Alter < 30 s), `veraltet`, `nicht_verwendbar`; veraltete Position wird nicht fortgeschrieben und nicht als Grundlage einer laufenden E | **keiner benannt** | Umgesetzt |
| LH-6.19-NFR-007 | WP-M5-15 | Zahlungsautomat aus M2 (WP-M2-30); nach Restore werden `payment_intent` und `operation` in unbestimmtem Zustand anhand der Providerreferenzen abgeglichen, bevor | **keiner benannt** | In Umsetzung |
| LH-6.19-NFR-008 | WP-M5-04 | (Arbeitsannahme aus WP-M5-04 Kriterium 3/6 und WP-M3-52, Dedupe und Quittierung) Alarm: offen -> (quittiert) -> geschlossen nach Behebung; ein anhaltender Fehle | **keiner benannt** | In Umsetzung |
| LH-6.19-NFR-009 | WP-M5-09 | Verbindungszustand (online, eingeschränkt, offline, Synchronisation läuft, Synchronisationskonflikt) mit Texten und je Zustand erlaubten/gesperrten Aktionen nac | **keiner benannt** | Bereit |
| LH-6.20-QAT-001 | WP-M5-21 | Statuswerte nach vertragsprozess.md. | **keiner benannt** | Bereit |
| LH-6.20-QAT-003 | WP-M5-23 | Drehbuch des Referenzfalls mit Fixture und erwarteten Status je Schritt (aus Zuständen M2/M3): Angebot, Buchung, Zuweisung im Fahrtenplan (`vehicle_run`), Diens | **keiner benannt** | In Umsetzung |
| LH-6.20-QAT-004 | WP-M5-24 | Verweis auf die Zustandsautomaten Buchung/Hold/Zahlung/Fahrzeug aus M2/M3 (hier keine neuen). **JSON-Schema / Ereignisse:** vorhandene `outbox`-Ereignisse; Stat | `tests/acceptance/test_negative_e2e.py::test_psp_timeout_genau_ein_endzustand_ohne_zweite_belastung` | Umgesetzt |
| LH-6.21-CHK-001 | WP-M3-09 | Einsatzfreigabe (WP-M3-08, `vertraege/zustaende/` noch festzulegen). | **keiner benannt** | Umgesetzt |
| LH-6.21-CHK-002 | WP-M3-13 | `inspection_run` (in Bearbeitung ... Abschluss in WP-M3-14); Vorlagenrevision Entwurf, freigegeben, abgelöst (WP-M3-10). Nur freigegebene Revisionen werden gewä | **keiner benannt** | Umgesetzt |
| LH-6.21-CHK-003 | WP-M3-10 | Vorlagenrevision Entwurf, freigegeben, abgelöst (`vertraege/zustaende/` noch festzulegen). | **keiner benannt** | In Umsetzung |
| LH-6.21-CHK-004 | WP-M3-16 | Prüflauf: in Arbeit → abgeschlossen (Ergebnis `FREIGABEFÄHIG` / `GESPERRT`); Sperre wird aus dem Submit abgeleitet. Zustandsautomat `zustaende/inspection_run.md | **keiner benannt** | Umgesetzt |
| LH-6.21-CHK-005 | WP-M3-11 | Vorlagenrevision: Entwurf → freigegeben → abgelöst (WP-M3-10, `zustaende/checklist_template_revision.md`). | **keiner benannt** | Umgesetzt |
| LH-6.21-CHK-006 | WP-M3-11 | Vorlagenrevision (WP-M3-10) und Prüflauf (`zustaende/inspection_run.md`, WP-M3-14). | **keiner benannt** | Umgesetzt |
| LH-6.21-CHK-007 | WP-M3-12 | Prüflauf (`zustaende/inspection_run.md`); Semantik „eingeschränkt“ im Ressourcenvektor laut OP-17. | **keiner benannt** | Umgesetzt |
| LH-6.21-CHK-008 | WP-M3-17 | Antwort: erfasst → Überprüfung erforderlich → korrigiert / begründet freigegeben; Prüflauf kann bei offener Überprüfung nicht abgeschlossen werden (`zustaende/i | **keiner benannt** | Umgesetzt |
| LH-6.21-CHK-009 | WP-M3-18 | Prüflauf; optional Ergebnis „technisch ungeklärt“, sofern die Fachseite das Element so einstuft (OP-16). | **keiner benannt** | Umgesetzt |
| LH-6.21-CHK-010 | WP-M3-19 | Prüflauf (`zustaende/inspection_run.md`); Einsatzfreigabe nur bei Qualifikation gültig und Bestätigung `JA`. | **keiner benannt** | Bereit |
| LH-6.21-CHK-011 | WP-M3-20 | DefectCase (Anfangsstatus „neu" laut WP-M3-43); Klassifikation/Sperre nicht Teil dieses Vertrags (WP-M3-45/-46). | **keiner benannt** | Umgesetzt |
| LH-6.21-CHK-012 | WP-M3-21 | Prüflauf (WP-M3-14): Abschluss setzt vollständige Quittierung voraus; Altschaden-Status ändert die Quittierung nicht. | `backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/KnownDamageAcknowledgementTest.kt::leerzustand_ohne_quittierungspflicht` | Umgesetzt |
| LH-6.21-CHK-013 | WP-M3-14 | Prüflauf (neu zu schreiben unter `vertraege/zustaende/pruefung.md`): in Bearbeitung -> abgeschlossen mit Ergebnis bestanden / eingeschränkt / gesperrt / technis | **keiner benannt** | Umgesetzt |
| LH-6.21-CHK-014 | WP-M3-15 | Prüflauf: „in Bearbeitung" (Entwurf änderbar) -> „abgeschlossen" (nur Nachträge). | **keiner benannt** | Umgesetzt |
| LH-6.21-CHK-015 | WP-M3-22 | Prüflauf/Freigabe (`vertraege/zustaende/pruefung.md`): nach Wechsel Ersatzfahrzeug „Prüfung ausstehend" -> bestätigt -> freigegeben. | **keiner benannt** | Umgesetzt |
| LH-6.21-CHK-016 | WP-M3-23 | Prüflauf/Freigabe, `vertraege/zustaende/pruefung.md` (Statuswerte als Projektion). | `backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/InspectionStatusProjectionTest.kt::sechs_statuswerte_eindeutig_aus_serverzustand` | In Umsetzung |
| LH-6.22-DMG-001 | WP-M3-43 | DefectCase, Anfangsstatus „neu" (Weiteres WP-M3-45/-46). | **keiner benannt** | Bereit |
| LH-6.22-DMG-002 | WP-M3-43 | Anfangsstatus `neu`; Übergänge siehe LH-6.22-DMG-007. | **keiner benannt** | Bereit |
| LH-6.22-DMG-004 | WP-M3-44 | Medienprüfung (`hochgeladen` → `in Prüfung` → `freigegeben` / `abgewiesen`) als Arbeitsnamen, mit dem Uploadbaustein aus WP-M4-21 abzustimmen; Akte selbst siehe | **keiner benannt** | Bereit |
| LH-6.22-DMG-005 | WP-M3-45 | Klasse ist unabhängig vom Akten-Status (DMG-007); Herabstufung hebt die Sperre nicht auf, Wiederfreigabe nur über WP-M3-51. | **keiner benannt** | Umgesetzt |
| LH-6.22-DMG-006 | WP-M3-46 | Fahrzeug `nicht disponierbar` über Sperrkern WP-M3-08; Alarm-Zustandsautomat aus WP-M3-52; bestehende Zuweisungen gehen in den Störungsablauf über (Ersatzdispos | **keiner benannt** | Umgesetzt |
| LH-6.22-DMG-007 | WP-M3-47 | `docs/umsetzungsplan/vertraege/zustaende/defect_case.md` (neu anzulegen, im Rahmen aus WP-M1-17). Zulässige Hauptfolge: `neu` → `geprüft` → `in Bearbeitung` → ` | **keiner benannt** | Bereit |
| LH-6.22-DMG-010 | WP-M3-54 | Störungsfall (Ablauf und Zustände) ist als Detailvertrag vorab gefordert und noch nicht erstellt: `vertraege/zustaende/stoerungsfall.md` (angelegt mit WP-M3-54; | **keiner benannt** | Umgesetzt |
| LH-6.23-SCH-001 | WP-M4-18 | Zustände Entwurf/veröffentlicht aus WP-M4-04; Lesemodell-Schema ist ein Detailvertrag-Ergebnis von WP-M4-18. | **keiner benannt** | Umgesetzt |
| LH-6.23-SCH-002 | WP-M4-03 | `vertraege/zustaende/dienstplan.md` (aus WP-M4-04). | **keiner benannt** | Umgesetzt |
| LH-6.23-SCH-003 | WP-M4-19 | `vertraege/zustaende/dienstplan.md`. **JSON-Schema / Ereignisse:** keine neuen. **Parameter und Kennzahlen:** keine. | **keiner benannt** | In Umsetzung |
| LH-6.23-SCH-004 | WP-M4-04 | `vertraege/zustaende/dienstplan.md` (Entwurf, veröffentlicht, ersetzt; Übergänge, Auslöser, Fehler) mit Sichtbarkeitsmatrix Rolle × Zustand; entsteht in WP-M4-0 | **keiner benannt** | Umgesetzt |
| LH-6.23-SCH-005 | WP-M4-14 | Dienstplan Entwurf -> geprüft -> veröffentlicht (Zustandsdatei `vertraege/zustaende/dienstplan.md` ist mit WP-M4-01 anzulegen; Übergang Entwurf -> veröffentlich | **keiner benannt** | Umgesetzt |
| LH-6.23-SCH-007 | WP-M4-17 | Schicht offen / besetzt, Entwurf / veröffentlicht (siehe `vertraege/zustaende/dienstplan.md`, WP-M4-01). | **keiner benannt** | Umgesetzt |
| LH-6.23-SCH-008 | WP-M4-05 | Zustand „offen“ der Schicht (`vertraege/zustaende/dienstplan.md`). | **keiner benannt** | Umgesetzt |
| LH-6.23-SCH-010 | WP-M4-15 | Revision: Entwurf -> veröffentlicht (final, unveränderlich). | **keiner benannt** | Umgesetzt |
| LH-6.24-TIM-002 | WP-M4-11 | Arbeitszeit: nicht gestartet, Arbeit läuft, Pause, beendet; Übergänge Kommen, Pausenbeginn, Pausenende, Gehen. Datei `vertraege/zustaende/time_event.md` wird mi | **keiner benannt** | Bereit |
| LH-6.24-TIM-003 | WP-M4-12 | Zustand des Stempels in der lokalen Queue: lokal („nicht synchronisiert“), synchronisiert; serverseitig Ergebnis APPLIED, DUPLICATE, REVIEW_REQUIRED. Datei `ver | **keiner benannt** | Bereit |
| LH-I.1-DOC-001 | WP-M4-25 | Beleg: Entwurf, danach Prüfung/Freigabe (nicht Gegenstand; siehe WP-M4-22/-23). Datei `vertraege/zustaende/expense_document.md`, falls vorhanden. | **keiner benannt** | Umgesetzt |
| LH-I.1-DOC-002 | WP-M4-27 | `extraction_run`: angestoßen, läuft, abgeschlossen, fehlgeschlagen (Wiederholung ohne Doppellauf); Feld: Vorschlag, bestätigt, korrigiert. Datei unter `vertraeg | **keiner benannt** | In Umsetzung |
| LH-I.1-DOC-003 | WP-M4-28 | Feldstatus `PROPOSED -> CONFIRMED / CORRECTED`; kein Rückweg ohne neue Revision. Belegzustand gemäß `vertraege/zustaende/` (Freigabe-Workflow, WP-M4-34). | `backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/OcrConfirmationTest.kt::rechte_mandant_revision_und_zustand_der_freigabepruefung` | In Umsetzung |
| LH-I.1-DOC-004 | WP-M4-29 | Freigabesperre „Kostenzuordnung fehlt“ als Guard im Freigabe-Workflow (WP-M4-34). | **keiner benannt** | Umgesetzt |
| LH-I.1-DOC-005 | WP-M4-30 | Freigabesperre „Pflichtwert fehlt“ im Freigabe-Workflow (WP-M4-34); Übergabe als Tankvorgang an das Kostenereignis (WP-M4-33). | **keiner benannt** | Umgesetzt |
| LH-I.1-DOC-006 | WP-M4-22 | Belegzustand beginnt als Entwurf (`vertraege/zustaende/` zum Beleg, WP-M4-01). | **keiner benannt** | Umgesetzt |
| LH-I.1-DOC-007 | WP-M4-23 | Ein gebuchter Beleg (`POSTED`) erlaubt keine freie Betragsbearbeitung (Kap. 06 §6.6); Korrektur nur über Gegenereignis (WP-M4-36). | **keiner benannt** | Umgesetzt |
| LH-I.1-DOC-008 | WP-M4-24 | Beleg mit Integritätsverletzung: Freigabesperre, kein Posting (`vertraege/zustaende/` zum Beleg). | **keiner benannt** | Umgesetzt |
| LH-I.1-DOC-009 | WP-M4-32 | Kandidat `OFFEN -> GLEICHE_EVIDENZ / EIGENER_VORGANG`; offener Kandidat blockiert die Freigabe. Sicherer Treffer wird als weitere Evidenz desselben Ereignisses  | **keiner benannt** | Umgesetzt |
| LH-I.1-DOC-010 | WP-M4-34 | Ein verweigerter Versuch (403) schreibt keinen `approval_step`, setzt weder `approved_by` noch `state`. Beleg in `vertraege/zustaende/beleg.md` (wird mit dem Ba | **keiner benannt** | Umgesetzt |
| LH-I.1-DOC-011 | WP-M4-35 | `allocation_set`: `DRAFT` → `POSTED`; eine neue Revision ersetzt die aktive über `allocation_activation` atomar; `POSTED` ist final. | **keiner benannt** | Umgesetzt |
| LH-I.1-DOC-012 | WP-M4-31 | Hinweis `OPEN` → `ACKNOWLEDGED`; ein offener Hinweis blockiert die finanzielle Freigabe (Problem-Code in WP-M4-34) oder führt in einen ausdrücklich freizugebend | **keiner benannt** | Umgesetzt |
| LH-I.1-DOC-013 | WP-M4-26 | Fahrer-App-Entwurf `lokal` → `synchronisiert`; serverseitig Beleg im Zustandsautomaten aus WP-M4-34 (`vertraege/zustaende/beleg.md`). | `mobile/driver/test/offline_beleg_sync_test.dart::hash_konflikt_und_upload_abbruch_zeigen_fehlerzustand_mit_wiederaufnahme` | Umgesetzt |
| LH-I.2-FCO-002 | WP-M4-40 | manuelles Kostenereignis nutzt den Beleg-Automaten aus WP-M4-34 (`vertraege/zustaende/beleg.md`). | `web/operator/src/kosten/Fahrzeugkosten.test.tsx::monatsmatrix_kostenart_mal_monat_leerzustand_und_nur_lesen_ohne_recht` | Umgesetzt |
| LH-I.2-FCO-003 | WP-M4-41 | Konflikt `OPEN` → `RESOLVED` (nur durch protokollierte Entscheidung). | `backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/OdometerRegisterTest.kt::aufloesung_ohne_grund_422_mit_akteur_grund_protokolliert_keine_automatische_auswahl` | Umgesetzt |
| LH-I.4-MUL-003 | WP-M1-05 | Mandantenstatus `ACTIVE`, `SUSPENDED`, `CLOSED` (Übergangsregeln im Detail im Bau; Dokument unter `vertraege/zustaende/tenant.md` anzulegen). | **keiner benannt** | Umgesetzt |
| LH-I.4-MUL-005 | WP-M1-35 | Freigabe: geplant (vor `valid_from`) → aktiv → abgelaufen / widerrufen (Zustand aus Zeit und `revoked_at` abgeleitet; Detail in `zustaende/driver-release.md` mi | **keiner benannt** | In Umsetzung |
| LH-I.6-KPI-010 | WP-M4-46 | Definition: Entwurf -> freigegeben (danach unveränderlich). | **keiner benannt** | Umgesetzt |
| LH-I.7-CFG-001 | WP-M1-24 | Feldliste als Schema mit dem Bau (Pflichtfelder offen, siehe OP-27). **Ereignisse:** `tenant.profile_changed`; Audit mit Akteur und Zeitpunkt über WP-M1-46. | **keiner benannt** | Umgesetzt |
| LH-I.7-CFG-003 | WP-M1-20 | siehe SYS-002. **Ereignisse:** `config.revision_created`; Audit über WP-M1-46. | **keiner benannt** | In Umsetzung |
| LH-I.7-CFG-006 | WP-M1-22 | neu `zustaende/config-revision.md`: Entwurf, validiert, freigegeben, publiziert, abgelöst; Rollback erzeugt neue Revision mit früheren Werten. | **keiner benannt** | Umgesetzt |
| LH-I.7-CFG-007 | WP-M1-23 | Bericht ist an eine unveränderte Revision gebunden; Änderung der Revision invalidiert den Bericht. | **keiner benannt** | In Umsetzung |
| LH-I.8-AUT-001 | WP-M3-53 | Incident-Zustandsautomat (Detailvertrag vorab, noch zu erstellen: `vertraege/zustaende/incident.md`); Mindestzustände: offen (auch bei fehlgeschlagener Suche),  | `db/tests/test_workflow_regelrevision.py::test_incident_zustandsautomat_und_identitaet_in_der_datenbank` | Umgesetzt |

Ohne benannten Zustandstest: 77 von 96 (kein Befund an sich: der Zustand kann durch Tests ohne Zustandsbezug im Namen gesichert sein, ist aber hier nicht belegt).

## 2. Entitäten sowie Preis- und Kostenregeln

Die folgenden Regeln sind durch Datenbank- oder Anwendungstests gesichert; die Tabelle weist die Prüfdatei aus (Existenz vom Werkzeug geprüft).

| Regel | Prüfdatei | Vorhanden |
|---|---|---|
| Geld ohne Gleitkomma (bigint in kleinster Einheit, Währung mit Exponent) | `db/tests/test_geld_zeit_spalten.py` | ja |
| Preis: Angebotspreis, Inhaltshash und Bindung (fare_quote) | `db/tests/test_fare_quote.py` | ja |
| Preis: Tarifrevisionen und Preisregeln | `db/tests/tarif_helpers.py` | ja |
| Kosten: Kostenereignis und Journal, Gegenereignis statt Korrektur | `db/tests/test_cost_event_journal.py` | ja |
| Kosten: Allokation centgenau | `db/tests/test_allocation.py` | ja |
| Kosten: Fiskalperiode und späte Gutschrift | `db/tests/test_fiscal_period.py` | ja |
| Zahlung: Zahlungsautomat und unbestimmte Zustände | `db/tests/payment_helpers.py` | ja |
| Kapazität: Hold-Zustandsautomat und Zusagefenster | `db/tests/test_capacity_hold_state.py` | ja |
| Entitäten: Dateninventar je Tabelle und Spalte | `db/tests/test_07_dateninventar.py` | ja |
| Entitäten: Mandantenschlüssel und Zeilenschutz | `db/tests/test_03_rls.py` | ja |

## 3. Offene Geschäftsentscheidungen und gesperrte produktive Aktivierungen

Aus den Entscheidungszeilen der P1-Arbeitspakete (`docs/umsetzungsplan/wp/M1` bis `M5`) und den Beschlussprotokollen (`docs/umsetzungsplan/beschluesse`, derzeit keines). **Jede ungeklärte Geld- oder Beförderungsrolle (D-01, D-03, D-04, D-06, D-07) verhindert ihre produktive Aktivierung:**

| Entscheidung | Gegenstand | Stand | Gesperrte produktive Aktivierung | Arbeitspakete mit Bezug |
|---|---|---|---|---|
| D-01 | Wer verkauft, befördert und verantwortet die Reisekette? | offen (kein Beschluss protokolliert) | Verkauf, Beförderung und Verantwortung der Reisekette: keine produktive Mehrbetreiber- oder Partnerbuchung, keine Zusage fremder Beförderungsleistung | WP-M1-05, WP-M1-34, WP-M1-35, WP-M5-25, WP-M5-31 |
| D-02 | Wie stark müssen Mandanten technisch isoliert werden? | offen (kein Beschluss protokolliert) | Grad der Mandantenisolation über den Stand der P1-Isolation hinaus: kein Mehrbetreiberbetrieb auf gemeinsamer Plattform | WP-M1-01, WP-M1-11, WP-M3-02, WP-M4-58, WP-M4-64, WP-M5-17 |
| D-03 | Gemeinsamer Checkout und Geldfluss | offen (kein Beschluss protokolliert) | Gemeinsamer Checkout und Geldfluss, PSP: keine produktive Zahlung und keine Auszahlung (Simulator statt PSP-Sandbox) | WP-M1-37, WP-M2-01, WP-M2-29, WP-M2-30, WP-M2-31, WP-M2-42, WP-M5-15, WP-M5-23, WP-M5-24, WP-M5-28, WP-M5-31 |
| D-04 | Anschlussrisiko und unvollständige Mehrbetreiberbuchung | offen (kein Beschluss protokolliert) | Anschlussrisiko und unvollständige Mehrbetreiberbuchung: keine produktive Mehrbetreiberbuchung | – |
| D-05 | Pilot, Peaklast, SLA und Betrieb | offen (kein Beschluss protokolliert) | Pilot, Peaklast, SLA und Betrieb: kein Bau der Produktionsumgebung beim Zielanbieter, keine verbindlichen Lastziele | WP-M1-04, WP-M1-09, WP-M1-19, WP-M1-25, WP-M1-36, WP-M1-39, WP-M1-40, WP-M1-41, WP-M1-42, WP-M2-01, WP-M2-02, WP-M2-04 |
| D-06 | Finanzumfang und ERP-Anbindung | offen (kein Beschluss protokolliert) | Finanzumfang und ERP-Anbindung: keine produktive Finanzbuchung und kein produktiver ERP-Export | WP-M4-01, WP-M4-02, WP-M4-22, WP-M4-27, WP-M4-29, WP-M4-33, WP-M4-34, WP-M4-36, WP-M4-37, WP-M4-63, WP-M5-07, WP-M5-31 |
| D-07 | Einheitliche Kostenbewertung und interne Verrechnung | offen (kein Beschluss protokolliert) | Einheitliche Kostenbewertung und interne Verrechnung: keine produktive Kostenumlage zwischen Rechtsträgern | WP-M4-01, WP-M4-33, WP-M4-35, WP-M4-39, WP-M4-40, WP-M4-42, WP-M4-43, WP-M4-44, WP-M4-56, WP-M4-63, WP-M5-07, WP-M5-31 |
| D-08 | Partnerstandards und tatsächliche Buchungsrechte | offen (kein Beschluss protokolliert) | Partnerstandards und tatsächliche Buchungsrechte: alle Partnerfähigkeiten bleiben deaktiviert | – |
| D-09 | Routing, Karten und Telematik | offen (kein Beschluss protokolliert) | Routing, Karten und Telematik: keine produktive Fußwegberechnung und Routing-Quelle | WP-M2-01, WP-M2-10, WP-M2-38, WP-M2-40, WP-M2-44, WP-M3-01, WP-M3-28, WP-M3-34, WP-M3-36, WP-M3-56, WP-M4-41, WP-M4-42 |
| D-10 | Fahrerdaten, ETA und Mitbestimmung | offen (kein Beschluss protokolliert) | Fahrerdaten, ETA und Mitbestimmung: keine Aktivierung der Geozone und keine fahrerbezogene Auswertung | WP-M1-32, WP-M1-35, WP-M1-42, WP-M3-05, WP-M3-19, WP-M3-34, WP-M4-01, WP-M4-11, WP-M4-13, WP-M4-61, WP-M4-62, WP-M4-63 |
| D-11 | Aufbewahrung, Datenrechte und Notbetrieb | offen (kein Beschluss protokolliert) | Aufbewahrung, Datenrechte und Notbetrieb: keine Produktivfreigabe personenbezogener Daten, keine fristgesteuerten Löschläufe | WP-M1-12, WP-M1-28, WP-M1-30, WP-M1-31, WP-M1-32, WP-M1-33, WP-M1-41, WP-M1-42, WP-M1-46, WP-M2-11, WP-M2-23, WP-M2-25 |
| D-12 | Reihenfolge der Lieferung und Quellbereinigung | offen (kein Beschluss protokolliert) | Reihenfolge der Lieferung und Quellbereinigung: keine Abnahmeentscheidung, keine benannten Reviewer | WP-M1-45, WP-M2-48, WP-M3-39, WP-M5-21, WP-M5-29, WP-M5-31 |

## 4. Ergebnis

- Gate Fachmodell: **teilweise**. Rückverfolgbarkeit der Zustandsautomaten (19 von 96 mit benanntem Zustandstest) und der Regeln aus Abschnitt 2 ist belegt; ein Fachreview durch benannte Personen steht aus.
- Die produktive Aktivierung von Geld- und Beförderungsfunktionen bleibt gesperrt, bis D-01, D-03, D-04, D-06 und D-07 beschlossen und protokolliert sind (Vorlage `docs/umsetzungsplan/beschluesse/_vorlage.md`).
