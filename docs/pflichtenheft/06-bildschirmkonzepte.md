# 06 – Bildschirmkonzepte und Bedienverträge

## 6.1 Gemeinsames Interaktionsmodell

Die folgenden Wireframes sind funktionale Bildschirmkonzepte, keine fertigen Designs oder implementierte Anwendung. Alle Beträge/Personen/Fahrten sind synthetische Beispiele. Desktop: Navigation links, Arbeitskontext oben, Hauptbereich und optionales Detailpanel. Mobil: ein primärer Arbeitsschritt pro Seite; Navigation und Status sind per Screenreader zugänglich. Mindestens Deutsch und vorbereitete Internationalisierung; native Apps gemäß Phasenplan.

Jeder Bildschirm benennt Mandant/Dienst, fachlichen Status und Datenstand. Ein bewusst ausgelöster Mandantenwechsel setzt fremde offene Formulare/Listen zurück; ungespeicherte eigene Eingaben werden mit Rückkehrmöglichkeit behandelt. Gleichzeitige Tabs führen ihren Kontext unabhängig. Der Kontext ist eine Anzeige der autorisierten Sitzung, keine Rechtequelle.

Karte immer mit gleichwertiger Haltestellen-/Fahrzeugliste. Tastaturbedienung einschließlich Alternativen zu Drag-and-drop; sichtbarer Fokus, WCAG 2.2 AA als Abnahmeziel, Kontrast, 200-%-Textvergrößerung und Reflow bei 320 CSS-Pixeln. Status nie nur als Farbe. Touchflächen als Designziel mindestens 44×44 CSS-Pixel. Asynchrone Statusmeldungen über zurückhaltende Live-Region, keine permanent springenden Bildschirmpositionen. Reduzierte Bewegung respektieren.

## 6.2 Fahrgast: Suche und kombinierte Angebote

```text
TAKTOVIA                                     Meine Reisen | Hilfe
Wohin möchten Sie fahren?
Von [Adresse / Haltestelle_____________________________]
Nach[Adresse / Haltestelle_____________________________]
Zeit [Abfahrt ▼] [25.10.2026] [09:00] [Europe/Berlin]
Reisende [2]  Unterstützung [Rollstuhlplatz auswählen]
[Verbindungen suchen]

Ergebnis 1 · 09:00–09:48 · 1 Umstieg · 2 Betreiber
09:00 Dorfplatz ── Rufbus A ── 09:20 Bahnhof
        10 min Umstieg · 3 min Fußweg enthalten
        Rollstuhlweg bestätigt · Anschluss nicht garantiert
09:30 Bahnhof  ── Rufbus B ── 09:48 Klinik
Heute hier zahlbar: 32,10 EUR · beide Abschnitte buchbar
[Details und Bedingungen]                 [Auswählen]
```

Pflichtfelder: Start/Ziel mit klar bestätigtem Treffpunkt, Abfahrts- oder Ankunftswunsch, Personenzahl und transportrelevanter Unterstützungsbedarf. Keine Diagnoseabfrage. Uneindeutige Ortszeiten beim Zeitwechsel erhalten eine verständliche Auswahl mit UTC-Offset; nicht existente Ortszeit verlangt Korrektur.

Alternatives gemischtes Ergebnis: „Rufbus A hier buchbar: 10,70 EUR. Bahn: Ticket separat erforderlich; Preis noch unbekannt.“ Kein Gesamtpreis von 10,70 EUR; `external_unknown_leg_ids` verhindert die falsche Beschriftung. Bei bekanntem Bahnpreis: „Hier 10,70 EUR, separat voraussichtlich 8,00 EUR, bekannte Summe 18,70 EUR“. Änderungen externer Preise sind als solche markiert.

Leere Ergebnisse nennen primären Ablehnungsgrund mit nächster Handlung: andere Zeit/Haltestelle, Callcenter oder später erneut suchen. Einzelner Partnerausfall zeigt fehlende Daten und weiterhin verfügbare Alternativen. Suche bleibt abbrechbar; Abbruch bestätigt keine Buchung. API: `POST journeys/plan`, `GET offers/{id}`.

## 6.3 Checkout: Betreiber, Preis und Verbindlichkeit

```text
Ihre Reise prüfen                                  Schritt 2 von 3
A · Dorfplatz → Bahnhof      10,70 EUR   [Stornoregel]
B · Bahnhof → Klinik        21,40 EUR   [Stornoregel]
Heute zahlbar                32,10 EUR
Anbieter und Rechnungsaussteller: A und B
Vermittlung: Plattform P · gemeinsame Zahlung · ggf. zwei Rechnungen
Umstieg: 10 min; Mindestwechselzeit 8 min; keine Anschlussgarantie
Zahlung [PSP-Zahlungsauswahl]
[ ] Beförderungs- und Stornobedingungen A/B akzeptiert
[Zahlungspflichtig buchen – 32,10 EUR]
```

Ablauf: Auswahl akzeptiert Angebot/Terms, löst den Holdauftrag aus und zeigt „Plätze werden reserviert“. Erst erfolgreicher Hold startet das kontrollierte Confirm-/Zahlungsverfahren. Ein Countdown zeigt die bestätigte gemeinsame Restfrist, nicht die längste Partnerfrist. Ablauf während PSP-Authentisierung: Zahlung/Reservierung prüfen, ggf. erneutes Angebot; kein irreführender erfolgreicher Abschluss.

Externe Ticketabschnitte erscheinen deutlich vor dem Kaufbutton und verlangen die explizite Bestätigung, dass sie separat zu beschaffen sind. Pflichtzustimmung zur Buchung wird nicht mit Marketingeinwilligung kombiniert. Teilkauf ist standardmäßig aus; wenn vertraglich angeboten, eigenständige, klar bezifferte Wahl vor Commit. Callcenter verwendet dieselbe API und dokumentiert die im Gespräch abgegebene Zustimmung.

Buttons werden während laufender Operation gegen versehentliche Doppelbetätigung gesperrt; serverseitige Idempotenz bleibt dennoch erforderlich. Nach Verbindungsabbruch öffnet dieselbe Operation, kein neuer Checkout. Ein Timeout zeigt „Bestätigung wird geprüft“, Betrag und Kontaktmöglichkeit. Die Anzeige behauptet weder Ablehnung noch Erfolg, solange ein Betreiberstatus unbekannt ist.

## 6.4 Reisebegleitung, Storno und Klärfall

```text
Reise TV-2048                Buchungsprüfung läuft
A: bestätigt · Referenz A-4711 · Abholung 09:00–09:05
B: Rückmeldung ausstehend · wir prüfen die Buchung
Zahlung: 32,10 EUR autorisiert, noch nicht eingezogen
Bitte keine zweite Buchung auslösen. [Hilfe zur Reise]
Letzter Stand 08:42:16 · Vorgang wird automatisch aktualisiert
```

Nach Bestätigung: chronologische Legs, Boarding-Code je Betreiber, eigener Treffpunkt und zugängliche Wegbeschreibung, verbindliche Zusage getrennt von aktueller ETA; Kontakt über Relay. Fremde Personal- oder Fahrzeugakten sind nie sichtbar. Bei gemischter Reise lautet die Überschrift „Plattformabschnitte bestätigt“, gefolgt vom externen Ticketbedarf; `coverage` steuert den Wortlaut.

Storno ist zweistufig: Stornoangebot mit betroffenen Abschnitten, Gebühren, erwarteter Erstattung, Frist und Reiseauswirkung → ausdrückliche Bestätigung. Beispiel B: „B stornieren; 21,40 EUR Erstattung; A bleibt bestehen“. Ein Teilstorno erklärt, dass die ursprüngliche Reisekette nicht mehr vollständig ist. Erst PSP-Bestätigung zeigt „erstattet“, davor „Erstattung beauftragt“. Fehlgeschlagene Partnerstornos zeigen verbleibende Verpflichtung und Supportfall. Eine Ersatzverbindung mit höherem Preis benötigt neue Zustimmung.

## 6.5 Leitstelle: lokaler Betrieb mit Föderationsübersicht

```text
Mandant A / Region Chiemgau                  Datenstand 09:12:04
Filter: [Dienst] [Offene Störungen] [Anschlussrisiken]
Fahrzeugliste / Karte              | Reise / Segmentdetails
A-12 bereit    2 Sitzplätze        | Reise TV-2048 · eigener Abschnitt A
A-14 gesperrt  kritischer Mangel    | Zusage 09:00–09:05 · ETA 09:08
A-18 offline  letzter Stand 4 min  | Übergabe Bahnhof · Anschluss gefährdet
                                  | Partner B: bestätigt · Kontaktrelay
                                  | [Ersatzoptionen prüfen]
                                  | [Fahrgast informieren] [Fall eröffnen]
```

Fremder Betreiber wird nur mit erlaubter Segment-/Anschlussprojektion angezeigt. Eine Karte mit Partnerpositionen ist nur bei ausdrücklichem, zweckgebundenem Grant zulässig; kein impliziter Zugriff auf dessen Flotte. „Partnerübernahme anfragen“ eröffnet einen Vertrag/Capability-geprüften Vorgang. Drag-and-drop disponiert ausschließlich eigene freigegebene Ressourcen; verbindliche Änderungen erklären Folgen, Preis und erforderliche Zustimmung.

Ein technischer Klärarbeitsplatz zeigt Saga-Schritte, letzte bestätigte Ergebnisse, Operations-/Partnerreferenz, Alter, nächste automatische Prüfung und Zuständigkeit. Rohpayloads mit PII bleiben gesperrt. Zulässige Aktionen sind „Status erneut abgleichen“, „gezielte Kompensation“ oder dokumentierte manuelle Entscheidung. Kein freier Button „als erfolgreich markieren“ ohne Nachweis.

## 6.6 Finanzen: gemeinsame Datenbasis, getrennte Sichten

```text
Finanzen · Rechtsträger A · Oktober 2026 · EUR
[Ist gebucht] [Plan] [Abweichung]    Stand / Regelversion anzeigen
[Kosten] [Belege] [Partnerabrechnung] [Zahlungsabgleich] [Abschluss]
Kostenobjekt: Fahrzeug A-12     [Details]
                         direkt       umgelegt       Summe
fix                        ...             ...          ...
variabel                   ...             ...          ...
Gesamt                     ...             ...          ...
Datenvollständigkeit 96 % · 3 ungeklärte Belege · 12 km unzugeordnet
[Ursprungsereignisse] [Umlageschlüssel] [Export]
```

Vier Zellen einer Kreuztabelle statt vier überlappend addierter Kostenklassen. Der Benutzer wählt Ist/Plan explizit; konsolidierte Berichte zeigen Eliminierungen, beteiligte Rechtsträger und Berechtigungsbasis. Keine Partnerkosten oder Margen ohne gesonderte Freigabe. Kosten pro km zeigen Bezugsstrecke, Kilometerklasse, Nenner und Datenstand; fehlender Nenner ergibt „nicht berechenbar“.

Belegprüfung: links unverändertes Original, rechts OCR-Felder mit Konfidenz und Korrekturhistorie; Kostenobjekte, Steuergruppe, Dublettenverknüpfung, Aufteilung und Summe. „Freigeben“ nur bei vollständiger Validierung; Vieraugenfall zeigt zuständige zweite Person. Original/PDF/XML-Abweichungen bleiben sichtbar. Ein bereits gebuchter Beleg bietet „Gegenbeleg erstellen“ statt freie Betragsbearbeitung.

Clearing: jede Zeile enthält Leg, Leistenden, Verkäufer, Tarifumsatz, Provision, Korrekturen, Sollauszahlung und Zahlungsstand. Drilldown verbindet Kundenbetrag → Segmentanspruch → PSP-Transfer → Kontoabgleich. „Kundenerstattung ausstehend“ und „Rückholung vom Betreiber ausstehend“ sind verschiedene Status. Periodenabschluss listet offene Differenzen und die freigegebene Behandlung; geschlossene Monate sind schreibgeschützt.

## 6.7 Fahrer: sichere Einsatzfreigabe und Offlineerfassung

```text
Nächster Dienst 06:00–14:00 · Fahrzeug A-12
Arbeitszeit: gestartet 05:52
Fahrzeugfreigabe: Prüfung erforderlich
[Vorhandene Schäden ansehen] [Pflichtprüfung starten]

Prüfung 3 / 8 · Rollstuhlrampe funktionsfähig?
[Ja] [Nein – Mangel melden] [Nicht prüfbar]
Ohne erfolgreiche Prüfung bleibt das Fahrzeug gesperrt.
```

Arbeitszeitbeginn und technische Einsatzfreigabe sind getrennt. Fahrzeugwechsel startet neue erforderliche Prüfung; „nicht prüfbar“ ist kein automatisches Ja. Kritischer Mangel zeigt klare Sperre, Vorgangsnummer und Kontakt zur Leitstelle. Wiederfreigabe erfordert berechtigte Fachrolle und ggf. erneute Prüfung.

Fahrtansicht enthält nur nächsten sicheren Handlungsschritt, Stopp, Personenzahl, notwendige Unterstützung und Boardingprüfung. Große Check-in/out-Flächen; keine Erfassungsdialoge während Fahrbewegung. Offlinebanner benennt Zeit des letzten Plans. Schaden/Beleg lässt sich als Entwurf mit lokalem Status speichern; „synchronisiert“ und „freigegeben“ bleiben getrennt. Beim Beleg höchstens fünf Eingabeschritte bis Entwurf (DOC-001).

## 6.8 Personalplanung und Konfiguration

Dienstplan: Wochenraster mit eigenen Beschäftigten, offenen Diensten, Fahrzeugen, Qualifikations-/Ruhezeitkonflikten; barrierefreie Tabellenalternative. Entwurf und veröffentlichte Revision prominent getrennt. Veröffentlichung zeigt Differenzliste und blockierende Konflikte, Benachrichtigungen nur für Betroffene. Abwesenheiten zeigen im Dispositionsraster „nicht verfügbar“ ohne Diagnose. Mitarbeiter sehen eigenen Plan, Zeitstempel, Korrekturantrag und Saldo; Planzeit überschreibt keine Istzeit.

Kooperationen: Partner, Rechtsträgerrollen, erlaubte Dienste/Aktionen/Felder, Vertragsversion, Frist und Nachweise; Capability-Teststatus separat vom Vertragsstatus. Widerruf erklärt Folgen für neue Reisen und bestehende Segmente, verlangt Begründung; Sicherheitsentzug kann sofort greifen. Kein pauschales Kontrollkästchen „alle Mandantendaten teilen“.

Konfigurationseditor: Name/Einheit, aktueller und vorgeschlagener Wert, Herkunft der wirksamen Regel, Wirksamkeitsdatum, Validator, Prüfer und Vorschau auf betroffene zukünftige Angebote. Bestätigte Zusagen erscheinen als unverändert gebundene Version. Unzulässige Sicherheitswerte blockieren Veröffentlichung. ETA-Personalisierung zeigt fehlende Fachfreigaben und bleibt deaktiviert.

## 6.9 Vollständiger Navigations- und Zustandskatalog

| ID | Rolle / Bildschirm | Primäre Aktion | Leere-/Fehler-/Rechtezustände |
|---|---|---|---|
| UI-01 | Passenger Suche | Angebot ermitteln | Keine Abdeckung, ungültige Zeit, Partnerausfall |
| UI-02 | Angebote/Details | Reise auswählen | Abgelaufen, externe Tickets, unbestätigte Barrierefreiheit |
| UI-03 | Checkout | Betrag/Terms bestätigen | Holdverlust, SCA, Preisänderung, Doppelklick |
| UI-04 | Aktive/alte Reisen | Reise verfolgen / stornieren | UNKNOWN, Teilstorno, verspätete Rückmeldung |
| UI-05 | Konto/Berechtigung | Kontakt, Präferenzen, Vertreter | Verifikation fehlt, Vertretung abgelaufen, Löschstatus |
| UI-06 | Callcenter | Für verifizierten Fahrgast buchen | Vertretungsumfang, Zustimmung und Kontaktkanal fehlen |
| UI-07 | Operator Livebetrieb | Eigene Buchung/Ressource bearbeiten | Sperre, veralteter Standort, keine Fremdrechte |
| UI-08 | Reise-/Saga-Klärung | Statusabgleich / Kompensation | Partner unerreichbar, Nachweis fehlt, Eskalation |
| UI-09 | Driver Dienststart | Pflichtprüfung abschließen | Offline, Mangel, andere Fahrzeugrevision |
| UI-10 | Driver Fahrt/Schaden | Check-in/out, Mangel melden | Falscher Stopp/Code, vergangene Revision |
| UI-11 | Flotte/Werkstatt | Wartung / Reparatur / Freigabe | Frist überschritten, Freigaberolle fehlt |
| UI-12 | Dienstplan | Entwurf veröffentlichen | Ruhezeit, Doppelbelegung, unbesetzter Dienst |
| UI-13 | Eigene Zeit/Abwesenheit | Antrag / Korrektur | Konflikt, offene Genehmigung, fehlende Buchung |
| UI-14 | Belege/OCR | Prüfen / Freigeben | Dublette, XML-Konflikt, fehlende Kostenstelle |
| UI-15 | TCO/Deckungsbeitrag | Ursache analysieren | Datenlücke, Nenner 0, fehlende Partnerrechte |
| UI-16 | Clearing/Abgleich | Gegenbericht / Differenz klären | Unbekannter Transfer, Chargeback, geschlossene Periode |
| UI-17 | Gebiete/Stops/Tarife | Revision freigeben | Ungültige Geometrie, konkurrierende Version |
| UI-18 | Kooperation/Rollen | Grant erteilen / entziehen | Vertragsablauf, offene Reisen, Scopekonflikt |
| UI-19 | Kennzahlen/Forecast/Simulation | Vergleich durchführen | Keine Daten, geringe Qualität, Live-Schreibversuch |
| UI-20 | Datenschutz/Audit/Support | Begründeten Fall bearbeiten | fehlender Zweck, Legal Hold, abgelaufener Zugriff |

UI-Abnahme: Fahrt mit zwei Betreibern und Teilstorno vollständig per Tastatur/Screenreader; gleicher Test mit Callcenter; Driverprüfpfad mit kritischem Nein und Offline-Sync; Financebeleg mit Dublette und Rundungsrest; Mandantenwechsel mit zwei Tabs; 412-Konflikt ohne stilles Überschreiben. Erforderlich sind verständliche Texte und überprüfbare Fachzustände, nicht nur Screenshots.
