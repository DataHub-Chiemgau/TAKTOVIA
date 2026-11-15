---
dokument_version: 0.1
abnahme_build: ENTWURF-W18-9d035b7
sprache: de
stand: 2026-11-15
arbeitspaket: WP-M5-27
---

# Administratorhandbuch TAKTOVIA

**Zielgruppe:** Mandantenadministratorinnen und -administratoren sowie Planung des Betreibers, die einen Mandanten einrichten und pflegen, ohne das Entwicklungsteam zu fragen.
**Bezug:** Operator-Web und Schnittstellen des Repository-Stands `9d035b7` (Welle 17 integriert, siehe [Übergabeprotokoll](uebergabeprotokoll.md)). Jede in diesem Handbuch beschriebene Oberfläche, Beschriftung und Schnittstelle wird von `tools/check_dokumentationspaket.py` gegen den Quelltext abgeglichen; was dort fehlt, steht im Abschnitt [Abweichungen zwischen Handbuch und Build](#10-abweichungen-zwischen-handbuch-und-build) und nicht als Schritt.

**Konventionen:** Fett gesetzte Wörter sind Beschriftungen, die Sie auf dem Bildschirm finden. Alle Beispieldaten sind synthetisch (Schulungsmandant, Domäne `schulung.invalid`); es gibt keine Screenshots, weil keine Schulungsumgebung ausgeführt werden konnte, die Oberflächen sind ausschließlich textlich beschrieben.

## Inhalt

1. [Mandant und Organisation](#1-mandant-und-organisation)
2. [Benutzer und Rollen](#2-benutzer-und-rollen)
3. [Dienste, Gebiete und Haltepunkte](#3-dienste-gebiete-und-haltepunkte)
4. [Tarife](#4-tarife)
5. [Fahrzeuge](#5-fahrzeuge)
6. [Checklistenvorlagen](#6-checklistenvorlagen)
7. [Konfigurationsrevisionen mit Vier-Augen-Freigabe und Rollback](#7-konfigurationsrevisionen-mit-vier-augen-freigabe-und-rollback)
8. [Dienstplan und Fahrer](#8-dienstplan-und-fahrer)
9. [Audit und Datenschutz](#9-audit-und-datenschutz)
10. [Abweichungen zwischen Handbuch und Build](#10-abweichungen-zwischen-handbuch-und-build)

## 1. Mandant und Organisation

Ein Mandant (Betreiber) wird von der Plattformbetreiberin angelegt ({{api:createTenant}}, Rolle `platform_operator`, nicht über das Operator-Web). Ab dann pflegt die Administration des Mandanten alles Weitere. Jede Oberfläche erscheint nur, wenn Ihre Sitzung das nötige Recht hat; fehlt ein Bereich, fehlt Ihnen die Rolle, und Sie bitten die Administration, sie zu erteilen (Abschnitt 2).

1. Öffnen Sie im Menü **Gebiete, Stops, Tarife**{{nav:/admin/gebiete}} (Bereich „Administration“, UI-17). Von dort führen Links zu den Unterseiten.
2. Pflegen Sie die Stammdaten Ihres Unternehmens unter **Unternehmensprofil**{{src:web/operator/src/identity/CompanyProfileForm.tsx}} (Schnittstelle {{api:getCompanyProfile}}): **Name des Unternehmens**{{src:web/operator/src/identity/CompanyProfileForm.tsx}}, **Anschrift**{{src:web/operator/src/identity/CompanyProfileForm.tsx}}, **Kontakt-E-Mail**{{src:web/operator/src/identity/CompanyProfileForm.tsx}} und **Standardwährung**{{src:web/operator/src/identity/CompanyProfileForm.tsx}}. Die Seite liegt unter dem Pfad {{route:/admin/unternehmensprofil}}.
3. Bei einem Konflikt (zwischenzeitlich geändert) lädt die Seite neu und meldet es; prüfen Sie Ihre Änderung und speichern Sie erneut. Nichts wird still überschrieben.
4. Legen Sie die beteiligten Organisationen unter **Organisationen und Rechtsträgerrollen**{{src:web/operator/src/identity/OrganizationAdmin.tsx}} an ({{route:/admin/organisationen}}): **Name**{{src:web/operator/src/identity/OrganizationAdmin.tsx}}, **Föderationskennung**{{src:web/operator/src/identity/OrganizationAdmin.tsx}} und die vertraglichen Rollen (zum Beispiel Verkehrsunternehmen oder Aufgabenträger); danach **Organisation anlegen**{{src:web/operator/src/identity/OrganizationAdmin.tsx}}. Eine doppelt vergebene Föderationskennung weist die Anwendung als Scopekonflikt zurück.
5. Gliedern Sie den Betrieb unter **Organisationsstruktur**{{src:web/operator/src/organisation/Organisationsstruktur.tsx}} ({{route:/admin/struktur}}) in Regionen und Dienste: **Name der Region**{{src:web/operator/src/organisation/Organisationsstruktur.tsx}}, **Region anlegen**{{src:web/operator/src/organisation/Organisationsstruktur.tsx}}.

## 2. Benutzer und Rollen

Benutzer melden sich über die Identitätsdomäne des Betreibers an (OIDC, privilegierte Konten mit MFA). Das Operator-Web legt keine Passwörter an; es erteilt **Mitgliedschaften** mit einer Rolle.

1. Öffnen Sie **Kooperation und Rollen**{{nav:/admin/kooperation}}.
2. Tragen Sie **Konto (Subject)**{{src:web/operator/src/identity/MembershipAdmin.tsx}} und **Rollencode**{{src:web/operator/src/identity/MembershipAdmin.tsx}} ein, optional **Gültig bis**{{src:web/operator/src/identity/MembershipAdmin.tsx}}, und wählen Sie **Mitgliedschaft erteilen**{{src:web/operator/src/identity/MembershipAdmin.tsx}} ({{api:createMembership}}; Liste: {{api:listMemberships}}). Eine Mitgliedschaft lässt sich entziehen; sie erscheint dann als „entzogen“.
3. Die Rollencodes des Katalogs sind `passenger`, `driver`, `callcenter`, `support`, `dispatcher`, `planner`, `tenant_admin`, `finance`, `staff_planning` und `workshop`. Welche Rolle welche Tätigkeit darf, steht in der Rechtematrix (`backend/modules/identity/src/main/resources/rechtematrix/rechtematrix.csv`; Entwurf, fachlich noch nicht freigegeben, OP-10).
4. Die Administration (`tenant_admin`) hat auf Daten mit Datenklasse PII, HR oder FINANZ keinen automatischen Zugriff; dafür weisen Sie eine Fachrolle (zum Beispiel `finance`) gesondert zu.
5. Einen Zugang sperren Sie über **Konten und Sitzungen sperren**{{src:web/operator/src/identity/AccountAccessAdmin.tsx}}: **Konto (Subject)**{{src:web/operator/src/identity/AccountAccessAdmin.tsx}}, **Grund der Sperre**{{src:web/operator/src/identity/AccountAccessAdmin.tsx}}, dann **Konto sperren**{{src:web/operator/src/identity/AccountAccessAdmin.tsx}}. Bestehende Sitzungen des Kontos werden beendet; die Liste **Sitzungen des Mandanten**{{src:web/operator/src/identity/AccountAccessAdmin.tsx}} zeigt den Stand. Mit **Wiederherstellung anstoßen**{{src:web/operator/src/identity/AccountAccessAdmin.tsx}} stoßen Sie die Kontowiederherstellung an.
6. Ihre eigene Sitzung und Ihr Mandant erscheinen im Kopfbereich (Mandantenwähler, **Konto**{{src:web/operator/src/App.tsx}}). Bei mehreren Mandanten wechseln Sie dort; der Wechsel prüft die Mitgliedschaft am Server.

## 3. Dienste, Gebiete und Haltepunkte

Ein Dienst ist ein Verkehrsangebot (zum Beispiel ein On-Demand-Gebiet mit Betriebszeiten). Änderungen an Diensten laufen über Konfigurationsrevisionen (Abschnitt 7).

1. **Dienst anlegen**{{src:web/operator/src/config/DienstKonfiguration.tsx}} unter {{route:/admin/dienstkonfiguration}} ({{api:createService}}): Dienst benennen, Betriebszeiten wählen, speichern.
2. Öffnen Sie **Gebiete, Haltepunkte, Betriebszeiten**{{src:web/operator/src/geo/Geodaten.tsx}} ({{route:/admin/geodaten}}).
3. Bediengebiet: erfassen Sie **Koordinatenliste**{{src:web/operator/src/geo/Geodaten.tsx}} und speichern Sie über den Knopf mit dem Namen „Bediengebiet speichern“ ({{api:createServiceArea}}). Eine ungültige Geometrie weist der Server ab.
4. Haltepunkte: **Haltepunkt anlegen**{{src:web/operator/src/geo/Geodaten.tsx}} mit **Name**{{src:web/operator/src/geo/Geodaten.tsx}}, **Koordinate**{{src:web/operator/src/geo/Geodaten.tsx}} und Merkmalen wie **Rollstuhl**{{src:web/operator/src/geo/Geodaten.tsx}}.
5. Betriebszeiten: Wochentage wählen und **Betriebszeit speichern**{{src:web/operator/src/geo/Geodaten.tsx}}.
6. Erlaubte Zielpunkte: unter **Erlaubte Zielpunkte und Relationsprüfung**{{src:web/operator/src/geo/Geodaten.tsx}} legen Sie die Zielmenge fest (**Zielpunkte speichern**{{src:web/operator/src/geo/Geodaten.tsx}}); **Relation prüfen**{{src:web/operator/src/geo/Geodaten.tsx}} zeigt, ob Start und Ziel zulässig sind. Eine leere Menge bedeutet keine Einschränkung.

## 4. Tarife

1. Öffnen Sie {{route:/admin/tarife}} (Titel **Tarife**{{src:web/operator/src/tarife/Tarife.tsx}}). Die Liste **Tarifrevisionen**{{src:web/operator/src/tarife/Tarife.tsx}} zeigt Zustand und Gültigkeit.
2. **Tarifrevision anlegen**{{src:web/operator/src/tarife/Tarife.tsx}} ({{api:createTariffRevision}}): wählen Sie **Tarifklasse**{{src:web/operator/src/tarife/Tarife.tsx}} und **Regeltyp**{{src:web/operator/src/tarife/Tarife.tsx}}, setzen Sie **Gültig ab (einschließlich)**{{src:web/operator/src/tarife/Tarife.tsx}} und **Gültig bis (ausschließlich)**{{src:web/operator/src/tarife/Tarife.tsx}}.
3. Veröffentlichen Sie die Revision ({{api:publishTariffRevision}}); eine veröffentlichte Revision ändern Sie nie, Sie legen eine neue an. Bestehende Zusagen behalten den Preis ihrer Ursprungsrevision.
4. Das Prüfen des wirksamen Tarifs ({{api:getEffectiveTariff}}) ist Teil der Demonstration des Standardbetriebs ([SB-02](standardbetrieb.md#2-abläufe-des-standardbetriebs)).

## 5. Fahrzeuge

1. Öffnen Sie **Flotte und Werkstatt**{{nav:/flotte}} und dort den Fahrzeugstamm ({{route:/flotte}}; Titel **Flotte und Werkstatt: Fahrzeugstamm**{{src:web/operator/src/flotte/Fahrzeugstamm.tsx}}).
2. Legen Sie ein Fahrzeug an ({{api:createVehicleMaster}}): **Kennung**{{src:web/operator/src/flotte/Fahrzeugstamm.tsx}}, **Fahrzeugtyp (Code)**{{src:web/operator/src/flotte/Fahrzeugstamm.tsx}}, **Sitzplätze insgesamt**{{src:web/operator/src/flotte/Fahrzeugstamm.tsx}}, **Rollstuhlplätze**{{src:web/operator/src/flotte/Fahrzeugstamm.tsx}} und **Barrierefreie Stellplätze**{{src:web/operator/src/flotte/Fahrzeugstamm.tsx}}.
3. Kapazitätskombinationen tragen Sie als Code mit Rollstuhlplätzen ein (**Aktive Kombination**{{src:web/operator/src/flotte/Fahrzeugstamm.tsx}}); die Kapazität eines Fahrzeugs entscheidet später, ob eine Buchung zugesagt wird.
4. Ordnen Sie das Fahrzeug einem Dienst zu (**Dienstzuordnung**{{src:web/operator/src/flotte/Dienstzuordnung.tsx}}, {{api:createVehicleServiceAssignment}}). Ohne Zuordnung bekommt das Fahrzeug keine Aufträge.
5. Ein neues Fahrzeug ist **nicht einsatzbereit**, bis die Pflichtprüfung des Fahrers vor dem Dienst bestanden und die Freigabe vom Server erteilt ist (Fahrerschulung, Kapitel 3). Eine Sperre setzt die Leitstelle ({{api:createVehicleBlock}}) oder der Server nach einem kritischen Mangel; aufheben darf sie nur die berechtigte Stelle ({{api:liftVehicleBlock}}).
6. Schäden verwalten Sie unter **Schadenakten**{{nav:/flotte/schaeden}}; die Reparatur und die technische Wiederfreigabe unter **Reparatur und Wiederfreigabe**{{src:web/operator/src/flotte/Reparatur.tsx}}. Die technische Freigabe verlangt zwei verschiedene Personen (Vieraugenpflicht).

## 6. Checklistenvorlagen

Checklistenvorlagen bestimmen die Pflichtprüfung des Fahrers.

1. Öffnen Sie **Checklistenvorlagen**{{nav:/flotte/checklisten}} (Titel **Flotte und Werkstatt: Checklistenvorlagen**{{src:web/operator/src/flotte/Checklistenvorlagen.tsx}}).
2. **Neue Vorlage**{{src:web/operator/src/flotte/Checklistenvorlagen.tsx}} ({{api:createChecklistTemplate}}): **Name der Vorlage**{{src:web/operator/src/flotte/Checklistenvorlagen.tsx}}, **Fahrzeugklasse**{{src:web/operator/src/flotte/Checklistenvorlagen.tsx}} und **Betriebsart**{{src:web/operator/src/flotte/Checklistenvorlagen.tsx}}.
3. **Prüfelement hinzufügen**{{src:web/operator/src/flotte/Checklistenvorlagen.tsx}}: **Schlüssel des Prüfelements**{{src:web/operator/src/flotte/Checklistenvorlagen.tsx}}, **Titel**{{src:web/operator/src/flotte/Checklistenvorlagen.tsx}}, **Hilfetext**{{src:web/operator/src/flotte/Checklistenvorlagen.tsx}} und **Eingabetyp**{{src:web/operator/src/flotte/Checklistenvorlagen.tsx}}.
4. Bestimmen Sie je Prüfelement die Folge eines Mangels: **Fahrzeugsperre (Standard)**{{src:web/operator/src/flotte/Checklistenvorlagen.tsx}}, **Dienstsperre (nur diese Schicht)**{{src:web/operator/src/flotte/Checklistenvorlagen.tsx}} oder **Keine (nur Mangel)**{{src:web/operator/src/flotte/Checklistenvorlagen.tsx}}. Sicherheitsrelevante Prüfpunkte (Bremsen, Beleuchtung, Gurte) setzen Sie auf Fahrzeugsperre; ein kritisches „Nein“ sperrt das Fahrzeug dann am Server.
5. Veröffentlichen Sie die Revision der Vorlage ({{api:publishChecklistTemplateRevision}}). Ein begonnener Lauf bleibt an seine Revision gebunden; neue Läufe nutzen die veröffentlichte.
6. Mit **Nur Fahrzeuge mit Rampe oder Lift**{{src:web/operator/src/flotte/Checklistenvorlagen.tsx}} begrenzen Sie ein Prüfelement auf Fahrzeuge mit Hilfe für Rollstuhlnutzer.

## 7. Konfigurationsrevisionen mit Vier-Augen-Freigabe und Rollback

Betriebsparameter (zum Beispiel Wartezeit bis No-Show, Alarmverzögerung) sind keine Einzelwerte, sondern **Konfigurationsrevisionen** je Bereich: Mandant, Region, Betriebshof, Dienst oder Fahrzeug. Jede Änderung ist nachvollziehbar und wirkt erst nach Freigabe.

1. Öffnen Sie **Gebiete, Stops, Tarife**{{nav:/admin/gebiete}}. Der Abschnitt **Konfigurationsrevisionen**{{src:web/operator/src/config/ConfigRevisions.tsx}} listet die Revisionen mit Ersteller, Zeitpunkt, Wirksamkeit, Grund und der Änderung alt gegen neu. Der **Parameterkatalog**{{src:web/operator/src/config/ConfigRevisions.tsx}} nennt Einheit und Wertebereich. Unter **Was galt zu einem Zeitpunkt?**{{src:web/operator/src/config/ConfigRevisions.tsx}} rekonstruieren Sie den damals wirksamen Stand.
2. Eine Änderung beginnt als Entwurf ({{api:createConfigRevision}}), Rechte `config:publish` für `planner` und `tenant_admin`. Die wirksame Konfiguration sehen Sie unter {{route:/admin/konfiguration}} (**Wirksame Konfiguration**{{src:web/operator/src/config/ConfigEditor.tsx}}).
3. Wählen Sie die Revision aus: der Bereich **Revision freigeben**{{src:web/operator/src/config/RevisionFreigabe.tsx}} führt in vier Schritten: **Prüfen**{{src:web/operator/src/config/RevisionFreigabe.tsx}} (Validator-Ergebnis, ein unzulässiger Sicherheitswert blockiert alles Weitere), **Freigeben (zweite Person)**{{src:web/operator/src/config/RevisionFreigabe.tsx}} ({{api:approveConfigRevision}}), **Wirkung prüfen**{{src:web/operator/src/config/RevisionFreigabe.tsx}} (Wirkungsbericht über betroffene Objekte) und **Veröffentlichen**{{src:web/operator/src/config/RevisionFreigabe.tsx}} ({{api:publishConfig}}).
4. **Vier-Augen-Regel:** Wer die Revision erstellt hat, kann sie nicht freigeben; die Seite zeigt dann „Sie haben diese Revision erstellt; die Freigabe muss eine zweite Person vornehmen.“ und der Server lehnt ab. Das ist gewollt, umgehen Sie es nie mit einem gemeinsamen Zugang.
5. **Rollback:** Eine fehlerhafte veröffentlichte Revision wird nie gelöscht oder überschrieben. Sie nehmen sie über {{api:rollbackConfigRevision}} als **neue Revision** mit den Werten der Vorgängerrevision zurück (Feld `rolled_back_from` verweist auf die fehlerhafte). Die neue Revision geht denselben Weg: Prüfen, Freigeben durch eine zweite Person, Veröffentlichen. Bestehende Zusagen bleiben bei ihrer Ursprungsrevision. Im Operator-Web des aktuellen Builds gibt es dafür **keinen eigenen Knopf** (Abschnitt 10); der Aufruf erfolgt über die Schnittstelle durch eine Person mit dem Recht `config:publish`.
6. Einstellungen, die das Fahrerumfeld betreffen (Fahrerdaten, Mitbestimmung, D-10), ändern Sie nicht ohne Abstimmung mit dem Betriebsrat; die Datenschutzdokumentation (Abschnitt 5 dort) nennt die Folgen.

## 8. Dienstplan und Fahrer

1. **Dienstplan**{{nav:/personal/dienstplan}} (UI-12, Rolle `staff_planning`): wählen Sie **Depot**{{src:web/operator/src/dienstplan/Dienstplan.tsx}} und den Zeitraum (**Woche**{{src:web/operator/src/dienstplan/Dienstplan.tsx}}, **2 Wochen**{{src:web/operator/src/dienstplan/Dienstplan.tsx}} oder **Monat**{{src:web/operator/src/dienstplan/Dienstplan.tsx}}). **Offene Dienste**{{src:web/operator/src/dienstplan/Dienstplan.tsx}} und **Konflikte**{{src:web/operator/src/dienstplan/Dienstplan.tsx}} (Ruhezeit, Doppelbelegung) sind eigene Ansichten. Jede Pflichtaktion geht auch ohne Ziehen und Ablegen über die **Schichtliste (Tabellenalternative zum Raster)**{{src:web/operator/src/dienstplan/DienstplanRaster.tsx}}.
2. Entwurf und Veröffentlichung sind getrennt: **Entwurf (Arbeitsstand)**{{src:web/operator/src/dienstplan/Dienstplan.tsx}} und **Nur veröffentlicht**{{src:web/operator/src/dienstplan/Dienstplan.tsx}}; veröffentlichen ({{api:publishRoster}}) darf nur `staff_planning`.
3. Fahrer legen Sie unter **Fahrer und Qualifikationen**{{nav:/flotte/fahrer}} an (**Fahrer anlegen**{{src:web/operator/src/flotte/Fahrerstamm.tsx}}, Mitgliedschaft mit der Rolle Fahrer, **Qualifikation (Code)**{{src:web/operator/src/flotte/Fahrerstamm.tsx}} mit Gültigkeit). Die Schicht weisen Sie unter **Schichtzuweisung**{{nav:/flotte/schichten}} zu (**Schicht zuweisen**{{src:web/operator/src/flotte/Schichtzuweisung.tsx}}, **Fahrer (Kennung)**{{src:web/operator/src/flotte/Schichtzuweisung.tsx}}, **Fahrzeug (Kennung)**{{src:web/operator/src/flotte/Schichtzuweisung.tsx}}, **Beginn (lokale Zeit)**{{src:web/operator/src/flotte/Schichtzuweisung.tsx}}, **Ende (lokale Zeit)**{{src:web/operator/src/flotte/Schichtzuweisung.tsx}}). Ein Fahrer ohne gültige Qualifikation oder Schicht sieht keine Aufträge.
4. Einen Fahrzeugwechsel mitten in der Schicht erfassen Sie unter **Fahrzeugwechsel**{{nav:/flotte/fahrzeugwechsel}}; danach ist eine neue Pflichtprüfung am Fahrzeug nötig.

## 9. Audit und Datenschutz

1. **Datenschutz, Audit, Support**{{nav:/admin/datenschutz}} (UI-20) enthält **Audit des Mandanten**{{src:web/operator/src/audit/AuditList.tsx}}. Jede Abfrage verlangt einen **Zweck der Abfrage**{{src:web/operator/src/audit/AuditList.tsx}} und wird selbst protokolliert.
2. **Aufbewahrungsregeln und Löschprotokoll**{{src:web/operator/src/datenschutz/RetentionOverview.tsx}} zeigen die Regeln je Datenklasse; Fristen sind durch Auftraggeber festzulegen (D-11) und erscheinen bis dahin als Entwurf.
3. Das **Dateninventar**{{src:web/operator/src/datenschutz/DateninventarExport.tsx}} lässt sich exportieren; der Katalog ist noch nicht freigegeben (OP-24).
4. Die Rollen der Leitstelle, ihre Störungs- und Vorfallsansichten und die Notbetriebsverfahren beschreibt das [Betriebshandbuch](../betrieb/betriebshandbuch.md#11-notbetrieb).

## 10. Abweichungen zwischen Handbuch und Build

Ehrlich festgehalten, damit niemand einen Schritt sucht, den es nicht gibt:

| Nr. | Gegenstand | Stand im Build | Folge |
|---|---|---|---|
| A-01 | Rollback einer Konfigurationsrevision | nur Schnittstelle {{api:rollbackConfigRevision}}, kein Bedienelement im Operator-Web | Abschnitt 7 Schritt 5 beschreibt den Aufruf; Bedienelement als Nachbesserung vorgemerkt |
| A-02 | Bediengebiet speichern, Haltepunkt und Betriebszeit | Bedienelemente vorhanden, Kartenansicht fehlt; Gebiet wird als Koordinatenliste erfasst | Abschnitt 3 beschreibt die Listeneingabe |
| A-03 | Callcenter (UI-06) | Platzhalterseite, Fachinhalt folgt in M2/M7 | nicht Teil dieses Handbuchs |
| A-04 | Screenshots | keine Schulungsumgebung ausgeführt | Text statt Bild; Screenshots sind nach Aufbau der Schulungsumgebung nachzuliefern |
