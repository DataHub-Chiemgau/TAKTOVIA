package de.taktovia.archfixtures.bad.config

import de.taktovia.archfixtures.bad.identity.internal.Secret

/** Verstoß 1: Modul `config` greift auf `internal` von `identity` zu. */
class Offender { val s = Secret() }
