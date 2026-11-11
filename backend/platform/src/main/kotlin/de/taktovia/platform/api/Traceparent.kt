package de.taktovia.platform.api

import java.util.UUID

/** W3C Trace Context `traceparent` (`00-<trace-id>-<parent-id>-<flags>`). */
data class TraceContext(val traceId: String, val parentId: String, val flags: String) {
    /** Die Correlation-ID ist die Trace-ID als UUID; so findet man Fehlerantwort und Trace über dieselbe ID. */
    fun correlationId(): UUID = UUID.fromString(traceId.replaceFirst(Regex("(.{8})(.{4})(.{4})(.{4})(.{12})"), "$1-$2-$3-$4-$5"))
}

object Traceparent {
    private val FORMAT = Regex("^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$")

    /** `null` bei ungültigem Header (Format, Version `ff`, Trace- oder Parent-ID nur aus Nullen). */
    fun parse(header: String?): TraceContext? {
        val m = FORMAT.matchEntire(header ?: return null) ?: return null
        val (version, trace, parent, flags) = m.destructured
        if (version == "ff" || trace.all { it == '0' } || parent.all { it == '0' }) return null
        return TraceContext(trace, parent, flags)
    }

    /** Gültiger Header: Trace-ID wird übernommen; ungültig oder fehlend: neue Correlation-ID (Kap. 05 §5.1). */
    fun correlationId(header: String?): UUID = parse(header)?.correlationId() ?: UUID.randomUUID()
}
