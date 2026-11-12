package de.taktovia.stafftime.internal

/**
 * Wem gehört eine Schicht? `staff_shift.driver_ref` verweist auf den Fahrerstamm (`driver.id`, WP-M3-05, Fremdschlüssel `NOT VALID` aktiv); der Fahrer ist über seine
 * Mitgliedschaft mit dem Konto (Issuer, Subject) verbunden. Bis zur Überführung der Fixtures (WP-M3-02 Kriterium 6) tragen ältere Schichten stattdessen die IdP-Subject-UUID
 * des Kontos; beides gilt als „eigene Schicht“. Bedingung auf dem Alias `s` der Tabelle `staff_shift`; Parameter in dieser Reihenfolge: [subject (klein), issuer, subject].
 */
internal object ShiftOwnership {
    const val PREDICATE = "(s.driver_ref::text = lower(?) OR EXISTS (SELECT 1 FROM taktovia.driver dr JOIN taktovia.membership ms ON ms.tenant_id = dr.tenant_id AND ms.id = dr.membership_id " +
        "WHERE dr.tenant_id = s.tenant_id AND dr.id = s.driver_ref AND ms.issuer = ? AND ms.subject = ? AND ms.revoked_at IS NULL))"

    fun args(issuer: String, subject: String): Array<Any> = arrayOf(subject, issuer, subject)
}
