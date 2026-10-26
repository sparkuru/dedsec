# Research: Phone key metadata visibility

- Query: How can the main session read only a selected key's managed path and algorithm through the current phone UI when uiautomator exposes only its fingerprint?
- Scope: internal source and sanitized UI evidence; read-only, no device calls
- Date: 2026-10-26

## Findings

### Current source and evidence

- `lib/ssh/library_page.dart:653` creates one Card per key.
- The card visibly renders `Text(key.label)` at line659,
  `Text(key.algorithm)` at line664 and `Text(key.path)` at line666.
- `SelectableText(key.fingerprint)` follows at line668, then the use-key button
  at line670. There is no explicit `ExcludeSemantics` or `MergeSemantics` in this
  card/page.
- `lib/ui/ctos_components.dart:40` defines CtosReadingColumn as only
  Center/ConstrainedBox; it does not suppress or replace semantics.
- `lib/ssh/library_api.dart:13` strictly parses the metadata-only key fields
  id/label/path/algorithm/fingerprint. Path is a `.ssh/` managed logical path
  (`:348`), not private-key contents.
- Main-session `ui-library.json` under
  `/tmp/ctos-phone-ssh-20261026.plE6pOaG/` shows the entire card as one
  nonclickable `android.view.View` with the fingerprint in its text, plus a
  separate clickable use-key button. Ordinary label/algorithm/path Text nodes
  are absent from the sanitized hierarchy capture.

This shows metadata exists in the rendered source, while the captured semantics
surface is merged/flattened around a selectable text control. It does not prove
the metadata is visually absent or intentionally excluded. The exact Flutter
engine/Android bridge merge mechanism was not inspected in this bounded query.

### Safe normal-UI diagnosis order

1. Inspect only the fingerprint card node's available attribute names and, if
   supplied by the current hierarchy reader, hint/tooltip/stateDescription values.
   The current temporary UI reader persists only class/bounds/clickable/enabled/
   focused/text/content-desc and would discard other available attributes.
2. A reader already able to inspect Android AccessibilityNodeInfo can query
   `getHintText()` for this one card. Ordinary merged Text labels can be conveyed
   as labels/hints around a selectable/editable semantic node. Standard
   uiautomator XML may omit this information; verify the actual available
   attributes rather than assuming a particular engine behavior.
3. If a screenshot taken through the existing permitted app UI is blocked by
   FLAG_SECURE and no existing accessibility reader exposes those public labels,
   ask the user to transcribe **only the algorithm and managed path shown on the
   card**. No private contents, file upload or secret-copy request is needed.

Do not disable FLAG_SECURE, export/decrypt the vault, reimport a key, open the
private source, or install an additional accessibility service merely to read
these two public metadata values. The fingerprint selection/copy surface itself
only yields the fingerprint and cannot supply the preceding ordinary Text.

### Related specs

- `.trellis/spec/frontend/app-secrets.md`: preserve sensitive-window owner
  lifetime and avoid raw-secret channels.
- `.trellis/spec/frontend/state-management.md`: UI state belongs to the current
  page and stale results require captured revisions.
- `.trellis/spec/backend/ssh-key-config-connect.md`: library metadata is public;
  private key bytes stay host-owned and exact source/identity bindings remain.

### External references

None consulted. Suggestions about additional node attributes are diagnostic
options, not claims that the current Android/Flutter bridge exposes them.

## Caveats / Not Found

- This researcher did not call ADB, capture a screen, access any key body, build
  code, or modify production files.
- The real connection now fails before host review with the fixed unsupported
  SSH-material message; the host agent owns that separate validator diagnosis.
  Public metadata visibility alone does not identify its cause.
