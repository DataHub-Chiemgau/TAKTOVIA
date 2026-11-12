package de.taktovia.app.alerting

import de.taktovia.platform.alert.BusinessAlertPort
import de.taktovia.platform.alert.BusinessAlertRequest
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.tenant.TenantId

/** Ergebnis einer Übernahme: wie viele technische Alarme neu angelegt, bestätigt (bestanden bereits), geschlossen oder verworfen wurden. */
data class HandoverResult(val raised: Int, val existing: Int, val resolved: Int, val skipped: Int)

/**
 * Übergabe betrieblich relevanter technischer Alarme (Alertmanager-Webhook, Version 4) an die Fachalarmierung (WP-M5-04, Kap. 04 §4.4): je `firing`-Alarm ein `TECH_<Szenario>`-Alarm in
 * `business_alert` über den Port der Alarm-Grundlage (WP-M3-52), je `resolved`-Alarm das Schließen. Dedupe über den Bedingungsschlüssel `TECH|<alertname>|<component>`: ein anhaltender Fehler ergibt
 * genau einen offenen Alarm, nach Behebung entsteht bei erneutem Auftreten ein neuer.
 *
 * **Minimierung:** übernommen werden ausschließlich die Etiketten `scenario`, `alertname` und `component` (feste Codes bzw. technische Namen, strikt geprüft); Anmerkungen, Texte und alle
 * anderen Etiketten werden nie gelesen. Damit gelangen weder Fahrgast- noch Personaldaten in den Alarm.
 *
 * **Nicht verdrahtet:** ob technische Alarme überhaupt und für welchen Mandanten in der Arbeitsliste geführt werden, ist offen (OP-07); die Komponente hat deshalb keinen HTTP-Eingang und
 * keine Standardverdrahtung, der Aufrufer wählt den Mandanten.
 */
class TechnicalAlertBridge(private val ctxTx: ContextTransactions, private val port: BusinessAlertPort) {
    private val code = Regex("^[A-Z][A-Z0-9_]{1,50}$")
    private val name = Regex("^[A-Za-z][A-Za-z0-9_-]{1,60}$")

    fun handover(tenant: TenantId, payload: Map<String, Any?>): HandoverResult {
        var raised = 0; var existing = 0; var resolved = 0; var skipped = 0
        val alerts = (payload["alerts"] as? List<*>).orEmpty().take(MAX_ALERTS)
        ctxTx.runSystem(tenant) { j ->
            for (a in alerts) {
                @Suppress("UNCHECKED_CAST") val labels = ((a as? Map<*, *>)?.get("labels") as? Map<String, Any?>)
                val scenario = labels?.get("scenario") as? String
                val alertname = labels?.get("alertname") as? String
                val component = labels?.get("component") as? String
                val status = (a as? Map<*, *>)?.get("status") as? String ?: payload["status"] as? String
                if (scenario == null || alertname == null || component == null || !code.matches(scenario) || !name.matches(alertname) || !name.matches(component) || status !in setOf("firing", "resolved")) { skipped++; continue }
                val key = "TECH|$alertname|$component"
                if (status == "firing") {
                    val r = port.raise(j, BusinessAlertRequest("TECH_$scenario", dedupeKey = key, reasonCode = scenario))
                    if (r.created) raised++ else existing++
                } else resolved += port.resolve(j, key)
            }
        }
        return HandoverResult(raised, existing, resolved, skipped)
    }

    private companion object { const val MAX_ALERTS = 100 }
}
