package de.taktovia.app.logging

import ch.qos.logback.classic.pattern.ClassicConverter
import ch.qos.logback.classic.pattern.ThrowableProxyConverter
import ch.qos.logback.classic.spi.ILoggingEvent
import de.taktovia.platform.logging.Maskierung

/** Logback-Wort `%safeMsg`: Logzeile mit maskierten kartennummernähnlichen Mustern (WP-M1-37) und aller Feldklassen der zentralen Maskierung (WP-M5-06: Kontakt, Zahlung, Token, Boarding-Code). */
class CardDataMaskingMessageConverter : ClassicConverter() {
    override fun convert(event: ILoggingEvent): String = Maskierung.maskText(event.formattedMessage ?: "")
}

/** Logback-Wort `%safeEx`: Ausnahmetext mit Stapel, ebenfalls maskiert. */
class CardDataMaskingThrowableConverter : ThrowableProxyConverter() {
    override fun convert(event: ILoggingEvent): String = Maskierung.maskText(super.convert(event))
}
