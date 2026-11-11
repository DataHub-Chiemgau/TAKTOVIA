# LH-6.19-NFR-004 – Fahrer-Planrevision p95 ≤ 10 s bis zur Clientdarstellung

- **Verantwortlicher:** Rolle Plattform/Fahrerbetrieb (WP-M5-13)
- **Arbeitspaket:** WP-M5-13
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Eine neue bestätigte Fahrer-Planrevision wird bei bestehender Verbindung in 95 % der Fälle binnen 10 s angezeigt.

**Originalabnahme (unverändert):** Zeitgestempelter Ende-zu-Ende-Test bestätigt Zielwert.

## Vertrag

- **Tabellen / DDL:** keine Änderung. Messkette über `vehicle_run` (Spalte `revision` bigint, Commit der Planrevision) → `outbox` (`occurred_at`, `published_at`, `aggregate_revision`) → Relay → Live-Kanal/Push → Empfang → tatsächliche Darstellung; Korrelation über Revisionsnummer. Fahrer erhalten ausschließlich Revisionen ihres zugeordneten Fahrtenplans (RLS/Zuordnung).
- **API:** `/v1/driver/shifts/{id}/run` (Nachladen); Live-Kanal aus M3. Ältere Revision überschreibt nie eine neuere; Fahrerereignis mit alter Revision führt zu definierter Konfliktantwort.
- **Zustandsautomat:** Verbindungszustände aus WP-M5-09; Zielmenge der Messung nur „bestehende Verbindung“; kurze Unterbrechung wird getrennt ausgewiesen.
- **JSON-Schema / Ereignisse:** Ereignis Planrevision aus `outbox`; Messprotokoll mit Zeitstempeln Commit, Relay, Empfang, Darstellung, Uhrenabgleich Server/Gerät, p50/p95/p99, Stichprobengröße, Verteilung, Ausreißern (verlorene Zustellungen durch Nachladen geschlossen zählen als Ausreißer, nicht als Erfolg).
- **Parameter und Kennzahlen:** keine Katalogparameter; technische Messgröße „Commit bis dargestellte Clientrevision“.
- **Oberfläche:** UI-10 (Driver Fahrt) meldet im Testmodus den Zeitpunkt der tatsächlichen Darstellung der Revision.
- **Entscheidungen:** D-05 (Peaklast, SLA, Hosting, Wartungsregel): alle Zielwerte sind vorläufige Lastenheftziele (Kap. 08 §8.6), Status je Zielwert im Protokoll „vorläufig, D-05 offen“ oder „vom Auftraggeber bestätigt am …“. D-05 blockiert die Abnahmewirkung, nicht den Bau. D-05 betrifft insbesondere Zielwerte und Geräte.
- **Offene Punkte:** OP-06 (Zustellweg Push/Live-Kanal/Polling, „bestätigte Planrevision“, „bestehende Verbindung“ nicht definiert), OP-27 (Zielgeräte und Testinstrumentierung „Revision dargestellt“ nicht festgelegt; Aussage nur für gewählte Geräte). Blockiert den Bau nicht.

## Tests

- **Positiv:** Serie von Planänderungen unter Referenzlast (250 verbundene Fahrzeuge je Mandant, zwei Mandanten); p95 ≤ 10 s von Commit bis Darstellung; Stichprobengröße und Verteilung protokolliert.
- **Negativ:** Verbindung kurz unterbrochen zählt nicht zur Zielmenge, wird getrennt ausgewiesen; Relay gestoppt und wieder gestartet, Nachladen erscheint als Ausreißer; Fahrer erhält keine Revision eines fremden Fahrtenplans.
- **Konkurrierend (Datentrennung):** zwei Planänderungen kurz nacheinander: dargestellt wird die höhere Revision; Planänderung parallel zu Fahrerereignis mit alter Revision führt zu definierter Konfliktantwort; zwei Mandanten parallel ohne Fremdzustellung.
- **Referenztests:** kein Katalogtest benannt.
- Testfälle: geplant:tests/performance/test_planrevision_p95.py::test_p95_commit_bis_darstellung_unter_referenzlast; geplant:tests/performance/test_planrevision_p95.py::test_hoehere_revision_wird_dargestellt_und_fremde_revision_nie_zugestellt; geplant:tests/performance/test_planrevision_p95.py::test_relay_neustart_nachladen_zaehlt_als_ausreisser; geplant:tests/performance/test_planrevision_p95.py::test_fahrerereignis_mit_alter_revision_liefert_konfliktantwort

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
