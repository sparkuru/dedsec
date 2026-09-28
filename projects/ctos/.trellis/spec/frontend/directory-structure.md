# Flutter Directory Structure

Keep UI and UI-facing models under `lib/`; the Android implementation remains
under `android/app/src/main/`. This is a single Flutter application, not a
multi-package workspace.

| Path | Ownership |
| --- | --- |
| `lib/main.dart` | App theme, navigation, shared observatory state, and top-level screens |
| `lib/device_info.dart`, `lib/connection_info.dart`, `lib/connection_state.dart`, `lib/traffic.dart` | Pure data projection and snapshot/traffic state |
| `lib/device_page.dart` | Device information presentation |
| `lib/terminal_interaction.dart` | PTY input, session command history, and selectable output projection |
| `lib/workbench.dart` | Catalogue page and routes into workbench flows |
| `lib/workbench/api.dart`, `models.dart` | Dart-side channel wrapper and catalogue/result models |
| `lib/workbench/script_page.dart`, `parameter_field.dart`, `result_card.dart`, `controls.dart`, `hftp_page.dart`, `file_store_card.dart` | Workbench forms, results, service UI, and reusable controls |
| `test/` | Flutter unit and widget tests, grouped by behavior |

Keep a screen-specific model beside the behavior it projects. Move a focused
pure parser/state model out of `main.dart` when it can be tested independently;
do not create a second global state framework for one page.

Examples: `ConnectionReport` in `lib/connection_info.dart`,
`ConnectionSnapshotState` in `lib/connection_state.dart`, and the modular
workbench widgets under `lib/workbench/`.
