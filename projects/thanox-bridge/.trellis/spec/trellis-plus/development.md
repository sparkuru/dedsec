# Development environment

## Current stage

The selected product stack is Flutter + Kotlin for Android. No product `pubspec.yaml`, Android project, Gradle wrapper, toolchain lock, existing test command or preview service exists yet. `flutter` and `dart` were not found on the current session's PATH; this is an environment observation, not a claim about other machines. Do not invent a Flutter command, container image, `.env`, HTTP endpoint, or preview script before a real consumer exists.

The first implementation task must identify and record the actual Flutter/Dart, Android SDK/build-tools, JDK, Kotlin/Gradle versions and reproducible build/test commands. Reuse a working project wrapper if one exists; if a Docker development wrapper is needed, establish and test it as part of that task rather than treating a policy file as execution permission. Document host/device dependencies, offline limitations and precise failure conditions.

## Device and source

For authorized real-device checks, start with `adb devices -l` and select the sole `product:PLR110` device explicitly with `adb -s <serial> ...`; never use an arbitrary default device. The device currently has Thanox Pro; re-read the installed version and activation before relying on prior results. ADB access may need the environment's normal sandbox approval; no policy here grants it.

`archive/thanox-repo` is a user-provided, ignored reference checkout of `https://github.com/Tornaco/Thanox.git`; the previously examined commit is `053888f56b8d07b964e848ce1ccb5f2b979bc969`. Its absence in a clean checkout must be reported; acquire or restore a matching reference only with the applicable network/setup authority, without overwriting an existing checkout. Do not require this ignored path as a hidden build dependency.

## Preview and configuration

Current preview: not applicable; there is no web/server product. Native Flutter interactions require Android emulator/device validation, not a browser server. If a later task actually introduces a previewable service or configuration, document its real startup, readiness, stop, network, secret-free example and configuration precedence then; do not introduce an unused `.env` or service now.
