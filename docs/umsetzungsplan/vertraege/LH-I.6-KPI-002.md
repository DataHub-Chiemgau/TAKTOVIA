# LH-I.6-KPI-002 – Ablehnungen mit normiertem Primärgrund und Mehrfachursachen

- **Verantwortlicher:** Rolle Analytics (Modul `analytics`; Katalog mit `booking-dispatch`, WP-M2-13)
- **Arbeitspaket:** WP-M4-48
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Ablehnungen werden mit normierten Grundcodes und optionalen Mehrfachursachen gespeichert.

**Originalabnahme (unverändert):** Eine Kapazitätsablehnung kann von einer Zeitfenster- oder Tarifablehnung unterschieden werden.

## Vertrag

- **Tabellen / DDL:** `rejection_reason` (neue Katalogtabelle nach V3, in M2 mit WP-M2-13 angelegt; Spalten Code, Gruppe, Text, gültig von/bis); `travel_attempt.primary_rejection` (text) und `travel_attempt.secondary_rejections` (text[]) aus V1. V1 hat kein eigenes Ablehnungskennzeichen: eine Ablehnung ist ein Versuch mit `primary_rejection` nicht NULL (Ableitung, im Detailvertrag mit M2 zu bestätigen). Ein nicht im Katalog enthaltener Code wird beim Schreiben abgewiesen: Primärgrund per FK oder Prüfung, Sekundärgründe (text[] erlaubt keinen FK) per Trigger/Prüfung, Mechanismus im Bau festzulegen. Sekundärgründe ohne Primärgrund sind unzulässig. Genau ein Primärgrund je Ablehnung (eine Spalte), Sekundärgründe nur als Mehrfachlabels ohne zusätzliche Ablehnungszählung (Kap. 01 §1.6); die Summe der Primärgründe entspricht der Zahl der Ablehnungen.
- **API:** `GET /v1/analytics/metrics` (Ablehnungen nach Grund und Gruppe: Kapazität, Zeitfenster, Tarif usw.; Primär getrennt von Sekundär ausgewiesen).
- **Zustandsautomat / Schema / Ereignisse:** keine; Codeliste aus WP-M2-13.
- **Parameter und Kennzahlen:** Datenbasis für `Kapazitätsablehnungen`, `Zeitfensterablehnungen` (Katalog K.1; „primäre Ursache fixieren, Sekundärgründe zusätzlich“); Tarifablehnung als Gruppe im Codekatalog (OP-25).
- **Oberfläche:** keine eigene (Ablehnungsstatistik in WP-M4-59/-60).
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-25 (Regel zur Wahl des Primärgrunds, Rangfolge); Gruppe „Tarif“ ist im Vorschlag der Codeliste aus WP-M2-13 (Kapazität, Personal, Ausstattung, Gebiet, Zeitfenster/Frist, Regelverbot, technischer Fehler) nicht enthalten und ist mit M2 abzugleichen, der Abnahmetest für die Tarifunterscheidung braucht diesen Code; ob die Gruppe „technischer Fehler“ in der Summe der Primärgründe zählt, ist festzulegen (K.1: technische Fehler getrennt von operativen Ablehnungen). Bau nicht blockiert, solange die Primärgrundwahl deterministisch ist.

## Tests

- **Positiv:** Kapazitäts-, Zeitfenster- und Tarifablehnung sind über Code und Gruppe unterscheidbar; Fixture PH-AT-021: drei Ablehnungen, davon zwei mit Sekundärgründen, ergeben drei Ablehnungen, Summe der Primärgründe drei, Sekundärgründe ohne zusätzliche Zählung und getrennt ausgewiesen.
- **Negativ:** unbekannter Primär- oder Sekundärcode wird beim Schreiben abgewiesen; Sekundärgründe ohne Primärgrund werden abgewiesen; je Versuch nur ein Primärgrund; technischer Fehler zählt nicht als fachliche Ablehnung; Ablehnungsstatistik zeigt nur den eigenen Mandanten.
- **Konkurrierend (Kapazität):** zwei Anfragen parallel um den letzten Platz: genau eine erhält den Platz, die andere genau einen Primärgrund Kapazität ohne Doppelzählung; mehrere zutreffende Gründe ergeben deterministisch denselben Primärgrund.
- **Referenztests:** PH-AT-021.

Testfälle: PH-AT-021; geplant:db/tests/test_rejection_reason.py::test_unbekannter_code_und_sekundaer_ohne_primaergrund_werden_abgewiesen; geplant:backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/RejectionStatsTest.kt::drei_ablehnungen_summe_primaergruende_drei; geplant:backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/RejectionStatsTest.kt::kapazitaet_von_zeitfenster_und_tarif_unterscheidbar; geplant:backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/RejectionStatsTest.kt::technischer_fehler_zaehlt_nicht_als_fachliche_ablehnung; geplant:backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/RejectionStatsTest.kt::ablehnungsstatistik_nur_eigener_mandant; geplant:backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/RejectionStatsTest.kt::mehrere_gruende_ergeben_deterministisch_gleichen_primaergrund; geplant:backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/RejectionStatsTest.kt::parallele_ablehnungen_um_letzten_platz_ohne_doppelzaehlung

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Kapazität).
