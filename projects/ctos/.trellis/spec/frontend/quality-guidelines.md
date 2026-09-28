# Flutter Quality Checks

Use the repository wrapper from `projects/ctos`; it provides the pinned
Flutter/Android toolchain inside Docker without publishing service ports.

```sh
./hako flutter analyze
./hako flutter test
```

- Add parser and state-transition tests beside the pure model; add widget
  tests for visible state, interaction, and exact channel payloads.
- Mock `ctos/native` with Flutter's test messenger. Keep mock handlers scoped
  to each test and remove them in teardown.
- Cover loading, unavailable, partial, stale, failed, cancellation, and
  recovery states when the screen exposes them. A blank/empty widget alone
  does not prove a permission or service path.
- For visible layout changes, exercise narrow width, landscape, large text, and
  reduced animations. Use exact layout assertions only for the contract being
  changed; avoid snapshots of incidental widget structure.
- `flutter test` does not prove Android IME, SAF, Root, HFTP service, or ROM
  behavior. Record those checks separately and only for an explicitly
  authorized current device.

Examples: `test/state_ui_test.dart`, `test/connection_info_test.dart`,
`test/workbench_usability_test.dart`, and `test/terminal_interaction_test.dart`.
