# Bootstrap specification review — 2026-09-28

- Mapped the actual layers: Flutter/Dart under `lib/`, Android Java under
  `android/app/src/main/java/`, the PTY/Python launcher/HFTP relay in C, and
  the bundled Python SDK/tools under `android/app/src/main/python/`.
- Replaced framework-neutral placeholders with source-backed directory,
  state, component, type, error, logging, persistence, host-bridge, and quality
  rules. Retained the existing connection, terminal, Python workbench, and
  product-copy contracts.
- Removed React hook and ORM/database templates that do not match this app;
  documented the actual SharedPreferences, private token-file, SAF, and
  process-local log behavior instead.
- Checked that every file linked by backend/frontend indexes exists and that
  every backend/frontend spec is indexed. Checked every spec index and local
  Markdown link, then searched for copied fill-in/template markers; none
  remain. The word “placeholder” in connection guidance describes an
  intentional display fallback, not unfinished documentation.
- Documentation-only change. No product test, APK build, installation, or
  device operation was run.
