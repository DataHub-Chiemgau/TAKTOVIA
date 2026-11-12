package de.taktovia.app.alerting

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.platform.alert.BusinessAlertPort
import de.taktovia.platform.context.ContextTransactions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * WP-M5-04: Übergabe technischer Alarme an die Fachalarmierung gegen die echte Anwendung und Datenbank: ein anhaltender Fehler ergibt genau einen offenen Alarm in der Arbeitsliste, nach Behebung
 * schließt er, erneutes Auftreten legt einen neuen an; nur feste Etiketten werden übernommen (keine Anmerkungstexte), ungültige Alarme werden verworfen. Der Alarm ist über die Arbeitsliste der
 * Leitstelle (HTTP) sichtbar.
 */
class TechnicalAlertBridgeTest {
    private val env = IdentityEnv
    private val bridge get() = TechnicalAlertBridge(env.server.ctx.getBean(ContextTransactions::class.java), env.server.ctx.getBean(BusinessAlertPort::class.java))

    private fun alert(status: String, name: String = "OutboxStau", scenario: String = "OUTBOX_STAU", component: String = "outbox-relay", extra: Map<String, String> = emptyMap(), annotations: Map<String, String> = emptyMap()) =
        mapOf("status" to status, "labels" to mapOf("alertname" to name, "scenario" to scenario, "component" to component, "severity" to "critical", "empfaenger" to "betrieb-bereitschaft") + extra, "annotations" to annotations)

    private fun payload(vararg alerts: Map<String, Any?>) = mapOf("version" to "4", "status" to "firing", "alerts" to alerts.toList())

    @Test
    fun anhaltender_fehler_ein_offener_alarm_schliesst_nach_behebung_neuer_alarm_bei_wiederkehr() {
        val t = env.tenant(); val s = UUID.randomUUID(); env.grant(t, s, "dispatcher", emptyList()); val token = env.token(s)
        fun open() = env.get("/v1/operator/alerts", token).json["items"].values().filter { it["kind"].asString() == "TECH_OUTBOX_STAU" }
        val first = bridge.handover(t, payload(alert("firing")))
        assertEquals(HandoverResult(1, 0, 0, 0), first)
        repeat(10) { assertEquals(HandoverResult(0, 1, 0, 0), bridge.handover(t, payload(alert("firing")))) }
        val items = open(); assertEquals(1, items.size, "ein anhaltender Fehler: genau ein offener Alarm")
        assertEquals("HIGH", items[0]["priority"].asString()); assertEquals("OUTBOX_STAU", items[0]["reason_code"].asString())
        assertEquals(HandoverResult(0, 0, 1, 0), bridge.handover(t, payload(alert("resolved"))))
        assertEquals(0, open().size, "nach Behebung geschlossen")
        assertEquals(1, env.get("/v1/operator/alerts?state=RESOLVED", token).json["items"].values().count { it["kind"].asString() == "TECH_OUTBOX_STAU" })
        assertEquals(HandoverResult(1, 0, 0, 0), bridge.handover(t, payload(alert("firing"))), "Wiederkehr: neuer Alarm")
        assertEquals(1, open().size)
    }

    @Test
    fun nur_feste_etiketten_werden_uebernommen_keine_texte_und_ungueltiges_wird_verworfen() {
        val t = env.tenant(); val s = UUID.randomUUID(); env.grant(t, s, "dispatcher", emptyList()); val token = env.token(s)
        val pii = mapOf("summary" to "Fahrgast Erika Muster, erika@example.test, Hauptstraße 1")
        val r = bridge.handover(t, payload(
            alert("firing", annotations = pii, extra = mapOf("passenger" to "Erika Muster", "instance" to "10.0.0.5:9090")),
            alert("firing", name = "x y", scenario = "OUTBOX_STAU"),                 // ungültiger Name
            alert("firing", scenario = "kleingeschrieben"),                          // ungültiges Szenario
            alert("firing", component = "a/b"),                                      // ungültige Komponente
            mapOf("status" to "firing"),                                             // ohne Etiketten
            alert("pending"),                                                         // unbekannter Status
        ))
        assertEquals(HandoverResult(1, 0, 0, 5), r)
        val text = env.get("/v1/operator/alerts", token).text
        for (forbidden in listOf("Erika", "Muster", "example.test", "Hauptstraße", "10.0.0.5", "passenger")) assertFalse(text.contains(forbidden), "Alarm enthält '$forbidden'")
        val rows = env.string("SELECT row_to_json(a)::text FROM taktovia.business_alert a WHERE tenant_id = ?", t.value)
        assertEquals(1, rows.size); assertTrue(rows[0].contains("TECH_OUTBOX_STAU")); assertFalse(rows[0].contains("Erika"))
        // mehr als 100 Alarme je Aufruf: nur die ersten 100 werden betrachtet
        val many = payload(*Array(150) { alert("firing", name = "Regel$it", scenario = "SZ_$it", component = "k$it") })
        assertEquals(100, bridge.handover(t, many).raised)
    }

    @Test
    fun mandantentrennung_der_uebergabe() {
        val a = env.tenant(); val b = env.tenant()
        bridge.handover(a, payload(alert("firing")))
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.business_alert WHERE tenant_id = ? AND alert_kind = 'TECH_OUTBOX_STAU'", a.value))
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.business_alert WHERE tenant_id = ?", b.value))
        assertEquals(HandoverResult(0, 0, 0, 0), bridge.handover(b, payload(alert("resolved"))), "fremder Mandant: nichts zu schließen")
    }
}
