package de.taktovia.platform.api

import de.taktovia.platform.problem.DbErrorMapper
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.Problem
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.ViolationCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

/** Bausteine des API-Grundgerüsts (WP-M1-02) ohne HTTP: Cursor, Traceparent, ETag, Quote, Fehlerkatalog. */
class ApiBuildingBlocksTest {
    private val codec = CursorCodec("0123456789abcdef0123456789abcdef".toByteArray())
    private val scope = CursorScope("tenant-a", "actor-1", "passengers")
    private val pos = CursorPosition(Instant.parse("2026-11-11T10:15:30.123456Z"), UUID.fromString("00000000-0000-4000-8000-000000000001"))

    @Test
    fun cursor_roundtrip_und_manipulation() {
        val token = codec.encode(pos, scope)
        assertEquals(pos, codec.decode(token, scope))
        fun bad(t: String, s: CursorScope = scope) = assertEquals(ErrorCode.INVALID_CURSOR, assertThrows<ProblemException> { codec.decode(t, s) }.code)
        bad(token.dropLast(2) + "xx")                         // Signatur verändert
        bad(token.replaceFirst("v1.", "v2."))                 // Version
        bad("v1.${token.split('.')[1]}")                      // Signatur fehlt
        bad("garbage")
        bad("v1.AAAA.AAAA")                                   // Signatur falsch
        bad(token, scope.copy(tenant = "tenant-b"))           // Cursor eines anderen Mandanten
        bad(token, scope.copy(actor = "actor-2"))             // Cursor eines anderen Akteurs
        bad(token, scope.copy(resource = "vehicles"))         // Cursor einer anderen Liste
        bad("v1." + "A".repeat(600))                          // Länge begrenzt
        // andere Schlüsselinstanz kann nicht fälschen
        val other = CursorCodec("ffffffffffffffffffffffffffffffff".toByteArray())
        assertEquals(ErrorCode.INVALID_CURSOR, assertThrows<ProblemException> { other.decode(token, scope) }.code)
    }

    @Test
    fun limit_1_bis_100_standard_50() {
        assertEquals(50, Paging.limit(null))
        assertEquals(1, Paging.limit("1"))
        assertEquals(100, Paging.limit("100"))
        listOf("0", "101", "-1", "abc", "").forEach {
            val e = assertThrows<ProblemException> { Paging.limit(it) }
            assertEquals(ErrorCode.VALIDATION_FAILED, e.code, it)
            assertEquals("/\$query/limit", e.violations.single().pointer)
        }
    }

    @Test
    fun seitenbau_setzt_next_cursor_nur_bei_folgeseite() {
        val rows = (1..6).map { CursorPosition(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(it.toLong()), UUID(0, it.toLong())) }
        val p = Paging.page(rows, 5, { it }, codec, scope, Instant.EPOCH)
        assertEquals(5, p.items.size)
        assertEquals(rows[4], codec.decode(p.nextCursor!!, scope))
        assertNull(Paging.page(rows.take(5), 5, { it }, codec, scope, Instant.EPOCH).nextCursor)
    }

    @Test
    fun traceparent_gueltig_und_ungueltig() {
        val ok = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
        assertEquals(UUID.fromString("4bf92f35-77b3-4da6-a3ce-929d0e0e4736"), Traceparent.correlationId(ok))
        listOf(null, "", "x", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7", "ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
            "00-00000000000000000000000000000000-00f067aa0ba902b7-01", "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01",
            "00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01").forEach {
            assertNull(Traceparent.parse(it), it)
            assertNotEquals(Traceparent.correlationId(it), Traceparent.correlationId(it), "neue Correlation-ID je Anfrage")
        }
    }

    @Test
    fun etag_format_und_if_match() {
        assertEquals("\"7\"", Etag.format(7))
        assertEquals(7L, Etag.parseIfMatch("\"7\""))
        assertEquals(ErrorCode.PRECONDITION_REQUIRED, assertThrows<ProblemException> { Etag.parseIfMatch(null) }.code)
        listOf("7", "W/\"7\"", "*", "\"-1\"", "\"a\"", "\"\"").forEach {
            assertEquals(ViolationCode.INVALID_FORMAT, assertThrows<ProblemException> { Etag.parseIfMatch(it) }.violations.single().code, it)
        }
        Etag.requireUpdated(1) { true }
        assertEquals(ErrorCode.REVISION_MISMATCH, assertThrows<ProblemException> { Etag.requireUpdated(0) { true } }.code)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { Etag.requireUpdated(0) { false } }.code)
    }

    @Test
    fun quote_je_mandant_und_akteur_getrennt_mit_retry_after() {
        var now = 1_000_000L
        val limiter = RateLimiter(InMemoryRateLimitStore { now }, Quota(3, 60))
        repeat(3) { assertTrue(limiter.check("t1", "a1").allowed) }
        val denied = limiter.check("t1", "a1")
        assertFalse(denied.allowed); assertTrue(denied.retryAfterSeconds in 1..60)
        assertTrue(limiter.check("t1", "a2").allowed, "anderer Akteur")
        assertTrue(limiter.check("t2", "a1").allowed, "anderer Mandant")
        now += 61_000
        assertTrue(limiter.check("t1", "a1").allowed, "neues Fenster")
        // Schlüsselbildung ist mehrdeutigkeitsfrei: ("t", "1:a") ist nicht ("t1", "a")
        val l2 = RateLimiter(InMemoryRateLimitStore { now }, Quota(1, 60))
        assertTrue(l2.check("t", "1:a").allowed); assertTrue(l2.check("t1", "a").allowed)
    }

    @Test
    fun db_fehler_werden_ohne_datenbankmeldung_abgebildet() {
        fun map(state: String) = DbErrorMapper.map(org.springframework.dao.DataIntegrityViolationException("x", SQLException("SECRET tabelle audit_event spalte tenant_id", state)))
        assertEquals(ErrorCode.IMMUTABLE_RECORD, map("23514").code)
        assertEquals(ErrorCode.CONFLICT, map("23505").code)
        assertEquals(ErrorCode.VALIDATION_FAILED, map("23503").code)
        assertEquals(ErrorCode.FORBIDDEN, map("42501").code)
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, map("08006").code)
        assertEquals(ErrorCode.INTERNAL_ERROR, map("XX000").code)
        assertEquals(ErrorCode.INTERNAL_ERROR, DbErrorMapper.map(RuntimeException("boom")).code)
        listOf("23514", "23505", "23503", "42501", "08006", "XX000").forEach {
            val m = map(it)
            assertFalse((m.detail ?: "").contains("SECRET")); assertFalse(m.message.orEmpty().contains("SECRET"))
        }
    }

    @Test
    fun problem_hat_stabile_typ_uri_und_gekuerzte_felder() {
        val p = Problem(ErrorCode.REVISION_MISMATCH, UUID(0, 1), "d".repeat(3000)).toMap()
        assertEquals("https://api.taktovia.example/problems/revision-mismatch", p["type"])
        assertEquals(412, p["status"]); assertEquals(2000, (p["detail"] as String).length)
        assertEquals(ErrorCode.entries.size, ErrorCode.entries.map { it.type }.toSet().size, "type je Code eindeutig")
    }
}
