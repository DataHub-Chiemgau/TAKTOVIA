"""Referenzdatensatz der Stammdateneinspielung: lädt `fixtures/stammdaten_referenz/stammdaten.referenz.json`, setzt Mandant, Aussteller und Benutzerkennungen
und liefert je Variante einen eigenen Datenbestand (Mandant B enthält andere Namen, Kennzeichen und Verweise als Mandant A)."""
import copy
import json
import uuid
from pathlib import Path

BASIS = Path(__file__).resolve().parents[1] / "fixtures" / "stammdaten_referenz" / "stammdaten.referenz.json"
SOLL = {"betreiber": 2, "dienste": 1, "bediengebiete": 1, "haltepunkte": 3, "tarife": 1, "fahrzeuge": 3, "benutzer": 4, "fahrer": 2, "konfiguration": 1}


def datensatz(tenant_id, issuer, variante="A") -> dict:
    text = BASIS.read_text(encoding="utf-8").replace("__TENANT__", str(tenant_id)).replace("__ISSUER__", issuer)
    for n in range(1, 5):
        text = text.replace(f"__SUBJ_{n}__", str(uuid.uuid4()))
    d = json.loads(text)
    if variante != "A":
        for b in d["betreiber"]:
            b["name"] += f" ({variante})"
            b["federation_ref"] += "-" + variante.lower()
        d["dienste"][0]["name"] += f" ({variante})"
        for fz in d["fahrzeuge"]:
            fz["kennzeichen"] = fz["kennzeichen"].replace("XX-", variante * 2 + "-")
    return d


def schreiben(d: dict, pfad) -> str:
    Path(pfad).write_text(json.dumps(d, indent=1, ensure_ascii=False), encoding="utf-8")
    return str(pfad)


def kopie(d: dict) -> dict:
    return copy.deepcopy(d)
