# -*- coding: utf-8 -*-
"""Built-in scripts; add new trusted scripts to registry() to expose an item."""

import hashlib
import platform
import sqlite3
import ssl
import sys

from ctos_sdk import Context, Parameter, Script
from ctos_tools.adapters import scripts


def runtime_check(context: Context, parameters: dict[str, str]) -> dict:
    """Exercise important bundled standard-library modules without networking."""
    with sqlite3.connect(":memory:") as connection:
        value = connection.execute("SELECT 6 * 7").fetchone()[0]
    return {"python": platform.python_version(), "implementation": sys.implementation.name,
            "architecture": platform.machine(), "openssl": ssl.OPENSSL_VERSION,
            "sqlite": sqlite3.sqlite_version, "sqliteCheck": value == 42,
            "sha256Check": hashlib.sha256(b"ctOS").hexdigest(), "offline": True}


def device_info(context: Context, parameters: dict[str, str]) -> dict:
    """Read the Android API snapshot delivered for this task."""
    return context.section("system")


def memory_snapshot(context: Context, parameters: dict[str, str]) -> dict:
    """Return memory values and their original source and capture time."""
    return context.section("memory")


def text_digest(context: Context, parameters: dict[str, str]) -> dict:
    """Compute reproducible UTF-8 text size and SHA-256 locally."""
    text = parameters["text"]
    return {"characters": len(text), "utf8Bytes": len(text.encode("utf-8")),
            "lines": len(text.splitlines()), "sha256": hashlib.sha256(text.encode("utf-8")).hexdigest()}


def registry() -> tuple[Script, ...]:
    """Keep the UI catalogue and execution allowlist in one explicit registry."""
    return (
        Script("python.selftest", "Python self-test", "Check Python and bundled libraries", "runtime", runtime_check),
        Script("device.info", "Device summary", "Model, Android, architecture and uptime", "system", device_info),
        Script("memory.snapshot", "Memory snapshot", "Total, available and low-memory status", "system", memory_snapshot),
        Script("text.digest", "Text digest", "UTF-8 size and SHA-256", "text", text_digest,
               (Parameter("text", "Text", multiline=True),)),
    ) + scripts()
