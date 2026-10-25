# 03 – Einheitliches Kostenmodell, Zahlungen und Partnerclearing

Status: technischer Spezifikationsentwurf V1.0, 25.10.2026. Diese Regeln konkretisieren Lastenheft V1.2, insbesondere PAY-001–017, DOC-001–030, FCO-001–024, MUL-010/020, KPI-017/018/020/027/028 und **Anhang I.9/COM-001–012**. Die Abschnittsangabe ist zwingend: §6.12 verwendet dieselben COM-IDs für Kommunikation. Fachliche und steuerliche Freigaben sind offene Projektentscheidungen; Beispielsteuersätze sind Testdaten, keine Feststellung des anwendbaren Steuersatzes.

## 1. Verbindliches gemeinsames Modell

Ein wirtschaftlicher Ursprung wird einmal als `economic_event` erfasst und über belegbare Referenzen in Fahrzeugakte, Kostenrechnung, Buchhaltung, Tarifcontrolling und Clearing verwendet. Vier getrennte Summen werden weder gleichgesetzt noch addiert:

| Sicht | Gegenstand | Maßgebliche Quelle | Wesentliche Abgrenzung |
|---|---|---|---|
| Ressourcenverbrauch / Betriebskosten | Energie, Personal, Leasing, Abschreibung, Wartung, Infrastruktur | Freigegebene Kostenereignisse und periodische Abgrenzungen | Zahlungszeitpunkt ist nicht Verbrauchszeitpunkt |
| Fahrgastentgelt | Vertraglicher Preis je Segment, Rabatt, Zuschuss, Gebühren, Steuer | Unveränderliche Angebots- und Tarifversion | Aus Betriebskosten wird kein Fahrpreis automatisch abgeleitet |
| Entgeltanspruch / Clearing | Anspruch Beförderer, Vermittlungsprovision, Subunternehmerleistung | Bestätigte Leistung bzw. vertraglicher Anspruchstatbestand | Eine Kundenzahlung ist nicht in voller Höhe Plattformumsatz |
| Zahlungsfluss | Autorisierung, Einzug, Erstattung, PSP-Transfer, Bankauszahlung | Verifizierte PSP-Ereignisse und Abgleich | Transfer, Rechnung und Zahlung erzeugen nicht erneut Kosten |

`behavior = FIXED | VARIABLE | MIXED` und `attribution = DIRECT | ALLOCATED` sind **zwei Achsen**, keine vier addierbaren Kostenklassen. Eine fixe Versicherungsprämie kann direkt einem Fahrzeug zugeordnet werden; dieselbe Kostenposition wird später auf Dienste umgelegt. Ein Bericht darf ihre Originalposition und Umlage nicht zusammenzählen (FCO-001, KPI-027).

### 1.1 Bewertungsarten und Zeitbezug

Jedes Kostenereignis besitzt `valuation = ACTUAL | PLAN | FORECAST | SIMULATION`, Leistungszeitraum, Buchungsperiode, Erfassungszeit und Bewertungs-/Regelversion. Abfragen benötigen explizit Bewertungsart, Berichtsumfang, Währung, Periode und Datenstand. PLAN wird durch ACTUAL nicht überschrieben. FORECAST eines Monats besteht aus Istkosten bis Stichtag plus Restprognose; eine zusätzliche Prognose des Gesamtmonats wird als alternative Version geführt, niemals auf die Istkosten addiert (FCO-017).

Anschaffung wird im Aktivierungsmodell als Investition, nicht vollständig als Periodenaufwand gebucht. Monatliche Abschreibung oder kalkulatorische Kapitalbindung erscheint in einer ausdrücklich gewählten Bewertungssicht. Leasingrate und Abschreibung desselben wirtschaftlichen Anteils dürfen nicht im selben TCO-Modell doppelt zählen. Materialeinkauf wird bei Lagerführung als Bestand geführt; Verbrauch/Entnahme löst die Kostenzuordnung aus. Tank-/Ladebelege bilden in P1 eine gekennzeichnete Erwerbsnäherung; bei bekannten Bestandsänderungen korrigiert P2 den Periodenverbrauch. Abweichende Füllstände und Ladeverluste bleiben in Verbrauchskennzahlen sichtbar (FCO-004/006/011/012/020).

## 2. Datenvertrag und Integrität

Die implementierbaren Kerntabellen stehen im zentralen Datenbankschema. Die folgenden Felder und Regeln sind der vollständige fachliche Vertrag, auch soweit Detailtabellen erst im jeweiligen Ausbau implementiert werden.

| Objekt | Pflichtinhalt / Relation | Regel |
|---|---|---|
| `economic_event` | Eigentümer-Tenant; buchende juristische Einheit; Ereignisart; Quellsystem und Quell-ID; Revision; Leistungszeitraum; Währung; Netto-/Steuer-/Bruttobetrag; abzugsfähige Steuer; valuation; Kostenart; behavior; ursprünglicher cost_object; Gegenereignisreferenz | Freigegebene Ereignisse unveränderlich; `gross = net + tax`; eindeutiger Ursprung je Einheit/Quellsystem/Quell-ID/Revision/Ereignisart |
| Beleg und Evidenzreferenz | Originalhash, Objektspeicher-ID, MIME, Eingangskanal, OCR-Version, Freigabeversion, Steuerregel | Ein Ereignis kann Foto, Kartenabrechnung und Rechnung als mehrere Nachweise besitzen; Evidenz ist kein weiterer Aufwand |
| `cost_object` | Tenant, juristische Einheit, Typ VEHICLE/SERVICE/SHIFT/JOURNEY_LEG/COST_CENTER/PROJECT/CONTRACT, Referenz | Zugehörigkeit und referenzierte Daten werden serverseitig geprüft; keine direkte Fremdmandantenreferenz als Erlaubnis |
| `allocation_set` / `allocation_line` | Ursprung, Regelversion, Rechenbasis, finale Beträge, Empfänger, valuation, gültige Revision | Pro Ursprung/Bewertung ist genau eine freigegebene Verteilung wirksam; Summe = verteilter Quellbetrag |
| `journal` / `journal_line` | Juristische Einheit, Währung, Periode, wirtschaftlicher Ursprung, Konten, Soll/Haben, Steuer- und Kostenobjektreferenz | Innerhalb jedes Journals gilt Soll = Haben; Veröffentlichung erst nach vollständiger Validierung in einer DB-Transaktion |
| `settlement_line` | Kooperation/Vertragsversion, journey/leg, Leistender, Verkäufer, Schuldner, Gläubiger, Erlös, Steuer, Provision, Anspruchsgrund, Gegenposition, Abrechnungsstatus | Eine Segmentleistung kann mehrere Anspruchskomponenten haben; jede ist separat eindeutig; eine Gegenposition korrigiert, überschreibt aber nie |
| Rechnungs-/Gutschriftenposition | Aussteller und Empfänger als juristische Einheiten, Leistungsart, Leistungszeitpunkt, Steuerregelrevision, Referenz auf Anspruch | Gruppierte Kundendarstellung ersetzt keine pro Aussteller erforderliche Rechnung |
| Exportbatch / Bestätigung | Zielsystem, Batch-ID, eindeutige Journal-ID pro Zeile, Inhaltshash, Empfangsstatus | Wiederholung liefert denselben Batch; Import muss externe ID deduplizieren oder einen dokumentierten manuellen Abgleich erzwingen |

Ergänzende Relationsobjekte: `tax_rule_revision`, `cost_category`, `allocation_rule_revision`, `fiscal_period`, `supplier_invoice`, `payment_order`, `refund_case`, `settlement_statement`, `dispute_case`, `reconciliation_run`. Sie müssen die tenant-/legal-entity-Schlüssel und die Referenzintegrität des Kerndatenmodells übernehmen. Eine Fremdfirma erhält nur eigene Gegenberichte und ausdrücklich freigegebene Leistungsreferenzen; Lieferantenpreise, Personalaufwand oder Margen anderer Betreiber bleiben verborgen (DOC-028, I.9/COM-007, GOV-007).

### 2.1 Beträge, Mengen, Rundung

- Geldbeträge werden als vorzeichenbehaftete `bigint` in der kleinsten Währungseinheit gespeichert; die API begrenzt sie auf ±9.007.199.254.740.991, damit ganzzahlige JSON-Darstellung exakt bleibt. `currency` und versionierter Währungsexponent sind Pflicht. Innerhalb eines Journals gibt es nur eine Währung.
- Mengen/Sätze werden mit `numeric(20,8)` verarbeitet. API-Mengen und Prozentsätze sind Dezimalstrings; IEEE-754-Gleitkomma ist für Geldberechnung unzulässig. Liter, kWh, km und Stunden besitzen explizite Einheiten. L/100 km und kWh/100 km werden nicht gemischt.
- Steuerrundung: je durch Steuerregel bestimmter Gruppe aus Aussteller, Steuerkennzeichen, Steuersatz und Leistungsart auf Währungspräzision, Standard `ROUND_HALF_UP`; die freigegebene Regel kann eine andere verbindliche Rundung bestimmen. Eingehende Originalrechnungsbeträge bleiben erhalten; tolerierte Rundungsdifferenzen sind eigene Positionen. Keine stille Neuberechnung eines erhaltenen Rechnungsbetrags.
- Allocation: ungerundete Quote mit Dezimalarithmetik; zunächst Betrag je Empfänger auf kleinere ganze Minor Units abrunden, Restcent nach größtem Dezimalrest verteilen; Gleichstand nach stabiler Empfänger-ID. Für negative Korrekturen zunächst absoluten Betrag verteilen, dann Vorzeichen anwenden. Wo möglich eine Korrektur mit den **gespeicherten ursprünglichen Teilbeträgen** ausführen, nicht mit einer inzwischen geänderten Regel.
- Beispiel: 100,00 EUR zu drei gleichen Anteilen ergibt bei IDs A < B < C genau 33,34 / 33,33 / 33,33 EUR. Die vollständige Rücknahme lautet −33,34 / −33,33 / −33,33 EUR.
- Fremdwährung wird in P1 nicht automatisch konvertiert. P2 benötigt Wechselkursquelle, Kurszeit, Kursversion und eigene Kursdifferenzbuchung. Beträge unterschiedlicher Währungen sind ohne diese Bewertung nicht summierbar.

### 2.2 Einmalige Wirkung und Konkurrenz

Eine eindeutige Datenbankbedingung sichert den fachlichen Ursprung, nicht allein den HTTP-Idempotenzschlüssel. Ein Retrying-Worker kann daher auch nach abgelaufenem HTTP-Schlüssel keinen zweiten Journaleffekt erzeugen. Auszahlung/Erstattung verwendet zusätzliche eindeutige PSP-Kommandoreferenzen; Webhooks werden in einer Inbox dedupliziert. Eine autorisierte Freigabe erzeugt in einer Transaktion Ereignis, Journal, Allocation und Outbox oder keines davon. Besteht die Buchhaltung aus einem späteren Worker, wird der Ursprung zunächst `APPROVED_PENDING_POSTING`; ein eindeutig referenzierter Worker erstellt das Journal und setzt `POSTED`. Nur `POSTED` zählt in den endgültigen Ist-Bericht.

Die Veröffentlichung prüft Fremdschlüssel, Periodensperre, Kontierung, Summengleichheit, Berechtigungen und Freigaben erneut unter Sperre. Bei konkurrierendem Monatsabschluss gewinnt genau eine Transaktion; nach Abschluss ist die Ursprungsperiode nicht mehr beschreibbar. Kein Kontosaldo wird als beliebig editierbares Feld geführt; Salden sind Projektionen der unveränderlichen Journalzeilen.

## 3. Beleg → Kosten → Rechnung → Abgleich

1. **Erfassung:** Autorisierter Upload in Quarantäne; Hash und Malwareprüfung; unverändertes Original und bearbeitete Ansicht getrennt. Offline-Client erzeugt stabile UUID vor Upload. Zuordnung nur zu eigenen oder ausdrücklich für diesen Zweck freigegebenen Ressourcen (DOC-001/004/007/008/013).
2. **Extraktion:** OCR-Ergebnisse tragen Feldkonfidenz und Quellenkoordinate. Der Nutzer bestätigt oder korrigiert Vorschläge; jede Änderung bleibt als Revision erhalten. Kein OCR-Text wird als Anweisung ausgeführt. Niedrige Qualität, Summenabweichung und widersprüchliche Fahrzeug-/Kilometerdaten blockieren die finanzielle Freigabe oder erzeugen einen explizit freizugebenden Klärfall (DOC-002/003/012).
3. **Dublettenprüfung:** Exakter Hash plus Mandant; zusätzlich Lieferant, Belegnummer, Datum, Währung, Betrag, Karten-/Kassentransaktion. Gleiches Dokument wird verknüpft; unsicherer Ähnlichkeitstreffer wird geprüft. Ein Hashvergleich zwischen Tenants ist kein Auskunftskanal. Kartenimport und Foto werden als Evidenzen desselben Tankereignisses zusammengeführt (DOC-009/019; E2E-065).
4. **Freigabe:** Erfassung, sachliche Prüfung, Budgetfreigabe und Exportberechtigung sind getrennt. Eine zweite Freigabestufe muss eine andere Person sein; Vertretungsregeln erlauben keine Selbstfreigabe. Kostenobjekt, Pflichtfelder, Steuern und vollständiger Split werden vor Posting geprüft (DOC-010/011/018).
5. **E-Rechnung:** Strukturierte UBL-/CII-Daten gegen freigegebene XSD- und Schematron-Version validieren. Unterstützte XRechnung-/ZUGFeRD-Profile explizit in einer Capability-Matrix aufführen. XML bleibt erhalten und ist bei geeigneten hybriden Rechnungen führend; eine PDF-Abweichung erzeugt einen Prüfhinweis. Nicht unterstützte Profile kommen in den Klärworkflow. Eine generische PDF mit eingebettetem beliebigem XML erfüllt den Vertrag nicht (DOC-014/015).
6. **Posting/Export:** Aufwand nur nach Freigabe und einmaliger Buchungswirkung. Tankkarten-Sammelzahlung gleicht Verbindlichkeiten aus; sie erzeugt nicht erneut Kraftstoffaufwand. Export wiederholt dieselben IDs und denselben Datenstand; bestätigte Import-IDs werden gespeichert (DOC-024).
7. **Korrektur:** Nach Posting kein Überschreiben; verknüpfter Gegenbeleg plus ggf. Ersatzbeleg. Nach Periodenschluss Buchung in der nächsten offenen Periode mit Ursprungsleistungszeitraum. Historischer Abschluss bleibt unter seinem Snapshot unverändert; ein heutiger Leistungszeitraumbericht darf die spätere Korrektur separat einbeziehen (DOC-030, FCO-023).

## 4. Mehrere Betreiber, ein Checkout

### 4.1 Rollen- und Preisvertrag

Pro Segment sind `performing_carrier`, `seller`, `payment_recipient`, `invoice_issuer`, `vehicle_owner`, `cost_bearer` und ggf. `subcontractor` getrennte Organisationen. Ein Tenant kann mehrere Rollen tragen; Rollengleichheit darf nicht angenommen werden (MUL-010, I.9/COM-005/008).

Ein Reisepreis besteht aus unveränderlichen `price_components`: Segmentfahrpreis, Steuer, Zuschlag, Rabatt, Zuschuss und freiwilliges Trinkgeld. Jede Komponente nennt Leistenden, Rechnungsaussteller, zahlenden Schuldner, begünstigten Anspruchsinhaber und Tarif-/Steuerrevision. Rabatte nennen zusätzlich Finanzierer und Verteilungsschlüssel. Ein gemeinsamer Verbundrabatt wird **vor Bestätigung** auf Segmente verteilt; spätere Erstattung verwendet diese Beträge. Zuschuss eines Aufgabenträgers kann Betreiberanspruch finanzieren, ohne als Kundenzahlung aufzutreten. Ein Informationssegment ohne buchbares Ticket ist nicht Teil des einzuziehenden Betrags und wird als extern zu bezahlen ausgewiesen.

`customer_payable_total = Summe kundenwirksamer Bruttokomponenten aller kaufbaren Segmente + eigener Checkoutgebühren − kundenwirksamer Rabatte`. Der Betrag ist vom separat ausgewiesenen geschätzten Gesamtaufwand der Reise einschließlich externer Tickets zu unterscheiden. Die Zustimmung umfasst Leistungsanbieter, Preisaufteilung, Umstiegsrisiko, Storno je Segment und ggf. mehrere Rechnungen (PAY-001/003/007/013).

### 4.2 Zahlungsautomat und Abstimmung mit der Reise-Saga

Zahlung: `CREATED → REQUIRES_ACTION → AUTHORIZED → CAPTURE_PENDING → CAPTURED`; reguläre Nebenpfade `AUTHORIZATION_FAILED`, `VOID_PENDING → VOIDED`, `CAPTURE_UNKNOWN → RECONCILING`, `REFUND_PENDING → PARTIALLY_REFUNDED | REFUNDED`. `DISPUTED` wird als separater Streitstatus geführt. Autorisierung, Capture, Refund, Transfer und Payout sind unterschiedliche Objekte/Zustandsautomaten.

- Die Saga hält alle verpflichtenden kaufbaren Segmente, autorisiert den exakt freigegebenen Gesamtbetrag und bestätigt die Segmente gemäß Partnervertrag. Der Capture erfolgt nach erfolgreicher Segmentbestätigung bzw. dem ausdrücklich vereinbarten Anspruchstatbestand. Capture vor Bestätigung ist eine abweichende vertragliche Variante mit zusätzlicher Erstattungspflicht, kein impliziter Fallback.
- Eine Zahlungsautorisierung ist keine Einnahme und keine Auszahlung. Vor erbrachter Leistung können Kundengelder/Partneransprüche Anzahlungen sein. Erlösrealisierung wird je Leistungsart und freigegebenem Kontierungsschema ausgelöst; eine Bestätigung ist kein universeller Umsatzzeitpunkt.
- Bei SCA/3DS-Wartezeit können Holds ablaufen. Ein abgelaufener Hold wird erneut angefragt; geänderter Betrag oder Bedingungen benötigen neue Kundenzustimmung. Eine erfolgreiche Zahlung darf keine abgelaufene Kapazität rekonstruieren.
- Timeout ist `UNKNOWN`, keine Ablehnung. Erst Statusabfrage nach PSP-/Partnerreferenz entscheidet über Wiederholung; bei fehlender Abfragemöglichkeit manueller Klärfall. Kein zweiter Charge auf Verdacht.
- Reise kann bestätigt und Zahlung in Klärung sein; beide Zustände bleiben sichtbar. Freigabe zur Beförderung folgt der ausdrücklich konfigurierten Kredit-/Zahlungspolicy. Ein gescheiterter Capture storniert nicht still eine bereits erbrachte Fahrt.
- Teilstorno erzeugt separate Erstattungsforderung, Provisionskorrektur und Transferreversal. Ein Kundenerstattungsauftrag ist erst bei bestätigtem PSP-Ergebnis bezahlt. Eine fehlgeschlagene Rückholung beim Partner löscht weder Kundenerstattungsanspruch noch Forderung gegen den Partner.

### 4.3 Clearing und Periodenabschluss

`settlement_line` entsteht aus eindeutigem Segment-/Leistungsereignis und eingefrorener Vertragsversion. Zustände: `PROVISIONAL → ELIGIBLE → STATEMENTED → ACCEPTED → PAYOUT_PENDING → PAID`; Streitfallstatus separat mit gesperrtem strittigem Teilbetrag. Monatliche Gegenberichte enthalten dieselben vertraglichen Leistungs- und Anspruchs-IDs, nicht gegenseitige interne Aufwände.

Die Reconciliation vergleicht vier Ebenen: (1) Reise/Segment samt Durchführung, (2) Anspruch/Provision/Steuer, (3) Kundenzahlung/Refund und PSP-Transfers, (4) PSP-Auszahlung/Bankreferenz. Jede Differenz hat Betrag, Alter, Grundcode und Bearbeiter. Provider-Webhooks allein belegen keine Bankauszahlung. Ein geschlossener Monat benötigt einen dokumentierten Umgang mit offenen Differenzen; kein automatisches Wegbuchen. Strittige Forderungen werden isoliert, nicht still verrechnet (PAY-014, I.9/COM-003/004/009/012).

## 5. Rechenbeispiel A: zwei Betreiber, ein Einzug, anschließender Teilstorno

**Reine Testannahmen:** EUR, Betreiber A/B sind jeweils Beförderer und Rechnungsaussteller ihrer Leistung; Plattform P vermittelt. A kostet 10,70 EUR brutto (10,00 netto + 0,70 Steuer), B 21,40 EUR brutto (20,00 + 1,40). Vereinbarte Provision P beträgt 10 % des Nettofahrpreises plus im Beispiel 19 % Steuer. PSP-Gebühr 0,50 EUR trägt P; sie wird im Beispiel bei Erstattung nicht zurückgegeben. Provision wird bei bestätigter Buchung fällig und bei vollständigem Segmentstorno vollständig korrigiert. Steuer- und Erlöszeitpunkte benötigen Freigabe; die Tabelle stellt zunächst Preis/Anspruch dar, keine pauschale sofortige Erlösrealisierung der Beförderer.

| Posten | Segment A | Segment B | Summe |
|---|---:|---:|---:|
| Fahrgastpreis brutto | 10,70 | 21,40 | **32,10** |
| Leistungswert netto | 10,00 | 20,00 | 30,00 |
| Leistungssteuer | 0,70 | 1,40 | 2,10 |
| Vermittlungsprovision netto | 1,00 | 2,00 | 3,00 |
| Steuer auf Provision | 0,19 | 0,38 | 0,57 |
| Betreiberanspruch nach Provisionsverrechnung | **9,51** | **19,02** | **28,53** |

Geldkontrolle: 32,10 = 9,51 + 19,02 + 3,57. Der Plattform steht aus diesem Geschäft netto 3,00 Provision zu, nicht 32,10 Umsatz. Nach 0,50 PSP-Kosten ist ihr Deckungsbeitrag 2,50 EUR; 0,57 Steuer ist kein Ertrag. Ein gemeinsamer Checkout darf die zwei Leistungsrechnungen und die Provisionsrechnung(en) in einer gemeinsamen Übersicht anbieten.

Illustrative Journale der Plattform P, vor Bankauszahlung; für jedes Journal gilt Soll = Haben:

| Vorgang | Soll | Haben |
|---|---|---|
| PSP bestätigt Einzug | PSP-Verrechnung 32,10 | Verbindlichkeit A 10,70; Verbindlichkeit B 21,40 |
| Provisionsanspruch | Verbindlichkeit A 1,19; Verbindlichkeit B 2,38 | Provisionsertrag 3,00; Provisionssteuer 0,57 |
| PSP-Gebühr | PSP-Aufwand 0,50 | PSP-Verrechnung 0,50 |
| Auskehrung an A und B bestätigt | Verbindlichkeit A 9,51; Verbindlichkeit B 19,02 | PSP-Verrechnung 28,53 |

Nun wird ausschließlich B ohne Stornogebühr storniert; A bleibt gültig. Die ursprüngliche Rechnung/Anspruchsaufteilung bleibt erhalten. Neue Gegenpositionen betragen: Fahrgastforderung −21,40, Beförderungswert −20,00, zugehörige Steuer −1,40, Provision −2,00, Provisionssteuer −0,38. B schuldet nach erfolgter Auskehrung 19,02 zurück, P korrigiert 2,38 Provision brutto; zusammen stehen 21,40 für die Erstattung bereit.

| Korrekturvorgang P | Soll | Haben |
|---|---|---|
| Provisionskorrektur B | Provisionsertrag 2,00; Provisionssteuer 0,38 | Verbindlichkeit B 2,38 |
| Erfolgreiches Transferreversal B | PSP-Verrechnung 19,02 | Verbindlichkeit B 19,02 |
| Erfolgreiche Fahrgasterstattung | Verbindlichkeit B 21,40 | PSP-Verrechnung 21,40 |

Ergebnis: Kunde netto belastet 10,70; A erhält 9,51; B netto 0; P behält brutto 1,19 minus PSP-Kosten 0,50 = 0,69, davon 0,19 Steuer und **0,50 Deckungsbeitrag**. Vorleistung/Anzahlung bei B wird nach ihrem Kontierungsschema korrigiert, nicht fiktiv in eine ausgeführte Fahrt umgewandelt.

Kann B den Transfer nicht zurückgeben, wird eine Forderung an B von 19,02 statt eines erfolgreichen PSP-Zuflusses gebucht. Der Erstattungsanspruch des Kunden bleibt 21,40. Die freigegebene Ausfall-/Liquiditätsregel entscheidet, ob P ihn vorfinanziert; keine stille Kürzung auf 2,38. Quellenrecherche bestätigt, dass Transferreversal bei unzureichendem Partnerguthaben scheitern kann [Q-K1].

## 6. Rechenbeispiel B: Fahrzeug X fährt für Betreiber Y

**Testannahmen:** Eine Periode, Nettovergleich bei vollständig abzugsfähiger Vorsteuer; Eigentümer X, Disponent/Beförderer Y, Auftraggeber K1/K2. X stellt das Fahrzeug für 100 tatsächlich bestätigte Dienststunden. Vertraglich berechnet X an Y 800,00 EUR netto; für die Demonstration kommen 152,00 Steuer hinzu. Die Überlassung gewährt Y Betriebsfähigkeit/Prüfstatus, aber keinen Zugriff auf Xs Einkaufsbelege.

| Ursprung und Klassifikation | X | Y |
|---|---:|---:|
| Leasing/Versicherung, fix, direkt Fahrzeug | 300,00 | – |
| Wartung, variable Testzuordnung, direkt Fahrzeug | 100,00 | – |
| Vertragliche Fahrzeuggestellung von X | Erlös 800,00 | Aufwand 800,00 |
| Energie, variabel, direkt Fahrzeug | – | 180,00 |
| Fahrer, variabel für diese Testschicht, direkt Dienst | – | 600,00 |
| Disposition, fix, umgelegt | – | 100,00 |

Y weist **1.680,00 EUR operative Vollkosten** aus: 800 + 180 + 600 + 100. Darin ist Xs Gestellungspreis enthalten; Xs 400 eigene Kosten dürfen nicht zusätzlich auf Ys Aufwand addiert werden. X führt 400 eigene Kosten, 800 Erlös und 400 Ergebnis vor weiteren Kosten. Die 152 Steuer der Gestellungsrechnung sind bei der angenommenen vollständigen Vorsteuerabzugsfähigkeit kein Aufwand bei Y. Ohne Abzugsfähigkeit gehören sie als eigene Kostenkomponente in die TCO-Sicht.

Freigegebener Leistungsschlüssel: 60 h für K1 und 40 h für K2. Sämtliche Positionen werden für dieses Beispiel nach Dienststunden verteilt: K1 **1.008,00**, K2 **672,00**, Summe **1.680,00**. Jede Verteilung referenziert denselben Leistungsnachweis und die Vertragsrevision. Die entstandene Rechnung X→Y wird als bilaterale Fremdleistung geführt; keine mandantenübergreifende Umbuchung von Xs Originalaufwand auf Y.

Ein berechtigter Verbundbericht kann optional eine **konsolidierte Ressourcensicht** erstellen. Dafür benötigt er eine eigene Freigabe und eliminiert die zusammengehörige interne Erlös-/Aufwandsbeziehung X→Y von 800. Konsolidierte externe Ressourcenkosten: X 400 + Y 180 + 600 + 100 = **1.280,00 EUR**. Diese konsolidierte Zahl ersetzt weder Ys einzelbetriebliche 1.680 noch die Rechnung. Ohne freigegebene Daten von X ist diese Sicht `NOT_AVAILABLE`, kein geschätzter Vollkostenwert (FCO-016, MUL-020, E2E-072/094/095).

Bei 2.000 relevanten Fahrzeug-km, 300 abgeschlossenen Fahrgastfahrten, 2.500 Fahrgast-km und 100 Dienststunden für dieselbe Periode beträgt Ys Kostenbasis: **0,84 EUR/Fahrzeug-km**, **5,60 EUR/Fahrgastfahrt**, **0,672 EUR/Fahrgast-km**, **16,80 EUR/Dienststunde**. Anzeige rundet 0,672 ggf. auf 0,67, exportiert aber die Berechnungsbasis. Doppelzählung einer Fahrgastfahrt in zwei Segmenten ist nur in der ausdrücklich als Segmentfahrt bezeichneten Kennzahl zulässig; Gesamtjourneys werden separat gezählt.

## 7. Kennzahlen und fachliche Abnahme

| Kennzahl | Konkreter Vertrag |
|---|---|
| Vollkosten | Summe wirksamer Ist-Aufwände inkl. nicht abzugsfähiger Steuer und freigegebener periodischer Bewertungen; keine Investitionssumme zusätzlich zur Abschreibung; keine zusätzlichen Transfer-/Zahlungsbeträge |
| Deckungsbeitrag | Versionierte Definition: Nettoerlöse minus direkt zurechenbare variable Kosten. Fixkosten separat. Eine zusätzliche DB-Stufe nach weiteren Kosten muss benannt werden |
| Zuschussbedarf | Freigegebene Kostenbasis minus tarifliche Nettoerlöse minus bereits berücksichtigte andere Deckung; bereits verbuchte Zuschüsse nicht doppelt abziehen |
| Kosten je Einheit | Gleiche Periode und Umfang für Zähler/Nenner; Nullnenner ergibt `null` + `ZERO_DENOMINATOR`; unvollständige Quellen ergeben Status `PROVISIONAL` + Abdeckungsgrad |
| Energie | Validierte Liter bzw. kWh, korrespondierende km, dokumentierte Bestands-/Ladeverluste; niemals Durchschnitt über unvereinbare Einheiten |
| Budget | Ist und Plan derselben Bewertung; Abweichung absolut und prozentual, bei Plan 0 nur absolut; Snapshot und Regelversion in Export und UI |

Abnahmefälle zusätzlich zu den referenzierten Lastenheftfällen:

1. **COST-01 / DOC-009, E2E-065:** Foto, Tankkartenimport und Wiederholung des Freigabejobs liefern genau ein gebuchtes Tankereignis; die drei Evidenzen bleiben nachvollziehbar.
2. **COST-02 / DOC-011, E2E-072:** 10.000 Minor Units im Drittelsplit → 3.334/3.333/3.333; Korrektur hebt exakt die ursprünglichen Beträge auf. Falsche Summe blockiert Posting.
3. **COST-03 / I.9/COM-002/005/008, E2E-093:** Beispiel A reproduziert 32,10 Einzug, 28,53 Auskehrung, 3,57 Provision brutto; nach B-Storno 10,70 Kundennettobelastung. Jeder Einzeljournalabschluss ist ausgeglichen.
4. **COST-04 / PAY-014, E2E-090/104:** Doppelte, verspätete und ungeordnete Webhooks erzeugen keine zweite Zahlung, Erstattung oder Clearingposition; unklare PSP-Antwort bleibt offen bis Reconciliation.
5. **COST-05 / FCO-023, E2E-073:** Periodenschluss konkurriert mit einer Freigabe; genau eine Reihenfolge gewinnt. Nachträgliche Gutschrift erzeugt neue Periode und lässt Abschluss-Snapshot unverändert.
6. **COST-06 / MUL-010/020, E2E-094:** Beispiel B ergibt Y 1.680, konsolidiert nur bei Berechtigung 1.280. Y sieht keine X-Lieferantenpreise oder Rohbelege.
7. **COST-07 / DOC-014/015, E2E-067/068:** Ungültiges XML und PDF/XML-Abweichung liefern konkrete Prüffehler; zwei Freigaben derselben Person erfüllen keine Vier-Augen-Freigabe.
8. **COST-08 / KPI-018/027:** Fehlende Energiedaten werden als fehlend markiert, nicht als null Kosten; Kosten/Fahrgast bei null Fahrgästen ist `null`. Original plus Umlage wird in keiner Standardsicht doppelt summiert.
9. **COST-09 / I.9/COM-004/007/011:** Ausgelaufene Kooperation verhindert Neugeschäft; laufende Erstattungs- und Gegenberichtsrechte bleiben eng begrenzt. Streitbetrag wird nicht automatisch ausgezahlt.
10. **COST-10 / PAY-001/006/008:** Verbundrabatt auf zwei Segmente ist beim Checkout centgenau verteilt; Teilstorno verwendet eingefrorene Rabattanteile und nennt eventuelle vertragliche Rabatt-Rückforderung vor Bestätigung, ohne automatische Neubepreisung.

## 8. Erforderliche Entscheidungen mit Varianten und Favorit

| Entscheidung des AG | Varianten | Favorit und Begründung | Freigabe vor |
|---|---|---|---|
| Finanzielle Systemgrenze | Vollständiger ERP-/Fibu-Ersatz; operatives Nebenbuch mit ERP-Export; reine Belegweiterleitung | **Favorit: operatives Nebenbuch mit ERP-Export.** Gemeinsame Kosten-/Anspruchsdaten direkt für Betrieb und Abnahme; Steuerabschluss, Bankwesen und Lohn bleiben spezialisierten Systemen zugeordnet. Das erfüllt H.2 ohne einen ungeplanten ERP-Neubau | Konten-/Exportmodell und P2-Finanzintegration |
| Verkäufer-/Zahlungsmodell | P als alleiniger Verkäufer/Merchant; Betreiber als Verkäufer mit PSP-Mehrparteienzahlung; je Segment separater PSP-Charge | **Favorit: Betreiber bleiben Verkäufer; ein Checkout mit PSP-Mehrparteienlösung**, soweit PSP-Vertrag und rechtliche Rollen dies erlauben. Kapazitäts- und Steuerhoheit bleiben nachvollziehbar; kein ungeprüftes Übernehmen sämtlicher Beförderungsrisiken durch P. Separate Charges sind transparenter Fallback, führen aber zu mehreren Belastungen und ggf. Authentifizierungen | PSP-Auswahl, Verträge, rechtliche Prüfung und erster realer Einzug |
| Träger von Refund-/Chargeback-/Partnerausfallrisiko | Plattformreserve; Betreiberreserve; Mischmodell mit Sicherheiten | **Favorit: vertragliche Betreiberhaftung plus definierte PSP-/Plattformreserve und Limits.** Kundenerstattungen dürfen nicht technisch vom Erfolg der Partner-Rückholung abhängen. Finanzierung, Fälligkeiten und Obergrenzen sind AG-Beschluss | Freigabe von Einzug/Auskehrung |
| Umlageschlüssel und TCO | km; Dienststunden; belegte Personen-km; Mischung | **Favorit: kostenartbezogene Schlüssel** – Energie nach plausibilisierten km/kWh, zeitgebundene Ressourcen nach Dienststunden, fixe Fahrzeugkosten nach verfügbarem Periodenumfang; verbleibende Leerkapazität sichtbar. Beispiel B verwendet bewusst einen einfachen Testschlüssel | erster verbindlicher Kostenbericht |
| Steuer-/Belegmodell | eine P-Leistungsrechnung; mehrere Betreiberrechnungen; Sammelübersicht plus Einzelbelege | **Favorit: gemeinsame Übersicht plus rechtskonforme Einzelbelege der tatsächlichen Aussteller.** Steuersätze, Vorsteuerabzug, Zuschüsse, Gutscheine, Provisions-/Beförderungsleistung müssen pro Rechtsbeziehung freigegeben werden | produktive Faktura |
| Archiv/Export | nur Objektspeicher; revisionsfähiges Archiv mit ERP; voll integriertes DMS | **Favorit: unveränderte Originale, manipulationsgeschützte Aufbewahrung und dokumentierter ERP-/Archivexport.** Fristen je Dokument-/Rechtsraumversion, Legal Hold, Datenschutzlöschung und Wiederherstellung explizit prüfen | produktiver Dokumentenbetrieb |

Die technische Architektur unterstützt diese Varianten. Die Favoriten sind Planungsannahmen und werden erst durch einen dokumentierten AG-Beschluss zu Geschäftsbedingungen. Keine der Varianten darf durch eine bloße PSP-API-Auswahl als rechtlich geklärt markiert werden. Forschungsstand und Zugriffsgrenzen: [recherche-kosten.md](recherche-kosten.md).
