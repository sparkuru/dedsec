#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_NAME=$(basename "$0")
readonly SCRIPT_NAME
readonly TARGET_PACKAGE="im.majo.dedsec.example"
readonly STYLE_RESET=$'\033[0m'
readonly STYLE_TITLE=$'\033[1;36m'
readonly STYLE_SUCCESS=$'\033[0;32m'
readonly STYLE_WARNING=$'\033[1;33m'
readonly STYLE_ERROR=$'\033[1;31m'

device_serial=""
output_path=""
log_tmp=""
adb_command=(adb)

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

warn() {
	printf '%s\n' "$(color_text "$STYLE_WARNING" "Warning: $*")" >&2
}

die() {
	printf '%s\n' "$(color_text "$STYLE_ERROR" "Error: $*")" >&2
	exit 1
}

usage() {
	printf '%s\n' "$(color_text "$STYLE_TITLE" "Usage: $SCRIPT_NAME [--serial SERIAL] [--output FILE]")" >&2
	printf '%s\n' 'Capture existing target and framework logcat entries without clearing the device log.' >&2
}

require_command() {
	command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

cleanup() {
	if [[ -n "$log_tmp" && -f "$log_tmp" ]]; then
		rm -f -- "$log_tmp"
	fi
}

parse_args() {
	while [[ $# -gt 0 ]]; do
		case "$1" in
		--serial)
			[[ $# -ge 2 ]] || die "--serial requires a value"
			device_serial=$2
			shift 2
			;;
		--output)
			[[ $# -ge 2 ]] || die "--output requires a value"
			output_path=$2
			shift 2
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

configure_device() {
	local state

	if [[ -n "$device_serial" ]]; then
		adb_command+=(-s "$device_serial")
	fi

	state=$("${adb_command[@]}" get-state 2>/dev/null) || die "ADB device is unavailable or ambiguous"
	[[ "$state" == "device" ]] || die "ADB state is not device: $state"

	if [[ -z "$device_serial" ]]; then
		device_serial=$("${adb_command[@]}" get-serialno | tr -d '\r')
	fi
}

configure_output() {
	local output_dir
	local timestamp

	if [[ -z "$output_path" ]]; then
		timestamp=$(date -u '+%Y%m%dT%H%M%SZ')
		output_path="${TMPDIR:-/tmp}/dedsec-lab-log-${timestamp}.txt"
	fi

	output_dir=$(dirname -- "$output_path")
	[[ -d "$output_dir" ]] || die "output directory does not exist: $output_dir"
	[[ ! -e "$output_path" ]] || die "refusing to overwrite: $output_path"
	log_tmp=$(mktemp "${output_path}.tmp.XXXXXXXX")
}

write_log() {
	local package_path

	package_path=$("${adb_command[@]}" shell pm path "$TARGET_PACKAGE" 2>/dev/null | tr -d '\r\n') || package_path="not-installed"
	printf 'collected_at=%s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
	printf 'adb.serial=%s\n' "$device_serial"
	printf 'target.package=%s\n' "$TARGET_PACKAGE"
	printf 'target.path=%s\n' "$package_path"
	printf 'logcat.begin\n'
	"${adb_command[@]}" logcat -d -v threadtime \
		DedsecTarget:V \
		DedsecTargetWorker:V \
		DedsecModule:V \
		LSPosed:V \
		LSPosed-Bridge:V \
		'*:S'
	printf 'logcat.end\n'
}

main() {
	require_command adb
	require_command date
	require_command dirname
	require_command mktemp
	require_command tr
	parse_args "$@"
	configure_device
	configure_output
	trap cleanup EXIT

	write_log >"$log_tmp"
	chmod 600 -- "$log_tmp"
	mv -- "$log_tmp" "$output_path"
	log_tmp=""

	info "Lab log: $output_path"
	warn "Review framework logs for device paths or identifiers before long-term storage."
}

main "$@"
