package de.taktovia.platform.telemetry

/** Endpunktklasse für die Latenzmetrik (WP-M5-03): getrennt, weil Angebote (p95 ≤ 5 s) und Standard-API (p95 ≤ 1 s) unterschiedliche Ziele haben (NFR-003, NFR-004). */
enum class RouteClass(val label: String) {
    OFFER("offer"), BOOKING("booking"), DRIVER("driver"), OPERATOR("operator"), STANDARD("standard"), HEALTH("health"), OTHER("other");

    companion object {
        /** Klassifiziert nach Pfadpräfix; der Pfad selbst wird nie als Label verwendet (Pfadparameter können Personenbezug tragen). */
        fun of(path: String?): RouteClass {
            val p = path ?: return OTHER
            return when {
                p.startsWith("/actuator") || p == "/healthz" -> HEALTH
                p.startsWith("/v1/ride-offers") -> OFFER
                p.startsWith("/v1/bookings") -> BOOKING
                p.startsWith("/v1/driver") -> DRIVER
                p.startsWith("/v1/operator") -> OPERATOR
                p.startsWith("/v1/") -> STANDARD
                else -> OTHER
            }
        }

        private val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
        fun method(m: String?): String = m?.uppercase()?.takeIf { it in METHODS } ?: "OTHER"
        fun statusClass(status: Int): String = "${status / 100}xx"
    }
}
