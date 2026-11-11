#!/usr/bin/env python3
"""Statische Pruefung von infra/ (WP-M0-08 bis -11, -35). Braucht keinen Docker-Daemon, kein Terraform.

Aufruf: python3 infra/check.py [infra-verzeichnis]
Prueft NICHT (nicht ausfuehrbar hier): laufende Dienste, Healthchecks, Realm-Import in Keycloak, terraform plan.
Exit 0 = alle statischen Pruefungen gruen.
"""
import csv
import json
import pathlib
import re
import shutil
import subprocess
import sys

import yaml

PRIV_ROLES = {"dispatcher", "finance", "tenant_admin"}
ALL_ROLES = {"passenger", "dispatcher", "driver", "finance", "tenant_admin"}
CORE_SERVICES = ["postgres", "rabbitmq", "redis", "keycloak", "minio", "valhalla"]
ENVS = ["development", "staging", "performance", "production"]
MODULE_REQUIRED_VARS = {"environment", "hosting_provider", "hosting_region", "secret_domain", "identity_domain",
                        "psp_domain", "db_migration_access", "db_runtime_access", "data_class", "account_domain", "network_domain",
                        "db_ingress_network_domain"}
SECRET_PATTERNS = [re.compile(p) for p in (r"-----BEGIN [A-Z ]*PRIVATE KEY-----", r"AKIA[0-9A-Z]{16}", r"ghp_[A-Za-z0-9]{20,}",
                                           r"xox[bp]-[0-9A-Za-z-]{10,}")]


def check_compose(root, errs, notes):
    d = root / "compose"
    dc = d / "docker-compose.yml"
    data = yaml.safe_load(dc.read_text(encoding="utf-8"))
    svcs = data["services"]
    for s in CORE_SERVICES + ["otel-collector", "prometheus"]:
        if s not in svcs:
            errs.append(f"compose: Dienst {s} fehlt")
            continue
        if not svcs[s].get("profiles"):
            errs.append(f"compose: {s} ohne Profil")
        for p in svcs[s].get("ports", []):
            if not str(p).startswith("127.0.0.1:"):
                errs.append(f"compose: {s} Port {p} nicht an 127.0.0.1 gebunden")
    for s in CORE_SERVICES + ["prometheus"]:
        if s in svcs and "healthcheck" not in svcs[s]:
            errs.append(f"compose: {s} ohne Healthcheck")
    for s in CORE_SERVICES:
        if s in svcs and "all" not in svcs[s].get("profiles", []):
            errs.append(f"compose: {s} nicht im Profil all")
    env = d / ".env.example"
    for line in env.read_text(encoding="utf-8").splitlines():
        m = re.match(r"([A-Z_]*(PASSWORD|PASS|SECRET)[A-Z_]*)=(.*)", line)
        if m and not m.group(3).startswith("dev-only"):
            errs.append(f"compose: {m.group(1)} in .env.example ist kein 'dev-only'-Platzhalter")
    if not (d / ".gitignore").read_text().count(".env"):
        errs.append("compose: .env nicht in .gitignore")
    for sh in d.glob("*.sh"):
        r = subprocess.run(["bash", "-n", str(sh)], capture_output=True, text=True)
        if r.returncode:
            errs.append(f"compose: Syntaxfehler {sh.name}: {r.stderr.strip()}")
    # docker compose config (nur Parsing/Interpolation, kein Daemon)
    if shutil.which("docker"):
        r = subprocess.run(["docker", "compose", "-f", str(dc), "--env-file", str(env), "--profile", "all",
                            "--profile", "observability", "config", "--format", "json"], capture_output=True, text=True)
        if r.returncode:
            errs.append(f"compose: 'docker compose config' fehlgeschlagen: {r.stderr.strip()[:300]}")
        else:
            got = set(json.loads(r.stdout)["services"])
            if got != set(CORE_SERVICES) | {"otel-collector", "prometheus"}:
                errs.append(f"compose: unerwartete Diensteliste {sorted(got)}")
            notes.append("docker compose config: ok")
        # Fehlendes Pflicht-Passwort muss abbrechen (negativ)
        r2 = subprocess.run(["docker", "compose", "-f", str(dc), "--env-file", "/dev/null", "--profile", "all", "config", "-q"],
                            capture_output=True, text=True)
        if r2.returncode == 0:
            errs.append("compose: fehlendes Pflicht-Passwort bricht 'config' nicht ab")
    else:
        notes.append("docker CLI fehlt: 'docker compose config' uebersprungen")
    # Imageangaben: Platzhalter/ungeprueft muessen gekennzeichnet sein
    if "nicht gegen Registry geprüft" not in dc.read_text(encoding="utf-8"):
        errs.append("compose: Kennzeichnung 'nicht gegen Registry geprüft' fehlt")


def check_realm(root, errs, notes):
    p = root / "keycloak" / "realm-taktovia-dev.json"
    realm = json.loads(p.read_text(encoding="utf-8"))
    clients = {c["clientId"]: c for c in realm["clients"]}
    for c in ("passenger-web", "operator-web", "driver-app"):
        if c not in clients:
            errs.append(f"realm: Client {c} fehlt")
        elif clients[c].get("attributes", {}).get("pkce.code.challenge.method") != "S256" or not clients[c]["publicClient"]:
            errs.append(f"realm: {c} ohne PKCE S256/public")
    machine = [c for c in clients.values() if c["clientId"].startswith("adapter-")]
    if len(machine) < 5:
        errs.append("realm: zu wenige Maschinen-Clients")
    for c in machine:
        if not c.get("serviceAccountsEnabled") or not str(c.get("secret", "")).startswith("dev-only"):
            errs.append(f"realm: {c['clientId']} ohne Service-Account oder Secret nicht 'dev-only'")
    role_names = {r["name"] for r in realm["roles"]["realm"]}
    if role_names != ALL_ROLES:
        errs.append(f"realm: Rollen {sorted(role_names)}")
    for r in realm["roles"]["realm"]:
        want = "true" if r["name"] in PRIV_ROLES else "false"
        if r["attributes"].get("mfa_required") != [want]:
            errs.append(f"realm: mfa_required der Rolle {r['name']} falsch")
    users = {u["username"]: u for u in realm["users"]}
    tenants = {}
    for t in "ab":
        for role in ALL_ROLES:
            u = users.get(f"{t}.{role}")
            if not u:
                errs.append(f"realm: Testakteur {t}.{role} fehlt")
                continue
            tenants.setdefault(t, set()).update(u["attributes"]["tenant_ids"])
    if tenants.get("a") == tenants.get("b") or any(len(v) != 1 for v in tenants.values()):
        errs.append("realm: Mandanten A/B nicht getrennt")
    if "ab.dispatcher" not in users or len(users["ab.dispatcher"]["attributes"]["tenant_ids"]) != 2:
        errs.append("realm: Akteur mit beiden Mandanten fehlt")
    if "none.passenger" not in users or users["none.passenger"]["attributes"]["tenant_ids"]:
        errs.append("realm: Akteur ohne Mitgliedschaft fehlt")
    for u in users.values():
        role = (u["realmRoles"] or [None])[0]
        want = "true" if role in PRIV_ROLES else "false"
        if u["attributes"]["mfa_required"] != [want]:
            errs.append(f"realm: mfa_required von {u['username']} falsch")
        if not u["email"].endswith("@example.test"):
            errs.append(f"realm: {u['username']} nicht synthetische Mail")
        for cr in u["credentials"]:
            if not cr["value"].startswith("dev-only-"):
                errs.append(f"realm: Passwort von {u['username']} nicht 'dev-only'")
    notes.append(f"realm: {len(users)} Nutzer, {len(clients)} Clients geprueft")


def check_observability(root, errs, notes):
    o = root / "observability"
    otel = yaml.safe_load((o / "otel-collector-config.yaml").read_text(encoding="utf-8"))
    if "otlp" not in otel["receivers"]:
        errs.append("otel: OTLP-Receiver fehlt")
    if "attributes/pii_guard" not in otel["processors"]:
        errs.append("otel: PII-Filter fehlt")
    for name, pl in otel["service"]["pipelines"].items():
        if "attributes/pii_guard" not in pl["processors"]:
            errs.append(f"otel: Pipeline {name} ohne PII-Filter")
        for comp in pl["receivers"] + pl["exporters"] + pl["processors"]:
            sect = {"receivers": pl["receivers"], "exporters": pl["exporters"], "processors": pl["processors"]}
            for k, names in sect.items():
                for n in names:
                    if n not in otel.get(k, {}):
                        errs.append(f"otel: {k[:-1]} {n} in Pipeline {name} nicht definiert")
    prom = yaml.safe_load((o / "prometheus.yml").read_text(encoding="utf-8"))
    if not prom.get("scrape_configs"):
        errs.append("prometheus: keine scrape_configs")
    rules = yaml.safe_load((o / "alert-rules.yml").read_text(encoding="utf-8"))
    if "PLATZHALTER" not in json.dumps(rules):
        errs.append("alert-rules: Alarmkanal nicht als PLATZHALTER gekennzeichnet")
    notes.append("observability: YAML gueltig, Pipelines konsistent")


def tfvars(path):
    out = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        m = re.match(r'\s*(\w+)\s*=\s*"(.*)"', line)
        if m:
            out[m.group(1)] = m.group(2)
    return out


def declared_vars(path):
    return set(re.findall(r'variable\s+"(\w+)"', path.read_text(encoding="utf-8")))


def check_terraform(root, errs, notes):
    t = root / "terraform"
    modvars = t / "modules/environment/variables.tf"
    text = modvars.read_text(encoding="utf-8")
    missing = MODULE_REQUIRED_VARS - declared_vars(modvars)
    if missing:
        errs.append(f"terraform: Modulvariablen fehlen: {sorted(missing)}")
    if re.search(r"^\s*default\s*=", text, re.M):
        errs.append("terraform: Modulvariable mit Default (Pflichtparameter duerfen keinen haben)")
    seen = {}
    for e in ENVS:
        d = t / "envs" / e
        for f in ("main.tf", "variables.tf", "terraform.tfvars.example", "backend.tf.example"):
            if not (d / f).exists():
                errs.append(f"terraform: envs/{e}/{f} fehlt")
        if not (d / "variables.tf").exists():
            continue
        need = declared_vars(d / "variables.tf")
        main = (d / "main.tf").read_text(encoding="utf-8")
        for v in MODULE_REQUIRED_VARS - {"environment", "data_class"}:
            if v not in need or f"var.{v}" not in main:
                errs.append(f"terraform: envs/{e} reicht {v} nicht durch")
        if not re.search(rf'^\s*environment\s*=\s*"{e}"', main, re.M):
            errs.append(f"terraform: envs/{e} setzt environment falsch")
        vals = tfvars(d / "terraform.tfvars.example")
        for v in need:
            if v not in vals:
                errs.append(f"terraform: envs/{e}/terraform.tfvars.example ohne {v}")
        if vals.get("db_migration_access") == vals.get("db_runtime_access"):
            errs.append(f"terraform: {e} Migrations- und Laufzeitzugang gleich")
        for k in ("secret_domain", "identity_domain", "psp_domain", "db_migration_access", "db_runtime_access", "account_domain", "network_domain"):
            v = vals.get(k)
            if v in seen:
                errs.append(f"terraform: {k}={v} in {e} und {seen[v]} geteilt")
            seen[v] = e
        if vals.get("hosting_provider") not in ("unset",) and e == "development":
            errs.append("terraform: Hostinganbieter fest verdrahtet (D-05 offen)")
    # Hosting nicht im Code verteilt: Anbieterbegriffe ausserhalb der Parameterdateien
    for f in t.rglob("*.tf"):
        if re.search(r'\b(aws|azurerm|google|hcloud|ionos|ovh)\b', f.read_text(encoding="utf-8"), re.I):
            errs.append(f"terraform: Anbieterbezug in {f.relative_to(root)} (D-05 offen)")
    notes.append("terraform: statische Struktur geprueft (terraform validate NICHT ausgefuehrt)")


def check_deploy(root, errs, notes):
    d = root / "deploy"
    for name in ("umgebungsmatrix.csv", "geheimnisse.csv"):
        rows = list(csv.DictReader((d / name).open(encoding="utf-8"), delimiter=";"))
        if not rows:
            errs.append(f"deploy: {name} leer")
        n = len(rows[0])
        if any(None in r or len(r) != n for r in rows):
            errs.append(f"deploy: {name} Spaltenzahl uneinheitlich")
        if name == "umgebungsmatrix.csv" and [r["umgebung"] for r in rows] != ENVS:
            errs.append("deploy: Umgebungsmatrix deckt die vier Umgebungen nicht ab")
        if name == "geheimnisse.csv":
            st = {r["geheimnis"]: r["zugang"] for r in rows}
            if "Migration" not in st.get("db_migration_credential", "") or "Laufzeit" not in st.get("db_runtime_credential", ""):
                errs.append("deploy: Migrations-/Laufzeitgeheimnis nicht getrennt zugeordnet")
    if "NICHT" not in (d / "ablauf.md").read_text(encoding="utf-8") and "Nichts davon wurde ausgeführt" not in (d / "ablauf.md").read_text(encoding="utf-8"):
        errs.append("deploy: ablauf.md ohne Kennzeichnung 'nicht ausgefuehrt'")
    yaml.safe_load((d / "pipeline-entwurf.yml.example").read_text(encoding="utf-8"))
    notes.append("deploy: CSV/YAML gueltig")


def check_secrets(root, errs, notes):
    n = 0
    for f in root.rglob("*"):
        if not f.is_file() or f.suffix in (".pyc",) or "__pycache__" in f.parts or f.name == "check.py" or f.name == "test_check.py":
            continue
        try:
            t = f.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        n += 1
        for p in SECRET_PATTERNS:
            if p.search(t):
                errs.append(f"secret-Muster in {f.relative_to(root)}: {p.pattern}")
        if f.name == ".env":
            errs.append(f"{f.relative_to(root)}: echte .env im Repository")
    notes.append(f"secret-Muster: {n} Dateien durchsucht")


def check_gateway(root, errs, notes):
    """WP-M1-36: TLS-Mindestversion, HSTS, geschlossener Klartext-Port, Upstreams nur per TLS (nicht gegen einen laufenden nginx geprueft)."""
    g = (root / "gateway" / "nginx.conf.template").read_text(encoding="utf-8")
    if not re.search(r"ssl_protocols\s+TLSv1\.2\s+TLSv1\.3\s*;", g) or re.search(r"TLSv1(\.[01])?\b(?!\.)", re.sub(r"TLSv1\.[23]", "", g)):
        errs.append("gateway: ssl_protocols nicht genau TLSv1.2/TLSv1.3")
    if not re.search(r'add_header\s+Strict-Transport-Security\s+"max-age=\d{7,}[^"]*"\s+always', g):
        errs.append("gateway: HSTS fehlt")
    m = re.search(r"listen 80[^;]*;.*?return\s+444\s*;", g, re.S)
    if not m or "proxy_pass" in m.group(0):
        errs.append("gateway: Klartext-Port 80 nicht mit 'return 444' geschlossen")
    if re.search(r"proxy_pass\s+http://", g) or re.search(r"return\s+30[12]\s+http://", g):
        errs.append("gateway: Klartext-Upstream oder Weiterleitung auf Klartext")
    if re.search(r"ssl_certificate(_key)?\s+/", g):
        errs.append("gateway: Zertifikat/Schluessel mit festem Pfad statt Secret-Einhaengung")
    notes.append("gateway: TLS-Konfiguration statisch geprueft (nginx NICHT gestartet)")


def check_alerting(root, errs, notes):
    """WP-M1-47: genau ein Empfaengerkanal, Adresse nur als eingehaengte Secret-Datei, Regeln auf Metriken des Backends."""
    am = yaml.safe_load((root / "alerting" / "alertmanager.yml").read_text(encoding="utf-8"))
    recv = am.get("receivers", [])
    if len(recv) != 1 or am["route"]["receiver"] != recv[0]["name"]:
        errs.append("alerting: nicht genau ein Empfaengerkanal")
    for r in recv:
        for w in r.get("webhook_configs", []):
            if "url" in w or "url_file" not in w:
                errs.append("alerting: Empfaengeradresse nicht als url_file (Secret-Datei)")
    rules = yaml.safe_load((root / "observability" / "operator-alert-rules.yml").read_text(encoding="utf-8"))
    exprs = " ".join(r["expr"] for g in rules["groups"] for r in g["rules"])
    if "taktovia_operator_warnings_total" not in exprs or "taktovia_operator_alert_pending_deliveries" not in exprs:
        errs.append("alerting: Regeln nutzen die Betreiberwarn-Metriken nicht")
    if "operator-alert-rules.yml" not in (root / "observability" / "prometheus.yml").read_text(encoding="utf-8"):
        errs.append("alerting: prometheus.yml bindet operator-alert-rules.yml nicht ein")
    notes.append("alerting: Alertmanager-/Regelkonfiguration statisch geprueft (nicht gestartet)")


def check_env_separation(root, errs, notes):
    """WP-M1-40: Umgebungsmatrix (umgebungsmatrix.json) und Trennung von Konten, Netzen, Identitaeten und Secrets je Umgebung."""
    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
    import check_env_separation as ces
    m = json.loads((root / "umgebungsmatrix.json").read_text(encoding="utf-8"))
    errs.extend(f"umgebungstrennung: {e}" for e in ces.check(m, root))
    notes.append("umgebungstrennung: Matrix, Terraform-Parameter, Realms und Compose statisch geprueft (reale Netze/Konten NICHT)")


def run(root):
    errs, notes = [], []
    for fn in (check_compose, check_realm, check_observability, check_terraform, check_deploy, check_secrets, check_gateway, check_alerting, check_env_separation):
        try:
            fn(root, errs, notes)
        except Exception as e:  # fehlende Datei etc. = Fehler, kein Absturz
            errs.append(f"{fn.__name__}: {type(e).__name__}: {e}")
    return errs, notes


def main():
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else __file__).resolve()
    if root.is_file():
        root = root.parent
    errs, notes = run(root)
    for n in notes:
        print("ok   ", n)
    for e in errs:
        print("FEHLER", e)
    print("NICHT AUSGEFUEHRT: laufende Dienste, Healthchecks, Keycloak-Import, terraform plan/validate")
    print("ERGEBNIS:", "ROT" if errs else "GRUEN")
    return 1 if errs else 0


if __name__ == "__main__":
    sys.exit(main())
