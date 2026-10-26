#!/usr/bin/env bash
set -Eeuo pipefail

readonly PROJECT_ROOT=/app
readonly G1_ROOT=/app/build/ssh-native-g1
readonly BASELINE=/app/build/ssh-native-g1/stock-run.lOHF9Oai
readonly G3_ROOT=/app/build/ssh-native-g3
readonly NDK_ROOT=/app/.devhome/android-sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64
readonly NDK_BIN="$NDK_ROOT/bin"
readonly OPENSSH_SOURCE="$BASELINE/sources/openssh-10.6p1"
readonly OPENSSH_BUILD="$BASELINE/openssh-build"
readonly OPENSSL_PREFIX="$BASELINE/openssl-prefix"
readonly PROJECT_CPP="$PROJECT_ROOT/android/app/src/main/cpp"
readonly PROJECT_TEST_CPP="$PROJECT_ROOT/android/app/src/test/cpp"
readonly BUILD_ROOT="$PROJECT_ROOT/android/ssh"
readonly STREAM_PATCH="$BUILD_ROOT/openssh-10.6p1-hostfile-stream-writer.patch"
readonly KEX_PATCH="$BUILD_ROOT/openssh-10.6p1-kex-guard.patch"
readonly SSH_ARCHIVE_SHA=a9dc9565dffe8640f64d863cd29a32bc4a3dbdec0566a7fc44c5d6ee767d5f39
readonly SSL_ARCHIVE_SHA=603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a
readonly ABI_PATCH_SHA=ea81738f191e7d9fb70ce0943b45ea06b07c4057d3d0128a694331b3a32f8b89
readonly STREAM_PATCH_SHA=a786566e1842996c0282647457f4dd63558cde00bc79ae1ba6bcd552bf30484a
readonly KEX_PATCH_SHA=50a59606000dc871f1b29e33eeb6d77fb5da456f40526d2871357758b61f8785
readonly BASELINE_SSH_SHA=5a3a15e6f4de095419aa369383a8e133861ca2bdff3c521cdb86fa896d81fa16
readonly LIBSSH_SHA=20b12e756e3be0bf94323dbcc87c7a59eb741c9568a9b2558c089ccfaf0a04f3
readonly OPENBSD_COMPAT_SHA=a4d908e93ecefd43c09e896d0a50fd3d87f668e73378c06648211bc001e51a17
readonly CRYPTO_SHA=feb776737f8f84d4110492a03e4c4d91312a65638408851c3a509aabb9736376
readonly SSH_PKCS11_SHA=b7048c56ab4f5eb4ebb4d92df40f047b9f767fac4b81e753bec2b9c2cbfb3163
readonly SSH_SK_CLIENT_SHA=3f233f95a8c196d3006910ee5eb6cee5dd4573092a28feb181aef233f161f6a8
readonly BASELINE_TREE_SHA=e3b0c5215b8050db328bd8773cc4ba00212eb9f8b21d924ed0ab207501ef0ed6

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
    style_text "$STYLE_TITLE" 'Pinned OpenSSH hostfile issuer and caller fixture build'
    printf '\nUsage: bash android-hostfile-issuer-build.sh --baseline /app/build/ssh-native-g1/stock-run.lOHF9Oai\n' >&2
    printf 'Compiles and links the project issuer/caller against the fixed Android ABI baseline.\n' >&2
    printf 'The fixture is never wired into the normal APK and is not run without a suitable Android runtime.\n' >&2
}

die() {
    style_text "$STYLE_ERROR" "Error: $*"
    printf '\n' >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"
}

regular() {
    [[ -f "$1" && ! -L "$1" && "$(realpath -e -- "$1")" == "$1" ]] \
        || die "input must be canonical and nonsymlink: $1"
}

validate_tree() {
    local root=$1
    [[ -d "$root" && ! -L "$root" && "$(realpath -e -- "$root")" == "$root" ]] \
        || die "input tree must be canonical and nonsymlink: $root"
    find -P "$root" -print0 | while IFS= read -r -d '' member; do
        [[ ! -L "$member" && (-d "$member" || -f "$member") \
            && "$(realpath -e -- "$member")" == "$member" ]] \
            || die "input tree contains a symlink or nonregular member: $member"
    done
}

fixed_hash() {
    local path=$1 expected=$2 actual
    regular "$path"
    actual=$(sha256sum -- "$path")
    [[ "${actual%% *}" == "$expected" ]] \
        || die "SHA256 mismatch for $path: expected $expected, got ${actual%% *}"
}

baseline_manifest() {
    local root=$1 destination=$2
    (
        cd -- "$root"
        find -P openssh-build openssl-prefix sources/openssh-10.6p1 \
            evidence/source-notices -type f -print0 |
            LC_ALL=C sort -z | xargs -0 sha256sum
    ) >"$destination"
}

run_stage() {
    local name=$1 seconds=$2
    shift 2
    stage=$name
    {
        printf 'stage=%s timeout_seconds=%s command=' "$name" "$seconds"
        printf '%q ' "$@"
        printf '\n'
    } >>"$run_dir/evidence/commands.txt"
    timeout --signal=TERM --kill-after=10s "${seconds}s" "$@" \
        >"$run_dir/logs/$name.log" 2>&1
}

on_exit() {
    local exit_status=$?
    if ((exit_status != 0)); then
        printf 'issuer fixture stopped at %s; evidence retained at %s\n' \
            "${stage:-unknown}" "${run_dir:-none}" >&2
    fi
    exit "$exit_status"
}

main() {
    if [[ $# == 1 && ("$1" == --help || "$1" == -h) ]]; then
        usage
        return 0
    fi
    [[ $# == 2 && "$1" == --baseline && "$2" == "$BASELINE" ]] \
        || { usage; die 'exactly the approved --baseline path is required'; }

    local baseline=$2
    local stage=preflight
    run_dir=''
    trap on_exit EXIT

    for tool in bash realpath sha256sum timeout mkdir mktemp cp find grep sort xargs patch awk; do
        require_command "$tool"
    done
    [[ -f /.dockerenv && -f "$PROJECT_ROOT/hako-env.sh" ]] \
        || die 'run inside the project hako container'
    [[ "$(realpath -e -- "${BASH_SOURCE[0]}")" == "$BUILD_ROOT/android-hostfile-issuer-build.sh" ]] \
        || die 'runner must come from the fixed project mount'
    [[ "$(realpath -e -- "$baseline")" == "$baseline" && "$(realpath -e -- "$G1_ROOT")" == "$G1_ROOT" ]] \
        || die 'baseline path must be canonical and nonsymlink'

    for path in "$OPENSSH_SOURCE" "$OPENSSH_BUILD" "$OPENSSL_PREFIX" \
        "$BASELINE/evidence/source-notices"; do
        validate_tree "$path"
    done
    for path in \
        "$BASELINE/inputs/openssh-10.6p1.tar.gz" \
        "$BASELINE/inputs/openssl-3.5.9.tar.gz" \
        "$BASELINE/inputs/openssh-10.6p1-bionic-includes.patch"; do
        regular "$path"
    done
    fixed_hash "$BASELINE/inputs/openssh-10.6p1.tar.gz" "$SSH_ARCHIVE_SHA"
    fixed_hash "$BASELINE/inputs/openssl-3.5.9.tar.gz" "$SSL_ARCHIVE_SHA"
    fixed_hash "$BASELINE/inputs/openssh-10.6p1-bionic-includes.patch" "$ABI_PATCH_SHA"
    fixed_hash "$BUILD_ROOT/openssh-10.6p1-bionic-includes.patch" "$ABI_PATCH_SHA"
    fixed_hash "$STREAM_PATCH" "$STREAM_PATCH_SHA"
    fixed_hash "$KEX_PATCH" "$KEX_PATCH_SHA"
    fixed_hash "$OPENSSH_BUILD/ssh" "$BASELINE_SSH_SHA"
    fixed_hash "$OPENSSH_BUILD/libssh.a" "$LIBSSH_SHA"
    fixed_hash "$OPENSSH_BUILD/openbsd-compat/libopenbsd-compat.a" "$OPENBSD_COMPAT_SHA"
    fixed_hash "$OPENSSL_PREFIX/lib/libcrypto.a" "$CRYPTO_SHA"
    fixed_hash "$OPENSSH_BUILD/ssh-pkcs11.o" "$SSH_PKCS11_SHA"
    fixed_hash "$OPENSSH_BUILD/ssh-sk-client.o" "$SSH_SK_CLIENT_SHA"
    [[ -x "$NDK_BIN/llvm-ar" ]] || die 'fixed NDK llvm-ar is required'
    for object in hostfile.o ssh_api.o sshkey.o packet.o; do
        regular "$OPENSSH_BUILD/$object"
    done
    for object in hostfile.o ssh_api.o sshkey.o packet.o kex.o kexgen.o; do
        ar_member=$("$NDK_BIN/llvm-ar" t "$OPENSSH_BUILD/libssh.a" | grep -Fx "$object" || true)
        [[ "$ar_member" == "$object" ]] || die "pinned libssh.a is missing $object"
    done
    [[ -f "$OPENSSH_BUILD/config.h" && -f "$OPENSSH_BUILD/Makefile" ]] \
        || die 'pinned OpenSSH generated configuration is incomplete'
    grep -Fq 'OpenSSH_10.6' "$OPENSSH_SOURCE/version.h" \
        || die 'pinned OpenSSH source version mismatch'

    mkdir -p -- "$G3_ROOT"
    run_dir=$(mktemp -d "$G3_ROOT/run.XXXXXXXX")
    mkdir -p -- "$run_dir/logs" "$run_dir/evidence" "$run_dir/objects" \
        "$run_dir/upstream" "$run_dir/upstream/guarded"
    [[ "$(realpath -e -- "$run_dir")" == "$run_dir" ]] || die 'run directory is not canonical'
    printf 'baseline=%s\n' "$baseline" >"$run_dir/evidence/scope.txt"
    {
        printf 'scope=issuer-and-pinned-client-fixture-with-shared-production-issuer\n'
        printf 'normal_apk=production-closure-wired;fixture-pinned-client-not-wired\n'
        printf 'runtime=not_run_without_qemu_aarch64\n'
    } >>"$run_dir/evidence/scope.txt"
    baseline_manifest "$baseline" "$run_dir/evidence/baseline.sha256"
    fixed_hash "$run_dir/evidence/baseline.sha256" "$BASELINE_TREE_SHA"
    sha256sum -- "$STREAM_PATCH" "$PROJECT_CPP/ssh_hostfile_issuer.c" \
        "$PROJECT_CPP/ssh_hostfile_issuer.h" "$PROJECT_CPP/ssh_file_view.c" \
        "$PROJECT_CPP/ssh_role_bootstrap.c" "$PROJECT_CPP/ssh_callback_adapter.c" \
        "$PROJECT_CPP/ssh_callback_adapter.h" \
        "$PROJECT_CPP/ssh_kex_guard.c" "$PROJECT_CPP/ssh_kex_guard.h" \
        "$PROJECT_CPP/ssh_pinned_client.c" "$PROJECT_CPP/ssh_pinned_client.h" \
        "$PROJECT_TEST_CPP/ssh_hostfile_issuer_test.c" \
        "$PROJECT_TEST_CPP/ssh_pinned_client_test.c" \
        >"$run_dir/evidence/project-inputs.sha256"

    cp -- "$OPENSSH_SOURCE/hostfile.c" "$run_dir/upstream/hostfile.c"
    cp -- "$OPENSSH_SOURCE/hostfile.h" "$run_dir/upstream/hostfile.h"
    patch --silent -d "$run_dir/upstream" -p0 <"$STREAM_PATCH"
    grep -Fq 'ctos_hostfile_write_entry' "$run_dir/upstream/hostfile.c" \
        || die 'stream-writer hook is absent after patch application'
    grep -Fq 'ctos_hostfile_write_entry' "$run_dir/upstream/hostfile.h" \
        || die 'stream-writer declaration is absent after patch application'
    for source in kex.c kexgen.c kexgexc.c; do
        cp -- "$OPENSSH_SOURCE/$source" "$run_dir/upstream/guarded/$source"
    done
    patch --silent -d "$run_dir/upstream/guarded" -p1 <"$KEX_PATCH"
    grep -Fq 'CTOS_SSH_KEX_GUARD' "$run_dir/upstream/guarded/kex.c" \
        || die 'KEX guard patch is absent after application'
    for forbidden in add_host_to_hostfile hostfile_replace_entries hostfile_create_user_ssh_dir \
        ssh_connect ssh_login ssh_userauth2 ssh_proxy_ fork execv; do
        if grep -Fq "$forbidden" "$PROJECT_CPP/ssh_hostfile_issuer.c" \
            || grep -Fq "$forbidden" "$PROJECT_CPP/ssh_pinned_client.c" \
            || grep -Fq "$forbidden" "$PROJECT_TEST_CPP/ssh_pinned_client_test.c"; then
            die "issuer/caller fixture must not use forbidden $forbidden"
        fi
    done
    local normal_ssh_inputs
    normal_ssh_inputs=$(sed -n "/tasks.register('compileSshFiles', Exec)/,/^}/p" \
        "$PROJECT_ROOT/android/app/build.gradle")
    [[ -n "$normal_ssh_inputs" ]] \
        || die 'normal compileSshFiles input block is missing'
    if grep -Fq 'ssh_pinned_client.c' <<<"$normal_ssh_inputs"; then
        die 'fixture source is wired into the normal compileSshFiles input: ssh_pinned_client.c'
    fi
    for required_source in ssh_hostfile_issuer.c ssh_kex_guard.c ssh_native_client.c; do
        grep -Fq "$required_source" <<<"$normal_ssh_inputs" \
            || die "normal compileSshFiles is missing production source: $required_source"
    done

    local cc="$NDK_BIN/aarch64-linux-android28-clang"
    local readelf="$NDK_BIN/llvm-readelf"
    local nm="$NDK_BIN/llvm-nm"
    [[ -x "$cc" && -x "$readelf" && -x "$nm" && -x "$NDK_BIN/llvm-ar" ]] \
        || die 'fixed NDK compiler/binutils are required'
    local -a common_flags=(
        -std=c11 -DCTOS_ANDROID_ABI_BASELINE_ONLY=1 -DHAVE_CONFIG_H
        -I"$run_dir/upstream" -I"$OPENSSH_BUILD" -I"$OPENSSH_SOURCE"
        -I"$OPENSSH_BUILD/openbsd-compat/include" -I"$OPENSSL_PREFIX/include"
        -I"$PROJECT_CPP" -fPIE -O2 -Wall -Wextra -Werror
    )
    run_stage compile-hostfile 120 "$cc" "${common_flags[@]}" -c \
        "$run_dir/upstream/hostfile.c" -o "$run_dir/objects/hostfile.o"
    run_stage compile-issuer 120 "$cc" "${common_flags[@]}" -c \
        "$PROJECT_CPP/ssh_hostfile_issuer.c" -o "$run_dir/objects/issuer.o"
    run_stage compile-view 120 "$cc" "${common_flags[@]}" -c \
        "$PROJECT_CPP/ssh_file_view.c" -o "$run_dir/objects/view.o"
    run_stage compile-role 120 "$cc" "${common_flags[@]}" -c \
        "$PROJECT_CPP/ssh_role_bootstrap.c" -o "$run_dir/objects/role.o"
    run_stage compile-callback 120 "$cc" "${common_flags[@]}" -c \
        "$PROJECT_CPP/ssh_callback_adapter.c" -o "$run_dir/objects/callback.o"
    run_stage compile-kex 120 "$cc" "${common_flags[@]}" -DCTOS_SSH_KEX_GUARD=1 \
        -Wno-pointer-sign -Wno-unused-parameter -Wno-deprecated-declarations -Wno-unused-result \
        -c "$run_dir/upstream/guarded/kex.c" -o "$run_dir/objects/guarded-kex.o"
    run_stage compile-kexgen 120 "$cc" "${common_flags[@]}" -DCTOS_SSH_KEX_GUARD=1 \
        -Wno-pointer-sign -Wno-unused-parameter -Wno-deprecated-declarations -Wno-unused-result \
        -c "$run_dir/upstream/guarded/kexgen.c" -o "$run_dir/objects/guarded-kexgen.o"
    run_stage compile-kexgexc 120 "$cc" "${common_flags[@]}" -DCTOS_SSH_KEX_GUARD=1 \
        -Wno-pointer-sign -Wno-unused-parameter -Wno-deprecated-declarations -Wno-unused-result \
        -c "$run_dir/upstream/guarded/kexgexc.c" -o "$run_dir/objects/guarded-kexgexc.o"
    run_stage compile-kex-guard 120 "$cc" "${common_flags[@]}" -I"$PROJECT_CPP" -c \
        "$PROJECT_CPP/ssh_kex_guard.c" -o "$run_dir/objects/kex-guard.o"
    run_stage compile-pinned-client 120 "$cc" "${common_flags[@]}" -c \
        "$PROJECT_CPP/ssh_pinned_client.c" -o "$run_dir/objects/pinned-client.o"
    run_stage compile-test 120 "$cc" "${common_flags[@]}" -c \
        "$PROJECT_TEST_CPP/ssh_hostfile_issuer_test.c" -o "$run_dir/objects/test.o"
    run_stage compile-pinned-test 120 "$cc" "${common_flags[@]}" -c \
        "$PROJECT_TEST_CPP/ssh_pinned_client_test.c" -o "$run_dir/objects/pinned-test.o"

    run_stage link-issuer 180 "$cc" -fPIE -pie -Wl,-z,max-page-size=16384 \
        -Wl,-z,relro -Wl,-z,now -Wl,--no-undefined \
        "$run_dir/objects/hostfile.o" "$run_dir/objects/issuer.o" \
        "$run_dir/objects/view.o" "$run_dir/objects/role.o" \
        "$run_dir/objects/callback.o" "$run_dir/objects/test.o" \
        "$OPENSSH_BUILD/ssh-pkcs11.o" "$OPENSSH_BUILD/ssh-sk-client.o" \
        -Wl,--start-group "$OPENSSH_BUILD/libssh.a" \
        "$OPENSSH_BUILD/openbsd-compat/libopenbsd-compat.a" \
        "$OPENSSL_PREFIX/lib/libcrypto.a" -lz -Wl,--end-group \
        -o "$run_dir/ctos_ssh_hostfile_issuer_test"

    run_stage link-pinned-client 180 "$cc" -fPIE -pie -Wl,-z,max-page-size=16384 \
        -Wl,-z,relro -Wl,-z,now -Wl,--no-undefined \
        "$run_dir/objects/hostfile.o" "$run_dir/objects/issuer.o" \
        "$run_dir/objects/view.o" "$run_dir/objects/role.o" \
        "$run_dir/objects/callback.o" "$run_dir/objects/pinned-client.o" \
        "$run_dir/objects/guarded-kex.o" "$run_dir/objects/guarded-kexgen.o" \
        "$run_dir/objects/guarded-kexgexc.o" "$run_dir/objects/kex-guard.o" \
        "$run_dir/objects/pinned-test.o" \
        "$OPENSSH_BUILD/ssh-pkcs11.o" "$OPENSSH_BUILD/ssh-sk-client.o" \
        -Wl,--start-group "$OPENSSH_BUILD/libssh.a" \
        "$OPENSSH_BUILD/openbsd-compat/libopenbsd-compat.a" \
        "$OPENSSL_PREFIX/lib/libcrypto.a" -lz -Wl,--end-group \
        -o "$run_dir/ctos_ssh_pinned_client_test"

    for binary in ctos_ssh_hostfile_issuer_test ctos_ssh_pinned_client_test; do
        "$readelf" -h "$run_dir/$binary" >"$run_dir/evidence/$binary.elf-header.txt"
        grep -Fq 'Machine:                           AArch64' \
            "$run_dir/evidence/$binary.elf-header.txt" \
            || die "$binary is not AArch64"
        grep -Fq 'Type:                              DYN' \
            "$run_dir/evidence/$binary.elf-header.txt" \
            || die "$binary is not a PIE executable"
    done
    "$nm" -g --defined-only "$run_dir/objects/hostfile.o" \
        >"$run_dir/evidence/hostfile-symbols.txt"
    grep -Eq '[[:space:]]ctos_hostfile_write_entry$' "$run_dir/evidence/hostfile-symbols.txt" \
        || die 'patched hostfile object does not export the stream writer'
    "$nm" -g --defined-only "$run_dir/ctos_ssh_pinned_client_test" \
        >"$run_dir/evidence/fixture-symbols.txt"
    for symbol in ctos_ssh_hostfile_issue ctos_ssh_pinned_client_open \
        ctos_ssh_callback_known_hosts_candidate \
        ctos_ssh_callback_known_hosts_candidate_abort ctos_ssh_callback_kex_complete; do
        grep -Eq "[[:space:]]$symbol$" "$run_dir/evidence/fixture-symbols.txt" \
            || die "fixture is missing $symbol"
    done
    if grep -Eq '[[:space:]](ssh_connect|ssh_login|ssh_userauth2|ssh_proxy_)' \
        "$run_dir/evidence/fixture-symbols.txt"; then
        die 'stock high-level SSH caller leaked into issuer/caller fixture'
    fi
    sha256sum -- "$run_dir"/objects/*.o "$run_dir"/ctos_ssh_hostfile_issuer_test \
        "$run_dir/ctos_ssh_pinned_client_test" \
        >"$run_dir/evidence/objects.sha256"
    if command -v qemu-aarch64 >/dev/null 2>&1; then
        printf 'qemu_aarch64=present_but_not_invoked_without_android_runtime_mapping\n' \
            >>"$run_dir/evidence/scope.txt"
    else
        printf 'qemu_aarch64=missing_runtime_not_run\n' >>"$run_dir/evidence/scope.txt"
    fi
    printf 'run_dir=%s\n' "$run_dir"
    printf 'SSH_HOSTFILE_CALLER_BUILD gate=pass runtime=not-run normal_apk=fixture-caller-not-wired\n'
}

main "$@"
