"""M1-Gate-Abnahmesuite (WP-M1-45): die elf Gate-Tests des Meilensteins als automatisierte Szenarien im in M1 prüfbaren Umfang.

Jedes Szenario läuft unter der Runtime-Rolle gegen die Datenbank (Fixture Mandanten A/B) und verknüpft die zugehörigen Backend-Tests (JUnit-Ergebnisse aus ./gradlew build) und
Web-Tests. Evidenz nach Kap. 08 §8.4 entsteht aus dem Testlauf (`taktovia_at.scenario.execute`). Umfang und spätere Meilensteine je Test: docs/m1-gate-umfang.md.
"""
