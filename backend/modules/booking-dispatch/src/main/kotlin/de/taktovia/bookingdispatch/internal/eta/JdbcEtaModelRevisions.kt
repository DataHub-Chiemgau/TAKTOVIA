package de.taktovia.bookingdispatch.internal.eta

import de.taktovia.bookingdispatch.api.EtaModelRevisionView
import de.taktovia.bookingdispatch.api.EtaModelRevisions
import de.taktovia.bookingdispatch.api.EtaQuantile
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import org.springframework.jdbc.core.JdbcOperations
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.util.UUID

/** Planungsannahmen für fehlende Modellparameter (Herleitung und Freigabe offen, OP-34): Haltezeit 30 s, Rollstuhl-Einstieg 120 s. */
internal object EtaPlanningDefaults {
    const val STOP_DWELL_SEC = 30
    const val WHEELCHAIR_BOARDING_SEC = 120
}

/** Liest die höchste freigegebene Revision des Mandanten (RLS). Die Zeile ist nach der Freigabe unveränderlich, die Sicht daher ohne Sperre konsistent. */
internal class JdbcEtaModelRevisions(private val ctxTx: ContextTransactions) : EtaModelRevisions {
    override fun current(ctx: RequestContext): EtaModelRevisionView? = ctxTx.run(ctx, readOnly = true) { current(it) }

    fun current(j: JdbcOperations): EtaModelRevisionView? = j.query(
        "SELECT id, version, road_data_version, parameters::text, buffer_pct_by_quantile::text, min_buffer_sec FROM taktovia.eta_model_revision " +
            "WHERE tenant_id = taktovia.current_tenant() AND state = 'APPROVED' ORDER BY version DESC LIMIT 1",
        { rs, _ ->
            val params = mapper.readTree(rs.getString(4))
            val table = mapper.readTree(rs.getString(5))
            EtaModelRevisionView(
                id = rs.getObject(1, UUID::class.java), version = rs.getInt(2), roadDataVersion = rs.getString(3),
                defaultStopDwellSec = params.path("default_stop_dwell_sec").asInt(EtaPlanningDefaults.STOP_DWELL_SEC),
                defaultWheelchairBoardingSec = params.path("default_wheelchair_boarding_sec").asInt(EtaPlanningDefaults.WHEELCHAIR_BOARDING_SEC),
                bufferPctByQuantile = EtaQuantile.entries.associateWith { BigDecimal(table.get(it.code).asString()) }, minBufferSec = rs.getInt(6),
            )
        },
    ).firstOrNull()

    private companion object { val mapper: JsonMapper = JsonMapper.builder().build() }
}
