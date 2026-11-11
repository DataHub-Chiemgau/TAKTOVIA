package de.taktovia.identity

import de.taktovia.identity.api.ConsentPurpose
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

/**
 * WP-M1-30: Verhalten der Datenschutzeinstellungen auf Dienstebene (die HTTP-Abbildung 201/404/422 prüft `PassengerApiTest` in `app`):
 * jede Änderung legt eine neue Revision an, fremde Einwilligungen sind unerreichbar (404), nicht änderbare oder unbekannte Zwecke ergeben 422.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsentApiTest {
    private lateinit var f: PassengerTestFixture
    @BeforeAll fun setup() { f = PassengerTestFixture() }
    @AfterAll fun teardown() { if (::f.isInitialized) f.close() }
    private val v get() = f.settings.consentTextVersion

    @Test fun aenderung_legt_neue_revision_an_fremde_einwilligung_404_unbekannter_zweck_422() {
        val t = f.tenant(); val (_, ctx) = f.verifiedPassenger(t)
        val a = f.api.consents.record(ctx, ConsentPurpose.MARKETING, true, v)
        val b = f.api.consents.record(ctx, ConsentPurpose.MARKETING, false, v)
        assertEquals(listOf(1L, 2L), listOf(a.sequence, b.sequence)); assertTrue(a.id != b.id)
        assertEquals(2, f.count("SELECT count(*) FROM taktovia.consent_revision WHERE passenger_id = ?::uuid", f.api.accounts.own(ctx)!!.id.toString()), "frühere Revision bleibt bestehen")
        // fremde Einwilligung: Konto ohne Fahrgastkonto im Mandanten (anderer Mandant, Betreiber) hat keine
        val other = f.tenant(); val (_, otherCtx) = f.verifiedPassenger(other)
        val crossed = f.base.context(t, otherCtx.actor.subject, setOf(f.base.seedMembership(t, otherCtx.actor.subject, "passenger")), setOf("passenger"))
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(ProblemException::class.java) { f.api.consents.record(crossed, ConsentPurpose.MARKETING, true, v) }.code)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(ProblemException::class.java) { f.api.consents.settings(crossed) }.code)
        assertEquals(0, f.count("SELECT count(*) FROM taktovia.consent_revision WHERE tenant_id = ?::uuid", other.toString()))
        // betrieblich/sicherheitsrelevant nicht abwählbar, veraltete Textversion abgewiesen
        val e = assertThrows(ProblemException::class.java) { f.api.consents.record(ctx, ConsentPurpose.OPERATIONAL_SAFETY, false, v) }
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code); assertEquals("/purpose", e.violations.single().pointer)
        assertEquals("/text_version", assertThrows(ProblemException::class.java) { f.api.consents.record(ctx, ConsentPurpose.MARKETING, true, "alt-1") }.violations.single().pointer)
        assertEquals(2, f.count("SELECT count(*) FROM taktovia.consent_revision WHERE passenger_id = ?::uuid", f.api.accounts.own(ctx)!!.id.toString()))
        // Revisionen sind unveränderlich, auch über die Fachrolle nicht änderbar
        assertThrows(Exception::class.java) { f.base.tx.run(t) { j -> j.update("UPDATE taktovia.consent_revision SET granted = true WHERE id = ?", b.id) } }
        assertThrows(Exception::class.java) { f.base.tx.run(t) { j -> j.update("DELETE FROM taktovia.consent_revision WHERE id = ?", a.id) } }
        assertTrue(UUID.randomUUID() != a.id)
    }
}
