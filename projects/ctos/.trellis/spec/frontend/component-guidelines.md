# Flutter Widget Guidelines

Build with Material 3 and `CtosTheme.dark()` in `lib/ui/ctos_theme.dart`, applied
by `CtosApp` in `lib/main.dart`. Use semantic colors and text styles so status
and controls remain consistent with the dark/mint visual system. The product
design entry is `design/flutter-experience.md`.

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

## Adaptive presentation contracts

**Scope:** navigation, theme, forms, results and data-field presentation.

**Signatures:** `CtosTheme.dark() -> ThemeData`,
`CtosTheme.withMotion(BuildContext) -> ThemeData`,
`CtosTheme.duration(BuildContext) -> Duration`,
`CtosPageHeading(title, subtitle, eyebrow?)`,
`CtosDataField(label, value)`, `workbenchPadding(width) -> EdgeInsets`.
These helpers only project data; they do not own platform calls or task state.

**Contracts:** supporting text uses at least 13 dp and dense body 14 dp; normal
text needs 4.5:1 contrast. Enabled input and necessary control boundaries use
`controlBorder`, at least 3:1 against actual field fill/surface; decorative
`border` is intentionally subtler. Preserve text scaling and 48 dp actions.
At 900 dp use a NavigationRail while retaining the keyed
`observatory-content` subtree and existing IndexedStack state. Reading content
is capped at 840 dp. Compact field rows are appropriate when labels/values fit;
high text scaling and long values stack and remain fully selectable.

**Validation:** 320/375 dp, landscape and 1200 dp with 1×/2× text must retain
reachable actions; wide/narrow transitions preserve filters, secret drafts and
live PTY identity. Reduced motion sets nonessential button/expansion/route
durations to zero through `withMotion`; otherwise feedback uses about 200 ms.
Xterm uses `CtosTheme.terminal`, preserving ANSI colors and the sole input path.

**Good/base/bad:** good uses actual `theme.inputDecorationTheme.fillColor` when
checking control contrast; base uses theme roles for readable fields. Bad uses
the subtle separator color as the sole input outline or rebuilds pages when
switching between rail and bottom navigation.

**Tests:** `theme_layout_test.dart` checks actual theme color pairs and
breakpoint state retention; `workbench_layout_test.dart` covers forms/results,
service actions and draft retention; `secondary_layout_test.dart` covers
fields, confirmations, timestamp precision and cancellation/error feedback.
Screenshot fixtures must supply Scaffold/Material and real glyph-capable fonts;
a green layout check alone does not prove visual quality.

**Wrong/correct:** wrong: `wide ? WidePages() : PhonePages()` with separate
state owners. Correct: keep the same keyed content subtree and change only
navigation presentation. Wrong: a PageStorage-keyed list with unkeyed nested
ExpansionTile/SelectableText storing bool and double to the same path. Correct:
give each persisted expansion and text/scroll position its own storage key.
