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

## Overview and settings navigation

The four root entries are overview (0), workbench (1), terminal (2), settings
(3). Overview owns device/network/interfaces/connections categories; the
summary and category selector lead each category's single vertical ListView.
Do not embed a fixed-height detail viewport or a scrolling ListView inside
another vertical ListView. `DevicePage.leading` defaults to an empty widget
list so standalone device presentation stays compatible. Preserve category
scroll/filter state in IndexedStack and independent PageStorage paths.

Settings owns global snapshot export and the lazily pushed security route.
Keep snapshot export's original availability gating and `openExport` payload;
task-history selection and tool output saves remain local to their flows.
Security reuses all SecretVaultSettingsPage behavior; merely opening settings
must not initialize/read the vault. Keep network polling on foreground overview
only, refresh connections when its category is entered/resumed/reselected via
overview, and retain Root retry/error in overview/network. Interface-history
navigation targets overview/interfaces; terminal IME guards use index 2.

Tests must verify root labels/order, absent old global export/secret entries,
lazy security, ordinary detail back, overview polling and inactive-entry silence,
large-text reachable actions, category filtering and live PTY preservation.

Overview's four-category SegmentedButton fills the reading column with
`expandedInsets: EdgeInsets.zero`; labels occupy equal widths and are centered.
Keep the selected fill/semantics while hiding the check icon so 320 dp / 2×
labels fit without horizontal scrolling. Root status belongs to the 48 dp
`root-status-info` icon immediately after the ctOS title: primary green online,
warning yellow while granting/offline. Tooltip and the scrollable status dialog
provide the state, source and existing failure reason; opening it must not
request Root. Do not restore the global APP badge/body Root row or the terminal
session selector's eyebrow. `overview_polish_test.dart` asserts bounds, colors,
accessible labels, state changes and absence of implicit Root calls.

Overview's summary contains the device name and memory/battery metrics, without
an eyebrow or Android/uptime subtitle. Keep full system/version/uptime fields in
DevicePage. Category subtitles describe their content scope consistently:
device system/resources/power; network status/live traffic; interfaces/address/
link state; TCP/UDP connections/app mapping. Count, source and capture time are
data metadata; interface count/source stays below the filter, separate from the
static heading description. Removing repetitive headings must not remove data
provenance or change collection/state lifetimes.

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

## Main-entry system back confirmation

**Scope / trigger:** Android system back on the uncovered root Observatory,
in all four main entries. The UX is owned by `HomeExitGuard` in `lib/ui/`.

**Signatures:** `HomeExitGuard({RouteObserver<ModalRoute<dynamic>>?
routeObserver, required int entry, required Scaffold Function(BuildContext,
Widget exitPrompt) builder})` wraps the builder's root Scaffold.
`CtosApp.build` still returns MaterialApp; `Observatory({exitRouteObserver?})`
retains its optional standalone preview constructor. Production CtosApp
registers and passes the same ModalRoute observer, including PopupRoutes.

**Contract:** PopScope blocks the first root back and shows
`再按一次返回键退出`. A second back strictly before 2 seconds calls
`SystemNavigator.pop` once; Timer expiry plus Stopwatch reject stale
confirmation. The prompt uses Snackbar theme, SafeArea, IgnorePointer and a
live-region Semantics with max width 840 dp. The builder places it in Scaffold's
floatingActionButton slot with FloatingActionButtonLocation.centerFloat;
Scaffold coordinates navigation/safe insets and puts floating Snackbar above
the prompt. This is an IgnorePointer informational surface, not a tap action.

**Validation / reset matrix:** details or popup current → normal route back;
keyboard visible → hide keyboard without exit; entry/observer change, route
coverage, keyboard opening, or non-resumed lifecycle → reset confirmation;
dispose → cancel timer and unsubscribe; pending exit → ignore repeated back.
Without an observer, standalone previews track inherited ModalRoute.isCurrent.

**Good / base / bad:** good preserves ordinary Snackbar feedback while the
exit hint is immediately visible; base confirms root back with a local timer;
bad clears every Snackbar, uses only PageRoute observation, or kills services.

**Tests:** `home_exit_guard_test.dart` dispatches binding.handlePopRoute,
observes SystemNavigator.pop, covers 1999/2000/2300 ms, production four entries,
popup/IME metrics/lifecycle resets, observer replacement/disposal and large
text. Assert multiline Snackbar and prompt Material rectangles do not overlap.
Cover Snackbar before and after the prompt, bottom placement, system safe
insets and production NavigationRail. A stable keyed non-null slot switches to
SizedBox.shrink while idle: no retained FAB exit-transition text/liveRegion,
and its measured size must be zero after timeout/switch/exit.
Actual Android IME/gesture/desktop/reopen still require scoped device checks.

**Wrong / correct:** wrong places a prompt at Scaffold.body bottom and only
asserts find.text: Scaffold's floating Snackbar can paint over it. Correct
uses the public Scaffold layout slot and asserts non-overlapping bottom geometry. Shared
ScaffoldMessenger cannot remove arbitrary queued Snackbar safely; do not let
an old exit hint queue behind another operation or delete unrelated feedback.
