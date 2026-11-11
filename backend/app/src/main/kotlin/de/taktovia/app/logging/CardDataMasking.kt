package de.taktovia.app.logging

import ch.qos.logback.classic.pattern.ClassicConverter
import ch.qos.logback.classic.pattern.ThrowableProxyConverter
import ch.qos.logback.classic.spi.ILoggingEvent
import de.taktovia.platform.carddata.CardData

/** Logback-Wort `%safeMsg`: Logzeile mit maskierten kartennummernähnlichen Mustern (WP-M1-37, Verteidigung in der Tiefe; die Eingabe wird vorher abgewiesen). */
class CardDataMaskingMessageConverter : ClassicConverter() {
    override fun convert(event: ILoggingEvent): String = CardData.mask(event.formattedMessage ?: "")
}

/** Logback-Wort `%safeEx`: Ausnahmetext mit Stapel, ebenfalls maskiert. */
class CardDataMaskingThrowableConverter : ThrowableProxyConverter() {
    override fun convert(event: ILoggingEvent): String = CardData.mask(super.convert(event))
}
