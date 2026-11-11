package de.taktovia.archfixtures.bad.platform

import de.taktovia.archfixtures.bad.identity.api.IdentityApi

/** Verstoß: platform kennt ein Modul. */
class PlatformUsesModule { var api: IdentityApi? = null }
