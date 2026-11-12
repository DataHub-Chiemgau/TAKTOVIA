"""Chunkweise Verschlüsselung mit AES-256-GCM (Bibliothek `cryptography`).

Format: MAGIC | key_id (16 Byte) | Nonce-Präfix (8 Byte) | { u32 Länge | Chiffretext(+Tag) }*
Je Chunk: Nonce = Präfix | Zähler(4 Byte), zugehörige Daten = MAGIC | key_id | Zähler | Endekennzeichen. Das Endekennzeichen der letzten Chunks macht
Abschneiden erkennbar, der Zähler Umordnen und Auslassen. Der Schlüssel ist ein eigener Backup-Schlüssel (nie der Betriebsschlüssel der Anwendung).
"""
import hashlib
import hmac
import os
import struct
from pathlib import Path
from typing import BinaryIO, Iterator

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MAGIC = b"TKBK1\n"
CHUNK = 1 << 20


class BackupIntegrityError(Exception):
    """Das Backup ist manipuliert, abgeschnitten oder beschädigt."""


class WrongKeyError(Exception):
    """Der Schlüssel gehört nicht zu diesem Backup."""


def key_id(key: bytes) -> bytes:
    return hmac.new(key, b"taktovia-backup-key-id", hashlib.sha256).digest()[:16]


def generate_key(path: Path) -> bytes:
    """Erzeugt einen zufälligen 256-Bit-Schlüssel und legt ihn mit Modus 0600 ab (nicht überschreiben)."""
    key = os.urandom(32)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(key.hex() + "\n")
    return key


def load_key(path: Path) -> bytes:
    st = os.stat(path)
    if st.st_mode & 0o077:
        raise PermissionError(f"Schlüsseldatei {path} ist für Gruppe oder Andere zugänglich (erwartet 0600)")
    raw = Path(path).read_text().strip()
    key = bytes.fromhex(raw)
    if len(key) != 32:
        raise ValueError("Backup-Schlüssel muss 32 Byte lang sein")
    return key


def _aad(kid: bytes, counter: int, final: bool) -> bytes:
    return MAGIC + kid + struct.pack(">IB", counter, 1 if final else 0)


def encrypt_stream(src: BinaryIO, dst: BinaryIO, key: bytes) -> None:
    aes = AESGCM(key)
    kid = key_id(key)
    prefix = os.urandom(8)
    dst.write(MAGIC + kid + prefix)
    counter = 0
    buf = src.read(CHUNK)
    while True:
        nxt = src.read(CHUNK)
        final = not nxt
        nonce = prefix + struct.pack(">I", counter)
        ct = aes.encrypt(nonce, buf, _aad(kid, counter, final))
        dst.write(struct.pack(">I", len(ct)) + ct)
        if final:
            return
        buf, counter = nxt, counter + 1


def decrypt_chunks(src: BinaryIO, key: bytes) -> Iterator[bytes]:
    """Liefert den Klartext chunkweise; wirft [WrongKeyError] bzw. [BackupIntegrityError] (auch bei abgeschnittener Datei)."""
    head = src.read(len(MAGIC) + 16 + 8)
    if len(head) != len(MAGIC) + 24 or not head.startswith(MAGIC):
        raise BackupIntegrityError("kein Backup im erwarteten Format")
    kid, prefix = head[len(MAGIC):len(MAGIC) + 16], head[len(MAGIC) + 16:]
    if not hmac.compare_digest(kid, key_id(key)):
        raise WrongKeyError("falscher Backup-Schlüssel")
    aes = AESGCM(key)
    counter = 0
    pending = None
    while True:
        raw = src.read(4)
        if raw and len(raw) != 4:
            raise BackupIntegrityError("abgeschnittenes Backup")
        if pending is not None:
            final = not raw
            yield _open(aes, kid, prefix, counter - 1, pending, final)
            if final:
                return
        elif not raw:
            raise BackupIntegrityError("leeres Backup")
        (n,) = struct.unpack(">I", raw)
        if n > CHUNK + 16:
            raise BackupIntegrityError("ungültige Chunkgröße")
        ct = src.read(n)
        if len(ct) != n:
            raise BackupIntegrityError("abgeschnittenes Backup")
        pending = ct
        counter += 1


def _open(aes: AESGCM, kid: bytes, prefix: bytes, counter: int, ct: bytes, final: bool) -> bytes:
    try:
        return aes.decrypt(prefix + struct.pack(">I", counter), ct, _aad(kid, counter, final))
    except InvalidTag as e:
        raise BackupIntegrityError("Backup manipuliert oder beschädigt (Authentifizierung fehlgeschlagen)") from e


def encrypt_file(src: Path, dst: Path, key: bytes) -> None:
    with open(src, "rb") as s, open(dst, "wb") as d:
        encrypt_stream(s, d, key)


def decrypt_file(src: Path, dst: Path, key: bytes) -> None:
    with open(src, "rb") as s, open(dst, "wb") as d:
        for chunk in decrypt_chunks(s, key):
            d.write(chunk)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def decrypt_path_chunks(path: Path, key: bytes) -> Iterator[bytes]:
    """Wie [decrypt_chunks], öffnet und schließt die Datei selbst."""
    with open(path, "rb") as f:
        yield from decrypt_chunks(f, key)
