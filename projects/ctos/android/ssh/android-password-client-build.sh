#!/usr/bin/env bash
set -Eeuo pipefail

readonly PROJECT_ROOT=/app
readonly BASELINE="$PROJECT_ROOT/build/ssh-native-g1/stock-run.lOHF9Oai"
readonly SOURCE="$BASELINE/sources/openssh-10.6p1"
readonly UP_BUILD="$BASELINE/openssh-build"
readonly SSL="$BASELINE/openssl-prefix"
readonly CPP="$PROJECT_ROOT/android/app/src/main/cpp"
readonly TEST_CPP="$PROJECT_ROOT/android/app/src/test/cpp"
readonly PRODUCTION_INCLUDE="$PROJECT_ROOT/build/app/generated/sshProduction/arm64-v8a/include"
readonly NDK_BIN="$PROJECT_ROOT/.devhome/android-sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/bin"
readonly BASELINE_HASH=e3b0c5215b8050db328bd8773cc4ba00212eb9f8b21d924ed0ab207501ef0ed6
readonly STYLE_TITLE=$'\033[1;36m'
readonly STYLE_ERROR=$'\033[1;31m'
readonly STYLE_RESET=$'\033[0m'
output_dir=''
scratch_dir=''
mode=client

style_text() {
	if [[ -t 2 && -z "${NO_COLOR:-}" ]]; then
		printf '%s%s%s' "$1" "$2" "$STYLE_RESET" >&2
	else
		printf '%s' "$2" >&2
	fi
}
usage() {
	style_text "$STYLE_TITLE" 'Pinned static AArch64/Bionic SSH client test build'
	printf '\nUsage: %s --output <new-/app/build/directory> [--client | --interop | --key-interop | --issuer]\n' "${0##*/}" >&2
	printf 'Build only; no target execution. Default: complete native client matrix.\n' >&2
}
die() {
	style_text "$STYLE_ERROR" "Error: $*"
	printf '\n' >&2
	exit 1
}
require_command() { command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"; }
fixed_hash() {
	local actual
	[[ -f "$1" && ! -L "$1" && "$(realpath -e -- "$1")" == "$1" ]] || die 'noncanonical pinned input'
	actual=$(sha256sum -- "$1")
	[[ "${actual%% *}" == "$2" ]] || die 'pinned input hash mismatch'
}
baseline_manifest() {
	(
		cd -- "$BASELINE"
		find -P openssh-build openssl-prefix sources/openssh-10.6p1 evidence/source-notices -type f -print0 |
			LC_ALL=C sort -z | xargs -0 sha256sum
	) >"$1"
}
cleanup() {
	if [[ -n "$scratch_dir" && -d "$scratch_dir" && "$scratch_dir" == /tmp/ctos-password-client.* ]]; then
		rm -rf -- "$scratch_dir"
	fi
}
parse_args() {
	while [[ $# -gt 0 ]]; do
		case "$1" in
		--output)
			[[ $# -ge 2 ]] || die '--output requires a new directory'
			output_dir=$2
			shift 2
			;;
		--client | --interop | --key-interop | --issuer)
			mode=${1#--}
			shift
			;;
		--help | -h)
			usage
			exit 0
			;;
		*) die "unknown argument: $1" ;;
		esac
	done
	[[ "$output_dir" == /app/build/* && "$output_dir" != */../* && "$output_dir" != */./* ]] ||
		die 'output must be a clean absolute ignored /app/build path'
	[[ "$(realpath -m -- "$output_dir")" == "$output_dir" && ! -e "$output_dir" && ! -L "$output_dir" ]] ||
		die 'output must be canonical and new'
}
main() {
	local tool
	for tool in realpath sha256sum mktemp mkdir cp rm grep find sort xargs cmp; do require_command "$tool"; done
	parse_args "$@"
	trap cleanup EXIT
	[[ -f /.dockerenv && -f "$PROJECT_ROOT/hako-env.sh" ]] || die 'run inside the project hako container'
	fixed_hash "$UP_BUILD/config.h" d97a4963a0a83f1917c91608d2720e1d7cdfa3ba74a7550d3fc5c7dd8e03f75d
	fixed_hash "$UP_BUILD/openbsd-compat/libopenbsd-compat.a" a4d908e93ecefd43c09e896d0a50fd3d87f668e73378c06648211bc001e51a17
	fixed_hash "$SSL/lib/libcrypto.a" feb776737f8f84d4110492a03e4c4d91312a65638408851c3a509aabb9736376
	local cc="$NDK_BIN/aarch64-linux-android28-clang"
	[[ -x "$cc" && -x "$NDK_BIN/llvm-ar" && -x "$NDK_BIN/llvm-nm" ]] || die 'fixed NDK missing'
	local archive="$PROJECT_ROOT/build/app/generated/sshProduction/arm64-v8a/libctos_ssh_production.a"
	[[ -f "$archive" && ! -L "$archive" ]] || die 'build current production archive first'
	[[ -f "$PRODUCTION_INCLUDE/hostfile.h" && ! -L "$PRODUCTION_INCLUDE/hostfile.h" ]] ||
		die 'generated production hostfile.h missing'
	scratch_dir=$(mktemp -d /tmp/ctos-password-client.XXXXXXXX)
	baseline_manifest "$scratch_dir/baseline-before.sha256"
	fixed_hash "$scratch_dir/baseline-before.sha256" "$BASELINE_HASH"
	if grep -En '^#define DEBUG_PK(OK)?([[:space:]]|$)' "$UP_BUILD/config.h"; then die 'secret-dumping debug macros enabled'; fi
	cp -- "$UP_BUILD/openbsd-compat/libopenbsd-compat.a" "$scratch_dir/compat-static.a"
	"$NDK_BIN/llvm-ar" d "$scratch_dir/compat-static.a" reallocarray.o
	printf '_Thread_local _Alignas(64) unsigned char ctos_static_test_tls_alignment;\n' >"$scratch_dir/tls.c"
	local binary=ctos_ssh_native_client_test test_source="$TEST_CPP/ssh_native_client_test.c"
	if [[ "$mode" == interop || "$mode" == key-interop ]]; then
		binary=ctos_ssh_password_interop_test
		test_source="$TEST_CPP/ssh_password_interop_test.c"
		if [[ "$mode" == key-interop ]]; then binary=ctos_ssh_key_interop_test; fi
	elif [[ "$mode" == issuer ]]; then
		binary=ctos_ssh_hostfile_issuer_test
		test_source="$TEST_CPP/ssh_hostfile_issuer_test.c"
	fi
	local -a native_sources=() inputs=("${BASH_SOURCE[0]}" "$TEST_CPP/ssh_native_client_test.c" "$test_source" "$archive" "$PRODUCTION_INCLUDE/hostfile.h")
	local unit
	for unit in ssh_file_view ssh_view_handle ssh_role_bootstrap ssh_callback_adapter ssh_hostfile_issuer ssh_kex_guard ssh_native_client ssh_key_policy; do
		native_sources+=("$CPP/$unit.c")
		inputs+=("$CPP/$unit.c" "$CPP/$unit.h")
	done
	if [[ "$mode" == key-interop ]]; then
		native_sources+=("$CPP/ssh_material_inspect.c")
		inputs+=("$CPP/ssh_material_inspect.c" "$CPP/ssh_material_inspect.h")
	fi
	local -a flags=(
		-std=c11 -DCTOS_ANDROID_ABI_BASELINE_ONLY=1 -DHAVE_CONFIG_H
		-I"$CPP" -I"$PRODUCTION_INCLUDE" -I"$SOURCE" -I"$UP_BUILD" -I"$UP_BUILD/openbsd-compat/include" -I"$SSL/include"
		-O2 -Wall -Wextra -Werror -Wno-pointer-sign -Wno-unused-parameter -Wno-unused-result
		-Wno-deprecated-declarations -Wno-misleading-indentation
		-static -fno-pie -fno-emulated-tls '-Wl,--no-undefined' '-Wl,-z,max-page-size=16384'
	)
	if [[ "$mode" == key-interop ]]; then flags+=(-DCTOS_INTEROP_PRIVATE_KEY=1); fi
	mkdir -p -- "${output_dir%/*}"
	mkdir -- "$output_dir"
	cp -- "$scratch_dir/baseline-before.sha256" "$output_dir/baseline-before.sha256"
	cp -- "$scratch_dir/tls.c" "$output_dir/tls.c"
	cp -- "$scratch_dir/compat-static.a" "$output_dir/compat-static.a"
	sha256sum -- "${inputs[@]}" "$output_dir/tls.c" "$output_dir/compat-static.a" >"$output_dir/inputs.sha256"
	"$cc" "${flags[@]}" "${native_sources[@]}" "$test_source" "$output_dir/tls.c" \
		-Wl,--start-group "$archive" "$output_dir/compat-static.a" "$SSL/lib/libcrypto.a" -Wl,--end-group -lz -ldl \
		-o "$output_dir/$binary"
	"$NDK_BIN/llvm-nm" --defined-only "$output_dir/$binary" >"$output_dir/symbols.txt"
	cp -- "$SOURCE/LICENCE" "$output_dir/OPENSSH-LICENCE"
	baseline_manifest "$output_dir/baseline-after.sha256"
	cmp -- "$output_dir/baseline-before.sha256" "$output_dir/baseline-after.sha256" || die 'pinned baseline changed during build'
	sha256sum -- "${inputs[@]}" "$output_dir/tls.c" "$output_dir/compat-static.a" >"$output_dir/inputs-after.sha256"
	cmp -- "$output_dir/inputs.sha256" "$output_dir/inputs-after.sha256" || die 'source inputs changed during build'
	sha256sum -- "$output_dir/$binary" >"$output_dir/binary.sha256"
	printf 'pinned=OpenSSH10.6p1\nndk=27.0.12077973\nabi=arm64-v8a\nlibc=Bionic\nlink=static\nmode=%s\nruntime=not-run\npackaged_product=0\nandroid_ui=not-tested\n' "$mode" >"$output_dir/scope.txt"
	printf 'SSH_PASSWORD_CLIENT_BUILD gate=pass mode=%s runtime=not-run\n' "$mode"
}
main "$@"
