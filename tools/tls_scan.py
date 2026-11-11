#!/usr/bin/env python3
"""TLS-Scan (WP-M1-36, LH-6.18-SEC-002): welche Protokollversionen nimmt ein Endpunkt an, und beantwortet er Klartext?

  tls_scan.py host:port [host:port ...]

Planungsdefault (OP-31): zulässig sind TLS 1.2 und 1.3; SSLv3, TLS 1.0 und 1.1 gelten als unsicher. Exit 0 = alle Endpunkte
sprechen nur zulässige Versionen und liefern im Klartext keine Nutzdaten.
"""
import socket
import ssl
import sys

SAFE = ("TLSv1.2", "TLSv1.3")
UNSAFE = ("TLSv1", "TLSv1.1")
_VERSIONS = {"TLSv1": ssl.TLSVersion.TLSv1, "TLSv1.1": ssl.TLSVersion.TLSv1_1, "TLSv1.2": ssl.TLSVersion.TLSv1_2, "TLSv1.3": ssl.TLSVersion.TLSv1_3}


def accepts(host, port, version, timeout=5):
    """True, wenn ein Handshake genau mit [version] gelingt (Zertifikat wird nicht geprüft, hier zählt nur das Protokoll)."""
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    try:
        ctx.set_ciphers("ALL:@SECLEVEL=0")   # alte Versionen nur deshalb versuchbar, damit der Server sie abweisen kann
        ctx.minimum_version = ctx.maximum_version = _VERSIONS[version]
    except (ssl.SSLError, ValueError):
        return None   # lokale Bibliothek kann diese Version nicht anbieten: nicht prüfbar
    try:
        with socket.create_connection((host, port), timeout=timeout) as s, ctx.wrap_socket(s, server_hostname=host):
            return True
    except (ssl.SSLError, OSError):
        return False


def plaintext_http_payload(host, port, timeout=5):
    """Antwort auf eine Klartext-HTTP-Anfrage (leer, wenn abgewiesen oder Verbindung geschlossen)."""
    try:
        with socket.create_connection((host, port), timeout=timeout) as s:
            s.settimeout(timeout)
            s.sendall(f"GET / HTTP/1.1\r\nHost: {host}\r\nConnection: close\r\n\r\n".encode())
            data = b""
            while len(data) < 65536:
                chunk = s.recv(4096)
                if not chunk:
                    break
                data += chunk
            return data.decode("latin-1")
    except OSError:
        return ""


def scan(host, port):
    """-> dict(accepted=[…], unsafe=[…], not_testable=[…], plaintext=<Antwort>, ok=bool)."""
    res = {v: accepts(host, port, v) for v in (*UNSAFE, *SAFE)}
    accepted = [v for v, r in res.items() if r]
    unsafe = [v for v in accepted if v in UNSAFE]
    plain = plaintext_http_payload(host, port)
    body = plain.split("\r\n\r\n", 1)[1] if "\r\n\r\n" in plain else ""
    status_ok = plain.startswith(("HTTP/1.1 2", "HTTP/1.0 2"))
    return {"accepted": accepted, "unsafe": unsafe, "not_testable": [v for v, r in res.items() if r is None],
            "plaintext": plain, "ok": bool(accepted) and not unsafe and not status_ok and not body.strip()}


def main(argv):
    rc = 0
    for t in argv[1:]:
        host, _, port = t.rpartition(":")
        r = scan(host, int(port))
        print(f"{t}: angenommen={r['accepted']} unsicher={r['unsafe']} nicht_pruefbar={r['not_testable']} "
              f"klartext_nutzdaten={'ja' if not r['ok'] and r['plaintext'].strip() and r['plaintext'].startswith('HTTP/1.1 2') else 'nein'} -> {'OK' if r['ok'] else 'FEHLER'}")
        rc |= 0 if r["ok"] else 1
    return rc


if __name__ == "__main__":
    sys.exit(main(sys.argv))
