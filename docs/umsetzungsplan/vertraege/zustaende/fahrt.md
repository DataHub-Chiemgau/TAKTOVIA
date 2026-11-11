# Zustandsautomat Fahrt (lokale Ausführung): NICHT SPEZIFIZIERT (OP-21)

- **Arbeitspaket:** WP-M1-17 (`LH-6.1-SYS-007`); Code: `StateMachines` führt `trip` als `UnspecifiedMachine` (registriert, nicht ausführbar).
- **Zustandsspalte:** `vehicle_run.state` (lokal) und `journey_leg.execution_state` (föderiert, M6)
- **Lücke:** Das Pflichtenheft enthält für diesen Automaten keine Zustands- und Übergangsliste (OP-21). Sie darf nicht erfunden werden und **muss vor Codebeginn dieser Definition vom Auftraggeber/Fachanalyse spezifiziert werden**. Sobald sie vorliegt, wird sie in dieser Datei beschrieben, in `StateMachines` als `StateMachine` definiert und per Migration in `state_transition_rule` eingetragen; der Rahmen (Prüfung, CAS, Outbox, Datenbankhärtung) bleibt unverändert.
- **Nachweis am echten Aggregat:** M2 (Fahrt) bzw. M3 (Fahrzeug, Störung), Vollabnahme siehe WP-M1-17.
