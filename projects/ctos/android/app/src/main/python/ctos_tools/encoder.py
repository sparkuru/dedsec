# -*- coding: utf-8 -*-
"""Pure byte transformations and display metadata for the encoder tool."""

import base64
import binascii
import hashlib
import re
from urllib.parse import quote_plus, unquote_plus


def _require_bytes(data: bytes) -> None:
    """Reject input outside the byte-oriented public contract."""
    if not isinstance(data, bytes):
        raise TypeError("Input must be bytes.")


def _decode_base64(data: bytes) -> bytes | None:
    """Decode canonical Base64, allowing whitespace and omitted padding."""
    compact_data = b"".join(data.split())
    if not compact_data:
        return None
    padding = b"=" * (-len(compact_data) % 4)
    try:
        decoded_data = base64.b64decode(compact_data + padding, validate=True)
    except (binascii.Error, ValueError):
        return None
    if base64.b64encode(decoded_data).rstrip(b"=") != compact_data.rstrip(b"="):
        return None
    return decoded_data


def _is_utf8(data: bytes) -> bool:
    """Return whether the bytes decode as UTF-8 without replacement."""
    try:
        data.decode("utf-8")
    except UnicodeDecodeError:
        return False
    return True


def _transform_base64(data: bytes, direction: str) -> bytes:
    """Apply the reference tool's Base64 auto-detection rule."""
    decoded_data = _decode_base64(data)
    if direction == "decode":
        if decoded_data is None:
            raise ValueError("Input is not valid Base64 data.")
        return decoded_data
    if direction == "auto" and decoded_data is not None and (
        b"=" in data or _is_utf8(decoded_data)
    ):
        return decoded_data
    return base64.b64encode(data)


def _replace_unicode_escape(match: re.Match[str]) -> str:
    """Convert a supported Unicode escape to its code point."""
    return chr(int(match.group(1) or match.group(2), 16))


def transform(data: bytes, operation: str, direction: str = "encode") -> bytes:
    """Transform bytes using Base64, URL components, or Unicode escapes.

    URL and Unicode operations require UTF-8 input. Auto mode follows the
    reference tool: percent escapes select URL decoding, and Unicode u/U
    escapes select Unicode decoding. Explicit modes resolve ambiguous input.
    """
    _require_bytes(data)
    if operation not in ("base64", "url", "unicode"):
        raise ValueError("Unsupported operation; choose base64, url, or unicode.")
    if direction not in ("encode", "decode", "auto"):
        raise ValueError("Unsupported direction; choose encode, decode, or auto.")
    if operation == "base64":
        return _transform_base64(data, direction)
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as error:
        raise ValueError(f"{operation} requires UTF-8 text input.") from error

    if operation == "url":
        should_decode = direction == "decode" or (
            direction == "auto" and bool(re.search(r"%[0-9A-Fa-f]{2}", text))
        )
        result = unquote_plus(text) if should_decode else quote_plus(text)
        return result.encode("utf-8")

    pattern = r"\\u([0-9A-Fa-f]{4})|\\U([0-9A-Fa-f]{8})"
    should_decode = direction == "decode" or (
        direction == "auto" and bool(re.search(pattern, text))
    )
    if not should_decode:
        return text.encode("unicode_escape")
    try:
        return re.sub(pattern, _replace_unicode_escape, text).encode("utf-8")
    except (ValueError, UnicodeEncodeError) as error:
        raise ValueError("Input contains an invalid Unicode code point.") from error


def hashes(data: bytes) -> dict[str, str]:
    """Return lowercase MD5, SHA-1, SHA-256, and SHA-512 hex digests."""
    _require_bytes(data)
    return {
        algorithm: hashlib.new(algorithm, data).hexdigest()
        for algorithm in ("md5", "sha1", "sha256", "sha512")
    }


def display(data: bytes) -> dict[str, str | int]:
    """Describe UTF-8 text or lossless binary Base64 for host presentation.

    The host must bound the displayed value; this function preserves all bytes
    so display limits do not affect a separate raw-byte export.
    """
    _require_bytes(data)
    try:
        value = data.decode("utf-8")
    except UnicodeDecodeError:
        return {
            "kind": "binary",
            "encoding": "base64",
            "value": base64.b64encode(data).decode("ascii"),
            "byte_length": len(data),
        }
    return {
        "kind": "text",
        "encoding": "utf-8",
        "value": value,
        "byte_length": len(data),
    }
