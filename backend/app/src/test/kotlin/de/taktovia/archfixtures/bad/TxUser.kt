package de.taktovia.archfixtures.bad

import de.taktovia.archfixtures.bad.adapters.psp.api.PspAdapter
import org.springframework.transaction.annotation.Transactional

/** Verstoß: PSP-Aufruf aus einer transaktionalen Methode. */
class TxUser {
    @Transactional
    fun pay(p: PspAdapter) = p.charge()
}

@Transactional
class TxClass { var p: PspAdapter? = null }
