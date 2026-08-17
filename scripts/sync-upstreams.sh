#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_NAME=$(basename "$0")
readonly SCRIPT_NAME
REPO_ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
readonly REPO_ROOT
readonly LOCK_FILE="$REPO_ROOT/third_party/upstreams.lock"
readonly UPSTREAM_ROOT="$REPO_ROOT/workbench/upstreams"
readonly STYLE_RESET=$'\033[0m'
readonly STYLE_TITLE=$'\033[1;36m'
readonly STYLE_SUCCESS=$'\033[0;32m'
readonly STYLE_ERROR=$'\033[1;31m'

selected_name=""
sync_all=false
sync_submodules=false

color_text() {
	local style=$1
	local value=$2

	if [[ -n "${NO_COLOR:-}" || ! -t 1 ]]; then
		printf '%s' "$value"
		return 0
	fi

	printf '%s%s%s' "$style" "$value" "$STYLE_RESET"
}

info() {
	printf '%s\n' "$(color_text "$STYLE_SUCCESS" "$*")"
}

die() {
	printf '%s\n' "$(color_text "$STYLE_ERROR" "Error: $*")" >&2
	exit 1
}

usage() {
	printf '%s\n' "$(color_text "$STYLE_TITLE" "Usage: $SCRIPT_NAME (--all | --name NAME) [--submodules]")" >&2
	printf '%s\n' 'Clone or update ignored upstream worktrees at locked revisions.' >&2
}

require_command() {
	command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

parse_args() {
	while [[ $# -gt 0 ]]; do
		case "$1" in
		--all)
			sync_all=true
			shift
			;;
		--name)
			[[ $# -ge 2 ]] || die "--name requires a value"
			selected_name=$2
			shift 2
			;;
		--submodules)
			sync_submodules=true
			shift
			;;
		--help | -h)
			usage
			exit 0
			;;
		*)
			die "unknown option: $1"
			;;
		esac
	done

	if [[ "$sync_all" == true && -n "$selected_name" ]]; then
		die "--all and --name are mutually exclusive"
	fi
	if [[ "$sync_all" == false && -z "$selected_name" ]]; then
		die "one of --all or --name is required"
	fi
}

sync_repository() {
	local name=$1
	local url=$2
	local revision=$3
	local directory=$4
	local destination="$UPSTREAM_ROOT/$directory"
	local actual_revision
	local remote_url

	[[ "$directory" =~ ^[a-zA-Z0-9._-]+$ ]] || die "unsafe upstream directory: $directory"

	if [[ ! -e "$destination" ]]; then
		git clone --filter=blob:none --no-checkout -- "$url" "$destination"
	fi

	[[ -d "$destination/.git" ]] || die "destination is not a Git worktree: $destination"
	remote_url=$(git -C "$destination" remote get-url origin)
	[[ "$remote_url" == "$url" ]] || die "origin mismatch for $name: $remote_url"
	[[ -z "$(git -C "$destination" status --porcelain)" ]] || die "upstream worktree is dirty: $destination"

	if ! git -C "$destination" cat-file -e "${revision}^{commit}" 2>/dev/null; then
		git -C "$destination" fetch --filter=blob:none origin "$revision"
	fi

	git -C "$destination" checkout --detach "$revision"
	actual_revision=$(git -C "$destination" rev-parse HEAD)
	[[ "$actual_revision" == "$revision" ]] || die "revision mismatch for $name"

	if [[ "$sync_submodules" == true ]]; then
		git -C "$destination" submodule update --init --recursive
	fi

	info "$name => $actual_revision"
}

sync_selected_repositories() {
	local directory
	local matched=false
	local name
	local revision
	local url

	while IFS=$'\t' read -r name url revision directory; do
		[[ "$name" == "name" ]] && continue
		if [[ "$sync_all" == false && "$name" != "$selected_name" ]]; then
			continue
		fi
		matched=true
		sync_repository "$name" "$url" "$revision" "$directory"
	done <"$LOCK_FILE"

	[[ "$matched" == true ]] || die "upstream not found in lock file: $selected_name"
}

main() {
	require_command git
	[[ -f "$LOCK_FILE" ]] || die "lock file not found: $LOCK_FILE"
	parse_args "$@"
	mkdir -p -- "$UPSTREAM_ROOT"
	sync_selected_repositories
}

main "$@"
