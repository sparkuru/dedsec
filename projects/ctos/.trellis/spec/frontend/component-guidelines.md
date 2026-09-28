# Flutter Widget Guidelines

Build with Material 3 and the app theme defined by `CtosApp` in
`lib/main.dart`. Use theme colors and text styles so status and controls remain
consistent with the dark/mint visual system.

- Use `StatelessWidget` for projection-only UI and `StatefulWidget` when the
  widget owns controllers, interaction state, polling, or a task lifecycle.
- Split UI by responsibility, as the workbench does: form fields in
  `parameter_field.dart`, result actions in `result_card.dart`, and shared
  action/dropdown layout in `controls.dart`. Do not duplicate a catalogue or
  native operation registry in view widgets.
- Prefer `ListView`/scrollable layouts and `LayoutBuilder` for narrow screens;
  constrain wide content to the existing workbench reading width. Keep primary
  actions reachable with the keyboard visible and support large text.
- Give icon-only buttons a tooltip or semantic label. Preserve Material touch
  targets (at least 48 dp where practical) and visible permission/error states.
- Product copy describes an action, its effect, actual state, or recovery. Put
  architecture and implementation history in `design/`, not in page headers.

Reference examples: `CtosApp` and `Observatory` in `lib/main.dart`,
`WorkbenchActions` and `WorkbenchDropdown` in `lib/workbench/controls.dart`,
and `ResultCard` in `lib/workbench/result_card.dart`.
