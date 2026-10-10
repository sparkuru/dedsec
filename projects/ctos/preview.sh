#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
	printf '%s\n' \
		'Usage: ./preview.sh [build]' \
		'       ./preview.sh --help' \
		'' \
		'Build the latest arm64 release APK through hako, then print its path and install command.' \
		'Requires Docker with a reachable daemon; first use may download build tools.' >&2
}

main() {
	local script_dir repo_root apk_path

	if [[ $# -gt 1 ]]; then
		usage
		return 2
	fi
	case "${1:-build}" in
	-h | --help)
		usage
		return 0
		;;
	build) ;;
	*)
		printf 'Error: unknown argument: %s\n' "$1" >&2
		usage
		return 2
		;;
	esac

	script_dir=${BASH_SOURCE[0]%/*}
	[[ $script_dir != "${BASH_SOURCE[0]}" ]] || script_dir=.
	repo_root=$(cd -- "$script_dir" && pwd -P)
	apk_path="$repo_root/build/app/outputs/flutter-apk/app-release.apk"
	[[ -x "$repo_root/hako" ]] || {
		printf 'Error: executable build wrapper not found: %s/hako\n' "$repo_root" >&2
		return 1
	}
	command -v docker >/dev/null 2>&1 || {
		printf 'Error: Docker is required on the host.\n' >&2
		return 127
	}

	cd -- "$repo_root"
	./hako flutter build apk --release --target-platform android-arm64
	[[ -f "$apk_path" && -s "$apk_path" ]] || {
		printf 'Error: build did not produce a nonempty APK: %s\n' "$apk_path" >&2
		return 1
	}
	printf '\nAPK: %s\n' "$apk_path"
	printf 'adb install %q\n' "$apk_path"
}

main "$@"
