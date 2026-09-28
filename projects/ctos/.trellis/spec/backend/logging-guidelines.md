# Runtime Logging

Keep diagnostics bounded and separate from machine-readable protocols.

- The Python workbench's stdout is a JSON protocol consumed by
  `PythonRunner`. Do not print progress, tracebacks, or debug text there.
  Send user-facing CLI diagnostics to stderr; keep HFTP readiness/event
  records in the documented structured format.
- `HftpLogs` accepts a fixed set of event fields and caps the session buffer at
  200 lines, 512 characters per line, and 32 KiB serialized. Preserve those
  bounds and the service-session ownership check.
- Do not record Authorization headers, request bodies, passwords, query
  strings, full SAF URIs, raw private paths, or unfiltered exception text.
  Sanitize controls and allowlist method, event, and reason values before
  publishing diagnostics.
- Log operational state needed to explain startup, connection, response,
  transfer, failure, and stop. Do not log secrets just to make debugging easier.
- C relay stdout is a bounded machine protocol; stderr is local process
  diagnostics. Never add arbitrary peer payload or user input to either.

Reference files: `HftpLogs.java`, `hftp.py`, `hftp_relay.c`, `HftpService.java`,
and `ctos_tools/cli.py`.
