---
dokument_version: 0.1
abnahme_build: ENTWURF-W18-9d035b7
sprache: de
stand: 2026-11-15
arbeitspaket: WP-M5-27
---

# Standardbetrieb und Demonstration

**Zweck:** Dieses Dokument legt fest, **was** der Abnehmer als Standardbetrieb demonstriert (Definition der Abläufe, OP-19), und gibt die Schrittfolge, auf die sich die Demonstration stützt. Die Demonstration erfolgt **allein mit den gelieferten Materialien** (Betriebshandbuch, [Administratorhandbuch](administratorhandbuch.md), [Fahrerschulung](fahrerschulung.md), [Datenschutzdokumentation](datenschutzdokumentation.md)), ohne Unterstützung durch das Entwicklungsteam. Abweichungen werden im Protokoll festgehalten, nicht mündlich behoben.
**Bezug:** Repository-Stand `9d035b7` (siehe [Übergabeprotokoll](uebergabeprotokoll.md)).

## Inhalt

1. [Regeln der Demonstration](#1-regeln-der-demonstration)
2. [Abläufe des Standardbetriebs](#2-abläufe-des-standardbetriebs)
3. [Schrittfolge je Ablauf](#3-schrittfolge-je-ablauf)
4. [Protokoll](#4-protokoll)
5. [Stand der Vorbereitung und Grenzen](#5-stand-der-vorbereitung-und-grenzen)

## 1. Regeln der Demonstration

1. Die Demonstration läuft in der Schulungs- bzw. Abnahmeumgebung mit synthetischen Daten (Referenzwelt, siehe Fahrerschulung Abschnitt 1), nie mit Produktionsdaten.
2. Der Abnehmer führt die Schritte selbst aus, sucht jede Information in den Handbüchern und fragt nicht das Entwicklungsteam. Eine Rückfrage wird als Abweichung protokolliert, ebenso jeder fehlende, falsche oder unklare Schritt.
3. Alle sechs Abläufe aus Abschnitt 2 werden in dieser Reihenfolge durchlaufen. Ergebnis je Ablauf: „bestanden“, „bestanden mit Abweichung“ oder „nicht bestanden“.
4. Befunde werden behoben und der betroffene Ablauf wird auf dem neuen Stand wiederholt; es werden keine Stände gemischt (ein Protokoll gehört zu genau einem Abnahme-Build).

## 2. Abläufe des Standardbetriebs

| ID | Ablauf | Handbuchkapitel | Oberfläche oder Schnittstelle | Ersatznachweis |
|---|---|---|---|---|
| SB-01 | Benutzer und Fahrzeug anlegen | [Benutzer und Rollen](administratorhandbuch.md#2-benutzer-und-rollen), [Fahrzeuge](administratorhandbuch.md#5-fahrzeuge) | **Kooperation und Rollen**{{nav:/admin/kooperation}}, **Flotte und Werkstatt**{{nav:/flotte}}, {{api:createMembership}}, {{api:createVehicleMaster}} | `web/operator/src/identity/MembershipAdmin.test.tsx`, `web/operator/src/flotte/Fahrzeugstamm.test.tsx`, `tests/acceptance/test_stammdaten_migration.py` |
| SB-02 | Dienst und Tarif prüfen | [Dienste, Gebiete und Haltepunkte](administratorhandbuch.md#3-dienste-gebiete-und-haltepunkte), [Tarife](administratorhandbuch.md#4-tarife) | **Gebiete, Stops, Tarife**{{nav:/admin/gebiete}}, {{api:getEffectiveTariff}} | `web/operator/src/tarife/Tarife.test.tsx`, `web/operator/src/config/DienstKonfiguration.test.tsx` |
| SB-03 | Fahrer startet Dienst mit Pflichtprüfung | [Dienststart und Pflichtprüfung](fahrerschulung.md#3-dienststart-und-pflichtprüfung) | **Dienst antreten**{{app:shift.start}}, **Pflichtpruefung starten**{{app:inspection.start}}, {{api:startDriverShift}} | `mobile/driver/test/pruefung/release_gate_test.dart` (nicht ausgeführt, kein Flutter), `tests/acceptance/e2e/referenzfall.py` |
| SB-04 | Buchung wird disponiert und gefahren | [Stoppliste und Navigation](fahrerschulung.md#4-stoppliste-und-navigation), [Check-in, Ausstieg und No-Show](fahrerschulung.md#5-check-in-ausstieg-und-no-show) | **Auftragsliste**{{nav:/leitstelle/auftraege}}, {{api:createRideOffer}}, {{api:confirmBooking}}, {{api:recordDriverEvent}} | `tests/acceptance/e2e/test_referenzfall_staging.py`, `tests/acceptance/e2e/referenzfall.py` |
| SB-05 | Leitstelle überwacht | [Alarmierung und Eskalation](../betrieb/betriebshandbuch.md#6-alarmierung-und-eskalation) | **Livebetrieb: Fahrzeuge**{{nav:/leitstelle/fahrzeuge}}, **Offene Störungen**{{nav:/leitstelle/stoerungen}} | `web/operator/src/vehicles/VehicleList.test.tsx`, `web/operator/src/alarm/Arbeitsliste.test.tsx` |
| SB-06 | Alarm wird nach Runbook behandelt | [Runbooks je Alarm](../betrieb/betriebshandbuch.md#7-runbooks-je-alarm) | **Vorfälle und Ablauf**{{nav:/leitstelle/vorfaelle}}, {{api:acknowledgeOperatorAlert}} | `tests/acceptance/test_betriebshandbuch.py`, `docs/betrieb/alarm-handlungsschritte.md` |

Die Spalte „Ersatznachweis“ nennt automatisierte Tests, die das Verhalten des Builds hinter dem jeweiligen Ablauf prüfen. Sie ersetzen die Demonstration **nicht**; sie zeigen nur, dass die beschriebenen Schritte technisch tragen.

## 3. Schrittfolge je Ablauf

### SB-01 Benutzer und Fahrzeug anlegen

1. Als `tenant_admin` anmelden, **Kooperation und Rollen**{{nav:/admin/kooperation}} öffnen, für ein Schulungskonto die Rolle `driver` erteilen (Administratorhandbuch, Abschnitt 2).
2. **Flotte und Werkstatt**{{nav:/flotte}} öffnen, ein Fahrzeug mit Kennung, Typ und Plätzen anlegen und dem Dienst zuordnen (Abschnitt 5).
3. Erwartung: Das Konto erscheint als aktive Mitgliedschaft; das Fahrzeug erscheint im Stamm und ist noch nicht einsatzbereit.

### SB-02 Dienst und Tarif prüfen

1. **Gebiete, Stops, Tarife**{{nav:/admin/gebiete}} öffnen; Dienst, Bediengebiet, Haltepunkte und Betriebszeiten prüfen (Abschnitt 3).
2. Unter den Tarifen die wirksame Tarifrevision prüfen: Gültigkeit und Tarifklasse (Abschnitt 4).
3. Erwartung: Dienst und Tarif sind wirksam; eine veröffentlichte Revision ist nicht änderbar, nur ersetzbar.

### SB-03 Fahrer startet Dienst mit Pflichtprüfung

1. In der Fahrer-App anmelden, **Dienst antreten**{{app:shift.start}}, **Pflichtpruefung starten**{{app:inspection.start}} und die Prüfung durchlaufen (Fahrerschulung, Abschnitt 3).
2. Erwartung: erst nach Serverbestätigung „Freigegeben“. Mit einem kritischen Mangel entsteht stattdessen eine Fahrzeugsperre (Fahrerschulung, 3.1).

### SB-04 Buchung wird disponiert und gefahren

1. Als Disponent die Buchung in der **Auftragsliste**{{nav:/leitstelle/auftraege}} finden; der Fahrer sieht sie in der **Stoppliste**{{app:run.title}}.
2. Der Fahrer erfasst Ankunft, Check-in und Ausstieg (Fahrerschulung, Abschnitt 5).
3. Erwartung: Die Buchung läuft über bestätigt zu abgeschlossen; die Leitstelle sieht den Fortschritt.

### SB-05 Leitstelle überwacht

1. **Livebetrieb: Fahrzeuge**{{nav:/leitstelle/fahrzeuge}} öffnen: Zustand, letzte Position, Standortalter, Einsatzfreigabe und Prüfstatus je Fahrzeug.
2. **Offene Störungen**{{nav:/leitstelle/stoerungen}} und **Überfälligkeit und Hinweise**{{nav:/leitstelle/hinweise}} prüfen.
3. Erwartung: Veraltete Positionen sind gekennzeichnet; keine Fremdmandantendaten.

### SB-06 Alarm wird nach Runbook behandelt

1. Einen Alarm aus der Alarmkette (zum Beispiel Redis-Ausfall, Übungsalarm) im Betriebshandbuch, Abschnitt 7, nachschlagen und das Runbook Schritt für Schritt abarbeiten (Rolle je Schritt beachten, kein Datenbankzugriff).
2. In der Leitstelle den Vorfall unter **Vorfälle und Ablauf**{{nav:/leitstelle/vorfaelle}} verfolgen und bearbeiten.
3. Erwartung: Der Alarm ist quittiert, die Nacharbeit nach Runbook vermerkt, Abweichungen stehen im Protokoll.

## 4. Protokoll

Das Protokoll wird nach [vorlagen/demonstrationsprotokoll.md](vorlagen/demonstrationsprotokoll.md) geführt und mit `python3 tools/check_dokumentationspaket.py --protokoll DATEI` auf Vollständigkeit geprüft (Kopf, ein Ergebnis je Ablauf, Beschreibung jeder Abweichung). Abgelegte Protokolle liegen in `docs/dokumentation/protokolle/`.

## 5. Stand der Vorbereitung und Grenzen

- **Nicht ausgeführt:** Die Demonstration durch den Abnehmer fand **nicht** statt; es gibt in dieser Umgebung keinen Abnehmer, keine Schulungsumgebung und keine Geräte mit der Fahrer-App. Das Protokollverzeichnis enthält deshalb kein Abnahmeprotokoll.
- **Trockenlauf:** `tools/check_dokumentationspaket.py` prüft, dass jeder Ablauf auf ein Handbuchkapitel, auf Oberflächen und Schnittstellen verweist, die im Build existieren, und auf vorhandene Ersatznachweise. Das belegt die Auffindbarkeit, nicht die Verständlichkeit.
- **Bekannte Abweichungen zwischen Handbuch und Build** (aus dem Abgleich): Rollback von Konfigurationsrevisionen hat im Operator-Web kein Bedienelement (Administratorhandbuch A-01); Check-in, Ausstieg und No-Show haben in der Fahrer-App noch keinen verdrahteten Bildschirm (Fahrerschulung F-01); die Fahrer-App ist im Rahmen noch nicht mit Anmeldung und Synchronisation verdrahtet (F-02). Diese Punkte würden die Demonstration von SB-03 und SB-04 am Gerät zu Abweichungen führen; sie sind vor der Demonstration zu schließen oder mit dem Auftraggeber als bekannte Abweichung freizugeben.
- **Durchlauf durch eine projektfremde Person:** nicht durchgeführt; einziger Ersatz ist die maschinelle Prüfung auf Auffindbarkeit und Vollständigkeit (Test `test_standardbetrieb_demonstration_mit_protokoll`).
