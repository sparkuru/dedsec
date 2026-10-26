#!/usr/bin/env bash
set -Eeuo pipefail

readonly PROJECT_ROOT=/app
readonly BASELINE="$PROJECT_ROOT/build/ssh-native-g1/stock-run.lOHF9Oai"
readonly SOURCE="$BASELINE/sources/openssh-10.6p1"
readonly UP_BUILD="$BASELINE/openssh-build"
readonly SSL="$BASELINE/openssl-prefix"
readonly CPP="$PROJECT_ROOT/android/app/src/main/cpp"
readonly BUILD_ROOT="$PROJECT_ROOT/android/ssh"
readonly NDK_BIN="$PROJECT_ROOT/.devhome/android-sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/bin"
readonly PATCH_SHA=7afd7a9a4ed9c17096b1bb4a3cd20d41ec9ed2d5720df5c552da93384d60fd5e
readonly STYLE_TITLE=$'\033[1;36m'
readonly STYLE_ERROR=$'\033[1;31m'
readonly STYLE_RESET=$'\033[0m'
output_dir=''
mode=objects
scratch_dir=''

style_text() {
	if [[ -t 2 && -z "${NO_COLOR:-}" ]]; then
		printf '%s%s%s' "$1" "$2" "$STYLE_RESET" >&2
	else
		printf '%s' "$2" >&2
	fi
}
usage() {
	style_text "$STYLE_TITLE" 'Pinned managed SSH configuration evaluator'
	printf '\nUsage: %s --output <new-directory> [--test | --static-test | --handle-test | --static-handle-test | --catalog-test | --static-catalog-test | --detached-test | --static-detached-test | --managed-detached-test | --static-managed-detached-test | --audit-test | --static-audit-test | --resource-test | --static-resource-test]\n' "${0##*/}" >&2
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
verify_baseline_unchanged() {
	baseline_manifest "$scratch_dir/baseline-after.sha256"
	cmp -- "$scratch_dir/baseline-before.sha256" "$scratch_dir/baseline-after.sha256" || die 'pinned baseline changed during build'
}
cleanup() {
	if [[ -n "$scratch_dir" && -d "$scratch_dir" && "$scratch_dir" == /tmp/ctos-config-evaluator.* ]]; then
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
		--handle-test)
			mode=handle
			shift
			;;
		--static-handle-test)
			mode=static-handle
			shift
			;;
		--catalog-test)
			mode=catalog
			shift
			;;
		--static-catalog-test)
			mode=static-catalog
			shift
			;;
		--detached-test)
			mode=detached
			shift
			;;
		--static-detached-test)
			mode=static-detached
			shift
			;;
		--managed-detached-test)
			mode=managed-detached
			shift
			;;
		--static-managed-detached-test)
			mode=static-managed-detached
			shift
			;;
		--audit-test)
			mode=audit
			shift
			;;
		--static-audit-test)
			mode=static-audit
			shift
			;;
		--resource-test)
			mode=resource
			shift
			;;
		--static-resource-test)
			mode=static-resource
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
	[[ "$(realpath -m -- "$output_dir")" == "$output_dir" ]] || die 'output path must be canonical'
	[[ ! -e "$output_dir" ]] || die 'output directory already exists'
}
main() {
	parse_args "$@"
	trap cleanup EXIT
	for tool in realpath sha256sum mktemp mkdir cp patch rm grep find sort xargs cmp; do require_command "$tool"; done
	[[ -f /.dockerenv && -f "$PROJECT_ROOT/hako-env.sh" ]] || die 'run inside the project hako container'
	fixed_hash "$SOURCE/readconf.c" 437a1eadeb24e4f814265caa61f5266c1aa7fd62cfa04fc1cac9c64f860f9dff
	fixed_hash "$SOURCE/readconf.h" 1de6680b8d7e7dd99d384ecef36470cf61b1782181b2556a5e26ef3e80426ed8
	fixed_hash "$SOURCE/misc.c" f135270c045c472c720b90e129cfa9c58456b324e08d68d3e14aa87ca37de1e0
	fixed_hash "$UP_BUILD/config.h" d97a4963a0a83f1917c91608d2720e1d7cdfa3ba74a7550d3fc5c7dd8e03f75d
	fixed_hash "$UP_BUILD/openbsd-compat/libopenbsd-compat.a" a4d908e93ecefd43c09e896d0a50fd3d87f668e73378c06648211bc001e51a17
	fixed_hash "$SSL/lib/libcrypto.a" feb776737f8f84d4110492a03e4c4d91312a65638408851c3a509aabb9736376
	fixed_hash "$BUILD_ROOT/openssh-10.6p1-config-evaluator.patch" "$PATCH_SHA"
	scratch_dir=$(mktemp -d /tmp/ctos-config-evaluator.XXXXXXXX)
	baseline_manifest "$scratch_dir/baseline-before.sha256"
	fixed_hash "$scratch_dir/baseline-before.sha256" e3b0c5215b8050db328bd8773cc4ba00212eb9f8b21d924ed0ab207501ef0ed6
	cp -- "$SOURCE/readconf.c" "$scratch_dir/readconf.c"
	patch --batch --fuzz=0 --silent -d "$scratch_dir" -p1 <"$BUILD_ROOT/openssh-10.6p1-config-evaluator.patch"
	if grep -En 'fatal\(|fatal_f\(|getenv\(|gethostname\(|getservbyname\(|getifaddrs\(|fork\(|execv\(' "$scratch_dir/readconf.c"; then
		die 'parser effect/fatal audit failed'
	fi
	local cc="$NDK_BIN/aarch64-linux-android28-clang"
	[[ -x "$cc" && -x "$NDK_BIN/llvm-ar" && -x "$NDK_BIN/llvm-nm" ]] || die 'fixed NDK missing'
	local -a flags=(
		-std=c11 -DCTOS_ANDROID_ABI_BASELINE_ONLY=1 -DHAVE_CONFIG_H
		-I"$CPP" -I"$SOURCE" -I"$UP_BUILD" -I"$UP_BUILD/openbsd-compat/include" -I"$SSL/include"
		-O2 -Wall -Wextra -Werror -Wno-pointer-sign -Wno-unused-parameter -Wno-unused-result
		-Wno-deprecated-declarations -Wno-misleading-indentation
	)
	mkdir -p -- "$output_dir"
	cp -- "$scratch_dir/baseline-before.sha256" "$output_dir/baseline.sha256"
	"$cc" "${flags[@]}" -fPIC -c "$scratch_dir/readconf.c" -o "$output_dir/readconf.o"
	"$cc" "${flags[@]}" -fPIC -c "$CPP/ssh_config_eval.c" -o "$output_dir/config_eval.o"
	"$NDK_BIN/llvm-nm" -u "$output_dir/readconf.o" >"$output_dir/parser-undefined.txt"
	if grep -E '\b(fopen|fopen64|_ssh__compat_glob|getenv|getpwuid|gethostname|getservbyname|getaddrinfo|fork|execv|socket|sshlog|sshfatal)$' "$output_dir/parser-undefined.txt"; then
		die 'parser undefined effect audit failed'
	fi
	sha256sum -- "$SOURCE/readconf.c" "$SOURCE/misc.c" "$BUILD_ROOT/openssh-10.6p1-config-evaluator.patch" \
		"$CPP/ssh_config_eval.c" "$CPP/ssh_config_eval.h" "$CPP/ssh_config_eval_hooks.h" >"$output_dir/inputs.sha256"
	cp -- "$SOURCE/LICENCE" "$output_dir/OPENSSH-LICENCE"
	printf 'pinned=OpenSSH10.6p1\nparser=namespaced\narchive83=unchanged\nraw_options=retained\nmode=%s\n' "$mode" >"$output_dir/scope.txt"
	verify_baseline_unchanged
	[[ "$mode" != objects ]] || return 0
	local archive="$PROJECT_ROOT/build/app/generated/sshProduction/arm64-v8a/libctos_ssh_production.a"
	local compat="$UP_BUILD/openbsd-compat/libopenbsd-compat.a"
	local -a link_flags=(-fPIE -pie)
	if [[ "$mode" == static || "$mode" == static-handle || "$mode" == static-catalog || "$mode" == static-detached || "$mode" == static-managed-detached || "$mode" == static-audit || "$mode" == static-resource ]]; then
		cp -- "$compat" "$scratch_dir/compat-static.a"
		"$NDK_BIN/llvm-ar" d "$scratch_dir/compat-static.a" reallocarray.o
		compat="$scratch_dir/compat-static.a"
		link_flags=(-static -fno-pie -fno-emulated-tls -DCTOS_CONFIG_STATIC_TEST=1)
	fi
	local -a wraps=()
	local effect
	local -a test_sources=("$PROJECT_ROOT/android/app/src/test/cpp/ssh_config_eval_test.c")
	local binary=ctos_ssh_config_eval_test
	local -a effects=(fopen fopen64 open open64 access stat lstat opendir getpwuid gethostname getservbyname getenv getifaddrs getaddrinfo socket connect fork vfork execv execve munmap fread ferror sshlog)
	if [[ "$mode" == handle || "$mode" == static-handle ]]; then
		test_sources=("$CPP/ssh_config_handle.c" "$PROJECT_ROOT/android/app/src/test/cpp/ssh_config_handle_test.c")
		binary=ctos_ssh_config_handle_test
		flags+=(-DCTOS_CONFIG_HANDLE_TEST=1)
		effects+=(ctos_ssh_config_eval_open ctos_ssh_config_eval_close ctos_ssh_config_eval_destroy ctos_ssh_view_handle_borrow ctos_ssh_view_borrow_release getpid getuid)
		sha256sum -- "$CPP/ssh_config_handle.c" "$CPP/ssh_config_handle.h" \
			"$PROJECT_ROOT/android/app/src/test/cpp/ssh_config_handle_test.c" >>"$output_dir/inputs.sha256"
	fi
	if [[ "$mode" == catalog || "$mode" == static-catalog ]]; then
		test_sources=("$CPP/ssh_config_handle.c" "$PROJECT_ROOT/android/app/src/test/cpp/ssh_config_catalog_test.c")
		binary=ctos_ssh_config_catalog_test
		flags+=(-DCTOS_CONFIG_HANDLE_TEST=1)
		effects+=(ctos_ssh_config_eval_open_catalog ctos_ssh_view_borrow_release)
		sha256sum -- "$CPP/ssh_config_handle.c" "$CPP/ssh_config_handle.h" \
			"$PROJECT_ROOT/android/app/src/test/cpp/ssh_config_catalog_test.c" >>"$output_dir/inputs.sha256"
	fi
	if [[ "$mode" == detached || "$mode" == static-detached || "$mode" == managed-detached || "$mode" == static-managed-detached || "$mode" == audit || "$mode" == static-audit || "$mode" == resource || "$mode" == static-resource ]]; then
		test_sources=("$PROJECT_ROOT/android/app/src/test/cpp/ssh_config_detached_test.c")
		binary=ctos_ssh_config_detached_test
		effects+=(mlock madvise clock_gettime munlock getpid getuid)
		effects+=(ctos_ssh_view_borrow_live ctos_ssh_view_borrow_view ctos_ssh_view_borrow_release
			ctos_ssh_view_handle_create ctos_ssh_view_handle_borrow ctos_ssh_view_borrow_owned_view)
		sha256sum -- "$CPP/ssh_file_io.c" "$CPP/ssh_file_io.h" \
			"$PROJECT_ROOT/android/app/src/test/cpp/ssh_config_detached_test.c" >>"$output_dir/inputs.sha256"
		if [[ "$mode" == managed-detached || "$mode" == static-managed-detached ]]; then
			test_sources=("$CPP/ssh_config_handle.c" "$PROJECT_ROOT/android/app/src/test/cpp/ssh_config_managed_detached_test.c")
			binary=ctos_ssh_config_managed_detached_test
			flags+=(-DCTOS_CONFIG_HANDLE_TEST=1)
			sha256sum -- "$CPP/ssh_config_handle.c" "$CPP/ssh_config_handle.h" \
				"$PROJECT_ROOT/android/app/src/test/cpp/ssh_config_managed_detached_test.c" >>"$output_dir/inputs.sha256"
		fi
		if [[ "$mode" == audit || "$mode" == static-audit ]]; then
			test_sources=("$CPP/ssh_config_handle.c" "$PROJECT_ROOT/android/app/src/test/cpp/ssh_config_audit_test.c")
			binary=ctos_ssh_config_audit_test
			flags+=(-DCTOS_CONFIG_HANDLE_TEST=1)
			effects+=(ctos_ssh_config_eval_audit)
			sha256sum -- "$CPP/ssh_config_handle.c" "$CPP/ssh_config_handle.h" \
				"$PROJECT_ROOT/android/app/src/test/cpp/ssh_config_audit_test.c" >>"$output_dir/inputs.sha256"
		fi
		if [[ "$mode" == resource || "$mode" == static-resource ]]; then
			test_sources=("$CPP/ssh_config_handle.c" "$PROJECT_ROOT/android/app/src/test/cpp/ssh_config_resource_test.c")
			binary=ctos_ssh_config_resource_test
			flags+=(-DCTOS_CONFIG_HANDLE_TEST=1)
			effects+=(ctos_ssh_config_eval_resource_audit)
			sha256sum -- "$CPP/ssh_config_handle.c" "$CPP/ssh_config_handle.h" \
				"$PROJECT_ROOT/android/app/src/test/cpp/ssh_config_resource_test.c" >>"$output_dir/inputs.sha256"
		fi
	fi
	for effect in "${effects[@]}"; do
		wraps+=("-Wl,--wrap=$effect")
	done
	"$cc" "${flags[@]}" "${link_flags[@]}" -Wl,--no-undefined -Wl,-z,max-page-size=16384 \
		"$CPP/ssh_file_view.c" "$CPP/ssh_view_handle.c" "$CPP/ssh_file_io.c" \
		"${test_sources[@]}" \
		"$output_dir/readconf.o" "$output_dir/config_eval.o" "${wraps[@]}" \
		-Wl,--start-group "$archive" "$compat" "$SSL/lib/libcrypto.a" -Wl,--end-group -lz -ldl \
		-o "$output_dir/$binary"
	verify_baseline_unchanged
	sha256sum -- "$output_dir/$binary" >"$output_dir/binary.sha256"
	printf 'SSH_CONFIG_EVALUATOR_BUILD gate=pass mode=%s runtime=not-run\n' "$mode"
}
main "$@"
