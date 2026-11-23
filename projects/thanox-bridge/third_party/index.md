# Third-party material inventory

| Component | Source and version | Retained scope | Notice |
| --- | --- | --- | --- |
| Trellis and trellis-core | `@mindfoldhq/trellis` and `@mindfoldhq/trellis-core` 0.6.17; https://github.com/mindfold-ai/trellis | Unchanged generated Trellis runtime, managed AGENTS block and initial spec scaffolds, subject to baseline review | [trellis-0.6.17-LICENSE](trellis-0.6.17-LICENSE), copied verbatim from the installed 0.6.17 distribution; both installed license files have the same SHA-256 `d8a6cc31abc16b6748c7a21f21611f5a1ec33f67d22ca23d7da1c19b95496bee` |
| Flutter | Flutter SDK 3.35.7, revision `adc901062556672b4138e18a4dc62a4be8f4b3c2` | Generated Android project scaffolding and launcher assets; the SDK remains in the external development image | [flutter-3.35.7-LICENSE](flutter-3.35.7-LICENSE), copied from the SDK; SHA-256 `a598db94b6290ffbe10b5ecf911057b6a943351c727fdda9e5f2891d68700a20` |
| Thanox | https://github.com/Tornaco/Thanox at `053888f56b8d07b964e848ce1ccb5f2b979bc969` | Binder interface and parcel layout used to implement interoperability; the reference checkout itself is not distributed | [thanox-apache-2.0-LICENSE](thanox-apache-2.0-LICENSE), copied from that checkout; SHA-256 `c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4` |

The ignored `archive/thanox-repo` is a user-provided reference checkout, not part of the intended tracked snapshot. Review additional source/version notices if more Thanox material is incorporated or distributed. Codex-local UUPM files are an untracked personal integration; do not include or relabel them as project-owned material without a separate source and license review.
