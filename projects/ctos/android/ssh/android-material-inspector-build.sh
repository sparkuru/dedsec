#!/usr/bin/env bash
set -Eeuo pipefail

readonly PROJECT_ROOT=/app
readonly BASELINE="$PROJECT_ROOT/build/ssh-native-g1/stock-run.lOHF9Oai"
readonly SOURCE="$BASELINE/sources/openssh-10.6p1"
readonly UP_BUILD="$BASELINE/openssh-build"
readonly SSL="$BASELINE/openssl-prefix"
readonly CPP="$PROJECT_ROOT/android/app/src/main/cpp"
readonly TEST="$PROJECT_ROOT/android/app/src/test/cpp/ssh_material_inspect_test.c"
readonly PAIR_TEST="$PROJECT_ROOT/android/app/src/test/cpp/ssh_key_association_test.c"
readonly PRIVATE_TEST="$PROJECT_ROOT/android/app/src/test/cpp/ssh_private_material_test.c"
readonly CERTIFICATE_TEST="$PROJECT_ROOT/android/app/src/test/cpp/ssh_certificate_facts_test.c"
readonly NDK_BIN="$PROJECT_ROOT/.devhome/android-sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/bin"
readonly STYLE_TITLE=$'\033[1;36m'
readonly STYLE_ERROR=$'\033[1;31m'
readonly STYLE_RESET=$'\033[0m'
output_dir=''
mode='test'
scratch_dir=''

style_text() {
	if [[ -t 2 && -z "${NO_COLOR:-}" ]]; then
		printf '%s%s%s' "$1" "$2" "$STYLE_RESET" >&2
	else
		printf '%s' "$2" >&2
	fi
}
usage() {
	style_text "$STYLE_TITLE" 'Pinned context-free SSH material inspector'
	printf '\nUsage: %s --output <new-directory> [--test | --static-test | --pair-test | --static-pair-test | --private-test | --static-private-test | --certificate-test | --static-certificate-test]\n' "${0##*/}" >&2
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
	if [[ -n "$scratch_dir" && -d "$scratch_dir" && "$scratch_dir" == /tmp/ctos-material-inspector.* ]]; then
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
		--test)
			mode='test'
			shift
			;;
		--static-test)
			mode=static
			shift
			;;
		--pair-test)
			mode=pair
			shift
			;;
		--static-pair-test)
			mode=static-pair
			shift
			;;
		--private-test)
			mode=private
			shift
			;;
		--static-private-test)
			mode=static-private
			shift
			;;
		--certificate-test)
			mode=certificate
			shift
			;;
		--static-certificate-test)
			mode=static-certificate
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
	[[ "$(realpath -m -- "$output_dir")" == "$output_dir" && ! -e "$output_dir" ]] || die 'output must be canonical and new'
}
main() {
	parse_args "$@"
	trap cleanup EXIT
	for tool in realpath sha256sum mktemp mkdir cp rm grep find sort xargs cmp; do require_command "$tool"; done
	[[ -f /.dockerenv && -f "$PROJECT_ROOT/hako-env.sh" ]] || die 'run inside the project hako container'
	fixed_hash "$UP_BUILD/config.h" d97a4963a0a83f1917c91608d2720e1d7cdfa3ba74a7550d3fc5c7dd8e03f75d
	fixed_hash "$UP_BUILD/openbsd-compat/libopenbsd-compat.a" a4d908e93ecefd43c09e896d0a50fd3d87f668e73378c06648211bc001e51a17
	fixed_hash "$SSL/lib/libcrypto.a" feb776737f8f84d4110492a03e4c4d91312a65638408851c3a509aabb9736376
	scratch_dir=$(mktemp -d /tmp/ctos-material-inspector.XXXXXXXX)
	baseline_manifest "$scratch_dir/baseline-before.sha256"
	fixed_hash "$scratch_dir/baseline-before.sha256" e3b0c5215b8050db328bd8773cc4ba00212eb9f8b21d924ed0ab207501ef0ed6
	if grep -En '^#define DEBUG_PK(OK)?([[:space:]]|$)' "$UP_BUILD/config.h"; then die 'secret-dumping debug macros enabled'; fi
	local cc="$NDK_BIN/aarch64-linux-android28-clang"
	[[ -x "$cc" && -x "$NDK_BIN/llvm-ar" && -x "$NDK_BIN/llvm-nm" ]] || die 'fixed NDK missing'
	local archive="$PROJECT_ROOT/build/app/generated/sshProduction/arm64-v8a/libctos_ssh_production.a"
	[[ -f "$archive" ]] || die 'build production archive first'
	local compat="$UP_BUILD/openbsd-compat/libopenbsd-compat.a"
	local -a flags=(
		-std=c11 -DCTOS_ANDROID_ABI_BASELINE_ONLY=1 -DHAVE_CONFIG_H
		-I"$CPP" -I"$SOURCE" -I"$UP_BUILD" -I"$UP_BUILD/openbsd-compat/include" -I"$SSL/include"
		-O2 -Wall -Wextra -Werror -Wno-pointer-sign -Wno-unused-parameter -Wno-unused-result
		-Wno-deprecated-declarations -Wno-misleading-indentation
	)
	local -a link_flags=(-fPIE -pie)
	if [[ "$mode" == static || "$mode" == static-pair || "$mode" == static-private || "$mode" == static-certificate ]]; then
		cp -- "$compat" "$scratch_dir/compat-static.a"
		"$NDK_BIN/llvm-ar" d "$scratch_dir/compat-static.a" reallocarray.o
		compat="$scratch_dir/compat-static.a"
		link_flags=(-static -fno-pie -fno-emulated-tls -DCTOS_MATERIAL_STATIC_TEST=1)
	fi
	local -a wraps=()
	local effect
	for effect in fopen fopen64 open open64 access stat lstat opendir getpwuid gethostname getservbyname getenv getifaddrs getaddrinfo socket connect fork vfork execv execve sshlog clock_gettime fmemopen fclose ferror sshkey_parse_private_fileblob bcrypt_pbkdf; do
		wraps+=("-Wl,--wrap=$effect")
	done
	local test_source="$TEST" binary='ctos_ssh_material_inspect_test'
	local -a test_inputs=("$TEST")
	if [[ "$mode" == pair || "$mode" == static-pair || "$mode" == private || "$mode" == static-private || "$mode" == certificate || "$mode" == static-certificate ]]; then
		test_source="$PAIR_TEST"
		binary='ctos_ssh_key_association_test'
		test_inputs+=("$PAIR_TEST")
		wraps+=('-Wl,--wrap=sshkey_sign' '-Wl,--wrap=sshkey_verify')
	fi
	if [[ "$mode" == private || "$mode" == static-private || "$mode" == certificate || "$mode" == static-certificate ]]; then
		test_source="$PRIVATE_TEST"
		binary='ctos_ssh_private_material_test'
		test_inputs+=("$PRIVATE_TEST")
	fi
	if [[ "$mode" == certificate || "$mode" == static-certificate ]]; then
		test_source="$CERTIFICATE_TEST"
		binary='ctos_ssh_certificate_facts_test'
		test_inputs+=("$CERTIFICATE_TEST")
	fi
	mkdir -p -- "$output_dir"
	cp -- "$scratch_dir/baseline-before.sha256" "$output_dir/baseline.sha256"
	sha256sum -- "$CPP/ssh_material_inspect.c" "$CPP/ssh_material_inspect.h" "$CPP/ssh_material_jni.c" \
		"$CPP/ssh_key_policy.c" "$CPP/ssh_key_policy.h" "${test_inputs[@]}" "$archive" >"$output_dir/inputs.sha256"
	"$cc" "${flags[@]}" "${link_flags[@]}" -Wl,--no-undefined -Wl,-z,max-page-size=16384 \
		"$CPP/ssh_key_policy.c" "$CPP/ssh_material_inspect.c" "$test_source" "${wraps[@]}" \
		-Wl,--start-group "$archive" "$compat" "$SSL/lib/libcrypto.a" -Wl,--end-group -lz -ldl \
		-o "$output_dir/$binary"
	"$NDK_BIN/llvm-nm" --defined-only "$output_dir/$binary" >"$output_dir/symbols.txt"
	cp -- "$SOURCE/LICENCE" "$output_dir/OPENSSH-LICENCE"
	printf 'pinned=OpenSSH10.6p1\ncontent_only=1\narchive83=unchanged\nmode=%s\n' "$mode" >"$output_dir/scope.txt"
	baseline_manifest "$scratch_dir/baseline-after.sha256"
	cmp -- "$scratch_dir/baseline-before.sha256" "$scratch_dir/baseline-after.sha256" || die 'pinned baseline changed during build'
	sha256sum -- "$output_dir/$binary" >"$output_dir/binary.sha256"
	printf 'SSH_MATERIAL_INSPECTOR_BUILD gate=pass mode=%s runtime=not-run\n' "$mode"
}
main "$@"
