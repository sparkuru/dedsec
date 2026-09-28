# Persistence

ctOS currently has no Room, SQLite, or other database. Do not describe an
in-memory snapshot, bounded log, or temporary tool file as durable history.

- Small HFTP settings are stored through `HftpConfig` in private
  `SharedPreferences`. Root auto-start preference is separately owned by
  `MainActivity`. Keep validation and defaults in those owners; do not duplicate
  them in Flutter.
- Tool input and output files use `ToolFiles`' private token store. Tokens are
  opaque to Flutter; SAF import/export is explicit. New output commits must
  preserve existing files and use the existing exclusive-commit path.
- HFTP logs are bounded in-memory diagnostics for the current service session.
  They are not persisted or exported automatically.
- There is no persisted task-result/history feature. Before adding one, define
  retention limits, cleanup behavior, secret handling, and schema/migration
  ownership in a task and `design/`; choose storage only after that design.

Reference files: `HftpConfig.java`, `MainActivity.java`, `ToolFiles.java`,
`HftpLogs.java`, `ctos_tools/files.py`, and `design/constraints.md`.
