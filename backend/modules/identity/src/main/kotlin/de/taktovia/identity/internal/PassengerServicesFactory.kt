package de.taktovia.identity.internal

import de.taktovia.identity.api.ConsentService
import de.taktovia.identity.api.ContactKey
import de.taktovia.identity.api.DeliveryPolicy
import de.taktovia.identity.api.IdentityApi
import de.taktovia.identity.api.PassengerAccounts
import de.taktovia.identity.api.PassengerApi
import de.taktovia.identity.api.PassengerDirectory
import de.taktovia.identity.api.PassengerProfiles
import de.taktovia.identity.api.PassengerSettings
import de.taktovia.identity.api.RecoveryDispatch
import de.taktovia.identity.api.VerificationSender
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.fieldprotection.ProtectedColumnStore
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.tenant.TenantTransactions
import java.time.Clock

internal object PassengerServicesFactory {
    fun create(
        identity: IdentityApi, tx: TenantTransactions, fieldTx: TenantTransactions, store: ProtectedColumnStore, audit: AuditPort, security: SecurityEventPort,
        outbox: Outbox, key: ContactKey, sender: VerificationSender, recovery: RecoveryDispatch, settings: PassengerSettings, clock: Clock,
    ): PassengerApi {
        val ctxTx = ContextTransactions(tx, identity.revalidator)
        val tokens = ContactTokens(key)
        val membership = JdbcMembershipAdmin(ctxTx, audit, outbox, clock)
        val accounts = JdbcPassengerAccounts(tx, ctxTx, fieldTx, store, audit, security, membership, tokens, sender, recovery, settings, clock)
        val profiles = JdbcPassengerProfiles(ctxTx, fieldTx, store, audit, tokens, settings)
        val consents = JdbcConsent(tx, ctxTx, audit, settings, clock)
        return object : PassengerApi {
            override val accounts: PassengerAccounts = accounts
            override val profiles: PassengerProfiles = profiles
            override val consents: ConsentService = consents
            override val delivery: DeliveryPolicy = consents
            override val directory: PassengerDirectory = JdbcPassengerDirectory(tx, fieldTx, store)
        }
    }
}
