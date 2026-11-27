#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly SCRIPT_DIR
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd -P)"
readonly PROJECT_ROOT
readonly CPP="$PROJECT_ROOT/android/app/src/main/cpp"
readonly JAVA="$PROJECT_ROOT/android/app/src/main/java/im/majo/ctos"
readonly TEST_JAVA="$PROJECT_ROOT/android/app/src/test/jni-product-jump"
readonly TEST_C="$PROJECT_ROOT/android/app/src/test/cpp/ssh_product_host_compat.c"
readonly RELEASE="$PROJECT_ROOT/build/app/intermediates/javac/release/compileReleaseJavaWithJavac/classes"
readonly ARCHIVE="${CTOS_OPENSSH_ARCHIVE:-$PROJECT_ROOT/build/ssh-native-g1/stock-run.lOHF9Oai/inputs/openssh-10.6p1.tar.gz}"
readonly JNI_HEADER="$PROJECT_ROOT/.devhome/android-sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include/jni.h"
readonly BCPROV="$PROJECT_ROOT/.devhome/gradle-home/caches/modules-2/files-2.1/org.bouncycastle/bcprov-jdk18on/1.86/830c8467bed8e4c1148262386b09fc42aedcf262/bcprov-jdk18on-1.86.jar"
readonly ANDROID_JAR="$PROJECT_ROOT/.devhome/android-sdk/platforms/android-35/android.jar"
readonly FLUTTER_JAR="$PROJECT_ROOT/.devhome/gradle-home/caches/modules-2/files-2.1/io.flutter/flutter_embedding_release/1.0.0-035316565ad77281a75305515e4682e6c4c6f7ca/38d07d60089d073764dd997aba930fd80ff060ca/flutter_embedding_release-1.0.0-035316565ad77281a75305515e4682e6c4c6f7ca.jar"
readonly ARCHIVE_SHA=a9dc9565dffe8640f64d863cd29a32bc4a3dbdec0566a7fc44c5d6ee767d5f39
readonly JNI_SHA=5df9074457c041b372f2c68ff646d6e8600cd673a223885e8df21c44c94ec667
readonly BCPROV_SHA=2af190b300cbb0b35e248ccf5f4a06b6072030aeb3da7a98ec73abe5b4cb371f
readonly ANDROID_SHA=4566663c3876e022b4fa4ced8c8697c4ab1688267f090114fd92d027b32e619b
readonly FLUTTER_SHA=1d126ff29f4db16deaadb52122e4102174ad053de28551f2efb57b2a58bbf94b
readonly HOSTFILE_SHA=a786566e1842996c0282647457f4dd63558cde00bc79ae1ba6bcd552bf30484a
readonly KEX_SHA=50a59606000dc871f1b29e33eeb6d77fb5da456f40526d2871357758b61f8785
readonly BANNER_SHA=ba61f35303233ebd2bf1c39d167774667766b3461721657f8af0483e51258a18
readonly PROVISIONAL_SHA=075e910206a43452da8e01bb3818e6fb5bc18e0ab8e62a5b5d7c5bd6a0da845a
readonly CONFIG_SHA=a6a01e3d5336d0fa69d02b1907b3a304b28a15041d8af2d0f1f3e3b0fe98705b
readonly BUILDER_IMAGE=sha256:cc39c88add68c6e4ed377beccd6a659e59b286954d2c7e5c8e5b1dde2a82a8e8
readonly JDK_IMAGE=sha256:94bec0dcb1a1dd7107a4f2d0722e152a63bb0f40a11c58eb4c1f782a10cd29fe
readonly JRE_IMAGE=sha256:082440c11fefcf190540df5374c3b2dc0ec2ca840bdda46dab8a4e9a1ae3e9c9
readonly SSH_IMAGE=sha256:30917cc575007b885a8f3fc478e3e2e643a6f52ec82ed33268a1370f7247b382
temp_container=''

usage() {
	printf 'Usage: bash %s --output <new-directory> [--expect-jump-open|--expect-jump-closed]\n' "${0##*/}" >&2
	printf 'Build a host JNI fixture from existing release classes and run isolated real-sshd checks.\n' >&2
	printf 'Build current release classes first; output must be /tmp/ctos-product-jump-* or project build/.\n' >&2
	printf 'By default test the supported single-jump path; closed mode tests a deliberately disabled gate.\n' >&2
	printf 'CTOS_OPENSSH_ARCHIVE may select the pinned source archive by its verified SHA-256.\n' >&2
}

die() {
	printf 'Error: %s\n' "$*" >&2
	exit 1
}

cleanup() {
	[[ -n "$temp_container" ]] || return 0
	local owner
	owner=$(docker container inspect --format '{{index .Config.Labels "ctos.fixture.owner"}}' "$temp_container" 2>/dev/null) || return 0
	[[ "$owner" == "$$" ]] || return 0
	docker rm "$temp_container" >/dev/null
}

verify_input() {
	local file=$1 expected=$2 actual
	[[ -f "$file" && ! -L "$file" ]] || die "missing regular input: $file"
	actual=$(sha256sum -- "$file")
	[[ "${actual%% *}" == "$expected" ]] || die "input hash mismatch: $file"
}

tree_manifest() {
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
	[[ ($# -eq 2 || ($# -eq 3 && ($3 == --expect-jump-open || $3 == --expect-jump-closed))) && $1 == --output ]] || {
		usage
		return 2
	}
	local tool output image input path
	for tool in docker realpath sha256sum cmp find sort xargs cp mkdir tail cat tee id grep; do
		command -v "$tool" >/dev/null 2>&1 || die "missing required command: $tool"
	done
	[[ -d "$RELEASE" && -f "$RELEASE/im/majo/ctos/SshPasswordConnector.class" ]] ||
		die 'compile current release Java classes before running this script'
	[[ -z "$(find -P "$RELEASE" -type l -print -quit)" ]] || die 'release classes must not contain symlinks'
	verify_input "$ARCHIVE" "$ARCHIVE_SHA"
	verify_input "$JNI_HEADER" "$JNI_SHA"
	verify_input "$BCPROV" "$BCPROV_SHA"
	verify_input "$ANDROID_JAR" "$ANDROID_SHA"
	verify_input "$FLUTTER_JAR" "$FLUTTER_SHA"
	verify_input "$SCRIPT_DIR/openssh-10.6p1-hostfile-stream-writer.patch" "$HOSTFILE_SHA"
	verify_input "$SCRIPT_DIR/openssh-10.6p1-kex-guard.patch" "$KEX_SHA"
	verify_input "$SCRIPT_DIR/openssh-10.6p1-banner-atomic.patch" "$BANNER_SHA"
	verify_input "$SCRIPT_DIR/openssh-10.6p1-provisional-input.patch" "$PROVISIONAL_SHA"
	verify_input "$SCRIPT_DIR/openssh-10.6p1-config-evaluator.patch" "$CONFIG_SHA"
	for image in "$BUILDER_IMAGE" "$JDK_IMAGE" "$JRE_IMAGE" "$SSH_IMAGE"; do
		[[ "$(docker image inspect "$image" --format '{{.Id}}')" == "$image" ]] || die "missing pinned local image: $image"
	done
	output=$(realpath -m -- "$2")
	[[ "$output" == /tmp/ctos-product-jump-* || "$output" == "$PROJECT_ROOT/build/"* ]] ||
		die 'output must be a new /tmp/ctos-product-jump-* or project build/ directory'
	[[ "$output" != *,* && ! -e "$output" ]] || die 'output must be a new path without commas'
	umask 077
	mkdir -p -- "$output/cpp" "$output/inputs" "$output/test-java" "$output/release-classes" "$output/classes" "$output/jre"
	trap cleanup EXIT
	local -a inputs=("$SCRIPT_DIR/host-product-jump-test.sh" "$SCRIPT_DIR/container-host-product-jump-build.sh"
		"$SCRIPT_DIR/docker-product-jump-test.sh" "$SCRIPT_DIR/openssh-jump-fixture.sh"
		"$ARCHIVE" "$JNI_HEADER" "$BCPROV" "$ANDROID_JAR" "$FLUTTER_JAR" "$TEST_C"
		"$SCRIPT_DIR/openssh-10.6p1-hostfile-stream-writer.patch" "$SCRIPT_DIR/openssh-10.6p1-kex-guard.patch"
		"$SCRIPT_DIR/openssh-10.6p1-banner-atomic.patch" "$SCRIPT_DIR/openssh-10.6p1-config-evaluator.patch"
		"$SCRIPT_DIR/openssh-10.6p1-provisional-input.patch"
		"$CPP"/ssh_*.c "$CPP"/ssh_*.h "$JAVA"/*.java)
	while IFS= read -r -d '' input; do inputs+=("$input"); done < <(find -P "$TEST_JAVA" -type f -print0 | LC_ALL=C sort -z)
	sha256sum -- "${inputs[@]}" >"$output/inputs-before.sha256"
	tree_manifest "$RELEASE" "$output/release-before.sha256"
	tree_manifest "$TEST_JAVA" "$output/test-java-before.sha256"
	(cd -- "$CPP" && sha256sum -- ssh_*.c ssh_*.h) >"$output/cpp-before.sha256"
	cp -a -- "$RELEASE/." "$output/release-classes/"
	cp -a -- "$TEST_JAVA/." "$output/test-java/"
	cp -- "$CPP"/ssh_*.c "$CPP"/ssh_*.h "$output/cpp/"
	cp -- "$ARCHIVE" "$output/inputs/openssh-10.6p1.tar.gz"
	cp -- "$JNI_HEADER" "$output/inputs/jni.h"
	cp -- "$BCPROV" "$output/inputs/bcprov.jar"
	cp -- "$TEST_C" "$output/inputs/ssh_product_host_compat.c"
	cp -- "$SCRIPT_DIR/openssh-10.6p1-hostfile-stream-writer.patch" "$output/inputs/hostfile.patch"
	cp -- "$SCRIPT_DIR/openssh-10.6p1-kex-guard.patch" "$output/inputs/kex.patch"
	cp -- "$SCRIPT_DIR/openssh-10.6p1-banner-atomic.patch" "$output/inputs/banner.patch"
	cp -- "$SCRIPT_DIR/openssh-10.6p1-provisional-input.patch" "$output/inputs/provisional.patch"
	cp -- "$SCRIPT_DIR/openssh-10.6p1-config-evaluator.patch" "$output/inputs/config.patch"
	cp -- "$SCRIPT_DIR/container-host-product-jump-build.sh" "$output/inputs/build.sh"
	tree_manifest "$output/release-classes" "$output/release-snapshot.sha256"
	tree_manifest "$output/test-java" "$output/test-java-snapshot.sha256"
	(cd -- "$output/cpp" && sha256sum -- ssh_*.c ssh_*.h) >"$output/cpp-snapshot.sha256"
	cmp -s -- "$output/release-before.sha256" "$output/release-snapshot.sha256" || die 'release classes changed while copying'
	cmp -s -- "$output/test-java-before.sha256" "$output/test-java-snapshot.sha256" || die 'fixture Java changed while copying'
	cmp -s -- "$output/cpp-before.sha256" "$output/cpp-snapshot.sha256" || die 'native source changed while copying'
	verify_input "$output/inputs/openssh-10.6p1.tar.gz" "$ARCHIVE_SHA"
	verify_input "$output/inputs/jni.h" "$JNI_SHA"
	verify_input "$output/inputs/bcprov.jar" "$BCPROV_SHA"
	verify_input "$output/inputs/hostfile.patch" "$HOSTFILE_SHA"
	verify_input "$output/inputs/kex.patch" "$KEX_SHA"
	verify_input "$output/inputs/banner.patch" "$BANNER_SHA"
	verify_input "$output/inputs/provisional.patch" "$PROVISIONAL_SHA"
	verify_input "$output/inputs/config.patch" "$CONFIG_SHA"
	cmp -s -- "$TEST_C" "$output/inputs/ssh_product_host_compat.c" || die 'host compatibility source changed while copying'
	cmp -s -- "$SCRIPT_DIR/container-host-product-jump-build.sh" "$output/inputs/build.sh" || die 'builder changed while copying'
	printf 'builder=%s\njdk=%s\njre=%s\nsshd=%s\n' "$BUILDER_IMAGE" "$JDK_IMAGE" "$JRE_IMAGE" "$SSH_IMAGE" >"$output/images.txt"
	local container_name="ctos-product-jump-jre-$$"
	if docker container inspect "$container_name" >/dev/null 2>&1; then
		die "temporary container name already exists: $container_name"
	fi
	temp_container=$container_name
	docker create --name "$temp_container" --label ctos.scope=ssh-product-jump-fixture \
		--label "ctos.fixture.owner=$$" --network none --entrypoint /bin/true "$JRE_IMAGE" >/dev/null
	docker cp "$temp_container:/opt/java/openjdk/." "$output/jre/"
	docker rm "$temp_container" >/dev/null
	temp_container=''
	tree_manifest "$output/jre" "$output/jre.sha256"
	local -a fixture_java=()
	while IFS= read -r -d '' path; do fixture_java+=("/out/test-java/${path#"$output/test-java/"}"); done \
		< <(find -P "$output/test-java" -name '*.java' -type f -print0 | LC_ALL=C sort -z)
	[[ ${#fixture_java[@]} -gt 0 ]] || die 'fixture Java sources are missing'
	run_logged "$output/javac.log" docker run --rm --pull=never --network none --cap-drop ALL \
		--label ctos.scope=ssh-product-jump-fixture --label "ctos.fixture.owner=$$" \
		--security-opt no-new-privileges --user "$(id -u):$(id -g)" --mount "type=bind,src=$output,dst=/out" \
		--entrypoint /opt/java/openjdk/bin/javac "$JDK_IMAGE" --release 17 \
		-cp /out/release-classes:/out/inputs/bcprov.jar -d /out/classes "${fixture_java[@]}"
	run_logged "$output/native-build.log" docker run --rm --pull=never --network none --cap-drop ALL \
		--label ctos.scope=ssh-product-jump-fixture --label "ctos.fixture.owner=$$" \
		--security-opt no-new-privileges --pids-limit 256 --memory 1g --user "$(id -u):$(id -g)" \
		--mount "type=bind,src=$output,dst=/out" --entrypoint /bin/bash "$BUILDER_IMAGE" /out/inputs/build.sh
	run_logged "$output/shim-smoke.log" docker run --rm --pull=never --network none --cap-drop ALL \
		--label ctos.scope=ssh-product-jump-fixture --label "ctos.fixture.owner=$$" \
		--security-opt no-new-privileges --pids-limit 64 --memory 256m --user "$(id -u):$(id -g)" \
		--mount "type=bind,src=$output,dst=/out,readonly" --entrypoint /out/jre/bin/java "$SSH_IMAGE" \
		-Xcheck:jni -Djava.library.path=/out -cp /out/classes im.majo.ctos.host.HostLinuxSmoke
	cat -- "$output/shim-smoke.log"
	local -a product_args=("$output/jre" "$output" "$output/classes" "$output/release-classes" "$output/release-classes")
	if [[ $# -eq 2 || $3 == --expect-jump-open ]]; then product_args+=(--expect-jump-open); fi
	CTOS_SSH_FIXTURE_IMAGE="$SSH_IMAGE" bash "$SCRIPT_DIR/docker-product-jump-test.sh" \
		"${product_args[@]}" | tee "$output/fixture.log"
	sha256sum -- "${inputs[@]}" >"$output/inputs-after.sha256"
	cmp -s -- "$output/inputs-before.sha256" "$output/inputs-after.sha256" || die 'source inputs changed during the test'
	tree_manifest "$RELEASE" "$output/release-after.sha256"
	cmp -s -- "$output/release-before.sha256" "$output/release-after.sha256" || die 'release classes changed during the test'
	tree_manifest "$output/classes" "$output/fixture-classes.sha256"
	printf 'PRODUCT_HOST_INPUTS unchanged=pass output=%s\n' "$output"
}

main "$@"
