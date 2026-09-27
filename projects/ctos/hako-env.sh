#!/usr/bin/env bash
# Prepare writable Flutter and Android SDK copies for the mapped host UID.
set -Eeuo pipefail

readonly DEVHOME=/app/.devhome
readonly FLUTTER_ROOT="${DEVHOME}/flutter"
readonly ANDROID_HOME="${DEVHOME}/android-sdk"
readonly ANDROID_SDK_ROOT="$ANDROID_HOME"
readonly GRADLE_USER_HOME="${DEVHOME}/gradle-home"
readonly PUB_CACHE="${DEVHOME}/pub-cache"
readonly XDG_CACHE_HOME="${DEVHOME}/cache"
readonly ANDROID_USER_HOME="${DEVHOME}/android-user"
readonly NDK_VERSION=27.0.12077973
readonly SDKMANAGER="${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager"
readonly BUILD_PYTHON_DIR="${DEVHOME}/python/cpython-3.13.7-linux-x86_64-gnu"
readonly BUILD_PYTHON_URL='https://github.com/astral-sh/python-build-standalone/releases/download/20250902/cpython-3.13.7%2B20250902-x86_64-unknown-linux-gnu-install_only_stripped.tar.gz'
readonly BUILD_PYTHON_SHA256=6a8280f4b08d75428eea83955678c51da00c585bb411562cd53f510680becf00
export FLUTTER_ROOT ANDROID_HOME ANDROID_SDK_ROOT GRADLE_USER_HOME
export PUB_CACHE XDG_CACHE_HOME ANDROID_USER_HOME
export FLUTTER_SUPPRESS_ANALYTICS=true DART_SUPPRESS_ANALYTICS=true
export PATH="${FLUTTER_ROOT}/bin:${FLUTTER_ROOT}/bin/cache/dart-sdk/bin:${ANDROID_HOME}/cmdline-tools/latest/bin:${PATH}"

STEP_INDEX=${HAKO_STEP_INDEX:-0}

quote_command() {
	local arg
	for arg in "$@"; do
		printf '%q ' "$arg"
	done
}

log_step() {
	local label=$1
	shift
	STEP_INDEX=$((STEP_INDEX + 1))
	printf '#%s [hako %s] %s\n' "$STEP_INDEX" "$label" "$(quote_command "$@")" >&2
}

run_step() {
	local label=$1
	shift
	log_step "$label" "$@"
	"$@"
}

bootstrap_copy() {
	local source_dir=$1
	local target_dir=$2
	local marker=$3

	[[ -f "$marker" ]] && return 0
	run_step setup mkdir -p "$target_dir"
	run_step setup chmod -R u+w "$target_dir"
	log_step setup tar -C "$source_dir" -cf - .
	log_step setup tar -C "$target_dir" --no-same-owner --mode='u+rwX' -xf -
	tar -C "$source_dir" -cf - . |
		tar -C "$target_dir" --no-same-owner --mode='u+rwX' -xf -
	run_step setup touch "$marker"
}

bootstrap_python() (
	[[ -x "${BUILD_PYTHON_DIR}/bin/python3.13" ]] && return 0
	local temporary command_name
	for command_name in curl tar sha256sum mktemp mkdir mv rm flock; do
		command -v "$command_name" >/dev/null || {
			printf '[hako] missing bootstrap command: %s\n' "$command_name" >&2
			return 1
		}
	done
	exec 9>"${DEVHOME}/.build-python.lock"
	run_step setup flock --timeout 180 9
	[[ -x "${BUILD_PYTHON_DIR}/bin/python3.13" ]] && return 0
	[[ ! -e "$BUILD_PYTHON_DIR" ]] || {
		printf '[hako] incomplete build Python exists; inspect %s before retrying\n' "$BUILD_PYTHON_DIR" >&2
		return 1
	}
	temporary=$(mktemp -d /tmp/ctos-build-python.XXXXXXXX)
	trap 'rm -rf -- "$temporary"' EXIT
	run_step setup curl --fail --location --retry 2 --max-time 180 \
		--output "${temporary}/python.tar.gz" "$BUILD_PYTHON_URL"
	printf '%s  %s\n' "$BUILD_PYTHON_SHA256" "${temporary}/python.tar.gz" | sha256sum --check --status
	run_step setup tar -C "$temporary" --no-same-owner -xf "${temporary}/python.tar.gz"
	run_step setup mkdir -p "${DEVHOME}/python"
	[[ ! -e "$BUILD_PYTHON_DIR" ]] || {
		printf '[hako] incomplete build Python exists; inspect %s before retrying\n' "$BUILD_PYTHON_DIR" >&2
		return 1
	}
	run_step setup mv -- "${temporary}/python" "$BUILD_PYTHON_DIR"
	run_step setup "${BUILD_PYTHON_DIR}/bin/python3.13" --version
)

main() {
	[[ $# -gt 0 ]] || {
		printf '[hako] missing container command\n' >&2
		return 2
	}

	run_step setup mkdir -p "$GRADLE_USER_HOME" "$PUB_CACHE" "$XDG_CACHE_HOME" "$ANDROID_USER_HOME"
	bootstrap_copy /sdks/flutter "$FLUTTER_ROOT" "${DEVHOME}/.flutter-ready"
	bootstrap_copy /opt/android-sdk-linux "$ANDROID_HOME" "${DEVHOME}/.android-sdk-ready"
	bootstrap_python

	if [[ ! -f "${ANDROID_HOME}/platforms/android-36/android.jar" || ! -x "${ANDROID_HOME}/build-tools/36.0.0/aapt2" ]]; then
		run_step setup "$SDKMANAGER" --sdk_root="$ANDROID_HOME" \
			'platforms;android-36' 'build-tools;36.0.0'
	fi

	if [[ ! -x "${ANDROID_HOME}/ndk/${NDK_VERSION}/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android28-clang" ]]; then
		run_step setup "$SDKMANAGER" --sdk_root="$ANDROID_HOME" "ndk;${NDK_VERSION}"
	fi

	log_step command "$@"
	exec "$@"
}

main "$@"
