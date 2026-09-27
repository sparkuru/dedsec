# -*- coding: utf-8 -*-
"""Machine JSON protocol for the ctOS native subprocess runner."""

import json
import platform
import sys
from pathlib import Path

from ctos_scripts import registry
from ctos_sdk import Context, SDK_VERSION, execute


def main() -> int:
    """Serve a catalogue or one allowlisted task per Python process."""
    scripts = registry()
    if sys.argv[1:] == ["catalog"]:
        result = {"python": platform.python_version(), "architecture": platform.machine(),
                  "sdk": SDK_VERSION, "offline": True, "scripts": [script.manifest() for script in scripts]}
    elif len(sys.argv) == 3 and sys.argv[1] == "run":
        script = next((script for script in scripts if script.id == sys.argv[2]), None)
        if script is None:
            raise ValueError("Unknown script")
        raw = sys.stdin.buffer.read(32769)
        if len(raw) > 32768:
            raise ValueError("Input exceeds 32 KiB")
        payload = json.loads(raw)
        context = Context(payload["device"], Path(payload["workdir"]),
                          Path(payload["filesRoot"]) if payload.get("filesRoot") else None)
        result = execute(script, context, payload.get("params", {}))
    else:
        raise ValueError("Expected catalog or run SCRIPT_ID")
    sys.stdout.write(json.dumps(result, ensure_ascii=False, allow_nan=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
