package de.taktovia.archfixtures.bad.policy

import de.taktovia.platform.policy.ConfirmingCommand

/** Absichtlich fehlerhaft (roter Selbsttest von PolicyCheckRules): bestätigender Befehl, der PolicyCheck nie aufruft. */
class UncheckedConfirmation {
    @ConfirmingCommand
    fun confirm(requestedWaitMin: Int): String = "bestätigt $requestedWaitMin"
}
