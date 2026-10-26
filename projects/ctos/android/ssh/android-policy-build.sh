#!/usr/bin/env bash
set -Eeuo pipefail

readonly BASELINE=/app/build/ssh-native-g1/stock-run.lOHF9Oai
readonly BASELINE_TREE_SHA=e3b0c5215b8050db328bd8773cc4ba00212eb9f8b21d924ed0ab207501ef0ed6
readonly SOURCE_ROOT=/app/android/app/src/main/cpp
readonly POLICY_SOURCES=(ssh_config_policy.c ssh_config_policy.h ssh_config_policy_probe.c ssh_file_view.c ssh_file_view.h ssh_file_io.c ssh_file_io.h)
readonly PATCH=/app/android/ssh/openssh-10.6p1-config-policy.patch
readonly OUTPUT_ROOT=/app/build/ssh-native-policy
readonly GENERATED_ROOT=/app/build/app/generated
readonly NDK_BIN=/app/.devhome/android-sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/bin
readonly STYLE_ERROR=$'\033[1;31m'
readonly STYLE_TITLE=$'\033[1;36m'
readonly STYLE_RESET=$'\033[0m'

style_text() {
	local style=$1 message=$2
	if [[ -t 2 && -z "${NO_COLOR:-}" ]]; then
		printf '%s%s%s' "$style" "$message" "$STYLE_RESET" >&2
	else
		printf '%s' "$message" >&2
	fi
}
die() {
	style_text "$STYLE_ERROR" "Error: $*"
	printf '\n' >&2
	exit 1
}
usage() {
	style_text "$STYLE_TITLE" 'Build the fixed Android synthetic native policy increment'
	printf '\nUsage: bash android-policy-build.sh --build\nRun only in project hako. Only fixture outputs; no ARM execution or production packaging.\n' >&2
}
regular() {
	[[ -f "$1" && ! -L "$1" && "$(realpath -e -- "$1")" == "$1" ]] || die 'noncanonical input file'
}
tree_manifest() {
	local destination=$1 member
	for member in openssh-build openssl-prefix sources/openssh-10.6p1 evidence/source-notices; do
		[[ -d "$BASELINE/$member" && "$(realpath -e -- "$BASELINE/$member")" == "$BASELINE/$member" ]] || die 'noncanonical baseline tree'
		find -P "$BASELINE/$member" -print0 | while IFS= read -r -d '' descendant; do
			[[ ! -L "$descendant" && (-d "$descendant" || -f "$descendant") && "$(realpath -e -- "$descendant")" == "$descendant" ]] || die 'unsupported baseline member'
		done
	done
	(cd -- "$BASELINE" && find -P openssh-build openssl-prefix sources/openssh-10.6p1 evidence/source-notices -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum) >"$destination"
	local actual
	actual=$(sha256sum -- "$destination")
	[[ "${actual%% *}" == "$BASELINE_TREE_SHA" ]] || die 'frozen baseline aggregate changed'
}
retained_exit() {
	local status=$1
	if ((status != 0)); then printf 'Policy build failed; evidence retained at %s\n' "${run_dir:-none}" >&2; fi
}
publish_directory() {
	local prepared=$1 destination=$2 retired=$3
	[[ ! -L "$destination" ]] || die 'symlinked fixture output'
	if [[ -e "$destination" ]]; then
		[[ -d "$destination" && "$(realpath -e -- "$destination")" == "$destination" ]] || die 'noncanonical fixture output'
		if [[ -e "$destination/.ctos-ssh-policy-owned" ]]; then
			regular "$destination/.ctos-ssh-policy-owned"
			grep -Fxq 'ctos-ssh-policy-probe-v1' "$destination/.ctos-ssh-policy-owned" || die 'unowned fixture output'
		else
			# Gradle creates declared output directories before the Exec task.
			[[ -z "$(find -P "$destination" -mindepth 1 -maxdepth 1 -print -quit)" ]] || die 'nonempty unowned fixture output'
		fi
		mv -- "$destination" "$run_dir/$retired"
	fi
	mv -- "$prepared" "$destination"
}
main() {
	if [[ $# == 1 && ("$1" == --help || "$1" == -h) ]]; then
		usage
		return 0
	fi
	[[ $# == 1 && "$1" == --build ]] || {
		usage
		die 'exactly --build is required'
	}
	local tool
	run_dir=''
	trap 'retained_exit "$?"' EXIT
	for tool in realpath find sha256sum sort xargs cmp cp mkdir mktemp patch make env timeout cat grep mv; do
		command -v "$tool" >/dev/null 2>&1 || die "missing required command: $tool"
	done
	[[ -f /.dockerenv && -f /app/hako-env.sh ]] || die 'run only inside project hako'
	[[ "$(realpath -e -- "${BASH_SOURCE[0]}")" == /app/android/ssh/android-policy-build.sh ]] || die 'unexpected runner mount'
	[[ "$(realpath -e -- "$BASELINE")" == "$BASELINE" ]] || die 'unexpected baseline mount'
	for tool in "${POLICY_SOURCES[@]}"; do regular "$SOURCE_ROOT/$tool"; done
	regular "$PATCH"
	[[ -x "$NDK_BIN/aarch64-linux-android28-clang" && -x "$NDK_BIN/llvm-readelf" ]] || die 'pinned NDK missing'
	mkdir -p -- "$OUTPUT_ROOT"
	[[ "$(realpath -e -- "$OUTPUT_ROOT")" == "$OUTPUT_ROOT" ]] || die 'unexpected output mount'
	run_dir=$(mktemp -d "$OUTPUT_ROOT/run.XXXXXXXX")
	printf 'run_dir=%s\n' "$run_dir"
	mkdir -- "$run_dir/evidence" "$run_dir/output" "$run_dir/openbsd-compat"
	tree_manifest "$run_dir/evidence/baseline-before.sha256"
	local build=$BASELINE/openssh-build source=$BASELINE/sources/openssh-10.6p1
	cp -- "$build/Makefile" "$run_dir/Makefile.upstream"
	cp -- "$build/config.h" "$build/libssh.a" "$run_dir/"
	cp -- "$build/openbsd-compat/libopenbsd-compat.a" "$run_dir/openbsd-compat/"
	for tool in readconf.c ssh.c sshconnect.c; do
		regular "$source/$tool"
		cp -- "$source/$tool" "$run_dir/"
		sha256sum -- "$source/$tool" >>"$run_dir/evidence/original-sources.sha256"
	done
	for tool in "${POLICY_SOURCES[@]}"; do cp -- "$SOURCE_ROOT/$tool" "$run_dir/"; done
	cp -- "$PATCH" "$run_dir/config-policy.patch"
	(cd -- "$run_dir" && patch --batch --fuzz=0 -p1 <config-policy.patch) >"$run_dir/evidence/patch.log" 2>&1
	sha256sum -- "$run_dir/"*.c "$run_dir/"*.h "$run_dir/config-policy.patch" >"$run_dir/evidence/policy-inputs.sha256"
	cat >"$run_dir/Policy.mk" <<'MAKEFILE'
include Makefile.upstream
.PHONY: policy
POLICY_FLAGS = -DCTOS_SSH_CONFIG_POLICY=1 -DCTOS_SSH_POLICY_FIXTURE=1
POLICY_STRICT = -std=c11 -O2 -fPIE -Wall -Wextra -Werror
POLICY_BASE = $(filter-out ssh.o readconf.o sshconnect.o,$(SSHOBJS))
POLICY_WRAPS = fork vfork execv execve access open open64 __open_2 fopen fopen64 fclose getpwuid _ssh__compat_glob _ssh__compat_globfree socket connect
comma := ,
POLICY_WRAP_FLAGS = $(addprefix -Wl$(comma)--wrap=,$(POLICY_WRAPS))
policy:
	$(CC) $(CPPFLAGS) $(CFLAGS) $(POLICY_FLAGS) -c readconf.c -o policy-readconf.o
	$(CC) $(CPPFLAGS) $(CFLAGS) $(POLICY_FLAGS) -Dmain=ctos_upstream_main -c ssh.c -o policy-ssh.o
	$(CC) $(CPPFLAGS) $(CFLAGS) $(POLICY_FLAGS) -c sshconnect.c -o policy-sshconnect.o
	$(CC) $(CPPFLAGS) $(POLICY_STRICT) $(POLICY_FLAGS) -c ssh_config_policy.c -o policy.o
	$(CC) $(CPPFLAGS) $(POLICY_STRICT) $(POLICY_FLAGS) -c ssh_config_policy_probe.c -o probe.o
	$(CC) $(POLICY_STRICT) -c ssh_file_view.c -o file-view.o
	$(CC) $(POLICY_STRICT) -c ssh_file_io.c -o file-io.o
	$(LD) -o output/libctos_ssh_policy_probe.so probe.o policy.o file-view.o file-io.o policy-readconf.o policy-ssh.o policy-sshconnect.o $(addprefix $(POLICY_BASE_BUILD)/,$(POLICY_BASE)) $(LDFLAGS) $(POLICY_WRAP_FLAGS) -lssh -lopenbsd-compat $(LIBS) $(GSSLIBS) $(CHANNELLIBS)
MAKEFILE
	(cd -- "$run_dir" && timeout --signal=TERM --kill-after=10s 180s env -i "PATH=$NDK_BIN:/usr/bin:/bin" LANG=C LC_ALL=C make --no-print-directory -f Policy.mk "POLICY_BASE_BUILD=$build" policy) >"$run_dir/evidence/build.log" 2>&1
	tree_manifest "$run_dir/evidence/baseline-after.sha256"
	cmp -- "$run_dir/evidence/baseline-before.sha256" "$run_dir/evidence/baseline-after.sha256"
	"$NDK_BIN/llvm-readelf" --file-header --program-headers --dynamic --wide "$run_dir/output/libctos_ssh_policy_probe.so" >"$run_dir/evidence/elf.txt"
	sha256sum -- "$run_dir/output/libctos_ssh_policy_probe.so" >"$run_dir/evidence/artifact.sha256"
	cp -r -- "$BASELINE/evidence/source-notices" "$run_dir/evidence/"
	printf '%s\n' 'Synthetic parser restrictions and missing-provider native guards only; no production path/broker/host-approval implementation.' >"$run_dir/evidence/LOCAL-CHANGES.txt"
	mkdir -p -- "$GENERATED_ROOT"
	[[ "$(realpath -e -- "$GENERATED_ROOT")" == "$GENERATED_ROOT" ]] || die 'noncanonical generated root'
	local jni_stage asset_stage
	jni_stage=$(mktemp -d "$GENERATED_ROOT/.sshPolicyProbeJniLibs.XXXXXXXX")
	asset_stage=$(mktemp -d "$GENERATED_ROOT/.sshPolicyProbeAssets.XXXXXXXX")
	mkdir -p -- "$jni_stage/arm64-v8a" "$asset_stage/licenses/ssh-policy"
	cp -- "$run_dir/output/libctos_ssh_policy_probe.so" "$jni_stage/arm64-v8a/"
	cp -r -- "$run_dir/evidence/source-notices" "$asset_stage/licenses/ssh-policy/"
	cp -- "$run_dir/evidence/LOCAL-CHANGES.txt" "$run_dir/evidence/policy-inputs.sha256" \
		"$run_dir/evidence/artifact.sha256" "$run_dir/evidence/baseline-before.sha256" \
		"$run_dir/evidence/baseline-after.sha256" "$run_dir/config-policy.patch" \
		"$BASELINE/inputs/openssh-10.6p1-bionic-includes.patch" "$asset_stage/licenses/ssh-policy/"
	printf '%s\n' 'ctos-ssh-policy-probe-v1' >"$jni_stage/.ctos-ssh-policy-owned"
	printf '%s\n' 'ctos-ssh-policy-probe-v1' >"$asset_stage/.ctos-ssh-policy-owned"
	publish_directory "$jni_stage" "$GENERATED_ROOT/sshPolicyProbeJniLibs" retired-jni
	publish_directory "$asset_stage" "$GENERATED_ROOT/sshPolicyProbeAssets" retired-assets
	printf 'Policy cross-build complete; ARM program was not executed: %s\n' "$run_dir" >&2
}
main "$@"
