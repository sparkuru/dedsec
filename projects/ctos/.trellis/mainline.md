# ctOS Mainline

- **Mode:** `serial`
- **Authorization:** On 2026-09-28, the user directed Codex to continue the remaining Trellis tasks in order and archive them all. This record bounds that authorization to the active task graph listed below.
- **Current scope:** After archiving `09-25-read-only-task-loop`, the remaining active graph contains `09-25-interface-terminal-polish` and its parent `09-25-product-experience-opportunities`. Complete the child first, then finish the parent integration task.
- **Current task:** `09-25-interface-terminal-polish` (in_progress). The PRD, design, implementation plan, and implement/check contexts were reviewed and approved on 2026-09-28; implementation and scoped validation are active.
- **Device access:** P2 has separate authorization for `TARGET-BOARD` release installation, App Shell/keyboard/shortcut/Ctrl-C/output search-copy/return checks, and a read-only Root PTY `id`. This authorization is limited to `09-25-interface-terminal-polish`; it does not permit Root instrumentation, system/VPN changes, data clearing, reboot, or device work on the parent task.
- **Stop conditions:** Stop and ask if the active graph changes, a material acceptance check cannot run, device permissions exceed task-specific authorization, or implementation scope needs to expand.
