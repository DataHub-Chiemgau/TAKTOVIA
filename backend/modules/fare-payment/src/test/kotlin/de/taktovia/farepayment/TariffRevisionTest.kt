package de.taktovia.farepayment

import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import de.taktovia.farepayment.api.CreateTariffRevision
import de.taktovia.farepayment.api.TariffRuleInput
import de.taktovia.farepayment.api.TariffState
import de.taktovia.farepayment.api.UpdateTariffRevision
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.problem.ErrorCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** WP-M2-07 / LH-6.10-PAY-003 gegen die echte Datenbank unter der Runtime-Rolle (Portebene; die HTTP-Ebene prüft `TariffHttpApiTest` in der Anwendung). */
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
class TariffRevisionTest {
    private val fx = TariffTestFixture()
    @AfterAll fun close() = fx.close()
    private fun at(s: String) = Instant.parse(s)

    @Test
    fun regel_innerhalb_gueltigkeit_wird_gefunden_noch_nicht_gueltige_nicht() {
        val w = fx.world(); val creator = fx.context(w.tenant)
        val rev = fx.published(creator, w, from = "2026-03-01T00:00:00Z", to = "2026-06-01T00:00:00Z")
        val ctx = fx.context(w.tenant)
        // links geschlossen, rechts offen
        assertEquals(rev.id, fx.tariffs.effective(ctx, w.service, "STANDARD", at("2026-03-01T00:00:00Z"))!!.id)
        assertEquals(rev.id, fx.tariffs.effective(ctx, w.service, "STANDARD", at("2026-05-31T23:59:59Z"))!!.id)
        assertNull(fx.tariffs.effective(ctx, w.service, "STANDARD", at("2026-02-28T23:59:59Z")), "noch nicht gültig")
        assertNull(fx.tariffs.effective(ctx, w.service, "STANDARD", at("2026-06-01T00:00:00Z")), "ab valid_to nicht mehr")
        // andere Tarifklasse, fremder Dienst, Entwurf und geprüfte Revision zählen nicht
        assertNull(fx.tariffs.effective(ctx, w.service, "ERMAESSIGT", at("2026-04-01T00:00:00Z")))
        assertNull(fx.tariffs.effective(ctx, fx.newService(w.tenant), "STANDARD", at("2026-04-01T00:00:00Z")), "Regel eines fremden Dienstes")
        val draft = fx.create(creator, w, from = "2027-01-01T00:00:00Z")
        assertNull(fx.tariffs.effective(ctx, w.service, "STANDARD", at("2027-02-01T00:00:00Z")), "Entwurf ist kein wirksamer Tarif")
        fx.tariffs.review(fx.context(w.tenant), draft.id, draft.revision)
        assertNull(fx.tariffs.effective(ctx, w.service, "STANDARD", at("2027-02-01T00:00:00Z")), "geprüft ist noch nicht veröffentlicht")
        // Regelinhalt vollständig geliefert
        val found = fx.tariffs.effective(ctx, w.service, "STANDARD", at("2026-04-01T00:00:00Z"))!!
        assertEquals(TariffState.VEROEFFENTLICHT, found.state); assertEquals("FESTPREIS", found.rules.single().ruleType); assertEquals(450, found.rules.single().amountMinor)
        assertEquals("0.07000000", found.rules.single().taxRate)
        assertEquals("EUR", found.currency); assertEquals(1, found.revisionNo)
    }

    @Test
    fun abgelaufene_regel_wird_nicht_angewendet() {
        val w = fx.world(); val creator = fx.context(w.tenant); val ctx = fx.context(w.tenant)
        val old = fx.published(creator, w, from = "2026-01-01T00:00:00Z", to = "2026-07-01T00:00:00Z", rules = listOf(fx.fixedRule(400)))
        assertEquals(old.id, fx.tariffs.effective(ctx, w.service, "STANDARD", at("2026-06-30T23:59:59Z"))!!.id)
        assertNull(fx.tariffs.effective(ctx, w.service, "STANDARD", at("2026-07-01T00:00:00Z")), "abgelaufen ohne Nachfolger: kein Tarif")
        // Nachfolgerevision: ab dem Ende der alten gilt sie, die alte nie mehr
        val next = fx.published(creator, w, from = "2026-07-01T00:00:00Z", rules = listOf(fx.fixedRule(500)))
        assertEquals(2, next.revisionNo)
        val after = fx.tariffs.effective(ctx, w.service, "STANDARD", at("2026-07-01T00:00:00Z"))!!
        assertEquals(next.id, after.id); assertEquals(500, after.rules.single().amountMinor)
        assertEquals(old.id, fx.tariffs.effective(ctx, w.service, "STANDARD", at("2026-06-30T23:59:59Z"))!!.id, "Preisstichtag davor: weiter die alte Revision")
        assertEquals(next.id, fx.tariffs.effective(ctx, w.service, "STANDARD", at("2031-01-01T00:00:00Z"))!!.id, "offen (valid_to leer)")
    }

    @Test
    fun veroeffentlichte_revision_ist_unveraenderlich() {
        val w = fx.world(); val creator = fx.context(w.tenant)
        val rev = fx.published(creator, w)
        val upd = UpdateTariffRevision(at("2026-01-01T00:00:00Z"), null, "Änderung", listOf(fx.fixedRule(999)))
        assertEquals(ErrorCode.IMMUTABLE_RECORD, fx.problem { fx.tariffs.update(creator, rev.id, rev.revision, upd) }.code)
        assertEquals(450, fx.tariffs.get(creator, rev.id).rules.single().amountMinor)
        // direkter Zugriff unter der Runtime-Rolle schlägt in der Datenbank fehl (Trigger bzw. fehlendes Recht), auch an der Anwendung vorbei
        for (sql in listOf("UPDATE taktovia.tariff_revision SET reason = 'x' WHERE id = ?", "UPDATE taktovia.tariff_revision SET valid_to = '2026-02-01Z' WHERE id = ?",
            "UPDATE taktovia.tariff_rule SET amount_minor = 1 WHERE revision_id = ?", "DELETE FROM taktovia.tariff_rule WHERE revision_id = ?", "DELETE FROM taktovia.tariff_revision WHERE id = ?")) {
            val e = runCatching { fx.tx.run(w.tenant) { j -> j.update(sql, rev.id) } }.exceptionOrNull()
            assertNotNull(e, sql)
        }
        val after = fx.tariffs.get(creator, rev.id)
        assertEquals(rev.revision, after.revision); assertEquals(rev.rules, after.rules)
        // Korrektur nur als neue Revision (anderer Zeitraum, sonst Überlappung)
        val fix = fx.create(creator, w, from = "2030-01-01T00:00:00Z")
        assertEquals(2, fix.revisionNo)
    }

    @Test
    fun parallele_veroeffentlichung_ueberlappend_genau_eine_erfolgreich() {
        val w = fx.world(); val creator = fx.context(w.tenant)
        val a = fx.create(creator, w, from = "2026-01-01T00:00:00Z", to = "2026-12-31T00:00:00Z"); val b = fx.create(creator, w, from = "2026-06-01T00:00:00Z", to = "2027-06-30T00:00:00Z")
        val rev = fx.context(w.tenant)
        val ra = fx.tariffs.review(rev, a.id, a.revision); val rb = fx.tariffs.review(rev, b.id, b.revision)
        val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
        try {
            val results = listOf(ra, rb).map { r -> pool.submit<Result<Any>> { go.await(); runCatching { fx.tariffs.publish(fx.context(w.tenant), r.id, r.revision) } } }
            go.countDown()
            val outcomes = results.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, outcomes.count { it.isSuccess }, outcomes.toString())
            val failure = outcomes.single { it.isFailure }.exceptionOrNull() as de.taktovia.platform.problem.ProblemException
            assertEquals(ErrorCode.CONFLICT, failure.code)
        } finally { pool.shutdownNow() }
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.tariff_revision WHERE service_id = ? AND state = 'VEROEFFENTLICHT'", w.service))
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.outbox WHERE tenant_id = ? AND event_type = 'tariff.revision_published'", w.tenant.value), "nur die erfolgreiche Veröffentlichung erzeugt ein Ereignis")
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = ?", w.tenant.value, AuditActions.TARIFF_REVISION_PUBLISHED))
    }

    @Test
    fun veroeffentlichung_parallel_zur_preisberechnung_nutzt_alte_oder_neue_revision_vollstaendig() {
        val w = fx.world(); val creator = fx.context(w.tenant); val ctx = fx.context(w.tenant)
        val old = fx.published(creator, w, from = "2026-01-01T00:00:00Z", to = "2026-07-01T00:00:00Z", rules = listOf(fx.fixedRule(400)))
        val zones = (1..6).map { TariffRuleInput(ruleType = "ZONENPREIS", amountMinor = 100L * it, zoneFrom = UUID.nameUUIDFromBytes("a$it".toByteArray()).toString(), zoneTo = UUID.nameUUIDFromBytes("b$it".toByteArray()).toString(), taxRuleRevision = "DE-2026-1") }
        val draft = fx.create(creator, w, from = "2026-07-01T00:00:00Z", rules = zones)
        val reviewed = fx.tariffs.review(fx.context(w.tenant), draft.id, draft.revision)
        val stop = AtomicBoolean(false); val seen = java.util.concurrent.ConcurrentLinkedQueue<Pair<Int, Int>>()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val readers = (1..3).map { pool.submit { while (!stop.get()) { fx.tariffs.effective(ctx, w.service, "STANDARD", at("2026-07-01T00:00:00Z"))?.let { seen += it.revisionNo to it.rules.size } } } }
            Thread.sleep(150)
            fx.tariffs.publish(fx.context(w.tenant), reviewed.id, reviewed.revision)
            Thread.sleep(150); stop.set(true); readers.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        assertTrue(seen.isNotEmpty() && seen.all { it == (2 to 6) }, "ab dem Stichtag nur die neue Revision, vollständig (alte war abgelaufen): $seen")
        assertEquals(old.id, fx.tariffs.effective(ctx, w.service, "STANDARD", at("2026-06-30T00:00:00Z"))!!.id)
    }

    @Test
    fun regel_ohne_zeitraum_dienst_tarifklasse_ergibt_422() {
        val w = fx.world(); val ctx = fx.context(w.tenant)
        fun pointers(cmd: CreateTariffRevision) = fx.problem { fx.tariffs.create(ctx, cmd) }.also { assertEquals(ErrorCode.VALIDATION_FAILED, it.code) }.violations.map { it.pointer }
        val ok = CreateTariffRevision(w.service, "STANDARD", at("2026-01-01T00:00:00Z"), null, "EUR", "Grund", listOf(fx.fixedRule()))
        assertEquals(listOf("/service_id"), pointers(ok.copy(serviceId = null)))
        assertEquals(listOf("/tariff_class"), pointers(ok.copy(tariffClass = null)))
        assertEquals(listOf("/tariff_class"), pointers(ok.copy(tariffClass = "standard")))
        assertEquals(listOf("/valid_from"), pointers(ok.copy(validFrom = null)))
        assertEquals(listOf("/valid_to"), pointers(ok.copy(validTo = at("2026-01-01T00:00:00Z"))))
        assertEquals(listOf("/currency"), pointers(ok.copy(currency = "XXX")))
        assertEquals(listOf("/reason"), pointers(ok.copy(reason = " ")))
        assertEquals(listOf("/rules"), pointers(ok.copy(rules = emptyList())))
        assertEquals(listOf("/rules"), pointers(ok.copy(rules = null)))
        // Regelverletzungen mit Feldverweis je Regel
        fun rule(r: TariffRuleInput) = pointers(ok.copy(rules = listOf(r)))
        val f = fx.fixedRule()
        assertEquals(listOf("/rules/0/rule_type"), rule(f.copy(ruleType = "PAUSCHALE")))
        assertEquals(listOf("/rules/0/amount_minor"), rule(f.copy(amountMinor = 4.5)))
        assertEquals(listOf("/rules/0/amount_minor"), rule(f.copy(amountMinor = -1L)))
        assertEquals(listOf("/rules/0/amount_minor"), rule(f.copy(amountMinor = 9_007_199_254_740_992L)))
        assertEquals(listOf("/rules/0/tax_rate"), rule(f.copy(taxRate = "1.0")))
        assertEquals(listOf("/rules/0/tax_rate"), rule(f.copy(taxRate = 0.07)))
        assertEquals(listOf("/rules/0/tax_rule_revision"), rule(f.copy(taxRuleRevision = null)))
        assertEquals(listOf("/rules/0/zone_from"), rule(f.copy(zoneFrom = UUID.randomUUID().toString())))
        assertEquals(listOf("/rules/0/rate_per_km"), rule(TariffRuleInput(ruleType = "GRUNDPREIS_STRECKE", amountMinor = 100L, taxRuleRevision = "x")))
        assertEquals(listOf("/rules/0/zone_from", "/rules/0/zone_to"), rule(TariffRuleInput(ruleType = "ZONENPREIS", amountMinor = 100L, taxRuleRevision = "x")))
        assertEquals(listOf("/rules"), pointers(ok.copy(rules = listOf(f, f))), "zwei Festpreise")
        assertEquals(listOf("/rules"), pointers(ok.copy(rules = listOf(f, TariffRuleInput(ruleType = "GRUNDPREIS_STRECKE", amountMinor = 1L, ratePerKm = "0.5", taxRuleRevision = "x")))), "gemischte Regeltypen")
        assertEquals(0, fx.adminCount("SELECT count(*) FROM taktovia.tariff_revision WHERE tenant_id = ?", w.tenant.value), "nichts gespeichert")
        assertEquals(0, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ?", w.tenant.value))
    }

    @Test
    fun fremder_mandant_404_ohne_recht_403_audit_vier_augen() {
        val a = fx.world(); val b = fx.world(); val creator = fx.context(a.tenant); val other = fx.context(b.tenant)
        val draft = fx.create(creator, a)
        // fremder Mandant: weder lesbar noch änderbar noch bepreisbar, auch der Dienst ist dort unbekannt
        assertEquals(ErrorCode.NOT_FOUND, fx.problem { fx.tariffs.get(other, draft.id) }.code)
        assertEquals(ErrorCode.NOT_FOUND, fx.problem { fx.tariffs.review(other, draft.id, draft.revision) }.code)
        assertEquals(ErrorCode.NOT_FOUND, fx.problem { fx.tariffs.update(other, draft.id, draft.revision, UpdateTariffRevision(at("2026-01-01T00:00:00Z"), null, "x", listOf(fx.fixedRule()))) }.code)
        assertEquals(ErrorCode.NOT_FOUND, fx.problem { fx.create(other, a) }.code, "Dienst des anderen Mandanten")
        assertTrue(fx.tariffs.list(other, null, null, 10, null).isEmpty())
        assertNull(fx.tariffs.effective(other, a.service, "STANDARD", at("2026-04-01T00:00:00Z")))
        // ohne Funktionsscope 403
        val reader = fx.context(a.tenant, scopes = setOf("tariffs:read"))
        assertEquals(ErrorCode.FORBIDDEN, fx.problem { fx.create(reader, a) }.code)
        assertEquals(ErrorCode.FORBIDDEN, fx.problem { fx.tariffs.review(reader, draft.id, draft.revision) }.code)
        assertEquals(draft.id, fx.tariffs.get(reader, draft.id).id)
        assertEquals(ErrorCode.FORBIDDEN, fx.problem { fx.tariffs.get(fx.context(a.tenant, scopes = emptySet()), draft.id) }.code)
        // Dienstumfang: ohne verwaltende Rolle nur Dienste des eigenen Scopes (sonst wie nicht vorhanden)
        val scoped = fx.context(a.tenant, scopes = setOf("tariffs:write", "tariffs:publish", "tariffs:read"), services = emptySet())
        assertEquals(ErrorCode.NOT_FOUND, fx.problem { fx.create(scoped, a) }.code)
        // Vier-Augen: Ersteller prüft und veröffentlicht nicht selbst, ein Dienstkonto nie
        assertEquals(ErrorCode.FORBIDDEN, fx.problem { fx.tariffs.review(creator, draft.id, draft.revision) }.code)
        assertEquals(ErrorCode.FORBIDDEN, fx.problem { fx.tariffs.review(fx.context(a.tenant, type = ActorType.SERVICE), draft.id, draft.revision) }.code)
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, fx.problem { fx.tariffs.publish(fx.context(a.tenant), draft.id, draft.revision) }.code, "ohne Prüfung keine Veröffentlichung")
        val second = fx.context(a.tenant)
        val reviewed = fx.tariffs.review(second, draft.id, draft.revision)
        assertEquals(TariffState.GEPRUEFT, reviewed.state); assertEquals("user:${second.actor.subject}", reviewed.reviewedBy)
        assertEquals(ErrorCode.FORBIDDEN, fx.problem { fx.tariffs.publish(creator, draft.id, reviewed.revision) }.code)
        assertEquals(ErrorCode.REVISION_MISMATCH, fx.problem { fx.tariffs.publish(second, draft.id, draft.revision) }.code, "veraltete Ressourcenrevision")
        val published = fx.tariffs.publish(second, draft.id, reviewed.revision)
        assertEquals("user:${creator.actor.subject}", published.createdBy); assertEquals(TariffState.VEROEFFENTLICHT, published.state)
        // Audit: genau ein Eintrag je Änderung (angelegt, geprüft, veröffentlicht), Akteur und Revisionsstand
        assertEquals(2, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = ? AND object_ref = ?", a.tenant.value, AuditActions.TARIFF_REVISION_CHANGED, "tariff_revision:${draft.id}"))
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = ? AND object_ref = ? AND actor_ref = ? AND redacted_changes->>'published_by' = ?",
            a.tenant.value, AuditActions.TARIFF_REVISION_PUBLISHED, "tariff_revision:${draft.id}", "user:${second.actor.subject}", "user:${second.actor.subject}"))
        assertEquals(0, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ?", b.tenant.value))
        // Ereignis nach Schema
        val payload = fx.admin.queryForObject("SELECT payload::text FROM taktovia.outbox WHERE tenant_id = ? AND event_type = 'tariff.revision_published'", String::class.java, a.tenant.value)!!
        val schema = SchemaRegistry.withDialect(Dialects.getDraft202012()).getSchema(Files.readString(PaymentTestDb.repoRoot().resolve("api/events/tariff.revision_published.v1.schema.json")))
        assertEquals(emptyList<Any>(), schema.validate(JsonMapper.builder().build().readTree(payload)).map { it.message }, payload)
        // Entwurf ändern: nur mit aktueller Revision, ersetzt Regeln; Liste und Filter
        val d2 = fx.create(creator, a, from = "2030-01-01T00:00:00Z")
        assertEquals(ErrorCode.REVISION_MISMATCH, fx.problem { fx.tariffs.update(creator, d2.id, d2.revision + 5, UpdateTariffRevision(at("2030-02-01T00:00:00Z"), null, "x", listOf(fx.fixedRule(700)))) }.code)
        val d3 = fx.tariffs.update(creator, d2.id, d2.revision, UpdateTariffRevision(at("2030-02-01T00:00:00Z"), at("2031-01-01T00:00:00Z"), "Neu", listOf(fx.fixedRule(700))))
        assertEquals(d2.revision + 1, d3.revision); assertEquals(700, d3.rules.single().amountMinor); assertEquals(at("2030-02-01T00:00:00Z"), d3.validFrom)
        assertEquals(listOf(draft.id, d2.id), fx.tariffs.list(creator, a.service, null, 10, null).map { it.id })
        assertEquals(listOf(d2.id), fx.tariffs.list(creator, null, TariffState.ENTWURF, 10, null).map { it.id })
    }
}
