# -*- coding: utf-8 -*-
# pip install cryptography==42.0.8
"""Versioned authenticated encryption and explicit tool-02 legacy decryption."""

import base64
import hashlib
import os

from cryptography.hazmat.primitives import padding
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from ctos_tools.files import MAX_FILE_BYTES

MAGIC = b"CTOSENC\x01"
HEADER_BYTES = len(MAGIC) + 16 + 12


def _key(password: str, salt: bytes, iterations: int) -> bytes:
    """Derive an AES-256 key without recording the supplied password."""
    if not password or len(password) > 8192:
        raise ValueError("Password must contain 1 to 8192 characters")
    return hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, iterations, dklen=32)


def encrypt(data: bytes, password: str) -> bytes:
    """Authenticate both the versioned header and encrypted file contents."""
    if len(data) > MAX_FILE_BYTES - HEADER_BYTES - 16:
        raise ValueError("Input is too large for the encrypted envelope")
    salt, nonce = os.urandom(16), os.urandom(12)
    header = MAGIC + salt + nonce
    return header + AESGCM(_key(password, salt, 200000)).encrypt(nonce, data, header)


def decrypt(data: bytes, password: str) -> bytes:
    """Return plaintext only after complete authentication succeeds."""
    if not HEADER_BYTES + 16 <= len(data) <= MAX_FILE_BYTES or not data.startswith(MAGIC):
        raise ValueError("Not a supported ctOS encrypted file")
    header = data[:HEADER_BYTES]
    salt = header[len(MAGIC):len(MAGIC) + 16]
    nonce = header[-12:]
    try:
        return AESGCM(_key(password, salt, 200000)).decrypt(nonce, data[HEADER_BYTES:], header)
    except Exception as error:
        raise ValueError("Incorrect password or damaged encrypted file") from error


def decrypt_legacy(data: bytes, password: str) -> bytes:
    """Read the original base64/CBC format, which has no authentication."""
    if len(data) > MAX_FILE_BYTES:
        raise ValueError("File exceeds 32 MiB")
    try:
        envelope = base64.b64decode(data, validate=True)
        salt = envelope[:16]
        ciphertext = base64.b64decode(envelope[16:], validate=True)
        if len(salt) != 16 or not ciphertext or len(ciphertext) % 16:
            raise ValueError("Invalid legacy envelope")
        decoder = Cipher(algorithms.AES(_key(password, salt, 10000)), modes.CBC(salt)).decryptor()
        padded = decoder.update(ciphertext) + decoder.finalize()
        unpadder = padding.PKCS7(128).unpadder()
        return unpadder.update(padded) + unpadder.finalize()
    except Exception as error:
        raise ValueError("Legacy decryption failed; password or format may be incorrect") from error
