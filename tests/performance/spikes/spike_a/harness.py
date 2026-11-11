"""Gleichzeitigkeits- und Messwerkzeug für Spike (a): echte, gleichzeitig offene Datenbankverbindungen (eine je Auftrag).

Zwei Ausführungsarten mit gleicher Schnittstelle run(func, args_list):
  Pool      alle Aufträge als Threads in einem Prozess (genügt für Korrektheitstests; durch die Python-GIL begrenzt)
  ProcPool  Aufträge verteilt auf mehrere Prozesse mit je mehreren Threads (weniger GIL-Verfälschung der Latenz bei 100 Verbindungen)
Aufträge sind in REGISTRY registrierte Funktionen f(conn, *args). Alle Aufträge einer Runde starten gleichzeitig (Barrier).
Ergebnisse, die Ausnahmen sind, werden als RuntimeError-Instanzen zurückgegeben (nie verschluckt).
"""
import multiprocessing as mp
import threading
import traceback

from taktovia_at import db as atdb

REGISTRY = {}
CTL_CONNECTIONS = 6


def task(name):
    def deco(fn):
        REGISTRY[name] = fn
        return fn
    return deco


class Pool:
    kind = "threads"

    def __init__(self, n, dbname):
        self.n, self.dbname = n, dbname
        self.conns = [atdb.runtime_conn(dbname) for _ in range(n)]
        self.ctl = [atdb.runtime_conn(dbname) for _ in range(CTL_CONNECTIONS)]

    def run(self, func, args_list):
        n = len(args_list)
        assert n <= self.n
        fn = REGISTRY[func]
        barrier, results = threading.Barrier(n), [None] * n

        def worker(i):
            barrier.wait()
            try:
                results[i] = fn(self.conns[i], *args_list[i])
            except BaseException as e:  # noqa: BLE001
                results[i] = RuntimeError(repr(e))
        ts = [threading.Thread(target=worker, args=(i,)) for i in range(n)]
        [t.start() for t in ts]
        [t.join() for t in ts]
        return results

    def close(self):
        for c in self.conns + self.ctl:
            try:
                c.close()
            except Exception:  # noqa: BLE001
                pass


def _proc_main(idx, dbname, count, jobs, res, ready, mp_barrier):
    conns = [atdb.runtime_conn(dbname) for _ in range(count)]
    ready.put(idx)
    while True:
        job = jobs.get()
        if job is None:
            break
        func, items = job                      # items: [(global_index, args)]
        fn = REGISTRY[func]
        out, local = [], threading.Barrier(len(items) + 1)

        def worker(slot, gi, args):
            local.wait()
            try:
                r = fn(conns[slot], *args)
            except BaseException as e:  # noqa: BLE001
                r = RuntimeError(repr(e) + traceback.format_exc(limit=2))
            out.append((gi, r))
        ts = [threading.Thread(target=worker, args=(s, gi, a)) for s, (gi, a) in enumerate(items)]
        [t.start() for t in ts]
        mp_barrier.wait()                      # alle Prozesse bereit
        local.wait()                           # Aufträge gleichzeitig starten
        [t.join() for t in ts]
        res.put((idx, out))
    for c in conns:
        c.close()


class ProcPool:
    kind = "processes"

    def __init__(self, n, dbname, nprocs=4):
        ctx = mp.get_context("fork")
        self.n, self.dbname, self.nprocs = n, dbname, nprocs
        counts = [n // nprocs + (1 if p < n % nprocs else 0) for p in range(nprocs)]
        self.jobs = [ctx.Queue() for _ in range(nprocs)]
        self.res, ready = ctx.Queue(), ctx.Queue()
        self.barrier = ctx.Barrier(nprocs)
        self.procs = [ctx.Process(target=_proc_main, args=(p, dbname, counts[p], self.jobs[p], self.res, ready, self.barrier), daemon=True) for p in range(nprocs)]
        [p.start() for p in self.procs]
        for _ in range(nprocs):
            ready.get(timeout=120)
        self.counts = counts
        self.ctl = [atdb.runtime_conn(dbname) for _ in range(CTL_CONNECTIONS)]

    def run(self, func, args_list, timeout=900):
        assert len(args_list) <= self.n
        per = [[] for _ in range(self.nprocs)]
        for i, a in enumerate(args_list):
            per[i % self.nprocs].append((i, a))
        for p in range(self.nprocs):
            assert len(per[p]) <= self.counts[p], "mehr Aufträge als Verbindungen im Prozess"
            self.jobs[p].put((func, per[p]))
        results = [None] * len(args_list)
        for _ in range(self.nprocs):
            _, out = self.res.get(timeout=timeout)
            for gi, r in out:
                results[gi] = r
        return results

    def close(self):
        for q in self.jobs:
            q.put(None)
        for p in self.procs:
            p.join(timeout=30)
        for c in self.ctl:
            try:
                c.close()
            except Exception:  # noqa: BLE001
                pass
