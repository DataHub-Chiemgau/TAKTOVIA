package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.EvidenceState
import de.taktovia.fleetsafety.api.MediaInspectors
import de.taktovia.fleetsafety.api.RequestEvidenceTicket
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.storage.ObjectKey
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M3-44 / LH-6.22-DMG-004: Fotoevidenz unter der Runtime-Rolle mit echter Datenbank, mandantengetrenntem Objektspeicher (Ersatz: prozesslokal, S3/MinIO nicht ausgeführt) und echter
 * Medienprüfung (Dekodieren, Neu-Kodieren ohne Metadaten, EICAR). Originalabnahme: vollständiger Musterfall erfassbar; Pflichtangaben mit Feldverweis; Fotos erst nach bestandener Prüfung
 * sichtbar; falscher Hash/MIME abgewiesen; veränderte Objekte erkannt; Link anderer Mandanten und abgelaufene Links scheitern; doppelter Upload ergibt einen Evidenzeintrag.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DefectEvidenceUploadTest {
    private lateinit var fx: FleetFixture
    private lateinit var h: EvidenceHarness

    @BeforeAll fun up() { fx = FleetFixture(); h = EvidenceHarness(fx) }
    @AfterAll fun down() = fx.close()

    private fun problem(block: () -> Unit): ProblemException = assertThrows(ProblemException::class.java, block)
    private fun pointers(p: ProblemException) = p.violations.map { it.pointer }

    private fun guided(kind: String? = "DENT", area: String? = "LEFT_SIDE", effect: String? = "COSMETIC_ONLY"): Map<String, Any?> =
        mapOf("capture_mode" to "GUIDED", "kind" to kind, "vehicle_area" to area, "observed_effect" to effect).filterValues { it != null }

    @Test
    fun foto_wird_erst_nach_bestandener_medienpruefung_sichtbar_und_ohne_exif_unter_mandantenpraefix_gespeichert() {
        val w = DefectWorld(fx); val ctx = w.ctx("dispatcher")
        val original = h.jpeg(1, exif = true)
        assertTrue(String(original, Charsets.ISO_8859_1).contains("Exif"))
        val t = h.ticket(ctx, original); assertEquals(EvidenceState.ISSUED, t.state)
        h.putQuarantine(w, t, original)
        h.inspector.available = false                                  // Prüfer ausgefallen: fail-closed
        val inReview = h.complete(ctx, t)
        assertEquals(EvidenceState.IN_REVIEW, inReview.state)
        val d = h.report(ctx, w, t.id, extra = guided()).defect
        assertEquals("DENT", d.kind); assertEquals("LEFT_SIDE", d.vehicleArea); assertEquals("COSMETIC_ONLY", d.observedEffect)
        // „in Prüfung“: nicht sichtbar, nicht abrufbar
        assertEquals(0, d.evidence.size); assertEquals(1, d.evidencePending)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.defect_evidence WHERE defect_id=?", d.id))
        assertEquals(ErrorCode.NOT_FOUND, problem { h.fleet.defectEvidence.downloadLink(ctx, d.id, t.id) }.code)
        assertEquals(0, h.fleet.defectEvidence.reviewPending(w.tenant))      // Prüfer weiter nicht verfügbar: bleibt in Quarantäne
        assertEquals(1, h.fleet.defects.get(ctx, d.id).evidencePending)
        // Prüfer wieder da: freigegeben
        h.inspector.available = true
        assertEquals(1, h.fleet.defectEvidence.reviewPending(w.tenant))
        val after = h.fleet.defects.get(ctx, d.id)
        assertEquals(1, after.evidence.size); assertEquals(0, after.evidencePending)
        val e = after.evidence.single(); assertEquals(t.id, e.id); assertEquals("image/jpeg", e.mime)
        // Objekt unter dem Mandantenpräfix, Metadaten (EXIF mit Standort) entfernt, gespeicherter Hash gehört zur gespeicherten Fassung
        val ref = fx.strings("SELECT media_ref FROM taktovia.defect_evidence WHERE id=?", t.id).single()
        assertEquals("tenants/${w.tenant.value}/$ref", ObjectKey(w.tenant, ref).path())
        val stored = h.store.get(ObjectKey(w.tenant, ref))!!
        assertFalse(String(stored, Charsets.ISO_8859_1).contains("Exif")); assertFalse(String(stored, Charsets.ISO_8859_1).contains("GPS"))
        assertEquals(h.hashHex(stored), e.sha256); assertNotEquals(h.hashHex(original), e.sha256)
        assertEquals(h.hashHex(original), fx.strings("SELECT original_sha256 FROM taktovia.defect_evidence WHERE id=?", t.id).single())
        assertNull(h.store.get(ObjectKey(w.tenant, t.uploadKey)), "Quarantäneobjekt entfällt nach dem Urteil")
        assertEquals(listOf("fleet.defect_evidence.released"), fx.strings("SELECT action FROM taktovia.audit_event WHERE object_ref=?", "defect_evidence:${t.id}"))
        // abrufbar über einen signierten Link, höchstens 60 s gültig
        val link = h.fleet.defectEvidence.downloadLink(ctx, d.id, t.id)
        assertTrue(link.expiresAt.isBefore(h.clock.instant().plusSeconds(61)))
        assertTrue(h.fleet.defectEvidence.openLinked(ctx, link.url).bytes.contentEquals(stored))
        // Ereignis nennt die Evidenzreferenz, nie Bild oder Beschreibung
        assertEquals(listOf(t.id.toString()), fx.strings("SELECT payload->'evidence_ids'->>0 FROM taktovia.outbox WHERE aggregate_id=? AND event_type='defect.reported'", d.id))
    }

    private fun assertNull(x: Any?, msg: String) = org.junit.jupiter.api.Assertions.assertNull(x, msg)

    @Test
    fun falscher_hash_wird_abgewiesen() {
        val w = DefectWorld(fx); val ctx = w.ctx("dispatcher")
        val bytes = h.jpeg(2); val other = h.jpeg(3)
        val t = h.ticket(ctx, bytes, declaredHash = h.hashWire(other))      // erwartet wird der Hash einer anderen Datei
        h.putQuarantine(w, t, bytes)
        val p = problem { h.complete(ctx, t) }
        assertEquals(ErrorCode.VALIDATION_FAILED, p.code); assertEquals(listOf("/sha256"), pointers(p))
        val row = h.fleet.defectEvidence.get(ctx, t.id)
        assertEquals(EvidenceState.REJECTED, row.state); assertEquals("HASH_MISMATCH", row.rejectReason)
        // nicht an die Akte gehängt, auch nicht über die Meldung
        val bad = problem { h.report(ctx, w, t.id) }
        assertEquals(listOf("/evidence_ids/0"), pointers(bad))
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.defect_case WHERE tenant_id=?", w.tenant.value))
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.defect_evidence WHERE upload_id=?", t.id))
        // fehlendes Objekt und abweichende Größe
        val noObject = h.ticket(ctx, h.jpeg(4)); assertEquals("OBJECT_MISSING", runCatching { h.complete(ctx, noObject) }.exceptionOrNull().let { (it as ProblemException).detail!!.substringAfter("(").substringBefore(")") })
        val b5 = h.jpeg(5); val sized = h.ticket(ctx, b5, size = b5.size + 1L); h.putQuarantine(w, sized, b5)
        assertEquals(listOf("/size_bytes"), pointers(problem { h.complete(ctx, sized) }))
    }

    @Test
    fun unzulaessiger_mime_uebergrosse_und_ausfuehrbare_datei_werden_abgewiesen() {
        val w = DefectWorld(fx); val ctx = w.ctx("workshop")
        val gif = problem { RequestEvidenceTicket.parse(h.ticketRaw(h.jpeg(6), mime = "image/gif")) }; assertEquals(listOf("/mime"), pointers(gif))
        val big = problem { RequestEvidenceTicket.parse(h.ticketRaw(h.jpeg(6), size = 11L * 1024 * 1024)) }; assertEquals(listOf("/size_bytes"), pointers(big)); assertEquals(ViolationCode.ABOVE_MAXIMUM, big.violations.single().code)
        assertEquals(listOf("/sha256"), pointers(problem { RequestEvidenceTicket.parse(h.ticketRaw(h.jpeg(6), declaredHash = "kein-hash")) }))
        // ausführbare Datei als „JPEG“ deklariert: der tatsächliche Typ weicht ab
        val elf = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()) + ByteArray(64)
        val te = h.ticket(ctx, elf); h.putQuarantine(w, te, elf)
        assertEquals(listOf("/mime"), pointers(problem { h.complete(ctx, te) })); assertEquals("MIME_MISMATCH", h.fleet.defectEvidence.get(ctx, te.id).rejectReason)
        // JPEG-Kopf mit eingebetteter Schadsoftware-Testsignatur: in der Medienprüfung abgewiesen, nie freigegeben
        val infected = h.jpeg(7) + MediaInspectors.EICAR.toByteArray(Charsets.ISO_8859_1)
        val ti = h.ticket(ctx, infected); h.putQuarantine(w, ti, infected)
        assertEquals(listOf("/content"), pointers(problem { h.complete(ctx, ti) }))
        val rejected = h.fleet.defectEvidence.get(ctx, ti.id); assertEquals(EvidenceState.REJECTED, rejected.state); assertEquals("MALWARE", rejected.rejectReason)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE object_ref=? AND action='fleet.defect_evidence.rejected'", "defect_evidence:${ti.id}"))
        // JPEG-Kopf, aber kein dekodierbares Bild
        val junk = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(40) { 7 }
        val tj = h.ticket(ctx, junk); h.putQuarantine(w, tj, junk)
        assertEquals(listOf("/content"), pointers(problem { h.complete(ctx, tj) })); assertEquals("MEDIA_INVALID", h.fleet.defectEvidence.get(ctx, tj.id).rejectReason)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.defect_evidence WHERE created_by=?", "user:${ctx.actor.subject}"))
        // Berechtigung: Fahrgast und Support laden nichts hoch
        assertEquals(ErrorCode.FORBIDDEN, problem { h.ticket(w.ctx("passenger"), h.jpeg(8)) }.code)
    }

    @Test
    fun doppelter_upload_ein_evidenzeintrag() {
        val w = DefectWorld(fx); val ctx = w.ctx("dispatcher"); val bytes = h.jpeg(10)
        // Wiederholung der Ticketanforderung nach Zeitüberschreitung: dasselbe Ticket
        val t1 = h.ticket(ctx, bytes); val t2 = h.ticket(ctx, bytes)
        assertEquals(t1.id, t2.id)
        // parallele Anforderung derselben Datei: genau ein Ticket
        val bytes2 = h.jpeg(11); val pool = Executors.newFixedThreadPool(6); val start = CountDownLatch(1)
        val ids = (1..6).map { pool.submit<UUID> { start.await(); h.ticket(ctx, bytes2).id } }
        start.countDown(); assertEquals(1, ids.map { it.get(60, TimeUnit.SECONDS) }.toSet().size)
        assertEquals(2, fx.count("SELECT count(*) FROM taktovia.defect_evidence_upload WHERE tenant_id=?", w.tenant.value))
        // paralleler Abschluss mit derselben Revision: genau einer gewinnt
        h.putQuarantine(w, t1, bytes)
        val start2 = CountDownLatch(1)
        val results = (1..4).map { pool.submit<Result<Any>> { start2.await(); runCatching { h.complete(ctx, t1) } } }
        start2.countDown()
        val rs = results.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, rs.count { it.isSuccess }, rs.toString())
        assertTrue(rs.filter { it.isFailure }.all { (it.exceptionOrNull() as ProblemException).code in setOf(ErrorCode.REVISION_MISMATCH, ErrorCode.CONFLICT) })
        // die Meldung wird nach Zeitüberschreitung wiederholt (gleiche Geräteereignis-ID) und trägt dasselbe Foto: eine Akte, ein Evidenzeintrag
        val ev = UUID.randomUUID()
        val first = h.report(ctx, w, t1.id, extra = mapOf("device_event_id" to ev.toString()))
        val again = h.report(ctx, w, t1.id, extra = mapOf("device_event_id" to ev.toString()))
        assertTrue(first.created); assertFalse(again.created); assertEquals(first.defect.id, again.defect.id)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.defect_evidence WHERE defect_id=?", first.defect.id))
        assertEquals(1, h.fleet.defects.get(ctx, first.defect.id).evidence.size)
        // dasselbe Foto an einer zweiten Akte: bereits gebunden, 422
        assertEquals(listOf("/evidence_ids/0"), pointers(problem { h.report(ctx, w, t1.id) }))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.defect_case WHERE tenant_id=?", w.tenant.value))
    }

    @Test
    fun pflichtangaben_fehlen_ergibt_422_mit_feldverweis() {
        val w = DefectWorld(fx); val ctx = w.ctx("dispatcher")
        // geführte Erfassung: Art, Fahrzeugbereich, Auswirkung und Foto sind Pflicht
        assertEquals(listOf("/kind", "/vehicle_area", "/observed_effect", "/evidence_ids"), pointers(problem { h.report(ctx, w, extra = mapOf("capture_mode" to "GUIDED")) }))
        assertEquals(listOf("/vehicle_area"), pointers(problem { h.report(ctx, w, UUID.randomUUID(), extra = guided(area = null)) }))
        assertEquals(listOf("/kind"), pointers(problem { h.report(ctx, w, UUID.randomUUID(), extra = guided(kind = "ERFUNDEN")) }))
        // Freitext über der Grenze
        assertEquals(listOf("/description"), pointers(problem { h.report(ctx, w, extra = mapOf("description" to "x".repeat(2001))) }))
        // ein Ticket ohne abgeschlossenen Upload zählt nicht als Foto; fremdes oder unbekanntes Ticket ebenso
        val bytes = h.jpeg(20); val open = h.ticket(ctx, bytes)
        assertEquals(listOf("/evidence_ids/0"), pointers(problem { h.report(ctx, w, open.id, extra = guided()) }))
        assertEquals(listOf("/evidence_ids/0"), pointers(problem { h.report(ctx, w, UUID.randomUUID(), extra = guided()) }))
        assertEquals(listOf("/evidence_ids/0"), pointers(problem { h.report(w.ctx("workshop"), w, open.id, extra = guided()) }))      // Ticket eines anderen Urhebers
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.defect_case WHERE tenant_id=?", w.tenant.value))
        // vollständiger Musterfall (Art, Bereich, Auswirkung, ein Foto) gelingt
        h.putQuarantine(w, open, bytes); h.complete(ctx, open)
        val d = h.report(ctx, w, open.id, extra = guided()).defect
        assertEquals(1, d.evidence.size); assertEquals("DENT", d.kind)
    }

    @Test
    fun fremder_mandant_und_abgelaufener_link_scheitern() {
        val a = DefectWorld(fx); val b = DefectWorld(fx); val ca = a.ctx("dispatcher"); val cb = b.ctx("dispatcher")
        val bytes = h.jpeg(30); val t = h.ticket(ca, bytes); h.putQuarantine(a, t, bytes); h.complete(ca, t)
        val d = h.report(ca, a, t.id).defect
        val link = h.fleet.defectEvidence.downloadLink(ca, d.id, t.id)
        // Mandant B: weder Ticket noch Akte noch Foto noch der Link von A
        assertEquals(ErrorCode.NOT_FOUND, problem { h.fleet.defectEvidence.get(cb, t.id) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { h.fleet.defectEvidence.downloadLink(cb, d.id, t.id) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { h.fleet.defectEvidence.openLinked(cb, link.url) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { h.fleet.defectEvidence.complete(cb, t.id, 1) }.code)
        // Fahrer ohne Eigentum an der Akte: kein Link
        assertEquals(ErrorCode.NOT_FOUND, problem { h.fleet.defectEvidence.downloadLink(a.ctx("driver"), d.id, t.id) }.code)
        // gültig innerhalb von 60 s, danach 404; manipulierte Signatur und fremder Zweck ebenfalls 404
        assertTrue(h.fleet.defectEvidence.openLinked(ca, link.url).bytes.isNotEmpty())
        assertEquals(ErrorCode.NOT_FOUND, problem { h.fleet.defectEvidence.openLinked(ca, link.url.replace(Regex("sig=.."), "sig=00")) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { h.fleet.defectEvidence.openLinked(ca, link.url.replace("purpose=defect_evidence", "purpose=receipt")) }.code)
        h.clock.advance(61)
        assertEquals(ErrorCode.NOT_FOUND, problem { h.fleet.defectEvidence.openLinked(ca, link.url) }.code)
        // abgelaufenes Ticket kann nicht mehr abgeschlossen werden
        val late = h.jpeg(31); val tl = h.ticket(ca, late); h.putQuarantine(a, tl, late)
        h.clock.advance(16 * 60)
        assertEquals(listOf("/content"), pointers(problem { h.complete(ca, tl) })); assertEquals("EXPIRED", h.fleet.defectEvidence.get(ca, tl.id).rejectReason)
    }

    @Test
    fun nachtraeglich_veraendertes_objekt_wird_von_der_integritaetspruefung_erkannt() {
        val w = DefectWorld(fx); val ctx = w.ctx("dispatcher")
        val ids = (40..42).map { s -> val b = h.jpeg(s); val t = h.ticket(ctx, b); h.putQuarantine(w, t, b); h.complete(ctx, t); t.id }
        h.report(ctx, w, *ids.toTypedArray())
        assertTrue(h.fleet.defectEvidence.verifyIntegrity(w.tenant).ok)
        assertEquals(3, h.fleet.defectEvidence.verifyIntegrity(w.tenant).checked)
        val refs = ids.map { fx.strings("SELECT media_ref FROM taktovia.defect_evidence WHERE id=?", it).single() }
        // Objekt im Speicher verändert bzw. entfernt
        h.store.put(ObjectKey(w.tenant, refs[0]), h.jpeg(99), "image/jpeg")
        h.store.delete(ObjectKey(w.tenant, refs[1]))
        val r = h.fleet.defectEvidence.verifyIntegrity(w.tenant)
        assertFalse(r.ok); assertEquals(listOf(ids[0]), r.mismatched); assertEquals(listOf(ids[1]), r.missing)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='security.defect_evidence.integrity_violation' AND object_ref=?", w.tenant.value, "defect_evidence:${ids[0]}"))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='security.defect_evidence.integrity_violation' AND object_ref=?", w.tenant.value, "defect_evidence:${ids[1]}"))
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND object_ref=? AND action='security.defect_evidence.integrity_violation'", w.tenant.value, "defect_evidence:${ids[2]}"))
        // die Evidenzzeilen selbst bleiben unverändert (unveränderlich)
        assertEquals(3, fx.count("SELECT count(*) FROM taktovia.defect_evidence WHERE defect_id=?", fx.strings("SELECT defect_id::text FROM taktovia.defect_evidence WHERE id=?", ids[0]).single().let(UUID::fromString)))
    }
}
