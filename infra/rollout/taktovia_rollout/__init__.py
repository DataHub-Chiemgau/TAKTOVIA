"""Produktionsumgebung und gestaffeltes Rollout (WP-M5-28). Nur Standardbibliothek (und psycopg für die Datenbankprüfungen).

Das Paket ist der ausführbare Teil des Bereitstellungsverfahrens: Artefaktprüfung gegen das Release-Gate (WP-M5-22), Trennung der Zugangsdaten je Umgebung (WP-M1-40),
Prüfung der Runtime-Rolle und des Schemas, gestaffeltes Rollout mit Rauchprobe und Stoppkriterien, Betriebszugriff mit Personenbindung. Es spricht über kleine Schnittstellen
(`Ziel`, `Migrator`) mit der Zielplattform; die Anbindung an einen Hostinganbieter ist offen (D-05) und hier NICHT enthalten."""
