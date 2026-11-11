package de.taktovia.archfixtures.bad.isolation

/** Verstoß (Stellvertreter für ein Objektspeicher-SDK): ein Paket außerhalb von platform.storage hängt von einem verbotenen Paket ab. */
class UsesSdk { val map = java.util.concurrent.ConcurrentHashMap<String, String>() }
