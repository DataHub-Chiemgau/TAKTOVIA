"""Invariantenabfrage nach einem Lauf (Kap. 04 §4.5), lesend unter der Administratorverbindung über alle Mandanten (Prüfwerkzeug, kein Anwendungspfad):
  1. kein Kapazitätsabschnitt über Kapazität (Summe der wirksamen Holds je Umlauf und Abschnitt <= Sitze bzw. Rollstuhlplätze des Fahrzeugs),
  2. keine doppelte Bestätigung (höchstens eine Buchung je Hold; höchstens eine nicht stornierte Buchung je Hold),
  3. keine Buchung im fremden Mandanten (Hold, Dienst und Fahrgast einer Buchung gehören zum selben Mandanten),
  4. keine verwaisten Verweise (Buchung ohne Hold)."""
from __future__ import annotations

OVER_CAPACITY = """
WITH seg AS (
  SELECT h.tenant_id, h.run_id, g AS segment,
         sum(coalesce((h.capacity_vector->>'seats')::int, 0)) AS seats, sum(coalesce((h.capacity_vector->>'wheelchair')::int, 0)) AS wheelchair
  FROM taktovia.capacity_hold h, LATERAL generate_series(h.interval_from, h.interval_to - 1) g
  WHERE h.state IN ('HELD', 'CONSUMED') AND h.run_id = ANY(%s::uuid[]) GROUP BY 1, 2, 3)
SELECT count(*) FROM seg s JOIN taktovia.vehicle_run r ON r.tenant_id = s.tenant_id AND r.id = s.run_id
JOIN taktovia.vehicle v ON v.tenant_id = r.tenant_id AND v.id = r.vehicle_id
WHERE s.seats > coalesce((v.capacity_config->>'seats')::int, 0) OR s.wheelchair > coalesce((v.capacity_config->>'wheelchair')::int, 0)"""

DOUBLE_CONFIRM = """
SELECT count(*) FROM (SELECT tenant_id, hold_id FROM taktovia.booking WHERE tenant_id = ANY(%s::uuid[]) GROUP BY 1, 2 HAVING count(*) > 1) d"""

DOUBLE_ACTIVE = """
SELECT count(*) FROM (SELECT tenant_id, hold_id FROM taktovia.booking WHERE tenant_id = ANY(%s::uuid[]) AND state NOT IN ('CANCELED') GROUP BY 1, 2 HAVING count(*) > 1) d"""

FOREIGN_TENANT = """
SELECT count(*) FROM taktovia.booking b
WHERE b.tenant_id = ANY(%s::uuid[]) AND (
  NOT EXISTS (SELECT 1 FROM taktovia.capacity_hold h WHERE h.tenant_id = b.tenant_id AND h.id = b.hold_id)
  OR NOT EXISTS (SELECT 1 FROM taktovia.service s WHERE s.tenant_id = b.tenant_id AND s.id = b.service_id)
  OR (b.passenger_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM taktovia.passenger_private p WHERE p.tenant_id = b.tenant_id AND p.id = b.passenger_id)))"""

BOOKING_WITHOUT_HOLD_ANYWHERE = """
SELECT count(*) FROM taktovia.booking b WHERE b.tenant_id = ANY(%s::uuid[]) AND NOT EXISTS (SELECT 1 FROM taktovia.capacity_hold h WHERE h.id = b.hold_id)"""


def check(admin, tenants: list[str], runs: list[str]) -> dict:
    """Liefert die Verstoßzahlen je Invariante (alle 0 = ohne Überbuchung) und die Mengen, über die geprüft wurde."""
    one = lambda sql, arg: admin.execute(sql, (arg,)).fetchone()[0]  # noqa: E731
    out = {
        "kapazitaetsabschnitte_ueber_kapazitaet": one(OVER_CAPACITY, runs),
        "doppelt_bestaetigte_holds": one(DOUBLE_CONFIRM, tenants),
        "holds_mit_mehreren_aktiven_buchungen": one(DOUBLE_ACTIVE, tenants),
        "buchungen_mit_fremdmandantenbezug": one(FOREIGN_TENANT, tenants),
        "buchungen_ohne_hold": one(BOOKING_WITHOUT_HOLD_ANYWHERE, tenants),
    }
    out["geprueft"] = {"mandanten": len(tenants), "umlaeufe": len(runs),
                       "buchungen": admin.execute("SELECT count(*) FROM taktovia.booking WHERE tenant_id = ANY(%s::uuid[])", (tenants,)).fetchone()[0],
                       "holds": admin.execute("SELECT count(*) FROM taktovia.capacity_hold WHERE tenant_id = ANY(%s::uuid[])", (tenants,)).fetchone()[0]}
    out["verstoesse_gesamt"] = sum(v for k, v in out.items() if k != "geprueft")
    return out
