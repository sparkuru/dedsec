#!/usr/bin/env bash
set -Eeuo pipefail

readonly OUTPUT=/out
readonly CPP="$OUTPUT/cpp"
readonly SOURCE="$OUTPUT/openssh-10.6p1"

run_logged() {
	local log=$1
	shift
	if ! "$@" >"$log" 2>&1; then
		tail -n 60 -- "$log" >&2
		printf 'Error: command failed: %s\n' "$1" >&2
		return 1
	fi
}

main() {
	[[ -f /.dockerenv && -f "$OUTPUT/inputs/openssh-10.6p1.tar.gz" ]] || {
		printf 'Error: run only in the prepared offline build container\n' >&2
		return 1
	}
	tar -xzf "$OUTPUT/inputs/openssh-10.6p1.tar.gz" -C "$OUTPUT"
	patch --batch --fuzz=0 --silent -d "$SOURCE" -p0 <"$OUTPUT/inputs/hostfile.patch"
	patch --batch --fuzz=0 --silent -d "$SOURCE" -p1 <"$OUTPUT/inputs/kex.patch"
	patch --batch --fuzz=0 --silent -d "$SOURCE" -p1 <"$OUTPUT/inputs/banner.patch"
	patch --batch --fuzz=0 --silent -d "$SOURCE" -p1 <"$OUTPUT/inputs/provisional.patch"
	(
		cd -- "$SOURCE"
		run_logged "$OUTPUT/configure.log" env CPPFLAGS="-I$CPP -DCTOS_SSH_KEX_GUARD=1" \
			CFLAGS='-O2 -fPIC' ./configure --without-pam
		run_logged "$OUTPUT/make.log" make -j2 CFLAGS='-O2 -fPIC' \
			libssh.a openbsd-compat/libopenbsd-compat.a ssh-pkcs11.o ssh-sk-client.o
	)
	cp -- "$SOURCE/readconf.c" "$OUTPUT/readconf.c"
	patch --batch --fuzz=0 --silent -d "$OUTPUT" -p1 <"$OUTPUT/inputs/config.patch"
	local unit
	local -a sources=()
	for unit in ssh_file_view ssh_view_handle ssh_file_io ssh_file_jni ssh_role_bootstrap ssh_role_jni \
		ssh_callback_adapter ssh_callback_jni ssh_session_jni ssh_config_handle ssh_config_jni ssh_config_eval \
		ssh_hostfile_issuer ssh_hostfile_changed ssh_kex_guard ssh_native_client ssh_key_policy \
		ssh_material_inspect ssh_material_jni ssh_host_trust ssh_host_trust_jni; do
		sources+=("$CPP/$unit.c")
	done
	run_logged "$OUTPUT/cc.log" cc -std=c11 -DCTOS_ANDROID_ABI_BASELINE_ONLY=1 -DHAVE_CONFIG_H \
		-I"$CPP" -isystem "$SOURCE" -isystem "$SOURCE/openbsd-compat/include" -idirafter "$OUTPUT/inputs" \
		-O2 -Wall -Wextra -Werror -Wno-format-truncation -Wno-pointer-sign -Wno-unused-parameter \
		-Wno-unused-result -Wno-deprecated-declarations -Wno-misleading-indentation -fPIC -shared -Wl,--no-undefined \
		"${sources[@]}" "$OUTPUT/readconf.c" -Wl,--start-group "$SOURCE/libssh.a" \
		"$SOURCE/openbsd-compat/libopenbsd-compat.a" "$SOURCE/ssh-pkcs11.o" "$SOURCE/ssh-sk-client.o" \
		-Wl,--end-group -lcrypto -lz -ldl -lpthread -lutil -o "$OUTPUT/libctos_ssh_files.so"
	run_logged "$OUTPUT/cc-host-compat.log" cc -std=c11 -Wall -Wextra -Werror -O2 -fPIC -shared \
		-Wl,--no-undefined -idirafter "$OUTPUT/inputs" "$OUTPUT/inputs/ssh_product_host_compat.c" \
		-o "$OUTPUT/libctos_host_compat.so"
	sha256sum -- "$OUTPUT/libctos_ssh_files.so" "$OUTPUT/libctos_host_compat.so" \
		"$SOURCE/libssh.a" "$SOURCE/openbsd-compat/libopenbsd-compat.a" >"$OUTPUT/native-binaries.sha256"
	printf 'PRODUCT_HOST_NATIVE build=pass test-hooks=disabled\n'
}

main "$@"
