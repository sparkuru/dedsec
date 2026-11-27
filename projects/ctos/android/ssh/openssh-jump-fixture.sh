#!/bin/sh
set -eu

readonly FIXTURE_DIR=/tmp/ctos-jump
sshd_jump_pid=''
sshd_target_pid=''

die() {
	printf 'Error: %s\n' "$*" >&2
	exit 1
}

cleanup() {
	status=$?
	if [ "$status" -ne 0 ]; then
		for log in "$FIXTURE_DIR/jump.log" "$FIXTURE_DIR/target.log" "$FIXTURE_DIR/negative.log" \
			"$FIXTURE_DIR/product-profiles.log" "$FIXTURE_DIR/product-gate.log" "$FIXTURE_DIR/product-direct.log" \
			"$FIXTURE_DIR/product-direct-none.log" "$FIXTURE_DIR/product-multi-jump.log" \
			"$FIXTURE_DIR/product-jump.log" "$FIXTURE_DIR/product-jump-changed.log" "$FIXTURE_DIR/product-jump-unknown.log" \
			"$FIXTURE_DIR/product-jump-unknown-root.log" \
			"$FIXTURE_DIR/product-jump-cancel.log"; do
			if [ -f "$log" ]; then
				printf 'FIXTURE failure-log=%s\n' "${log##*/}" >&2
				tail -n 24 "$log" >&2
			fi
		done
	fi
	if [ -n "$sshd_jump_pid" ]; then
		kill "$sshd_jump_pid" 2>/dev/null || :
		wait "$sshd_jump_pid" 2>/dev/null || :
	fi
	if [ -n "$sshd_target_pid" ]; then
		kill "$sshd_target_pid" 2>/dev/null || :
		wait "$sshd_target_pid" 2>/dev/null || :
	fi
}

write_server_config() {
	name=$1
	port=$2
	forwarding=$3
	fixture_account=$4
	cat >"$FIXTURE_DIR/$name.config" <<CONFIG
Port $port
ListenAddress 127.0.0.1
HostKey $FIXTURE_DIR/${name}_host
PidFile /run/sshd-$name.pid
UsePAM yes
KbdInteractiveAuthentication no
PasswordAuthentication yes
PubkeyAuthentication yes
AuthorizedKeysFile .ssh/authorized_keys
PermitRootLogin no
AllowUsers $fixture_account
AllowTcpForwarding $forwarding
PermitOpen 127.0.0.1:22222
UseDNS no
PrintMotd no
LogLevel DEBUG1
CONFIG
}

write_client_config() {
	config=$1
	known_hosts=$2
	cat >"$config" <<CONFIG
Host jump
    HostName 127.0.0.1
    Port 22221
    User ctosjump
    HostKeyAlias jump.fixture
    IdentityFile $FIXTURE_DIR/jump_client
Host target
    HostName 127.0.0.1
    Port 22222
    User ctostarget
    HostKeyAlias target.fixture
    IdentityFile $FIXTURE_DIR/target_client
    ProxyJump jump
Host *
    IdentitiesOnly yes
    BatchMode yes
    StrictHostKeyChecking yes
    UserKnownHostsFile $known_hosts
    GlobalKnownHostsFile /dev/null
    ConnectTimeout 10
    LogLevel ERROR
CONFIG
}

wait_for_server() {
	name=$1
	port=$2
	pid=$3
	attempt=0
	while [ "$attempt" -lt 10 ]; do
		if grep -q "Server listening on 127.0.0.1 port $port" "$FIXTURE_DIR/$name.log"; then return 0; fi
		kill -0 "$pid" 2>/dev/null || die "$name sshd exited"
		attempt=$((attempt + 1))
		sleep 1
	done
	die "$name sshd did not start"
}

run_product_jump_case() {
	product_case=$1
	product_log="$FIXTURE_DIR/product-$product_case.log"
	if [ "$product_case" = jump-changed ]; then
		timeout 180 /test/jdk/bin/java -Xcheck:jni -Djava.library.path=/test/native \
			-cp /test/classes:/test/factory:/test/release:/test/bcprov.jar:/test/android.jar:/test/flutter.jar \
			im.majo.ctos.SshProductDockerTest "$product_case" "$FIXTURE_DIR/jump_host.pub" \
			"$FIXTURE_DIR/target_host.pub" "$FIXTURE_DIR/rogue_host.pub" <"$FIXTURE_DIR/passwords" >"$product_log" 2>&1
	else
		timeout 180 /test/jdk/bin/java -Xcheck:jni -Djava.library.path=/test/native \
			-cp /test/classes:/test/factory:/test/release:/test/bcprov.jar:/test/android.jar:/test/flutter.jar \
			im.majo.ctos.SshProductDockerTest "$product_case" "$FIXTURE_DIR/jump_host.pub" \
			"$FIXTURE_DIR/target_host.pub" <"$FIXTURE_DIR/passwords" >"$product_log" 2>&1
	fi
}

main() {
	mode=${1:-stock}
	[ "$#" -le 1 ] || die 'expected no arguments, --driver, --product-java, or --product-open'
	case "$mode" in
	stock) ;;
	--driver)
		[ -x /test/qemu ] || die 'mount QEMU read-only'
		[ -x /test/driver ] || die 'mount the driver read-only'
		;;
	--product-java | --product-open)
		[ -x /test/jdk/bin/java ] || die 'mount the Java runtime read-only'
		[ -f /test/native/libctos_ssh_files.so ] || die 'mount the product JNI library read-only'
		[ -f /test/native/libctos_host_compat.so ] || die 'mount the host compatibility library read-only'
		if [ ! -d /test/classes ] || [ ! -d /test/factory ] || [ ! -d /test/release ]; then
			die 'mount compiled classes read-only'
		fi
		[ -f /test/bcprov.jar ] || die 'mount BouncyCastle read-only'
		[ -f /test/android.jar ] || die 'mount Android API signatures read-only'
		[ -f /test/flutter.jar ] || die 'mount Flutter embedding signatures read-only'
		;;
	*) die 'expected no arguments, --driver, --product-java, or --product-open' ;;
	esac
	[ -f /.dockerenv ] || die 'run only inside a disposable Docker container'
	[ "$(id -u)" -eq 0 ] || die 'the isolated sshd fixture requires container root'
	for interface in /sys/class/net/*; do
		[ "$interface" = /sys/class/net/lo ] || die 'the fixture requires a network-none container'
	done
	for tool in awk cat chmod chown chpasswd cmp getent grep id mkdir openssl sleep ssh ssh-keygen tail timeout tr useradd wc; do
		command -v "$tool" >/dev/null 2>&1 || die "required command not found: $tool"
	done
	[ -x /usr/sbin/sshd ] || die 'missing sshd'
	for fixture_account in ctosjump ctostarget; do
		if getent passwd "$fixture_account" >/dev/null; then die 'fixture account already exists'; fi
	done
	trap cleanup EXIT
	trap 'exit 1' HUP INT TERM
	export LANG=C
	umask 077
	mkdir -p "$FIXTURE_DIR" /run/sshd
	for fixture_account in ctosjump ctostarget; do
		useradd --create-home --shell /bin/sh "$fixture_account"
		openssl rand -hex 24 | tr -d '\n' >"$FIXTURE_DIR/$fixture_account.password"
		[ "$(wc -c <"$FIXTURE_DIR/$fixture_account.password")" -eq 48 ] || die 'could not generate a test password'
		printf '%s:%s\n' "$fixture_account" "$(cat "$FIXTURE_DIR/$fixture_account.password")" | chpasswd
	done
	if cmp -s "$FIXTURE_DIR/ctosjump.password" "$FIXTURE_DIR/ctostarget.password"; then
		die 'jump and target passwords must differ'
	fi
	cat "$FIXTURE_DIR/ctosjump.password" "$FIXTURE_DIR/ctostarget.password" >"$FIXTURE_DIR/passwords"
	[ "$(wc -c <"$FIXTURE_DIR/passwords")" -eq 96 ] || die 'invalid combined password input'
	for name in jump target rogue; do
		ssh-keygen -q -t ed25519 -N '' -f "$FIXTURE_DIR/${name}_host"
	done
	for name in jump target; do
		fixture_account="ctos$name"
		ssh-keygen -q -t ed25519 -N '' -f "$FIXTURE_DIR/${name}_client"
		mkdir -p "/home/$fixture_account/.ssh"
		cat "$FIXTURE_DIR/${name}_client.pub" >"/home/$fixture_account/.ssh/authorized_keys"
		chown -R "$fixture_account:$fixture_account" "/home/$fixture_account/.ssh"
		chmod 700 "/home/$fixture_account/.ssh"
		chmod 600 "/home/$fixture_account/.ssh/authorized_keys"
	done
	awk '{print $1, $2}' "$FIXTURE_DIR/jump_host.pub" >"$FIXTURE_DIR/jump.public"
	awk '{print $1, $2}' "$FIXTURE_DIR/target_host.pub" >"$FIXTURE_DIR/target.public"
	if cmp -s "$FIXTURE_DIR/jump.public" "$FIXTURE_DIR/target.public"; then die 'host keys must differ'; fi
	awk '{print "jump.fixture", $1, $2}' "$FIXTURE_DIR/jump_host.pub" >"$FIXTURE_DIR/known_hosts"
	awk '{print "target.fixture", $1, $2}' "$FIXTURE_DIR/target_host.pub" >>"$FIXTURE_DIR/known_hosts"
	awk '{print "jump.fixture", $1, $2}' "$FIXTURE_DIR/jump_host.pub" >"$FIXTURE_DIR/wrong_hosts"
	awk '{print "target.fixture", $1, $2}' "$FIXTURE_DIR/rogue_host.pub" >>"$FIXTURE_DIR/wrong_hosts"
	write_server_config jump 22221 local ctosjump
	write_server_config target 22222 no ctostarget
	write_client_config "$FIXTURE_DIR/client.config" "$FIXTURE_DIR/known_hosts"
	write_client_config "$FIXTURE_DIR/wrong.config" "$FIXTURE_DIR/wrong_hosts"
	/usr/sbin/sshd -t -f "$FIXTURE_DIR/jump.config"
	/usr/sbin/sshd -t -f "$FIXTURE_DIR/target.config"
	/usr/sbin/sshd -D -e -f "$FIXTURE_DIR/jump.config" >"$FIXTURE_DIR/jump.log" 2>&1 &
	sshd_jump_pid=$!
	/usr/sbin/sshd -D -e -f "$FIXTURE_DIR/target.config" >"$FIXTURE_DIR/target.log" 2>&1 &
	sshd_target_pid=$!
	wait_for_server jump 22221 "$sshd_jump_pid"
	wait_for_server target 22222 "$sshd_target_pid"
	result=$(timeout 60 ssh -F "$FIXTURE_DIR/client.config" target 'printf "%s\n" CTOS_JUMP_TARGET_OK')
	[ "$result" = CTOS_JUMP_TARGET_OK ] || die 'target command returned unexpected output'
	grep -q 'ctype direct-tcpip' "$FIXTURE_DIR/jump.log" || die 'jump server did not report a direct-tcpip channel'
	grep -q 'Accepted publickey for ctosjump' "$FIXTURE_DIR/jump.log" || die 'jump authentication was not observed'
	grep -q 'Accepted publickey for ctostarget' "$FIXTURE_DIR/target.log" || die 'target authentication was not observed'
	printf 'FIXTURE stock-proxyjump=pass jump-forward=direct-tcpip distinct-host-keys=pass\n'
	negative_status=0
	timeout 60 ssh -F "$FIXTURE_DIR/wrong.config" target 'printf "%s\n" SHOULD_NOT_EXECUTE' \
		>"$FIXTURE_DIR/negative.out" 2>"$FIXTURE_DIR/negative.log" || negative_status=$?
	[ "$negative_status" -eq 255 ] || die "wrong-host-key ssh returned unexpected status: $negative_status"
	[ ! -s "$FIXTURE_DIR/negative.out" ] || die 'wrong-host-key test produced target output'
	grep -q 'Host key verification failed' "$FIXTURE_DIR/negative.log" || die 'negative test failed for an unexpected reason'
	printf 'FIXTURE wrong-target-host-key=rejected\n'
	if [ "$mode" = --driver ]; then
		jump_start=$(($(wc -l <"$FIXTURE_DIR/jump.log") + 1))
		target_start=$(($(wc -l <"$FIXTURE_DIR/target.log") + 1))
		timeout 300 /test/qemu /test/driver 127.0.0.1 22221 22222 ctosjump ctostarget \
			"$FIXTURE_DIR/jump_host.pub" "$FIXTURE_DIR/target_host.pub" <"$FIXTURE_DIR/passwords"
		tail -n "+$jump_start" "$FIXTURE_DIR/jump.log" | grep -q 'ctype direct-tcpip' ||
			die 'driver did not open a new direct-tcpip channel'
		tail -n "+$jump_start" "$FIXTURE_DIR/jump.log" | grep -q 'Accepted password for ctosjump' ||
			die 'driver jump password authentication was not observed'
		tail -n "+$jump_start" "$FIXTURE_DIR/jump.log" | grep -q 'userauth-request for user ctosjump service ssh-connection method password' ||
			die 'driver jump password request was not logged'
		tail -n "+$target_start" "$FIXTURE_DIR/target.log" | grep -q 'Accepted password for ctostarget' ||
			die 'driver target password authentication was not observed'
		tail -n "+$target_start" "$FIXTURE_DIR/target.log" | grep -q 'userauth-request for user ctostarget service ssh-connection method password' ||
			die 'driver target password request was not logged'
		printf 'FIXTURE external-driver=pass new-jump-forward=direct-tcpip distinct-credentials=pass\n'
		jump_start=$(($(wc -l <"$FIXTURE_DIR/jump.log") + 1))
		target_start=$(($(wc -l <"$FIXTURE_DIR/target.log") + 1))
		driver_status=0
		timeout 300 /test/qemu /test/driver 127.0.0.1 22221 22222 ctosjump ctostarget \
			"$FIXTURE_DIR/jump_host.pub" "$FIXTURE_DIR/rogue_host.pub" \
			<"$FIXTURE_DIR/passwords" >"$FIXTURE_DIR/driver-negative.log" 2>&1 || driver_status=$?
		cat "$FIXTURE_DIR/driver-negative.log"
		[ "$driver_status" -eq 1 ] || die "wrong-host-key driver returned unexpected status: $driver_status"
		grep -q 'SSH_JUMP_INTEROP phase=target-auth status=fail' "$FIXTURE_DIR/driver-negative.log" ||
			die 'wrong-host-key driver did not fail during target authentication'
		grep -Fxq 'SSH_JUMP_INTEROP target-host-key=changed credential-release=none' "$FIXTURE_DIR/driver-negative.log" ||
			die 'driver did not prove the changed key was rejected before credential release'
		if grep -q 'SSH_JUMP_INTEROP cleanup=failed' "$FIXTURE_DIR/driver-negative.log"; then
			die 'wrong-host-key driver failed to clean up'
		fi
		if grep -q 'SSH_JUMP_INTEROP phase=target-pty-command status=pass' "$FIXTURE_DIR/driver-negative.log"; then
			die 'wrong-host-key driver reported a target command'
		fi
		tail -n "+$jump_start" "$FIXTURE_DIR/jump.log" | grep -q 'ctype direct-tcpip' ||
			die 'wrong-host-key driver did not open a new direct-tcpip channel'
		tail -n "+$jump_start" "$FIXTURE_DIR/jump.log" | grep -q 'Accepted password for ctosjump' ||
			die 'wrong-host-key driver did not authenticate the jump account'
		tail -n "+$jump_start" "$FIXTURE_DIR/jump.log" | grep -q 'userauth-request for user ctosjump service ssh-connection method password' ||
			die 'wrong-host-key driver did not request the jump password'
		if tail -n "+$target_start" "$FIXTURE_DIR/target.log" |
			grep -Eq 'userauth-request.*method password|Accepted password|Starting session'; then
			die 'wrong-host-key driver reached a target password request, authentication, or session'
		fi
		printf 'FIXTURE driver-wrong-target-host-key=rejected target-password-and-session=absent target-password-request=absent\n'
		jump_start=$(($(wc -l <"$FIXTURE_DIR/jump.log") + 1))
		target_start=$(($(wc -l <"$FIXTURE_DIR/target.log") + 1))
		timeout 300 /test/qemu /test/driver 127.0.0.1 22221 22222 ctosjump ctostarget \
			"$FIXTURE_DIR/jump_host.pub" "$FIXTURE_DIR/target_host.pub" --stale-reject \
			<"$FIXTURE_DIR/passwords" >"$FIXTURE_DIR/driver-stale.log" 2>&1
		cat "$FIXTURE_DIR/driver-stale.log"
		grep -Fxq 'SSH_JUMP_INTEROP stale-target-host=unknown credential-release=none' \
			"$FIXTURE_DIR/driver-stale.log" || die 'old view did not classify the target as unknown'
		tail -n "+$jump_start" "$FIXTURE_DIR/jump.log" | grep -q 'ctype direct-tcpip' ||
			die 'stale-view driver did not open a new direct-tcpip channel'
		if tail -n "+$target_start" "$FIXTURE_DIR/target.log" |
			grep -Eq 'userauth-request.*method password|Accepted password|Starting session'; then
			die 'stale-view driver reached target password authentication or a session'
		fi
		jump_start=$(($(wc -l <"$FIXTURE_DIR/jump.log") + 1))
		target_start=$(($(wc -l <"$FIXTURE_DIR/target.log") + 1))
		timeout 300 /test/qemu /test/driver 127.0.0.1 22221 22222 ctosjump ctostarget \
			"$FIXTURE_DIR/jump_host.pub" "$FIXTURE_DIR/target_host.pub" --fresh-known \
			<"$FIXTURE_DIR/passwords" >"$FIXTURE_DIR/driver-fresh.log" 2>&1
		cat "$FIXTURE_DIR/driver-fresh.log"
		grep -Fxq 'SSH_JUMP_INTEROP fresh-target-host=known previous-view=unknown' \
			"$FIXTURE_DIR/driver-fresh.log" || die 'fresh view did not classify the target as known'
		tail -n "+$jump_start" "$FIXTURE_DIR/jump.log" | grep -q 'ctype direct-tcpip' ||
			die 'fresh-view driver did not open a new direct-tcpip channel'
		tail -n "+$target_start" "$FIXTURE_DIR/target.log" | grep -q 'Accepted password for ctostarget' ||
			die 'fresh-view driver did not authenticate the target account'
		target_start=$(($(wc -l <"$FIXTURE_DIR/target.log") + 1))
		driver_status=0
		timeout 300 /test/qemu /test/driver 127.0.0.1 22221 22222 ctosjump ctostarget \
			"$FIXTURE_DIR/jump_host.pub" "$FIXTURE_DIR/rogue_host.pub" --fresh-known \
			<"$FIXTURE_DIR/passwords" >"$FIXTURE_DIR/driver-fresh-negative.log" 2>&1 || driver_status=$?
		cat "$FIXTURE_DIR/driver-fresh-negative.log"
		[ "$driver_status" -eq 1 ] || die "wrong fresh host key returned unexpected status: $driver_status"
		grep -Fxq 'SSH_JUMP_INTEROP target-host-key=changed credential-release=none' \
			"$FIXTURE_DIR/driver-fresh-negative.log" || die 'fresh-view wrong key was not rejected before credentials'
		if grep -q 'SSH_JUMP_INTEROP cleanup=failed' "$FIXTURE_DIR/driver-fresh-negative.log"; then
			die 'wrong fresh host key failed to clean up'
		fi
		if tail -n "+$target_start" "$FIXTURE_DIR/target.log" |
			grep -Eq 'userauth-request.*method password|Accepted password|Starting session'; then
			die 'wrong fresh host key reached target password authentication or a session'
		fi
		printf 'FIXTURE stale-unknown=rejected fresh-known=accepted fresh-wrong-key=rejected target-password-before-trust=absent\n'
	fi
	if [ "$mode" = --product-java ] || [ "$mode" = --product-open ]; then
		timeout 180 /test/jdk/bin/java -Xcheck:jni -Djava.library.path=/test/native \
			-cp /test/classes:/test/factory:/test/release:/test/bcprov.jar:/test/android.jar:/test/flutter.jar \
			im.majo.ctos.SshProductProfilesRouteTest >"$FIXTURE_DIR/product-profiles.log" 2>&1
		grep -Fxq 'PRODUCT_PROFILES direct=pass none=pass single=pass multi=unsupported bad-hop=rejected sockets=0' \
			"$FIXTURE_DIR/product-profiles.log" || die 'product profile route results missing'
		if grep -Eqi 'WARNING in native method|WARNING.*JNI|FATAL ERROR in native method|JNI DETECTED ERROR' \
			"$FIXTURE_DIR/product-profiles.log"; then die 'product profile JNI checks reported a warning or error'; fi
		cat "$FIXTURE_DIR/product-profiles.log"
		if [ "$mode" = --product-java ]; then
			jump_start=$(($(wc -l <"$FIXTURE_DIR/jump.log") + 1))
			target_start=$(($(wc -l <"$FIXTURE_DIR/target.log") + 1))
			timeout 180 /test/jdk/bin/java -Xcheck:jni -Djava.library.path=/test/native \
				-cp /test/classes:/test/factory:/test/release:/test/bcprov.jar \
				im.majo.ctos.SshProductDockerTest gate "$FIXTURE_DIR/jump_host.pub" "$FIXTURE_DIR/target_host.pub" \
				<"$FIXTURE_DIR/passwords" >"$FIXTURE_DIR/product-gate.log" 2>&1
			grep -Fxq 'PRODUCT_DOCKER mode=gate outcome=jump-gate-rejected-before-socket cleanup=pass commits=0 peer=real-sshd platform=host-jni' \
				"$FIXTURE_DIR/product-gate.log" || die 'product jump gate result missing'
			if tail -n "+$jump_start" "$FIXTURE_DIR/jump.log" | grep -Eq 'Connection from|userauth-request|Accepted '; then
				die 'closed product jump gate reached the jump server'
			fi
			if tail -n "+$target_start" "$FIXTURE_DIR/target.log" | grep -Eq 'Connection from|userauth-request|Accepted '; then
				die 'closed product jump gate reached the target server'
			fi
		fi
		jump_start=$(($(wc -l <"$FIXTURE_DIR/jump.log") + 1))
		target_start=$(($(wc -l <"$FIXTURE_DIR/target.log") + 1))
		timeout 180 /test/jdk/bin/java -Xcheck:jni -Djava.library.path=/test/native \
			-cp /test/classes:/test/factory:/test/release:/test/bcprov.jar \
			im.majo.ctos.SshProductDockerTest direct "$FIXTURE_DIR/jump_host.pub" "$FIXTURE_DIR/target_host.pub" \
			<"$FIXTURE_DIR/passwords" >"$FIXTURE_DIR/product-direct.log" 2>&1
		grep -Fxq 'PRODUCT_DOCKER mode=direct outcome=direct-target-command cleanup=pass commits=0 peer=real-sshd platform=host-jni' \
			"$FIXTURE_DIR/product-direct.log" || die 'product direct command result missing'
		tail -n "+$target_start" "$FIXTURE_DIR/target.log" | grep -q 'Accepted password for ctostarget' ||
			die 'product direct target password was not accepted'
		tail -n "+$target_start" "$FIXTURE_DIR/target.log" | grep -q 'Starting session' ||
			die 'product direct target session was not observed'
		if tail -n "+$jump_start" "$FIXTURE_DIR/jump.log" | grep -Eq 'Connection from|userauth-request|Accepted '; then
			die 'direct test unexpectedly contacted the jump server'
		fi
		jump_start=$(($(wc -l <"$FIXTURE_DIR/jump.log") + 1))
		target_start=$(($(wc -l <"$FIXTURE_DIR/target.log") + 1))
		timeout 180 /test/jdk/bin/java -Xcheck:jni -Djava.library.path=/test/native \
			-cp /test/classes:/test/factory:/test/release:/test/bcprov.jar:/test/android.jar:/test/flutter.jar \
			im.majo.ctos.SshProductDockerTest direct-none "$FIXTURE_DIR/jump_host.pub" "$FIXTURE_DIR/target_host.pub" \
			<"$FIXTURE_DIR/passwords" >"$FIXTURE_DIR/product-direct-none.log" 2>&1
		grep -Fxq 'PRODUCT_DOCKER list=production-catalog-plan-row bridge-lifecycle=not-run' \
			"$FIXTURE_DIR/product-direct-none.log" || die 'ProxyJump none target row result missing'
		grep -Fxq 'PRODUCT_DOCKER mode=direct-none outcome=direct-target-command cleanup=pass commits=0 peer=real-sshd platform=host-jni' \
			"$FIXTURE_DIR/product-direct-none.log" || die 'ProxyJump none product command result missing'
		tail -n "+$target_start" "$FIXTURE_DIR/target.log" | grep -q 'Accepted password for ctostarget' ||
			die 'ProxyJump none target password was not accepted'
		tail -n "+$target_start" "$FIXTURE_DIR/target.log" | grep -q 'Starting session' ||
			die 'ProxyJump none target session was not observed'
		if tail -n "+$jump_start" "$FIXTURE_DIR/jump.log" | grep -Eq 'Connection from|userauth-request|Accepted '; then
			die 'ProxyJump none unexpectedly contacted the jump server'
		fi
		if [ "$mode" = --product-open ]; then
			jump_start=$(($(wc -l <"$FIXTURE_DIR/jump.log") + 1))
			target_start=$(($(wc -l <"$FIXTURE_DIR/target.log") + 1))
			run_product_jump_case multi-jump
			grep -Fxq 'PRODUCT_DOCKER mode=multi-jump outcome=multi-rejected-before-socket cleanup=pass commits=0 peer=real-sshd platform=host-jni' \
				"$FIXTURE_DIR/product-multi-jump.log" || die 'multi-jump rejection result missing'
			if tail -n "+$jump_start" "$FIXTURE_DIR/jump.log" | grep -Eq 'Connection from|userauth-request|Accepted '; then
				die 'multi-jump rejection reached the jump server'
			fi
			if tail -n "+$target_start" "$FIXTURE_DIR/target.log" | grep -Eq 'Connection from|userauth-request|Accepted '; then
				die 'multi-jump rejection reached the target server'
			fi
			jump_start=$(($(wc -l <"$FIXTURE_DIR/jump.log") + 1))
			target_start=$(($(wc -l <"$FIXTURE_DIR/target.log") + 1))
			run_product_jump_case jump
			grep -Fxq 'PRODUCT_DOCKER mode=jump outcome=jump-target-pty-command cleanup=pass commits=0 peer=real-sshd platform=host-jni' \
				"$FIXTURE_DIR/product-jump.log" || die 'single-jump target PTY result missing'
			tail -n "+$jump_start" "$FIXTURE_DIR/jump.log" | grep -q 'Accepted password for ctosjump' ||
				die 'single-jump password was not accepted'
			tail -n "+$jump_start" "$FIXTURE_DIR/jump.log" | grep -q 'ctype direct-tcpip' ||
				die 'single-jump did not open direct-tcpip'
			tail -n "+$target_start" "$FIXTURE_DIR/target.log" | grep -q 'Accepted password for ctostarget' ||
				die 'single-jump target password was not accepted'
			tail -n "+$target_start" "$FIXTURE_DIR/target.log" | grep -q 'Starting session: shell on ' ||
				die 'single-jump target PTY shell was not observed'
			jump_start=$(($(wc -l <"$FIXTURE_DIR/jump.log") + 1))
			target_start=$(($(wc -l <"$FIXTURE_DIR/target.log") + 1))
			run_product_jump_case jump-changed
			grep -Fxq 'PRODUCT_DOCKER mode=jump-changed outcome=changed-before-target-auth cleanup=pass commits=0 peer=real-sshd platform=host-jni' \
				"$FIXTURE_DIR/product-jump-changed.log" || die 'changed target rejection result missing'
			tail -n "+$jump_start" "$FIXTURE_DIR/jump.log" | grep -q 'Accepted password for ctosjump' ||
				die 'changed target case did not authenticate the jump'
			tail -n "+$jump_start" "$FIXTURE_DIR/jump.log" | grep -q 'ctype direct-tcpip' ||
				die 'changed target case did not open direct-tcpip'
			tail -n "+$target_start" "$FIXTURE_DIR/target.log" | grep -q 'Remote protocol version' ||
				die 'changed target case did not reach the target SSH server'
			if tail -n "+$target_start" "$FIXTURE_DIR/target.log" | grep -Eq 'userauth-request|Accepted |Starting session'; then
				die 'changed target case reached target authentication or a session'
			fi
			jump_start=$(($(wc -l <"$FIXTURE_DIR/jump.log") + 1))
			target_start=$(($(wc -l <"$FIXTURE_DIR/target.log") + 1))
			run_product_jump_case jump-unknown
			grep -Fxq 'PRODUCT_DOCKER mode=jump-unknown outcome=unknown-before-target-auth cleanup=pass commits=0 peer=real-sshd platform=host-jni' \
				"$FIXTURE_DIR/product-jump-unknown.log" || die 'unknown target rejection result missing'
			tail -n "+$jump_start" "$FIXTURE_DIR/jump.log" | grep -q 'Accepted password for ctosjump' ||
				die 'unknown target case did not authenticate the jump'
			tail -n "+$jump_start" "$FIXTURE_DIR/jump.log" | grep -q 'ctype direct-tcpip' ||
				die 'unknown target case did not open direct-tcpip'
			tail -n "+$target_start" "$FIXTURE_DIR/target.log" | grep -q 'Remote protocol version' ||
				die 'unknown target case did not reach the target SSH server'
			if tail -n "+$target_start" "$FIXTURE_DIR/target.log" | grep -Eq 'userauth-request|Accepted |Starting session'; then
				die 'unknown target case reached target authentication or a session'
			fi
			jump_start=$(($(wc -l <"$FIXTURE_DIR/jump.log") + 1))
			target_start=$(($(wc -l <"$FIXTURE_DIR/target.log") + 1))
			run_product_jump_case jump-unknown-root
			grep -Fxq 'PRODUCT_DOCKER mode=jump-unknown-root outcome=unknown-jump-before-auth cleanup=pass commits=0 peer=real-sshd platform=host-jni' \
				"$FIXTURE_DIR/product-jump-unknown-root.log" || die 'unknown jump rejection result missing'
			tail -n "+$jump_start" "$FIXTURE_DIR/jump.log" | grep -q 'Remote protocol version' ||
				die 'unknown jump case did not reach the jump SSH server'
			if tail -n "+$jump_start" "$FIXTURE_DIR/jump.log" | grep -Eq 'userauth-request|Accepted |ctype direct-tcpip'; then
				die 'unknown jump case reached jump authentication or opened a forward'
			fi
			if tail -n "+$target_start" "$FIXTURE_DIR/target.log" | grep -Eq 'Connection from|Remote protocol version|userauth-request|Accepted |Starting session'; then
				die 'unknown jump case reached the target SSH server'
			fi
			jump_start=$(($(wc -l <"$FIXTURE_DIR/jump.log") + 1))
			target_start=$(($(wc -l <"$FIXTURE_DIR/target.log") + 1))
			run_product_jump_case jump-cancel
			grep -Fxq 'PRODUCT_DOCKER mode=jump-cancel outcome=target-prompt-cancelled cleanup=pass commits=0 peer=real-sshd platform=host-jni' \
				"$FIXTURE_DIR/product-jump-cancel.log" || die 'target prompt cancellation result missing'
			tail -n "+$jump_start" "$FIXTURE_DIR/jump.log" | grep -q 'Accepted password for ctosjump' ||
				die 'target prompt cancellation did not authenticate the jump'
			tail -n "+$jump_start" "$FIXTURE_DIR/jump.log" | grep -q 'ctype direct-tcpip' ||
				die 'target prompt cancellation did not open direct-tcpip'
			tail -n "+$target_start" "$FIXTURE_DIR/target.log" | grep -q 'userauth-request for user ctostarget service ssh-connection method none' ||
				die 'target prompt cancellation did not reach authentication discovery'
			if tail -n "+$target_start" "$FIXTURE_DIR/target.log" | grep -Eq 'userauth-request.*method password|Accepted password|Starting session'; then
				die 'cancelled target received a password request or opened a session'
			fi
			if grep -Eqi 'WARNING in native method|WARNING.*JNI|FATAL ERROR in native method|JNI DETECTED ERROR' \
				"$FIXTURE_DIR/product-multi-jump.log" "$FIXTURE_DIR/product-jump.log" \
				"$FIXTURE_DIR/product-jump-changed.log" "$FIXTURE_DIR/product-jump-unknown.log" "$FIXTURE_DIR/product-jump-unknown-root.log" \
				"$FIXTURE_DIR/product-jump-cancel.log"; then
				die 'JVM JNI checks reported a warning or error'
			fi
			cat "$FIXTURE_DIR/product-multi-jump.log" "$FIXTURE_DIR/product-jump.log" \
				"$FIXTURE_DIR/product-jump-changed.log" "$FIXTURE_DIR/product-jump-unknown.log" "$FIXTURE_DIR/product-jump-unknown-root.log" \
				"$FIXTURE_DIR/product-jump-cancel.log"
		fi
		if grep -Eqi 'WARNING in native method|WARNING.*JNI|FATAL ERROR in native method|JNI DETECTED ERROR' \
			"$FIXTURE_DIR/product-direct.log" "$FIXTURE_DIR/product-direct-none.log"; then
			die 'JVM JNI checks reported a warning or error'
		fi
		if [ "$mode" = --product-java ]; then
			if grep -Eqi 'WARNING in native method|WARNING.*JNI|FATAL ERROR in native method|JNI DETECTED ERROR' \
				"$FIXTURE_DIR/product-gate.log"; then die 'JVM JNI checks reported a warning or error'; fi
			cat "$FIXTURE_DIR/product-gate.log"
			printf 'FIXTURE product-jump-gate=no-network\n'
		else
			printf 'FIXTURE product-jump=two-saved-confirmations target-pty=pass changed-unknown-cancel=no-target-password multi=no-network\n'
		fi
		cat "$FIXTURE_DIR/product-direct.log" "$FIXTURE_DIR/product-direct-none.log"
		if [ "$mode" = --product-java ]; then
			printf 'FIXTURE product-direct=password-authenticated target-command=pass jump-gate=no-network proxyjump-none=direct\n'
		else
			printf 'FIXTURE product-direct=password-authenticated target-command=pass proxyjump-none=direct\n'
		fi
	fi
	if [ "$mode" = --product-open ]; then
		printf 'FIXTURE checks=pass network=none temporary-account=container-only\n'
	else
		printf 'FIXTURE gate=pass network=none temporary-account=container-only\n'
	fi
}

main "$@"
