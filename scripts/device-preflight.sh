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
report_tmp=""
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
	printf '%s\n' 'Collect a read-only Android research-device baseline.' >&2
}

require_command() {
	command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

cleanup() {
	if [[ -n "$report_tmp" && -f "$report_tmp" ]]; then
		rm -f -- "$report_tmp"
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
	local safe_serial
	local timestamp

	if [[ -z "$output_path" ]]; then
		safe_serial=${device_serial//[^a-zA-Z0-9._-]/_}
		timestamp=$(date -u '+%Y%m%dT%H%M%SZ')
		output_path="${TMPDIR:-/tmp}/dedsec-device-${safe_serial}-${timestamp}.txt"
	fi

	output_dir=$(dirname -- "$output_path")
	[[ -d "$output_dir" ]] || die "output directory does not exist: $output_dir"
	[[ ! -e "$output_path" ]] || die "refusing to overwrite: $output_path"
	report_tmp=$(mktemp "${output_path}.tmp.XXXXXXXX")
}

read_property() {
	local key=$1
	local value

	value=$("${adb_command[@]}" shell getprop "$key" | tr -d '\r')
	printf '%s=%s\n' "$key" "$value"
}

read_root_identity() {
	local identity

	if identity=$("${adb_command[@]}" shell su -c id 2>/dev/null); then
		printf 'root.available=true\n'
		printf 'root.identity=%s\n' "$(printf '%s' "$identity" | tr -d '\r\n')"
		return 0
	fi

	printf 'root.available=false\n'
}

read_framework_modules() {
	local modules
	local remote_script

	# shellcheck disable=SC2016 # The device shell expands this script.
	remote_script='for file in /data/adb/modules/*/module.prop; do
        [ -f "$file" ] || continue
        id=$(sed -n "s/^id=//p" "$file" | head -n 1)
        name=$(sed -n "s/^name=//p" "$file" | head -n 1)
        version=$(sed -n "s/^version=//p" "$file" | head -n 1)
        case "$id $name" in
            *[Vv]ector*|*[Ll][Ss][Pp]osed*|*[Zz]ygisk*) printf "%s|%s|%s\n" "$id" "$name" "$version" ;;
        esac
    done'

	if modules=$("${adb_command[@]}" shell su -c "$remote_script" 2>/dev/null); then
		printf 'framework.modules.begin\n'
		printf '%s\n' "$(printf '%s' "$modules" | tr -d '\r')"
		printf 'framework.modules.end\n'
		return 0
	fi

	printf 'framework.modules=unavailable\n'
}

write_report() {
	local package_path
	local selinux_mode
	local storage_line

	printf 'schema=1\n'
	printf 'collected_at=%s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
	printf 'adb.serial=%s\n' "$device_serial"
	read_property ro.product.manufacturer
	read_property ro.product.model
	read_property ro.product.device
	read_property ro.build.fingerprint
	read_property ro.build.version.release
	read_property ro.build.version.sdk
	read_property ro.build.version.security_patch
	read_property ro.product.cpu.abilist
	read_property ro.boot.slot_suffix
	read_property ro.boot.verifiedbootstate
	read_property ro.boot.vbmeta.device_state
	read_property ro.boot.flash.locked

	selinux_mode=$("${adb_command[@]}" shell getenforce 2>/dev/null | tr -d '\r\n') || selinux_mode="unavailable"
	printf 'selinux.mode=%s\n' "$selinux_mode"
	read_root_identity
	read_framework_modules

	package_path=$("${adb_command[@]}" shell pm path "$TARGET_PACKAGE" 2>/dev/null | tr -d '\r\n') || package_path=""
	if [[ -n "$package_path" ]]; then
		printf 'target.installed=true\n'
		printf 'target.path=%s\n' "$package_path"
	else
		printf 'target.installed=false\n'
	fi

	storage_line=$("${adb_command[@]}" shell df -k /data/local/tmp 2>/dev/null | tail -n 1 | tr -d '\r') || storage_line="unavailable"
	printf 'storage.data_local_tmp=%s\n' "$storage_line"
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

	write_report >"$report_tmp"
	chmod 600 -- "$report_tmp"
	mv -- "$report_tmp" "$output_path"
	report_tmp=""

	info "Device report: $output_path"
	warn "The report contains a device serial, model, and build fingerprint. Keep it out of Git."
}

main "$@"
