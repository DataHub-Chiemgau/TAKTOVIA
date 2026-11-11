package de.taktovia.communicationsupport

import de.taktovia.communicationsupport.api.CreateTemplateRevision
import de.taktovia.communicationsupport.api.NotificationChannel
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

/** WP-M2-33, Datentrennung: Vorlagen eines fremden Mandanten sind über die Modul-API nicht lesbar (404); die Pflege ist in P1 Seed und Dienstoperation (OP 29), keine HTTP-Schnittstelle. */
class TemplateAccessTest {
    private companion object {
        val f = CommFixture()
        @JvmStatic @AfterAll fun close() = f.close()
    }

    @Test
    fun fremde_vorlage_ergibt_404() {
        val a = f.world(0); val b = f.world(0)
        val mine = f.api.templates.create(f.context(a.tenant), CreateTemplateRevision("booking.confirmed", "de", null, NotificationChannel.SMS, "Ref {{booking_reference}}"))
        assertEquals(mine.id, f.api.templates.get(f.context(a.tenant), mine.id).id)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(ProblemException::class.java) { f.api.templates.get(f.context(b.tenant), mine.id) }.code, "fremder Mandant")
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(ProblemException::class.java) { f.api.templates.get(f.context(a.tenant), UUID.randomUUID()) }.code, "unbekannte ID")
        assertEquals(0, f.api.templates.list(f.context(b.tenant), null).size)
        assertEquals(1, f.api.templates.list(f.context(a.tenant), "booking.confirmed").size)
    }
}
