package de.taktovia.config.api

import de.taktovia.config.internal.ConfigServicesFactory
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import org.springframework.jdbc.core.JdbcOperations
import java.time.Clock
import java.util.UUID

/** Öffentliche Ports des Moduls `config`; wächst mit den Arbeitspaketen (bisher: Unternehmensprofil, WP-M1-24). */
interface ConfigApi {
    /** Unternehmensprofil des Mandanten (Kontakt, Abrechnung, Betriebsart, Rechtsinformationen, Standardwährung). */
    val companyProfile: CompanyProfileService

    companion object {
        fun create(ctxTx: ContextTransactions, audit: AuditPort, outbox: Outbox, clock: Clock = Clock.systemUTC()): ConfigApi =
            ConfigServicesFactory.create(ctxTx, audit, outbox, clock)
    }
}

/**
 * Felder des Unternehmensprofils (Planungsannahme PA-11, OP-27): Pflichtfelder sind [legalName], [contactEmail] und [defaultCurrency];
 * die Währung muss im Katalog `currency` stehen. [operatingMode] (Betriebsart) ist ein Code im Muster `^[A-Z][A-Z0-9_]{1,63}$`; ein
 * fester Katalog ist nicht festgelegt (OP-27). Abrechnungsangaben sind nur Adresse und E-Mail; Kontenrahmen und Rechnungsstellung gehören nach M4 (D-06).
 */
data class CompanyProfileInput(
    val legalName: String, val contactEmail: String, val defaultCurrency: String,
    val address: String? = null, val contactPhone: String? = null, val billingAddress: String? = null, val billingEmail: String? = null,
    val operatingMode: String? = null, val legalNotice: String? = null,
)

data class CompanyProfileView(val input: CompanyProfileInput, val revision: Long)

interface CompanyProfileService {
    /** Aktuelles Profil des Mandanten im Kontext; 404, wenn noch keines angelegt ist. */
    fun get(ctx: RequestContext): CompanyProfileView

    /**
     * Ändert das Profil (vollständiger Ersatz) mit der gelesenen Revision (`If-Match`): 412 bei veralteter Revision (auch bei zwei gleichzeitigen
     * Änderungen genau eine Gewinnerin), 404 bei unbekannter Revision ohne Profil, 422 mit Feldfehler bei ungültiger Währung. Ist `expectedRevision` 0
     * und noch kein Profil vorhanden, entsteht Revision 1. Je Änderung genau ein Auditeintrag und ein Ereignis `tenant.profile_changed` in derselben
     * Transaktion; eine Änderung ohne Wirkung erzeugt weder Revision noch Audit noch Ereignis. Das Profil hängt nur am Mandanten des Kontexts: ohne
     * Mitwirkung einer Plattformbetreiberrolle.
     */
    fun update(ctx: RequestContext, expectedRevision: Long, input: CompanyProfileInput): CompanyProfileView

    /** Erste Profilrevision in der laufenden Transaktion des Aufrufers (Erstausstattung bei der Mandantenanlage, WP-M1-05); Mandant = Tenantkontext der Transaktion. */
    fun seed(jdbc: JdbcOperations, actor: ActorRef, correlationId: UUID, input: CompanyProfileInput): CompanyProfileView
}
