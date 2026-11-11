package de.taktovia.platform.tenant

import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Leitlinie 3: Aufrufe externer Dienste (PSP, Partner, Benachrichtigung) laufen nie innerhalb einer offenen SQL-Transaktion. Adapter rufen [requireNone] vor jedem
 * externen Aufruf; ist auf dem Thread eine Spring-Transaktion offen (auch eine von [TenantTransactions]), scheitert der Aufruf, bevor etwas gesendet wird.
 * Ergänzung zur Architekturregel (die nur `@Transactional`-Code prüft) und zum Nachweis an der Datenbank (Test `PspNoOpenTransactionTest`).
 */
object ExternalCallGuard {
    fun requireNone(what: String) {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "$what innerhalb einer offenen Datenbanktransaktion (Leitlinie 3)" }
    }
}
