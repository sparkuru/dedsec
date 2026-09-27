# -*- coding: utf-8 -*-
"""Workbench metadata and host-capability adapters for portable cores."""

import importlib

from ctos_sdk import Context, Parameter, Script
from ctos_tools import encoder, ip_lookup, password
from ctos_tools.files import read_input, write_artifact


def _input(context: Context, values: dict[str, str]) -> bytes:
    """Read an explicit selected file or the supplied UTF-8 text."""
    if values.get("file"):
        return read_input(context.files_root, values["file"])
    return values.get("text", "").encode("utf-8")


def password_run(context: Context, values: dict[str, str]) -> dict:
    """Generate without persisting the seed, salt or result."""
    salt = values["salt"] or None
    if values["salt_file"]:
        salt = read_input(context.files_root, values["salt_file"]).decode("utf-8").strip()
        if not salt:
            raise ValueError("Selected salt file is empty")
    generated = password.generate(values["seed"], int(values["length"]), salt,
                                  values["charset"], values["must"])
    return {"password": generated, "length": len(generated), "sensitive": True}


def encoder_run(context: Context, values: dict[str, str]) -> dict:
    """Present a bounded preview and preserve complete byte output separately."""
    data = _input(context, values)
    if values["operation"] == "hash":
        return {"bytes": len(data), "hashes": encoder.hashes(data)}
    output = encoder.transform(data, values["operation"], values["direction"])
    preview = encoder.display(output[:4096])
    return {"bytes": len(output), "preview": preview, "previewTruncated": len(output) > 4096,
            "artifact": write_artifact(context.files_root, output, "converted.bin")}


def ip_run(context: Context, values: dict[str, str]) -> dict:
    """Make a network request only after explicit workbench submission."""
    return ip_lookup.lookup(values["target"])


def crypto_run(context: Context, values: dict[str, str]) -> dict:
    """Commit output only after encryption or complete decryption succeeds."""
    data = read_input(context.files_root, values["file"])
    crypto = importlib.import_module("ctos_tools.crypto")
    operation = values["operation"]
    handlers = {"encrypt": crypto.encrypt, "decrypt": crypto.decrypt,
                "legacy-decrypt": crypto.decrypt_legacy}
    result = handlers[operation](data, values["password"])
    name = "encrypted.ctos" if operation == "encrypt" else "decrypted.bin"
    return {"artifact": write_artifact(context.files_root, result, name),
            "format": "legacy CBC (unauthenticated)" if operation == "legacy-decrypt" else "ctOS AES-256-GCM v1",
            "warning": "Legacy CBC cannot verify file authenticity" if operation == "legacy-decrypt" else ""}


def hftp_run(context: Context, values: dict[str, str]) -> dict:
    """Keep persistent services outside the short-task execution protocol."""
    raise ValueError("Start HFTP using the foreground-service page or terminal CLI")


def scripts() -> tuple[Script, ...]:
    """Declare exactly the five requested portable tools."""
    return (
        Script("tools.password", "08 Password generator", "Deterministic passwords with explicit salt", "tools", password_run, (
            Parameter("seed", "Seed", secret=True),
            Parameter("length", "Length (1-128)", default="16", max_length=3),
            Parameter("salt", "Salt (optional)", required=False, secret=True),
            Parameter("salt_file", "Salt file (optional)", required=False, kind="file"),
            Parameter("charset", "Character set (optional)", required=False, max_length=256),
            Parameter("must", "Required characters (optional)", required=False, max_length=128),
        )),
        Script("tools.encoder", "26 Encoder", "Base64, URL, Unicode and file hashes", "tools", encoder_run, (
            Parameter("operation", "Operation", default="base64", kind="choice", choices=("base64", "url", "unicode", "hash")),
            Parameter("direction", "Direction", default="encode", kind="choice", choices=("encode", "decode", "auto")),
            Parameter("text", "Text (used when no file selected)", required=False, multiline=True),
            Parameter("file", "Input file (optional)", required=False, kind="file"),
        )),
        Script("tools.ip", "09 IP lookup", "User-triggered HTTPS lookup; empty target queries public IP", "tools", ip_run, (
            Parameter("target", "IP or DNS name (optional)", required=False, max_length=253),
        )),
        Script("tools.crypto", "02 File encryption", "Authenticated encryption and explicit legacy decryption", "tools", crypto_run, (
            Parameter("operation", "Operation", default="encrypt", kind="choice", choices=("encrypt", "decrypt", "legacy-decrypt")),
            Parameter("file", "Input file", kind="file"),
            Parameter("password", "Password", secret=True),
        )),
        Script("tools.hftp", "16 HFTP", "Private file library with a visible background service", "tools", hftp_run),
    )
