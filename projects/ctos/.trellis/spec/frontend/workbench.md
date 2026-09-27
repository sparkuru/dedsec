# Workbench interaction contract

- Navigation is Overview / Information / Workbench / Terminal. Workbench
  item metadata and parameter forms come from the Python catalogue, not a
  duplicate Dart execution registry.
- Load the catalogue lazily on first entry. Show preparation, failure and
  retry states. Preserve the catalogue when returning from a detail route.
- Each item opens a real secondary route with an AppBar back action.
  Parameters use Form/TextFormField; validate before dispatch and preserve
  exact values. Count Unicode scalar values to agree with Python string length.
- While a task runs, disable duplicate submission and parameter editing;
  expose cancellation until the native result settles. Route disposal cancels
  its task by ID and ignores late responses after unmounting.
- Results show explicit completion/failure/cancellation/timeout, App scope,
  exit code, duration, provenance-bearing data and bounded logs. Copy/export
  the result envelope; cancellation of the system file picker is not success.
- Use theme colors and Material controls with >=48 dp touch targets. Keep
  content <=840 dp, scroll long output, and test 375 dp, landscape, large text
  and reduced animation settings. Device UI and permission checks are separate.

- SDK v2 choice/secret/file metadata is rendered by the modular ParameterField.
  Secrets disable suggestions and start masked; generated passwords are masked
  until an explicit reveal. File selection stores tokens, displays filenames,
  and exports raw artifacts separately from the result JSON.
- HFTP has a dedicated service route. Poll actual host state, disable duplicate
  start/import/clear while active, expose stop and session credentials with
  explicit reveal/copy. Route disposal never stops the service. Explain LAN
  HTTP transport and Android/session limits where the user chooses them.
