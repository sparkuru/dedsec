# -*- coding: utf-8 -*-
"""Deterministic password generation compatible with tool 08."""

import base64
import hashlib

DEFAULT_CHARSET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-#."


def generate(seed: str, length: int = 16, salt: str | None = None,
             charset: str = "", must_contain: str = "") -> str:
    """Preserve the original derivation while requiring explicit salt input."""
    if not seed or len(seed) > 8192:
        raise ValueError("Seed must contain 1 to 8192 characters")
    if not 1 <= length <= 128:
        raise ValueError("Length must be between 1 and 128")
    alphabet = charset or DEFAULT_CHARSET
    alphabet += "".join(character for character in must_contain if character not in alphabet)
    if len(alphabet) > 256 or len(set(must_contain)) > length:
        raise ValueError("Character constraints cannot be satisfied")
    salt_value = seed if salt is None else base64.b64encode(salt.encode("utf-8")).decode("ascii")
    if len(salt_value) > 16384:
        raise ValueError("Salt is too long")
    for attempt in range(1000):
        current_seed = f"{seed}_{attempt}"
        result = ""
        while len(result) < length:
            digest = hashlib.sha256(current_seed.encode("utf-8")).digest()
            derived = hashlib.pbkdf2_hmac("sha256", digest, salt_value.encode("utf-8"), 10000)
            result += "".join(alphabet[value % len(alphabet)] for value in derived)
            current_seed = derived.hex()
        result = result[:length]
        if all(character in result for character in must_contain):
            return result
    raise ValueError("Character constraints exceeded the 1000-attempt limit")
