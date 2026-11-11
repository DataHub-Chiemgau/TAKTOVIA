"""Minimaler OSM-PBF-Schreiber (nur Standardbibliothek) und Generator eines synthetischen Straßennetzes für einen lokalen Valhalla-Probelauf.

NUR für den Spike: Das Netz ist ein künstliches Gitter (Wohnstraßen, Hauptstraßen, Einbahnstraßen, Fußgängerzone, Treppe mit wheelchair=no, stufenfreier Weg).
Es sagt nichts über reale Regionen. Das PBF-Format wird hier von Hand kodiert (Protobuf-Varints); ob eine Datei gültig ist, zeigt der Kachelbau von Valhalla.
"""
import struct
import zlib

GRAN = 100          # Nanograd je Einheit (Standard)


def varint(n):
    out = bytearray()
    n &= (1 << 64) - 1
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def zigzag(n):
    return (n << 1) ^ (n >> 63)


def key(field, wt):
    return varint((field << 3) | wt)


def f_varint(field, n):
    return key(field, 0) + varint(n)


def f_bytes(field, b):
    return key(field, 2) + varint(len(b)) + b


def f_packed(field, nums, signed=False):
    body = b"".join(varint(zigzag(n) if signed else n) for n in nums)
    return f_bytes(field, body)


def blob(btype, payload):
    raw = zlib.compress(payload, 6)
    b = f_varint(2, len(payload)) + f_bytes(3, raw)
    head = f_bytes(1, btype.encode()) + f_varint(3, len(b))
    return struct.pack(">I", len(head)) + head + b


class StringTable:
    def __init__(self):
        self.items, self.idx = [b""], {b"": 0}

    def get(self, s):
        b = s.encode()
        if b not in self.idx:
            self.idx[b] = len(self.items)
            self.items.append(b)
        return self.idx[b]

    def encode(self):
        return b"".join(f_bytes(1, s) for s in self.items)


def write_pbf(path, nodes, ways, chunk=8000):
    """nodes: [(id, lat, lon)], ways: [(id, [node ids], {tag: value})]."""
    out = bytearray()
    header = f_bytes(4, b"OsmSchema-V0.6") + f_bytes(4, b"DenseNodes") + f_bytes(16, b"taktovia-spike-e")
    out += blob("OSMHeader", header)
    for i in range(0, len(nodes), chunk):
        part = nodes[i:i + chunk]
        st = StringTable()
        ids, lats, lons = [], [], []
        pi = pla = plo = 0
        for nid, lat, lon in part:
            ids.append(nid - pi)
            pi = nid
            la, lo = round(lat * 1e9 / GRAN), round(lon * 1e9 / GRAN)
            lats.append(la - pla)
            lons.append(lo - plo)
            pla, plo = la, lo
        dense = f_packed(1, ids, True) + f_packed(8, lats, True) + f_packed(9, lons, True)
        group = f_bytes(2, dense)
        out += blob("OSMData", f_bytes(1, st.encode()) + f_bytes(2, group) + f_varint(17, GRAN))
    for i in range(0, len(ways), chunk):
        part = ways[i:i + chunk]
        st = StringTable()
        group = b""
        for wid, refs, tags in part:
            ks, vs = [st.get(k) for k in tags], [st.get(v) for v in tags.values()]
            deltas, prev = [], 0
            for r in refs:
                deltas.append(r - prev)
                prev = r
            way = f_varint(1, wid) + f_packed(2, ks) + f_packed(3, vs) + f_packed(8, deltas, True)
            group += f_bytes(3, way)
        out += blob("OSMData", f_bytes(1, st.encode()) + f_bytes(2, group) + f_varint(17, GRAN))
    with open(path, "wb") as f:
        f.write(bytes(out))
    return len(out)


GRID = {"rows": 120, "cols": 120, "lat0": 51.00, "lon0": 13.70, "dlat": 0.0009, "dlon": 0.0014}
STEPS_PAIR = ((63, 70), (64, 70))          # nur über Treppe verbunden
ZONE_ROW, ZONE_COLS = 61, (56, 64)         # Fußgängerzone (Wohnstraße ersetzt durch highway=pedestrian)
ONEWAY_ROW = 17                            # Einbahnstraße in Richtung steigender Spalte
ISLAND = ((50.9950, 13.7800), (50.9950, 13.7810))   # nicht angebundener Weg (keine Verbindung)


def node_latlon(r, c, g=GRID):
    return g["lat0"] + r * g["dlat"], g["lon0"] + c * g["dlon"]


def synthetic_network(g=GRID):
    """Gitter rows x cols mit konstruierten Fällen: Hauptstraßen (jede 10. Zeile/Spalte), Einbahnstraßen (jede 7. Zeile ab 3), Fußgängerzone, Treppe als einzige
    Verbindung zweier Knoten (wheelchair=no), nicht angebundene Insel. Rein synthetisch, keine reale Region."""
    rows, cols = g["rows"], g["cols"]
    nid = lambda r, c: 1 + r * cols + c  # noqa: E731
    nodes = [(nid(r, c), *node_latlon(r, c, g)) for r in range(rows) for c in range(cols)]
    ways, wid = [], 1

    def add(refs, tags):
        nonlocal wid
        if len(refs) >= 2:
            ways.append((wid, refs, tags))
            wid += 1

    def road(i):
        return {"highway": "secondary" if i % 10 == 0 else "residential", "maxspeed": "50" if i % 10 == 0 else "30"}
    for r in range(rows):
        tags = dict(road(r), **({"oneway": "yes"} if r % 7 == 3 else {}))
        if r == ZONE_ROW:
            add([nid(r, c) for c in range(0, ZONE_COLS[0] + 1)], tags)
            add([nid(r, c) for c in range(ZONE_COLS[0], ZONE_COLS[1] + 1)], {"highway": "pedestrian"})
            add([nid(r, c) for c in range(ZONE_COLS[1], cols)], tags)
        else:
            add([nid(r, c) for c in range(cols)], tags)
    (ra, ca), (rb, cb) = STEPS_PAIR
    for c in range(cols):
        if c == ca:
            add([nid(r, c) for r in range(0, ra + 1)], road(c))
            add([nid(r, c) for r in range(rb, rows)], road(c))
        else:
            add([nid(r, c) for r in range(rows)], road(c))
    add([nid(ra, ca), nid(rb, cb)], {"highway": "steps", "wheelchair": "no"})
    base = 10_000_000
    nodes += [(base + 1, *ISLAND[0]), (base + 2, *ISLAND[1])]
    add([base + 1, base + 2], {"highway": "residential"})
    return nodes, ways
