package de.taktovia.app.payment

import de.taktovia.bookingdispatch.api.Bookings
import de.taktovia.bookingdispatch.api.CouplingOutcome
import de.taktovia.farepayment.api.CouplingKind
import de.taktovia.farepayment.api.CouplingTask
import de.taktovia.farepayment.api.Payments
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.slf4j.LoggerFactory

/**
 * Kopplung von Zahlung und Buchung über **explizite Fachoperationen** (WP-M2-30): das Modul `fare-payment` schreibt nie eine Buchung und liest nie ihren Zustand, die Buchung nie einen
 * Zahlstatus. Diese Klasse arbeitet die Aufgabenliste des Zahlungsmoduls ab:
 *  - Zahlung `AUTHORIZED` (OP-5): Buchung `CONFIRMING` -> `CONFIRMED` (`Bookings.confirmAfterPayment`, Grund `PAYMENT_AUTHORIZED`), danach stößt das Zahlungsmodul den Einzug an. Ist die Buchung
 *    inzwischen nicht mehr `CONFIRMING`, wird sie nicht bestätigt; die Autorisierung wird dann vom Zahlungsmodul aufgehoben (siehe `Payments.releaseAuthorizationIn`).
 *  - Autorisierung abgelehnt, abgelaufen (SCA länger als der Hold) oder aufgehoben: die Buchung wird mit Grund `PAYMENT_FAILED` storniert und gibt Hold und Kapazität frei; es wird nie ein
 *    abgelaufener Platz bestätigt.
 * Die Operationen der Buchung laufen als Systemauftrag ohne Personenkontext (der Mandant kommt aus dem Auftrag). Jeder Schritt ist wiederholbar (Wiederaufnahme nach Abbruch): ein bereits erfolgter Buchungsübergang wird erkannt, die Kopplung erst danach als vollzogen markiert.
 */
class PaymentBookingCoupling(private val tx: TenantTransactions, private val payments: Payments, private val bookings: Bookings) {
    private val log = LoggerFactory.getLogger("taktovia.payment.coupling")

    /** Ein Durchlauf für den Mandanten; Anzahl vollzogener Kopplungen. Ein Fehler bei einer Aufgabe stoppt die anderen nicht. */
    fun runTenant(tenant: TenantId, limit: Int = 50): Int {
        val tasks = tx.run(tenant, readOnly = true) { j -> payments.couplingTasksIn(j, limit) }
        var done = 0
        for (t in tasks) {
            try { if (handle(tenant, t)) done++ } catch (e: Exception) { log.warn("Kopplung zurückgestellt ({}): {}", t.kind, e.javaClass.simpleName) }
        }
        return done
    }

    private fun handle(tenant: TenantId, t: CouplingTask): Boolean {
        when (t.kind) {
            CouplingKind.CONFIRM_BOOKING -> {
                when (bookings.confirmAfterPayment(tenant, t.bookingId)) {
                    CouplingOutcome.APPLIED, CouplingOutcome.ALREADY_APPLIED -> tx.run(tenant) { j -> payments.completeCouplingIn(j, t.paymentId, CouplingKind.CONFIRM_BOOKING) }   // danach der Einzug
                    // Buchung inzwischen storniert oder abgeschlossen: sie wird nicht bestätigt, die Autorisierung wird aufgehoben
                    CouplingOutcome.NOT_APPLICABLE -> tx.run(tenant) { j -> payments.releaseAuthorizationIn(j, t.paymentId) }
                }
            }
            CouplingKind.RELEASE_BOOKING -> {
                bookings.cancelAfterPaymentFailure(tenant, t.bookingId)   // Hold und Kapazität werden frei; eine bestätigte Buchung bleibt unberührt
                tx.run(tenant) { j -> payments.completeCouplingIn(j, t.paymentId, CouplingKind.RELEASE_BOOKING) }
            }
        }
        return true
    }
}
