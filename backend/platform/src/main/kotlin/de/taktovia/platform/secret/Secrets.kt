package de.taktovia.platform.secret

import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * Secret-Referenz `secret://<domäne>/<name>` (WP-M1-36, Kap. 01 §1.8). Konfiguration und Datenbank halten nur Referenzen,
 * nie Werte. Die Domäne entspricht der Geheimnisdomäne der Umgebung (`taktovia-<umgebung>-secrets`, infra/deploy/umgebungsmatrix.csv),
 * der Name einem Eintrag aus infra/deploy/geheimnisse.csv.
 */
data class SecretRef(val domain: String, val name: String) {
    init {
        require(DOMAIN.matches(domain)) { "ungültige Secret-Domäne" }
        require(NAME.matches(name)) { "ungültiger Secret-Name" }
    }
    override fun toString() = "secret://$domain/$name"

    companion object {
        private val DOMAIN = Regex("[a-z][a-z0-9-]{2,62}")
        private val NAME = Regex("[a-z][a-z0-9_]{1,62}")
        private val FORMAT = Regex("secret://([a-z][a-z0-9-]{2,62})/([a-z][a-z0-9_]{1,62})")

        fun isRef(value: String?) = value != null && FORMAT.matches(value)

        fun parse(value: String): SecretRef {
            val m = FORMAT.matchEntire(value) ?: throw IllegalArgumentException("keine Secret-Referenz (erwartet secret://<domäne>/<name>)")
            return SecretRef(m.groupValues[1], m.groupValues[2])
        }

        /** Konfigurationswert für ein Geheimnisfeld: nur eine Referenz ist zulässig, ein Klartextwert wird abgewiesen (ohne ihn auszugeben). */
        fun requireRef(property: String, value: String?): SecretRef {
            require(isRef(value)) { "Eigenschaft '$property' muss eine Secret-Referenz sein, kein Klartextwert" }
            return parse(value!!)
        }
    }
}

/** Secretwert; `toString` gibt nie den Inhalt aus (Logs, Konfigurationsdump). */
class SecretValue(private val value: CharArray) {
    constructor(value: String) : this(value.toCharArray())
    fun reveal(): String = String(value)
    override fun toString() = "SecretValue(***)"
    override fun equals(other: Any?) = other is SecretValue && other.value.contentEquals(value)
    override fun hashCode() = value.contentHashCode()
}

class SecretNotFoundException(ref: SecretRef) : RuntimeException("Secret nicht gefunden: $ref")
class SecretDomainMismatchException(ref: SecretRef, expected: String) :
    RuntimeException("Secret $ref gehört nicht zur Domäne dieser Umgebung ($expected)")

/** Port zur Secret-Verwaltung der Umgebung (Anbieter bis D-05 offen). */
interface SecretStore {
    fun read(ref: SecretRef): SecretValue
}

/**
 * Secrets als Dateien unter `<verzeichnis>/<domäne>/<name>` (von der Secret-Verwaltung der Umgebung eingehängt, z. B. als
 * Volume). Jeder Zugriff liest die Datei neu: eine Rotation wird ohne Neustart und ohne neues Image wirksam, Referenz bleibt, Wert wechselt.
 * Aktualisierung durch den Verwalter muss atomar sein (Datei ersetzen, nicht teilweise schreiben).
 */
class FileSecretStore(private val root: Path) : SecretStore {
    override fun read(ref: SecretRef): SecretValue = try {
        SecretValue(Files.readString(root.resolve(ref.domain).resolve(ref.name)).trimEnd('\n', '\r'))
    } catch (e: NoSuchFileException) {
        throw SecretNotFoundException(ref)
    }
}

/** Löst Referenzen auf und erzwingt die Trennung der Umgebungen: eine Referenz einer fremden Domäne wird nie aufgelöst. */
class SecretResolver(val domain: String, private val store: SecretStore) {
    fun resolve(ref: SecretRef): SecretValue {
        if (ref.domain != domain) throw SecretDomainMismatchException(ref, domain)
        return store.read(ref)
    }
    fun resolve(ref: String) = resolve(SecretRef.parse(ref))
}
