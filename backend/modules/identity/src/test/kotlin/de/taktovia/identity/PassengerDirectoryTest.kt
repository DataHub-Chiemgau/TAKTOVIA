package de.taktovia.identity

import de.taktovia.identity.api.ContactChannel
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

/** WP-M2-32: Verzeichnis für den Nachrichtenversand: nur verifizierte Kontakte, Sprache des Kontos, Mandantentrennung. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PassengerDirectoryTest {
    private lateinit var f: PassengerTestFixture
    @BeforeAll fun setup() { f = PassengerTestFixture() }
    @AfterAll fun teardown() { if (::f.isInitialized) f.close() }

    @Test fun nur_verifizierter_kontakt_wird_ausgegeben_und_mandanten_sind_getrennt() {
        val t = f.tenant(); val other = f.tenant()
        val subject = UUID.randomUUID().toString(); val email = "versand.${UUID.randomUUID().toString().take(8)}@example.org"
        val reg = f.register(t, subject, ContactChannel.EMAIL, email, "en-GB")
        val id = reg.passenger.id
        assertNull(f.api.directory.verifiedContact(t, id, ContactChannel.EMAIL), "unverifizierter Kanal: kein Kontakt")
        assertEquals("en-GB", f.api.directory.locale(t, id))
        f.api.accounts.confirmVerification(f.ctx(t, subject), ContactChannel.EMAIL, f.sender.last(ContactChannel.EMAIL, email)!!)
        assertEquals(email, f.api.directory.verifiedContact(t, id, ContactChannel.EMAIL))
        assertNull(f.api.directory.verifiedContact(t, id, ContactChannel.PHONE), "kein Telefonkanal hinterlegt")
        assertNull(f.api.directory.verifiedContact(other, id, ContactChannel.EMAIL), "fremder Mandant")
        assertNull(f.api.directory.locale(other, id))
    }
}
