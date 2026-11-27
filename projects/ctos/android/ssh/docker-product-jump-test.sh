#!/usr/bin/env bash
set -Eeuo pipefail

readonly IMAGE="${CTOS_SSH_FIXTURE_IMAGE:-ctos-ssh-fixture:local}"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly SCRIPT_DIR
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd -P)"
readonly PROJECT_ROOT
readonly BCPROV="$PROJECT_ROOT/.devhome/gradle-home/caches/modules-2/files-2.1/org.bouncycastle/bcprov-jdk18on/1.86/830c8467bed8e4c1148262386b09fc42aedcf262/bcprov-jdk18on-1.86.jar"
readonly BCPROV_SHA=2af190b300cbb0b35e248ccf5f4a06b6072030aeb3da7a98ec73abe5b4cb371f
readonly ANDROID_JAR="$PROJECT_ROOT/.devhome/android-sdk/platforms/android-35/android.jar"
readonly ANDROID_SHA=4566663c3876e022b4fa4ced8c8697c4ab1688267f090114fd92d027b32e619b
readonly FLUTTER_JAR="$PROJECT_ROOT/.devhome/gradle-home/caches/modules-2/files-2.1/io.flutter/flutter_embedding_release/1.0.0-035316565ad77281a75305515e4682e6c4c6f7ca/38d07d60089d073764dd997aba930fd80ff060ca/flutter_embedding_release-1.0.0-035316565ad77281a75305515e4682e6c4c6f7ca.jar"
readonly FLUTTER_SHA=1d126ff29f4db16deaadb52122e4102174ad053de28551f2efb57b2a58bbf94b

usage() {
	printf 'Usage: bash %s <jdk-dir> <native-dir> <test-classes> <factory-classes> <release-classes> [--expect-jump-open]\n' "${0##*/}" >&2
	printf 'Run the product connector against disposable, network-none OpenSSH servers.\n' >&2
	printf 'Inputs must already be compiled and compatible with the fixture image.\n' >&2
}

die() {
	printf 'Error: %s\n' "$*" >&2
	exit 1
}

main() {
	if [[ $# -eq 1 && ($1 == --help || $1 == -h) ]]; then
		usage
		return 0
	fi
	[[ $# -eq 5 || ($# -eq 6 && $6 == --expect-jump-open) ]] || {
		usage
		return 2
	}
	local jdk native classes factory release path actual image_id fixture_mode=--product-java
	if [[ $# -eq 6 ]]; then fixture_mode=--product-open; fi
	for path in docker realpath sha256sum; do command -v "$path" >/dev/null 2>&1 || die "missing required command: $path"; done
	jdk=$(realpath -e -- "$1")
	native=$(realpath -e -- "$2")
	classes=$(realpath -e -- "$3")
	factory=$(realpath -e -- "$4")
	release=$(realpath -e -- "$5")
	for path in "$jdk" "$native" "$classes" "$factory" "$release" "$SCRIPT_DIR" "$BCPROV" "$ANDROID_JAR" "$FLUTTER_JAR"; do
		[[ "$path" != *,* ]] || die 'mount paths cannot contain commas'
	done
	[[ -x "$jdk/bin/java" && -f "$native/libctos_ssh_files.so" && -f "$native/libctos_host_compat.so" ]] ||
		die 'Java runtime or native fixture libraries are missing'
	[[ -f "$classes/im/majo/ctos/SshProductDockerTest.class" && -f "$factory/im/majo/ctos/SshPasswordConnector.class" &&
		-f "$release/im/majo/ctos/AppSecrets.class" && -f "$BCPROV" ]] || die 'compiled fixture or product classes are missing'
	actual=$(sha256sum -- "$BCPROV")
	[[ "${actual%% *}" == "$BCPROV_SHA" ]] || die 'BouncyCastle input hash mismatch'
	actual=$(sha256sum -- "$ANDROID_JAR")
	[[ "${actual%% *}" == "$ANDROID_SHA" ]] || die 'Android API signature input hash mismatch'
	actual=$(sha256sum -- "$FLUTTER_JAR")
	[[ "${actual%% *}" == "$FLUTTER_SHA" ]] || die 'Flutter embedding signature input hash mismatch'
	image_id=$(docker image inspect "$IMAGE" --format '{{.Id}}') || die "missing local image: $IMAGE"
	printf 'PRODUCT_FIXTURE image=%s id=%s\n' "$IMAGE" "$image_id"
	sha256sum -- "$native/libctos_ssh_files.so" "$native/libctos_host_compat.so" \
		"$factory/im/majo/ctos/SshPasswordConnector.class" "$classes/im/majo/ctos/SshProductDockerTest.class"
	docker run --rm --pull=never --network none \
		--label ctos.scope=ssh-product-jump-fixture --label "ctos.fixture.owner=$$" \
		--cap-drop ALL --cap-add SETUID --cap-add SETGID --cap-add CHOWN --cap-add DAC_OVERRIDE \
		--cap-add FOWNER --cap-add SYS_CHROOT --cap-add KILL --cap-add AUDIT_WRITE \
		--security-opt no-new-privileges --pids-limit 128 --memory 1g --ulimit core=0:0 \
		--tmpfs '/tmp:rw,nosuid,nodev,mode=1777' --tmpfs '/run:rw,nosuid,nodev,mode=755' \
		--mount "type=bind,src=$SCRIPT_DIR/openssh-jump-fixture.sh,dst=/test/run.sh,readonly" \
		--mount "type=bind,src=$jdk,dst=/test/jdk,readonly" \
		--mount "type=bind,src=$native,dst=/test/native,readonly" \
		--mount "type=bind,src=$classes,dst=/test/classes,readonly" \
		--mount "type=bind,src=$factory,dst=/test/factory,readonly" \
		--mount "type=bind,src=$release,dst=/test/release,readonly" \
		--mount "type=bind,src=$BCPROV,dst=/test/bcprov.jar,readonly" \
		--mount "type=bind,src=$ANDROID_JAR,dst=/test/android.jar,readonly" \
		--mount "type=bind,src=$FLUTTER_JAR,dst=/test/flutter.jar,readonly" \
		--entrypoint /bin/sh "$IMAGE" /test/run.sh "$fixture_mode"
}

main "$@"
