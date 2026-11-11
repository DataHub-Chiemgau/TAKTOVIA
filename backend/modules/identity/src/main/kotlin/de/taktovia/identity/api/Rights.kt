package de.taktovia.identity.api

import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import java.security.MessageDigest

/** Eine Rolle des Rollenkatalogs (`membership.role_code`, DB-Tabelle `role_catalog`). */
data class Role(val code: String, val labelDe: String, val placeholder: Boolean)

/**
 * Rollenkatalog (WP-M1-07, LH-6.2-IAM-004): die sieben Pflichtrollen Fahrgast, Fahrer, Callcenter, Support, Disponent, Verkehrsplaner und
 * Administrator sowie Platzhalter für weitere Fachrollen aus Kap. 06 (Finanzen, Personalplanung, Werkstatt/Freigabe; Bedeutung für M3/M4).
 * Die Werteliste ist fachlich nicht freigegeben (OP-10). Ein Test hält Katalog, Datenbank-CHECK, Rechtematrix und OpenAPI-Enum synchron.
 */
object RoleCatalog {
    val ROLES: List<Role> = listOf(
        Role("passenger", "Fahrgast", false), Role("driver", "Fahrer", false), Role("callcenter", "Callcenter", false), Role("support", "Support", false),
        Role("dispatcher", "Disponent", false), Role("planner", "Verkehrsplaner", false), Role("tenant_admin", "Administrator", false),
        Role("finance", "Finanzen", true), Role("staff_planning", "Personalplanung", true), Role("workshop", "Werkstatt/Freigabe", true),
    )
    val CODES: Set<String> = ROLES.map { it.code }.toSet()
    /** Die sieben Pflichtrollen aus IAM-004. */
    val REQUIRED: List<String> = ROLES.filter { !it.placeholder }.map { it.code }
    const val ADMIN = "tenant_admin"
    fun isValid(code: String) = code in CODES
}

/** Eine Tätigkeit der Rechtematrix: eine Operation der OpenAPI-Verträge mit ihrem Funktionsscope und den erlaubten Rollen. */
data class MatrixOperation(val operationId: String, val scope: String, val dataClass: String, val milestone: String, val allowed: Set<String>)

/**
 * Versionierte Rechtematrix (Rolle x Tätigkeit), geladen aus `rechtematrix/rechtematrix.csv` im Repository (WP-M1-07). Die Matrix ist die einzige
 * Quelle der Rechte: Funktionsscopes einer Rolle = Scopes der für sie erlaubten Tätigkeiten ([scopesFor]). Alle Tätigkeiten mit gleichem
 * Scope haben gleiche Rollenzellen ([problems] prüft das), damit Scope und Tätigkeit nie auseinanderlaufen. Das Ausrollen wird mit
 * Version und SHA-256 protokolliert ([sha256], [version]).
 */
class RightsMatrix private constructor(val version: Int, val sha256: String, val roles: List<String>, val operations: List<MatrixOperation>) {
    private val byId = operations.associateBy { it.operationId }

    fun operation(operationId: String): MatrixOperation? = byId[operationId]

    /** Ist die Tätigkeit für mindestens eine der Rollen erlaubt? Unbekannte Tätigkeit: nein (deny-by-default). */
    fun allows(roles: Set<String>, operationId: String): Boolean = byId[operationId]?.allowed?.any { it in roles } == true

    /** Funktionsscopes, die die Rollen gemeinsam erhalten. */
    fun scopesFor(roles: Set<String>): Set<String> = operations.filter { op -> op.scope != "-" && op.allowed.any { it in roles } }.map { it.scope }.toSet()

    /** Alle Scopes der Matrix (ohne den Platzhalter `-` für Operationen ohne Menschen-/Dienstscope). */
    val scopes: Set<String> get() = operations.map { it.scope }.filter { it != "-" }.toSet()

    /** Strukturprüfung der Matrix: Rollenspalten = Katalog, eindeutige Tätigkeiten, gleiche Zellen je Scope, gültige Datenklassen. Leer = in Ordnung. */
    fun problems(): List<String> = buildList {
        if (roles.toSet() != RoleCatalog.CODES || roles.size != roles.toSet().size) add("Rollenspalten ${roles} entsprechen nicht dem Rollenkatalog ${RoleCatalog.CODES}")
        operations.groupBy { it.operationId }.filterValues { it.size > 1 }.keys.forEach { add("Tätigkeit $it doppelt") }
        operations.filter { it.scope != "-" }.groupBy { it.scope }.forEach { (scope, ops) ->
            if (ops.map { it.allowed }.toSet().size > 1) add("Scope $scope hat in ${ops.map { it.operationId }} unterschiedliche Rollenzellen")
        }
        operations.forEach {
            if (it.dataClass !in DATA_CLASSES) add("${it.operationId}: unbekannte Datenklasse ${it.dataClass}")
            if (it.scope == "-" && it.allowed.isNotEmpty()) add("${it.operationId}: Operation ohne Scope darf für keine Menschenrolle erlaubt sein")
            if (it.dataClass != "-" && RoleCatalog.ADMIN in it.allowed) add("${it.operationId}: Administrator hat Zugriff auf Datenklasse ${it.dataClass} (Kap. 01 §1.7)")
        }
    }

    /**
     * Abgleich mit den Verträgen: jede Operation der OpenAPI-Dateien hat genau eine Matrixzeile mit demselben Scope, und es gibt keine Zeile
     * ohne Operation. [contract] bildet operationId -> Scopes der ersten Sicherheitsanforderung (leer: keine). Leer = in Ordnung.
     */
    fun verifyContract(contract: Map<String, List<String>>): List<String> = buildList {
        contract.forEach { (id, scopes) ->
            val op = byId[id]
            when {
                op == null -> add("Operation $id hat keinen Eintrag in der Rechtematrix")
                (scopes.singleOrNull() ?: "-") != op.scope -> add("Operation $id: Scope im Vertrag $scopes, in der Rechtematrix ${op.scope}")
            }
        }
        (byId.keys - contract.keys).forEach { add("Rechtematrix kennt Operation $it nicht im Vertrag (veralteter Eintrag)") }
    }

    companion object {
        val DATA_CLASSES = setOf("-", "PII", "HR", "FINANZ")
        const val RESOURCE = "/rechtematrix/rechtematrix.csv"
        private const val FIXED = 4

        fun load(): RightsMatrix = parse(RightsMatrix::class.java.getResourceAsStream(RESOURCE)?.use { String(it.readAllBytes(), Charsets.UTF_8) } ?: error("Rechtematrix $RESOURCE fehlt im Klassenpfad"))

        fun parse(text: String): RightsMatrix {
            val sha = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            val version = Regex("(?m)^#\\s*matrix_version:\\s*(\\d+)\\s*$").find(text)?.groupValues?.get(1)?.toInt() ?: error("matrix_version fehlt")
            val lines = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }
            val header = lines.first().split(';')
            require(header.take(FIXED) == listOf("operation_id", "scope", "datenklasse", "meilenstein")) { "Kopfzeile der Rechtematrix ungültig" }
            val roles = header.drop(FIXED)
            val ops = lines.drop(1).map { l ->
                val c = l.split(';')
                require(c.size == header.size) { "Zeile '${c.firstOrNull()}' hat ${c.size} statt ${header.size} Spalten" }
                require(c.drop(FIXED).all { it == "J" || it == "N" }) { "Zeile '${c[0]}': Zellen nur J oder N" }
                MatrixOperation(c[0], c[1], c[2], c[3], roles.filterIndexed { i, _ -> c[FIXED + i] == "J" }.toSet())
            }
            return RightsMatrix(version, sha, roles, ops)
        }
    }
}

sealed interface Decision {
    data object Allowed : Decision
    /** [reason] ist nur für Audit und Log bestimmt, nie für die Antwort an den Aufrufer. */
    data class Denied(val reason: String) : Decision
}

/**
 * Zentrale Autorisierungskomponente (WP-M1-07): entscheidet, ob der Kontext die Operation ausführen darf. Menschen: die Operation steht in der
 * Rechtematrix, ihr Scope im Vertrag entspricht der Matrix und mindestens eine Rolle des Kontexts ist erlaubt. Dienstkonten: die Scopes des
 * Kontexts (Token ∩ Obergrenze des Adapters) enthalten den Scope des Vertrags; die Operation steht ebenfalls in der Matrix. Alles andere: verweigert.
 */
interface Authorizer {
    fun decide(ctx: RequestContext, operationId: String, declaredScopes: List<String>): Decision
}

class MatrixAuthorizer(private val matrix: RightsMatrix) : Authorizer {
    override fun decide(ctx: RequestContext, operationId: String, declaredScopes: List<String>): Decision {
        val op = matrix.operation(operationId) ?: return Decision.Denied("not_in_matrix")
        if (declaredScopes.isEmpty()) return Decision.Denied("no_scope_declared")
        if (declaredScopes.singleOrNull() != op.scope) return Decision.Denied("matrix_scope_mismatch")
        return when (ctx.actor.type) {
            ActorType.SERVICE -> if (ctx.scopes.containsAll(declaredScopes)) Decision.Allowed else Decision.Denied("scope_missing")
            ActorType.HUMAN -> if (ctx.roles.any { it in op.allowed }) Decision.Allowed else Decision.Denied("role_not_allowed")
        }
    }
}

/** Ergebnis des Protokollierens beim Ausrollen. */
data class RolloutResult(val recorded: Boolean, val version: Int, val sha256: String, val previousSha256: String?)

interface RightsMatrixRollout {
    /** Schreibt beim Ausrollen genau dann eine Zeile in `rights_matrix_rollout`, wenn die Matrix von der zuletzt protokollierten abweicht (idempotent, instanzübergreifend). */
    fun record(rolledOutBy: String): RolloutResult
}
