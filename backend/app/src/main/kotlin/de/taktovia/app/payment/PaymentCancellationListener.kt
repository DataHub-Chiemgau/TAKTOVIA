package de.taktovia.app.payment

import de.taktovia.bookingdispatch.api.CancellationEvent
import de.taktovia.bookingdispatch.api.CancellationListener
import de.taktovia.bookingdispatch.api.RefundHint
import de.taktovia.farepayment.api.CancelledBooking
import de.taktovia.farepayment.api.Payments
import org.springframework.jdbc.core.JdbcOperations

/**
 * Verbindet „Buchung storniert“ mit dem Zahlungsmodul (WP-M2-31): in der Transaktion des Stornos wird bei `AUTHORIZED` ein Void, bei `CAPTURED` eine Erstattung in der durch Stornoregel und Grund
 * bestimmten Höhe **beauftragt** (nie ein PSP-Aufruf in der Transaktion); der Erstattungshinweis geht in die Stornoantwort. Die Module kennen einander nicht, die Anwendung verdrahtet sie.
 */
class PaymentCancellationListener(private val payments: Payments) : CancellationListener {
    override fun onCancelled(j: JdbcOperations, event: CancellationEvent): RefundHint? =
        payments.onBookingCancelledIn(j, CancelledBooking(event.bookingId, event.reason.name, event.cause.name))?.let { RefundHint(it.status.name, it.amountMinor, it.currency) }
}
