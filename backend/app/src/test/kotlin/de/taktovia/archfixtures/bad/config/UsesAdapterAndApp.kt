package de.taktovia.archfixtures.bad.config

import de.taktovia.archfixtures.bad.adapters.psp.api.PspAdapter
import de.taktovia.archfixtures.bad.app.AppThing

/** Verstoß: Modul kennt Adapter und app. */
class UsesAdapterAndApp { var a: PspAdapter? = null; var b: AppThing? = null }
