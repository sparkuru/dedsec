#!/bin/sh
set -eu

die() {
	printf 'Error: %s\n' "$*" >&2
	exit 1
}

[ -f /.dockerenv ] || die 'run only in a disposable Docker container'
[ "$(id -u)" -eq 0 ] || die 'the isolated sshd fixture requires container root'
for interface in /sys/class/net/*; do
	[ "$interface" = /sys/class/net/lo ] || die 'the fixture requires a network-none container'
done
mode=${1:-test}
case "$mode" in
test)
	if [ ! -x /test/qemu ] || [ ! -x /test/driver ]; then
		die 'mount the QEMU runner and test driver read-only'
	fi
	;;
serve) ;;
*) die 'expected test or serve' ;;
esac
if getent passwd ctosfixture >/dev/null; then die 'fixture account already exists'; fi

sshd_pid=''
sshd_pid2=''
timer_pid=''
cleanup() {
	status=$?
	if [ "$status" -ne 0 ] && [ -f /tmp/sshd.log ]; then
		printf 'FIXTURE failure-server-log-begin\n'
		cat /tmp/sshd.log
		printf 'FIXTURE failure-server-log-end\n'
	fi
	if [ -n "$sshd_pid" ]; then
		kill "$sshd_pid" 2>/dev/null || :
		wait "$sshd_pid" 2>/dev/null || :
	fi
	if [ -n "$sshd_pid2" ]; then
		kill "$sshd_pid2" 2>/dev/null || :
		wait "$sshd_pid2" 2>/dev/null || :
	fi
	if [ -n "$timer_pid" ]; then
		kill "$timer_pid" 2>/dev/null || :
		wait "$timer_pid" 2>/dev/null || :
	fi
}
trap cleanup EXIT
if [ "$mode" = serve ]; then
	trap 'exit 0' HUP INT TERM
else
	trap 'exit 1' HUP INT TERM
fi

export LANG=C
umask 077
mkdir -p /run/sshd
useradd --create-home --shell /bin/sh ctosfixture
openssl rand -hex 24 | tr -d '\n' >/tmp/secret
[ "$(wc -c </tmp/secret)" -eq 48 ] || die 'could not generate a test password'
printf 'ctosfixture:%s\n' "$(cat /tmp/secret)" | chpasswd
ssh-keygen -q -t ed25519 -N '' -f /tmp/host_key
cat >/etc/pam.d/sshd <<'PAM'
auth required pam_unix.so
account required pam_permit.so
session required pam_permit.so
PAM

write_config() {
	config_path=$1
	config_port=$2
	config_methods=$3
	cat >"$config_path" <<CONFIG
Port $config_port
ListenAddress 127.0.0.1
HostKey /tmp/host_key
PidFile /run/sshd-fixture-$config_port.pid
UsePAM yes
KbdInteractiveAuthentication yes
PasswordAuthentication yes
PubkeyAuthentication no
AuthenticationMethods $config_methods
PermitRootLogin no
AllowUsers ctosfixture
UseDNS no
PrintMotd no
LogLevel VERBOSE
CONFIG
}

run_case() {
	scenario=$1
	methods=$2
	write_config /tmp/sshd_config 22222 "$methods"
	/usr/sbin/sshd -t -f /tmp/sshd_config
	/usr/sbin/sshd -D -e -f /tmp/sshd_config >/tmp/sshd.log 2>&1 &
	sshd_pid=$!
	ready=0
	attempt=0
	while [ "$attempt" -lt 10 ]; do
		if grep -q 'Server listening on 127.0.0.1 port 22222' /tmp/sshd.log; then
			ready=1
			break
		fi
		kill -0 "$sshd_pid"
		attempt=$((attempt + 1))
		sleep 1
	done
	[ "$ready" -eq 1 ]
	printf 'FIXTURE scenario=%s server=OpenSSH-9.2 network=loopback-only\n' "$scenario"
	/test/qemu /test/driver 127.0.0.1 22222 ctosfixture /tmp/host_key.pub "$scenario" </tmp/secret
	kill "$sshd_pid"
	wait "$sshd_pid" || :
	sshd_pid=''
	printf 'FIXTURE server-events-begin scenario=%s\n' "$scenario"
	grep -E 'Connection from|Partial password|Accepted keyboard-interactive|Failed keyboard-interactive|Starting session|Close session' /tmp/sshd.log
	printf 'FIXTURE server-events-end scenario=%s\n' "$scenario"
}

/usr/sbin/sshd -V 2>&1
if [ "$mode" = serve ]; then
	write_config /tmp/sshd_config 22222 keyboard-interactive:pam
	write_config /tmp/sshd_config_2 22223 password,keyboard-interactive:pam
	/usr/sbin/sshd -t -f /tmp/sshd_config
	/usr/sbin/sshd -t -f /tmp/sshd_config_2
	/usr/sbin/sshd -D -e -f /tmp/sshd_config >/tmp/sshd.log 2>&1 &
	sshd_pid=$!
	/usr/sbin/sshd -D -e -f /tmp/sshd_config_2 >/tmp/sshd_2.log 2>&1 &
	sshd_pid2=$!
	attempt=0
	while [ "$attempt" -lt 10 ]; do
		if grep -q 'Server listening on 127.0.0.1 port 22222' /tmp/sshd.log &&
			grep -q 'Server listening on 127.0.0.1 port 22223' /tmp/sshd_2.log; then
			break
		fi
		kill -0 "$sshd_pid"
		kill -0 "$sshd_pid2"
		attempt=$((attempt + 1))
		sleep 1
	done
	[ "$attempt" -lt 10 ] || die 'sshd listeners did not start'
	printf 'FIXTURE mode=serve status=ready keyboard=22222 password-keyboard=22223 lifetime=900s\n'
	(
		sleep 900
		kill -TERM "$$"
	) &
	timer_pid=$!
	wait "$sshd_pid"
	exit 1
fi
run_case --keyboard keyboard-interactive:pam
run_case --keyboard-reject keyboard-interactive:pam
run_case --password-keyboard password,keyboard-interactive:pam
printf 'FIXTURE gate=pass network=none temporary-account=container-only\n'
