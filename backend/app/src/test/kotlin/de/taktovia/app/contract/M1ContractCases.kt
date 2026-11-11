package de.taktovia.app.contract

/**
 * Vertragsfälle der produktiven Endpunkte. Wer einen Endpunkt aus `api/<modul>.yaml` baut, streicht seine `operationId` aus
 * `api/contract-pending.txt` und trägt hier einen [ContractCase] mit positivem Fall und Fehlerfall ein; ohne Eintrag
 * scheitert `ContractCoverageTest`. Stand WP-M1-03: noch kein M1-Endpunkt ist gebaut (Umsetzung in den WPs M1-04 ff.).
 */
object M1ContractCases {
    val cases: Map<String, ContractCase> = emptyList<ContractCase>().associateBy { it.operationId }
}
