#!/usr/bin/env bash
# Run through ./hako. Downloads only the pinned Android build requirements and
# creates a project-specific disposable signing identity for both P0 APKs.
set -Eeuo pipefail

main() {
  if [[ ${1:-} == --help || ${1:-} == -h ]]; then
    printf 'Usage: ./hako ./tooling/prepare-android.sh\n' >&2
    return 0
  fi
  [[ $# -eq 0 && ${ANDROID_HOME:-} == /app/.devhome/android-sdk ]] || {
    printf 'Run this command through ./hako without arguments.\n' >&2
    return 2
  }
  local command_name
  for command_name in sdkmanager keytool mkdir; do
    command -v "$command_name" >/dev/null 2>&1 || return 127
  done
  sdkmanager 'platforms;android-36' 'build-tools;35.0.0'
  local signing_store=/app/.devhome/tacchi-development.p12
  if [[ ! -e "$signing_store" ]]; then
    umask 077
    keytool -genkeypair -keystore "$signing_store" -storetype PKCS12 \
      -storepass android -keypass android -alias tacchi-dev -keyalg RSA \
      -keysize 3072 -validity 3650 -dname 'CN=tacchi disposable P0 development'
  fi
}

main "$@"
