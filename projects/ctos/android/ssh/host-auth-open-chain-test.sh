#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly SCRIPT_DIR
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd -P)"
readonly PROJECT_ROOT
readonly CPP="$PROJECT_ROOT/android/app/src/main/cpp"
readonly TEST="$PROJECT_ROOT/android/app/src/test/cpp"
readonly ARCHIVE="${CTOS_OPENSSH_ARCHIVE:-$PROJECT_ROOT/build/ssh-native-g1/stock-run.lOHF9Oai/inputs/openssh-10.6p1.tar.gz}"
readonly SOURCE_SHA=a9dc9565dffe8640f64d863cd29a32bc4a3dbdec0566a7fc44c5d6ee767d5f39
readonly HOSTFILE_SHA=a786566e1842996c0282647457f4dd63558cde00bc79ae1ba6bcd552bf30484a
readonly KEX_SHA=50a59606000dc871f1b29e33eeb6d77fb5da456f40526d2871357758b61f8785
readonly BANNER_SHA=ba61f35303233ebd2bf1c39d167774667766b3461721657f8af0483e51258a18
readonly PROVISIONAL_SHA=075e910206a43452da8e01bb3818e6fb5bc18e0ab8e62a5b5d7c5bd6a0da845a

usage() {
	printf 'Usage: bash %s --output <new-directory> [--openssh-build <host-build>]\n' "${0##*/}"
	printf 'Run synthetic encrypted SSH authentication chains on Linux.\n'
	printf 'An optional existing OpenSSH build is hashed, but its provenance is not verified.\n'
	printf 'Output must be a new /tmp/ctos-auth-open-chain-* or project build/ directory.\n'
	printf 'CTOS_OPENSSH_ARCHIVE may select the pinned source archive by its verified SHA-256.\n'
}

die() {
	printf 'Error: %s\n' "$*" >&2
	exit 1
}

verify_input() {
	local actual path=$1 expected=$2
	[[ -f "$path" && ! -L "$path" ]] || die "missing regular input: $path"
	actual=$(sha256sum -- "$path")
	[[ "${actual%% *}" == "$expected" ]] || die "input hash mismatch: $path"
}

run_logged() {
	local log=$1
	shift
	if ! "$@" >"$log" 2>&1; then
		tail -n 60 -- "$log" >&2
		die "command failed: $1"
	fi
}

upstream_manifest() {
	(
		cd -- "$1"
		find -P . -type f \( -name '*.h' -o -name 'libssh.a' -o -name 'libopenbsd-compat.a' \
			-o -name 'ssh-pkcs11.o' -o -name 'ssh-sk-client.o' \) -print0 |
			LC_ALL=C sort -z | xargs -0 sha256sum
	) >"$2"
}

main() {
	local output_dir='' source_dir='' tool unit
	while [[ $# -gt 0 ]]; do
		case "$1" in
		--output)
			[[ $# -ge 2 && -z "$output_dir" ]] || die 'invalid --output'
			output_dir=$2
			shift 2
			;;
		--openssh-build)
			[[ $# -ge 2 && -z "$source_dir" ]] || die 'invalid --openssh-build'
			source_dir=$2
			shift 2
			;;
		-h | --help)
			usage
			return 0
			;;
		*)
			usage >&2
			die "unknown argument: $1"
			;;
		esac
	done
	[[ -n "$output_dir" ]] || die '--output is required'
	for tool in cat cc cmp env find make mkdir nm openssl patch prlimit realpath sha256sum sort tail tar timeout uname xargs; do
		command -v "$tool" >/dev/null 2>&1 || die "missing required command: $tool"
	done
	[[ "$(uname -s)" == Linux ]] || die 'Linux is required for this host fixture'
	verify_input "$ARCHIVE" "$SOURCE_SHA"
	verify_input "$SCRIPT_DIR/openssh-10.6p1-hostfile-stream-writer.patch" "$HOSTFILE_SHA"
	verify_input "$SCRIPT_DIR/openssh-10.6p1-kex-guard.patch" "$KEX_SHA"
	verify_input "$SCRIPT_DIR/openssh-10.6p1-banner-atomic.patch" "$BANNER_SHA"
	verify_input "$SCRIPT_DIR/openssh-10.6p1-provisional-input.patch" "$PROVISIONAL_SHA"
	output_dir=$(realpath -m -- "$output_dir")
	[[ "$output_dir" == /tmp/ctos-auth-open-chain-* || "$output_dir" == "$PROJECT_ROOT/build/"* ]] ||
		die 'output must be a new /tmp/ctos-auth-open-chain-* or project build/ directory'
	[[ ! -e "$output_dir" ]] || die 'output directory already exists'
	umask 077
	mkdir -p -- "$output_dir"
	local -a native_sources=() inputs=(
		"$SCRIPT_DIR/host-auth-open-chain-test.sh" "$ARCHIVE" "$CPP"/ssh_*.h
		"$SCRIPT_DIR/openssh-10.6p1-hostfile-stream-writer.patch"
		"$SCRIPT_DIR/openssh-10.6p1-kex-guard.patch" "$SCRIPT_DIR/openssh-10.6p1-banner-atomic.patch"
		"$SCRIPT_DIR/openssh-10.6p1-provisional-input.patch"
		"$TEST/ssh_native_client_test.c" "$TEST/ssh_auth_open_chain_test.c"
	)
	for unit in ssh_file_view ssh_view_handle ssh_role_bootstrap ssh_callback_adapter ssh_hostfile_issuer \
		ssh_hostfile_changed ssh_host_trust ssh_material_inspect ssh_kex_guard ssh_native_client ssh_key_policy; do
		native_sources+=("$CPP/$unit.c")
	done
	inputs+=("${native_sources[@]}")
	sha256sum -- "${inputs[@]}" >"$output_dir/inputs-before.sha256"
	{
		uname -a
		cc --version
		openssl version
	} >"$output_dir/toolchain.txt"
	if [[ -z "$source_dir" ]]; then
		tar -xzf "$ARCHIVE" -C "$output_dir"
		source_dir="$output_dir/openssh-10.6p1"
		patch --silent -d "$source_dir" -p0 <"$SCRIPT_DIR/openssh-10.6p1-hostfile-stream-writer.patch"
		patch --silent -d "$source_dir" -p1 <"$SCRIPT_DIR/openssh-10.6p1-kex-guard.patch"
		patch --silent -d "$source_dir" -p1 <"$SCRIPT_DIR/openssh-10.6p1-banner-atomic.patch"
		patch --batch --fuzz=0 --silent -d "$source_dir" -p1 <"$SCRIPT_DIR/openssh-10.6p1-provisional-input.patch"
		(
			cd -- "$source_dir"
			run_logged "$output_dir/configure.log" env CPPFLAGS="-I$CPP -DCTOS_SSH_KEX_GUARD=1" \
				CFLAGS='-O2 -fPIC' ./configure --without-pam
			run_logged "$output_dir/make.log" make -j2 CFLAGS='-O2 -fPIC' \
				libssh.a openbsd-compat/libopenbsd-compat.a ssh-pkcs11.o ssh-sk-client.o
		)
		printf 'openssh_build=pinned-source-built\n' >"$output_dir/provenance.txt"
	else
		source_dir=$(realpath -- "$source_dir")
		printf 'openssh_build=reused-unverified-provenance\n' >"$output_dir/provenance.txt"
	fi
	for unit in config.h libssh.a openbsd-compat/libopenbsd-compat.a ssh-pkcs11.o ssh-sk-client.o; do
		[[ -f "$source_dir/$unit" ]] || die "missing OpenSSH host build input: $unit"
	done
	nm -g --defined-only "$source_dir/libssh.a" >"$output_dir/openssh-symbols.txt"
	grep -Eq '[[:space:]]ctos_ssh_packet_read_provisional$' "$output_dir/openssh-symbols.txt" ||
		die 'OpenSSH host build lacks the provisional packet patch'
	upstream_manifest "$source_dir" "$output_dir/openssh-before.sha256"
	run_logged "$output_dir/cc.log" cc -std=c11 -DCTOS_SSH_NATIVE_TEST_HOOKS=1 -DHAVE_CONFIG_H \
		-I"$CPP" -isystem "$source_dir" -isystem "$source_dir/openbsd-compat/include" \
		-O2 -Wall -Wextra -Werror -Wno-pointer-sign -Wno-unused-parameter -Wno-unused-result \
		-Wno-deprecated-declarations -Wno-misleading-indentation "${native_sources[@]}" \
		"$TEST/ssh_auth_open_chain_test.c" -Wl,--wrap=sshpkt_start -Wl,--wrap=sshkey_sign \
		-Wl,--wrap=sshkey_parse_private_fileblob -Wl,--start-group "$source_dir/libssh.a" \
		"$source_dir/openbsd-compat/libopenbsd-compat.a" "$source_dir/ssh-pkcs11.o" \
		"$source_dir/ssh-sk-client.o" -Wl,--end-group -lcrypto -lz -ldl -lpthread -lutil \
		-o "$output_dir/ssh_auth_open_chain_test"
	run_logged "$output_dir/test.log" prlimit --memlock=65536:65536 --core=0:0 -- \
		timeout 120 "$output_dir/ssh_auth_open_chain_test"
	cat -- "$output_dir/test.log"
	sha256sum -- "${inputs[@]}" >"$output_dir/inputs-after.sha256"
	cmp -s -- "$output_dir/inputs-before.sha256" "$output_dir/inputs-after.sha256" || die 'project inputs changed'
	upstream_manifest "$source_dir" "$output_dir/openssh-after.sha256"
	cmp -s -- "$output_dir/openssh-before.sha256" "$output_dir/openssh-after.sha256" || die 'OpenSSH inputs changed'
	sha256sum -- "$output_dir/ssh_auth_open_chain_test" >"$output_dir/binary.sha256"
	printf 'SSH_AUTH_OPEN_CHAIN_INPUTS unchanged=pass\n'
}

main "$@"
