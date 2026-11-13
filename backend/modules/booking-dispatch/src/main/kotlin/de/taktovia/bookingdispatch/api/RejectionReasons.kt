package de.taktovia.bookingdispatch.api

/** Kennzahl, in die ein Ablehnungsgrund einzählt (Datenbasis, Berechnung in M4: Anhang K.1, WP-M4-46/-51). `null` bei technischen Fehlern (keine fachliche Ablehnung) und beim Regelverbot (keine eigene Kennzahl in K.1, zählt nur in die Angebotsquote). */
enum class RejectionMetric(val label: String) {
    CAPACITY("Kapazitätsablehnungen"), PERSONNEL("Personalmangelablehnungen"), EQUIPMENT("Ausstattungsablehnungen"), AREA("Gebietsablehnungen"),
    TIME_WINDOW("Zeitfensterablehnungen"),
    /** Technische Partnerablehnungen (WP-M4-50): in P1 nur als Definition, keine Partner. */
    PARTNER("Technische Partnerablehnungen"),
}

/**
 * Gruppe des Ablehnungsgrunds in der Berichtsspalte „Ablehnungsgrund“ (Spalte `rejection_reason.reason_group`, WP-M4-48): feiner als die Kennzahl, z. B. getrennt nach Betrieb geschlossen
 * ([TIME_OPENING]) und Frist ([TIME_DEADLINE]), die beide in die Zeitfensterablehnungen zählen.
 */
enum class RejectionGroup { CAPACITY, PERSONNEL, EQUIPMENT, AREA, TIME_OPENING, TIME_DEADLINE, RULE, TARIFF, LIMIT, PARTNER, TECHNICAL }

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
enum class RejectionCode(val priority: Int, val metric: RejectionMetric?, val counts: Boolean, val text: String, val nextAction: String, val group: RejectionGroup) {
    LEAD_TIME(10, RejectionMetric.TIME_WINDOW, true, "Die gewünschte Abfahrt liegt zu kurzfristig.", "Wählen Sie eine spätere Abfahrtszeit.", RejectionGroup.TIME_DEADLINE),
    HORIZON(20, RejectionMetric.TIME_WINDOW, true, "Die gewünschte Zeit liegt zu weit in der Zukunft.", "Wählen Sie einen näheren Termin.", RejectionGroup.TIME_DEADLINE),
    WINDOW_NOT_FEASIBLE(30, RejectionMetric.TIME_WINDOW, true, "Für die gewünschte Ankunft lässt sich kein erreichbares Abholfenster bilden.", "Wählen Sie eine spätere Ankunftszeit.", RejectionGroup.TIME_DEADLINE),
    OUTSIDE_HOURS(40, RejectionMetric.TIME_WINDOW, true, "Der Dienst ist zur gewünschten Zeit nicht in Betrieb.", "Wählen Sie eine Zeit innerhalb der Betriebszeiten.", RejectionGroup.TIME_OPENING),
    OUTSIDE_AREA(50, RejectionMetric.AREA, true, "Start oder Ziel liegt außerhalb des Bediengebiets.", "Wählen Sie Start und Ziel innerhalb des Bediengebiets.", RejectionGroup.AREA),
    RULE_FORBIDDEN(60, null, true, "Diese Verbindung ist für den Dienst nicht zugelassen.", "Wählen Sie ein anderes Ziel oder einen anderen Start.", RejectionGroup.RULE),
    NO_STOP_IN_RANGE(70, RejectionMetric.AREA, true, "In Reichweite von Start oder Ziel gibt es keinen buchbaren Haltepunkt.", "Wählen Sie einen Start oder ein Ziel näher an einem Haltepunkt.", RejectionGroup.AREA),
    NO_ACCESSIBLE_STOP(75, RejectionMetric.EQUIPMENT, true, "In Reichweite gibt es keinen für Rollstuhlnutzung geeigneten Haltepunkt.", "Wählen Sie einen geeigneten Haltepunkt oder kontaktieren Sie den Betreiber.", RejectionGroup.EQUIPMENT),
    NO_ROUTE(80, RejectionMetric.CAPACITY, true, "Zwischen Start und Ziel besteht keine befahrbare Verbindung.", "Wählen Sie ein anderes Ziel.", RejectionGroup.CAPACITY),
    EQUIPMENT_MISSING(90, RejectionMetric.EQUIPMENT, true, "Kein Fahrzeug mit der benötigten Ausstattung verfügbar.", "Wählen Sie eine andere Zeit oder verzichten Sie auf die Zusatzausstattung.", RejectionGroup.EQUIPMENT),
    NO_STAFF(100, RejectionMetric.PERSONNEL, true, "Zur gewünschten Zeit ist kein Fahrpersonal eingeplant.", "Wählen Sie eine andere Zeit.", RejectionGroup.PERSONNEL),
    NO_CAPACITY(110, RejectionMetric.CAPACITY, true, "Zur gewünschten Zeit ist kein Platz mehr frei.", "Wählen Sie eine andere Zeit oder verringern Sie die Personenzahl.", RejectionGroup.CAPACITY),
    NO_TARIFF(65, null, true, "Für die gewünschte Verbindung ist kein gültiger Tarif hinterlegt.", "Wählen Sie eine andere Verbindung oder kontaktieren Sie den Betreiber.", RejectionGroup.TARIFF),
    MAX_WAIT_EXCEEDED(35, RejectionMetric.TIME_WINDOW, true, "Die Wartezeit bis zur Abholung würde die zulässige Maximalwartezeit überschreiten.", "Wählen Sie eine spätere Abfahrtszeit.", RejectionGroup.TIME_DEADLINE),
    NO_VEHICLE(85, RejectionMetric.CAPACITY, true, "Zur gewünschten Zeit steht im Bediengebiet kein Fahrzeug zur Verfügung.", "Wählen Sie eine andere Zeit.", RejectionGroup.CAPACITY),
    SHIFT_ENDED(105, RejectionMetric.PERSONNEL, true, "Die Schicht des verfügbaren Fahrpersonals endet vor der gewünschten Fahrt.", "Wählen Sie eine frühere Zeit.", RejectionGroup.PERSONNEL),
    MAX_DETOUR_EXCEEDED(115, null, true, "Die Fahrt würde die zulässige Umweggrenze überschreiten.", "Wählen Sie eine andere Zeit oder ein anderes Ziel.", RejectionGroup.LIMIT),
    PARTNER_UNAVAILABLE(120, RejectionMetric.PARTNER, true, "Der Partnerbetrieb für diese Verbindung ist nicht erreichbar.", "Versuchen Sie es später erneut oder wählen Sie eine andere Verbindung.", RejectionGroup.PARTNER),
    TECHNICAL(1000, null, false, "Die Anfrage konnte technisch nicht bearbeitet werden.", "Versuchen Sie es in einigen Minuten erneut.", RejectionGroup.TECHNICAL),
    ;

    companion object {
        fun of(code: String?): RejectionCode? = entries.firstOrNull { it.name == code }

        /**
         * Grund einer verletzten harten Grenze (WP-M2-18) oder eines zugesagten Fensters als Ablehnungscode (WP-M4-50): Maximalwartezeit und Umweggrenze; die übrigen Verletzungen betreffen bestehende
         * Zusagen (kein Grund für eine neue Anfrage) und liefern `null`.
         */
        fun of(violation: WindowViolationCode): RejectionCode? = when (violation) {
            WindowViolationCode.MAX_WAIT_EXCEEDED -> MAX_WAIT_EXCEEDED
            WindowViolationCode.MAX_DETOUR_EXCEEDED -> MAX_DETOUR_EXCEEDED
            else -> null
        }

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
