package de.taktovia.identity

import de.taktovia.identity.api.ContactChannel
import de.taktovia.identity.api.FavoriteInput
import de.taktovia.identity.api.ProfilePatch
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

/**
 * WP-M1-29 / LH-6.2-IAM-006: Fahrgastprofil und Präferenzen gegen die echte Datenbank. „Erneute Anmeldung“ und „zweites Gerät“ sind hier neue
 * Kontexte desselben Kontos (die Sitzung trägt keinen Profilzustand: alles kommt aus der Datenbank); die HTTP-Sicht prüft `PassengerApiTest` in `app`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PassengerProfileTest {
    private lateinit var f: PassengerTestFixture
    @BeforeAll fun setup() { f = PassengerTestFixture() }
    @AfterAll fun teardown() { if (::f.isInitialized) f.close() }

    private fun problem(block: () -> Unit): ProblemException = assertThrows { block() }

    /** Fixture-Haltepunkte (Tabelle stop_revision aus dem Kernschema; gepflegter Stamm erst mit M2). */
    private fun stop(t: TenantId): UUID {
        val stop = UUID.randomUUID()
        f.base.adminExec("INSERT INTO taktovia.stop_revision(tenant_id,id,stop_id,revision,point,name,accessibility,approved_by,valid_from) VALUES (?::uuid,?::uuid,?::uuid,1,ST_SetSRID(ST_MakePoint(9.0,48.0),4326),'Fixture','{}'::jsonb,'test',now())", t.toString(), UUID.randomUUID().toString(), stop.toString())
        return stop
    }

    @Test fun aenderungen_nach_neuanmeldung_konsistent() {
        val t = f.tenant(); val (s, ctx) = f.verifiedPassenger(t)
        val s1 = stop(t); val s2 = stop(t); val s3 = stop(t)
        val rev = f.api.profiles.get(ctx).revision
        val email = PassengerTestFixture.defaultValue(ContactChannel.EMAIL); val phone = "+4915${(10000000..99999999).random()}"
        val changed = f.api.profiles.update(ctx, rev, ProfilePatch(
            locale = "en-GB", email = email, phone = phone, favorites = listOf(FavoriteInput("Zuhause", s1), FavoriteInput("Arbeit", s2)),
            preferredStops = listOf(s3, s1), notificationChannels = listOf("PUSH", "SMS")))
        assertTrue(changed.revision > rev)
        // Abmeldung und erneute Anmeldung / zweites Gerät: neuer Kontext desselben Kontos liest denselben Stand
        for (again in listOf(f.ctx(t, s), f.ctx(t, s))) {
            val p = f.api.profiles.get(again)
            assertEquals("en-GB", p.locale)
            assertEquals(email, p.email!!.value); assertEquals(phone, p.phone!!.value)
            assertEquals(listOf("Zuhause" to s1, "Arbeit" to s2), p.favorites.map { it.label to it.stopId })
            assertEquals(listOf(s3, s1), p.preferredStops)
            assertEquals(listOf("PUSH", "SMS"), p.notificationChannels)
            assertEquals(changed.revision, p.revision)
        }
        // einzelne Bereiche ändern, die übrigen bleiben; Listen ersetzen den Bereich
        val p2 = f.api.profiles.update(ctx, changed.revision, ProfilePatch(notificationChannels = listOf("EMAIL")))
        assertEquals(listOf("EMAIL"), p2.notificationChannels); assertEquals(listOf(s3, s1), p2.preferredStops); assertEquals("en-GB", p2.locale)
        val p3 = f.api.profiles.update(ctx, p2.revision, ProfilePatch(favorites = emptyList()))
        assertTrue(p3.favorites.isEmpty())
        // Unterstützungsbedarf: nur Katalog, verschlüsselt gespeichert, erscheint nicht im Profil
        f.api.profiles.replaceSupportNeeds(ctx, listOf("WHEELCHAIR_SPACE", "BOARDING_HELP"))
        assertEquals(listOf("BOARDING_HELP", "WHEELCHAIR_SPACE"), f.api.profiles.readSupportNeeds(f.ctx(t, s)))
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { f.api.profiles.replaceSupportNeeds(ctx, listOf("Rollstuhlfahrer mit Diagnose X")) }.code)
        assertTrue(f.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ?::uuid AND action = 'identity.passenger.profile_changed'", t.toString()) >= 3)
    }

    @Test fun fremdes_profil_ergibt_404() {
        val t = f.tenant(); val (_, ctxA) = f.verifiedPassenger(t)
        val other = f.tenant(); val (_, ctxB) = f.verifiedPassenger(other)
        // Das Profil gehört zum Konto des Akteurs: Konten ohne Fahrgastkonto (z. B. Betreiber, anderer Mandant) finden nichts
        assertEquals(ctxA.tenant, t)
        val strangerSub = UUID.randomUUID().toString()
        val stranger = f.base.context(t, strangerSub, setOf(f.base.seedMembership(t, strangerSub, "passenger")), setOf("passenger"))
        assertEquals(ErrorCode.NOT_FOUND, problem { f.api.profiles.get(stranger) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { f.api.profiles.update(stranger, 1, ProfilePatch(locale = "en-GB")) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { f.api.profiles.readSupportNeeds(stranger) }.code)
        // Fahrgast aus Mandant B im Kontext von Mandant A: weder Konto noch Mitgliedschaft
        val crossed = f.base.context(t, ctxB.actor.subject, ctxB.membershipIds, setOf("passenger"))
        assertThrows<Exception> { f.api.profiles.get(crossed) }
        assertNull(f.api.accounts.own(stranger), "Mitglied ohne Fahrgastkonto hat kein Konto")
        // Betreiberliste: nur Konten des eigenen Mandanten
        val operator = f.base.context(t, "op", setOf(f.base.seedMembership(t, "op", "callcenter")), setOf("callcenter"))
        val ids = f.api.accounts.list(operator, 50, null).map { it.id }
        assertTrue(f.api.accounts.own(ctxA)!!.id in ids); assertFalse(f.api.accounts.own(ctxB)!!.id in ids)
    }

    @Test fun ungueltige_sprache_und_unbekannte_haltestelle_abgewiesen() {
        val t = f.tenant(); val (_, ctx) = f.verifiedPassenger(t); val known = stop(t); val foreign = stop(f.tenant())
        val rev = f.api.profiles.get(ctx).revision
        val v = problem { f.api.profiles.update(ctx, rev, ProfilePatch(locale = "xx-XX")) }
        assertEquals(ErrorCode.VALIDATION_FAILED, v.code); assertEquals("/locale", v.violations.single().pointer)
        val unknown = problem { f.api.profiles.update(ctx, rev, ProfilePatch(preferredStops = listOf(known, UUID.randomUUID()))) }
        assertEquals("/preferred_stops/1", unknown.violations.single().pointer)
        assertEquals("/favorites/0/stop_id", problem { f.api.profiles.update(ctx, rev, ProfilePatch(favorites = listOf(FavoriteInput("X", foreign)))) }.violations.single().pointer, "Haltestelle eines anderen Mandanten ist unbekannt")
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { f.api.profiles.update(ctx, rev, ProfilePatch(notificationChannels = listOf("FAX"))) }.code)
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { f.api.profiles.update(ctx, rev, ProfilePatch(email = "kein-kontakt")) }.code)
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { f.api.profiles.update(ctx, rev, ProfilePatch(preferredStops = listOf(known, known))) }.code)
        // nichts davon hat etwas verändert
        assertEquals(rev, f.api.profiles.get(ctx).revision); assertTrue(f.api.profiles.get(ctx).preferredStops.isEmpty())
        // ein Kontakt, den ein anderes Konto führt, kann nicht übernommen werden; der alte Kontakt bleibt unberührt
        val (_, other) = f.verifiedPassenger(t); val taken = f.api.profiles.get(other).email!!.value!!
        val mine = f.api.profiles.get(ctx).email!!.value
        assertEquals(ErrorCode.CONFLICT, problem { f.api.profiles.update(ctx, rev, ProfilePatch(email = taken)) }.code)
        assertEquals(mine, f.api.profiles.get(ctx).email!!.value)
    }

    @Test fun veralteter_etag_ergibt_412() {
        val t = f.tenant(); val (_, ctx) = f.verifiedPassenger(t)
        val rev = f.api.profiles.get(ctx).revision
        val ok = f.api.profiles.update(ctx, rev, ProfilePatch(locale = "en-GB"))
        assertEquals(ErrorCode.REVISION_MISMATCH, problem { f.api.profiles.update(ctx, rev, ProfilePatch(locale = "de-DE")) }.code)
        assertEquals("en-GB", f.api.profiles.get(ctx).locale, "stilles Überschreiben ausgeschlossen")
        assertEquals(ok.revision, f.api.profiles.get(ctx).revision)
    }

    @Test fun betreibersicht_ohne_feldrecht_ohne_kontakt() {
        val t = f.tenant(); val (_, ctx) = f.verifiedPassenger(t)
        f.api.profiles.replaceSupportNeeds(ctx, listOf("LOW_FLOOR"))
        val operator = f.base.context(t, "op2", setOf(f.base.seedMembership(t, "op2", "callcenter")), setOf("callcenter"))
        val view = f.api.accounts.list(operator, 50, null).first { it.id == f.api.accounts.own(ctx)!!.id }
        // minimierte Felder: das Typsystem der Betreibersicht kennt weder Kontakt noch Unterstützungsmerkmale
        assertEquals(setOf("id", "status", "locale", "createdAt", "revision"), view::class.java.declaredFields.filter { !it.isSynthetic }.map { it.name }.toSet())
        // Kontakt und Unterstützungsmerkmal sind auch auf der Datenbankebene für die Runtime-Rolle gesperrt
        for (col in listOf("contact_ciphertext", "support_ciphertext")) {
            assertThrows<Exception> { f.base.tx.run(t) { j -> j.queryForList("SELECT $col FROM taktovia.passenger_private") } }
        }
    }

    @Test fun parallele_aenderung_zwei_sitzungen_412() {
        val t = f.tenant(); val (s, ctx) = f.verifiedPassenger(t)
        val rev = f.api.profiles.get(ctx).revision
        val n = 4; val barrier = CyclicBarrier(n); val pool = Executors.newFixedThreadPool(n)
        try {
            val results = (1..n).map { i -> pool.submit(Callable { barrier.await(); runCatching { f.api.profiles.update(f.ctx(t, s), rev, ProfilePatch(locale = if (i % 2 == 0) "en-GB" else "de-DE", notificationChannels = listOf(if (i % 2 == 0) "PUSH" else "EMAIL"))) } }) }.map { it.get() }
            assertEquals(1, results.count { it.isSuccess }, "genau eine der gleichzeitigen Änderungen mit derselben Revision gelingt")
            results.filter { it.isFailure }.forEach { assertEquals(ErrorCode.REVISION_MISMATCH, (it.exceptionOrNull() as ProblemException).code) }
            val winner = results.single { it.isSuccess }.getOrThrow()
            val now = f.api.profiles.get(ctx)
            assertEquals(winner.locale, now.locale); assertEquals(winner.notificationChannels, now.notificationChannels)
            assertEquals(1, now.notificationChannels.size, "kein Mischstand beider Sitzungen")
        } finally { pool.shutdownNow() }
    }
}
