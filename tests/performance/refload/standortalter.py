"""Auswertung der Standortfrische (WP-M5-08, LH-6.19-NFR-005) aus den Beobachtungen des Leitstellen-Beobachters (`runner.location_observer`): Was zeigt die Anzeige (UI-07, Fahrzeugliste) je Fahrzeug?

Messpunkt der Kette „Erfassung → Empfang → Anzeige“ (Kap. 08 §8.6):
  Erfassung  = Messzeit des Geräts (`position_measured_at`),
  Empfang    = Serverempfangszeit (`position_received_at`),
  Anzeige    = Antwort der Fahrzeugliste; das Standortalter `position_age_seconds` berechnet der Server gegen seine Uhr (nie das Gerät, nie der Browser).
Die Auswertung trennt „Normalbetrieb“ (Fahrzeuge mit laufendem Positionsstrom) von künstlich unterbrochenen Strömen (Veraltet-Nachweis). Alle Zielwerte sind vorläufig (D-05 offen, OP-11)."""
from __future__ import annotations

from datetime import datetime

from . import aload

THRESHOLD_S = 30
STATUS_ZIEL = "vorläufig, D-05 offen"


def _ts(s: str | None) -> float | None:
    return datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp() if s else None


def _pct(values: list[float], p: float) -> float | None:
    return round(aload.percentile(sorted(values), p), 3) if values else None


def evaluate(obs: list[dict], bounds: list[dict], warmup_s: float, rigs: dict[str, list], stride: int, threshold_s: int = THRESHOLD_S) -> dict:
    """[rigs] je Mandant die Fahrerzugänge (Index i, `vehicle_id`); jedes [stride]-te Fahrzeug ist unterbrochen (ab Aufwärmende ohne Meldung)."""
    own = {t: {r.vehicle_id for r in rs} for t, rs in rigs.items()}
    interrupted = {t: {r.vehicle_id for i, r in enumerate(rs) if stride > 0 and i % stride == 0} for t, rs in rigs.items()}
    all_interrupted = set().union(*interrupted.values()) if interrupted else set()
    late_probe = {r.vehicle_id for t, rs in rigs.items() for i, r in enumerate(rs) if stride > 0 and i % stride == 1}
    measured = [o for o in obs if o["t"] >= warmup_s]

    def stage_of(t: float) -> str:
        for b in bounds:
            if b["t_start"] <= t < b["t_end"]:
                return b["name"]
        return bounds[-1]["name"]

    per_stage = {}
    for b in bounds:
        normal = [o for o in measured if stage_of(o["t"]) == b["name"] and o["vehicle_id"] not in all_interrupted and o["age_s"] is not None]
        ages = [float(o["age_s"]) for o in normal]
        delays = [_ts(o["received_at"]) - _ts(o["measured_at"]) for o in normal if o["received_at"] and o["measured_at"]]
        no_pos = sum(1 for o in measured if stage_of(o["t"]) == b["name"] and o["vehicle_id"] not in all_interrupted and o["age_s"] is None)
        per_stage[b["name"]] = {
            "faktor": b["factor"], "beobachtungen": len(normal), "ohne_position": no_pos, "alter_p50_s": _pct(ages, 50), "alter_p95_s": _pct(ages, 95), "alter_max_s": max(ages) if ages else None,
            "anteil_ueber_30s": round(sum(1 for a in ages if a > threshold_s) / len(ages), 5) if ages else None, "ueberschreitungen_ueber_30s": sum(1 for a in ages if a > threshold_s),
            "erfassung_bis_empfang_p95_s": _pct(delays, 95), "als_veraltet_gekennzeichnet": sum(1 for o in normal if o["stale"]),
        }
    # Lücken je Fahrzeug im Normalbetrieb: Abstand aufeinanderfolgender verschiedener Messzeiten größer als die Schwelle (Meldelücke des Stroms, wie die Anzeige sie sieht)
    series: dict[str, list[float]] = {}
    for o in obs:
        if o["measured_at"] and o["vehicle_id"] not in all_interrupted and o["t"] >= warmup_s:
            series.setdefault(o["vehicle_id"], []).append(_ts(o["measured_at"]))
    gaps = []
    for v, ts in series.items():
        distinct = sorted(set(ts))
        gaps += [b - a for a, b in zip(distinct, distinct[1:]) if b - a > threshold_s]
    # unterbrochene Ströme: die Anzeige muss sie als veraltet kennzeichnen, mit unveränderter Zeit der letzten Position (keine Fortschreibung)
    by_vehicle: dict[str, list[dict]] = {}
    for o in obs:
        if o["vehicle_id"] in all_interrupted:
            by_vehicle.setdefault(o["vehicle_id"], []).append(o)
    stale_marked, stale_total, unchanged = 0, 0, 0
    for v, os_ in by_vehicle.items():
        late = [o for o in os_ if o["age_s"] is not None and o["age_s"] > threshold_s + 2]
        stale_total += len(late)
        stale_marked += sum(1 for o in late if o["stale"] and o["state"] == "STALE")
        unchanged += 1 if len({o["measured_at"] for o in os_ if o["measured_at"]}) == 1 else 0
    # Reihenfolge: je Fahrzeug darf die angezeigte Messzeit nie zurückgehen (verspätete ältere Meldung verdrängt die neuere nicht)
    regress = 0
    for v in {o["vehicle_id"] for o in obs}:
        seq = [_ts(o["measured_at"]) for o in sorted((o for o in obs if o["vehicle_id"] == v), key=lambda o: o["t"]) if o["measured_at"]]
        regress += sum(1 for a, b in zip(seq, seq[1:]) if b < a)
    # Datentrennung: der Beobachter eines Mandanten sieht ausschließlich Fahrzeuge dieses Mandanten
    foreign = {t: len({o["vehicle_id"] for o in obs if o["tenant"] == t and o["vehicle_id"] not in own[t]}) for t in own}
    seen = {t: len({o["vehicle_id"] for o in obs if o["tenant"] == t}) for t in own}
    with_pos = {t: len({o["vehicle_id"] for o in obs if o["tenant"] == t and o["measured_at"]}) for t in own}
    return {
        "protokoll_version": "1.0", "norm": "LH-6.19-NFR-005", "ziel": f"frische Positionen jünger als {threshold_s} s im Normalbetrieb", "status_zielwert": STATUS_ZIEL,
        "messpunkt": "Standortalter der Fahrzeugliste (UI-07), vom Server gegen seine Uhr berechnet; Erfassung = Messzeit des Geräts, Empfang = Serverempfangszeit",
        "normalbetrieb": "Fahrzeuge mit laufendem Positionsstrom (Meldeintervall des Lastprofils, Standard 15 s); Planungsannahme bis D-05/OP-11",
        "je_stufe": per_stage, "luecken_im_normalbetrieb": {"anzahl": len(gaps), "groesste_s": round(max(gaps), 1) if gaps else 0.0, "definition": f"Abstand aufeinanderfolgender Messzeiten eines Fahrzeugs > {threshold_s} s"},
        "unterbrochene_stroeme": {"fahrzeuge": len(all_interrupted), "beobachtet": len(by_vehicle), "beobachtungen_ueber_schwelle": stale_total, "davon_als_veraltet_gekennzeichnet": stale_marked, "fahrzeuge_mit_unveraenderter_messzeit": unchanged},
        "reihenfolge": {"fahrzeuge_mit_verspaeteter_aelterer_meldung": len(late_probe), "rueckgang_der_angezeigten_messzeit": regress},
        "datentrennung": {"fremde_fahrzeuge_in_der_anzeige_je_mandant": foreign, "fahrzeuge_je_mandant_beobachtet": seen, "fahrzeuge_mit_position_je_mandant": with_pos},
        "beobachtungen_gesamt": len(obs), "beobachtungen_nach_aufwaermen": len(measured),
    }


def render_md(ev: dict, counters: dict, ref_stage: str) -> str:
    L = ["# Messprotokoll Standortfrische (WP-M5-08, LH-6.19-NFR-005)", "", f"- Zielwert: {ev['ziel']} (**{ev['status_zielwert']}**)", f"- Messpunkt: {ev['messpunkt']}", f"- Normalbetrieb: {ev['normalbetrieb']}", "",
         "## Standortalter je Stufe (Normalbetrieb)", "", "| Stufe | Beobachtungen | ohne Position | Alter p50 s | Alter p95 s | Alter max s | Anteil > 30 s | Überschreitungen | Erfassung→Empfang p95 s | als veraltet gekennzeichnet |", "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|"]
    for k, s in ev["je_stufe"].items():
        L.append(f"| {k}{' (Referenz)' if k == ref_stage else ''} | {s['beobachtungen']} | {s['ohne_position']} | {s['alter_p50_s']} | {s['alter_p95_s']} | {s['alter_max_s']} | {s['anteil_ueber_30s']} | {s['ueberschreitungen_ueber_30s']} | {s['erfassung_bis_empfang_p95_s']} | {s['als_veraltet_gekennzeichnet']} |")
    L += ["", f"## Lücken im Normalbetrieb", "", f"{ev['luecken_im_normalbetrieb']['anzahl']} Lücken ({ev['luecken_im_normalbetrieb']['definition']}), größte {ev['luecken_im_normalbetrieb']['groesste_s']} s.", "", "## Unterbrochene Ströme (künstlich veraltet)", "",
          f"{ev['unterbrochene_stroeme']['fahrzeuge']} Fahrzeuge ohne Meldung ab Aufwärmende; {ev['unterbrochene_stroeme']['davon_als_veraltet_gekennzeichnet']} von {ev['unterbrochene_stroeme']['beobachtungen_ueber_schwelle']} Beobachtungen über der Schwelle waren als veraltet gekennzeichnet; Messzeit unverändert (keine Fortschreibung) bei {ev['unterbrochene_stroeme']['fahrzeuge_mit_unveraenderter_messzeit']} von {ev['unterbrochene_stroeme']['beobachtet']} Fahrzeugen.",
          "", "## Reihenfolge und Datentrennung", "", f"Rückgang der angezeigten Messzeit: {ev['reihenfolge']['rueckgang_der_angezeigten_messzeit']} (Fahrzeuge mit verspäteter älterer Meldung: {ev['reihenfolge']['fahrzeuge_mit_verspaeteter_aelterer_meldung']}). Fremde Fahrzeuge in der Anzeige je Mandant: {ev['datentrennung']['fremde_fahrzeuge_in_der_anzeige_je_mandant']}.",
          "", "## Negativproben unter Last (Zähler)", "", "```", str({k: v for k, v in sorted(counters.items()) if k.startswith('pos_probe') or k.startswith('beobachter')}), "```", "",
          "Grenzen: Generator, Anwendung, PostgreSQL und Test-Issuer teilen sich eine Maschine; Werte gelten für diese Umgebung, nicht als Abnahme (D-05 offen). Sendeintervall und Schwelle sind Planungsannahmen (D-05 GPS-Frequenz, OP-11)."]
    return "\n".join(L) + "\n"
