#!/usr/bin/env bash
set -Eeuo pipefail

readonly BASELINE=/app/build/ssh-native-g1/stock-run.lOHF9Oai
readonly BASELINE_TREE_SHA=e3b0c5215b8050db328bd8773cc4ba00212eb9f8b21d924ed0ab207501ef0ed6
readonly RESEARCH=/app/.trellis/tasks/10-08-ssh-terminal-client/research
readonly SOURCE_ROOT=/app/android/app/src/main/cpp
readonly NDK_BIN=/app/.devhome/android-sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/bin
readonly OUTPUT_ROOT=/app/build/ssh-native-kex
readonly GENERATED_ROOT=/app/build/app/generated
readonly STYLE_TITLE=$'\033[1;36m'
readonly STYLE_ERROR=$'\033[1;31m'
readonly STYLE_RESET=$'\033[0m'

style_text() {
	local style=$1 message=$2
	if [[ -t 2 && -z "${NO_COLOR:-}" ]]; then
		printf '%s%s%s\n' "$style" "$message" "$STYLE_RESET" >&2
	else
		printf '%s\n' "$message" >&2
	fi
}
die() {
	style_text "$STYLE_ERROR" "Error: $*"
	exit 1
}
usage() {
	style_text "$STYLE_TITLE" 'Build the fixed Android in-memory real KEX fixture'
	printf 'Usage: bash android-kex-build.sh --build\nRun only in project hako; no ARM execution or production packaging.\n' >&2
}
regular() {
	[[ -f "$1" && ! -L "$1" && "$(realpath -e -- "$1")" == "$1" ]] || die 'noncanonical input file'
}
tree_manifest() {
	local destination=$1 member descendant
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
publish_directory() {
	local prepared=$1 destination=$2 retired=$3 run_dir=$4
	[[ ! -L "$destination" ]] || die 'symlinked fixture output'
	if [[ -e "$destination" ]]; then
		[[ -d "$destination" && "$(realpath -e -- "$destination")" == "$destination" ]] || die 'noncanonical fixture output'
		if [[ -e "$destination/.ctos-ssh-kex-owned" ]]; then
			regular "$destination/.ctos-ssh-kex-owned"
			grep -Fxq 'ctos-ssh-kex-probe-v1' "$destination/.ctos-ssh-kex-owned" || die 'unowned fixture output'
		else
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
	local tool run_dir
	for tool in realpath find sha256sum sort xargs cmp cp mkdir mktemp patch make env timeout grep cat mv; do
		command -v "$tool" >/dev/null 2>&1 || die "missing required command: $tool"
	done
	[[ -f /.dockerenv && -f /app/hako-env.sh ]] || die 'run only inside project hako'
	[[ "$(realpath -e -- "${BASH_SOURCE[0]}")" == "$RESEARCH/android-kex-build.sh" ]] || die 'unexpected runner mount'
	regular "$RESEARCH/openssh-10.6p1-kex-guard.patch"
	for tool in ssh_kex_guard.c ssh_kex_guard.h ssh_role_bootstrap.c ssh_role_bootstrap.h \
		ssh_native_launcher.c ssh_native_launcher.h; do regular "$SOURCE_ROOT/$tool"; done
	regular /app/android/app/src/test/cpp/ssh_kex_test.c
	[[ -x "$NDK_BIN/aarch64-linux-android28-clang" && -x "$NDK_BIN/llvm-readelf" ]] || die 'pinned NDK missing'
	mkdir -p -- "$OUTPUT_ROOT"
	[[ "$(realpath -e -- "$OUTPUT_ROOT")" == "$OUTPUT_ROOT" ]] || die 'unexpected output mount'
	run_dir=$(mktemp -d "$OUTPUT_ROOT/run.XXXXXXXX")
	printf 'run_dir=%s\n' "$run_dir"
	mkdir -- "$run_dir/evidence" "$run_dir/output" "$run_dir/openbsd-compat"
	tree_manifest "$run_dir/evidence/baseline-before.sha256"
	cp -- "$BASELINE/openssh-build/Makefile" "$run_dir/Makefile.upstream"
	cp -- "$BASELINE/openssh-build/config.h" "$BASELINE/openssh-build/libssh.a" "$run_dir/"
	cp -- "$BASELINE/openssh-build/openbsd-compat/libopenbsd-compat.a" "$run_dir/openbsd-compat/"
	for tool in kex.c kexgen.c kexgexc.c; do
		regular "$BASELINE/sources/openssh-10.6p1/$tool"
		cp -- "$BASELINE/sources/openssh-10.6p1/$tool" "$run_dir/"
		sha256sum -- "$BASELINE/sources/openssh-10.6p1/$tool" >>"$run_dir/evidence/original-sources.sha256"
	done
	cp -- "$SOURCE_ROOT/ssh_kex_guard.c" "$SOURCE_ROOT/ssh_kex_guard.h" \
		"$SOURCE_ROOT/ssh_role_bootstrap.c" "$SOURCE_ROOT/ssh_role_bootstrap.h" \
		"$SOURCE_ROOT/ssh_native_launcher.c" "$SOURCE_ROOT/ssh_native_launcher.h" \
		/app/android/app/src/test/cpp/ssh_kex_test.c "$run_dir/"
	cp -- "$RESEARCH/openssh-10.6p1-kex-guard.patch" "$run_dir/kex-guard.patch"
	(cd -- "$run_dir" && patch --batch --fuzz=0 -p1 <kex-guard.patch) >"$run_dir/evidence/patch.log" 2>&1
	sha256sum -- "$run_dir/"*.c "$run_dir/"*.h "$run_dir/kex-guard.patch" >"$run_dir/evidence/kex-inputs.sha256"
	cat >"$run_dir/Kex.mk" <<'MAKEFILE'
include Makefile.upstream
.PHONY: guarded-kex
KEX_FLAGS = -DCTOS_SSH_KEX_GUARD=1
KEX_STRICT = -std=c11 -O2 -fPIE -Wall -Wextra -Werror
guarded-kex:
	$(CC) $(CPPFLAGS) $(CFLAGS) $(KEX_FLAGS) -c kex.c -o guarded-kex.o
	$(CC) $(CPPFLAGS) $(CFLAGS) $(KEX_FLAGS) -c kexgen.c -o guarded-kexgen.o
	$(CC) $(CPPFLAGS) $(CFLAGS) $(KEX_FLAGS) -c kexgexc.c -o guarded-kexgexc.o
	$(CC) $(CPPFLAGS) $(KEX_STRICT) -c ssh_kex_guard.c -o guard.o
	$(CC) $(CPPFLAGS) $(KEX_STRICT) -c ssh_role_bootstrap.c -o role.o
	$(CC) $(CPPFLAGS) $(KEX_STRICT) -c ssh_native_launcher.c -o launcher.o
	$(CC) $(CPPFLAGS) $(KEX_STRICT) -c ssh_kex_test.c -o test.o
	$(LD) -o output/libctos_ssh_kex_probe.so test.o launcher.o guard.o role.o guarded-kex.o guarded-kexgen.o guarded-kexgexc.o $(BUILDDIR)/ssh-pkcs11.o $(BUILDDIR)/ssh-sk-client.o $(LDFLAGS) -Wl,--wrap=sshkey_sign -Wl,--wrap=sshkey_verify -Wl,--wrap=mm_choose_dh -lssh -lopenbsd-compat $(LIBS) $(CHANNELLIBS)
MAKEFILE
	(cd -- "$run_dir" && timeout --signal=TERM --kill-after=10s 180s env -i "PATH=$NDK_BIN:/usr/bin:/bin" LANG=C LC_ALL=C make --no-print-directory -f Kex.mk guarded-kex) >"$run_dir/evidence/build.log" 2>&1
	tree_manifest "$run_dir/evidence/baseline-after.sha256"
	cmp -- "$run_dir/evidence/baseline-before.sha256" "$run_dir/evidence/baseline-after.sha256"
	"$NDK_BIN/llvm-readelf" --file-header --program-headers --dynamic --wide "$run_dir/output/libctos_ssh_kex_probe.so" >"$run_dir/evidence/elf.txt"
	sha256sum -- "$run_dir/output/libctos_ssh_kex_probe.so" >"$run_dir/evidence/artifact.sha256"
	cp -r -- "$BASELINE/evidence/source-notices" "$run_dir/evidence/"
	mkdir -p -- "$GENERATED_ROOT"
	[[ "$(realpath -e -- "$GENERATED_ROOT")" == "$GENERATED_ROOT" ]] || die 'noncanonical generated root'
	local jni_stage asset_stage
	jni_stage=$(mktemp -d "$GENERATED_ROOT/.sshKexProbeJniLibs.XXXXXXXX")
	asset_stage=$(mktemp -d "$GENERATED_ROOT/.sshKexProbeAssets.XXXXXXXX")
	mkdir -p -- "$jni_stage/arm64-v8a" "$asset_stage/licenses/ssh-kex"
	cp -- "$run_dir/output/libctos_ssh_kex_probe.so" "$jni_stage/arm64-v8a/"
	cp -r -- "$run_dir/evidence/source-notices" "$asset_stage/licenses/ssh-kex/"
	cp -- "$run_dir/kex-guard.patch" "$run_dir/evidence/kex-inputs.sha256" \
		"$run_dir/evidence/artifact.sha256" "$run_dir/evidence/baseline-before.sha256" \
		"$run_dir/evidence/baseline-after.sha256" "$asset_stage/licenses/ssh-kex/"
	printf '%s\n' 'Synthetic in-memory KEX with launcher-owned expected public host keys. No product trust classification, broker role issuance, private release or SSH server.' >"$asset_stage/licenses/ssh-kex/LOCAL-CHANGES.txt"
	printf '%s\n' 'ctos-ssh-kex-probe-v1' >"$jni_stage/.ctos-ssh-kex-owned"
	printf '%s\n' 'ctos-ssh-kex-probe-v1' >"$asset_stage/.ctos-ssh-kex-owned"
	publish_directory "$jni_stage" "$GENERATED_ROOT/sshKexProbeJniLibs" retired-jni "$run_dir"
	publish_directory "$asset_stage" "$GENERATED_ROOT/sshKexProbeAssets" retired-assets "$run_dir"
	printf 'KEX cross-build complete; ARM program was not executed: %s\n' "$run_dir"
}
main "$@"
