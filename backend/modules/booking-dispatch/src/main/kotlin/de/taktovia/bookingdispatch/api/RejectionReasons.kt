package de.taktovia.bookingdispatch.api

/** Kennzahl, in die ein Ablehnungsgrund einzählt (Datenbasis, Berechnung in M4: Anhang K.1, WP-M4-46/-51). `null` bei technischen Fehlern (keine fachliche Ablehnung) und beim Regelverbot (keine eigene Kennzahl in K.1, zählt nur in die Angebotsquote). */
enum class RejectionMetric(val label: String) {
    CAPACITY("Kapazitätsablehnungen"), PERSONNEL("Personalmangelablehnungen"), EQUIPMENT("Ausstattungsablehnungen"), AREA("Gebietsablehnungen"),
    TIME_WINDOW("Zeitfensterablehnungen"),
}

/**
 * Standardisierter Ablehnungsgrund einer nicht erfüllbaren Anfrage (WP-M2-13, LH-6.5-OPT-011; Katalog als Vorschlag dieses Plans, OP-14). [code] ist der stabile Schlüssel in
 * `rejection_reason.code`, in `travel_attempt.primary_rejection`/`secondary_rejections`, in der API-Antwort und im Ereignis `booking.attempt_recorded`.
 *
 * **Prioritätsregel der Primärgrundwahl:** der Grund mit dem kleinsten [priority] ist der Primärgrund, alle übrigen verletzten Gründe stehen als Sekundärgründe in der
 * Reihenfolge ihrer Priorität. Die Reihenfolge folgt der Prüfreihenfolge der Vorprüfung (Frist/Zeit vor Gebiet vor Regel vor Haltepunkten, danach Fahrzeugsuche: Ausstattung,
 * Personal, Kapazität) und ist damit deterministisch: derselbe Datenstand ergibt denselben Primärgrund, unabhängig von der Reihenfolge, in der Ursachen festgestellt werden.
 * [TECHNICAL] ist nie ein Primärgrund (`counts = false`): ein technischer Fehler wird als Versuch mit Ergebnis `TECHNICAL_ERROR` erfasst und nicht als Ablehnung gezählt.
 *
 * [nextAction] ist der Textbaustein „nächste Handlung“ für UI-01 (Kap. 06 §6.2); [text] die Begründung für den Fahrgast.
 */
enum class RejectionCode(val priority: Int, val metric: RejectionMetric?, val counts: Boolean, val text: String, val nextAction: String) {
    LEAD_TIME(10, RejectionMetric.TIME_WINDOW, true, "Die gewünschte Abfahrt liegt zu kurzfristig.", "Wählen Sie eine spätere Abfahrtszeit."),
    HORIZON(20, RejectionMetric.TIME_WINDOW, true, "Die gewünschte Zeit liegt zu weit in der Zukunft.", "Wählen Sie einen näheren Termin."),
    WINDOW_NOT_FEASIBLE(30, RejectionMetric.TIME_WINDOW, true, "Für die gewünschte Ankunft lässt sich kein erreichbares Abholfenster bilden.", "Wählen Sie eine spätere Ankunftszeit."),
    OUTSIDE_HOURS(40, RejectionMetric.TIME_WINDOW, true, "Der Dienst ist zur gewünschten Zeit nicht in Betrieb.", "Wählen Sie eine Zeit innerhalb der Betriebszeiten."),
    OUTSIDE_AREA(50, RejectionMetric.AREA, true, "Start oder Ziel liegt außerhalb des Bediengebiets.", "Wählen Sie Start und Ziel innerhalb des Bediengebiets."),
    RULE_FORBIDDEN(60, null, true, "Diese Verbindung ist für den Dienst nicht zugelassen.", "Wählen Sie ein anderes Ziel oder einen anderen Start."),
    NO_STOP_IN_RANGE(70, RejectionMetric.AREA, true, "In Reichweite von Start oder Ziel gibt es keinen buchbaren Haltepunkt.", "Wählen Sie einen Start oder ein Ziel näher an einem Haltepunkt."),
    NO_ACCESSIBLE_STOP(75, RejectionMetric.EQUIPMENT, true, "In Reichweite gibt es keinen für Rollstuhlnutzung geeigneten Haltepunkt.", "Wählen Sie einen geeigneten Haltepunkt oder kontaktieren Sie den Betreiber."),
    NO_ROUTE(80, RejectionMetric.CAPACITY, true, "Zwischen Start und Ziel besteht keine befahrbare Verbindung.", "Wählen Sie ein anderes Ziel."),
    EQUIPMENT_MISSING(90, RejectionMetric.EQUIPMENT, true, "Kein Fahrzeug mit der benötigten Ausstattung verfügbar.", "Wählen Sie eine andere Zeit oder verzichten Sie auf die Zusatzausstattung."),
    NO_STAFF(100, RejectionMetric.PERSONNEL, true, "Zur gewünschten Zeit ist kein Fahrpersonal eingeplant.", "Wählen Sie eine andere Zeit."),
    NO_CAPACITY(110, RejectionMetric.CAPACITY, true, "Zur gewünschten Zeit ist kein Platz mehr frei.", "Wählen Sie eine andere Zeit oder verringern Sie die Personenzahl."),
    TECHNICAL(1000, null, false, "Die Anfrage konnte technisch nicht bearbeitet werden.", "Versuchen Sie es in einigen Minuten erneut."),
    ;

    companion object {
        fun of(code: String?): RejectionCode? = entries.firstOrNull { it.name == code }

        /**
         * Deterministische Wahl (OPT-011): aus den festgestellten [causes] (ohne Wiederholung, Reihenfolge der Feststellung egal) den Primärgrund und die Sekundärgründe. Technische
         * Ursachen zählen nie als Grund: Bleibt nach Abzug keine fachliche Ursache, ist die Entscheidung `null` (kein fachliches Ergebnis, der Aufrufer behandelt es als technischen Fehler).
         */
        fun decide(causes: Collection<RejectionCode>): RejectionDecision? {
            val business = causes.filter { it.counts }.distinct().sortedWith(compareBy({ it.priority }, { it.name }))
            if (business.isEmpty()) return null
            return RejectionDecision(business.first(), business.drop(1))
        }
    }
}

/** Genau ein Primärgrund, weitere Ursachen nur als [secondary] (nach Priorität). */
data class RejectionDecision(val primary: RejectionCode, val secondary: List<RejectionCode>)
