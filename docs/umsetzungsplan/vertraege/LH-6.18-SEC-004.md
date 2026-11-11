# LH-6.18-SEC-004 – Dateninventar mit Zweck und Rechtsgrundlage

- **Verantwortlicher:** Rolle Plattform (Datenschutz-/Schema-Prüfung, CI)
- **Arbeitspaket:** WP-M1-32
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Systeme speichern nur zweckgebundene, notwendige Standort- und Personeninformationen.

**Originalabnahme (unverändert):** Dateninventar ordnet jedem Feld Zweck und Rechtsgrundlage zu.

## Vertrag

- **Tabellen / DDL:** keine neue Tabelle. Maschinenlesbares Dateninventar im Repository (Vorschlag `db/dateninventar.csv`, je Migration fortgeschrieben), Zeile je Tabelle und Spalte aller migrierten Tabellen (V1, V2, V3 und M1-Migrationen). Pflichtfelder (Format ist Vorschlag, mit dem Bau als Schema festzuschreiben): `tabelle`, `spalte`, `personenbezogen` (ja/nein), `zweck` für jedes Feld; bei „ja" und bei Standortbezug zusätzlich `rechtsgrundlage`, `schutzklasse`, `loeschklasse` (WP-M1-32 AK1). Schutzklassen nach Kap. 01 §1.7: Kontakt/Unterstützung, Standort/Driver-ETA, Belege/Journal, Security-Audit, aggregierte Statistik. Löschklasse je Aggregattabelle nach Kap. 04 §4.1. Beispiele für Pflichtklassifikation: `passenger_private.contact_ciphertext`, `passenger_private.support_ciphertext`, `passenger_private.subject_token`, `travel_attempt.coarse_origin`/`coarse_destination` (nur grobe Auflösung), `audit_event.actor_ref`, `payment_intent.provider_ref` (Zuordnung zur Schutzklasse offen, siehe SEC-008).
- **CI-Prüfung „Schema ↔ Inventar":** liest `information_schema.columns` des Schemas `taktovia` und gleicht gegen das Inventar ab; unklassifizierte Spalte, Inventareintrag ohne Spalte, unbekannter Zweck oder unbekannte Rechtsgrundlage (nicht im freigegebenen Katalog) lassen CI scheitern. Zielwert: null unklassifizierte Felder.
- **API:** keine.
- **Zustandsautomat / Schema / Ereignisse:** keine; Format des Inventars wird als `vertraege/schemas/dateninventar.v1.schema.json` festgehalten.
- **Parameter und Kennzahlen:** keine aus kataloge.csv.
- **Oberfläche:** UI-20 Basis – lesbarer Export des Inventars für den Datenschutz (nur Leserechte; berechtigte Rolle laut Rollenkatalog, Rollenzuordnung für UI-20 im Pflichtenheft nicht festgelegt).
- **Entscheidungen:** D-11 (Verantwortliche je Datenfluss, Rechtsgrundlagen) – blockiert Produktivfreigabe, nicht den Bau; D-10 für Fahrer-/Standortfelder (ab M4).
- **Offene Punkte:** OP-24 (Katalog zulässiger Zwecke und Rechtsgrundlagen fehlt; fachlich/rechtlich vom Auftraggeber freizugeben). Bis dahin wird ein vorläufiger Katalog als Testdaten geführt und ausdrücklich als nicht freigegeben gekennzeichnet; kein Bauhindernis. Felder ohne nachgewiesenen Zweck (z. B. feinere Auflösung als `travel_attempt.coarse_origin`) werden nicht angelegt, Abweichungen als Befund dokumentiert (Befund unter `docs/umsetzungsplan/befunde/`; Prüfung, dass kein Feld feiner als grobe Auflösung angelegt ist, erfolgt als Reviewpunkt, da Auflösung aus dem Spaltentyp `text` nicht automatisch ableitbar ist). Inventar für Tabellen späterer Meilensteine ist dort Pflicht über dieselbe Prüfung.

## Tests

- **Positiv:** vollständiger Inventarabgleich über alle in V1, V2, V3 und M1-Migrationen vorhandenen Tabellen (`information_schema.columns`, Schema `taktovia`) über alle in M1 migrierten Tabellen meldet null unklassifizierte Felder; jedes personenbezogene Feld hat Zweck und Rechtsgrundlage, Schutzklasse und Löschklasse; UI-20 exportiert das Inventar lesbar und nur mit Leserecht (Rolle ohne Recht erhält 403, Fremdmandant ist nicht betroffen, da das Inventar mandantenunabhängige Schemainformation ist).
- **Negativ:** Testmigration mit neuer Spalte ohne Inventareintrag scheitert in CI; Eintrag mit unbekanntem Zweck oder unbekannter Rechtsgrundlage scheitert; personenbezogenes Feld ohne Rechtsgrundlage scheitert; Inventareintrag für nicht existierende Spalte scheitert.
- **Konkurrierend:** nicht einschlägig (Build-Prüfung ohne Laufzeitzustand; WP-M1-32: „nicht einschlägig (Build-Prüfung)").
- **Referenztests:** keine Katalog-ID im WP genannt.
- Testfälle: geplant:db/tests/test_07_dateninventar.py::test_alle_spalten_klassifiziert; geplant:db/tests/test_07_dateninventar.py::test_personenbezogenes_feld_ohne_rechtsgrundlage_scheitert; geplant:db/tests/test_07_dateninventar.py::test_eintrag_fuer_nicht_existierende_spalte_scheitert; geplant:web/operator/src/datenschutz/DateninventarExport.test.tsx::inventar_export_lesbar_nur_mit_leserecht; geplant:db/tests/test_07_dateninventar.py::test_migration_ohne_inventareintrag_scheitert; geplant:db/tests/test_07_dateninventar.py::test_unbekannter_zweck_scheitert

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
