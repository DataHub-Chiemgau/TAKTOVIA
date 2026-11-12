# ruff: noqa: E501
"""WP-M2-18 (OPT-005, harte Grenzen) und WP-M2-19 (OPT-006, Schutz zugesagter Fenster) im Worker.

Szenario: Korridor mit 300 s je Knoten, Umlauf mit Buchung X (Einstieg Knoten 2 um 600, Ausstieg Knoten 5 um 1560). Neue Buchung N fährt von Knoten 1 nach
Knoten 6 (Abholung vor A, Ausstieg nach B): Abholung 300, X-Einstieg 660, X-Ausstieg 1620, N-Ausstieg 1980; Fahrzeit an Bord 1620 s bei 1500 s Direktfahrt (8 %).
"""

from __future__ import annotations

from dataclasses import replace

from insertion_fixtures import MATRIX, PARAMS, empty_vehicle, request, vehicle, x_commitment
from taktovia_routing.insertion import find_insertions, validate_variant

N_ARGS = dict(pe=0, pl=400, ae=0, al=1980)


def n_request(**kw: int):  # type: ignore[no-untyped-def]
    args = {**N_ARGS, **kw}
    return request(1, 6, pe=args["pe"], pl=args["pl"], ae=args["ae"], al=args["al"], wait_ref=0)


def offered(params, req=None, v=None) -> bool:  # type: ignore[no-untyped-def]
    out = find_insertions([v or vehicle()], req or n_request(), params, MATRIX)
    for var in out.variants:
        assert validate_variant(v or vehicle(), req or n_request(), params, MATRIX, var) == []
    return bool(out.variants)


def test_wartezeit_grenze_knapp_innerhalb_und_ausserhalb() -> None:
    assert offered(replace(PARAMS, max_wait_s=300))  # Wartezeit 300 = Grenze
    out = find_insertions([vehicle()], n_request(), replace(PARAMS, max_wait_s=299), MATRIX)
    assert out.variants == [] and out.grounds.get("MAX_WAIT", 0) > 0


def test_umweg_grenze_knapp_innerhalb_und_ausserhalb() -> None:
    assert offered(replace(PARAMS, max_detour_pct=8))  # (1620 - 1500) / 1500 = 8 %
    out = find_insertions([vehicle()], n_request(), replace(PARAMS, max_detour_pct=7), MATRIX)
    assert out.variants == [] and out.grounds.get("MAX_DETOUR", 0) > 0


def test_verspaetung_ankunft_mit_karenz() -> None:
    assert offered(PARAMS, n_request(al=1980))
    assert not offered(PARAMS, n_request(al=1979))
    assert offered(replace(PARAMS, arrival_grace_s=1), n_request(al=1979))  # Zusage plus Karenz
    out = find_insertions([vehicle()], n_request(al=1979), PARAMS, MATRIX)
    assert out.grounds.get("ARRIVAL_WINDOW", 0) > 0


def test_diagnose_der_geprueften_grenzen_je_variante() -> None:
    var = find_insertions(
        [vehicle()], n_request(), replace(PARAMS, max_wait_s=300, max_detour_pct=8), MATRIX
    ).variants[0]
    assert (
        var.checks["wait_s"] == 300
        and var.checks["ride_s"] == 1620
        and var.checks["direct_s"] == 1500
    )
    assert (
        var.checks["arrival_margin_s"] == 0
        and var.checks["max_wait_s"] == 300
        and var.config_revision_id == "cfg-1"
    )


def test_grenzen_bestehender_buchung_gelten_mit_ihrer_revision() -> None:
    # X hat einen Umweg von 0 % zugesagt; die Einfügung zwischen A und B ist unzulässig, egal wie großzügig die neuen Parameter sind
    strikt = vehicle(commitments=(x_commitment(detour=0),))
    out = find_insertions([strikt], n_request(pl=400), replace(PARAMS, max_detour_pct=200), MATRIX)
    assert out.variants  # vor A und nach B ist weiterhin möglich
    nur_mitte = request(3, 4, pe=0, pl=1000, ae=0, al=1400)  # vor A und nach B nicht erreichbar
    mitte = find_insertions([strikt], nur_mitte, replace(PARAMS, max_detour_pct=200), MATRIX)
    assert mitte.variants == [] and mitte.grounds.get("COMMITMENT_MAX_DETOUR", 0) > 0
    # mit der zugesagten Grenze von 50 % ist dieselbe Einfügung zulässig (X: 1020 s statt 900 s an Bord, 13 %)
    locker = find_insertions([vehicle()], nur_mitte, replace(PARAMS, max_detour_pct=200), MATRIX)
    assert locker.variants and locker.variants[0].pos_pickup == 1


def test_zugesagtes_abholfenster_wird_nicht_gerissen() -> None:
    # Einfügung vor A schiebt X von 660 auf 720
    assert offered(PARAMS, v=vehicle(commitments=(x_commitment(pickup_latest=720),)))
    knapp = vehicle(commitments=(x_commitment(pickup_latest=719),))
    out = find_insertions([knapp], request(1, 2, pe=0, pl=400, ae=0, al=700), PARAMS, MATRIX)
    assert out.variants == [] and out.grounds.get("COMMITMENT_PICKUP_WINDOW", 0) > 0


def test_zugesagtes_ankunftsfenster_und_karenz_der_zusage() -> None:
    req = request(1, 2, pe=0, pl=400, ae=0, al=700)
    assert find_insertions(
        [vehicle(commitments=(x_commitment(arrival_latest=1680),))], req, PARAMS, MATRIX
    ).variants
    eng = vehicle(commitments=(x_commitment(arrival_latest=1679),))
    out = find_insertions([eng], req, PARAMS, MATRIX)
    assert out.variants == [] and out.grounds.get("COMMITMENT_ARRIVAL_WINDOW", 0) > 0
    # die Karenz der Zusage gilt, nicht die der neuen Parameter; eine spätere Parameteränderung ändert die Zusage nicht
    mit_karenz = vehicle(commitments=(x_commitment(arrival_latest=1679, arrival_grace_s=1),))
    assert find_insertions([mit_karenz], req, replace(PARAMS, arrival_grace_s=0), MATRIX).variants
    assert not find_insertions([eng], req, replace(PARAMS, arrival_grace_s=3600), MATRIX).variants


def test_leerfahrzeug_ohne_zusagen_wird_nicht_blockiert() -> None:
    assert find_insertions([empty_vehicle()], n_request(), PARAMS, MATRIX).variants
