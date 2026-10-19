#!/usr/bin/env bash
set -Eeuo pipefail

readonly PROJECT_ROOT=/app
readonly BASELINE=/app/build/ssh-native-g1/stock-run.lOHF9Oai
readonly NDK_ROOT=/app/.devhome/android-sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64
readonly NDK_BIN="$NDK_ROOT/bin"
readonly OPENSSH_SOURCE="$BASELINE/sources/openssh-10.6p1"
readonly OPENSSH_BUILD="$BASELINE/openssh-build"
readonly OPENSSL_PREFIX="$BASELINE/openssl-prefix"
readonly PROJECT_CPP="$PROJECT_ROOT/android/app/src/main/cpp"
readonly TASK_ROOT="$PROJECT_ROOT/.trellis/tasks/10-08-ssh-terminal-client"
readonly STREAM_PATCH="$TASK_ROOT/research/openssh-10.6p1-hostfile-stream-writer.patch"
readonly KEX_PATCH="$TASK_ROOT/research/openssh-10.6p1-kex-guard.patch"
readonly BANNER_PATCH="$TASK_ROOT/research/openssh-10.6p1-banner-atomic.patch"
readonly SSH_ARCHIVE_SHA=a9dc9565dffe8640f64d863cd29a32bc4a3dbdec0566a7fc44c5d6ee767d5f39
readonly SSL_ARCHIVE_SHA=603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a
readonly ABI_PATCH_SHA=ea81738f191e7d9fb70ce0943b45ea06b07c4057d3d0128a694331b3a32f8b89
readonly STREAM_PATCH_SHA=a786566e1842996c0282647457f4dd63558cde00bc79ae1ba6bcd552bf30484a
readonly KEX_PATCH_SHA=50a59606000dc871f1b29e33eeb6d77fb5da456f40526d2871357758b61f8785
readonly BANNER_PATCH_SHA=ba61f35303233ebd2bf1c39d167774667766b3461721657f8af0483e51258a18
readonly LIBSSH_SHA=20b12e756e3be0bf94323dbcc87c7a59eb741c9568a9b2558c089ccfaf0a04f3
readonly OPENBSD_COMPAT_SHA=a4d908e93ecefd43c09e896d0a50fd3d87f668e73378c06648211bc001e51a17
readonly CRYPTO_SHA=feb776737f8f84d4110492a03e4c4d91312a65638408851c3a509aabb9736376
readonly SSH_PKCS11_SHA=b7048c56ab4f5eb4ebb4d92df40f047b9f767fac4b81e753bec2b9c2cbfb3163
readonly SSH_SK_CLIENT_SHA=3f233f95a8c196d3006910ee5eb6cee5dd4573092a28feb181aef233f161f6a8
readonly BASELINE_TREE_SHA=e3b0c5215b8050db328bd8773cc4ba00212eb9f8b21d924ed0ab207501ef0ed6

readonly STYLE_TITLE=$'\033[1;36m'
readonly STYLE_ERROR=$'\033[1;31m'
readonly STYLE_RESET=$'\033[0m'

output_archive=''
scratch_dir=''

style_text() {
    local style=$1
    local text=$2
    if [[ -t 2 && -z "${NO_COLOR:-}" ]]; then
        printf '%s%s%s' "$style" "$text" "$STYLE_RESET" >&2
    else
        printf '%s' "$text" >&2
    fi
}

usage() {
    style_text "$STYLE_TITLE" 'Pinned OpenSSH production source/object closure builder'
    printf '\nUsage: bash android-production-openssh-build.sh --output <archive>\n' >&2
    printf 'Builds the normal-APK OpenSSH library closure; fixture callers remain excluded.\n' >&2
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
    while IFS= read -r -d '' member; do
        [[ ! -L "$member" && (-d "$member" || -f "$member") \
            && "$(realpath -e -- "$member")" == "$member" ]] \
            || die "input tree contains a symlink or nonregular member: $member"
    done < <(find -P "$root" -print0)
}

fixed_hash() {
    local path=$1
    local expected=$2
    local actual
    regular "$path"
    actual=$(sha256sum -- "$path")
    [[ "${actual%% *}" == "$expected" ]] \
        || die "SHA256 mismatch for $path: expected $expected, got ${actual%% *}"
}

sha256_only() {
    sha256sum -- "$1" | awk '{print $1}'
}

baseline_manifest() {
    local root=$1
    local destination=$2
    (
        cd -- "$root"
        find -P openssh-build openssl-prefix sources/openssh-10.6p1 \
            evidence/source-notices -type f -print0 |
            LC_ALL=C sort -z | xargs -0 sha256sum
    ) >"$destination"
}

cleanup() {
    if [[ -n "${scratch_dir:-}" && -d "$scratch_dir" \
        && "$scratch_dir" == /tmp/ctos-production-openssh.* ]]; then
        rm -rf -- "$scratch_dir"
    fi
}

run_stage() {
    local stage=$1
    shift
    {
        printf 'stage=%s command=' "$stage"
        printf '%q ' "$@"
        printf '\n'
    } >>"$output_archive.evidence.commands"
    "$@"
}

parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
        --output)
            [[ $# -ge 2 ]] || die '--output requires a path'
            output_archive=$2
            shift 2
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            die "unknown argument: $1"
            ;;
        esac
    done
    [[ -n "$output_archive" ]] || die '--output is required'
    [[ "$output_archive" == /* && "$output_archive" != */../* \
        && "$output_archive" != */./* ]] || die 'output must be an absolute clean path'
}

main() {
    parse_args "$@"
    trap cleanup EXIT

    for tool in bash realpath sha256sum timeout mkdir mktemp cp find grep sort xargs \
        patch awk rm diff cmp mv make; do
        require_command "$tool"
    done
    [[ -f /.dockerenv && -f "$PROJECT_ROOT/hako-env.sh" ]] \
        || die 'run inside the project hako container'
    [[ "$(realpath -e -- "${BASH_SOURCE[0]}")" \
        == "$TASK_ROOT/research/android-production-openssh-build.sh" ]] \
        || die 'runner must come from the fixed project mount'
    [[ "$(realpath -e -- "$BASELINE")" == "$BASELINE" ]] \
        || die 'baseline path must be canonical and nonsymlink'

    for path in "$OPENSSH_SOURCE" "$OPENSSH_BUILD" "$OPENSSL_PREFIX" \
        "$BASELINE/evidence/source-notices"; do
        validate_tree "$path"
    done
    for path in "$BASELINE/inputs/openssh-10.6p1.tar.gz" \
        "$BASELINE/inputs/openssl-3.5.9.tar.gz" \
        "$BASELINE/inputs/openssh-10.6p1-bionic-includes.patch"; do
        regular "$path"
    done
    fixed_hash "$BASELINE/inputs/openssh-10.6p1.tar.gz" "$SSH_ARCHIVE_SHA"
    fixed_hash "$BASELINE/inputs/openssl-3.5.9.tar.gz" "$SSL_ARCHIVE_SHA"
    fixed_hash "$BASELINE/inputs/openssh-10.6p1-bionic-includes.patch" "$ABI_PATCH_SHA"
    fixed_hash "$TASK_ROOT/research/openssh-10.6p1-bionic-includes.patch" "$ABI_PATCH_SHA"
    fixed_hash "$STREAM_PATCH" "$STREAM_PATCH_SHA"
    fixed_hash "$KEX_PATCH" "$KEX_PATCH_SHA"
    fixed_hash "$BANNER_PATCH" "$BANNER_PATCH_SHA"
    fixed_hash "$OPENSSH_BUILD/libssh.a" "$LIBSSH_SHA"
    fixed_hash "$OPENSSH_BUILD/openbsd-compat/libopenbsd-compat.a" "$OPENBSD_COMPAT_SHA"
    fixed_hash "$OPENSSL_PREFIX/lib/libcrypto.a" "$CRYPTO_SHA"
    fixed_hash "$OPENSSH_BUILD/ssh-pkcs11.o" "$SSH_PKCS11_SHA"
    fixed_hash "$OPENSSH_BUILD/ssh-sk-client.o" "$SSH_SK_CLIENT_SHA"
    grep -Fq 'OpenSSH_10.6' "$OPENSSH_SOURCE/version.h" \
        || die 'pinned OpenSSH source version mismatch'
    [[ -x "$NDK_BIN/aarch64-linux-android28-clang" \
        && -x "$NDK_BIN/llvm-ar" && -x "$NDK_BIN/llvm-ranlib" \
        && -x "$NDK_BIN/llvm-nm" ]] \
        || die 'fixed NDK compiler and archive tools are required'

    mkdir -p -- "$(dirname -- "$output_archive")"
    if [[ -e "$output_archive" ]]; then
        die "refusing to overwrite existing output: $output_archive"
    fi
    scratch_dir=$(mktemp -d /tmp/ctos-production-openssh.XXXXXXXX)
    mkdir -p -- "$scratch_dir/upstream" "$scratch_dir/kex" \
        "$scratch_dir/patched" "$scratch_dir/members" "$scratch_dir/pic-build" \
        "$(dirname -- "$output_archive")"
    baseline_manifest "$BASELINE" "$output_archive.evidence.baseline.sha256"
    fixed_hash "$output_archive.evidence.baseline.sha256" "$BASELINE_TREE_SHA"
    printf 'scope=normal-apk-production-openssh-library-closure\n' \
        >"$output_archive.evidence.scope"
    printf 'fixture_sources=excluded\nnormal_caller=ssh_native_client.c\n' \
        >>"$output_archive.evidence.scope"
    printf 'archive_is_not_runtime_completion=verified\npic_objects=source-recompiled\n' \
        >>"$output_archive.evidence.scope"

    cp -- "$OPENSSH_SOURCE/hostfile.c" "$scratch_dir/upstream/hostfile.c"
    cp -- "$OPENSSH_SOURCE/hostfile.h" "$scratch_dir/upstream/hostfile.h"
    patch --silent -d "$scratch_dir/upstream" -p0 <"$STREAM_PATCH"
    for source in kex.c kexgen.c kexgexc.c; do
        cp -- "$OPENSSH_SOURCE/$source" "$scratch_dir/kex/$source"
    done
    patch --silent -d "$scratch_dir/kex" -p1 <"$KEX_PATCH"
    cp -- "$OPENSSH_SOURCE/ssh_api.c" "$scratch_dir/upstream/ssh_api.c"
    patch --silent -d "$scratch_dir/upstream" -p1 <"$BANNER_PATCH"
    grep -Fq 'ctos_hostfile_write_entry' "$scratch_dir/upstream/hostfile.c" \
        || die 'stream writer patch did not apply'
    grep -Fq 'CTOS_SSH_KEX_GUARD' "$scratch_dir/kex/kex.c" \
        || die 'KEX guard patch did not apply'
    cp -- "$OPENSSH_BUILD/Makefile" "$scratch_dir/pic-build/Makefile"
    cp -- "$OPENSSH_BUILD/config.h" "$scratch_dir/pic-build/config.h"

    local cc="$NDK_BIN/aarch64-linux-android28-clang"
    local ar="$NDK_BIN/llvm-ar"
    local ranlib="$NDK_BIN/llvm-ranlib"
    local make_jobs=2
    local output_root
    output_root=$(dirname -- "$output_archive")
    local pic_cflags='-O2 -pipe -Wunknown-warning-option -Wno-error=format-truncation -Qunused-arguments -Wall -Wextra -Wpointer-arith -Wuninitialized -Wsign-compare -Wformat-security -Wsizeof-pointer-memaccess -Wno-pointer-sign -Wno-unused-parameter -Wno-unused-result -Wmisleading-indentation -Wbitwise-instead-of-logical -fno-strict-aliasing -ftrapv -fzero-call-used-regs=used -ftrivial-auto-var-init=zero -mretpoline -fno-builtin-memset -fstack-protector-strong -Wno-deprecated-declarations'
    local pic_cppflags="-I. -I$OPENSSH_SOURCE -I$OPENSSH_BUILD -I$OPENSSH_BUILD/openbsd-compat/include -I$OPENSSL_PREFIX/include -DCTOS_ANDROID_ABI_BASELINE_ONLY=1 -DHAVE_CONFIG_H"
    local -a common_flags=(
        -std=c11 -DCTOS_ANDROID_ABI_BASELINE_ONLY=1 -DHAVE_CONFIG_H
        -I"$scratch_dir/upstream" -I"$scratch_dir/kex" -I"$OPENSSH_BUILD"
        -I"$OPENSSH_SOURCE" -I"$OPENSSH_BUILD/openbsd-compat/include"
        -I"$OPENSSL_PREFIX/include" -fPIC -O2 -Wall -Wextra -Werror
        -Wno-pointer-sign -Wno-unused-parameter -Wno-unused-result
        -Wno-deprecated-declarations -Wno-misleading-indentation
    )
    run_stage compile-hostfile "$cc" "${common_flags[@]}" -c \
        "$scratch_dir/upstream/hostfile.c" -o "$scratch_dir/patched/hostfile.o"
    run_stage compile-ssh-api "$cc" "${common_flags[@]}" -c \
        "$scratch_dir/upstream/ssh_api.c" -o "$scratch_dir/patched/ssh_api.o"
    for source in kex.c kexgen.c kexgexc.c; do
        run_stage "compile-${source%.c}" "$cc" "${common_flags[@]}" \
            -DCTOS_SSH_KEX_GUARD=1 -I"$PROJECT_CPP" -c \
            "$scratch_dir/kex/$source" -o "$scratch_dir/patched/${source%.c}.o"
    done

    # OpenSSH's Makefile has a source-owned PIC target. Build that target in a
    # fresh directory with the fixed baseline configuration, then replace the
    # five objects whose sources carry the project patches. Reusing the G1
    # executable objects would make a shared JNI library fail with absolute
    # AArch64 relocations.
    run_stage archive-list "$ar" t "$OPENSSH_BUILD/libssh.a" \
        >"$scratch_dir/archive-members.txt"
    [[ -s "$scratch_dir/archive-members.txt" ]] \
        || die 'baseline libssh.a has no archive members'
    while IFS= read -r member; do
        [[ "$member" != */* && "$member" == *.o ]] \
            || die "unexpected non-object archive member: $member"
    done <"$scratch_dir/archive-members.txt"
    run_stage pic-compile timeout 600 make -C "$scratch_dir/pic-build" \
        -f Makefile libssh-pic.a ssh-pkcs11.lo ssh-sk-client.lo \
        CC="$cc" AR="$ar" RANLIB="$ranlib" \
        srcdir="$OPENSSH_SOURCE" top_srcdir="$OPENSSH_SOURCE" \
        abs_top_srcdir="$OPENSSH_SOURCE" \
        abs_top_builddir="$scratch_dir/pic-build" \
        BUILDDIR="$scratch_dir/pic-build" VPATH="$OPENSSH_SOURCE" \
        COMPATINCLUDES="$OPENSSH_BUILD/openbsd-compat/include" \
        CPPFLAGS="$pic_cppflags" CFLAGS_NOPIE="$pic_cflags" PICFLAG=-fPIC \
        -j"$make_jobs" >"$output_archive.evidence.pic-build.log" 2>&1
    run_stage pic-archive-list "$ar" t "$scratch_dir/pic-build/libssh-pic.a" \
        >"$scratch_dir/pic-archive-members.txt"
    sed 's/\.lo$/.o/' "$scratch_dir/pic-archive-members.txt" \
        | LC_ALL=C sort >"$scratch_dir/pic-archive-members.sorted.txt"
    LC_ALL=C sort "$scratch_dir/archive-members.txt" \
        >"$scratch_dir/archive-members.sorted.txt"
    diff -u "$scratch_dir/archive-members.sorted.txt" \
        "$scratch_dir/pic-archive-members.sorted.txt" \
        || die 'PIC source build changed the pinned libssh.a object set'
    # llvm-ar does not have one portable output-directory option across the
    # NDK/tool versions used by this project. Extract in a fresh directory
    # with the compatible `ar x archive` form, then normalize libtool .lo
    # member names to the archive's object names.
    # shellcheck disable=SC2016  # parameters are for the nested bash.
    run_stage archive-extract bash -c \
        'cd -- "$1" && "$2" x "$3"' _ "$scratch_dir/members" "$ar" \
        "$scratch_dir/pic-build/libssh-pic.a"
    while IFS= read -r member; do
        mv -- "$scratch_dir/members/$member" \
            "$scratch_dir/members/${member%.lo}.o"
    done <"$scratch_dir/pic-archive-members.txt"
    (
        cd -- "$scratch_dir/members"
        find . -maxdepth 1 -type f -name '*.o' -printf '%f\n' | LC_ALL=C sort
    ) >"$scratch_dir/extracted-members.txt"
    LC_ALL=C sort "$scratch_dir/archive-members.txt" \
        >"$scratch_dir/archive-members.sorted.txt"
    diff -u "$scratch_dir/archive-members.sorted.txt" \
        "$scratch_dir/extracted-members.txt" \
        || die 'PIC archive extraction did not reproduce the complete object set'
    for required_object in ssh-pkcs11.o ssh-sk-client.o; do
        ! grep -Fxq "$required_object" "$scratch_dir/archive-members.sorted.txt" \
            || die "baseline libssh.a unexpectedly contains explicit closure object: $required_object"
    done
    rm -f -- "$scratch_dir/members/hostfile.o" "$scratch_dir/members/kex.o" \
        "$scratch_dir/members/kexgen.o" "$scratch_dir/members/kexgexc.o"
    cp -- "$scratch_dir/patched/hostfile.o" "$scratch_dir/members/hostfile.o"
    cp -- "$scratch_dir/patched/kex.o" "$scratch_dir/members/kex.o"
    cp -- "$scratch_dir/patched/kexgen.o" "$scratch_dir/members/kexgen.o"
    cp -- "$scratch_dir/patched/kexgexc.o" "$scratch_dir/members/kexgexc.o"
    cp -- "$scratch_dir/patched/ssh_api.o" "$scratch_dir/members/ssh_api.o"
    cp -- "$scratch_dir/pic-build/ssh-pkcs11.lo" \
        "$scratch_dir/members/ssh-pkcs11.o"
    cp -- "$scratch_dir/pic-build/ssh-sk-client.lo" \
        "$scratch_dir/members/ssh-sk-client.o"
    {
        cat "$scratch_dir/archive-members.sorted.txt"
        printf '%s\n' ssh-pkcs11.o ssh-sk-client.o
    } | LC_ALL=C sort >"$output_archive.evidence.archive-members"
    cp -- "$scratch_dir/upstream/hostfile.c" "$output_archive.evidence.hostfile.c"
    cp -- "$scratch_dir/upstream/hostfile.h" "$output_archive.evidence.hostfile.h"
    cp -- "$scratch_dir/upstream/ssh_api.c" "$output_archive.evidence.ssh_api.c"
    mkdir -p -- "$output_root/include"
    cp -- "$scratch_dir/upstream/hostfile.h" "$output_root/include/hostfile.h"
    cmp -- "$scratch_dir/upstream/hostfile.h" "$output_root/include/hostfile.h" \
        || die 'staged production hostfile.h differs from patched source'
    printf 'staged_hostfile_header=%s\n' "$output_root/include/hostfile.h" \
        >>"$output_archive.evidence.scope"
    {
        printf '%s  source/hostfile.c\n' \
            "$(sha256_only "$scratch_dir/upstream/hostfile.c")"
        printf '%s  source/hostfile.h\n' \
            "$(sha256_only "$scratch_dir/upstream/hostfile.h")"
        printf '%s  source/ssh_api.c\n' \
            "$(sha256_only "$scratch_dir/upstream/ssh_api.c")"
        printf '%s  source/kex.c\n' "$(sha256_only "$scratch_dir/kex/kex.c")"
        printf '%s  source/kexgen.c\n' \
            "$(sha256_only "$scratch_dir/kex/kexgen.c")"
        printf '%s  source/kexgexc.c\n' \
            "$(sha256_only "$scratch_dir/kex/kexgexc.c")"
        printf 'source_patch_sha256 %s  %s\n' "$STREAM_PATCH_SHA" \
            "$STREAM_PATCH"
        printf 'source_patch_sha256 %s  %s\n' "$KEX_PATCH_SHA" "$KEX_PATCH"
        printf 'source_patch_sha256 %s  %s\n' "$BANNER_PATCH_SHA" "$BANNER_PATCH"
    } >"$output_archive.evidence.sources.sha256"
    "$ar" rcs "$output_archive" "$scratch_dir/members"/*.o
    "$ranlib" "$output_archive"
    {
        printf '%s  archive/libctos_ssh_production.a\n' \
            "$(sha256_only "$output_archive")"
        (
            cd -- "$scratch_dir/members"
            sha256sum -- *.o
        )
    } >"$output_archive.evidence.objects.sha256"
    {
        printf '%s\n' '# defined-global-symbols'
        "$NDK_BIN/llvm-nm" -g --defined-only "$output_archive"
        printf '%s\n' '# undefined-global-symbols'
        "$NDK_BIN/llvm-nm" -g --undefined-only "$output_archive"
    } >"$output_archive.evidence.symbols"
    if sed -n '/^# defined-global-symbols$/,/^# undefined-global-symbols$/p' \
        "$output_archive.evidence.symbols" |
        grep -Eq '[[:space:]](ssh_connect|ssh_login|ssh_userauth2|ssh_proxy_)'; then
        die 'production library closure contains a stock high-level caller'
    fi
    printf 'archive=%s\n' "$output_archive"
    printf 'SSH_PRODUCTION_OPENSSH_BUILD gate=pass fixture=excluded\n'
}

main "$@"
