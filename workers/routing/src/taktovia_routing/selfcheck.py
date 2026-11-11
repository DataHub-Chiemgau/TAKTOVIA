"""OR-Tools-Selbsttest (WP-M0-07 AK 2): importiert OR-Tools und löst ein Trivialproblem."""

from __future__ import annotations

from ortools.constraint_solver import pywrapcp, routing_enums_pb2


def solve_trivial_tsp() -> tuple[list[int], int]:
    """Löst ein 4-Knoten-TSP und liefert (Knotenfolge, Gesamtkosten)."""
    dist = [
        [0, 10, 15, 20],
        [10, 0, 35, 25],
        [15, 35, 0, 30],
        [20, 25, 30, 0],
    ]
    manager = pywrapcp.RoutingIndexManager(4, 1, 0)
    routing = pywrapcp.RoutingModel(manager)
    cb = routing.RegisterTransitMatrix(dist)
    routing.SetArcCostEvaluatorOfAllVehicles(cb)
    params = pywrapcp.DefaultRoutingSearchParameters()
    params.first_solution_strategy = routing_enums_pb2.FirstSolutionStrategy.PATH_CHEAPEST_ARC
    solution = routing.SolveWithParameters(params)
    if solution is None:
        raise RuntimeError("Trivialproblem nicht lösbar")
    nodes: list[int] = []
    index = routing.Start(0)
    while not routing.IsEnd(index):
        nodes.append(manager.IndexToNode(index))
        index = solution.Value(routing.NextVar(index))
    nodes.append(manager.IndexToNode(index))
    return nodes, int(solution.ObjectiveValue())


if __name__ == "__main__":
    print(solve_trivial_tsp())
