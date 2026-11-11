# Ereignisschemas

Versionierte JSON-Schemas für Outbox-Ereignisse (`outbox.schema_version`, Kap. 01 §1.8 „kompatible Events“).

- Dateiname: `<bereich.ereignis>.v<N>.schema.json`, z. B. `journey.confirmed.v1.schema.json`. Eine neue Hauptversion ist eine neue Datei; bestehende Versionen werden nie inkompatibel geändert.
- Jedes Schema ist JSON Schema 2020-12 und wird von `tools/lint.py` geprüft.
- Inhalte entstehen mit den jeweiligen Meilensteinen (ab M1); in M0 gibt es nur Konvention und Prüfung. Der Ort ist in Umsetzungsplan §3 nicht vorgesehen und wird hier festgelegt.
