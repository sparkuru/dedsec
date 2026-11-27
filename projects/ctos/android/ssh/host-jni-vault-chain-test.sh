#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly SCRIPT_DIR
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd -P)"
readonly PROJECT_ROOT
readonly CPP="$PROJECT_ROOT/android/app/src/main/cpp"
readonly JAVA="$PROJECT_ROOT/android/app/src/main/java/im/majo/ctos"
readonly TEST="$PROJECT_ROOT/android/app/src/test"
readonly ARCHIVE="${CTOS_OPENSSH_ARCHIVE:-$PROJECT_ROOT/build/ssh-native-g1/stock-run.lOHF9Oai/inputs/openssh-10.6p1.tar.gz}"
readonly RELEASE_CLASSES="$PROJECT_ROOT/build/app/intermediates/javac/release/compileReleaseJavaWithJavac/classes"
readonly ANDROID_JAR="$PROJECT_ROOT/.devhome/android-sdk/platforms/android-35/android.jar"
readonly JNI_HEADERS="$PROJECT_ROOT/.devhome/android-sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include"
readonly BCPROV="$PROJECT_ROOT/.devhome/gradle-home/caches/modules-2/files-2.1/org.bouncycastle/bcprov-jdk18on/1.86/830c8467bed8e4c1148262386b09fc42aedcf262/bcprov-jdk18on-1.86.jar"
readonly SOURCE_SHA=a9dc9565dffe8640f64d863cd29a32bc4a3dbdec0566a7fc44c5d6ee767d5f39
readonly BCPROV_SHA=2af190b300cbb0b35e248ccf5f4a06b6072030aeb3da7a98ec73abe5b4cb371f
readonly JNI_SHA=5df9074457c041b372f2c68ff646d6e8600cd673a223885e8df21c44c94ec667
readonly HOSTFILE_SHA=a786566e1842996c0282647457f4dd63558cde00bc79ae1ba6bcd552bf30484a
readonly KEX_SHA=50a59606000dc871f1b29e33eeb6d77fb5da456f40526d2871357758b61f8785
readonly BANNER_SHA=ba61f35303233ebd2bf1c39d167774667766b3461721657f8af0483e51258a18
readonly PROVISIONAL_SHA=075e910206a43452da8e01bb3818e6fb5bc18e0ab8e62a5b5d7c5bd6a0da845a

usage() {
	printf 'Usage: bash %s --output <new-directory>\n' "${0##*/}" >&2
	printf 'Exercise production Java/vault/JNI with synthetic SSH peers on the host.\n' >&2
	printf 'Compile current release Java classes first; output must be under /tmp or project build/.\n' >&2
	printf 'CTOS_OPENSSH_ARCHIVE may select the pinned source archive by its verified SHA-256.\n' >&2
}

die() {
	printf 'Error: %s\n' "$*" >&2
	exit 1
}

verify_input() {
	local path=$1 expected=$2 actual
	[[ -f "$path" && ! -L "$path" ]] || die "missing regular input: $path"
	actual=$(sha256sum -- "$path")
	[[ "${actual%% *}" == "$expected" ]] || die "input hash mismatch: $path"
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
	for tool in cc java make openssl patch prlimit sha256sum tar timeout mkdir cp find sort xargs cmp realpath grep tail cat env uname; do
		command -v "$tool" >/dev/null 2>&1 || die "missing required command: $tool"
	done
	[[ "$(uname -s)" == Linux ]] || die 'Linux is required for this host fixture'
	[[ -d "$RELEASE_CLASSES" && -f "$JNI_HEADERS/jni.h" && -f "$BCPROV" && -f "$ANDROID_JAR" ]] ||
		die 'release classes, Android SDK, JNI headers or BouncyCastle are missing'
	verify_input "$ARCHIVE" "$SOURCE_SHA"
	verify_input "$BCPROV" "$BCPROV_SHA"
	verify_input "$JNI_HEADERS/jni.h" "$JNI_SHA"
	verify_input "$SCRIPT_DIR/openssh-10.6p1-hostfile-stream-writer.patch" "$HOSTFILE_SHA"
	verify_input "$SCRIPT_DIR/openssh-10.6p1-kex-guard.patch" "$KEX_SHA"
	verify_input "$SCRIPT_DIR/openssh-10.6p1-banner-atomic.patch" "$BANNER_SHA"
	verify_input "$SCRIPT_DIR/openssh-10.6p1-provisional-input.patch" "$PROVISIONAL_SHA"
	output_dir=$(realpath -m -- "$2")
	[[ "$output_dir" == /tmp/ctos-vault-jni-* || "$output_dir" == "$PROJECT_ROOT/build/"* ]] ||
		die 'output must be a new /tmp/ctos-vault-jni-* or project build/ directory'
	[[ ! -e "$output_dir" ]] || die 'output directory already exists'
	umask 077
	mkdir -p -- "$output_dir"
	local -a native_sources=() inputs=(
		"$SCRIPT_DIR/host-jni-vault-chain-test.sh" "$ARCHIVE" "$JNI_HEADERS/jni.h" "$BCPROV" "$ANDROID_JAR" "$CPP"/ssh_*.h
		"$SCRIPT_DIR/openssh-10.6p1-hostfile-stream-writer.patch"
		"$SCRIPT_DIR/openssh-10.6p1-kex-guard.patch" "$SCRIPT_DIR/openssh-10.6p1-banner-atomic.patch"
		"$SCRIPT_DIR/openssh-10.6p1-provisional-input.patch"
		"$TEST/cpp/ssh_native_client_test.c" "$TEST/cpp/ssh_fresh_vault_jni_test.c"
		"$TEST/jni-vault-chain/im/majo/ctos/SshFreshVaultRealJniTest.java"
		"$TEST/jni-vault-chain/im/majo/ctos/SshConnectorStopRealJniTest.java"
		"$TEST/jni-product-jump/android/os/"*.java "$TEST/jni-product-jump/android/system/"*.java
		"$TEST/jni-product-jump/im/majo/ctos/host/HostLinux.java"
		"$TEST/jni-product-jump/im/majo/ctos/MainActivity.java" "$JAVA"/*.java
		"$TEST/jni-service-lock/android/content/Context.java" "$TEST/jni-service-lock/android/os/"*.java
		"$TEST/jni-service-lock/im/majo/ctos/SshServiceLockedAdmissionRealJniTest.java"
	)
	for unit in ssh_file_view ssh_view_handle ssh_role_bootstrap ssh_callback_adapter ssh_callback_jni \
		ssh_hostfile_issuer ssh_hostfile_changed ssh_host_trust ssh_material_inspect ssh_kex_guard \
		ssh_native_client ssh_key_policy ssh_file_jni ssh_role_jni ssh_session_jni; do
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
		openssl version
	} >"$output_dir/toolchain.txt" 2>&1
	tar -xzf "$ARCHIVE" -C "$output_dir"
	source_dir="$output_dir/openssh-10.6p1"
	patch --batch --fuzz=0 --silent -d "$source_dir" -p0 <"$SCRIPT_DIR/openssh-10.6p1-hostfile-stream-writer.patch"
	patch --batch --fuzz=0 --silent -d "$source_dir" -p1 <"$SCRIPT_DIR/openssh-10.6p1-kex-guard.patch"
	patch --batch --fuzz=0 --silent -d "$source_dir" -p1 <"$SCRIPT_DIR/openssh-10.6p1-banner-atomic.patch"
	patch --batch --fuzz=0 --silent -d "$source_dir" -p1 <"$SCRIPT_DIR/openssh-10.6p1-provisional-input.patch"
	(
		cd -- "$source_dir"
		run_logged "$output_dir/configure.log" env CPPFLAGS="-I$CPP -DCTOS_SSH_KEX_GUARD=1" \
			CFLAGS='-O2 -fPIC' ./configure --without-pam
		run_logged "$output_dir/make.log" make -j2 CFLAGS='-O2 -fPIC' \
			libssh.a openbsd-compat/libopenbsd-compat.a ssh-pkcs11.o ssh-sk-client.o
	)
	run_logged "$output_dir/cc.log" cc -std=c11 -DCTOS_SSH_NATIVE_TEST_HOOKS=1 -DHAVE_CONFIG_H \
		-I"$CPP" -isystem "$source_dir" -isystem "$source_dir/openbsd-compat/include" -idirafter "$JNI_HEADERS" \
		-O2 -Wall -Wextra -Werror -Wno-pointer-sign -Wno-unused-parameter -Wno-unused-result \
		-Wno-deprecated-declarations -Wno-misleading-indentation -fPIC -shared -Wl,--no-undefined \
		-Wl,--wrap=ctos_ssh_callback_known_hosts_candidate -Wl,--wrap=ctos_ssh_callback_changed_host_candidate \
		-Wl,--wrap=ctos_ssh_callback_credential_bytes_selected \
		-Wl,--wrap=ctos_ssh_view_allocate -Wl,--wrap=ctos_ssh_view_handle_destroy -Wl,--wrap=socketpair \
		"${native_sources[@]}" "$TEST/cpp/ssh_fresh_vault_jni_test.c" \
		-Wl,--start-group "$source_dir/libssh.a" "$source_dir/openbsd-compat/libopenbsd-compat.a" \
		"$source_dir/ssh-pkcs11.o" "$source_dir/ssh-sk-client.o" -Wl,--end-group \
		-lcrypto -lz -ldl -lpthread -lutil -o "$output_dir/libctos_ssh_files.so"
	mkdir -- "$output_dir/classes"
	run_logged "$output_dir/javac.log" java -m jdk.compiler/com.sun.tools.javac.Main \
		-cp "$release_snapshot" -d "$output_dir/classes" \
		"$TEST/jni-vault-chain/im/majo/ctos/SshFreshVaultRealJniTest.java"
	mkdir -- "$output_dir/connector-classes"
	run_logged "$output_dir/connector-javac.log" java -m jdk.compiler/com.sun.tools.javac.Main \
		-cp "$output_dir/classes:$release_snapshot" -d "$output_dir/connector-classes" \
		"$TEST/jni-product-jump/android/os/"*.java "$TEST/jni-product-jump/android/system/"*.java \
		"$TEST/jni-product-jump/im/majo/ctos/host/HostLinux.java" \
		"$TEST/jni-product-jump/im/majo/ctos/MainActivity.java" \
		"$TEST/jni-vault-chain/im/majo/ctos/SshConnectorStopRealJniTest.java"
	mkdir -- "$output_dir/service-classes"
	run_logged "$output_dir/service-javac.log" java -m jdk.compiler/com.sun.tools.javac.Main \
		-cp "$output_dir/classes:$release_snapshot:$ANDROID_JAR" -d "$output_dir/service-classes" \
		"$TEST/jni-service-lock/android/content/Context.java" "$TEST/jni-service-lock/android/os/"*.java \
		"$TEST/jni-service-lock/im/majo/ctos/SshServiceLockedAdmissionRealJniTest.java"
	run_logged "$output_dir/jni.log" prlimit --memlock=65536:65536 --core=0:0 -- \
		timeout 180 java -Xcheck:jni -Djava.library.path="$output_dir" \
		-cp "$output_dir/classes:$release_snapshot:$BCPROV" im.majo.ctos.SshFreshVaultRealJniTest
	cat -- "$output_dir/jni.log"
	for unit in published-service-unknown published-callback-unknown \
		vault-shared-destroy-false-before vault-shared-destroy-false-after \
		vault-shared-destroy-throw-before vault-shared-destroy-throw-after \
		vault-lease-normal vault-lease-retryable vault-lease-begin-before vault-lease-begin-after \
		vault-lease-end-before vault-lease-end-after vault-session-lease-retryable \
		vault-session-lease-end-before vault-session-lease-end-after \
		vault-lock-retryable vault-lock-begin-before vault-lock-begin-after \
		vault-lock-end-before vault-lock-end-after vault-lock-revoke-throw \
		vault-lock-revoke-unconfirmed vault-background-begin-before vault-lock-drain \
		cancel-begin cancel-end-before cancel-end-after cancel-shared-begin \
		cancel-shared-end-before cancel-shared-end-after cancel-port-begin \
		cancel-port-end-before cancel-port-end-after cancel-terminal-failure shared-retryable \
		shared-begin-before shared-begin-after shared-end-before shared-end-after shared-revoke shared-close \
		shared-drain session-retryable session-begin-before session-begin-after session-end-before \
		session-end-after session-callback session-drain session-child-retryable \
		session-child-begin-after session-child-callback session-child-end-before trust-failure trust-drain \
		creation-unconfirmed creation-end-before creation-end-after creation-drain \
		creation-prior-close creation-prior-failed-close creation-lock-failure creation-lock-uncertain \
		creation-lock-save creation-service-lock-failure creation-lock-idle creation-service-idle creation-stop-idle \
		creation-transferred-unconfirmed creation-pending-once creation-constructor-stop-unconfirmed \
		creation-prior-close-delayed-pending \
		creation-ordinary-then-screen creation-ordinary-then-service \
		creation-ordinary-save-then-screen creation-ordinary-save-then-service \
		creation-ordinary-uncertain-then-service \
		terminal-cancel-unconfirmed terminal-running-stop-unconfirmed terminal-owner-stop-unconfirmed \
		terminal-running-save-stop-unconfirmed terminal-running-save-service-first \
		terminal-running-save-screen-first terminal-running-save-ordinary-first \
		terminal-save-status-unconfirmed terminal-save-status-uncertain terminal-save-status-untracked-uncertain \
		terminal-save-status-screen-io \
		terminal-save-status-service-uncertain terminal-save-status-service-io \
		terminal-save-status-drain-success terminal-save-status-drain-fail-io \
		terminal-save-status-drain-fail-uncertain terminal-save-status-port-unknown \
		terminal-save-status-screen-io-close-retry terminal-save-status-screen-uncertain-close-retry \
		terminal-save-status-untracked-uncertain-close-retry \
		terminal-save-status-untracked-screen-uncertain-close-retry \
		terminal-save-status-untracked-service-uncertain-close-retry \
		terminal-final-running-ordinary-screen terminal-final-running-service-screen \
		terminal-final-running-screen-first terminal-final-status-ordinary-screen \
		terminal-final-status-service-screen terminal-final-uncertain-service-screen \
		terminal-final-save-ordinary-screen \
		terminal-owner-lock-unconfirmed terminal-owner-reject-unconfirmed; do
		run_logged "$output_dir/$unit.log" prlimit --memlock=65536:65536 --core=0:0 -- \
			timeout 30 java -Xcheck:jni -Djava.library.path="$output_dir" \
			-cp "$output_dir/classes:$release_snapshot:$BCPROV" im.majo.ctos.SshFreshVaultRealJniTest "$unit"
		cat -- "$output_dir/$unit.log"
	done
	for unit in drain unknown admitted-unknown material-drain material-unknown; do
		run_logged "$output_dir/connector-$unit.log" prlimit --memlock=65536:65536 --core=0:0 -- \
			timeout 30 java -Xcheck:jni -Djava.library.path="$output_dir" \
			-cp "$output_dir/connector-classes:$output_dir/classes:$release_snapshot:$BCPROV" \
			im.majo.ctos.SshConnectorStopRealJniTest "$unit"
		cat -- "$output_dir/connector-$unit.log"
	done
	for unit in no-owner normal unknown inflight-unknown unrelated unknown-unrelated dispatch-unrelated \
		unknown-dispatch owner-propagates presuppressed same-throwable; do
		run_logged "$output_dir/service-$unit.log" prlimit --memlock=65536:65536 --core=0:0 -- \
			timeout 30 java -Xcheck:jni -Djava.library.path="$output_dir" \
			-cp "$output_dir/service-classes:$output_dir/classes:$release_snapshot:$ANDROID_JAR:$BCPROV" \
			im.majo.ctos.SshServiceLockedAdmissionRealJniTest "$unit"
		cat -- "$output_dir/service-$unit.log"
	done
	if grep -Eqi 'WARNING in native method|WARNING.*JNI|FATAL ERROR in native method|JNI DETECTED ERROR' \
		"$output_dir/jni.log" "$output_dir"/published-*.log "$output_dir"/connector-*.log "$output_dir"/service-*.log "$output_dir"/vault-*.log "$output_dir"/cancel-*.log "$output_dir"/shared-*.log "$output_dir"/session-*.log \
		"$output_dir"/trust-*.log "$output_dir"/creation-*.log "$output_dir"/terminal-*.log; then
		die 'JVM JNI checks reported a warning or error'
	fi
	sha256sum -- "${inputs[@]}" >"$output_dir/inputs-after.sha256"
	cmp -s -- "$output_dir/inputs-before.sha256" "$output_dir/inputs-after.sha256" || die 'source inputs changed during the test'
	class_manifest "$RELEASE_CLASSES" "$output_dir/release-after.sha256"
	cmp -s -- "$output_dir/release-before.sha256" "$output_dir/release-after.sha256" || die 'release classes changed during the test'
	sha256sum -- "$output_dir/libctos_ssh_files.so" >"$output_dir/binary.sha256"
	printf 'SSH_FRESH_VAULT_JNI_INPUTS unchanged=pass\n'
}

main "$@"
