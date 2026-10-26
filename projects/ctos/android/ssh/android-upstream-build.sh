#!/usr/bin/env bash
set -Eeuo pipefail

readonly PROJECT_ROOT=/app
readonly G1_ROOT=/app/build/ssh-native-g1
readonly G2_ROOT=/app/build/ssh-native-g2
readonly NDK_BIN=/app/.devhome/android-sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/bin
readonly SOURCE=/app/android/app/src/main/cpp/ssh_upstream_probe.c
readonly ABI_PATCH=/app/android/ssh/openssh-10.6p1-bionic-includes.patch
readonly SSH_ARCHIVE_SHA=a9dc9565dffe8640f64d863cd29a32bc4a3dbdec0566a7fc44c5d6ee767d5f39
readonly SSL_ARCHIVE_SHA=603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a
readonly PATCH_SHA=ea81738f191e7d9fb70ce0943b45ea06b07c4057d3d0128a694331b3a32f8b89
readonly BASELINE_SSH_SHA=5a3a15e6f4de095419aa369383a8e133861ca2bdff3c521cdb86fa896d81fa16
readonly CRYPTO_SHA=feb776737f8f84d4110492a03e4c4d91312a65638408851c3a509aabb9736376
readonly BASELINE_TREE_SHA=e3b0c5215b8050db328bd8773cc4ba00212eb9f8b21d924ed0ab207501ef0ed6
readonly GENERATED_ROOT=/app/build/app/generated
readonly JNI_OUTPUT="$GENERATED_ROOT/sshUpstreamProbeJniLibs"
readonly ASSET_OUTPUT="$GENERATED_ROOT/sshUpstreamProbeAssets"
readonly -a PROBE_NAMES=(libctos_ssh_upstream_probe.so libctos_ssh_upstream_client.so libctos_ssh_upstream_baseline.so)
readonly STYLE_TITLE=$'\033[1;36m'
readonly STYLE_ERROR=$'\033[1;31m'
readonly STYLE_RESET=$'\033[0m'

style_text() {
	local style=$1 text=$2
	if [[ -t 2 && -z "${NO_COLOR:-}" ]]; then
		printf '%s%s%s' "$style" "$text" "$STYLE_RESET" >&2
	else
		printf '%s' "$text" >&2
	fi
}
usage() {
	style_text "$STYLE_TITLE" 'Android trusted-synthetic upstream probe build'
	printf '\nUsage: bash android-upstream-build.sh --baseline /app/build/ssh-native-g1/stock-run.<id>\n' >&2
	printf 'Use only this project hako container and the frozen approved ABI baseline.\n' >&2
	printf 'Links fixture-only driver/VFS client/closefrom observer; never runs ARM binaries.\n' >&2
}
die() {
	style_text "$STYLE_ERROR" "Error: $*"
	printf '\n' >&2
	exit 1
}
require_command() { command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"; }
regular() {
	[[ -f "$1" && ! -L "$1" && "$(realpath -e -- "$1")" == "$1" ]] || die 'input must be canonical and nonsymlink'
}
validate_tree() {
	local root=$1
	[[ -d "$root" && ! -L "$root" && "$(realpath -e -- "$root")" == "$root" ]] || die 'input tree must be canonical and nonsymlink'
	# -P never follows descendants; pipefail preserves traversal/read errors.
	find -P "$root" -print0 | while IFS= read -r -d '' member; do
		[[ ! -L "$member" && (-d "$member" || -f "$member") && "$(realpath -e -- "$member")" == "$member" ]] || die 'input tree contains a symlink or nonregular member'
	done
}
baseline_manifest() {
	local baseline=$1 destination=$2
	# The digest is specific to the approved lOH run, including its configuration,
	# installed headers/libraries, actual object files and retained source notices.
	# The fixed inner Bash program expands its own positional argument.
	# shellcheck disable=SC2016
	timeout --signal=TERM --kill-after=10s 60s bash -c '
set -Eeuo pipefail
cd -- "$1"
find -P openssh-build openssl-prefix sources/openssh-10.6p1 evidence/source-notices -type f -print0 |
    LC_ALL=C sort -z | xargs -0 sha256sum
' bash "$baseline" >"$destination"
	fixed_hash "$destination" "$BASELINE_TREE_SHA"
}
fixed_hash() {
	local path=$1 expected=$2 actual
	regular "$path"
	actual=$(sha256sum -- "$path")
	[[ "${actual%% *}" == "$expected" ]] || die 'frozen input SHA256 mismatch'
}
retained_exit() {
	local status=$1
	if ((status != 0)); then
		printf 'G2 build failed at %s; evidence retained at %s\n' "${stage:-preflight}" "${run_dir:-none}" >&2
	fi
}
run_stage() {
	local name=$1 directory=$2 seconds=$3
	shift 3
	stage=$name
	printf 'G2 build stage: %s\n' "$stage" >&2
	{
		printf 'stage=%s directory=%q timeout_seconds=%s command=' "$name" "$directory" "$seconds"
		printf '%q ' "$@"
		printf '\n'
	} >>"$run_dir/evidence/commands.txt"
	(cd -- "$directory" && timeout --signal=TERM --kill-after=10s "${seconds}s" "$@") >"$run_dir/logs/$name.log" 2>&1
}
publish_directory() {
	local prepared=$1 destination=$2 retired=$3
	[[ "$(realpath -e -- "$GENERATED_ROOT")" == "$GENERATED_ROOT" && ! -L "$destination" ]] || die 'generated output path is not canonical'
	if [[ -e "$destination" ]]; then
		[[ -d "$destination" && -f "$destination/.ctos-ssh-upstream-owned" ]] || die 'refuse to replace unowned generated output'
		grep -Fxq 'ctos-ssh-upstream-probe-v1' "$destination/.ctos-ssh-upstream-owned" || die 'generated ownership marker mismatch'
		mv -- "$destination" "$run_dir/$retired"
	fi
	mv -- "$prepared" "$destination"
}
main() {
	if [[ $# == 1 && ("$1" == --help || "$1" == -h) ]]; then
		usage
		return 0
	fi
	[[ $# == 2 && "$1" == --baseline ]] || {
		usage
		die 'exactly --baseline and one approved path are required'
	}
	local baseline=$2 build source prefix tool
	run_dir=''
	stage=preflight
	trap 'retained_exit "$?"' EXIT
	for tool in bash realpath sha256sum cmp timeout mkdir mktemp cp mv find grep make env cat sort xargs; do require_command "$tool"; done
	[[ -f /.dockerenv && -f "$PROJECT_ROOT/hako-env.sh" ]] || die 'run inside the project hako container'
	[[ "$(realpath -e -- "${BASH_SOURCE[0]}")" == /app/android/ssh/android-upstream-build.sh ]] || die 'runner must come from the fixed project mount'
	[[ "$baseline" =~ ^/app/build/ssh-native-g1/stock-run\.[A-Za-z0-9]+$ && -d "$baseline" && ! -L "$baseline" ]] || die 'baseline must be a fixed G1 run'
	[[ "$baseline" == "$G1_ROOT/stock-run.lOHF9Oai" ]] || die 'aggregate pin supports only the approved lOH baseline'
	[[ "$(realpath -e -- "$baseline")" == "$baseline" && "$(realpath -e -- "$G1_ROOT")" == "$G1_ROOT" ]] || die 'symlinked baseline path rejected'
	build=$baseline/openssh-build
	source=$baseline/sources/openssh-10.6p1
	prefix=$baseline/openssl-prefix
	for tool in "$build" "$source" "$prefix" "$baseline/evidence/source-notices"; do validate_tree "$tool"; done
	fixed_hash "$baseline/inputs/openssh-10.6p1.tar.gz" "$SSH_ARCHIVE_SHA"
	fixed_hash "$baseline/inputs/openssl-3.5.9.tar.gz" "$SSL_ARCHIVE_SHA"
	fixed_hash "$baseline/inputs/openssh-10.6p1-bionic-includes.patch" "$PATCH_SHA"
	fixed_hash "$ABI_PATCH" "$PATCH_SHA"
	fixed_hash "$build/ssh" "$BASELINE_SSH_SHA"
	fixed_hash "$prefix/lib/libcrypto.a" "$CRYPTO_SHA"
	for tool in "$build/Makefile" "$build/config.h" "$build/libssh.a" "$build/openbsd-compat/libopenbsd-compat.a" "$SOURCE" \
		"$baseline/evidence/openssh-Makefile" "$baseline/evidence/openssh-config.h" "$baseline/evidence/local-patch-output.sha256" \
		"$baseline/evidence/NOTICE-policy.txt" "$baseline/evidence/local-changes.txt"; do regular "$tool"; done
	cmp -- "$build/Makefile" "$baseline/evidence/openssh-Makefile" || die 'baseline Makefile differs from its completed evidence'
	cmp -- "$build/config.h" "$baseline/evidence/openssh-config.h" || die 'baseline configuration differs from evidence'
	grep -Fxq '#define WITH_ZLIB 1' "$build/config.h" || die 'Compression cannot be disabled'
	grep -Fq '"OpenSSH_10.6"' "$source/version.h" || die 'baseline source version mismatch'
	fixed_hash "$source/includes.h" cb803101f4f9be821e6a95caa1081e392f7105614664d965cd2dee40f62846e4
	fixed_hash "$source/openbsd-compat/bsd-misc.c" c8e5c6b226aff1f9583d0ad8598277ef5433e0be13ca9c3d367060f5a95977fe
	fixed_hash "$source/openbsd-compat/bsd-misc.h" 0f6dd021a23f8940c1069709fe31807b753a38d9ce732258640a1bab249bba1b
	fixed_hash "$source/openbsd-compat/explicit_bzero.c" 2dc2c2a5c9f7fda3e69b3eb2c1b13ec18b6cc947d252f33d82c8cd691f947fec
	fixed_hash "$source/openbsd-compat/getrrsetbyname.c" b77fbc0fc22f564e2caffaf83dc75cbcb661e30e3d099b569997bc177178b6dc
	[[ -d "$baseline/evidence/source-notices" && ! -L "$baseline/evidence/source-notices" ]] || die 'complete upstream source notices required'
	[[ -x "$NDK_BIN/aarch64-linux-android28-clang" && -x "$NDK_BIN/llvm-readelf" && -x "$NDK_BIN/llvm-nm" ]] || die 'fixed NDK tools required'
	mkdir -p -- "$G2_ROOT" "$GENERATED_ROOT"
	[[ "$(realpath -e -- "$G2_ROOT")" == "$G2_ROOT" && "$(realpath -e -- "$GENERATED_ROOT")" == "$GENERATED_ROOT" ]] || die 'generated build roots must be canonical'
	run_dir=$(mktemp -d "$G2_ROOT/run.XXXXXXXX")
	printf 'run_dir=%s\n' "$run_dir"
	mkdir -p -- "$run_dir/logs" "$run_dir/evidence" "$run_dir/openbsd-compat" "$run_dir/output"
	stage='baseline-before'
	baseline_manifest "$baseline" "$run_dir/evidence/baseline-before.sha256"
	cp -- "$SOURCE" "$run_dir/ssh_upstream_probe.c"
	cp -- "$build/Makefile" "$run_dir/Makefile.upstream"
	cp -- "$build/config.h" "$run_dir/config.h"
	cp -- "$build/libssh.a" "$run_dir/libssh.a"
	cp -- "$build/openbsd-compat/libopenbsd-compat.a" "$run_dir/openbsd-compat/libopenbsd-compat.a"
	sha256sum -- "$SOURCE" "$run_dir/ssh_upstream_probe.c" "$build/Makefile" "$build/config.h" "$build/libssh.a" \
		"$build/openbsd-compat/libopenbsd-compat.a" "$prefix/lib/libcrypto.a" >"$run_dir/evidence/inputs.sha256"
	cat >"$run_dir/G2.mk" <<'MAKEFILE'
include Makefile.upstream
.PHONY: g2-objects g2-source-manifest
G2_WRAPS = open open64 __open_2 fopen fopen64 fdopen close stat stat64 access mkdir mkstemp link rename unlink getpwuid _ssh__compat_glob _ssh__compat_globfree closefrom
G2_WRAP_FLAGS = $(addprefix -Wl$(comma)--wrap=,$(G2_WRAPS))
comma := ,
G2_STRICT = -std=c11 -O2 -fPIE -Wall -Wextra -Werror
G2_DRIVER_OBJS = $(sort $(SKOBJS) $(filter ssh-pkcs11.o,$(SSHOBJS)))
g2-source-manifest:
	@test '$(filter ssh-pkcs11.o,$(SSHOBJS))' = 'ssh-pkcs11.o'
	@printf '%s\n' $(addprefix $(G2_BASE_BUILD)/,$(sort $(SSHOBJS) $(G2_DRIVER_OBJS))) > evidence/upstream-object-paths.txt
	@printf 'SSHOBJS=%s\nSKOBJS=%s\nG2_DRIVER_OBJS=%s\nLIBS=%s\nGSSLIBS=%s\nCHANNELLIBS=%s\nLDFLAGS=%s\nCPPFLAGS=%s\n' '$(SSHOBJS)' '$(SKOBJS)' '$(G2_DRIVER_OBJS)' '$(LIBS)' '$(GSSLIBS)' '$(CHANNELLIBS)' '$(LDFLAGS)' '$(CPPFLAGS)' > evidence/upstream-link-contract.txt
g2-objects:
	$(CC) $(G2_STRICT) $(CPPFLAGS) -DSSH_UPSTREAM_DRIVER=1 -c ssh_upstream_probe.c -o driver.o
	$(CC) $(G2_STRICT) $(CPPFLAGS) -DSSH_UPSTREAM_VFS=1 -c ssh_upstream_probe.c -o vfs.o
	$(CC) $(G2_STRICT) $(CPPFLAGS) -DSSH_UPSTREAM_BASELINE=1 -c ssh_upstream_probe.c -o observer.o
g2-driver: g2-objects
	$(LD) -o output/libctos_ssh_upstream_probe.so driver.o $(addprefix $(G2_BASE_BUILD)/,$(G2_DRIVER_OBJS)) $(LDFLAGS) $(G2_WRAP_FLAGS) -lssh -lopenbsd-compat $(LIBS) $(GSSLIBS) $(CHANNELLIBS)
g2-vfs: g2-objects
	$(LD) -o output/libctos_ssh_upstream_client.so $(addprefix $(G2_BASE_BUILD)/,$(SSHOBJS)) vfs.o $(LDFLAGS) $(G2_WRAP_FLAGS) -Wl,--wrap=_exit -lssh -lopenbsd-compat $(LIBS) $(GSSLIBS) $(CHANNELLIBS)
g2-baseline: g2-objects
	$(LD) -o output/libctos_ssh_upstream_baseline.so $(addprefix $(G2_BASE_BUILD)/,$(SSHOBJS)) observer.o $(LDFLAGS) -Wl,--wrap=closefrom -lssh -lopenbsd-compat $(LIBS) $(GSSLIBS) $(CHANNELLIBS)
MAKEFILE
	local -a clean=(env -i "PATH=$NDK_BIN:/usr/bin:/bin" LANG=C LC_ALL=C)
	run_stage object-contract "$run_dir" 30 "${clean[@]}" make --no-print-directory -f G2.mk "G2_BASE_BUILD=$build" g2-source-manifest
	while IFS= read -r tool; do
		[[ "$tool" == "$build/"*.o ]] || die 'unexpected expanded upstream object path'
		regular "$tool"
		sha256sum -- "$tool" >>"$run_dir/evidence/inputs.sha256"
	done <"$run_dir/evidence/upstream-object-paths.txt"
	run_stage build-probes "$run_dir" 300 "${clean[@]}" make --no-print-directory -f G2.mk "G2_BASE_BUILD=$build" g2-driver g2-vfs g2-baseline
	stage='baseline-after'
	for tool in "$build" "$source" "$prefix" "$baseline/evidence/source-notices"; do validate_tree "$tool"; done
	baseline_manifest "$baseline" "$run_dir/evidence/baseline-after.sha256"
	cmp -- "$run_dir/evidence/baseline-before.sha256" "$run_dir/evidence/baseline-after.sha256" || die 'baseline inputs changed during the build'
	for tool in "${PROBE_NAMES[@]}"; do
		regular "$run_dir/output/$tool"
		run_stage "elf-$tool" "$run_dir" 30 "$NDK_BIN/llvm-readelf" --file-header --program-headers --dynamic --wide "$run_dir/output/$tool"
		run_stage "symbols-$tool" "$run_dir" 30 "$NDK_BIN/llvm-nm" --defined-only "$run_dir/output/$tool"
		sha256sum -- "$run_dir/output/$tool" >>"$run_dir/evidence/artifacts.sha256"
	done
	for tool in "$run_dir/output/"*; do
		case "${tool##*/}" in
		libctos_ssh_upstream_probe.so | libctos_ssh_upstream_client.so | libctos_ssh_upstream_baseline.so) regular "$tool" ;;
		*) die 'unexpected output outside the three binary allowlist' ;;
		esac
	done
	local jni_stage asset_stage notices
	jni_stage=$(mktemp -d "$GENERATED_ROOT/.sshUpstreamProbeJniLibs.XXXXXXXX")
	asset_stage=$(mktemp -d "$GENERATED_ROOT/.sshUpstreamProbeAssets.XXXXXXXX")
	mkdir -p -- "$jni_stage/arm64-v8a" "$asset_stage/licenses/ssh-upstream"
	for tool in "${PROBE_NAMES[@]}"; do cp -- "$run_dir/output/$tool" "$jni_stage/arm64-v8a/"; done
	notices=$asset_stage/licenses/ssh-upstream
	cp -r -- "$baseline/evidence/source-notices" "$notices/"
	validate_tree "$notices/source-notices"
	cp -- "$baseline/evidence/NOTICE-policy.txt" "$baseline/evidence/local-changes.txt" "$ABI_PATCH" "$notices/"
	printf '%s\n' \
		'Trusted synthetic G2 research: new driver and VFS hooks plus closefrom-only ABI observer.' \
		'Original upstream notices and ABI baseline patch are retained; no production configuration policy is supplied.' \
		'Immutable config/IdentityFile use logical-length locked anonymous FDs. Mutable public proposals use locked 16KiB funopen storage.' \
		'VFS client wraps real OpenSSH _exit cleanup paths to release adapter-owned resources; observer and driver do not wrap _exit.' \
		'Public proposal backups copy bytes; general POSIX hard-link aliases and all upstream heap/stdio locking are not claimed.' \
		'Identification-only owned loopback; no KEX, host approval, remote authentication, production broker or cross-child revoke proof.' \
		'Capacity is 16 objects x 16KiB; generation is synthetic 73, not the production vault model.' \
		>"$notices/G2-LOCAL-CHANGES.txt"
	cp -- "$run_dir/evidence/inputs.sha256" "$run_dir/evidence/artifacts.sha256" "$notices/"
	cp -- "$run_dir/evidence/baseline-before.sha256" "$run_dir/evidence/baseline-after.sha256" "$notices/"
	printf '%s\n' 'ctos-ssh-upstream-probe-v1' >"$jni_stage/.ctos-ssh-upstream-owned"
	printf '%s\n' 'ctos-ssh-upstream-probe-v1' >"$asset_stage/.ctos-ssh-upstream-owned"
	stage=publish-fixture-only
	publish_directory "$jni_stage" "$JNI_OUTPUT" retired-jni
	publish_directory "$asset_stage" "$ASSET_OUTPUT" retired-assets
	stage=complete
	printf 'Fixture-only G2 binaries/assets published; evidence: %s\n' "$run_dir" >&2
}

main "$@"
