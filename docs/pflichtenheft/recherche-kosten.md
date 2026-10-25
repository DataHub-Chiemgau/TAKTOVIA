# Rechercheprotokoll – Kosten, PSP und E-Rechnung

Recherche/Abruf: **25.10.2026**. Aus der Cloud-Umgebung wurden die unten genannten offiziellen GitHub-Ressourcen über HTTPS mit normaler TLS-Prüfung abgerufen. Die aufgeführten Quellen ergaben HTTP 200; SHA-256 bezieht sich auf die tatsächlich gelesenen Bytes. Branch-URLs sind veränderlich: Die Hashes dokumentieren den Rechercheinhalt, nicht eine empfohlene Produktivversion. Ein späterer Implementierungsauftrag muss freigegebene Releases pinnen.

## Q-K1 – Stripe: veröffentlichte OpenAPI-Spezifikation

Quelle: https://raw.githubusercontent.com/stripe/openapi/master/openapi/spec3.json

Projekt: https://github.com/stripe/openapi

Abruf: HTTP 200; 8.317.513 Bytes; SHA-256 `7cff4cc46d0654101a36a3302f7544640f5f73e4f3cad6f264f0e23c19fa1776`.

Ausgewertete Stellen: `components.schemas.transfer`, `transfer_reversal`, `refund`, `payment_intent`, `charge` und `paths./v1/transfers/{id}/reversals.post`.

Belegte Aussagen:

- `transfer` bewegt Geld zwischen Stripe-Konten im Connect-Kontext; Transfer und Bank-/Kartenauszahlung (`Payout`) sind getrennte Objekte.
- `transfer_reversal` kann vollständig oder teilweise erfolgen. Die Beschreibung nennt für `transfer_group`-Transfers ausreichendes Guthaben des Empfängerkontos als Bedingung. Die API verhindert eine Rücknahme über den noch nicht reversierten Betrag hinaus.
- `refund` ist die Erstattung eines bereits erstellten Charge an das ursprüngliche Zahlungsmittel. Es ist ein anderes Objekt als `transfer_reversal`.
- `payment_intent` führt Authentifizierung und Statuswechsel eines Zahlungsvorgangs; die Beschreibung empfiehlt genau einen PaymentIntent pro Order/Kundensitzung und nennt maximal einen erfolgreichen Charge pro Intent.
- Felder `transfer_group`, `source_transaction`, `transfer_data`, `application_fee_amount` und `on_behalf_of` zeigen technische Verknüpfungen zwischen Zahlung, Verteilung und Connected Account. `on_behalf_of` beschreibt einen Settlement Merchant, keine beliebige Liste aller Beförderer.

Architekturfolgerung: Ein Checkout kann einen Zahlungsauftrag und mehrere Segmentansprüche zusammenführen. Auszahlung, Erstattung, Provisionskorrektur und Rückholung von Partnern brauchen jedoch eigene Operationen und Reconciliation. **Ein gemeinsamer PaymentIntent beweist weder eine erlaubte Mehrverkäufer-Rechnung noch eine bestimmte Haftungsverteilung.** Diese bleiben Vertrags-/Rechtsentscheidungen. Stripe dient als konkret untersuchte technische Variante, nicht als verbindliche Anbieterwahl; ein anderer PSP muss denselben Capability-/Fehlernachweis liefern.

Für eine abschließende Anbieterauswahl noch zu prüfen: unterstützte Länder und Währungen, zulässige Geschäftsrollen, KYC/KYB, SCA-Holds, Capturefristen, Split-Support, Ausfall-/Chargeback-Haftung, Reserven, Datenhaltung, Gebühren und Rückerstattung dieser Gebühren. Die Gebühren im Pflichtenheftbeispiel sind synthetische Testdaten.

## Q-K2 – KoSIT: XRechnung-Geschäftsregeln

Quelle: https://raw.githubusercontent.com/itplr-kosit/xrechnung-schematron/master/README.md

Projekt: https://github.com/itplr-kosit/xrechnung-schematron

Abruf: HTTP 200; 1.497 Bytes; SHA-256 `600d55b997645f5412ff9813537eb718238a1c9696cda8096c34d58b20a71fb2`.

Belegte Aussagen: Das Projekt beschreibt Schematron-Regeln für die deutsche CIUS XRechnung entsprechend EN16931:2017. Es erklärt Versionierung und weist darauf hin, dass Änderungen das Validierungsverhalten beeinflussen können.

Architekturfolgerung: Rechnungsvalidierung braucht gespeicherte Regel-/Releaseversionen und reproduzierbare Prüfberichte. Ein einmal erfolgreiches XML-Parsing reicht für fachliche Rechnungskonformität nicht aus.

## Q-K3 – KoSIT: Validator-Konfiguration

Quelle: https://raw.githubusercontent.com/itplr-kosit/validator-configuration-xrechnung/master/README.md

Projekt: https://github.com/itplr-kosit/validator-configuration-xrechnung

Abruf: HTTP 200; 2.025 Bytes; SHA-256 `5be65e76f189eba5a66ebf80e39a86c64f20f905afece10ece42f2b9c42a787f`.

Belegte Aussagen: Die Konfiguration validiert UBL- und UN/CEFACT-Rechnungs-/Gutschriftdokumente gegen EN16931, XRechnung-CIUS und Erweiterungen. Releasepakete enthalten erforderliche XML-Schemata und Schematron-Regeln. Der README nennt die Einbindung von EN16931-Regeln, UBL 2.1 und UN/CEFACT CII.

Architekturfolgerung: **Favorit** ist ein abgegrenzter Validierungsadapter mit geprüfter KoSIT-Konfiguration für XRechnung und separater, profilbezogener ZUGFeRD-Unterstützung. Kein pauschales Versprechen, jedes ZUGFeRD-Profil durch dieselbe XRechnung-Konfiguration zu validieren. Original, strukturierte Daten, Validatorversion und Prüfbericht werden getrennt erhalten. Die Entscheidung operatives Nebenbuch statt vollständigem ERP ergibt sich aus dem Projektumfang und dieser Integrationsgrenze, nicht aus einer externen Normpflicht.

## Q-K4 – PostgreSQL: exakte numerische Typen

Quelle: https://raw.githubusercontent.com/postgres/postgres/master/doc/src/sgml/datatype.sgml

Projekt: https://github.com/postgres/postgres

Abruf: HTTP 200; 198.382 Bytes; SHA-256 `0443f7bda9cdd0646ebd7013e8ae2592cd6599e67f7182d46d26800491f8e917`.

Belegte Aussagen: Die Dokumentation empfiehlt `numeric` für Geld-/Mengenwerte mit Exaktheitsanforderung und beschreibt exakte Addition/Subtraktion/Multiplikation. Sie weist auf unterschiedliche Rundung von `numeric` und binären Gleitkommatypen hin; `numeric` rundet Gleichstände von null weg.

Architekturfolgerung: Endbeträge in Integer-Minor-Units, Mengen/Raten in `numeric`; explizite, getestete Rundungsregeln vor Buchung und JSON-sichere Betragsgrenzen. SQL-, Backend- und Client-Rundung dürfen sich nicht zufällig unterscheiden.

## Nicht abrufbare Primärseiten und Grenze der Recherche

Die folgenden HTTPS-Aufrufe scheiterten aus dieser Umgebung mit `Tunnel connection failed: 403 Forbidden`. Inhalte wurden **nicht** als live geprüft ausgegeben:

- https://docs.stripe.com/connect/separate-charges-and-transfers
- https://docs.stripe.com/connect/direct-charges
- https://docs.stripe.com/connect/charges
- https://ec.europa.eu/digital-building-blocks/sites/spaces/DIGITAL/pages/467108847/eInvoicing
- https://www.bundesfinanzministerium.de/Content/DE/FAQ/e-rechnung.html
- https://www.postgresql.org/docs/current/datatype-numeric.html

Die offiziellen GitHub-Spezifikationen liefern technische Evidenz. Aktuelle steuerrechtliche Pflichten, Aufbewahrungsfristen und die konkrete PSP-Vertragszulässigkeit werden dadurch nicht bestätigt. Diese Punkte sind gezielte Freigabefragen im Pflichtenheft, kein Grund, die technische Dokumentation unvollständig zu lassen. Ein ausprobierter Beispielrepository-Pfad `stripe-samples/connect-destination-charge` lieferte 404 und wurde nicht als Quelle verwendet.
