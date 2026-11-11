"""Lokale TLS-Testserver für die Selbsttests des TLS-Scanners (Zertifikat wird zur Laufzeit mit openssl erzeugt, nichts im Repository)."""
import socket
import ssl
import subprocess
import tempfile
import threading
from pathlib import Path


def make_cert(directory):
    d = Path(directory)
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", str(d / "k.pem"), "-out", str(d / "c.pem"),
                    "-days", "1", "-subj", "/CN=localhost"], check=True, capture_output=True)
    return d / "c.pem", d / "k.pem"


class TlsServer:
    """Antwortet auf HTTP über TLS mit 200 und Nutzdaten; Klartext-Anfragen werden wie von Tomcat/nginx abgewiesen (Verbindung geschlossen)."""

    def __init__(self, minimum, plaintext_ok=False):
        self.tmp = tempfile.TemporaryDirectory()
        cert, key = make_cert(self.tmp.name)
        self.ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        self.ctx.load_cert_chain(cert, key)
        self.ctx.set_ciphers("ALL:@SECLEVEL=0")
        self.ctx.minimum_version = minimum
        self.plaintext_ok = plaintext_ok
        self.sock = socket.socket()
        self.sock.bind(("127.0.0.1", 0))
        self.sock.listen(16)
        self.port = self.sock.getsockname()[1]
        self.stop = False
        threading.Thread(target=self._loop, daemon=True).start()

    def _handle(self, c):
        try:
            c.settimeout(3)
            if self.plaintext_ok:
                c.recv(4096)
                c.sendall(b"HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello")
                return
            with self.ctx.wrap_socket(c, server_side=True) as t:
                t.recv(4096)
                t.sendall(b"HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello")
        except (ssl.SSLError, OSError):
            pass
        finally:
            c.close()

    def _loop(self):
        while not self.stop:
            try:
                c, _ = self.sock.accept()
            except OSError:
                return
            threading.Thread(target=self._handle, args=(c,), daemon=True).start()

    def close(self):
        self.stop = True
        self.sock.close()
        self.tmp.cleanup()
