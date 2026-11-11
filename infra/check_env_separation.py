#!/usr/bin/env python3
"""Statische Prüfung der Umgebungstrennung (WP-M1-40, LH-6.18-SEC-012) anhand von infra/umgebungsmatrix.json.

Aufruf: python3 infra/check_env_separation.py     (Exit 0 = keine Verstöße)

Prüft: je Umgebung eigenes Konto, Netz, Datenbankhost, Realm/Issuer, Secret-, PSP- und Identitätsdomäne und eigene Datenbankzugänge (nichts geteilt);
Umgebungskennung = Umgebungsname; Datenklasse und -herkunft (nur Produktion hat Produktionsdaten); kein Netzpfad zwischen Umgebungen und die
Datenbank nur aus dem eigenen Netz; Dumps der Produktion nur in Produktion; Übereinstimmung mit Terraform-Parametern, Bereitstellungsmatrix und
Realm-Exporten; Entwicklungs-Compose und Terraform enthalten keine Bezeichner anderer Umgebungen.
Prüft NICHT (nicht ausführbar hier): reale Netze, Konten, Firewalls, Terraform-Plan.
"""
import csv
import json
import re
import sys
from pathlib import Path

ENVS = ["development", "staging", "performance", "production"]
UNIQUE = [("konto",), ("netz",), ("datenbank", "host"), ("identitaet", "realm"), ("identitaet", "issuer"), ("identitaet", "domaene"),
          ("secrets", "domaene"), ("psp", "domaene"), ("datenbank", "migrationszugang"), ("datenbank", "laufzeitzugang")]


def dig(d, path):
    for k in path:
        d = d[k]
    return d


def tfvars(path):
    out = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        m = re.match(r'\s*(\w+)\s*=\s*"(.*)"', line)
        if m:
            out[m.group(1)] = m.group(2)
    return out


def _identifiers(u):
    """Bezeichner, die nur zu dieser Umgebung gehören (dürfen in Konfiguration anderer Umgebungen nicht vorkommen)."""
    return [dig(u, p) for p in UNIQUE if p != ("identitaet", "domaene")] + [u["identitaet"]["domaene"]]


def check(m, infra):
    infra = Path(infra)
    errs = []
    envs = m.get("umgebungen", {})
    if list(envs) != ENVS:
        return [f"Umgebungsmatrix: erwartet {ENVS}, gefunden {list(envs)}"]
    for key in UNIQUE:
        seen = {}
        for e in ENVS:
            v = dig(envs[e], key)
            if v in seen:
                errs.append(f"{'.'.join(key)}='{v}' in {e} und {seen[v]} geteilt")
            seen[v] = e
    for e, u in envs.items():
        if u["umgebungskennung"] != e:
            errs.append(f"{e}: Umgebungskennung {u['umgebungskennung']!r} ist nicht der Umgebungsname")
        db = u["datenbank"]
        if db["migrationszugang"] == db["laufzeitzugang"]:
            errs.append(f"{e}: Migrations- und Laufzeitzugang der Datenbank gleich")
        if db["erlaubte_quellnetze"] != [u["netz"]]:
            errs.append(f"{e}: Datenbank nur aus dem eigenen Netz erreichbar, Quellnetze sind {db['erlaubte_quellnetze']}")
        if (e == "production") != (u["datenklasse"] == "Produktionsdaten"):
            errs.append(f"{e}: Datenklasse {u['datenklasse']!r}: Produktionsdaten nur in production")
        if e != "production" and "production" in u["erlaubte_dump_herkunft"]:
            errs.append(f"{e}: Dump aus production erlaubt")
        if e == "production" and u["erlaubte_dump_herkunft"] != ["production"]:
            errs.append("production: Dump nur aus production erlaubt")
        if e != "development" and u["identitaet"]["issuer"].startswith("http://"):
            errs.append(f"{e}: Issuer ohne TLS")
        if not u["zugaenge"]:
            errs.append(f"{e}: keine Zugänge beschrieben")
    # Netzpfade: nur innerhalb der eigenen Umgebung
    nets = {envs[e]["netz"]: e for e in ENVS}
    for p in m.get("netzpfade", []):
        if p["von"] != p["nach"]:
            errs.append(f"Netzpfad {p['von']} -> {p['nach']} zwischen Umgebungen ({nets.get(p['von'])} -> {nets.get(p['nach'])}) ist nicht zulässig")
    for e in ENVS:
        if not any(p["von"] == p["nach"] == envs[e]["netz"] for p in m.get("netzpfade", [])):
            errs.append(f"{e}: kein umgebungsinterner Netzpfad beschrieben")
    # Zugänge: kein Entwicklungs-/Testzugang in Produktion
    prod_groups = {z["gruppe"] for z in envs["production"]["zugaenge"]}
    if prod_groups - {"Betrieb"}:
        errs.append(f"production: Zugänge nur für Betrieb, gefunden {sorted(prod_groups)}")
    # Bereitstellungsmatrix (deploy/umgebungsmatrix.csv) und Terraform-Parameter stimmen mit der Matrix überein
    csv_path = infra / "deploy" / "umgebungsmatrix.csv"
    if csv_path.exists():
        with csv_path.open(encoding="utf-8") as fh:
            rows = {r["umgebung"]: r for r in csv.DictReader(fh, delimiter=";")}
        for e in ENVS:
            r = rows.get(e)
            if not r or (r["geheimnisdomaene"], r["identitaetsdomaene"], r["psp_domaene"]) != (envs[e]["secrets"]["domaene"], envs[e]["identitaet"]["domaene"], envs[e]["psp"]["domaene"]):
                errs.append(f"{e}: deploy/umgebungsmatrix.csv weicht von umgebungsmatrix.json ab")
    for e in ENVS:
        f = infra / "terraform" / "envs" / e / "terraform.tfvars.example"
        if f.exists():
            t, u = tfvars(f), envs[e]
            want = {"secret_domain": u["secrets"]["domaene"], "identity_domain": u["identitaet"]["domaene"], "psp_domain": u["psp"]["domaene"],
                    "db_migration_access": u["datenbank"]["migrationszugang"], "db_runtime_access": u["datenbank"]["laufzeitzugang"],
                    "account_domain": u["konto"], "network_domain": u["netz"], "db_ingress_network_domain": u["netz"]}
            for k, v in want.items():
                if t.get(k) != v:
                    errs.append(f"{e}: terraform {k}={t.get(k)!r} weicht von der Matrix ab ({v!r})")
    # Realm-Exporte
    for e in ENVS:
        u = envs[e]
        f = infra / u["identitaet"]["realm_datei"]
        if not f.exists():
            errs.append(f"{e}: Realm-Datei {u['identitaet']['realm_datei']} fehlt")
            continue
        realm = json.loads(f.read_text(encoding="utf-8"))
        if realm.get("realm") != u["identitaet"]["realm"]:
            errs.append(f"{e}: Realm {realm.get('realm')!r} weicht von der Matrix ab")
        if not u["identitaet"]["issuer"].endswith("/realms/" + u["identitaet"]["realm"]):
            errs.append(f"{e}: Issuer passt nicht zum Realmnamen")
        redirects = [r for c in realm.get("clients", []) for r in c.get("redirectUris", [])]
        if e == "development":
            if any(not re.match(r"http://(localhost|127\.0\.0\.1):", r) for r in redirects):
                errs.append("development: Redirect-Ziel außerhalb von localhost")
        else:
            if any(not r.startswith("https://") for r in redirects):
                errs.append(f"{e}: Redirect-Ziel ohne https")
            if realm.get("users") or '"credentials"' in json.dumps(realm) or '"secret"' in json.dumps(realm):
                errs.append(f"{e}: Realm-Export enthält Nutzer, Zugangsdaten oder Client-Secrets")
            if e != "production" and any("/" + "taktovia.example" in r or r.startswith("https://passenger.taktovia") or r.startswith("https://operator.taktovia") for r in redirects):
                errs.append(f"{e}: Redirect-Ziel der Produktion")
    errs += cross_contamination(m, infra)
    return errs


def cross_contamination(m, infra, extra_files=None):
    """Konfiguration einer Umgebung nennt keinen Bezeichner einer anderen: Entwicklungs-Compose, Terraform je Umgebung, Backend-Standardkonfiguration."""
    infra = Path(infra)
    envs = m["umgebungen"]
    scopes = {"development": [infra / "compose"]}
    for e in ENVS:
        scopes.setdefault(e, []).append(infra / "terraform" / "envs" / e)
    files = {}
    for e, dirs in scopes.items():
        for d in dirs:
            for f in (sorted(d.rglob("*")) if d.exists() else []):
                if f.is_file() and f.suffix not in (".pyc",):
                    files[(e, f.relative_to(infra).as_posix())] = f.read_text(encoding="utf-8", errors="ignore")
    for rel, text in (extra_files or {}).items():
        files[("development", rel)] = text
    app_yml = infra.parent / "backend/app/src/main/resources/application.yml"
    if app_yml.exists():
        files[("alle", "backend/app/src/main/resources/application.yml")] = app_yml.read_text(encoding="utf-8")
    errs = []
    for (owner, rel), text in files.items():
        for e in ENVS:
            if e == owner:
                continue
            for ident in _identifiers(envs[e]):
                if ident and ident in text:
                    errs.append(f"{rel} ({owner}) nennt den Bezeichner '{ident}' der Umgebung {e}")
    # kein Produktionsbezeichner irgendwo in der Entwicklungs-Compose-Konfiguration (auch nicht als Wort 'production' in Hostnamen)
    for (owner, rel), text in files.items():
        if owner == "development" and rel.startswith("compose/") and re.search(r"\b[\w.-]*\.production\.[\w.-]+", text):
            errs.append(f"{rel}: Hostname der Produktion in der Entwicklungskonfiguration")
    return errs


def main():
    infra = Path(__file__).resolve().parent
    m = json.loads((infra / "umgebungsmatrix.json").read_text(encoding="utf-8"))
    errs = check(m, infra)
    for e in errs:
        print("FEHLER", e)
    print("Umgebungstrennung:", "FEHLER" if errs else "OK (statisch geprueft)")
    return 1 if errs else 0


if __name__ == "__main__":
    sys.exit(main())
