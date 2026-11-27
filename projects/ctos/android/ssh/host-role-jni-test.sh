#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly SCRIPT_DIR
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd -P)"
readonly PROJECT_ROOT
readonly CPP="$PROJECT_ROOT/android/app/src/main/cpp"
readonly JAVA="$PROJECT_ROOT/android/app/src/main/java/im/majo/ctos"
readonly TEST="$PROJECT_ROOT/android/app/src/test"
readonly JNI_HEADERS="$PROJECT_ROOT/.devhome/android-sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include"
readonly JNI_HEADER_SHA=5df9074457c041b372f2c68ff646d6e8600cd673a223885e8df21c44c94ec667
work_dir=''

usage() {
	printf 'Usage: %s [--help]\n' "${0##*/}" >&2
	printf 'Run C role and real Java/JNI ownership tests on Linux; no Docker or device is required.\n' >&2
	printf 'Requires a C compiler, a JDK with jdk.compiler, and the project Android NDK 27.0.12077973.\n' >&2
}

die() {
	printf 'Error: %s\n' "$*" >&2
	exit 1
}

cleanup() {
	if [[ -n "$work_dir" && -d "$work_dir" && "$work_dir" == /tmp/ctos-role-jni.* ]]; then
		rm -rf -- "$work_dir"
	fi
}

run_logged() {
	local log=$1
	shift
	if ! "$@" >"$log" 2>&1; then
		cat -- "$log" >&2
		die "command failed: $1"
	fi
}

check_jni_log() {
	local log=$1
	local expected=$2
	cat -- "$log"
	if grep -Eqi 'WARNING in native method|WARNING.*JNI|FATAL ERROR in native method|JNI DETECTED ERROR' "$log"; then
		die 'JVM JNI checks reported a warning or error'
	fi
	grep -Fxq -- "$expected" "$log" || die 'expected JNI test result was not emitted'
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
	local tool header_hash
	for tool in cc java uname dirname mktemp mkdir rm cat grep sha256sum cmp timeout; do
		command -v "$tool" >/dev/null 2>&1 || die "missing required command: $tool"
	done
	[[ "$(uname -s)" == Linux && -r /proc/uptime ]] || die 'Linux with /proc/uptime is required'
	[[ -f "$JNI_HEADERS/jni.h" && ! -L "$JNI_HEADERS/jni.h" ]] || die 'project NDK JNI header not found'
	header_hash=$(sha256sum -- "$JNI_HEADERS/jni.h")
	[[ "${header_hash%% *}" == "$JNI_HEADER_SHA" ]] || die 'project NDK JNI header hash mismatch'

	work_dir=$(mktemp -d /tmp/ctos-role-jni.XXXXXXXX)
	trap cleanup EXIT
	local -a inputs=(
		"$SCRIPT_DIR/host-role-jni-test.sh" "$JNI_HEADERS/jni.h"
		"$CPP/ssh_role_bootstrap.c" "$CPP/ssh_role_bootstrap.h" "$CPP/ssh_role_jni.c"
		"$JAVA/VaultException.java" "$JAVA/VaultCodec.java" "$JAVA/SshRoleContext.java"
		"$JAVA/SshRoleRegistry.java" "$JAVA/SshNativeRoleBootstrap.java"
		"$TEST/cpp/ssh_role_bootstrap_test.c"
		"$TEST/jni-role/im/majo/ctos/SshNativeRoleBootstrap.java"
		"$TEST/jni-role/im/majo/ctos/SshRoleRealJniTest.java"
	)
	sha256sum -- "${inputs[@]}" >"$work_dir/inputs-before.sha256"

	cc -std=c11 -O2 -Wall -Wextra -Werror -pthread -I"$CPP" \
		"$CPP/ssh_role_bootstrap.c" "$TEST/cpp/ssh_role_bootstrap_test.c" -o "$work_dir/role-test"
	run_logged "$work_dir/c-role.log" timeout 20 "$work_dir/role-test"
	cat -- "$work_dir/c-role.log"

	run_logged "$work_dir/cc-jni.log" cc -std=c11 -O2 -Wall -Wextra -Werror -fPIC -shared -pthread -H \
		-I"$CPP" -idirafter "$JNI_HEADERS" "$CPP/ssh_role_bootstrap.c" "$CPP/ssh_role_jni.c" \
		-o "$work_dir/libctos_ssh_files.so"
	grep -Fq -- " $JNI_HEADERS/jni.h" "$work_dir/cc-jni.log" || die 'compiler did not use the verified NDK JNI header'

	mkdir -- "$work_dir/shim-classes" "$work_dir/production-classes"
	java -m jdk.compiler/com.sun.tools.javac.Main -d "$work_dir/shim-classes" \
		"$TEST/jni-role/im/majo/ctos/SshNativeRoleBootstrap.java"
	run_logged "$work_dir/shim.log" timeout 20 java -Xcheck:jni -cp "$work_dir/shim-classes" \
		im.majo.ctos.SshNativeRoleBootstrap "$work_dir/libctos_ssh_files.so"
	check_jni_log "$work_dir/shim.log" \
		'ROLE_JNI v1-v2-canonical-ownership=pass tampering=rejected cleanup=pass'

	cat >"$work_dir/AppSecrets.java" <<'JAVA'
package im.majo.ctos;
final class AppSecrets {
    interface EffectGuard { void check() throws VaultException; }
}
JAVA
	java -m jdk.compiler/com.sun.tools.javac.Main -d "$work_dir/production-classes" \
		"$work_dir/AppSecrets.java" "$JAVA/VaultException.java" "$JAVA/VaultCodec.java" \
		"$JAVA/SshRoleContext.java" "$JAVA/SshRoleRegistry.java" "$JAVA/SshNativeRoleBootstrap.java" \
		"$TEST/jni-role/im/majo/ctos/SshRoleRealJniTest.java"
	run_logged "$work_dir/production.log" timeout 20 java -Xcheck:jni -Djava.library.path="$work_dir" \
		-cp "$work_dir/production-classes" im.majo.ctos.SshRoleRealJniTest
	check_jni_log "$work_dir/production.log" \
		'ROLE_REAL_JNI java-native-v2=pass forged-and-stale=rejected cleanup=pass'

	sha256sum -- "${inputs[@]}" >"$work_dir/inputs-after.sha256"
	cmp -s -- "$work_dir/inputs-before.sha256" "$work_dir/inputs-after.sha256" || die 'source inputs changed during the test'
	printf 'ROLE_JNI_INPUTS unchanged=pass\n'
}

main "$@"
