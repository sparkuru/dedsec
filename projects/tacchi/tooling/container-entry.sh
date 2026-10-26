#!/usr/bin/env bash
# Runs only inside the pinned hako image. SDK copies are owned by the caller UID.
set -Eeuo pipefail

readonly CACHE_ROOT=/app/.devhome

copy_sdk() {
  local source_dir=$1 destination=$2
  if [[ ! -d "$destination" ]]; then
    local staging_dir
    staging_dir=$(mktemp -d "$CACHE_ROOT/sdk-copy.XXXXXXXX")
    if ! tar -C "$source_dir" -cf - . |
      tar -C "$staging_dir" --no-same-owner -xf -; then
      # staging_dir is the fresh mktemp directory inside this fixed cache root.
      rm -rf -- "$staging_dir"
      return 1
    fi
    mv -- "$staging_dir" "$destination"
  fi
}

main() {
  local command_name
  for command_name in flock tar mktemp mv rm; do
    command -v "$command_name" >/dev/null 2>&1 || return 127
  done
  # Serialize only SDK initialization; unrelated test commands can run concurrently.
  exec 9>"$CACHE_ROOT/sdk-init.lock"
  flock 9
  copy_sdk /opt/android-sdk-linux "$CACHE_ROOT/android-sdk"
  flock -u 9
  exec 9>&-
  export PATH="$CACHE_ROOT/android-sdk/cmdline-tools/latest/bin:$CACHE_ROOT/android-sdk/platform-tools:$PATH"
  exec "$@"
}

main "$@"
