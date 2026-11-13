package de.taktovia.platform

import de.taktovia.platform.carddata.CardData
import de.taktovia.platform.carddata.CardDataFilter
import de.taktovia.platform.carddata.CardDataInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/**
 * Befund aus dem Lasttest (WP-M5-10): Positionsmeldungen der Fahrer-App mit Breitengrad 51..55 und 16 gültigen Stellen (so serialisiert ein `double`) wurden in etwa jedem zehnten Fall als
 * Kartennummer abgewiesen (Mastercard-Präfix 51..55 und Luhn-Summe). Gleitkommazahlen sind keine Kartennummern; ganzzahlige Zahlenwerte bleiben geprüft.
 */
class CardDataCoordinatesTest {
    private val json = JsonMapper()

    @Test
    fun koordinaten_mit_sechzehn_stellen_werden_nicht_als_kartennummer_abgewiesen_ganzzahlen_schon() {
        var flaggedByStringRule = 0
        val rnd = java.util.Random(7)
        for (i in 0 until 2000) {
            val lat = "5" + (1 + rnd.nextInt(5)) + "." + (1..14).joinToString("") { (rnd.nextInt(9) + 1).toString() }       // 16 gültige Stellen, Breitengrad 51..55
            val node = json.readTree("""{"samples":[{"position":{"latitude":$lat,"longitude":13.7412345678901}}]}""")
            val asText = node.get("samples").get(0).get("position").get("latitude").asString()
            if (CardData.contains(asText)) {
                flaggedByStringRule++
                assertEquals(emptyList<String>(), CardDataFilter.screen(CardDataInput(body = node)).map { it.location }, "Koordinate $asText darf nicht abgewiesen werden")
            }
        }
        assertTrue(flaggedByStringRule > 0, "Der Test braucht mindestens eine Koordinate, die die reine Zeichenkettenregel treffen würde")
        // Gegenprobe: dieselbe Ziffernfolge als ganze Zahl ist weiterhin eine Kartennummer
        assertEquals(listOf("body:/amount"), CardDataFilter.screen(CardDataInput(body = json.readTree("""{"amount":5105105105105100}"""))).map { it.location })
    }
}
