# -*- coding: utf-8 -*-
"""Versioned SDK for trusted, APK-bundled ctOS scripts."""

import contextlib
import io
import json
import re
import time
import traceback
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Callable

SDK_VERSION = 2
MAX_LOG_CHARS = 16384
MAX_DATA_BYTES = 32768


@dataclass(frozen=True)
class Parameter:
    """Describe a string parameter rendered and validated by the workbench."""

    name: str
    label: str
    required: bool = True
    max_length: int = 8192
    multiline: bool = False
    default: str = ""
    kind: str = "text"
    choices: tuple[str, ...] = ()
    secret: bool = False


@dataclass(frozen=True)
class Context:
    """Supply task-local snapshots and a private workspace, without Root handles."""

    device: dict
    workdir: Path
    files_root: Path | None = None
    network: dict | None = None

    def section(self, name: str) -> dict:
        """Return a readable device section or report its actual failure."""
        section = self.device.get(name, {})
        if section.get("state") != "available":
            raise RuntimeError(section.get("reason", f"Unavailable section: {name}"))
        return {"source": section["source"], "capturedAt": section["capturedAt"],
                "data": section["data"]}


@dataclass(frozen=True)
class Script:
    """Register metadata and a callable at build time; no dynamic code loading."""

    id: str
    title: str
    description: str
    category: str
    run: Callable[[Context, dict[str, str]], dict]
    parameters: tuple[Parameter, ...] = ()

    def manifest(self) -> dict:
        """Serialize the shared catalogue and parameter form contract."""
        return {"id": self.id, "title": self.title, "description": self.description,
                "category": self.category, "environment": "App", "sdk": SDK_VERSION,
                "parameters": [asdict(parameter) for parameter in self.parameters]}

    def validate(self, values: dict) -> dict[str, str]:
        """Reject unknown, non-string, missing and oversized parameters."""
        known = {parameter.name for parameter in self.parameters}
        if not isinstance(values, dict) or set(values) - known:
            raise ValueError("Unknown parameters")
        return {parameter.name: self._value(parameter, values) for parameter in self.parameters}

    @staticmethod
    def _value(parameter: Parameter, values: dict) -> str:
        """Validate one value against the declared parameter."""
        value = values.get(parameter.name, parameter.default)
        if not isinstance(value, str):
            raise ValueError(f"{parameter.name}: expected text")
        if parameter.required and not value.strip():
            raise ValueError(f"{parameter.name}: required")
        if len(value) > parameter.max_length:
            raise ValueError(f"{parameter.name}: maximum {parameter.max_length} characters")
        if parameter.choices and value not in parameter.choices:
            raise ValueError(f"{parameter.name}: invalid choice")
        if parameter.kind == "file" and value and not re.fullmatch(r"[a-f0-9]{32}\.input", value):
            raise ValueError(f"{parameter.name}: invalid file token")
        return value


class BoundedLog(io.TextIOBase):
    """Keep stdout and stderr memory bounded even when a script is noisy."""

    def __init__(self) -> None:
        self._parts: list[str] = []
        self._length = 0
        self.truncated = False

    def write(self, text: str) -> int:
        """Store only the remaining capacity while retaining stream semantics."""
        remaining = max(0, MAX_LOG_CHARS - self._length)
        if remaining:
            self._parts.append(text[:remaining])
        self._length += min(len(text), remaining)
        self.truncated |= len(text) > remaining
        return len(text)

    def text(self) -> str:
        """Return the retained log."""
        return "".join(self._parts)


def execute(script: Script, context: Context, values: dict) -> dict:
    """Run a script and return a bounded, JSON-compatible task result."""
    started_at = int(time.time() * 1000)
    started = time.monotonic()
    stdout, stderr = BoundedLog(), BoundedLog()
    data, state, exit_code = None, "completed", 0
    with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
        try:
            data = script.run(context, script.validate(values))
            encoded = json.dumps(data, ensure_ascii=False, allow_nan=False)
            if len(encoded.encode("utf-8")) > MAX_DATA_BYTES:
                raise ValueError("Result exceeds 32 KiB")
        except SystemExit as error:
            data = None
            exit_code = error.code if isinstance(error.code, int) else (0 if error.code is None else 1)
            state = "completed" if exit_code == 0 else "failed"
            if exit_code:
                traceback.print_exc()
        except KeyboardInterrupt:
            data, state, exit_code = None, "cancelled", 130
        except Exception:
            data, state, exit_code = None, "failed", 1
            traceback.print_exc()
    return {"script": script.id, "environment": "App", "sdk": SDK_VERSION,
            "startedAt": started_at, "durationMs": int((time.monotonic() - started) * 1000),
            "state": state, "exitCode": exit_code, "data": data,
            "stdout": stdout.text(), "stderr": stderr.text(),
            "truncated": stdout.truncated or stderr.truncated}
