"""Berichtsgenerator „Monatsverfügbarkeit“ (WP-M5-05, Detailvertrag LH-6.19-NFR-001).

Formel (je Kalendermonat, je Bereich getrennt; Zahlen in Sekunden, Ausweis in Minuten):

    Verfügbarkeit = (Messminuten − Ausfallminuten außerhalb Wartung) / (Messminuten − Wartungsminuten)

 * Der Zeitraum wird in Intervalle der Länge `probenintervall_s` zerlegt; ein Messwert gilt für das Intervall, in dem sein Zeitpunkt liegt. Mehrere Werte im Intervall: der schlechteste gilt
   (fehler_plattform vor fehler_provider vor ok vor keine_messung nur, wenn alle Werte keine_messung sind).
 * Messminuten: Intervalle mit Messwert ok, fehler_plattform oder fehler_provider. `keine_messung` und fehlende Werte sind keine Messminuten (weder verfügbar noch Ausfall).
 * Ausfallminuten: fehler_plattform. `fehler_provider` wird getrennt ausgewiesen und nicht zum Ausfall gerechnet (Messgrenze, D-05 offen).
 * Wartungsminuten: Messminuten innerhalb eines vorab eingetragenen Wartungsfensters (`eingetragen_am` vor `von`); sie zählen weder als Ausfall noch im Nenner.
 * Ziel: mindestens 99,9 %. Unterschreitet die Abdeckung die Mindestabdeckung, lautet die Aussage `unzureichende_abdeckung` und es gibt kein erreicht/verfehlt.
Alle Zielwerte sind vorläufige Lastenheftziele (Kap. 08 §8.6, D-05).
"""
import hashlib
import json
import math
from datetime import datetime, timedelta, timezone
from pathlib import Path

from .proben import BEREICHE, ERGEBNISSE, FEHLER_PLATTFORM, FEHLER_PROVIDER, KEINE_MESSUNG, OK

SCHEMA_VERSION = "verfuegbarkeitsbericht.v1"
FORMEL = "Verfügbarkeit = (Messminuten − Ausfallminuten außerhalb Wartung) / (Messminuten − Wartungsminuten)"
RANG = {KEINE_MESSUNG: 0, OK: 1, FEHLER_PROVIDER: 2, FEHLER_PLATTFORM: 3}
HERE = Path(__file__).resolve().parents[1]


def lade_vertrag(pfad: Path | None = None) -> dict:
    return json.loads((pfad or HERE / "messpunktvertrag.json").read_text(encoding="utf-8"))


def lade_wartung(pfad: Path | None = None) -> list[dict]:
    return json.loads((pfad or HERE / "wartungskalender.json").read_text(encoding="utf-8"))["fenster"]


def utc(s) -> datetime:
    if isinstance(s, datetime):
        return s.astimezone(timezone.utc)
    t = datetime.fromisoformat(s.replace("Z", "+00:00"))
    return (t.replace(tzinfo=timezone.utc) if t.tzinfo is None else t).astimezone(timezone.utc)


def monat_zeitraum(monat: str) -> tuple[datetime, datetime]:
    """`2026-11` -> [1. November 00:00 UTC, 1. Dezember 00:00 UTC). Der Kalendermonat wird in UTC gezählt (Arbeitswert, D-05)."""
    j, m = (int(x) for x in monat.split("-"))
    von = datetime(j, m, 1, tzinfo=timezone.utc)
    return von, datetime(j + (m == 12), 1 if m == 12 else m + 1, 1, tzinfo=timezone.utc)


def lade_rohdaten(pfad: Path) -> tuple[list[dict], str]:
    raw = Path(pfad).read_bytes()
    saetze = []
    for n, zeile in enumerate(raw.decode("utf-8").splitlines(), 1):
        if not zeile.strip():
            continue
        s = json.loads(zeile)
        if s.get("bereich") not in BEREICHE or s.get("ergebnis") not in ERGEBNISSE or "zeitpunkt" not in s:
            raise ValueError(f"Rohdatenzeile {n} ungültig (Bereich, Ergebnis oder Zeitpunkt)")
        saetze.append(s)
    return saetze, hashlib.sha256(raw).hexdigest()


def _vereinigung(fenster: list[tuple[int, int]]) -> list[tuple[int, int]]:
    out: list[list[int]] = []
    for a, b in sorted(fenster):
        if out and a <= out[-1][1]:
            out[-1][1] = max(out[-1][1], b)
        else:
            out.append([a, b])
    return [(a, b) for a, b in out]


def _ueberlappung(a: int, b: int, wartung: list[tuple[int, int]]) -> int:
    return sum(max(0, min(b, y) - max(a, x)) for x, y in wartung)


def _min(sek: int) -> float:
    return round(sek / 60.0, 3)


def _laeufe(slots: list[dict], ziel_ergebnis: str, wartung) -> list[dict]:
    """Zusammenhängende Intervalle gleichen Ergebnisses als Ereignisse (Ausfall, Anbieterfehler, Lücke)."""
    out, cur = [], None
    for s in slots:
        if s["ergebnis"] == ziel_ergebnis:
            if cur and cur["ende"] == s["a"]:
                cur["ende"] = s["b"]
            else:
                cur = {"start": s["a"], "ende": s["b"]}
                out.append(cur)
        else:
            cur = None
    res = []
    for e in out:
        gesamt = e["ende"] - e["start"]
        wart = _ueberlappung(e["start"], e["ende"], wartung)
        res.append({"von": datetime.fromtimestamp(e["start"], timezone.utc).isoformat(timespec="seconds"), "bis": datetime.fromtimestamp(e["ende"], timezone.utc).isoformat(timespec="seconds"),
                    "dauer_min": _min(gesamt), "davon_wartung_min": _min(wart), "zaehlt_min": _min(gesamt - wart)})
    return res


def berechne_bereich(saetze: list[dict], von: datetime, bis: datetime, intervall_s: int, wartung: list[tuple[int, int]], ziel: float, mindestabdeckung: float) -> dict:
    t0, t1 = int(von.timestamp()), int(bis.timestamp())
    n = math.ceil((t1 - t0) / intervall_s)
    zustand: list[str | None] = [None] * n
    ausserhalb = 0
    for s in saetze:
        i = (int(utc(s["zeitpunkt"]).timestamp()) - t0) // intervall_s
        if i < 0 or i >= n:
            ausserhalb += 1
            continue
        if zustand[i] is None or RANG[s["ergebnis"]] > RANG[zustand[i]]:
            zustand[i] = s["ergebnis"]
    slots = []
    for i in range(n):
        a, b = t0 + i * intervall_s, min(t1, t0 + (i + 1) * intervall_s)
        slots.append({"a": a, "b": b, "ergebnis": zustand[i] or KEINE_MESSUNG, "fehlt": zustand[i] is None})
    mess = ausfall = provider = wart_mess = keine = fehlend = 0
    for s in slots:
        laenge, w = s["b"] - s["a"], _ueberlappung(s["a"], s["b"], wartung)
        if s["ergebnis"] == KEINE_MESSUNG:
            keine += laenge - w
            fehlend += (laenge - w) if s["fehlt"] else 0
            continue
        mess += laenge
        wart_mess += w
        if s["ergebnis"] == FEHLER_PLATTFORM:
            ausfall += laenge - w
        elif s["ergebnis"] == FEHLER_PROVIDER:
            provider += laenge - w
    zeitraum = t1 - t0
    wart_gesamt = _ueberlappung(t0, t1, wartung)
    basis = zeitraum - wart_gesamt
    nenner = mess - wart_mess
    abdeckung = 100.0 * nenner / basis if basis > 0 else 0.0
    v = 100.0 * (nenner - ausfall) / nenner if nenner > 0 else None
    v_provider = 100.0 * (nenner - ausfall - provider) / nenner if nenner > 0 else None
    if v is None or abdeckung < mindestabdeckung:
        aussage, erreicht = "unzureichende_abdeckung", None
    else:
        erreicht = v >= ziel
        aussage = "ziel_erreicht" if erreicht else "ziel_verfehlt"
    return {
        "messminuten": _min(mess), "wartungsminuten": _min(wart_mess), "ausfallminuten": _min(ausfall), "providerfehlerminuten": _min(provider), "keine_messung_minuten": _min(keine),
        "fehlende_intervalle": sum(1 for s in slots if s["fehlt"]), "rohdatensaetze_ausserhalb_zeitraum": ausserhalb,
        "abdeckung_prozent": round(abdeckung, 4), "verfuegbarkeit_prozent": None if v is None else round(v, 4),
        "verfuegbarkeit_inkl_providerfehler_prozent": None if v_provider is None else round(v_provider, 4),
        "ziel_prozent": ziel, "ziel_erreicht": erreicht, "aussage": aussage,
        "ausfaelle": _laeufe(slots, FEHLER_PLATTFORM, wartung), "providerfehler": _laeufe(slots, FEHLER_PROVIDER, wartung),
        "luecken": [x for x in _laeufe([s for s in slots if s["fehlt"]], KEINE_MESSUNG, wartung)][:100],
    }


def erzeuge_bericht(rohdaten: list[dict], von: datetime, bis: datetime, vertrag: dict, wartung_kalender: list[dict], rohdaten_verweis: str, rohdaten_sha256: str,
                    erzeugt_am: datetime | None = None, umgebung: str = "staging") -> dict:
    von, bis = utc(von), utc(bis)
    intervall = int(vertrag["probenintervall_s"])
    fenster, wartung_sek = [], []
    for f in wartung_kalender:
        a, b = utc(f["von"]), utc(f["bis"])
        vorab = utc(f["eingetragen_am"]) < a
        ueberlappt = max(0, min(int(b.timestamp()), int(bis.timestamp())) - max(int(a.timestamp()), int(von.timestamp())))
        fenster.append({"von": a.isoformat(timespec="seconds"), "bis": b.isoformat(timespec="seconds"), "grund": f.get("grund", ""), "eingetragen_am": utc(f["eingetragen_am"]).isoformat(timespec="seconds"),
                        "vorab_eingetragen": vorab, "minuten_im_zeitraum": _min(ueberlappt), "herausgerechnet": vorab})
        if vorab:
            wartung_sek.append((int(a.timestamp()), int(b.timestamp())))
    wartung_union = _vereinigung(wartung_sek)
    bereiche = {}
    for b in BEREICHE:
        bereiche[b] = berechne_bereich([s for s in rohdaten if s["bereich"] == b], von, bis, intervall, wartung_union, float(vertrag["ziel_verfuegbarkeit_prozent"]),
                                       float(vertrag["mindestabdeckung_prozent"]))
        bereiche[b]["bezeichnung"] = vertrag["bereiche"][b]["bezeichnung"]
    hinweise = ["Zielwerte und Messgrenzen sind vorläufig, D-05 offen (Kap. 08 §8.6).",
                "Ein voller Produktivmonat ist erst im Pilotbetrieb nachweisbar (OP-02); dieser Bericht belegt das Messverfahren über den angegebenen Zeitraum."]
    voller_monat = (von.day, von.hour, von.minute, von.second) == (1, 0, 0, 0) and monat_zeitraum(von.strftime("%Y-%m")) == (von, bis)
    if not voller_monat:
        hinweise.append("Der Zeitraum ist kein voller Kalendermonat (Testzeitraum).")
    return {
        "schema_version": SCHEMA_VERSION, "erzeugt_am": (erzeugt_am or datetime.now(timezone.utc)).astimezone(timezone.utc).isoformat(timespec="seconds"), "umgebung": umgebung,
        "zeitraum": {"von": von.isoformat(timespec="seconds"), "bis": bis.isoformat(timespec="seconds"), "kalendermonat": von.strftime("%Y-%m") if voller_monat else None},
        "formel": FORMEL,
        "messpunkte": [{"bereich": b, "pfade": vertrag["bereiche"][b]["pfade"], "erfolgskriterium": vertrag["bereiche"][b]["erfolgskriterium"]} for b in BEREICHE],
        "probenintervall_s": intervall, "messgrenze": vertrag["messgrenze"], "fehlende_proben": vertrag["fehlende_proben"], "wartungsregel": vertrag["wartungsregel"],
        "status_zielwerte": "vorläufig, D-05 offen", "mindestabdeckung_prozent": float(vertrag["mindestabdeckung_prozent"]),
        "wartungsfenster": fenster, "bereiche": bereiche,
        "rohdaten": {"verweis": rohdaten_verweis, "sha256": rohdaten_sha256, "saetze": len(rohdaten)}, "hinweise": hinweise,
    }


def als_markdown(b: dict) -> str:
    z = b["zeitraum"]
    titel = f"Monatsverfügbarkeit {z['kalendermonat']}" if z["kalendermonat"] else f"Verfügbarkeit im Testzeitraum {z['von']} bis {z['bis']}"
    out = [f"# {titel}", "",
           f"Umgebung: {b['umgebung']} · erzeugt {b['erzeugt_am']} · Probenintervall {b['probenintervall_s']} s · Status der Zielwerte: **{b['status_zielwerte']}**", "",
           f"Formel: `{b['formel']}`", "", "| Bereich | Verfügbarkeit | Ziel | Aussage | Messminuten | Ausfallminuten | Wartungsminuten | Anbieterfehler-Min. | keine Messung (Min.) | Abdeckung |", "|---|---:|---:|---|---:|---:|---:|---:|---:|---:|"]
    for k, v in b["bereiche"].items():
        vp = "–" if v["verfuegbarkeit_prozent"] is None else f"{v['verfuegbarkeit_prozent']:.4f} %"
        out.append(f"| {v['bezeichnung']} | {vp} | {v['ziel_prozent']} % | {v['aussage']} | {v['messminuten']} | {v['ausfallminuten']} | {v['wartungsminuten']} | {v['providerfehlerminuten']} | {v['keine_messung_minuten']} | {v['abdeckung_prozent']} % |")
    out += ["", "## Messpunkte"] + [f"- **{m['bereich']}**: {', '.join(m['pfade'])}. {m['erfolgskriterium']}" for m in b["messpunkte"]]
    out += ["", "## Wartungsfenster"] + ([f"- {w['von']} bis {w['bis']} ({w['grund']}): {w['minuten_im_zeitraum']} min im Zeitraum, {'herausgerechnet' if w['herausgerechnet'] else 'nachträglich eingetragen, NICHT herausgerechnet'}" for w in b["wartungsfenster"]] or ["- keine"])
    for k, v in b["bereiche"].items():
        out += ["", f"## Ausfälle {v['bezeichnung']}"] + ([f"- {a['von']} bis {a['bis']}: {a['dauer_min']} min (davon Wartung {a['davon_wartung_min']} min, gezählt {a['zaehlt_min']} min)" for a in v["ausfaelle"]] or ["- keine"])
        if v["providerfehler"]:
            out += [f"Anbieterfehler (getrennt, nicht in den Ausfallminuten): " + "; ".join(f"{a['von']} bis {a['bis']} {a['dauer_min']} min" for a in v["providerfehler"])]
    out += ["", "## Rohdaten", f"{b['rohdaten']['verweis']} (SHA-256 {b['rohdaten']['sha256']}, {b['rohdaten']['saetze']} Sätze)", "", "## Messgrenze", b["messgrenze"], "", "## Fehlende Proben", b["fehlende_proben"], "", "## Wartungsregel", b["wartungsregel"], "", "## Hinweise"] + [f"- {h}" for h in b["hinweise"]]
    return "\n".join(out) + "\n"
