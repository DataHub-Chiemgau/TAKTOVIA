# Vergabe der Mangelklassen A/B/C (WP-M0-22)

Quelle: Kap. 08 §8.4 (Lastenheft §11.3 lag im Repository nur als DOCX vor und wurde nicht ausgewertet). Die Regel ist
vorläufig und wird mit WP-M0-33 bestätigt. Umsetzung: `taktovia_at/defects.py`.

| Klasse | Bedingung | Wirkung |
|---|---|---|
| A | mindestens eine Kategorie aus `TRAFFIC_SAFETY`, `DATA_DISCLOSURE`, `DOUBLE_BOOKING`, `CORE_BOOKING_OUTAGE` | blockiert die Produktion; Workaround oder Termin stufen nicht herab |
| B | beeinträchtigt eine Kernfunktion (`impairs_core`) | freigabefähig nur mit freigegebenem Workaround und verbindlichem Behebungstermin |
| C | sonst | Maßnahmenplan |

Die Klasse steht nur bei `result = FAILED` im Evidenzdatensatz (Schema erzwingt das). Bei `PASSED` und `NOT_EXECUTED` ist sie leer.
Die Automatik setzt `acceptance_decision` immer auf `PENDING`; die Abnahme entscheidet der Auftraggeber.

# Prüfregel „Evidenz enthält keine Fahrgast- oder Personaldaten“

`taktovia_at/evidence.py::check_no_personal_data` weist einen Datensatz ab, wenn (1) ein Schlüsselname auf eine
Personenkategorie zeigt (E-Mail, Telefon, Name, Adresse, IBAN, Chiffrat, Diagnose), (2) ein Feld ausdrücklich als
personenbezogen markiert ist (Suffix `__pii` oder `"personal_data": true`), oder (3) ein Wert wie eine E-Mail-Adresse, Telefonnummer
oder IBAN aussieht. Die Regel ist eine Sicherung gegen Versehen, kein Ersatz für die Datensparsamkeit beim Erzeugen der Evidenz.
