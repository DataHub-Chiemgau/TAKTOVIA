# Zustandsautomat Störung: NICHT SPEZIFIZIERT (OP-21)

- **Arbeitspaket:** WP-M1-17 (`LH-6.1-SYS-007`); Code: `StateMachines` führt `disruption` als `UnspecifiedMachine` (registriert, nicht ausführbar).
- **Zustandsspalte:** keine: im Kern-DDL gibt es keine Störungstabelle
- **Lücke:** Das Pflichtenheft enthält für diesen Automaten keine Zustands- und Übergangsliste (OP-21). Sie darf nicht erfunden werden und **muss vor Codebeginn dieser Definition vom Auftraggeber/Fachanalyse spezifiziert werden**. Sobald sie vorliegt, wird sie in dieser Datei beschrieben, in `StateMachines` als `StateMachine` definiert und per Migration in `state_transition_rule` eingetragen; der Rahmen (Prüfung, CAS, Outbox, Datenbankhärtung) bleibt unverändert.
- **Nachweis am echten Aggregat:** M2 (Fahrt) bzw. M3 (Fahrzeug, Störung), Vollabnahme siehe WP-M1-17.

**Nachtrag WP-M3-01:** Die Automaten dieses Bereichs sind jetzt spezifiziert: [fahrzeug_sperre.md](fahrzeug_sperre.md), [einsatzfreigabe.md](einsatzfreigabe.md), [fahrzeug_dienststatus.md](fahrzeug_dienststatus.md), [incident.md](incident.md); die Registrierung in `StateMachines` erfolgt mit WP-M3-53.
