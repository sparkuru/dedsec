# Development environment

## Current stage

The selected product stack is Flutter + Kotlin for Android. No product `pubspec.yaml`, Android project, Gradle wrapper, toolchain lock, existing product test command or preview service exists yet. Do not invent an application command, `.env`, HTTP endpoint, or preview script before a real consumer exists.

The user requested a development-container bootstrap using the existing device-verification task. Root `./hako` runs ephemeral commands as the host UID/GID using `ghcr.io/cirruslabs/flutter:3.35.7` by default. On this host it provides Flutter 3.35.7, Dart 3.9.2, JDK 21.0.6, Android platform 35 and build-tools 35.0.0; these are an initial development environment, not a product compatibility decision. `./hako flutter --version`, `./hako dart --version`, `./hako java -version` and `./hako sdkmanager --list_installed` inspect the toolchain. An Android-only Flutter project was generated under a disposable container `/tmp` with `flutter create --no-pub`; no application build or package download was verified. `flutter doctor` recognizes the Android toolchain and its installed licenses, while reporting that the image's Flutter Git checkout has an unknown channel/source. Web, Linux desktop and Android Studio are not provisioned by this Android-focused wrapper.

The host needs Docker, Bash, `flock`, `sha256sum` and standard shell utilities. The first run copies Flutter and Android SDKs into ignored `.devhome/` so the host user can write caches and install additional SDK packages; the copies currently occupy about 2.2 GiB. The temporary SDK-copy container reads one image file using the supplemental root group but writes as the host UID/GID to its isolated destination; normal command containers do not add that group. `HAKO_IMAGE` can select a different image only if it provides the SDKs at the documented image paths. The SDK copies are keyed by image reference; replacing an image under the same reference does not refresh an existing copy. Docker access still follows the active Codex sandbox and approval policy. `HAKO_NETWORK=none` disables the command container's networking; dependency or SDK installation requires network access or an already populated cache.

No Android USB device or host ADB server is forwarded automatically. API 36 platform/build-tool needs, Gradle/Kotlin versions, Gradle and Pub dependency resolution, a full build, and physical-device execution remain unverified until a product implementation supplies its actual requirements. A later implementation task must establish its reproducible build/test commands and toolchain versions; update the image or SDK components when evidence requires it. There is no product server, so `hako` publishes no ports and no `dev.sh` is provided.

## Device and source

For authorized real-device checks, start with `adb devices -l` and select the sole `product:PLR110` device explicitly with `adb -s <serial> ...`; never use an arbitrary default device. The device currently has Thanox Pro; re-read the installed version and activation before relying on prior results. ADB access may need the environment's normal sandbox approval; no policy here grants it.

`archive/thanox-repo` is a user-provided, ignored reference checkout of `https://github.com/Tornaco/Thanox.git`; the previously examined commit is `053888f56b8d07b964e848ce1ccb5f2b979bc969`. Its absence in a clean checkout must be reported; acquire or restore a matching reference only with the applicable network/setup authority, without overwriting an existing checkout. Do not require this ignored path as a hidden build dependency.

## Preview and configuration

Current preview: not applicable; there is no web/server product. Native Flutter interactions require Android emulator/device validation, not a browser server. If a later task actually introduces a previewable service or configuration, document its real startup, readiness, stop, network, secret-free example and configuration precedence then; do not introduce an unused `.env` or service now.
