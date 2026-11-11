package de.taktovia.app

import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import javax.sql.DataSource

/**
 * Verweigert den Start, wenn die Anwendung nicht unter der eingeschränkten Runtime-Rolle läuft
 * (Kap. 04 §4.6, WP-M0-15): kein Superuser, kein BYPASSRLS, kein Eigentümer von Objekten im Schema `taktovia`.
 * Eine Verbindung als Migrator oder Administrator würde RLS wirkungslos machen.
 */
@Component
class RuntimeRoleGuard(private val dataSource: DataSource) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        val problems = check()
        check(problems.isEmpty()) { "Datenbankrolle unzulässig: ${problems.joinToString("; ")}" }
    }

    fun check(): List<String> = dataSource.connection.use { c ->
        c.createStatement().use { st ->
            st.executeQuery(
                """SELECT current_user, r.rolsuper, r.rolbypassrls,
                          (SELECT count(*) FROM pg_class k JOIN pg_namespace n ON n.oid = k.relnamespace
                            WHERE n.nspname = 'taktovia' AND k.relowner = r.oid)
                     FROM pg_roles r WHERE r.rolname = current_user"""
            ).use { rs ->
                check(rs.next())
                buildList {
                    val user = rs.getString(1)
                    if (rs.getBoolean(2)) add("$user ist Superuser")
                    if (rs.getBoolean(3)) add("$user umgeht RLS (BYPASSRLS)")
                    if (rs.getLong(4) > 0) add("$user besitzt Objekte im Schema taktovia")
                }
            }
        }
    }
}
