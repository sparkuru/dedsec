#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly SCRIPT_DIR
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd -P)"
readonly PROJECT_ROOT
readonly CPP="$PROJECT_ROOT/android/app/src/main/cpp"
readonly JAVA="$PROJECT_ROOT/android/app/src/main/java/im/majo/ctos"
readonly TEST="$PROJECT_ROOT/android/app/src/test/jni-config/im/majo/ctos"
readonly ARCHIVE="${CTOS_OPENSSH_ARCHIVE:-$PROJECT_ROOT/build/ssh-native-g1/stock-run.lOHF9Oai/inputs/openssh-10.6p1.tar.gz}"
readonly RELEASE_CLASSES="$PROJECT_ROOT/build/app/intermediates/javac/release/compileReleaseJavaWithJavac/classes"
readonly JNI_HEADERS="$PROJECT_ROOT/.devhome/android-sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include"
readonly SOURCE_SHA=a9dc9565dffe8640f64d863cd29a32bc4a3dbdec0566a7fc44c5d6ee767d5f39
readonly PATCH_SHA=a6a01e3d5336d0fa69d02b1907b3a304b28a15041d8af2d0f1f3e3b0fe98705b
readonly JNI_SHA=5df9074457c041b372f2c68ff646d6e8600cd673a223885e8df21c44c94ec667

usage() {
	printf 'Usage: %s --output <new-project-build-directory>\n' "${0##*/}" >&2
	printf 'Run the host JVM against production route Java/JNI and pinned OpenSSH.\n' >&2
	printf 'Requires existing release Java classes, NDK JNI headers, GCC, a JDK and OpenSSL development files.\n' >&2
	printf 'CTOS_OPENSSH_ARCHIVE may select the pinned source archive by its verified SHA-256.\n' >&2
}

die() {
	printf 'Error: %s\n' "$*" >&2
	exit 1
}

verify_hash() {
	local actual
	[[ -f "$1" && ! -L "$1" ]] || die "missing regular input: $1"
	actual=$(sha256sum -- "$1")
	[[ "${actual%% *}" == "$2" ]] || die "input hash mismatch: $1"
}

class_manifest() {
	(
		cd -- "$1"
		find -P . -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum
	) >"$2"
}

run_logged() {
	local log=$1
	shift
	if ! "$@" >"$log" 2>&1; then
		tail -n 60 -- "$log" >&2
		die "command failed: $1"
	fi
}

main() {
	if [[ $# -eq 1 && ($1 == --help || $1 == -h) ]]; then
		usage
		return 0
	fi
	[[ $# -eq 2 && $1 == --output ]] || {
		usage
		return 2
	}
	local tool output_dir source_dir release_snapshot unit
	for tool in cc java make patch tar timeout sha256sum find sort xargs cmp cp mkdir realpath grep tail uname cat env; do
		command -v "$tool" >/dev/null 2>&1 || die "missing required command: $tool"
	done
	[[ "$(uname -s)" == Linux && -r /proc/uptime ]] || die 'Linux with /proc/uptime is required'
	[[ -d "$RELEASE_CLASSES" ]] || die 'release Java classes are required; compile them first'
	verify_hash "$ARCHIVE" "$SOURCE_SHA"
	verify_hash "$SCRIPT_DIR/openssh-10.6p1-config-evaluator.patch" "$PATCH_SHA"
	verify_hash "$JNI_HEADERS/jni.h" "$JNI_SHA"
	output_dir=$(realpath -m -- "$2")
	[[ "$output_dir" == "$PROJECT_ROOT/build/"* && "$output_dir" != "$PROJECT_ROOT/build/" ]] ||
		die 'output must be a new directory under the project build directory'
	[[ ! -e "$output_dir" ]] || die 'output directory already exists'
	mkdir -p -- "$output_dir"
	local -a native_sources=() inputs=(
		"$SCRIPT_DIR/host-config-route-jni-test.sh" "$ARCHIVE" "$JNI_HEADERS/jni.h"
		"$SCRIPT_DIR/openssh-10.6p1-config-evaluator.patch" "$CPP"/ssh_*.h
		"$JAVA/SshConfigEvaluator.java" "$JAVA/SshConnectionMaterial.java" "$JAVA/VaultException.java"
		"$JAVA/VaultCodec.java" "$JAVA/VaultCollections.java" "$JAVA/VaultSshMetadata.java" "$JAVA/VaultManifest.java"
		"$TEST/SshConfigRouteRealJniTest.java" "$TEST/SshConnectionMaterialRouteRealJniTest.java"
	)
	for unit in ssh_file_view ssh_view_handle ssh_file_io ssh_config_eval ssh_config_handle ssh_config_jni; do
		native_sources+=("$CPP/$unit.c")
	done
	inputs+=("${native_sources[@]}")
	sha256sum -- "${inputs[@]}" >"$output_dir/inputs-before.sha256"
	class_manifest "$RELEASE_CLASSES" "$output_dir/release-before.sha256"
	release_snapshot="$output_dir/release-classes"
	mkdir -- "$release_snapshot"
	cp -a -- "$RELEASE_CLASSES/." "$release_snapshot/"
	class_manifest "$release_snapshot" "$output_dir/release-snapshot.sha256"
	cmp -s -- "$output_dir/release-before.sha256" "$output_dir/release-snapshot.sha256" || die 'release classes changed during copying'
	{
		cc --version
		java -version
	} >"$output_dir/toolchain.txt" 2>&1
	tar -xzf "$ARCHIVE" -C "$output_dir"
	source_dir="$output_dir/openssh-10.6p1"
	(
		cd -- "$source_dir"
		run_logged "$output_dir/configure.log" env CPPFLAGS="-I$CPP" CFLAGS='-O2 -fPIC' ./configure --without-pam
		run_logged "$output_dir/make.log" make -j2 CFLAGS='-O2 -fPIC' \
			libssh.a openbsd-compat/libopenbsd-compat.a ssh-pkcs11.o ssh-sk-client.o
	)
	cp -- "$source_dir/readconf.c" "$output_dir/readconf.c"
	patch --batch --fuzz=0 --silent -d "$output_dir" -p1 <"$SCRIPT_DIR/openssh-10.6p1-config-evaluator.patch"
	run_logged "$output_dir/cc-jni.log" cc -std=c11 -DHAVE_CONFIG_H -DCTOS_ANDROID_ABI_BASELINE_ONLY=1 \
		-I"$CPP" -isystem "$source_dir" -isystem "$source_dir/openbsd-compat/include" -idirafter "$JNI_HEADERS" \
		-O2 -Wall -Wextra -Werror -Wno-format-truncation -Wno-pointer-sign -Wno-unused-parameter \
		-Wno-unused-result -Wno-deprecated-declarations -Wno-misleading-indentation -fPIC -shared -Wl,--no-undefined \
		"${native_sources[@]}" "$output_dir/readconf.c" -Wl,--start-group "$source_dir/libssh.a" \
		"$source_dir/openbsd-compat/libopenbsd-compat.a" "$source_dir/ssh-pkcs11.o" "$source_dir/ssh-sk-client.o" \
		-Wl,--end-group -lcrypto -lz -ldl -lpthread -lutil -o "$output_dir/libctos_ssh_files.so"
	mkdir -p -- "$output_dir/classes" "$output_dir/stubs/android/os"
	cat >"$output_dir/VaultEnvelope.java" <<'JAVA'
package im.majo.ctos;
final class VaultEnvelope { static final int MAX_PLAINTEXT = 12 * 1024 * 1024; }
JAVA
	cat >"$output_dir/SshPublicFileLease.java" <<'JAVA'
package im.majo.ctos;
final class SshPublicFileLease {
    long nativeToken() throws VaultException { throw VaultException.invalid(); }
    boolean active() { return false; }
}
JAVA
	cat >"$output_dir/stubs/android/os/Process.java" <<'JAVA'
package android.os;
public final class Process { public static int myUid() { return 1000; } }
JAVA
	cat >"$output_dir/stubs/android/os/SystemClock.java" <<'JAVA'
package android.os;
public final class SystemClock { public static long elapsedRealtime() { return System.nanoTime() / 1000000; } }
JAVA
	cat >"$output_dir/SshNativeRoleBootstrap.java" <<'JAVA'
package im.majo.ctos;
final class SshNativeRoleBootstrap {
    static String[] systemIdentity() { return new String[] {"localuser", "local.example"}; }
}
JAVA
	cat >"$output_dir/AppSecrets.java" <<'JAVA'
package im.majo.ctos;
final class AppSecrets { interface EffectGuard { void check() throws VaultException; } }
JAVA
	run_logged "$output_dir/javac.log" java -m jdk.compiler/com.sun.tools.javac.Main \
		-cp "$release_snapshot" -d "$output_dir/classes" "$output_dir/VaultEnvelope.java" \
		"$output_dir/SshPublicFileLease.java" "$output_dir/stubs/android/os/Process.java" \
		"$output_dir/stubs/android/os/SystemClock.java" "$output_dir/SshNativeRoleBootstrap.java" "$output_dir/AppSecrets.java" \
		"$JAVA/VaultException.java" "$JAVA/VaultCodec.java" "$JAVA/VaultCollections.java" "$JAVA/VaultSshMetadata.java" \
		"$JAVA/VaultManifest.java" "$JAVA/SshConfigEvaluator.java" "$JAVA/SshConnectionMaterial.java" \
		"$TEST/SshConfigRouteRealJniTest.java" "$TEST/SshConnectionMaterialRouteRealJniTest.java"
	local class_name
	for class_name in SshConfigRouteRealJniTest SshConnectionMaterialRouteRealJniTest; do
		run_logged "$output_dir/$class_name.log" timeout 120 java -Xcheck:jni -Djava.library.path="$output_dir" \
			-cp "$output_dir/classes:$release_snapshot" "im.majo.ctos.$class_name"
		cat -- "$output_dir/$class_name.log"
		if grep -Eqi 'WARNING in native method|WARNING.*JNI|FATAL ERROR in native method|JNI DETECTED ERROR' "$output_dir/$class_name.log"; then
			die 'JVM JNI checks reported a warning or error'
		fi
	done
	sha256sum -- "${inputs[@]}" >"$output_dir/inputs-after.sha256"
	cmp -s -- "$output_dir/inputs-before.sha256" "$output_dir/inputs-after.sha256" || die 'source inputs changed during the test'
	class_manifest "$RELEASE_CLASSES" "$output_dir/release-after.sha256"
	cmp -s -- "$output_dir/release-before.sha256" "$output_dir/release-after.sha256" || die 'release classes changed during the test'
	sha256sum -- "$output_dir/libctos_ssh_files.so" >"$output_dir/binary.sha256"
	class_manifest "$output_dir/classes" "$output_dir/compiled-classes.sha256"
	printf 'CONFIG_ROUTE_JNI_INPUTS unchanged=pass\n'
}

main "$@"
