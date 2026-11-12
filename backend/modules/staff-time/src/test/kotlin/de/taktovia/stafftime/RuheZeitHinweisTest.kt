package de.taktovia.stafftime

import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.stafftime.api.HintBasis
import de.taktovia.stafftime.api.RecordTimeEvent
import de.taktovia.stafftime.api.RuleCode
import de.taktovia.stafftime.api.RuleHint
import de.taktovia.stafftime.api.TimeEventKind
import de.taktovia.stafftime.api.TimeEventSource
import de.taktovia.stafftime.api.WorkRuleSource
import de.taktovia.stafftime.api.WorkRules
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * WP-M4-13 / LH-6.24-TIM-006: Hinweise zu Ruhezeit, Tagesarbeitszeit und Pflichtpause gegen die echte Datenbank (Runtime-Rolle), über die Lesesichten von Dienstplan und Soll/Ist.
 * Die Pausenregel ist eine Planungsannahme (Regelmatrix nicht freigegeben, Offener Punkt 3); die Hinweise sind Warnungen und ändern keine Plan- oder Ist-Daten.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RuheZeitHinweisTest {
    private lateinit var fx: StaffFixture
    @BeforeAll fun up() { fx = StaffFixture() }
    @AfterAll fun down() = fx.close()

    private val noBreakNeeded = null   // Schichten bis 6 Stunden brauchen keine Pause

    private fun hints(w: RosterTestWorld, ctx: de.taktovia.platform.context.RequestContext, rosterId: UUID): List<RuleHint> = fx.staff.rosters.get(ctx, rosterId).hints!!

    @Test
    fun regelkonformer_plan_ohne_hinweis() {
        val w = RosterTestWorld(fx); val ctx = w.planner(); val p = w.person(); val r = w.roster(ctx)
        fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-01", start = "06:00", end = "12:00", driver = p.driverId))
        fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-02", start = "06:00", end = "12:00", driver = p.driverId))
        assertEquals(emptyList<RuleHint>(), hints(w, ctx, r.rosterId))
    }

    @Test
    fun ruhezeit_eine_minute_unter_grenze_erzeugt_warnhinweis() {
        val w = RosterTestWorld(fx); val ctx = w.planner(); val p = w.person(); val r = w.roster(ctx)
        val a = fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-01", start = "06:00", end = "12:00", driver = p.driverId))
        // Ende 12:00 Ortszeit (11:00 UTC); Beginn 22:59 Ortszeit = 10 h 59 min später
        val b = fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-01", start = "22:59", end = "23:59", driver = p.driverId))
        val h = hints(w, ctx, r.rosterId).single()
        assertEquals(RuleCode.REST_PERIOD_SHORT, h.rule); assertEquals(HintBasis.PLAN, h.basis)
        assertEquals(660L, h.expected, "Sollwert: min_rest_hours * 60"); assertEquals(659L, h.actual, "Istwert: gemessene Ruhezeit in Minuten")
        assertEquals("MINUTES", h.unit); assertEquals(p.driverId.toString(), h.personRef); assertEquals(setOf(a.id, b.id), h.shiftIds.toSet())
        assertTrue(h.message.contains("keine Rechtsbewertung"), "kein automatischer Rechtsentscheid")
    }

    @Test
    fun ruhezeit_exakt_auf_grenze_kein_hinweis() {
        val w = RosterTestWorld(fx); val ctx = w.planner(); val p = w.person(); val r = w.roster(ctx)
        fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-01", start = "06:00", end = "12:00", driver = p.driverId))
        fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-01", start = "23:00", end = "23:59", driver = p.driverId))   // genau 11 h später
        assertEquals(emptyList<RuleHint>(), hints(w, ctx, r.rosterId))
    }

    @Test
    fun tagesarbeitszeit_ueberschreitung_erzeugt_hinweis() {
        val w = RosterTestWorld(fx); val ctx = w.planner(); val p = w.person(); val r = w.roster(ctx)
        fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-01", start = "06:00", end = "12:00", driver = p.driverId))
        fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-01", start = "23:00", end = "05:00", endDay = "2026-12-02", driver = p.driverId))   // Ruhezeit genau 11 h
        val h = hints(w, ctx, r.rosterId).single()
        assertEquals(RuleCode.DAILY_WORK_EXCEEDED, h.rule); assertEquals(600L, h.expected); assertEquals(720L, h.actual); assertEquals(LocalDate.of(2026, 12, 1), h.day)
    }

    @Test
    fun ruhezeit_ueber_dst_umstellung_aus_absoluten_zeitpunkten() {
        val w = RosterTestWorld(fx); val ctx = w.planner()
        // Frühjahr 2026-03-29 (02:00 -> 03:00): 20:00 -> 07:00 Ortszeit sind 11 h Wanduhr, real nur 10 h -> Hinweis
        val spring = w.person(); val rs = w.roster(ctx, LocalDate.of(2026, 3, 25))
        fx.staff.rosterShifts.create(ctx, rs.rosterId, w.input(day = "2026-03-28", start = "14:00", end = "20:00", driver = spring.driverId))
        fx.staff.rosterShifts.create(ctx, rs.rosterId, w.input(day = "2026-03-29", start = "07:00", end = "13:00", driver = spring.driverId))
        val hs = hints(w, ctx, rs.rosterId).single()
        assertEquals(RuleCode.REST_PERIOD_SHORT, hs.rule); assertEquals(600L, hs.actual, "real 10 h trotz 11 h Wanduhr")
        // Herbst 2026-10-25 (03:00 -> 02:00): 20:00 -> 06:30 Ortszeit sind 10,5 h Wanduhr, real 11,5 h -> kein Hinweis
        val autumn = w.person(); val ra = w.roster(ctx, LocalDate.of(2026, 10, 22))
        fx.staff.rosterShifts.create(ctx, ra.rosterId, w.input(day = "2026-10-24", start = "14:00", end = "20:00", driver = autumn.driverId))
        fx.staff.rosterShifts.create(ctx, ra.rosterId, w.input(day = "2026-10-25", start = "06:30", end = "12:30", driver = autumn.driverId))
        assertEquals(emptyList<RuleHint>(), hints(w, ctx, ra.rosterId))
    }

    @Test
    fun pflichtpause_fehlt_erzeugt_hinweis_planungsannahme() {
        val w = RosterTestWorld(fx); val ctx = w.planner(); val r = w.roster(ctx)
        val noBreak = w.person(); val withBreak = w.person()
        fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-01", start = "06:00", end = "13:00", driver = noBreak.driverId))   // 7 h ohne Pause
        val plan = mapOf("schema_version" to "1.0", "breaks" to listOf(mapOf("offset_min" to 240, "duration_min" to 30, "paid" to false)))
        fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-02", start = "06:00", end = "13:00", driver = withBreak.driverId).copy(breakPlan = plan))
        val h = hints(w, ctx, r.rosterId).single()
        assertEquals(RuleCode.BREAK_MISSING, h.rule); assertEquals(noBreak.driverId.toString(), h.personRef); assertEquals(30L, h.expected); assertEquals(0L, h.actual)
        // über 9 h: 45 min
        fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-03", start = "06:00", end = "16:00", driver = withBreak.driverId).copy(breakPlan = plan))
        assertEquals(setOf(45L), hints(w, ctx, r.rosterId).filter { it.personRef == withBreak.driverId.toString() }.map { it.expected }.toSet())
    }

    @Test
    fun hinweise_aendern_keine_plan_oder_ist_daten_und_regeln_sind_konfigurierbar() {
        val w = RosterTestWorld(fx); val ctx = w.planner(); val p = w.person(); val r = w.roster(ctx)
        fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-01", start = "06:00", end = "12:00", driver = p.driverId))
        fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-01", start = "22:59", end = "23:59", driver = p.driverId))
        val before = fx.strings("SELECT shift_revision::text || state || starts_at::text FROM taktovia.staff_shift WHERE tenant_id = ? AND roster_id = ? ORDER BY id", w.tenant.value, r.rosterId)
        val etag = fx.staff.rosters.get(ctx, r.rosterId).etag
        repeat(3) { assertEquals(1, hints(w, ctx, r.rosterId).size) }
        assertEquals(before, fx.strings("SELECT shift_revision::text || state || starts_at::text FROM taktovia.staff_shift WHERE tenant_id = ? AND roster_id = ? ORDER BY id", w.tenant.value, r.rosterId))
        assertEquals(etag, fx.staff.rosters.get(ctx, r.rosterId).etag, "das Lesen der Hinweise verändert den Planstand nicht")
        // Regelparameter kommen aus der Konfiguration: bei 10 h Mindestruhezeit gibt es keinen Hinweis mehr
        val relaxed = de.taktovia.stafftime.api.StafftimeApi.create(fx.ctxTx, de.taktovia.platform.audit.JdbcAuditPort(), de.taktovia.platform.outbox.Outbox(), workRules = WorkRuleSource { _, _, _ -> WorkRules(minRestMinutes = 600) })
        assertEquals(emptyList<RuleHint>(), relaxed.rosters.get(ctx, r.rosterId).hints)
    }

    @Test
    fun hinweis_fuer_dritte_ohne_depotrecht_nicht_sichtbar() {
        val w = RosterTestWorld(fx); val ctx = w.planner(); val p = w.person(); val r = w.roster(ctx)
        fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-01", start = "06:00", end = "12:00", driver = p.driverId))
        fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-01", start = "22:59", end = "23:59", driver = p.driverId))
        // Planer ohne Depotrecht für dieses Depot: der Dienstplan und damit die Hinweise sind nicht auffindbar (404), auch nicht mit Recht für ein anderes Depot
        val outsider = w.planner(depots = listOf(w.newDepot("anderes Depot")))
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { fx.staff.rosters.get(outsider, r.rosterId) }.code)
        // ohne Dienstplanscope (Fahrer, Disposition) nicht zugreifbar
        assertEquals(ErrorCode.FORBIDDEN, assertThrows<ProblemException> { fx.staff.rosters.get(w.driver(), r.rosterId) }.code)
        // verbindliche Sicht und Listen tragen keine Hinweise
        publishAsAdmin(w, r.rosterId)
        assertNull(fx.staff.rosters.get(ctx, r.rosterId, de.taktovia.stafftime.api.RosterViewKind.PUBLISHED).hints)
        assertTrue(fx.staff.rosters.list(ctx, null, null, null, 10, null).all { it.hints == null })
        // betroffene Person: Hinweise der Eigensicht; fremde Person ohne Verwaltungsrecht: 404; Personalrolle mit Verwaltungsrecht: Tage ohne Hinweise
        val self = StaffM4Support.employee(w.tenant, UUID.fromString(p.subject))
        val own = fx.staff.timeTargetActual.days(self, null, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 1), java.time.ZoneId.of("Europe/Berlin"))
        assertEquals(listOf(RuleCode.REST_PERIOD_SHORT), own.single().hints.map { it.rule })
        val third = StaffM4Support.employee(w.tenant)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { fx.staff.timeTargetActual.days(third, p.subject, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 1), java.time.ZoneId.of("Europe/Berlin")) }.code)
        val viaHr = fx.staff.timeTargetActual.days(w.hr(), p.subject, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 1), java.time.ZoneId.of("Europe/Berlin"))
        assertTrue(viaHr.single().hints.isEmpty(), "Hinweise nur für Planer mit Depotrecht und die betroffene Person")
    }

    @Test
    fun ist_hinweise_aus_zeitereignissen() {
        val w = RosterTestWorld(fx); val p = w.person(); val self = StaffM4Support.employee(w.tenant, UUID.fromString(p.subject))
        fun stamp(kind: TimeEventKind, at: String) = fx.staff.timeEvents.record(self, RecordTimeEvent(UUID.randomUUID(), kind, Instant.parse(at), Instant.parse(at), TimeEventSource.APP, null))
        // 07:00-14:00 UTC ohne Pause (7 h), danach 23:00-... nächster Beginn nach nur 9 h
        stamp(TimeEventKind.WORK_START, "2026-12-01T07:00:00Z"); stamp(TimeEventKind.WORK_END, "2026-12-01T14:00:00Z")
        stamp(TimeEventKind.WORK_START, "2026-12-01T23:00:00Z"); stamp(TimeEventKind.WORK_END, "2026-12-02T03:00:00Z")
        val days = fx.staff.timeTargetActual.days(self, null, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 2), java.time.ZoneId.of("Europe/Berlin"))
        val all = days.flatMap { it.hints }
        assertTrue(all.all { it.basis == HintBasis.ACTUAL })
        assertEquals(setOf(RuleCode.BREAK_MISSING, RuleCode.REST_PERIOD_SHORT), all.map { it.rule }.toSet())
        val rest = all.single { it.rule == RuleCode.REST_PERIOD_SHORT }
        assertEquals(540L, rest.actual); assertEquals(4, rest.eventIds.size, "betroffene Zeitereignisse benannt")
        // Ist-Daten unverändert: nur die vier gestempelten Ereignisse
        assertEquals(4, fx.count("SELECT count(*) FROM taktovia.time_event WHERE tenant_id = ?", w.tenant.value))
    }

    /** Stellt die Veröffentlichung nach (Administration; der Befehl ist WP-M4-14), damit die Eigensicht die Schichten sieht. */
    private fun publishAsAdmin(w: RosterTestWorld, rosterId: UUID) {
        fx.exec("UPDATE taktovia.staff_shift SET state = 'PUBLISHED' WHERE tenant_id = ? AND roster_id = ? AND state = 'DRAFT'", w.tenant.value, rosterId)
        fx.exec("UPDATE taktovia.roster_revision SET state = 'PUBLISHED', published_at = now(), published_by = 'user:test' WHERE tenant_id = ? AND roster_id = ? AND state = 'DRAFT'", w.tenant.value, rosterId)
    }
}
