# LH-6.19-NFR-007 – Ausfallübung mit RPO-/RTO-Messung und PSP-Abgleich

- **Verantwortlicher:** Rolle Betrieb/Datenbank (WP-M5-15, Drehbuch WP-M5-14)
- **Arbeitspaket:** WP-M5-15
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Backups und Replikation zielen auf RPO höchstens 15 Minuten und RTO höchstens 2 Stunden.

**Originalabnahme (unverändert):** Ausfallübung belegt Wiederherstellung in Zielzeit.

## Vertrag

- **Tabellen / DDL:** keine Änderung. Nach Restore automatisierte Konsistenzprüfungen: `outbox`/`inbox`-Stand, `idempotency_record`, Kapazitätsinvarianten (Kap. 04 §4.5), `audit_event`-Kette, Lösch-/Sperrjournal (Wiedereinspielen vor Freigabe, Kap. 01 §1.7); Abgleichbericht über `booking`, `capacity_hold`, `payment_intent` (`provider_ref`, `state`), `operation` im Verlustfenster. Mandantentrennung unverändert wirksam (Runtime-Rolle ohne Superuser, BYPASSRLS oder Tabelleneigentum; Stichprobe Isolationssuite auf wiederhergestellter Datenbank). Restore in eine andere Umgebung als die Quellumgebung ist technisch unterbunden.
- **API:** keine neuen; End-to-End-Probe nutzt die synthetische Buchung aus WP-M5-05 (`/v1/ride-offers`, `/v1/bookings`). PSP-Statusabfrage über Providerreferenz (WP-M2-29).
- **Zustandsautomat:** Zahlungsautomat aus M2 (WP-M2-30); nach Restore werden `payment_intent` und `operation` in unbestimmtem Zustand anhand der Providerreferenzen abgeglichen, bevor unsichere Schritte wiederholt oder Kunden als ungebucht markiert werden; kein Wiederholen mit neuer Referenz.
- **JSON-Schema / Ereignisse:** Übungsprotokoll (Kap. 08 §8.6): Ausfallzeitpunkt, letzter wiederherstellbarer Commit (RPO-Messpunkt), Zeitpunkt funktionsfähiger End-to-End-Probe (RTO-Ende), gemessenes RPO und RTO, Abweichungen, Maßnahmen; Evidenz in `e2e.csv` für AT-23.
- **Parameter und Kennzahlen:** RPO ≤ 15 min, RTO ≤ 2 h (vorläufige Lastenheftziele); keine Katalogparameter. Backups und Replikation sind die tatsächlich im Betrieb verfügbaren Mittel, keine Testabkürzung.
- **Oberfläche:** keine; Drehbuch (WP-M5-14) durch eine Person ausführbar, die es nicht geschrieben hat.
- **Entscheidungen:** D-05 (Peaklast, SLA, Hosting, Wartungsregel): alle Zielwerte sind vorläufige Lastenheftziele (Kap. 08 §8.6), Status je Zielwert im Protokoll „vorläufig, D-05 offen“ oder „vom Auftraggeber bestätigt am …“. D-05 blockiert die Abnahmewirkung, nicht den Bau. D-03 (Teil PSP-Auswahl): ohne Beschluss Abgleich nur gegen den PSP-Simulator. D-11 (Wiederanlauf, Notbetrieb) blockiert nur die Produktivschaltung.
- **Offene Punkte:** OP-21 (Topologie Standby/Region, Einbezug Objektspeicher in RPO/RTO, Ende der RTO-Messung, Umfang PSP-Abgleich nur in Kap. 08 §8.6 umrissen; Arbeitsdefinition aus WP-M5-14). Übertragbarkeit auf Produktion erst nach Hostingentscheid. Blockiert den Bau nicht.

## Tests

- **Positiv:** Übung in produktnaher Umgebung unter laufender Last: Ausfall der Primärdatenbank, Wiederherstellung nach Drehbuch, Wiederanlauf aller Komponenten, End-to-End-Probe; gemessenes RPO ≤ 15 min und RTO ≤ 2 h mit Zeitstempeln im Protokoll; AT-23 in `e2e.csv` eingetragen.
- **Negativ:** Übung mit zusätzlich ausgefallenem Messaging; Restore auf einen Zeitpunkt mitten in einer Buchungstransaktion; beschädigtes WAL-Segment wird erkannt; Restore ohne Wiedereinspielen des Löschjournals wird blockiert; nach Restore keine wiederauferstandenen gelöschten Daten und keine aufgehobene Fahrzeugsperre; Restore in fremde Umgebung unterbunden; verletzter Konsistenzbefund blockiert die Freigabe.
- **Konkurrierend (Geld, Kapazität, Datentrennung):** Zahlungen und Bestätigungen laufen bis zum Ausfallzeitpunkt; verspätete PSP-Ereignisse nach Wiederanlauf haben genau eine Wirkung je Vorgang, keine zweite Belastung, keine Buchung ohne Kapazität; Abgleichlauf parallel zu PSP-Webhook; offline erfasste Fahrerereignisse synchronisieren ohne Doppelwirkung; Isolationssuite auf wiederhergestellter Datenbank grün.
- **Referenztests:** AT-23.
- Testfälle: AT-23; geplant:tests/performance/test_ausfalluebung.py::test_rpo_rto_in_zielzeit_mit_zeitstempeln; geplant:tests/performance/test_ausfalluebung.py::test_psp_abgleich_nach_restore_keine_zweite_belastung; geplant:db/tests/test_restore_konsistenz.py::test_restore_ohne_loeschjournal_wird_blockiert; geplant:tests/performance/test_ausfalluebung.py::test_abgleich_parallel_zu_psp_webhook_genau_eine_wirkung; geplant:db/tests/test_restore_konsistenz.py::test_restore_in_fremde_umgebung_unterbunden

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
