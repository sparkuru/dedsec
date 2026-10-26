#!/usr/bin/env bash
set -Eeuo pipefail

readonly PROJECT_ROOT=/app
readonly BUILD_ROOT="$PROJECT_ROOT/build/ssh-native-g1"
readonly NDK_ROOT="$PROJECT_ROOT/.devhome/android-sdk/ndk/27.0.12077973"
readonly NDK_BIN="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
readonly OPENSSH_SHA=a9dc9565dffe8640f64d863cd29a32bc4a3dbdec0566a7fc44c5d6ee767d5f39
readonly OPENSSL_SHA=603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a
readonly BIONIC_PATCH=/app/android/ssh/openssh-10.6p1-bionic-includes.patch
readonly BIONIC_PATCH_SHA=ea81738f191e7d9fb70ce0943b45ea06b07c4057d3d0128a694331b3a32f8b89
readonly UPSTREAM_INCLUDES_SHA=fbff8dd95e2e6f116a64c5fb4ab8ce4f7469136371d522aacce745f98a5c23ea
readonly UPSTREAM_BSD_MISC_C_SHA=355ff9f880563af7e7d34473e2e8f4e90ffb859dd94b23dcc3952deba38fc2a8
readonly UPSTREAM_BSD_MISC_H_SHA=2b46eaf38d67fbc2a586d326a8b2bcb3d1193218582b0d2f96a1bd1272e9cca9
readonly UPSTREAM_EXPLICIT_BZERO_SHA=2dc2c2a5c9f7fda3e69b3eb2c1b13ec18b6cc947d252f33d82c8cd691f947fec
readonly UPSTREAM_GETRRSET_SHA=eb8c71bc01082ea33a1c73c30ac9004aa6388547b873c8ec3e0c406e864c2294
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
	style_text "$STYLE_TITLE" 'Android ABI compatibility baseline research build'
	printf '\nUsage: bash android-stock-build.sh /app/build/ssh-native-g1/<input-directory>\n' >&2
	printf 'Run only inside this project hako container; inputs must already exist.\n' >&2
	printf 'No download, APK packaging, ARM execution or production adaptation.\n' >&2
	printf 'Each run is fresh. Build evidence is retained on success and failure.\n' >&2
	printf 'Applies one fixed Bionic include/macro compatibility patch; this is not unmodified stock.\n' >&2
	printf 'Public resolver branch is baseline-only; DNSSEC/trust behavior is unverified.\n' >&2
}

die() {
	style_text "$STYLE_ERROR" "Error: $*"
	printf '\n' >&2
	exit 1
}

require_command() {
	command -v "$1" >/dev/null 2>&1 || die "required command unavailable: $1"
}

assert_safe_directory() {
	local directory=$1 resolved
	[[ "$directory" =~ ^/app/build/ssh-native-g1/[A-Za-z0-9_./-]+$ ]] || die 'input directory is outside the fixed project build root'
	[[ "$directory" != */ && "$directory" != *'//'* && "$directory" != *'/./'* && "$directory" != *'/../'* && "$directory" != */. && "$directory" != */.. ]] || die 'noncanonical input directory rejected'
	[[ -d "$directory" && ! -L "$directory" ]] || die 'input directory must be an existing regular directory'
	resolved=$(realpath -e -- "$directory")
	[[ "$resolved" == "$directory" ]] || die 'symlinked input path rejected'
}

verify_archive() {
	local archive=$1 expected=$2 result
	[[ -f "$archive" && ! -L "$archive" ]] || die 'fixed input must be a regular nonsymlink file'
	[[ "$(realpath -e -- "$archive")" == "$archive" ]] || die 'symlinked input rejected'
	result=$(sha256sum -- "$archive")
	[[ "${result%% *}" == "$expected" ]] || die 'fixed source SHA256 mismatch'
}

snapshot_config() {
	local run=$1 name
	for name in config.log config.h Makefile; do
		if [[ -f "$run/openssh-build/$name" ]]; then
			cp -- "$run/openssh-build/$name" "$run/evidence/openssh-$name"
		fi
	done
	if [[ -f "$run/openssl-build/configdata.pm" ]]; then
		cp -- "$run/openssl-build/configdata.pm" "$run/evidence/openssl-configdata.pm"
	fi
}

report_exit() {
	local status=$1
	if ((status != 0)); then
		if [[ -n "${run_dir:-}" && -d "$run_dir/evidence" ]]; then
			if ! snapshot_config "$run_dir"; then
				printf 'Warning: failure configuration snapshot was incomplete.\n' >&2
			fi
			printf 'Research stage failed: %s; retained run directory: %s\n' "${stage:-preflight}" "$run_dir" >&2
		else
			printf 'Research preflight failed before creating a run directory.\n' >&2
		fi
	fi
}

run_stage() {
	local name=$1 directory=$2 seconds=$3
	shift 3
	stage=$name
	printf 'Research stage: %s\n' "$name" >&2
	{
		printf 'stage=%s directory=%q timeout_seconds=%s command=' "$name" "$directory" "$seconds"
		printf '%q ' "$@"
		printf '\n'
	} >>"$run_dir/evidence/commands.txt"
	(cd -- "$directory" && timeout --signal=TERM --kill-after=10s "${seconds}s" "$@") >"$run_dir/logs/$name.log" 2>&1
}

collect_notices() {
	local path relative
	mkdir -p -- "$run_dir/evidence/source-notices"
	find "$run_dir/sources" -type f \( -iname 'licence*' -o -iname 'license*' -o -iname 'notice*' -o -iname 'copying*' \) -print0 >"$run_dir/evidence/notice-files.bin"
	while IFS= read -r -d '' path; do
		relative=${path#"$run_dir/sources/"}
		mkdir -p -- "$run_dir/evidence/source-notices/$(dirname -- "$relative")"
		cp -- "$path" "$run_dir/evidence/source-notices/$relative"
		sha256sum -- "$path" >>"$run_dir/evidence/source-notices.sha256"
	done <"$run_dir/evidence/notice-files.bin"
	printf '%s\n' \
		'Fixed upstream source notices are retained in full for this research build.' \
		'One local Bionic compatibility patch changes includes.h, bsd-misc.c/h and getrrsetbyname.c; this is an ABI compatibility baseline, not unmodified stock.' \
		'Upstream copyright and license text are preserved; no production APK assets are installed.' \
		'Any future distribution must retain applicable notices and identify local changes.' \
		'Archive SHA256 verification is not a claim of PGP signature verification.' \
		>"$run_dir/evidence/NOTICE-policy.txt"
}

main() {
	if [[ $# == 1 && ("$1" == --help || "$1" == -h) ]]; then
		usage
		return 0
	fi
	[[ $# == 1 ]] || {
		usage
		die 'exactly one fixed input directory is required'
	}
	local input_dir=$1 source_dir ssl_source ssh_source prefix tool
	run_dir=''
	stage=preflight
	trap 'report_exit "$?"' EXIT
	for tool in bash realpath sha256sum tar timeout mkdir mktemp cp find dirname grep cat perl make env patch; do
		require_command "$tool"
	done
	[[ -f /.dockerenv && -f /app/hako-env.sh && -f /app/hako ]] || die 'run through the project hako container'
	[[ "$(realpath -e -- "${BASH_SOURCE[0]}")" == /app/android/ssh/android-stock-build.sh ]] || die 'runner must come from the fixed /app project mount'
	[[ "$(realpath -e -- "$BUILD_ROOT")" == "$BUILD_ROOT" && -d "$BUILD_ROOT" ]] || die 'project build root must already exist without symlinks'
	assert_safe_directory "$input_dir"
	verify_archive "$input_dir/openssh-10.6p1.tar.gz" "$OPENSSH_SHA"
	verify_archive "$input_dir/openssl-3.5.9.tar.gz" "$OPENSSL_SHA"
	verify_archive "$BIONIC_PATCH" "$BIONIC_PATCH_SHA"
	for tool in aarch64-linux-android28-clang llvm-ar llvm-ranlib llvm-readelf; do
		[[ -x "$NDK_BIN/$tool" ]] || die "fixed NDK tool unavailable: $tool"
	done
	[[ -f "$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include/zlib.h" ]] || die 'public NDK zlib header required'
	[[ -f "$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/28/libz.so" ]] || die 'public API28 NDK zlib link stub required'
	run_dir=$(mktemp -d "$BUILD_ROOT/stock-run.XXXXXXXX")
	printf 'run_dir=%s\n' "$run_dir"
	mkdir -p -- "$run_dir/sources" "$run_dir/inputs" "$run_dir/logs" "$run_dir/evidence" "$run_dir/openssl-build" "$run_dir/openssh-build"
	cp -- "$input_dir/openssh-10.6p1.tar.gz" "$input_dir/openssl-3.5.9.tar.gz" "$run_dir/inputs/"
	cp -- "$BIONIC_PATCH" "$run_dir/inputs/openssh-10.6p1-bionic-includes.patch"
	verify_archive "$run_dir/inputs/openssh-10.6p1.tar.gz" "$OPENSSH_SHA"
	verify_archive "$run_dir/inputs/openssl-3.5.9.tar.gz" "$OPENSSL_SHA"
	verify_archive "$run_dir/inputs/openssh-10.6p1-bionic-includes.patch" "$BIONIC_PATCH_SHA"
	source_dir=$run_dir/sources
	ssl_source=$source_dir/openssl-3.5.9
	ssh_source=$source_dir/openssh-10.6p1
	prefix=$run_dir/openssl-prefix
	sha256sum -- "$run_dir/inputs/openssh-10.6p1.tar.gz" "$run_dir/inputs/openssl-3.5.9.tar.gz" >"$run_dir/evidence/source-archives.sha256"
	run_stage extract-openssl "$source_dir" 60 tar --extract --gzip --file "$run_dir/inputs/openssl-3.5.9.tar.gz" --no-same-owner --no-same-permissions
	run_stage extract-openssh "$source_dir" 60 tar --extract --gzip --file "$run_dir/inputs/openssh-10.6p1.tar.gz" --no-same-owner --no-same-permissions
	[[ -d "$ssl_source" && ! -L "$ssl_source" && -d "$ssh_source" && ! -L "$ssh_source" ]] || die 'fixed upstream source roots missing'
	if ! grep -Fxq 'MAJOR=3' "$ssl_source/VERSION.dat" || ! grep -Fxq 'MINOR=5' "$ssl_source/VERSION.dat" || ! grep -Fxq 'PATCH=9' "$ssl_source/VERSION.dat"; then
		die 'OpenSSL source version mismatch'
	fi
	if ! grep -Fq '"OpenSSH_10.6"' "$ssh_source/version.h" || ! grep -Fq '"p1"' "$ssh_source/version.h"; then
		die 'OpenSSH source version mismatch'
	fi
	[[ -f "$ssl_source/LICENSE.txt" && -f "$ssh_source/LICENCE" && -f "$ssh_source/configure" ]] || die 'source license or release configure missing'
	cp -- "$ssl_source/VERSION.dat" "$run_dir/evidence/openssl-VERSION.dat"
	cp -- "$ssh_source/version.h" "$run_dir/evidence/openssh-version.h"
	collect_notices
	verify_archive "$ssh_source/includes.h" "$UPSTREAM_INCLUDES_SHA"
	verify_archive "$ssh_source/openbsd-compat/bsd-misc.c" "$UPSTREAM_BSD_MISC_C_SHA"
	verify_archive "$ssh_source/openbsd-compat/bsd-misc.h" "$UPSTREAM_BSD_MISC_H_SHA"
	verify_archive "$ssh_source/openbsd-compat/explicit_bzero.c" "$UPSTREAM_EXPLICIT_BZERO_SHA"
	verify_archive "$ssh_source/openbsd-compat/getrrsetbyname.c" "$UPSTREAM_GETRRSET_SHA"
	cp -- "$ssh_source/includes.h" "$run_dir/evidence/unmodified-upstream-includes.h"
	cp -- "$ssh_source/openbsd-compat/bsd-misc.c" "$run_dir/evidence/unmodified-upstream-bsd-misc.c"
	cp -- "$ssh_source/openbsd-compat/bsd-misc.h" "$run_dir/evidence/unmodified-upstream-bsd-misc.h"
	cp -- "$ssh_source/openbsd-compat/getrrsetbyname.c" "$run_dir/evidence/unmodified-upstream-getrrsetbyname.c"
	sha256sum -- "$run_dir/inputs/openssh-10.6p1-bionic-includes.patch" "$ssh_source/includes.h" \
		"$ssh_source/openbsd-compat/bsd-misc.c" "$ssh_source/openbsd-compat/bsd-misc.h" \
		"$ssh_source/openbsd-compat/explicit_bzero.c" "$ssh_source/openbsd-compat/getrrsetbyname.c" >"$run_dir/evidence/local-patch-inputs.sha256"
	run_stage bionic-include-order-patch "$ssh_source" 15 patch --batch --forward --fuzz=0 -p1 --input "$run_dir/inputs/openssh-10.6p1-bionic-includes.patch"
	verify_archive "$ssh_source/openbsd-compat/explicit_bzero.c" "$UPSTREAM_EXPLICIT_BZERO_SHA"
	cp -- "$ssh_source/includes.h" "$run_dir/evidence/abi-compat-includes.h"
	cp -- "$ssh_source/openbsd-compat/bsd-misc.c" "$run_dir/evidence/abi-compat-bsd-misc.c"
	cp -- "$ssh_source/openbsd-compat/bsd-misc.h" "$run_dir/evidence/abi-compat-bsd-misc.h"
	cp -- "$ssh_source/openbsd-compat/getrrsetbyname.c" "$run_dir/evidence/abi-compat-getrrsetbyname.c"
	sha256sum -- "$ssh_source/includes.h" "$ssh_source/openbsd-compat/bsd-misc.c" \
		"$ssh_source/openbsd-compat/bsd-misc.h" "$ssh_source/openbsd-compat/explicit_bzero.c" \
		"$ssh_source/openbsd-compat/getrrsetbyname.c" >"$run_dir/evidence/local-patch-output.sha256"
	printf '%s\n' \
		'build_kind=abi_compat_baseline' \
		'Local change: Bionic unistd.h is parsed before OpenSSH defines.h fallback macros.' \
		'Local change: parenthesized bzero fallback definition/declaration prevents function-like macro expansion and preserves its real symbol.' \
		'A callable Bionic bzero macro is not assumed to provide a linkable bzero symbol; HAVE_BZERO remains the actual configure result.' \
		'explicit_bzero.c stays byte-identical, including its volatile function-pointer wipe.' \
		'Local change: Bionic plus CTOS_ANDROID_ABI_BASELINE_ONLY uses public res_init/res_query without fabricating private resolver state.' \
		'The resolver branch is unavailable without the baseline flag; original upstream state logic remains unchanged there.' \
		'Original DNS response parsing and AD information remain, but EDNS0/DO control and trusted DNSSEC validation are not established.' \
		'Ordinary getaddrinfo target lookup is unchanged. VerifyHostKeyDNS reliability and production trust are not validated.' \
		'No attribute declarations, configure cache results, crypto checks or closefrom behavior are changed.' \
		'The prior unmodified stock failure is preserved separately; this runner always creates a fresh run.' \
		>"$run_dir/evidence/local-changes.txt"
	run_stage host-perl "$run_dir" 15 perl -v
	run_stage host-make "$run_dir" 15 make --version
	run_stage ndk-clang "$run_dir" 15 "$NDK_BIN/aarch64-linux-android28-clang" --version
	local -a clean_environment=(env -i "PATH=$NDK_BIN:/usr/bin:/bin" LANG=C LC_ALL=C)
	run_stage openssl-configure "$run_dir/openssl-build" 180 "${clean_environment[@]}" "ANDROID_NDK_ROOT=$NDK_ROOT" \
		perl "$ssl_source/Configure" android-arm64 -D__ANDROID_API__=28 no-shared no-module no-dso -fPIC \
		-Wl,-z,max-page-size=16384 "--prefix=$prefix" "--openssldir=$prefix/ssl" --libdir=lib
	run_stage openssl-build-libs "$run_dir/openssl-build" 900 "${clean_environment[@]}" "ANDROID_NDK_ROOT=$NDK_ROOT" make -j4 build_libs
	run_stage openssl-install-dev "$run_dir/openssl-build" 180 "${clean_environment[@]}" "ANDROID_NDK_ROOT=$NDK_ROOT" make install_dev
	[[ -f "$prefix/lib/libcrypto.a" && ! -e "$prefix/lib/libcrypto.so" ]] || die 'private static libcrypto missing or unexpected shared library present'
	[[ -f "$prefix/include/openssl/opensslv.h" ]] || die 'private installed OpenSSL headers missing'
	run_stage openssh-configure "$run_dir/openssh-build" 300 "${clean_environment[@]}" \
		"CC=$NDK_BIN/aarch64-linux-android28-clang" "AR=$NDK_BIN/llvm-ar" "RANLIB=$NDK_BIN/llvm-ranlib" \
		'CFLAGS=-O2 -fPIE' "CPPFLAGS=-DCTOS_ANDROID_ABI_BASELINE_ONLY=1 -I$prefix/include" "LDFLAGS=-L$prefix/lib -pie -Wl,-z,max-page-size=16384" \
		'LIBS=-lz' 'PKG_CONFIG=/bin/false' 'PKG_CONFIG_PATH=' 'PKG_CONFIG_LIBDIR=' CONFIG_SITE=/dev/null \
		bash "$ssh_source/configure" --build=x86_64-pc-linux-gnu --host=aarch64-linux-android \
		"--with-ssl-dir=$prefix" --with-pie --without-rpath --without-pam --without-selinux --without-kerberos5 \
		--disable-pkcs11 --disable-security-key
	grep -Fxq '#define WITH_ZLIB 1' "$run_dir/openssh-build/config.h" || die 'Compression cannot be silently disabled'
	run_stage openssh-build "$run_dir/openssh-build" 600 "${clean_environment[@]}" make -j4 ssh ssh-keygen
	snapshot_config "$run_dir"
	for tool in ssh ssh-keygen; do
		[[ -f "$run_dir/openssh-build/$tool" ]] || die "expected stock artifact missing: $tool"
		run_stage "readelf-$tool" "$run_dir" 30 "$NDK_BIN/llvm-readelf" --file-header --program-headers --dynamic --wide "$run_dir/openssh-build/$tool"
	done
	sha256sum -- "$prefix/lib/libcrypto.a" "$prefix/include/openssl/opensslv.h" "$run_dir/openssh-build/ssh" "$run_dir/openssh-build/ssh-keygen" >"$run_dir/evidence/artifacts.sha256"
	printf '%s\n' \
		'ABI compatibility baseline with a fixed Bionic include/macro compatibility patch; not unmodified stock.' \
		'No production SSH adaptation, APK packaging or executable launch.' \
		'Public NDK zlib is linked; Compression capability is not disabled to simplify the build.' \
		'Cross-configure runtime checks are not Android runtime or algorithm validation.' \
		'Public resolver ABI branch does not establish EDNS0/DO control, trusted DNSSEC AD or VerifyHostKeyDNS reliability.' \
		'G2 must reject any VerifyHostKeyDNS acceptance bypass and retain explicit human host-key approval.' \
		'This baseline is never executed or connected to production; it is not a full G1 or trust gate pass.' \
		'App UID, device APIs, 16KiB hardware, memory adapters and SSH authentication remain untested.' \
		>"$run_dir/evidence/scope.txt"
	stage=complete
	printf 'ABI compatibility baseline artifacts and evidence retained: %s\n' "$run_dir" >&2
}

main "$@"
