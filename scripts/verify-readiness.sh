#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_NAME=$(basename "$0")
readonly SCRIPT_NAME
REPO_ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
readonly REPO_ROOT
readonly TARGET_ROOT="$REPO_ROOT/labs/target-app"
readonly STYLE_RESET=$'\033[0m'
readonly STYLE_TITLE=$'\033[1;36m'
readonly STYLE_SUCCESS=$'\033[0;32m'
readonly STYLE_ERROR=$'\033[1;31m'

run_android=false

color_text() {
	local style=$1
	local value=$2

	if [[ -n "${NO_COLOR:-}" || ! -t 1 ]]; then
		printf '%s' "$value"
		return 0
	fi

	printf '%s%s%s' "$style" "$value" "$STYLE_RESET"
}

info() {
	printf '%s\n' "$(color_text "$STYLE_SUCCESS" "$*")"
}

die() {
	printf '%s\n' "$(color_text "$STYLE_ERROR" "Error: $*")" >&2
	exit 1
}

usage() {
	printf '%s\n' "$(color_text "$STYLE_TITLE" "Usage: $SCRIPT_NAME [--android]")" >&2
	printf '%s\n' 'Validate readiness files and shell scripts. --android also builds and tests the target app.' >&2
}

require_command() {
	command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

parse_args() {
	while [[ $# -gt 0 ]]; do
		case "$1" in
		--android)
			run_android=true
			shift
			;;
		--help | -h)
			usage
			exit 0
			;;
		*)
			die "unknown option: $1"
			;;
		esac
	done
}

verify_required_files() {
	local required_files=(
		"docs/learning/readiness.md"
		"docs/learning/experiment-template.md"
		"docs/research/vector-reading-index.md"
		"third_party/upstreams.lock"
		"labs/_template/README.md"
		"labs/target-app/gradlew"
		"labs/target-app/gradle/wrapper/gradle-wrapper.jar"
		"labs/target-app/app/src/main/AndroidManifest.xml"
	)
	local relative_path

	for relative_path in "${required_files[@]}"; do
		[[ -f "$REPO_ROOT/$relative_path" ]] || die "required file is missing: $relative_path"
	done
}

verify_shell_scripts() {
	local script_path
	local target_gradlew="$TARGET_ROOT/gradlew"

	while IFS= read -r -d '' script_path; do
		bash -n "$script_path"
		shellcheck "$script_path"
		shfmt -d "$script_path"
	done < <(find "$REPO_ROOT/scripts" -type f -name '*.sh' -print0)

	sh -n "$target_gradlew"
	shellcheck -s sh "$target_gradlew"
	shfmt -d "$target_gradlew"
}

verify_lock_file() {
	awk -F '\t' '
        NR == 1 {
            if ($0 != "name\turl\trevision\tdirectory") exit 1
            next
        }
        NF != 4 || $3 !~ /^[0-9a-f]{40}$/ || seen[$1]++ || seen_dir[$4]++ { exit 1 }
        END { if (NR < 2) exit 1 }
    ' "$REPO_ROOT/third_party/upstreams.lock" || die "invalid upstream lock file"
}

verify_yaml() {
	ruby -e 'require "yaml"; YAML.safe_load_file(ARGV.fetch(0))' "$REPO_ROOT/third_party/sources.yml"
}

verify_android_project() {
	local sdk_root=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}

	[[ -n "$sdk_root" && -d "$sdk_root" ]] || die "ANDROID_SDK_ROOT or ANDROID_HOME must reference an Android SDK"
	[[ -x "$TARGET_ROOT/gradlew" ]] || die "Gradle wrapper is not executable"
	(
		cd -- "$TARGET_ROOT"
		./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
	)
}

main() {
	require_command awk
	require_command bash
	require_command find
	require_command git
	require_command ruby
	require_command shellcheck
	require_command shfmt
	parse_args "$@"

	verify_required_files
	verify_shell_scripts
	verify_lock_file
	verify_yaml
	git -C "$REPO_ROOT" diff --check

	if [[ "$run_android" == true ]]; then
		verify_android_project
	fi

	info "Readiness checks passed"
}

main "$@"
