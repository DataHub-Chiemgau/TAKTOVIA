---
dokument_version: 0.1
abnahme_build: ENTWURF-W18-9d035b7
sprache: de
stand: 2026-11-15
arbeitspaket: WP-M5-27
---

# Fahrerschulung TAKTOVIA Fahrer-App

**Zielgruppe:** Fahrerinnen und Fahrer sowie die Schulungsleitung des Betreibers.
**Bezug:** Fahrer-App `mobile/driver` des Repository-Stands `9d035b7` (siehe [Übergabeprotokoll](uebergabeprotokoll.md)). Die Beschriftungen der App stehen in `mobile/driver/lib/ui/strings.dart` und werden von `tools/check_dokumentationspaket.py` gegen diesen Quelltext abgeglichen; die App schreibt ihre Texte ohne Umlaute (Beispiel „Pruefung“), dieses Dokument mit Umlauten.
**Wichtiger Hinweis zum Stand:** Die Fahrer-App wurde in dieser Umgebung nicht gebaut und nicht ausgeführt (kein Flutter). Alles Folgende beschreibt das im Quelltext festgelegte Verhalten. Was dort noch kein Bedienelement hat, steht in [Abweichungen zwischen Schulung und Build](#11-abweichungen-zwischen-schulung-und-build).

## Inhalt

1. [Schulungsumgebung und Übungsdaten](#1-schulungsumgebung-und-übungsdaten)
2. [Anmelden](#2-anmelden)
3. [Dienststart und Pflichtprüfung](#3-dienststart-und-pflichtprüfung)
4. [Stoppliste und Navigation](#4-stoppliste-und-navigation)
5. [Check-in, Ausstieg und No-Show](#5-check-in-ausstieg-und-no-show)
6. [Schadenmeldung](#6-schadenmeldung)
7. [Offlineverhalten](#7-offlineverhalten)
8. [Beleg erfassen und Arbeitszeit stempeln](#8-beleg-erfassen-und-arbeitszeit-stempeln)
9. [Ausfall der App und Notbetrieb](#9-ausfall-der-app-und-notbetrieb)
10. [Übungsablauf](#10-übungsablauf)
11. [Abweichungen zwischen Schulung und Build](#11-abweichungen-zwischen-schulung-und-build)

## 1. Schulungsumgebung und Übungsdaten

Geübt wird in einer **Schulungsumgebung** mit **synthetischen Daten**: ein eigener Schulungsmandant, erfundene Fahrerinnen und Fahrer (Konten der Domäne `schulung.invalid`), erfundene Fahrgäste und Fahrzeuge. Echte Fahrgast- oder Personaldaten gehören nie in die Schulung. Die Schulungsumgebung wird nach dem Muster der Referenzwelt aufgebaut (`tests/acceptance/e2e/referenzwelt.py`: zwei Mandanten, eingespielte Stammdaten, Fahrzeug, Schicht, Umlauf); die Einrichtung durch das Betriebspersonal beschreibt das [Betriebshandbuch](../betrieb/betriebshandbuch.md#3-umgebungen). **Nicht ausgeführt:** eine Schulungsumgebung mit der Fahrer-App auf Geräten gab es in der Entwicklungsumgebung nicht; die Übungen in Abschnitt 10 sind als Ablauf festgelegt, aber noch mit keiner Fahrerin und keinem Fahrer durchgeführt.

Vor jeder Übung bestätigt die Schulungsleitung: Schulungsmandant gewählt, Schicht und Fahrzeug für die Übung zugewiesen, Prüfvorlage veröffentlicht, Gerät mit Netz.

## 2. Anmelden

1. Öffnen Sie die App **TAKTOVIA Fahrer**{{app:app.title}}.
2. Wählen Sie **Anmelden**{{app:login.button}}. Ohne Anmeldung zeigt die App „Bitte anmelden, um Ihren Dienst zu sehen.“ und keine Aufträge.
3. Läuft die Sitzung ab oder wird sie beendet, erscheint „Ihre Sitzung ist abgelaufen. Bitte erneut anmelden.“ bzw. „Ihre Sitzung wurde beendet. Bitte erneut anmelden.“ Offene Ereignisse bleiben verschlüsselt auf dem Gerät und werden nach der erneuten Anmeldung desselben Kontos gesendet.

## 3. Dienststart und Pflichtprüfung

Ohne bestandene Pflichtprüfung gibt es keine Einsatzfreigabe. **Die Freigabe erteilt nur der Server**, nie die App und nie der Fahrer selbst.

1. Der Startbildschirm zeigt Ihren nächsten Dienst mit Zeit und Fahrzeug. Ist keine Schicht zugewiesen, steht dort **Keine gueltige Schicht. Es werden keine Auftraege angezeigt.**{{app:shift.none}} Wenden Sie sich an die Leitstelle.
2. Tippen Sie zum Dienstbeginn auf **Dienst antreten**{{app:shift.start}} ({{api:startDriverShift}}). Das geht nur mit Verbindung: **Ohne Verbindung kann der Dienst nicht angetreten werden.**{{app:shift.offline}}
3. Starten Sie die Prüfung mit **Pflichtpruefung starten**{{app:inspection.start}}. Sie beginnt erst nach der Dienstanmeldung. Passt keine eindeutige Vorlage zum Fahrzeug, bleibt die Freigabe aus und die Leitstelle wird informiert.
4. Beantworten Sie jeden Prüfpunkt der Vorlage (Antwort „in Ordnung“ oder „Mangel“; bei einem Mangel Foto und Hinweis nach Vorlage). Die Anzeige führt Sie durch die Punkte (Fortschritt „Pruefung n / gesamt“). Der Server speichert den Lauf ({{api:createInspection}}).
5. Am Ende bestätigen Sie die Einsatzbereitschaft: **Ich bin fuer diesen Dienst einsatzbereit.**{{app:inspection.readiness.question}} Antworten Sie mit **Ja**{{app:inspection.readiness.yes}} oder **Nein**{{app:inspection.readiness.no}}. Es gibt kein Freitextfeld; tragen Sie keine Gesundheitsangaben ein. Bei „Nein“ gibt es keine Freigabe, wenden Sie sich an die Leitstelle.
6. Die App unterscheidet drei Zustände und zeigt „Freigegeben“ erst, wenn der Server es bestätigt hat: **Lokal abgeschlossen, Freigabe ausstehend**{{app:inspection.status.local}}, **Synchronisiert, noch nicht freigegeben**{{app:inspection.status.synced}}, **Freigegeben**{{app:inspection.status.released}}.

### 3.1 Pflichtprüfung mit kritischem Mangel

Ist ein sicherheitsrelevanter Prüfpunkt (zum Beispiel Bremsen, Beleuchtung oder Gurte) mit „Mangel“ beantwortet, handelt es sich um einen **kritischen Mangel**:

1. Die App zeigt sofort: **Kritischer Mangel. Bitte nicht fahren und die Leitstelle kontaktieren. Die Sperre wird bei der Uebertragung am Server gesetzt.**{{app:inspection.offline.criticalNo}}
2. **Fahren Sie nicht.** Rufen Sie die Leitstelle an (Rufnummer des Betreibers laut Dienstanweisung, nie im Chat oder auf dem Gerätebildschirm hinterlegt).
3. Der Server setzt die **Fahrzeugsperre** und keine Einsatzfreigabe wird erteilt (siehe 3.2). Das Fahrzeug bleibt gesperrt, bis die berechtigte Stelle nach der Reparatur freigibt.
4. Ein bereits bekannter Mangel (Altschaden) wird bei der nächsten Prüfung angezeigt; Sie quittieren ihn, melden ihn aber nicht doppelt.

### 3.2 Fahrzeugsperre

Eine **Fahrzeugsperre** bedeutet: dieses Fahrzeug darf nicht eingesetzt werden. Sie entsteht durch einen kritischen Mangel (Server) oder durch die Leitstelle (Sperre mit Grund). Als Fahrer können Sie eine Sperre **nie selbst aufheben**: **Eine Sperre hebt nur die berechtigte Stelle auf. Naechster Schritt: die Leitstelle kontaktieren.**{{app:action.blocked.liftBlock}} Auch die Einsatzfreigabe können Sie nicht selbst erteilen: **Die Einsatzfreigabe erteilt nur der Server. Naechster Schritt: Pruefung lokal abschliessen, die Freigabe erscheint nach der Uebertragung; bei Dringlichkeit die Leitstelle kontaktieren.**{{app:action.blocked.releaseVehicle}} Fehlt Ihnen für diesen Dienst eine Qualifikation, sagt die App: **Die Freigabe fuer diesen Dienst ist derzeit nicht moeglich. Bitte wenden Sie sich an die Leitstelle.**{{app:inspection.qualificationMissing}}

## 4. Stoppliste und Navigation

1. Nach der Freigabe zeigt **Stoppliste**{{app:run.title}} die Stopps Ihres Dienstes in Reihenfolge, mit Soll-Zeit, Prognose, Personenzahl und notwendiger Unterstützung (Rollstuhl, Einstiegshilfe, Ausstiegshilfe).
2. Der Eintrag **Naechster Stopp**{{app:run.next}} ist hervorgehoben. Mit **Navigation starten**{{app:nav.start}} übergeben Sie das Ziel an Ihre Navigations-App.
3. Ändert die Leitstelle den Plan, erscheint ein Hinweis, die Planrevision steigt, und die Navigation startet auf das neue Ziel neu. Prüfen Sie nach jeder Änderung den nächsten Stopp.
4. Fehlt die Stoppliste („Kein Auftrag“) oder ist sie ungültig, ist keine Navigation möglich.

## 5. Check-in, Ausstieg und No-Show

An jedem Stopp erfassen Sie Ereignisse. Die App legt sie als Ereignis ab ({{api:recordDriverEvent}}), jedes wirkt genau einmal am Server, auch wenn die Übertragung wiederholt wird.

1. **Ankunft:** Mit der Ankunft am Haltepunkt beginnt die Wartefrist (Ereignisart **arrived**{{src:mobile/driver/lib/offline/offline_actions.dart}}).
2. **Check-in:** Steigt der Fahrgast ein, erfassen Sie den Check-in (Ereignisart **checkIn**{{src:mobile/driver/lib/offline/offline_actions.dart}}), am Fahrgastcode oder per Auswahl.
3. **Ausstieg:** Am Ziel erfassen Sie den Ausstieg (Ereignisart **checkOut**{{src:mobile/driver/lib/offline/offline_actions.dart}}).
4. **No-Show:** Erscheint der Fahrgast nicht, ist der No-Show erst nach Ablauf der Wartefrist zulässig (Dienstparameter, vom Server geliefert, Planungsdefault 10 Minuten; der Server lehnt einen zu frühen No-Show ab). Sie wählen einen Grund (**NOT_PRESENT**{{src:mobile/driver/lib/fahrt/wait_period.dart}} nicht angetroffen, **REFUSED_TO_BOARD**{{src:mobile/driver/lib/fahrt/wait_period.dart}} Mitfahrt verweigert oder **OTHER**{{src:mobile/driver/lib/fahrt/wait_period.dart}} sonstiger). Ohne Grund wird das Ereignis nicht gebildet.
5. Wurde der Halt inzwischen storniert, aber der Check-in offline erfasst, entscheidet die Leitstelle: **Klaerfall: Der Check-in wurde offline erfasst, der Halt wurde inzwischen storniert. Die Leitstelle entscheidet, nichts wird still ueberschrieben.**{{app:connection.conflict.checkInVsCancel}}
6. Während der Fahrt sind Beleg und Stempeln gesperrt; bedienen Sie die App nur im Stand.

## 6. Schadenmeldung

1. Einen Schaden oder Mangel melden Sie jederzeit, auch ohne aktive Fahrt: **Mangel melden**{{app:defect.report}} ({{api:createDefect}}). Der Hinweis in der App lautet „Einen Schaden oder Mangel melden, auch ohne aktive Fahrt.“
2. Wählen Sie Art, Fahrzeugbereich und Auswirkung aus den Listen, nehmen Sie Fotos auf (**Foto aufnehmen**{{src:mobile/driver/lib/schaden/defect_capture_screen.dart}}) und senden Sie (**Senden**{{src:mobile/driver/lib/schaden/defect_capture_screen.dart}}).
3. Die Statusanzeige sagt Ihnen, ob die Meldung nur auf dem Gerät liegt oder am Server angekommen ist. Eine Meldung wird auch offline gespeichert und später genau einmal übertragen.
4. Bei Verletzten oder Gefahr rufen Sie zuerst den Notruf und dann die Leitstelle; die App ersetzt das nicht.

## 7. Offlineverhalten

Ohne Netz arbeitet die App mit dem zuletzt bestätigten Stand weiter. Der Banner nennt den Stand: **Offline. Noch kein Plan geladen.**{{app:offline.noPlan}} oder, mit Plan, „Offline. Letzter Plan: Uhrzeit“.

| Offline erlaubt | Nur mit Verbindung (Server) |
|---|---|
| zuletzt bestätigten Dienst und Stoppliste mit Stand ansehen | Dienst antreten, neue verbindliche Buchung |
| Beleg, Schaden und Zeitereignis erfassen (lokale Warteschlange) | Fahrzeugfreigabe (Einsatzfreigabe), Aufhebung einer Sperre |
| Check-in, Ausstieg und No-Show als Ereignis erfassen | Prüfung zur Freigabe abschließen |

1. Die Verbindungsanzeige kennt fünf Zustände: online, eingeschränkt, offline, Synchronisation läuft, Synchronisationskonflikt. Offline gilt: **Offline. Keine Echtzeitdaten. Angezeigt wird der zuletzt bestaetigte Stand.**{{app:connection.offline}} Es gibt keine laufende Ankunftszeit und keine Live-Position.
2. Eine Prüfung können Sie offline nur beginnen, wenn Vorlage und Liste der bekannten Schäden vor dem letzten Netzverlust geladen wurden. Sonst sagt die App: **Ohne Verbindung kann diese Pruefung nicht begonnen werden: Die Pruefvorlage liegt nicht auf dem Geraet. Bitte einmal mit Verbindung oeffnen.**{{app:inspection.offline.noTemplate}}
3. Eine offline abgeschlossene Prüfung ist nicht freigegeben: **Pruefung lokal abgeschlossen. Freigabe ausstehend: Das Fahrzeug ist erst nach der Bestaetigung durch den Server freigegeben.**{{app:inspection.offline.releasePending}} Fahren Sie erst, wenn die App „Freigegeben“ zeigt, es sei denn, die Leitstelle gibt Ihnen im Notbetrieb anderes vor.
4. Kommt das Netz zurück, überträgt die App die Ereignisse in der Reihenfolge der Erfassung. Eine Übertragung, die der Server nicht übernimmt, zeigt **Vom Server nicht uebernommen: bitte die Leitstelle kontaktieren, die Pruefung ist neu durchzufuehren**{{app:inspection.status.review}}.
5. Wenn die Verbindung nur kurz (wenige Sekunden) aussetzt, ändert sich die Anzeige nicht.

## 8. Beleg erfassen und Arbeitszeit stempeln

1. Einen Beleg (zum Beispiel Tankquittung) erfassen Sie im Stand: **Beleg erfassen**{{app:receipt.title}}. Aufnehmen mit **Foto aufnehmen**{{app:receipt.take}}, bei schlechter Qualität („unscharf“, „zu klein“) mit **Neu aufnehmen**{{app:receipt.retake}} wiederholen, dann **Speichern**{{app:receipt.save}}. Der Beleg bleibt als Entwurf, bis die Finanzen ihn prüfen.
2. Während der Fahrt ist die Belegerfassung gesperrt: **Während der Fahrt ist die Belegerfassung gesperrt. Bitte nach dem Halt erfassen.**{{app:receipt.moving}}
3. Arbeitszeit stempeln Sie unter **Arbeitszeit**{{app:worktime.title}}: **Kommen**{{app:worktime.clockIn}}, **Pause beginnen**{{app:worktime.breakStart}}, **Pause beenden**{{app:worktime.breakEnd}}, **Gehen**{{app:worktime.clockOut}}. Das Einstempeln erteilt **keine** Einsatzfreigabe.
4. Während Ihres Dienstes wird der Fahrzeugstandort erfasst und an die Leitstelle übermittelt, außerhalb des Dienstes nichts: **Waehrend Ihres Dienstes wird der Fahrzeugstandort erfasst und an die Leitstelle uebermittelt. Ausserhalb des Dienstes wird nichts erfasst.**{{app:position.notice}}

## 9. Ausfall der App und Notbetrieb

Fällt die App aus (Absturz, Gerät defekt) oder die Verbindung zur Plattform, gilt das Notbetriebsverfahren des Betriebshandbuchs, [Abschnitt 11.2 Ausfall der Driver-App-Verbindung](../betrieb/betriebshandbuch.md#112-ausfall-der-driver-app-verbindung). In Kurzform:

1. **Ausfall der App oder der Verbindung:** Sie arbeiten nach der zuletzt bestätigten Stoppliste weiter (ausgedruckt oder gemerkt) und rufen die Leitstelle an; die Leitstelle fragt Position und Fortschritt telefonisch ab.
2. **Keine Einsatzfreigabe und keine Sperraufhebung ohne Serververbindung.** Ein Fahrzeug, dessen Freigabe Sie nicht sehen konnten, fahren Sie nur nach ausdrücklicher Weisung der Leitstelle.
3. **Ausfall der Kernbuchung oder der Leitstellenoberfläche:** Die Leitstelle teilt Ihnen telefonisch mit, was gilt ([Abschnitt 11.1](../betrieb/betriebshandbuch.md#111-ausfall-der-kernbuchung) und [11.3](../betrieb/betriebshandbuch.md#113-ausfall-der-leitstellenoberfläche)). Es werden keine verbindlichen Zusagen an Fahrgäste gegeben, solange der Fachkern nichts bestätigt hat.
4. Nach der Rückkehr überträgt die App alle Ereignisse; Widersprüche (zum Beispiel Check-in nach einem Storno) klärt die Leitstelle, nichts wird still überschrieben.
5. Erlaubt und verboten im Notbetrieb entspricht der Tabelle in Abschnitt 7 (Offline erlaubt gegen Nur mit Verbindung).

## 10. Übungsablauf

Jede Übung dauert etwa 20 Minuten; die Schulungsleitung bewertet mit der Checkliste am Ende jeder Übung. Alle Übungen laufen mit synthetischen Daten in der Schulungsumgebung (Abschnitt 1).

| Nr. | Übung | Ablauf | Bestanden, wenn |
|---|---|---|---|
| Ü-1 | Dienststart | anmelden, **Dienst antreten**{{app:shift.start}}, Schicht und Fahrzeug erkennen | Fahrer nennt Schicht und Fahrzeug, kein Hilferuf |
| Ü-2 | Pflichtprüfung ohne Mangel | **Pflichtpruefung starten**{{app:inspection.start}}, alle Punkte „in Ordnung“, Einsatzbereitschaft „Ja“ | Zustand „Freigegeben“ erst nach Serverbestätigung benannt |
| Ü-3 | Pflichtprüfung mit kritischem Mangel | bei Bremsen „Mangel“ wählen | Fahrer fährt nicht, ruft die Leitstelle, nennt die Fahrzeugsperre und dass er sie nicht aufheben kann |
| Ü-4 | Stoppliste | Plan lesen, nächsten Stopp nennen, Navigation starten, Planänderung bemerken | richtiger nächster Stopp nach der Änderung |
| Ü-5 | Check-in, Ausstieg, No-Show | Fahrgast einsteigen lassen, Ausstieg erfassen, zweiten Fahrgast als No-Show nach Wartefrist | No-Show erst nach Wartefrist und mit Grund |
| Ü-6 | Schadenmeldung | **Mangel melden**{{app:defect.report}} mit Foto | Status „am Server“ oder „nur lokal“ richtig gelesen |
| Ü-7 | Offlineverhalten | Flugmodus an, Plan ansehen, Ereignis erfassen, Flugmodus aus | Fahrer nennt, was offline geht und was nicht, Ereignis kommt einmal an |
| Ü-8 | Beleg | **Beleg erfassen**{{app:receipt.title}} im Stand, im Fahrzeugmodus gesperrt | Fahrer erfasst nur im Stand |
| Ü-9 | Ausfall der App (Notbetrieb) | App „abschalten“, Leitstelle spielt Notbetrieb | Fahrer arbeitet nach Stoppliste weiter, ruft an, fährt ohne Freigabe nur auf Weisung |

Bewertung: Eine Übung gilt als nicht bestanden, wenn eine Sicherheitsregel verletzt wird (Fahren trotz kritischem Mangel, Sperre selbst aufheben, Freigabe ohne Serverbestätigung angenommen). Sie wird wiederholt.

## 11. Abweichungen zwischen Schulung und Build

| Nr. | Gegenstand | Stand im Build | Folge |
|---|---|---|---|
| F-01 | Check-in, Ausstieg, No-Show | Ereignislogik, Outbox und Schnittstelle vorhanden ({{api:recordDriverEvent}}); ein eigener Bildschirm mit Bedienelementen ist im App-Rahmen (`lib/main.dart`) noch nicht verdrahtet | Abschnitt 5 beschreibt das Verhalten, nicht die Schaltflächen; vor der Schulung am Gerät zu klären |
| F-02 | App-Hauptprogramm | OIDC-Anbindung, Schlüsselbund und Sync sind im Rahmen noch nicht verdrahtet (Dokument `mobile/driver/docs/driver-app-basis.md`, Abschnitt 4) | Schulung auf Geräten erst nach der Verdrahtung |
| F-03 | Geräteabnahme | Flutter in der Entwicklungsumgebung nicht verfügbar, keine App-Läufe | alle Texte dieses Dokuments stammen aus dem Quelltext, nicht aus einem Lauf |
| F-04 | Screenshots | keine | Text statt Bild |
