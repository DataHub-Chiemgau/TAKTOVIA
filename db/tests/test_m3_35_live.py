"""WP-M3-35: Projektion der Outbox-Ereignisse in `live_event` unter der echten Runtime-Rolle. Nur Referenzen und Positivliste skalarer Statusfelder (keine Freitexte, keine
Personenbezüge), Mandantentrennung, Unveränderlichkeit, kein direktes Schreiben durch die Laufzeitrolle, Snapshot-Horizont des Cursors (eine offene ältere Transaktion hält
neuere Einträge zurück, damit kein später sichtbarer Eintrag hinter einem ausgelieferten Cursor liegt)."""
import json, unittest
import psycopg
from helpers import runtime, set_tenant, tx, uid, seed_tenant

A, B = uid(), uid()
OUT = ("INSERT INTO taktovia.outbox(tenant_id,event_id,aggregate_id,aggregate_revision,event_type,schema_version,occurred_at,payload) "
       "VALUES (%s,%s,%s,%s,%s,'1.0',now(),%s::jsonb)")
HORIZON = "SELECT seq FROM taktovia.live_event WHERE tenant_id=%s AND tx < (pg_snapshot_xmin(pg_current_snapshot())::text)::bigint ORDER BY tx, seq"


class LiveProjektion(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm335-a'); seed_tenant(B, 'm335-b')

    def emit(self, tenant, etype, payload, agg=None, rev=1):
        eid = uid()
        with tx(tenant) as c:
            c.execute(OUT, (tenant, eid, agg or uid(), rev, etype, json.dumps(payload)))
        return eid

    def row(self, tenant, eid):
        with tx(tenant) as c:
            return c.execute("SELECT event_type, topic, service_id, shift_id, status::text, aggregate_revision FROM taktovia.live_event WHERE event_id=%s", (eid,)).fetchone()

    def test_statusereignis_wird_in_derselben_transaktion_projiziert(self):
        svc = uid()
        eid = self.emit(A, 'alert.raised', {'service_id': str(svc), 'priority': 'CRITICAL', 'alert_kind': 'VEHICLE_BLOCK', 'state': 'OPEN'}, rev=3)
        t, topic, service, shift, status, rev = self.row(A, eid)
        self.assertEqual((t, topic, service, rev), ('alert.raised', 'alert', svc, 3))
        self.assertEqual(json.loads(status), {'priority': 'CRITICAL', 'alert_kind': 'VEHICLE_BLOCK', 'state': 'OPEN'})

    def test_rollback_der_fachtransaktion_hinterlaesst_kein_kanalereignis(self):
        eid = uid()
        with self.assertRaises(RuntimeError):
            with tx(A) as c:
                c.execute(OUT, (A, eid, uid(), 1, 'alert.raised', '{}'))
                raise RuntimeError('rollback')
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.live_event WHERE event_id=%s", (eid,)).fetchone()[0], 0)

    def test_personenbezug_und_freitext_gelangen_nie_in_den_kanal(self):
        eid = self.emit(A, 'dispatch.run_revised', {'run_id': str(uid()), 'revision': 4, 'cause': 'REPLAN', 'passenger_name': 'Erika Muster', 'note': 'Bitte klingeln, Frau Muster',
                                                    'pickup': {'lat': 51.0, 'lon': 13.7}, 'state': 'Text mit Leerzeichen und Name'})
        status = json.loads(self.row(A, eid)[4])
        self.assertEqual(set(status), {'run_id', 'revision', 'cause'})
        self.assertNotIn('Muster', json.dumps(status))

    def test_ereignisfamilien_ausserhalb_der_positivliste_werden_nicht_projiziert(self):
        for etype in ('passenger.registered', 'payment.captured', 'driver.position_recorded', 'notification.sent'):
            eid = self.emit(A, etype, {'state': 'X'})
            self.assertIsNone(self.row(A, eid), etype)

    def test_unveraenderlich_und_direktes_schreiben_der_laufzeitrolle_wird_abgewiesen(self):
        eid = self.emit(A, 'fleet.vehicle_blocked', {'state': 'ACTIVE'})
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.Error):
                c.execute("UPDATE taktovia.live_event SET service_id=%s WHERE event_id=%s", (uid(), eid))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("INSERT INTO taktovia.live_event(tenant_id,event_id,event_type,topic,aggregate_id,aggregate_revision,occurred_at) VALUES (%s,%s,'a.b','a',%s,1,now())", (A, uid(), uid()))

    def test_mandantentrennung(self):
        eid = self.emit(A, 'fleet.vehicle_blocked', {'state': 'ACTIVE'})
        self.assertIsNone(self.row(B, eid))
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.live_event WHERE event_id=%s", (eid,)).fetchone()[0], 0)
            with self.assertRaises(psycopg.errors.Error):
                c.execute(OUT, (A, uid(), uid(), 1, 'alert.raised', '{}'))

    def test_snapshot_horizont_haelt_neuere_eintraege_hinter_einer_offenen_aelteren_transaktion_zurueck(self):
        # offene ältere Transaktion hält ihr Ereignis noch nicht committet; ein neueres, committetes Ereignis darf nicht vor ihr ausgeliefert werden
        slow = runtime(); set_tenant(slow, A)
        slow_id = uid(); slow.execute(OUT, (A, slow_id, uid(), 1, 'alert.raised', '{}'))   # nimmt Transaktionsnummer und seq, committet noch nicht
        fast_id = self.emit(A, 'alert.raised', {})
        seen = self.visible(A)
        self.assertNotIn(fast_id, seen, 'neuerer Eintrag hinter offener älterer Transaktion darf nicht sichtbar sein')
        slow.commit(); slow.close()
        seen = self.visible(A)
        self.assertEqual([i for i in seen if i in (slow_id, fast_id)], [slow_id, fast_id], 'nach dem Commit erscheinen beide in Transaktionsreihenfolge')

    def visible(self, tenant):
        c = runtime()
        try:
            set_tenant(c, tenant)
            rows = c.execute("SELECT event_id FROM taktovia.live_event WHERE tenant_id=%s AND tx < (pg_snapshot_xmin(pg_current_snapshot())::text)::bigint ORDER BY tx, seq", (tenant,)).fetchall()
            c.rollback()
            return [r[0] for r in rows]
        finally:
            c.close()


if __name__ == '__main__':
    unittest.main()
