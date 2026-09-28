# Dart Types and Platform Data

The Dart code is null-safe and the platform boundary is dynamic JSON. Keep
untyped values at that boundary and project them into small domain models
before UI code depends on them.

- Use immutable `final` model classes for parsed snapshots and entries.
  Examples include `DeviceSection`, `DeviceSnapshot`, `AppIdentity`, and
  `ConnectionEntry`.
- Parse native JSON in the API/model layer. Check optional fields and preserve
  unknown/unavailable values deliberately; do not let a UI widget repeatedly
  cast the same raw `Map`.
- MethodChannel method names, argument keys, enum strings, and Python SDK
  values are wire contracts. Localized labels may change, but the submitted
  value must remain the protocol value.
- Treat nullable platform results as nullable: picker cancellation is `null`,
  not an empty selected file. Do not use `!` for a value that can be absent
  because of cancellation, plugin failure, or an older device response.
- For tolerant display fallbacks, preserve provenance and report parsing
  failure. Do not silently substitute zero, an empty string, or a fabricated
  success state for invalid required data.

Reference examples: `HftpSettings.fromJson` and `_decode` in
`lib/workbench/api.dart`, `DeviceSnapshot.fromJson` in
`lib/device_info.dart`, and `ConnectionReport.parse` in
`lib/connection_info.dart`.
