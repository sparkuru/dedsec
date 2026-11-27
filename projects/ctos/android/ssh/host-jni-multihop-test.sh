#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly SCRIPT_DIR
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd -P)"
readonly PROJECT_ROOT
readonly ARCHIVE="${CTOS_OPENSSH_ARCHIVE:-$PROJECT_ROOT/build/ssh-native-g1/stock-run.lOHF9Oai/inputs/openssh-10.6p1.tar.gz}"
readonly SOURCE_SHA=a9dc9565dffe8640f64d863cd29a32bc4a3dbdec0566a7fc44c5d6ee767d5f39
readonly HOSTFILE_SHA=a786566e1842996c0282647457f4dd63558cde00bc79ae1ba6bcd552bf30484a
readonly KEX_SHA=50a59606000dc871f1b29e33eeb6d77fb5da456f40526d2871357758b61f8785
readonly BANNER_SHA=ba61f35303233ebd2bf1c39d167774667766b3461721657f8af0483e51258a18
readonly PROVISIONAL_SHA=075e910206a43452da8e01bb3818e6fb5bc18e0ab8e62a5b5d7c5bd6a0da845a
readonly CPP="$PROJECT_ROOT/android/app/src/main/cpp"
readonly TEST="$PROJECT_ROOT/android/app/src/test"
readonly JNI_HEADERS="$PROJECT_ROOT/.devhome/android-sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include"
work_dir=''

usage() {
	printf 'Usage: bash %s [--help]\n' "${0##*/}" >&2
	printf 'Run a local host-JVM/JNI test with pinned OpenSSH and synthetic jump peers.\n' >&2
	printf 'CTOS_OPENSSH_ARCHIVE may select the pinned source archive by its verified SHA-256.\n' >&2
}

die() {
	printf 'Error: %s\n' "$*" >&2
	exit 1
}

cleanup() {
	if [[ -n "$work_dir" && -d "$work_dir" && "$work_dir" == /tmp/ctos-jni-multihop.* ]]; then
		rm -rf -- "$work_dir"
	fi
}

verify_input() {
	local path=$1
	local expected=$2
	local actual
	[[ -f "$path" && ! -L "$path" ]] || die "missing regular input: $path"
	actual=$(sha256sum -- "$path")
	[[ "${actual%% *}" == "$expected" ]] || die "input hash mismatch: $path"
}

run_logged() {
	local log=$1
	shift
	if ! "$@" >"$log" 2>&1; then
		tail -n 40 -- "$log" >&2
		die "command failed: $1"
	fi
}

main() {
	if [[ $# -eq 1 && ($1 == --help || $1 == -h) ]]; then
		usage
		return 0
	fi
	[[ $# -eq 0 ]] || {
		usage
		return 2
	}
	local tool
	for tool in cc java make openssl patch prlimit sha256sum tar timeout mktemp mkdir rm tail grep cat env uname dirname cmp; do
		command -v "$tool" >/dev/null 2>&1 || die "missing required command: $tool"
	done
	[[ "$(uname -s)" == Linux ]] || die 'Linux is required for this host fixture'
	[[ -f "$JNI_HEADERS/jni.h" ]] || die 'project NDK JNI header not found'
	verify_input "$ARCHIVE" "$SOURCE_SHA"
	verify_input "$SCRIPT_DIR/openssh-10.6p1-hostfile-stream-writer.patch" "$HOSTFILE_SHA"
	verify_input "$SCRIPT_DIR/openssh-10.6p1-kex-guard.patch" "$KEX_SHA"
	verify_input "$SCRIPT_DIR/openssh-10.6p1-banner-atomic.patch" "$BANNER_SHA"
	verify_input "$SCRIPT_DIR/openssh-10.6p1-provisional-input.patch" "$PROVISIONAL_SHA"
	work_dir=$(mktemp -d /tmp/ctos-jni-multihop.XXXXXXXX)
	trap cleanup EXIT
	local -a native_sources=()
	local -a inputs=(
		"$ARCHIVE" "$SCRIPT_DIR/host-jni-multihop-test.sh"
		"$SCRIPT_DIR/openssh-10.6p1-hostfile-stream-writer.patch"
		"$SCRIPT_DIR/openssh-10.6p1-kex-guard.patch" "$SCRIPT_DIR/openssh-10.6p1-banner-atomic.patch"
		"$SCRIPT_DIR/openssh-10.6p1-provisional-input.patch"
		"$CPP/ssh_session_jni.c" "$CPP"/ssh_*.h "$JNI_HEADERS/jni.h"
		"$TEST/cpp/ssh_native_client_test.c" "$TEST/cpp/ssh_callback_jni_multihop_test.c"
		"$TEST/jni-multihop/im/majo/ctos/SshNativeCallbackAdapter.java"
	)
	local unit
	for unit in ssh_file_view ssh_view_handle ssh_role_bootstrap ssh_callback_adapter ssh_callback_jni \
		ssh_hostfile_issuer ssh_hostfile_changed ssh_host_trust ssh_material_inspect ssh_kex_guard \
		ssh_native_client ssh_key_policy; do
		native_sources+=("$CPP/$unit.c")
	done
	inputs+=("${native_sources[@]}")
	sha256sum -- "${inputs[@]}" >"$work_dir/inputs-before.sha256"
	tar -xzf "$ARCHIVE" -C "$work_dir"
	local source_dir="$work_dir/openssh-10.6p1"
	patch --silent -d "$source_dir" -p0 <"$SCRIPT_DIR/openssh-10.6p1-hostfile-stream-writer.patch"
	patch --silent -d "$source_dir" -p1 <"$SCRIPT_DIR/openssh-10.6p1-kex-guard.patch"
	patch --silent -d "$source_dir" -p1 <"$SCRIPT_DIR/openssh-10.6p1-banner-atomic.patch"
	patch --batch --fuzz=0 --silent -d "$source_dir" -p1 <"$SCRIPT_DIR/openssh-10.6p1-provisional-input.patch"
	printf 'Building host OpenSSH with %s\n' "$(openssl version)"
	(
		cd -- "$source_dir"
		run_logged "$work_dir/configure.log" env \
			CPPFLAGS="-I$CPP -DCTOS_SSH_KEX_GUARD=1" CFLAGS='-O2 -fPIC' ./configure --without-pam
		run_logged "$work_dir/make.log" make -j2 CFLAGS='-O2 -fPIC' \
			libssh.a openbsd-compat/libopenbsd-compat.a ssh-pkcs11.o ssh-sk-client.o
	)
	cc -std=c11 -DCTOS_SSH_NATIVE_TEST_HOOKS=1 -DHAVE_CONFIG_H -I"$CPP" \
		-isystem "$source_dir" -isystem "$source_dir/openbsd-compat/include" -idirafter "$JNI_HEADERS" \
		-O2 -Wall -Wextra -Werror -Wno-pointer-sign -Wno-unused-parameter -Wno-unused-result \
		-Wno-deprecated-declarations -Wno-misleading-indentation -fPIC -shared -Wl,--no-undefined \
		"${native_sources[@]}" "$TEST/cpp/ssh_callback_jni_multihop_test.c" \
		-Wl,--start-group "$source_dir/libssh.a" "$source_dir/openbsd-compat/libopenbsd-compat.a" \
		"$source_dir/ssh-pkcs11.o" "$source_dir/ssh-sk-client.o" -Wl,--end-group \
		-lcrypto -lz -ldl -lpthread -lutil -o "$work_dir/libctos_jni_multihop.so"
	mkdir -- "$work_dir/classes"
	java -m jdk.compiler/com.sun.tools.javac.Main -d "$work_dir/classes" \
		"$TEST/jni-multihop/im/majo/ctos/SshNativeCallbackAdapter.java"
	run_logged "$work_dir/jni.log" prlimit --memlock=65536:65536 --core=0:0 -- \
		timeout 120 java -Xcheck:jni -cp "$work_dir/classes" \
		im.majo.ctos.SshNativeCallbackAdapter "$work_dir/libctos_jni_multihop.so"
	cat -- "$work_dir/jni.log"
	if grep -Eq 'WARNING in native method|FATAL ERROR in native method' "$work_dir/jni.log"; then
		die 'JVM JNI checks reported a warning'
	fi
	sha256sum -- "${inputs[@]}" >"$work_dir/inputs-after.sha256"
	cmp -s -- "$work_dir/inputs-before.sha256" "$work_dir/inputs-after.sha256" || die 'source inputs changed during the test'
	printf 'JNI_MULTIHOP_INPUTS unchanged=pass\n'
}

main "$@"
