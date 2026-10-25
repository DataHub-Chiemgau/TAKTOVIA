# 07 – Entscheidungsvorlage für den Auftraggeber

Stand 25.10.2026. **Favorit** bedeutet begründete Empfehlung für dieses Projekt, keine bereits erteilte Zustimmung. Technische Details können auf Basis dieser Defaults weiter spezifiziert und in einer Sandbox geprüft werden. Rechtsträger-, Geldfluss-, Genehmigungs- und Datenverantwortungsfragen sind vor produktiver Aktivierung verbindlich zu entscheiden. Ein ausbleibender Beschluss ist keine Freigabe.

Die Recherche stützt sich auf tatsächlich abgerufene offizielle PostgreSQL-, TOMP-, UIC/OSDM-, GTFS-, Stripe- und KoSIT-Quellen. Belege und Zugriffsgrenzen stehen in [Recherche Föderation](recherche-federation.md) und [Recherche Kosten](recherche-kosten.md). Ein API-Standard oder PSP-Endpunkt beweist keine vertragliche Vertriebsberechtigung. Aufwandseinschätzungen und Favoriten sind die projektbezogene Bewertung dieser Quellen.

## D-01 – Wer verkauft, befördert und verantwortet die Reisekette?

| Variante | Konsequenz | Empfehlung |
|---|---|---|
| Plattform vermittelt, Betreiber sind Verkäufer/Beförderer ihrer Segmente | Ein Reiseauftrag mit mehreren Leistungs-/Rechnungsverhältnissen; Plattform koordiniert Support und vertraglich begrenzte Kompensation | **Favorit**: passt zur lokalen Betreiberhoheit und nachgewiesenen Partnerrechten |
| Plattform verkauft die Gesamtreise als eigener Vertragspartner | Einheitlicher Kundenvertrag möglich; Plattform übernimmt weitergehende Leistungs-, Steuer-, Zahlungs- und Anschlussrisiken | Nur bei bewusst gewähltem Geschäftsmodell und entsprechenden Verträgen |
| Reines Auskunftsportal mit Weiterleitung | Geringere Transaktionsintegration, externe Käufe | Fallback für Partner ohne Buchungsrechte, erfüllt allein den gewünschten durchgängigen Kauf nicht |

**Benötigter Beschluss:** Rechtsträgerliste mit Rolle je Dienst, datenschutzrechtlicher Verantwortlichkeit, Rechnungsaussteller, Support- und Anschlussverantwortung. Federführung AG/Betreiber/Recht/Datenschutz; vor Partnerverträgen und produktivem Checkout. Favorit beruht auf Lastenheft M, P.2 und [F-03/F-04](recherche-federation.md), nicht auf einer rechtlichen Zulässigkeitszusage.

## D-02 – Wie stark müssen Mandanten technisch isoliert werden?

| Variante | Konsequenz | Empfehlung |
|---|---|---|
| Gemeinsame PostgreSQL-Datenbank mit RLS, lokalen Schlüsseln und geteilten Projektionen | Wirtschaftlicher Betrieb und ein Migrationspfad; sorgfältige Tests und gemeinsame Ressourcen nötig | **Favorit** für den Pilot |
| Datenbank pro Mandant | Getrennte Zugänge/Restore; mehr Migrationen, Pools und Betriebsaufwand | Für besondere Verträge/Schutzklassen |
| Eigener Cluster/Cloudbereich je Mandant | Getrennte Ressourcen/Administrationsgrenzen möglich | Nur bei verbindlicher Souveränitäts-/Isolationsvorgabe |

**Benötigter Beschluss:** Gibt es Betreiber mit Pflicht zu eigener Datenbank, Schlüsselhoheit, eigenem Administrator oder bestimmter EU-/DE-Hostingregion? Ohne solche Vorgaben ist RLS der technische Planungsdefault; Fremdzugriffe bleiben in allen Varianten API-/projektionsbasiert. Schema/Partition pro Tenant allein ist kein gleichwertiger Sicherheitsnachweis. Quellen F-01/F-02.

## D-03 – Gemeinsamer Checkout und Geldfluss

| Variante | Konsequenz | Empfehlung |
|---|---|---|
| PSP-Mehrparteienmodell mit einem Checkout und zugeordneten Betreiberansprüchen | Gemeinsame Kundenzahlung; PSP-konformes Onboarding, Settlement, Refunds und Reversals; mehrere Rechnungen möglich | **Favorit**, sofern der PSP das konkrete Rollen-/Ländermodell vertraglich unterstützt |
| Getrennte Zahlung je Betreiber in einer gemeinsamen Oberfläche | Je Betreiber klarer Zahlungsempfänger; mehrere Authentisierungen/Teilfehler und aufwendigere UX | Fallback, wenn gemeinsamer Einzug nicht zulässig oder nicht verfügbar |
| Plattform als alleiniger Merchant/Seller | Ein Einzug und eigener Vertrieb; zusätzliche Finanz-, Steuer-, Chargeback- und Leistungspflichten | Nur zusammen mit bewusster Entscheidung D-01 |

**Benötigter Beschluss:** Rollen, Zahlungsarten, Capturezeitpunkt (sofort/nach Fahrt/bei Vorausbuchung), Gebühren-/Chargebackträger, Rückerstattungsreserve und PSP-Auswahl. Stripe Connect ist eine technisch untersuchte Variante, keine vorausgewählte Beschaffung. Derselbe Nachweis ist von alternativen PSPs einzuholen: SCA, Holdfristen, Split/Transfer, Statusabruf, Teilrefund, negative Salden, Gebühren, Sandbox und SLA. Quelle Q-K1. Ein gemeinsamer Checkout darf nie als automatisch erlaubtes Einsammeln fremder Gelder ausgelegt werden.

## D-04 – Anschlussrisiko und unvollständige Mehrbetreiberbuchung

| Variante | Konsequenz | Empfehlung |
|---|---|---|
| Nur vollständig bestätigte buchbare Kette; Kompensation bei Teilfehler | Restliche Kosten-/Ausfallrisiken benötigen klare Zuständigkeit; kein stiller Teilkauf | **Favorit** |
| Bewusst akzeptierter Teilkauf | Fahrgast kann verbleibendes Segment weiter nutzen; konkreter Preis/Risiko vorab sichtbar | Option für geeignete Produkte, standardmäßig aus |
| Vertragliche Anschlussabsicherung inklusive Ersatz/Erstattung | Höherer Kundennutzen; messbare Echtzeitqualität und Kostenträger notwendig | Gezielte spätere Partner-/Produktoption |

**Benötigter Beschluss:** Wer trägt nicht stornierbare Segmentkosten, Ersatzbeförderung und Gebühren? Welche Übergänge werden abgesichert und in welchem Umfang? Welche Fälle darf die Leitstelle entscheiden? Technischer Default ist transparente Nichtgarantie und fehlertolerante Saga. Keine Kombination mit unkontrollierbaren Restpflichten als garantierter Komplettkauf aktivieren. Quellen F-03/F-04 und Lastenheft M.2.

## D-05 – Pilot, Peaklast, SLA und Betrieb

**Favorit:** Zwei kooperierende Betreiber, ein definierter Übergabehub, begrenzte Region und eine zusätzliche nicht buchbare ÖPNV-Verbindung als Pilot. Dadurch werden die entscheidenden Föderationsfälle früh geprüft. Alternative: Einbetreiberpilot ist einfacher, validiert jedoch keine Mehrbetreiberreise; flächiger Mehrregionenstart erhöht gleichzeitig Vertrags-, Daten- und Betriebsrisiken.

**Benötigte Angaben:** Betreiber/Dienste, Genehmigungsmodell, Flottenzahl je Betreiber, Regionen, Peak-Anfragen/s, gleichzeitige Suchen/Buchungen, GPS-Frequenz, Partnerlatenzen, Budget, Hostingregion und Supportzeiten. Zielwerte des Lastenhefts bleiben vorläufig: 99,9 %, Standard-API p95≤1 s, Angebot p95≤5 s, 250 Fahrzeuge/Mandant, 30.000 Buchungen/Tag, 5.000 Sessions, RPO≤15 min/RTO≤2 h. 30.000/Tag ist kein Peakprofil. Bei 5-s-Angebotsbudget: 1 s lokale Planung, 3 s begrenztes Partnerfenster, 1 s Integration/Reserve; pro Partner zunächst 1,5 s, kontrollierte Wiederholung nur innerhalb Gesamtbudget. Endgültige Werte entstehen durch Pilotbenchmark.

## D-06 – Finanzumfang und ERP-Anbindung

**Favorit:** Plattform führt operatives Kosten-/Erlösnebenbuch mit nachvollziehbaren Journalen, Belegen, Umlagen, Clearing und wiederholsicherem Export; bestehende Finanzbuchhaltung bleibt das abschließende Hauptbuch. Alternative vollständiger ERP-/Fibu-Ersatz erweitert Umfang um Kontenführung, Abschlüsse, Steuer-/Zahlungsverkehr, gesetzliche Verfahren und länderspezifische Wartung erheblich.

**Benötigte Angaben:** Ziel-ERP/DATEV-Variante, Kontenrahmen, Kostenstellen, rechtliche Einheiten, Steuerregeln, Vorsteuerabzug, Rundung, Periodenabschluss, Export-/Importbestätigungen, zulässige E-Rechnungsprofile und Aufbewahrung. Beleg-/XML-Validierung über KoSIT ist technische Evidenz, keine automatische GoBD-/Steuerfreigabe. Quellen Q-K2/Q-K3/Q-K4.

## D-07 – Einheitliche Kostenbewertung und interne Verrechnung

**Favorit:** Istkosten nach wirtschaftlichem Ursprung und Leistungsperiode, getrennte Plan-/Forecast-Sichten, Verteilung nach verursachungsgerechten Treibern (km, Betriebszeit, reservierte Kapazität oder freigegebener Vertrag); direkte Kosten zuerst zuordnen, gemeinsame Kosten transparent umlagen. Fix/variabel und direkt/umgelegt bleiben zwei Achsen. Intercompany-Rechnungen und Konsolidierungseliminierung werden separat geführt.

Varianten: reine Kilometerumlage ist einfach, verzerrt Standzeit-/Spezialfahrzeugkosten; reine Zeitumlage ignoriert energie-/kilometerabhängige Unterschiede; vertragliche Pauschalen sind für Partneransprüche sinnvoll, aber kein Ersatz der internen Istkostensicht. **Benötigter Beschluss:** Welche Kostenarten/Treiber, Abschreibung/Leasing, Energiebevorratung, Zuschüsse, Leer-/Besetztkilometer und Deckungsbeitragsstufen gelten? Welche Organisation darf welche konsolidierten Werte sehen? Beispiele in Kapitel 03 sind Testannahmen, keine Steuer-/Preisvorgaben.

## D-08 – Partnerstandards und tatsächliche Buchungsrechte

**Favorit:** Interner kanonischer Journeyvertrag, Adapter je Partner: TOMP für geeignete DRT/MaaS-Flows, OSDM für tatsächlich buchbare Bahn-/ÖPNV-Vertriebsprodukte, GTFS/GTFS-RT bzw. NeTEx/SIRI/VDV für die vorhandene Fahrplan-/Echtzeitversorgung. Alternative „ein Standard für alles“ spart nur scheinbar Aufwand, wenn Fähigkeiten/Verträge abweichen.

**Benötigte Angaben:** Pilotpartner, technische Ansprechpartner, Versionen, Sandbox, Datenlizenzen, Such-/Hold-/Confirm-/Status-/Cancel-/Refundrechte, Idempotenz und Support. Erst Capability-/Vertragstest entscheidet über `PLATFORM_BOOKABLE`; fehlender Ticketverkauf bleibt expliziter externer Abschnitt. Quellen F-03 bis F-05.

## D-09 – Routing, Karten und Telematik

**Favorit:** Providerunabhängiger Adapter, für Pilot ein regional validierter Valhalla-/OSM-Routingdienst und vorhandene vertraglich freigegebene Echtzeitdaten. Alternative kommerzieller Routingdienst reduziert Eigenbetrieb und kann bessere Verkehrsdaten liefern, bindet jedoch an Preis-/Lizenz-/Datennutzungsbedingungen. Auswahl nicht nur nach Funktionsliste, sondern nach Teststrecken, Fuß-/Rollstuhlwegen, Restriktionen, Matrixlatenz und zulässiger Datenspeicherung.

**Benötigte Angaben:** Daten-/Kartenlizenzen, reale Haltepunkte, barrierefreie Zugänge, Telemetrieanbieter und E-Ladeinfrastruktur. Diese konkrete Routinganbieterwahl ist noch kein vergleichend recherchierter Beschaffungsentscheid; vor Festlegung ist ein streckenbezogener Spike mit zwei verfügbaren Varianten erforderlich. Die Architektur hält beide offen.

## D-10 – Fahrerdaten, ETA und Mitbestimmung

**Favorit:** P1 sichere regionale/fahrzeugbezogene ETA ohne individuelles Fahrerprofil. Personalisierung separat freigeben, nur aus ausreichend validen rechtmäßigen Beobachtungen, mit begrenztem Zweck und eigener Zugriffs-/Löschklasse. Alternative individuelle ETA bereits im Pilot verbessert möglicherweise Prognosen, erhöht aber Prüf-/Freigabeaufwand und benötigt belastbare Daten. Leistungsranglisten bleiben außerhalb des spezifizierten Zwecks.

**Benötigter Beschluss:** Betriebsrat-/Personalvertretungsbeteiligung, Datenschutzprüfung/DSFA, zulässiger Modellzweck, Beobachtungsfenster, Mindeststichprobe, Qualitätsgrenzen, menschliche Beanstandung und Löschfrist. Fachfreigaben sind technische Aktivierungsvoraussetzungen; rechtliche Quellen aus Lastenheft P wurden in dieser Recherche nicht vollständig neu geprüft.

## D-11 – Aufbewahrung, Datenrechte und Notbetrieb

**Favorit:** Objekt- und zweckbezogene Löschmatrix, minimale operative Standort-/Kontakthaltung, separate gesetzlich erforderliche Finanzarchive und eng begrenzte laufende Reisefreigaben nach Kooperationspause. Alternative pauschale Daueraufbewahrung ist fachlich nicht erforderlich; pauschale Sofortlöschung kann laufende Beförderung und Nachweise gefährden.

**Benötigter Beschluss:** Verantwortliche je Datenfluss, Löschfristen, Legal Holds, Auskunfts-/Exportprozess, zulässiger Supportzugriff und Wiederanlauf. Offline dürfen Fahrer letzte bestätigte Aufträge sehen und Beobachtungen erfassen; neue technische Freigaben bleiben serverpflichtig. Betreiber müssen den konkreten Notbetriebs-/Kontaktprozess und berechtigte Freigaberollen liefern (O-14/O-16–24).

## D-12 – Reihenfolge der Lieferung und Quellbereinigung

**Favorit:** Technische Grundlage von Mandantentrennung, Kostenereignis und Föderationsvertrag ab P1; Produktfreigaben nach den Originalphasen. Pilotabnahme prüft die Mehrbetreiberkette früh in Sandbox. Ein vorgezogenes produktives P2-Feature braucht einen ausdrücklichen Umfangs-/Terminbeschluss. Web zuerst, Driver-Client mit nötigem Offline-/Gerätezugriff; native Passenger-App gemäß P2.

**Benötigter Beschluss:** Budget/Team/Termin, priorisierte Pilotpartner, gewünschte Vorziehung einzelner P2-Anforderungen; Bereinigung doppelter COM-IDs in einer späteren Lastenheftrevision. Bis dahin bleiben Original-IDs erhalten und werden durch Abschnitt/`source_key` eindeutig. Alle 202 P1-Anforderungen sind MUSS; eine kleine Demo ersetzt nicht diese P1-Abnahme.

## Beschlussprotokoll

Je Entscheidung sind `decision_id`, gewählte Variante, Begründung, verantwortlicher Entscheider, Datum, betroffene Anforderungs-Keys, Vertrags-/Policyversion und Bedingungen zu protokollieren. Zuerst benötigt werden D-01/D-03/D-04 (Geschäftsmodell), D-05/D-08 (Pilot/Partner) und D-06/D-07 (Finanzregeln). D-02 kann dem technischen Favoriten folgen, sofern keine abweichenden Verträge vorliegen. Die technische Ausarbeitung ist mit diesen offen ausgewiesenen Punkten prüfbar; eine Produktivfreigabe wird daraus nicht abgeleitet.
