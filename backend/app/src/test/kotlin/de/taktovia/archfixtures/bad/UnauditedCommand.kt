package de.taktovia.archfixtures.bad

import de.taktovia.platform.audit.AuditRequired

/** Verstoß: als auditpflichtig markierter Befehl ohne Audit-Aufruf (WP-M1-46). */
class UnauditedCommand {
    @AuditRequired
    fun grantMembership(): String = "ohne Audit"
}
