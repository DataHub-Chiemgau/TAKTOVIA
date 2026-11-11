"""Vergabe der Mangelklassen A/B/C (Lastenheft §11.3, Kap. 08 §8.4). Regel vorläufig; Bestätigung mit WP-M0-33.

Quelle (Kap. 08 §8.4): A (u. a. Verkehrssicherheit, Datenoffenlegung, Doppelbuchung, Ausfall Kernbuchung) blockiert
Produktion; B benötigt einen freigegebenen Workaround und verbindlichen Behebungstermin; C einen Maßnahmenplan.

Regel:
  1. Hat der Mangel mindestens eine A-Kategorie, ist er A. Workaround oder Termin stufen A nie herab.
  2. Sonst ist er B, wenn er eine Kernfunktion beeinträchtigt (impairs_core=True); B ist nur mit freigegebenem Workaround
     und Behebungstermin vertretbar (Hinweis in release_condition); fehlen sie, bleibt die Klasse B, aber die Freigabe
     ist blockiert.
  3. Sonst ist er C (Maßnahmenplan).
Die Klasse wird nur bei Ergebnis FAILED vergeben; bei PASSED ist die Mangelklasse leer.
"""
CLASS_A_CATEGORIES = frozenset({"TRAFFIC_SAFETY", "DATA_DISCLOSURE", "DOUBLE_BOOKING", "CORE_BOOKING_OUTAGE"})


def classify(categories=(), impairs_core=False, workaround_approved=False, remediation_date=False):
    cats = set(categories)
    unknown = cats - CLASS_A_CATEGORIES
    if cats & CLASS_A_CATEGORIES:
        return {"defect_class": "A", "release_blocked": True, "release_condition": "blockiert die Produktion"}
    if impairs_core:
        ok = workaround_approved and remediation_date
        return {"defect_class": "B", "release_blocked": not ok,
                "release_condition": "freigegebener Workaround und verbindlicher Behebungstermin" + ("" if not ok else " (liegen vor)")}
    del unknown
    return {"defect_class": "C", "release_blocked": False, "release_condition": "Maßnahmenplan"}
