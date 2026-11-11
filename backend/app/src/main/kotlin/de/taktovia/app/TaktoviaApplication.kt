package de.taktovia.app

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/** Das einzige Deployable (Leitlinie 1, Kap. 01 §1.1). Fachendpunkte folgen mit den Modulen. */
@SpringBootApplication(scanBasePackages = ["de.taktovia"])
class TaktoviaApplication

fun main(args: Array<String>) {
    runApplication<TaktoviaApplication>(*args)
}
