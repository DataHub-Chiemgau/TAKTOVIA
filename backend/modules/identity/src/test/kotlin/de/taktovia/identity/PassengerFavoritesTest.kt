package de.taktovia.identity

import de.taktovia.identity.api.FavoriteInput
import de.taktovia.identity.api.ProfilePatch
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
 * WP-M2-36 (UI-05 Konto: Favoriten mit validierter Koordinate und optionalem Haltepunktbezug) gegen die echte Datenbank unter der Runtime-Rolle:
 * Anlegen, Umbenennen, Löschen, Sprache, Koordinatenvalidierung, Haltepunktreferenz (unbekannt/fremd/inaktiv), veralteter Stand (412), Datentrennung
 * (zwei Mandanten, gleiche Subject-ID) und Nebenläufigkeit (Deaktivierung des Haltepunkts parallel zur Favoritenänderung).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PassengerFavoritesTest {
    private lateinit var f: PassengerTestFixture
    @BeforeAll fun setup() { f = PassengerTestFixture() }
    @AfterAll fun teardown() { if (::f.isInitialized) f.close() }

    private fun problem(block: () -> Unit): ProblemException = assertThrows { block() }

    private fun stop(t: TenantId, lon: Double = 9.0, lat: Double = 48.0): UUID {
        val stop = UUID.randomUUID()
        f.base.adminExec("INSERT INTO taktovia.stop_revision(tenant_id,id,stop_id,revision,point,name,accessibility,approved_by,valid_from) VALUES (?::uuid,?::uuid,?::uuid,1,ST_SetSRID(ST_MakePoint(?,?),4326),'Fixture','{}'::jsonb,'test',now() - interval '1 hour')",
            t.toString(), UUID.randomUUID().toString(), stop.toString(), lon, lat)
        return stop
    }

    /** Deaktivierung ist eine neue Revision (active = false); die offene Revision wird geschlossen. */
    private fun deactivate(t: TenantId, stop: UUID) {
        f.base.adminExec("UPDATE taktovia.stop_revision SET valid_to = now() - interval '1 second' WHERE tenant_id = ?::uuid AND stop_id = ?::uuid AND revision = 1", t.toString(), stop.toString())
        f.base.adminExec("INSERT INTO taktovia.stop_revision(tenant_id,id,stop_id,revision,point,name,accessibility,approved_by,valid_from,active) SELECT tenant_id,?::uuid,stop_id,2,point,name,accessibility,approved_by,now() - interval '1 second',false FROM taktovia.stop_revision WHERE tenant_id = ?::uuid AND stop_id = ?::uuid AND revision = 1",
            UUID.randomUUID().toString(), t.toString(), stop.toString())
    }

    @Test fun favorit_anlegen_umbenennen_loeschen_mit_koordinate() {
        val t = f.tenant(); val (s, ctx) = f.verifiedPassenger(t); val st = stop(t, 9.18, 48.78)
        val rev = f.api.profiles.get(ctx).revision
        val p1 = f.api.profiles.update(ctx, rev, ProfilePatch(favorites = listOf(FavoriteInput("Zuhause", lat = 48.775, lon = 9.182), FavoriteInput("Haltestelle", stopId = st))))
        val home = p1.favorites[0]; val byStop = p1.favorites[1]
        assertEquals(48.775, home.lat, 1e-9); assertEquals(9.182, home.lon, 1e-9); assertEquals(null, home.stopId); assertFalse(home.stopBookable)
        assertEquals(48.78, byStop.lat, 1e-9, "Koordinate aus der Haltepunktrevision abgeleitet"); assertEquals(9.18, byStop.lon, 1e-9)
        assertEquals(st, byStop.stopId); assertTrue(byStop.stopBookable)
        // umbenennen: Liste ersetzen; stabil nach neuem Lesen aus anderem Kontext
        val p2 = f.api.profiles.update(ctx, p1.revision, ProfilePatch(favorites = listOf(FavoriteInput("Daheim", lat = 48.775, lon = 9.182), FavoriteInput("Haltestelle", stopId = st))))
        assertEquals(listOf("Daheim", "Haltestelle"), f.api.profiles.get(f.ctx(t, s)).favorites.map { it.label })
        // löschen
        val p3 = f.api.profiles.update(ctx, p2.revision, ProfilePatch(favorites = listOf(FavoriteInput("Haltestelle", stopId = st))))
        assertEquals(listOf("Haltestelle"), p3.favorites.map { it.label })
        assertTrue(f.api.profiles.update(ctx, p3.revision, ProfilePatch(favorites = emptyList())).favorites.isEmpty())
    }

    @Test fun sprachwechsel_wird_gespeichert() {
        val t = f.tenant(); val (s, ctx) = f.verifiedPassenger(t)
        val p = f.api.profiles.update(ctx, f.api.profiles.get(ctx).revision, ProfilePatch(locale = "en-GB"))
        assertEquals("en-GB", p.locale); assertEquals("en-GB", f.api.profiles.get(f.ctx(t, s)).locale)
        assertEquals("/locale", problem { f.api.profiles.update(ctx, p.revision, ProfilePatch(locale = "fr-FR")) }.violations.single().pointer)
    }

    @Test fun ungueltige_koordinate_wird_abgewiesen() {
        val t = f.tenant(); val (_, ctx) = f.verifiedPassenger(t); val rev = f.api.profiles.get(ctx).revision
        fun bad(fav: FavoriteInput, pointer: String) { assertEquals(pointer, problem { f.api.profiles.update(ctx, rev, ProfilePatch(favorites = listOf(fav))) }.violations.single().pointer) }
        bad(FavoriteInput("A", lat = 91.0, lon = 9.0), "/favorites/0/lat")
        bad(FavoriteInput("A", lat = -90.5, lon = 9.0), "/favorites/0/lat")
        bad(FavoriteInput("A", lat = 48.0, lon = 180.5), "/favorites/0/lon")
        bad(FavoriteInput("A", lat = Double.NaN, lon = 9.0), "/favorites/0/lat")
        bad(FavoriteInput("A", lat = 0.0, lon = 0.0), "/favorites/0/lat")        // nie 0/0
        bad(FavoriteInput("A", lat = 48.0), "/favorites/0/lon")                   // nur eine Hälfte
        bad(FavoriteInput("A"), "/favorites/0/lat")                               // weder Koordinate noch Haltepunkt
        bad(FavoriteInput(" ", lat = 48.0, lon = 9.0), "/favorites/0/label")
        assertEquals(rev, f.api.profiles.get(ctx).revision, "nichts wurde verändert")
    }

    @Test fun unbekannte_und_fremde_haltepunktreferenz_wird_abgewiesen() {
        val t = f.tenant(); val (_, ctx) = f.verifiedPassenger(t); val own = stop(t); val foreign = stop(f.tenant())
        val rev = f.api.profiles.get(ctx).revision
        for (bad in listOf(UUID.randomUUID(), foreign)) {
            val v = problem { f.api.profiles.update(ctx, rev, ProfilePatch(favorites = listOf(FavoriteInput("Ok", lat = 48.0, lon = 9.0), FavoriteInput("X", stopId = bad)))) }
            assertEquals(ErrorCode.VALIDATION_FAILED, v.code); assertEquals("/favorites/1/stop_id", v.violations.single().pointer)
        }
        assertTrue(f.api.profiles.update(ctx, rev, ProfilePatch(favorites = listOf(FavoriteInput("Eigene", stopId = own)))).favorites.single().stopBookable)
    }

    @Test fun favorit_mit_inaktivem_haltepunkt_bleibt_ort_aber_nicht_buchbar() {
        val t = f.tenant(); val (s, ctx) = f.verifiedPassenger(t); val st = stop(t, 9.2, 48.7)
        val p1 = f.api.profiles.update(ctx, f.api.profiles.get(ctx).revision, ProfilePatch(favorites = listOf(FavoriteInput("Halt", stopId = st))))
        assertTrue(p1.favorites.single().stopBookable)
        deactivate(t, st)
        val fav = f.api.profiles.get(f.ctx(t, s)).favorites.single()
        assertFalse(fav.stopBookable, "inaktiver Haltepunkt wird nicht als buchbar vorgeschlagen")
        assertEquals(48.7, fav.lat, 1e-9); assertEquals(9.2, fav.lon, 1e-9)
        assertEquals(st, fav.stopId, "die Referenz bleibt als Ort erhalten")
        // das Profil bleibt änderbar, ohne dass der Favorit mit inaktivem Haltepunkt abgewiesen wird
        val again = f.api.profiles.update(ctx, p1.revision, ProfilePatch(favorites = listOf(FavoriteInput("Halt neu", stopId = st, lat = 48.7, lon = 9.2))))
        assertEquals("Halt neu", again.favorites.single().label); assertFalse(again.favorites.single().stopBookable)
    }

    @Test fun veralteter_stand_ergibt_412() {
        val t = f.tenant(); val (_, ctx) = f.verifiedPassenger(t); val rev = f.api.profiles.get(ctx).revision
        f.api.profiles.update(ctx, rev, ProfilePatch(favorites = listOf(FavoriteInput("A", lat = 48.0, lon = 9.0))))
        val v = problem { f.api.profiles.update(ctx, rev, ProfilePatch(favorites = listOf(FavoriteInput("B", lat = 48.0, lon = 9.0)))) }
        assertEquals(ErrorCode.REVISION_MISMATCH, v.code)
        assertEquals("A", f.api.profiles.get(ctx).favorites.single().label)
    }

    @Test fun zwei_mandanten_gleiche_subject_id_bleiben_getrennt() {
        val t1 = f.tenant(); val t2 = f.tenant(); val subject = UUID.randomUUID().toString()
        f.register(t1, subject = subject); f.register(t2, subject = subject, value = PassengerTestFixture.defaultValue(de.taktovia.identity.api.ContactChannel.EMAIL))
        val c1 = f.ctx(t1, subject); val c2 = f.ctx(t2, subject)
        f.api.profiles.update(c1, f.api.profiles.get(c1).revision, ProfilePatch(favorites = listOf(FavoriteInput("Nur Mandant 1", lat = 48.0, lon = 9.0))))
        assertTrue(f.api.profiles.get(c2).favorites.isEmpty(), "Mandant 2 sieht die Favoriten von Mandant 1 nie")
        assertEquals(listOf("Nur Mandant 1"), f.api.profiles.get(c1).favorites.map { it.label })
    }

    @Test fun fremdes_konto_ergibt_404() {
        val t = f.tenant(); val (_, ctx) = f.verifiedPassenger(t)
        val sub = UUID.randomUUID().toString()
        val stranger = f.base.context(t, sub, setOf(f.base.seedMembership(t, sub, "passenger")), setOf("passenger"))
        assertEquals(ErrorCode.NOT_FOUND, problem { f.api.profiles.get(stranger) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { f.api.profiles.update(stranger, 1, ProfilePatch(favorites = emptyList())) }.code)
        assertTrue(f.api.profiles.get(ctx).favorites.isEmpty())
    }

    @Test fun konkurrierend_deaktivierung_des_haltepunkts_parallel_zur_favoritenaenderung() {
        val t = f.tenant(); val (s, ctx) = f.verifiedPassenger(t); val st = stop(t)
        val rev = f.api.profiles.get(ctx).revision
        val pool = Executors.newFixedThreadPool(2)
        try {
            val barrier = CyclicBarrier(2)
            val writer = pool.submit(Callable { barrier.await(); f.api.profiles.update(f.ctx(t, s), rev, ProfilePatch(favorites = listOf(FavoriteInput("Halt", stopId = st)))) })
            val deactivator = pool.submit(Callable { barrier.await(); deactivate(t, st) })
            deactivator.get(); writer.get()
        } finally { pool.shutdownNow() }
        // Ergebnis ist in beiden Reihenfolgen konsistent: Favorit vorhanden, Haltepunkt am Ende nicht buchbar, Koordinate erhalten
        val fav = f.api.profiles.get(ctx).favorites.single()
        assertEquals(st, fav.stopId); assertFalse(fav.stopBookable); assertEquals(48.0, fav.lat, 1e-9)
    }
}
