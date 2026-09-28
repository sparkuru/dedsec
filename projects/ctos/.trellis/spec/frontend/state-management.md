# Flutter State and Async Lifecycle

The current app uses widget-owned state and small immutable value models; it
does not use Provider, Bloc, Riverpod, or a custom hook package. Keep a new
state owner local to its screen unless multiple routes genuinely share that
state.

- Store screen interaction state in the owning `State` and update it with
  `setState`. Keep parse/search/freshness logic in pure models such as
  `ConnectionReport` and `ConnectionSnapshotState` so those rules can be unit
  tested without pumping a screen.
- Check `mounted` before applying asynchronous results or using a `BuildContext`
  after `await`. Dispose controllers, timers, stream subscriptions, lifecycle
  observers, and other owned resources in `dispose`.
- A page leaving the tree may cancel a short Python job it owns; it must not
  stop a persistent HFTP service. The service has a separate native lifetime.
- Preserve the last successful connection snapshot during refresh. Use its
  capture time, error, and expiry state to distinguish old data from an empty
  current result; see `lib/connection_state.dart`.
- When overlapping requests can finish out of order, guard responses with the
  captured operation revision. `HftpPage` separates business-operation and
  log revisions so an old poll cannot overwrite a newer start, stop, or clear.
- Keep task IDs paired with cancellation and ignore late results after
  cancellation or route disposal. Do not use a page's `BuildContext` from an
  unowned background callback.

Reference files: `lib/main.dart`, `lib/workbench.dart`,
`lib/workbench/script_page.dart`, `lib/workbench/hftp_page.dart`, and
`lib/connection_state.dart`.
