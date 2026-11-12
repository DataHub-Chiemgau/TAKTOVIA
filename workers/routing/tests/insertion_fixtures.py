# ruff: noqa: E501
"""Gemeinsame Bausteine der Einfügetests (WP-M2-17 bis -19): Linienkorridor mit 300 s je Knotenabstand."""

from __future__ import annotations

from taktovia_routing.insertion import (
    Capacity,
    Commitment,
    Movement,
    Params,
    PlanStop,
    Request,
    VehiclePlan,
)

TENANT = "00000000-0000-4000-8000-00000000000a"
OTHER_TENANT = "00000000-0000-4000-8000-00000000000b"
STEP = 300  # Sekunden je Knotenabstand
N = 10
MATRIX = [[abs(a - b) * STEP for b in range(N)] for a in range(N)]
PARAMS = Params(pooling_allowed=True, stop_dwell_s=60, wheelchair_boarding_s=120, max_wait_s=3600,
                max_detour_pct=100, arrival_grace_s=0, config_revision_id="cfg-1")  # fmt: skip


def mv(b: str, seats: int = 1, wc: int = 0) -> Movement:
    return Movement(b, seats, wc)


def x_commitment(detour: int | None = 50, **kw: int) -> Commitment:
    """Buchung X: Einstieg an Knoten 2 (Planzeit 600), Ausstieg an Knoten 5 (Planzeit 1560)."""
    base = dict(pickup_earliest=600, pickup_latest=1200, arrival_earliest=1500, arrival_latest=2400)
    base.update(kw)
    return Commitment("X", **base, max_detour_pct=detour, direct_s=3 * STEP)


def x_stops() -> tuple[PlanStop, ...]:
    return (PlanStop(2, 600, (mv("X"),)), PlanStop(5, 1560, (), (mv("X"),)))


def vehicle(
    vid: str = "veh-1", *, stops: tuple[PlanStop, ...] | None = None, commitments: tuple[Commitment, ...] | None = None,
    revision: int = 3, run_id: str | None = "run-1", tenant: str = TENANT, seats: int = 4, wc: int = 1,
    until: int = 36000,
) -> VehiclePlan:  # fmt: skip
    return VehiclePlan(
        vid, tenant, run_id, revision, Capacity(seats, wc, frozenset({"RAMP"})), 0, 0, until,
        x_stops() if stops is None else stops, (x_commitment(),) if commitments is None else commitments,
    )  # fmt: skip


def empty_vehicle(vid: str = "veh-empty", tenant: str = TENANT) -> VehiclePlan:
    return VehiclePlan(vid, tenant, None, 0, Capacity(4, 1, frozenset({"RAMP"})), 0, 0, 36000)


def request(pu: int, do: int, *, pe: int = 0, pl: int = 36000, ae: int = 0, al: int = 36000, bid: str = "N", seats: int = 1,
            wc: int = 0, tenant: str = TENANT, wait_ref: int | None = None) -> Request:  # fmt: skip
    return Request(bid, tenant, pu, do, pe, pl, ae, al, seats, wc, frozenset(), wait_ref)
