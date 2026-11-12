package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.DefectAmendment
import de.taktovia.fleetsafety.api.DefectFilter
import de.taktovia.fleetsafety.api.EvidenceTicketView
import de.taktovia.fleetsafety.api.RequestEvidenceTicket
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.storage.ObjectKey
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * WP-M3-50 / LH-6.22-DMG-011: Vertraulichkeit der Schadenakte unter der Runtime-Rolle mit echter Datenbank, echten Mitgliedschaftszeilen (Rechteentzug), echten signierten Links
 * (steuerbare Uhr) und mandantengetrenntem Objektspeicher (Ersatz: prozesslokal). Originalabnahme: Fahrer anderer Fahrzeuge können keine vertraulichen Dokumente des Falls lesen.
 * Rollenmatrix der Schadenakte (Planungsannahme, nicht freigegeben): Melder (Fahrer) sieht Akte, Standarddokumente und eigene vertrauliche Dokumente; Leitstelle (dispatcher) Akte und
 * Standarddokumente; Werkstatt und Betriebsleitung (Prüfer) zusätzlich alle vertraulichen Dokumente; anderer Fahrer und anderer Mandant: nichts (404).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DefectConfidentialityTest {
    private lateinit var fx: FleetFixture
    private lateinit var h: EvidenceHarness

    @BeforeAll fun up() { fx = FleetFixture(); h = EvidenceHarness(fx) }
    @AfterAll fun down() = fx.close()

    private val issuer = "https://idp.test/realms/fleet"
    private val deniedAction = "security.defect_confidential.access_denied"
    private val readAction = "fleet.defect_evidence.confidential_read"

    private fun problem(block: () -> Unit): ProblemException = assertThrows(ProblemException::class.java, block)
    private fun assertNotFound(block: () -> Unit) = assertEquals(ErrorCode.NOT_FOUND, problem(block).code)

    /** Konto mit echter Mitgliedschaftszeile (Rolle `role`) im Mandanten; der Kontext trägt die Mitgliedschaft, damit der Entzug wirkt. */
    private fun member(role: String, tenant: TenantId, subject: UUID = UUID.randomUUID()): Pair<RequestContext, UUID> {
        val mid = UUID.randomUUID()
        fx.exec("INSERT INTO taktovia.membership(tenant_id,id,issuer,subject,role_code) VALUES (?,?,?,?,?)", tenant.value, mid, issuer, subject.toString(), role)
        return RequestContext(Actor(ActorType.HUMAN, issuer, subject.toString(), "sid-$subject", true), tenant, setOf(role), setOf("defects:read", "defects:write"), emptySet(), UUID.randomUUID(),
            membershipIds = setOf(mid)) to mid
    }

    private fun revoke(membership: UUID) = fx.exec("UPDATE taktovia.membership SET revoked_at = clock_timestamp() WHERE id = ?", membership)

    /** Foto über Ticket, Quarantäne und bestandene Medienprüfung (freigegeben); [confidential] setzt die Vertraulichkeitsstufe am Ticket. */
    private fun photo(ctx: RequestContext, w: DefectWorld, seed: Int, confidential: Boolean): EvidenceTicketView {
        val bytes = h.jpeg(seed)
        val raw = h.ticketRaw(bytes) + if (confidential) mapOf("confidentiality" to "CONFIDENTIAL") else emptyMap()
        val t = h.fleet.defectEvidence.requestTicket(ctx, RequestEvidenceTicket.parse(raw))
        assertEquals(if (confidential) "CONFIDENTIAL" else "STANDARD", t.confidentiality)
        h.putQuarantine(w, t, bytes)
        return h.complete(ctx, t)
    }

    /** Akte mit einem Standarddokument und einem vertraulichen Dokument des meldenden Fahrers. */
    private inner class Case(seed: Int) {
        val w = DefectWorld(fx)
        val reporterSubject: UUID = UUID.randomUUID()
        private val driverMember = member("driver", w.tenant, reporterSubject)
        private val workshopMember = member("workshop", w.tenant)
        val driver: RequestContext = driverMember.first
        val driverMembership: UUID = driverMember.second
        val dispatcher: RequestContext = member("dispatcher", w.tenant).first
        val workshop: RequestContext = workshopMember.first
        val workshopMembership: UUID = workshopMember.second
        val standard: EvidenceTicketView
        val confidential: EvidenceTicketView
        val defect: UUID

        init {
            w.shift(reporterSubject)
            standard = photo(driver, w, seed, false)
            confidential = photo(driver, w, seed + 1, true)
            defect = h.report(driver, w, standard.id, confidential.id).defect.id
        }

        fun otherDriver(onVehicle: UUID = fx.vehicle(w.tenant)): RequestContext = member("driver", w.tenant).first.also { w.shift(UUID.fromString(it.actor.subject), onVehicle, startOffsetHours = -30, lengthHours = 2) }
        fun link(ctx: RequestContext, ev: EvidenceTicketView = confidential) = h.fleet.defectEvidence.downloadLink(ctx, defect, ev.id)
        fun bytes(ev: EvidenceTicketView): ByteArray = h.store.get(ObjectKey(w.tenant, fx.strings("SELECT media_ref FROM taktovia.defect_evidence WHERE id=?", ev.id).single()))!!
        fun securityEvents(evidence: EvidenceTicketView = confidential) =
            fx.strings("SELECT action FROM taktovia.audit_event WHERE object_ref=? AND action=?", "defect_evidence:${evidence.id}", deniedAction)
    }

    @Test
    fun fahrer_anderes_fahrzeug_kann_vertrauliches_dokument_nicht_lesen() {
        val c = Case(10)
        val foreignVehicle = c.otherDriver()                              // Fahrer eines anderen Fahrzeugs
        val sameVehicleOtherShift = c.otherDriver(c.w.vehicle)            // Fahrer desselben Fahrzeugs, eigene (andere) Schicht, nicht Melder der Akte
        val otherTenant = Case(20)
        val ownerLink = c.link(c.workshop)                                // gültiger Link eines Berechtigten
        for ((name, other) in listOf("anderes Fahrzeug" to foreignVehicle, "andere Schicht" to sameVehicleOtherShift, "anderer Mandant" to otherTenant.driver)) {
            assertNotFound { h.fleet.defects.get(other, c.defect) }
            assertNotFound { h.fleet.defectEvidence.listForDefect(other, c.defect) }
            assertNotFound { h.fleet.defectEvidence.downloadLink(other, c.defect, c.confidential.id) }       // auch mit bekannter Dokument-ID
            assertNotFound { h.fleet.defectEvidence.downloadLink(other, c.defect, c.standard.id) }
            assertNotFound { h.fleet.defectEvidence.openLinked(other, ownerLink.url) }                       // weitergegebener Link
            assertTrue(h.fleet.defects.list(other, DefectFilter(vehicleId = c.w.vehicle), 50, null).none { it.id == c.defect }, name)
        }
        // Leitstelle: Akte und Standarddokument ja, vertrauliches Dokument existiert für sie nicht (kein Hinweis, nicht ausgegraut)
        val seen = h.fleet.defects.get(c.dispatcher, c.defect)
        assertEquals(listOf(c.standard.id), seen.evidence.map { it.id }); assertEquals(0, seen.evidencePending)
        assertEquals(listOf(c.standard.id), h.fleet.defectEvidence.listForDefect(c.dispatcher, c.defect).map { it.id })
        assertNotFound { c.link(c.dispatcher) }
        assertNotFound { h.fleet.defectEvidence.openLinked(c.dispatcher, ownerLink.url) }
        assertTrue(h.fleet.defectEvidence.openLinked(c.dispatcher, c.link(c.dispatcher, c.standard).url).bytes.contentEquals(c.bytes(c.standard)))
        // Berechtigte lesen
        assertTrue(h.fleet.defectEvidence.openLinked(c.workshop, ownerLink.url).bytes.contentEquals(c.bytes(c.confidential)))
        assertEquals(setOf(c.standard.id, c.confidential.id), h.fleet.defectEvidence.listForDefect(c.workshop, c.defect).map { it.id }.toSet())
        // Der Melder sieht seine Akte und seine eigenen Dokumente (Projektion Fahrer)
        assertEquals(setOf(c.standard.id, c.confidential.id), h.fleet.defects.get(c.driver, c.defect).evidence.map { it.id }.toSet())
    }

    @Test
    fun fremde_id_in_liste_suche_medien_404() {
        val c = Case(30)
        val mine = Case(40)
        val other = c.otherDriver()
        // Liste und Suche (Filter nach Fahrzeug, Status, Schwere): die fremde Akte kommt nie vor
        for (f in listOf(DefectFilter(), DefectFilter(vehicleId = c.w.vehicle), DefectFilter(status = "NEW"), DefectFilter(vehicleId = c.w.vehicle, status = "NEW", severity = "COSMETIC"))) {
            assertTrue(h.fleet.defects.list(other, f, 100, null).none { it.id == c.defect })
            assertTrue(h.fleet.defects.list(mine.driver, f, 100, null).none { it.id == c.defect })       // anderer Mandant
        }
        // Medienendpunkt: fremde Evidenz-ID unter eigener Akte, fremde Akte, unsignierter Pfad mit bekanntem Objektnamen
        val otherCase = Case(50)
        assertNotFound { h.fleet.defectEvidence.downloadLink(otherCase.driver, otherCase.defect, c.confidential.id) }
        assertNotFound { h.fleet.defectEvidence.downloadLink(c.workshop, otherCase.defect, c.confidential.id) }
        assertNotFound { h.fleet.defectEvidence.downloadLink(c.workshop, UUID.randomUUID(), c.confidential.id) }
        val ref = fx.strings("SELECT media_ref FROM taktovia.defect_evidence WHERE id=?", c.confidential.id).single()
        assertNotFound { h.fleet.defectEvidence.openLinked(c.workshop, "/v1/objects/${c.w.tenant.value}/$ref") }
        assertNotFound { h.fleet.defectEvidence.openLinked(c.workshop, "/v1/objects/${c.w.tenant.value}/$ref?exp=9999999999&purpose=defect_evidence&sig=${"0".repeat(64)}") }
        assertNotFound { h.fleet.defectEvidence.openLinked(other, "/v1/objects/${c.w.tenant.value}/$ref") }
        // Rückgabe des vertraulichen Anteils an das Ticket ist nur dem Urheber möglich
        assertEquals(ErrorCode.NOT_FOUND, problem { h.fleet.defectEvidence.get(other, c.confidential.id) }.code)
    }

    @Test
    fun abgelaufener_oder_weitergegebener_link_abgewiesen() {
        val c = Case(60)
        val link = c.link(c.workshop)
        assertTrue(link.expiresAt.isBefore(h.clock.instant().plusSeconds(61)))
        assertTrue(h.fleet.defectEvidence.openLinked(c.workshop, link.url).bytes.isNotEmpty())           // frisch
        // weitergegeben an Dritte (Fahrer eines anderen Fahrzeugs, Leitstelle, anderer Mandant)
        assertNotFound { h.fleet.defectEvidence.openLinked(c.otherDriver(), link.url) }
        assertNotFound { h.fleet.defectEvidence.openLinked(c.dispatcher, link.url) }
        assertNotFound { h.fleet.defectEvidence.openLinked(Case(70).workshop, link.url) }
        // verändert
        assertNotFound { h.fleet.defectEvidence.openLinked(c.workshop, link.url.dropLast(1) + (if (link.url.last() == '0') '1' else '0')) }
        assertNotFound { h.fleet.defectEvidence.openLinked(c.workshop, link.url.replace("exp=", "exp=1")) }
        // abgelaufen
        h.clock.advance(61)
        assertNotFound { h.fleet.defectEvidence.openLinked(c.workshop, link.url) }
        // ein neuer Link funktioniert wieder, danach läuft auch er ab
        val again = c.link(c.workshop)
        assertTrue(h.fleet.defectEvidence.openLinked(c.workshop, again.url).bytes.isNotEmpty())
        h.clock.advance(61)
        assertNotFound { h.fleet.defectEvidence.openLinked(c.workshop, again.url) }
    }

    @Test
    fun abgewiesener_zugriff_sicherheitsereignis_ohne_fallinhalt() {
        val c = Case(80)
        val other = c.otherDriver()
        assertEquals(0, c.securityEvents().size)
        assertNotFound { c.link(c.dispatcher) }                                        // Rolle ohne Recht auf vertrauliche Dokumente
        assertNotFound { h.fleet.defectEvidence.downloadLink(other, c.defect, c.confidential.id) }   // Akte nicht sichtbar
        val workshopLink = c.link(c.workshop)
        assertNotFound { h.fleet.defectEvidence.openLinked(other, workshopLink.url) }  // weitergegebener Link
        assertEquals(3, c.securityEvents().size)
        val actors = fx.strings("SELECT actor_ref FROM taktovia.audit_event WHERE object_ref=? AND action=? ORDER BY occurred_at", "defect_evidence:${c.confidential.id}", deniedAction)
        assertEquals(listOf("user:${c.dispatcher.actor.subject}", "user:${other.actor.subject}", "user:${other.actor.subject}"), actors)
        val details = fx.strings("SELECT redacted_changes::text FROM taktovia.audit_event WHERE object_ref=? AND action=?", "defect_evidence:${c.confidential.id}", deniedAction)
        val caseNumber = fx.strings("SELECT case_number FROM taktovia.defect_case WHERE id=?", c.defect).single()
        details.forEach { d ->
            assertFalse(d.contains("Kratzer"), d); assertFalse(d.contains(caseNumber), d); assertFalse(d.contains(c.confidential.sha256Hex), d); assertFalse(d.contains(c.w.vehicle.toString()), d)
            assertTrue(d.contains("reason"), d)
        }
        assertEquals(setOf("ROLE_NOT_PERMITTED", "NOT_VISIBLE"), details.map { Regex("\"reason\":\\s*\"([A-Z_]+)\"").find(it)!!.groupValues[1] }.toSet())
        // ein Zugriff auf ein nicht vorhandenes Dokument ist kein Sicherheitsereignis eines Falls
        val before = fx.count("SELECT count(*) FROM taktovia.audit_event WHERE action=?", deniedAction)
        assertNotFound { h.fleet.defectEvidence.downloadLink(c.workshop, c.defect, UUID.randomUUID()) }
        assertEquals(before, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE action=?", deniedAction))
    }

    @Test
    fun berechtigter_zugriff_wird_auditiert() {
        val c = Case(90)
        val link = c.link(c.workshop)
        val read = h.fleet.defectEvidence.openLinked(c.workshop, link.url)
        assertTrue(read.bytes.contentEquals(c.bytes(c.confidential)))
        val obj = "defect_evidence:${c.confidential.id}"
        val rows = fx.strings("SELECT actor_ref || '|' || redacted_changes::text FROM taktovia.audit_event WHERE object_ref=? AND action=? ORDER BY occurred_at", obj, readAction)
        assertEquals(2, rows.size, "Link-Ausstellung und Abruf")
        rows.forEach { assertTrue(it.startsWith("user:${c.workshop.actor.subject}|"), it); assertFalse(it.contains("Kratzer"), it) }
        assertTrue(rows[0].contains(Regex("\"stage\":\\s*\"link\"")) && rows[1].contains(Regex("\"stage\":\\s*\"download\"")), rows.toString())
        // der Urheber liest sein eigenes vertrauliches Dokument, auch das wird protokolliert
        assertTrue(h.fleet.defectEvidence.openLinked(c.driver, c.link(c.driver).url).bytes.isNotEmpty())
        assertEquals(4, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE object_ref=? AND action=?", obj, readAction))
        // ein Standarddokument braucht keinen Vertraulichkeitsnachweis
        assertTrue(h.fleet.defectEvidence.openLinked(c.dispatcher, c.link(c.dispatcher, c.standard).url).bytes.isNotEmpty())
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE object_ref=? AND action=?", "defect_evidence:${c.standard.id}", readAction))
        // Projektion je Rolle: das vertrauliche Dokument der Werkstatt (Nachtrag) sieht der meldende Fahrer nicht, die Werkstatt sieht beides
        val extra = photo(c.workshop, c.w, 95, true)
        h.fleet.defects.amend(c.workshop, c.defect, DefectAmendment.parse(mapOf("change_reason" to "Gutachten angehängt", "add_evidence_ids" to listOf(extra.id.toString()))))
        assertEquals(setOf(c.standard.id, c.confidential.id), h.fleet.defects.get(c.driver, c.defect).evidence.map { it.id }.toSet())
        assertEquals(setOf(c.standard.id, c.confidential.id), h.fleet.defectEvidence.listForDefect(c.driver, c.defect).map { it.id }.toSet())
        assertNotFound { h.fleet.defectEvidence.downloadLink(c.driver, c.defect, extra.id) }
        assertEquals(setOf(c.standard.id, c.confidential.id, extra.id), h.fleet.defects.get(c.workshop, c.defect).evidence.map { it.id }.toSet())
        assertTrue(h.fleet.defectEvidence.listForDefect(c.workshop, c.defect).first { it.id == extra.id }.confidentiality == "CONFIDENTIAL")
    }

    @Test
    fun rechteentzug_waehrend_offenem_downloadlink() {
        val c = Case(100)
        // 1. Entzug vor dem Abruf (committed): der offene Link nützt nichts mehr
        val link = c.link(c.workshop)
        revoke(c.workshopMembership)
        assertNotFound { h.fleet.defectEvidence.openLinked(c.workshop, link.url) }
        assertEquals(listOf(deniedAction), c.securityEvents())
        assertNotFound { c.link(c.workshop) }
        // auch der Urheber verliert mit seiner Mitgliedschaft den Zugriff auf sein vertrauliches Dokument
        val driverLink = c.link(c.driver)
        revoke(c.driverMembership)
        assertNotFound { h.fleet.defectEvidence.openLinked(c.driver, driverLink.url) }

        // 2. echte Gleichzeitigkeit: der Entzug ist noch nicht committed, der Abruf wartet auf die Mitgliedschaftszeile und sieht nach dem Commit den Entzug
        val (second, secondMembership) = member("workshop", c.w.tenant)
        val secondLink = c.link(second)
        val admin = FleetTestDb.admin()
        val pool = Executors.newSingleThreadExecutor()
        try {
            admin.connection.use { conn ->
                conn.autoCommit = false
                conn.prepareStatement("UPDATE taktovia.membership SET revoked_at = clock_timestamp() WHERE id = ?").use { ps -> ps.setObject(1, secondMembership); ps.executeUpdate() }
                val pending = pool.submit(Callable { runCatching { h.fleet.defectEvidence.openLinked(second, secondLink.url) } })
                assertThrows(TimeoutException::class.java, { pending.get(700, TimeUnit.MILLISECONDS) }, "der Abruf wartet auf den offenen Entzug")
                conn.commit()
                val outcome = pending.get(10, TimeUnit.SECONDS)
                assertEquals(ErrorCode.NOT_FOUND, (outcome.exceptionOrNull() as ProblemException).code, "nach dem Commit des Entzugs abgewiesen")
            }
        } finally { pool.shutdownNow(); admin.close() }
        // ein anderes, nicht entzogenes Konto liest weiter
        val (third, _) = member("workshop", c.w.tenant)
        assertTrue(h.fleet.defectEvidence.openLinked(third, c.link(third).url).bytes.isNotEmpty())
    }

    @Test
    fun mandanten_parallel_poolverbindung_isoliert() {
        val a = Case(110); val b = Case(120)
        val linkA = a.link(a.workshop); val linkB = b.link(b.workshop)
        val bytesA = a.bytes(a.confidential); val bytesB = b.bytes(b.confidential)
        val pool = Executors.newFixedThreadPool(8)       // Poolgröße der Fixture 12: Verbindungen werden zwischen den Mandanten wiederverwendet
        try {
            val jobs = (0 until 160).map { i ->
                Callable<String?> {
                    val (own, foreign, ownLink, ownBytes) = if (i % 2 == 0) Tuple(a, b, linkA, bytesA) else Tuple(b, a, linkB, bytesB)
                    val ctx = own.workshop
                    val ids = h.fleet.defectEvidence.listForDefect(ctx, own.defect).map { it.id }.toSet()
                    if (ids != setOf(own.standard.id, own.confidential.id)) return@Callable "falsche Liste $ids"
                    if (!h.fleet.defectEvidence.openLinked(ctx, ownLink.url).bytes.contentEquals(ownBytes)) return@Callable "falsche Bytes"
                    for (probe in listOf<() -> Unit>(
                        { h.fleet.defectEvidence.listForDefect(ctx, foreign.defect) },
                        { h.fleet.defectEvidence.downloadLink(ctx, foreign.defect, foreign.confidential.id) },
                        { h.fleet.defects.get(ctx, foreign.defect) },
                        { h.fleet.defectEvidence.openLinked(ctx, if (i % 2 == 0) linkB.url else linkA.url) },
                    )) {
                        val r = runCatching { probe() }.exceptionOrNull()
                        if (r !is ProblemException || r.code != ErrorCode.NOT_FOUND) return@Callable "fremder Zugriff nicht abgewiesen: $r"
                    }
                    null
                }
            }
            val failures = pool.invokeAll(jobs).mapNotNull { it.get() }
            assertEquals(emptyList<String>(), failures)
        } finally { pool.shutdownNow() }
    }

    private data class Tuple(val own: Case, val foreign: Case, val link: de.taktovia.platform.storage.SignedUrl, val bytes: ByteArray)
}
