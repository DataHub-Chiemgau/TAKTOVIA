# Übungsplan Ausfallübung Datenbank (WP-M5-15, LH-6.19-NFR-007)

Detailvertrag: [LH-6.19-NFR-007](../../docs/umsetzungsplan/vertraege/LH-6.19-NFR-007.md). Drehbuch und Messdefinitionen (WP-M5-14): [wiederanlauf-drehbuch-m5.md](wiederanlauf-drehbuch-m5.md).
Alle Zielwerte (RPO höchstens 15 min, RTO höchstens 2 h) sind **vorläufige Lastenheftziele, D-05 offen**; das Ergebnis gilt für die Übungsumgebung, nicht für die Produktion (Hosting D-05, OP-21).

## Szenario

Ausfall der Primärdatenbank unter laufender Last, Wiederherstellung nach Drehbuch **ausschließlich** aus Basissicherung und archiviertem WAL, Wiederanlauf, Abgleich externer Zahlungen, End-to-End-Probe.

| Punkt | Festlegung |
|---|---|
| Umgebung | privater PostgreSQL-Cluster (initdb) mit dem Schema des Repositorys und Umgebungskennung `development`, Archivierung wie `infra/backup/postgresql-archive.conf` (`archive_timeout = 60s`, Verschlüsselung, Betriebsrolle `ops-backup`); Ziel = neuer Cluster derselben Umgebung (Restore in eine andere Umgebung ist technisch abgewiesen) |
| Lastniveau | je Übungsmandant (zwei) zwei Buchungsschreiber (Hold, Bestätigung, Zahlung gegen den PSP-Simulator, Einzug), ein Löschvorgang je 0,5 s, eine Fahrzeugsperre je 3 s, alles unter der **Runtime-Rolle** mit RLS; getaktet 0,05 s je Schreiber. Das ist kein Referenzlastprofil (250 Fahrzeuge, 30.000 Buchungen/Tag: `refload/`), sondern ein Dauerschreibprofil, das ein Verlustfenster mit Vorgängen aller Arten erzeugt |
| Ausfall | Prozess hart beendet (`pg_ctl -m immediate`) und Datenverzeichnis verworfen (Plattenverlust): nicht archiviertes WAL ist verloren. Zeitpunkt: 8 s nach dem ersten Segmentwechsel unter Last |
| Beobachter | der Übungsautomat protokolliert; die Ausführung durch eine Person, die das Drehbuch nicht geschrieben hat, steht aus (Abnahme 2 des WP-M5-14) |
| Abbruchkriterien | beschädigtes oder fehlendes WAL-Segment, Manifest- oder Umgebungsabweichung, blockierender Konsistenzbefund, nicht abgeschlossener Abgleich: die Freigabe bleibt gesperrt (`ReleaseBlocked`), die Übung gilt als nicht bestanden |
| Zusatzfälle | Messaging fällt zusätzlich aus (Freigabe bleibt gesperrt, bis der Strom zurück ist); Restore auf einen Zeitpunkt zwischen zwei Commits und mitten in einer Buchungstransaktion |

## Messung (Definitionen aus WP-M5-14)

- **Ausfallzeitpunkt:** unmittelbar vor dem Beenden des Prozesses. **RPO-Messpunkt:** `pg_last_xact_replay_timestamp()` der wiederhergestellten Datenbank (letzter wiederherstellbarer Commit). **Gemessenes RPO** = Ausfallzeitpunkt minus RPO-Messpunkt.
- **RTO-Ende:** Zeitpunkt der erfolgreichen End-to-End-Probe nach Freigabe (Probe aus WP-M5-05 gegen die Anwendung auf der wiederhergestellten Datenbank; ohne Anwendung gekennzeichnete Datenbank-Probe). **Gemessenes RTO** = RTO-Ende minus Ausfallzeitpunkt.
- Alle Zeiten sind echte Uhrzeiten (UTC) des Laufs, keine simulierte Uhr. Zusätzlich zählt der Lauf die vom Client bestätigten Commits nach dem RPO-Messpunkt (Verlustfenster): ist es leer, ist die RPO-Messung bedeutungslos und der Test schlägt fehl.

## Abgleich (Schritt 6)

`ausfalluebung/abgleich.py`: Quellen des Verlustfensters außerhalb der Datenbank sind der Ereignisstrom (Messaging) und der Zahlungsanbieter. Je Händlerreferenz wird der Anbieterstatus gelesen (nie ein neuer Autorisierungs- oder Einzugsaufruf);
der verifizierte Einzug wird höchstens einmal übernommen (Vergleich-und-Setzen auf die Revision), Fahrzeugsperren aus dem Verlustfenster werden aus dem Ereignisstrom vor der Freigabe wieder gesetzt, Löschungen über das Lösch-/Sperrjournal.
Ergebnisse je Vorgang: `KONSISTENT`, `UEBERNOMMEN`, `LOKAL_VERLOREN_PSP_VORHANDEN_MANUELLE_KLAERUNG` (Zahlung beim Anbieter vollzogen, lokal verloren: Erstattung oder Neubuchung ist eine fachliche Entscheidung, nie eine zweite Belastung),
`BUCHUNG_VERLOREN_ZAHLUNG_VORHANDEN_KLAERUNG` (Kunde wird nicht als ungebucht markiert, bevor das geklärt ist), `BUCHUNG_VERLOREN_KEINE_ZAHLUNG_KUNDE_INFORMIEREN`, `SPERRE_AUS_EREIGNISSTROM_WIEDERHERGESTELLT`, `FORTSETZEN_MIT_GLEICHER_KOMMANDOREFERENZ`, `ANOMALIE_LOKAL_VOR_PSP` (blockiert).
Der betriebliche Abgleichlauf des Backends (`Payments.reconcileAfterRestoreIn`, Operationen mit Lease und Fencing) ist mit `PaymentRestoreReconcileTest` getestet; der Bericht der Übung ersetzt ihn nicht, er prüft dieselben Invarianten gegen die wirklich wiederhergestellte Datenbank.

## Ausführung und Nachweise

| Nachweis | Befehl | Stand |
|---|---|---|
| komplette Übung, Abgleich, Zusatzfälle, Parallelität Abgleich/Webhook | `cd tests/performance && python -m unittest test_ausfalluebung` (Datenbankumgebung wie `db/db.py`, PostgreSQL-Werkzeuge; `TAKTOVIA_PERF_RUN=1` zusätzlich mit der Anwendung für die Probe) | ausgeführt |
| Freigabeschranken auf logisch wiederhergestellten Datenbanken | `cd db/tests && python -m unittest test_restore_konsistenz` | ausgeführt |
| AT-23 mit Evidenz und Eintrag in `e2e.csv` | `python tests/acceptance/m5_at23/run.py --mit-anwendung --write-e2e` | ausgeführt (Evidenz unter `tests/acceptance/evidence/m5/`, Protokoll `protokolle/ausfalluebung-m5-15.json`) |

**Nicht ausgeführt / Grenzen:** PSP ist der Simulator (D-03 offen, kein echter Anbieter); der Ereignisstrom ist eine Datei statt eines Brokers; der Backupspeicher ein lokales Verzeichnis (kein S3 Object Lock); kein Standby über Hosts; die Last schreibt per SQL (nicht die Kotlin-Anwendung); Keycloak durch einen JWT-Test-Issuer ersetzt;
eine Maschine für alles (Zeiten gelten für diese Datenmenge und Hardware); Drehbuchausführung durch Dritte; Übertragbarkeit auf Produktion; regelmäßige Wiederholung im Betrieb (WP-M5-26).
