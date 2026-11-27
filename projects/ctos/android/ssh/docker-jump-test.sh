#!/usr/bin/env bash
set -Eeuo pipefail

readonly IMAGE="${CTOS_SSH_FIXTURE_IMAGE:-ctos-ssh-fixture:local}"
readonly QEMU='/usr/bin/qemu-aarch64'
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly SCRIPT_DIR

usage() {
	printf 'Usage: %s [--driver /absolute/path/to/arm64-driver]\n' "${0##*/}" >&2
	printf 'Run isolated OpenSSH ProxyJump checks using the existing local fixture image.\n' >&2
	printf 'Set CTOS_SSH_FIXTURE_IMAGE to select an equivalent pre-existing local image.\n' >&2
}

die() {
	printf 'Error: %s\n' "$*" >&2
	exit 1
}

main() {
	local driver=''
	case "${1:-}" in
	'') [[ $# -eq 0 ]] || die 'unexpected empty argument' ;;
	--help | -h)
		[[ $# -eq 1 ]] || die 'help takes no arguments'
		usage
		return 0
		;;
	--driver)
		[[ $# -eq 2 ]] || die '--driver requires exactly one path'
		driver=$2
		[[ "$driver" == /* && -f "$driver" && -x "$driver" ]] || die 'driver must be an absolute path to an executable regular file'
		[[ "$driver" != *,* ]] || die 'driver path cannot contain a comma'
		[[ -x "$QEMU" ]] || die "missing executable QEMU: $QEMU"
		;;
	*)
		usage
		return 2
		;;
	esac
	command -v docker >/dev/null 2>&1 || die 'docker is required'
	local image_id
	image_id=$(docker image inspect "$IMAGE" --format '{{.Id}}') || die "missing local image: $IMAGE"
	printf 'FIXTURE image=%s id=%s\n' "$IMAGE" "$image_id"
	[[ "$SCRIPT_DIR" != *,* ]] || die 'fixture path cannot contain a comma'
	local -a command=(
		docker run --rm --pull=never --network none
		--label ctos.scope=ssh-proxyjump-fixture
		--label "ctos.fixture.owner=$$"
		--cap-drop ALL
		--cap-add SETUID --cap-add SETGID --cap-add CHOWN --cap-add DAC_OVERRIDE
		--cap-add FOWNER --cap-add SYS_CHROOT --cap-add KILL --cap-add AUDIT_WRITE
		--security-opt no-new-privileges --pids-limit 128 --memory 512m --ulimit core=0:0
		--tmpfs '/tmp:rw,nosuid,nodev,mode=1777' --tmpfs '/run:rw,nosuid,nodev,mode=755'
		--mount "type=bind,src=$SCRIPT_DIR/openssh-jump-fixture.sh,dst=/test/run.sh,readonly"
	)
	if [[ -n "$driver" ]]; then
		command+=(
			--mount "type=bind,src=$QEMU,dst=/test/qemu,readonly"
			--mount "type=bind,src=$driver,dst=/test/driver,readonly"
		)
	fi
	command+=(--entrypoint /bin/sh "$IMAGE" /test/run.sh)
	if [[ -n "$driver" ]]; then command+=(--driver); fi
	"${command[@]}"
}

main "$@"
