# Recherche: Mandantentrennung und Partnerstandards

Recherche durchgeführt am 25.10.2026. Belegstatus: die unten angegebenen offiziellen Quellrepository-Dateien wurden per HTTPS erfolgreich abgerufen (HTTP 200). Die Webpräsenzen `www.postgresql.org`, `osdm.io` und `gtfs.org` waren aus dieser Umgebung mit Proxy-403 nicht abrufbar; ihre Inhalte wurden deshalb nicht als live geprüfte Webseiten ausgegeben. Stattdessen wurden die offiziellen öffentlich zugänglichen Quellrepositorys gelesen. Es wurden keine Rechte, Partnerzugänge oder vertraglichen Standardunterstützungen eines konkreten Verkehrsunternehmens nachgewiesen.

## 1. Primärquellen und tragende Aussagen

| Nr. | Quelle, geprüfter Stand | Belegte Aussage | Architekturableitung |
|---|---|---|---|
| F-01 | [PostgreSQL 17 Dokumentationsquelle, ddl.sgml](https://raw.githubusercontent.com/postgres/postgres/REL_17_STABLE/doc/src/sgml/ddl.sgml), Abschnitte `ddl-rowsecurity`, `ddl-schemas`, `ddl-partitioning`; Branch `REL_17_STABLE` am Abrufdatum | Aktivierte RLS ohne Policy ist default deny; Owner umgehen RLS gewöhnlich, `FORCE ROW LEVEL SECURITY` unterwirft sie; Superuser/BYPASSRLS umgehen RLS; referentielle Prüfungen umgehen sie ebenfalls. Tabellenweite Aktionen wie TRUNCATE sind nicht RLS-gefiltert. | Runtime nicht Owner/Superuser/BYPASSRLS; separate Migrationen, eigene Service-Rechte, sichere Fehlerabbildung. Tenantfilter in App und RLS kombiniert. Kein TRUNCATE für Runtime. |
| F-02 | [TOMP-WG/TOMP-API README](https://raw.githubusercontent.com/TOMP-WG/TOMP-API/master/README.md), `master` am Abrufdatum | Offener Kommunikationsstandard zwischen Transport Operator und Mobility-as-a-Service Provider; Module Offer, Purchase, Execute sowie ergänzende Funktionen. | TOMP als Adapterfamilie, nicht als internes Datenmodell oder als Nachweis eines bestimmten Partnervertrags. |
| F-03 | [TOMP Module v2.0](https://raw.githubusercontent.com/TOMP-WG/TOMP-API/master/wiki/modules.md), am Abrufdatum | Unterschiedliche Purchase-Flows: sofort bestätigt mit Cancel Window, Auto-confirm oder explizite 2-phase Purchase; bei DRT auch verzögerte Bestätigung. Nicht jeder Modus verlangt jedes Modul. | Pro Partner Flow, Version und Capability testen. Ein generischer TOMP-Hinweis beweist keine atomare Hold-/Confirm-Buchung. |
| F-04 | [UIC: OSDM README](https://raw.githubusercontent.com/UnionInternationalCheminsdeFer/OSDM/master/README.md), `master` am Abrufdatum | OSDM umfasst Offline-Modell und Online-API; Retailer-/Distributor-Modi unterscheiden sich; Distributor bietet zusätzlich zu Admissions, Reservations und Ancillaries auch Priced Segments. | Bahn-/ÖPNV-Vertriebsadapter nach unterstütztem Modus; keine Annahme, dass jede Fahrplanauskunft Tickets verkaufen darf. |
| F-05 | [GTFS offizielle Spezifikationsquelle](https://raw.githubusercontent.com/google/transit/master/gtfs/spec/en/reference.md), Header „Revised April 27, 2026“, am Abrufdatum | GTFS spezifiziert die Dateien eines Transitdatasets, unter anderem Betreiber, Haltestellen, Linien, Fahrten und Kalender. | Feedimport liefert Verkehrsdaten; Buchungs- und Zahlungsfähigkeit bedarf einer anderen explizit bestätigten Schnittstelle und Freigabe. |

Die Quellen beweisen jeweils die dokumentierten Produkt-/Standardmechanismen. Die Bewertung von Kosten, operativer Komplexität und Eignung im folgenden Variantenvergleich ist eine Architekturabwägung für TAKTOVIA und keine Herstellerzusage. Vor Implementierung werden konkrete Release-/Commitstände für alle Partneradapter festgelegt; die Recherche auf beweglichen Dokumentationsbranches ist kein Laufzeit-Pinning.

Zur Wiedererkennung der gelesenen Dateien wurden folgende SHA-256-Werte der dekodierten UTF-8-Quelltexte bestimmt (keine unabhängige Artefaktsignatur):

```text
F-01 cea060a26fddc0b9b401e1a478941854d7e4bd9c1340f1d91f4beaa8a17207ed
F-02 9c6b8c90ba1abb88b8df788ad6afe39c9aa5711411e0e7c74af3d26721c387e5
F-03 b6446ae1bf6a8c5516ac074f782bc35d62bb1cb9ce12db42d11bae0e6f43abe3
F-04 20ca09a56326c0d3b8b00a6b0ea19fe8456ea7e73aa902d08671b5574e55a19d
F-05 1ff40b8001b180bd023dd6f1899907aecbcb4600c8dbcb6fc6c841a50839b147
```

## 2. Variantenentscheidung: Datenhaltung

| Variante | Nutzen | Aufwand / Grenze | Urteil |
|---|---|---|---|
| Gemeinsame DB, gemeinsames Schema je Domäne, `tenant_id` + RLS | Einheitliche Migrationen, atomare lokale Buchung, wirtschaftlicher Pilotbetrieb, gute gemeinsame Betriebswerkzeuge | Erfordert konsequente Service-/Policytests; gemeinsame DB teilt Ressourcen und Störungspotenzial; RLS ist keine physische Isolation | **Favorit für den Start**, mit expliziten Föderationsprojektionen und tenantfähigem Repository-Layer |
| Schema pro Tenant | Separater Namespace, teilweise delegierbare Rechte | Migrationen vervielfachen sich; sichere `search_path`-Behandlung nötig; gemeinsame Instanz und privilegierte Rollen bleiben gemeinsame Risiken | Kein pauschaler Sicherheitsgewinn, der hier den zusätzlichen Aufwand begründet |
| Datenbank pro Tenant | Getrennte Zugangsdaten, separater Restore/Lebenszyklus; mehr organisatorische Isolation | Föderation braucht ohnehin APIs/Projektionen; teureres Pooling, Migrationen, Monitoring und Betrieb; gemeinsame Hostinstanz ist noch keine physische Trennung | Option für vertraglich besonders geschützte oder große Mandanten |
| Cluster/Cloudprojekt pro Tenant | Getrennte Ressourcen und mögliche Schlüssel-/Administrationsgrenzen | Höchster Betriebsaufwand, aufwendige föderierte Auswertung und Updates | Nur bei expliziter Schutz-/Souveränitätsvorgabe oder nach Lastnachweis |
| Partition pro Tenant | Kann Datenpflege/Abfragezugriff beschleunigen | Ist eine Speicher-/Abfragestruktur und ersetzt weder RLS noch Rechteprüfung | Performanceoption, keine separate Isolationsvariante |

Erforderliche AG-Entscheidung: Gibt es vertragliche Pflichten zur eigenen Datenbank, eigenem Schlüssel, eigenen Administratoren oder bestimmter Hostingjurisdiktion? Ohne solche Vorgaben gilt der technische Favorit. Der Domain-/Adapterzuschnitt muss die spätere Auslagerung eines Tenants ermöglichen; ein solcher Umzug benötigt einen gesonderten Migrations-/Abgleichplan und erfolgt nicht durch Laufzeitänderung einer Tenant-ID.

## 3. Variantenentscheidung: Reisekettenbuchung

| Variante | Verhalten | Risiken / Eignung | Urteil |
|---|---|---|---|
| Zentraler Dispatcher verändert alle Betreiberbestände | Eine Engine steuert fremde Fahrzeuge direkt | Verletzt Betreiberhoheit und vertragliche Grenzen von FED-004; hohe Datenkopplung | Verworfen |
| Verteilte Datenbanktransaktion über alle Partner | Alle bestätigten Segmente theoretisch atomar | Externe APIs/PSP bieten regelmäßig keine gemeinsame 2PC-Transaktion; Ausfallkopplung; keine realistische Standardvoraussetzung | Verworfen als allgemeiner Vertrag |
| Persistente Saga mit Holds, Statusabgleich und Kompensation | Jeder Betreiber bestätigt seinen Anteil; Orchestrator koordiniert und behandelt Fehler | Restliche Kompensations-/Haftungsrisiken müssen ausdrücklich geregelt werden; UNKNOWN ist unvermeidbar | **Favorit**, entspricht Lastenheft Anhang M und FED-006 |
| Getrennte Käufe mit einem gemeinsamen Reiseplan | Kunde kauft Segmente einzeln, getrennte Zahlungen/Verträge | Schlechtere Durchgängigkeit; Teilbuchungsrisiko muss vor Kauf sichtbar sein | Fallback für Partner ohne brauchbare Hold-/Kompensationsfähigkeiten |
| Nur Auskunft und externer Checkout | Navigation zur Anbieterbuchung | Keine Aussage über erfolgreichen externen Kauf ohne verifizierbaren Rückkanal | Erforderlicher Modus für Informationspartner |

AG entscheidet die wirtschaftliche Policy für unvermeidbare Restfälle: Wer trägt Storno-/Kompensationskosten, wer unterstützt den Fahrgast bei Anschlussverlust, und welche Kombinationen dürfen als Komplettkauf angeboten werden? Technischer Favorit ist eine vollständig bestätigte buchbare Kette; keine kostenpflichtige automatische Teilbuchung ohne vorherige, konkrete Zustimmung.

## 4. Variantenentscheidung: Standards

**Empfehlung: adapterbasierte Kombination.** TOMP ist der bevorzugte Startpunkt für DRT/MaaS-Partner mit passenden Betriebsflows; OSDM für buchbare Bahn-/ÖPNV-Vertriebsprodukte mit passender Rolle. GTFS/GTFS-RT bzw. NeTEx/SIRI/VDV werden nach tatsächlicher Datenversorgung als Fahrplan-/Echtzeitquellen verwendet. Der interne Journey-/Preisvertrag bleibt unabhängig. Ein partnerseitiger Conformance-Report muss Version, angebotene Endpunkte, Sicherheit, Idempotenz, Timeouts, Statusabgleich, Ticketausgabe und Stornoregeln belegen.

Die AG muss keine abstrakte Entscheidung „TOMP oder OSDM für alles“ treffen. Benötigt werden zuerst die Pilotpartner, ihre Vertriebsrechte und dokumentierten technischen Fähigkeiten. Danach wird pro Partner die passende Alternative freigegeben.
