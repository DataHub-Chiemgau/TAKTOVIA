package de.taktovia.archfixtures.bad.platform.money

/** Verstoß 4: Geldfeld als Double, außerdem Gleitkommafeld im Paket money. */
class BadMoney(val amountMinor: Double, val ratio: Double) {
    fun totalMinor(): Double = amountMinor
}
