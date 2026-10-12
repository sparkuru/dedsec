# -*- coding: utf-8 -*-
"""Build and execute the research-only, pinned OpenSSH memory adapter probe."""

import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile

ARCHIVE_SHA256 = "021a2e709a0edf4250b1256bd5a9e500411a90dddabea830ed59cef90eb9d85c"
WRAPS = (
    "open", "fopen", "stat", "access", "mkdir", "mkstemp", "link", "rename",
    "unlink", "getpwuid", "_ssh__compat_glob", "_ssh__compat_globfree", "closefrom",
)


class CLIStyle:
    """Apply semantic colors to terminal status and help."""

    COLORS = {"TITLE": 7, "SUB_TITLE": 2, "CONTENT": 3, "WARNING": 4, "ERROR": 2}

    @staticmethod
    def color(text: str = "", color: int = 3) -> str:
        """Color terminal text while keeping redirected evidence readable."""
        if not sys.stdout.isatty():
            return text
        codes = {2: 31, 3: 32, 4: 33, 7: 36}
        return f"\033[1;{codes.get(color, 37)}m{text}\033[0m"


class ColoredArgumentParser(argparse.ArgumentParser):
    """Keep CLI help in the common semantic color pipeline."""

    def _format_action_invocation(self, action: argparse.Action) -> str:
        """Color option invocations."""
        return CLIStyle.color(super()._format_action_invocation(action), 2)

    def format_help(self) -> str:
        """Color headings and preserve multiline examples."""
        return CLIStyle.color(super().format_help(), 7)


def execute(arguments: list[str], source: Path, log: Path, timeout: int = 120) -> None:
    """Run a bounded build step and retain diagnostics under /tmp."""
    with log.open("w", encoding="utf-8") as output:
        subprocess.run(arguments, cwd=source, check=True, stdout=output,
                       stderr=subprocess.STDOUT, timeout=timeout)


def build_probe(source: Path, workdir: Path) -> Path:
    """Compile strict-warning adapter/driver and link exact upstream objects."""
    native = Path(__file__).with_name("ssh-memory-native.c").resolve()
    compile_base = ["cc", "-std=c11", "-Wall", "-Wextra", "-Werror", "-O2",
                    "-I.", "-Iopenbsd-compat"]
    for name, defines in (("vfs", []), ("driver", ["-DSSH_MEMORY_DRIVER"])):
        execute(compile_base + defines + ["-c", str(native), "-o", str(workdir / f"{name}.o")],
                source, workdir / f"{name}-compile.log")
    link_flags = ["-L.", "-Lopenbsd-compat"] + [f"-Wl,--wrap={name}" for name in WRAPS]
    libraries = ["-lssh", "-lopenbsd-compat", "-lcrypto"]
    ssh_objects = ["ssh.o", "readconf.o", "clientloop.o", "sshtty.o", "sshconnect.o",
                   "sshconnect2.o", "mux.o", "ssh-sk-client.o"]
    execute(["cc", "-o", str(workdir / "ssh-memory")] + ssh_objects +
            [str(workdir / "vfs.o")] + link_flags + libraries, source,
            workdir / "ssh-link.log")
    execute(["cc", "-o", str(workdir / "memory-probe"), str(workdir / "driver.o"),
             "ssh-sk-client.o"] + link_flags + libraries, source,
            workdir / "driver-link.log")
    return workdir / "memory-probe"


def main() -> int:
    """Verify pinned source, build in /tmp, run synthetic tests and report evidence."""
    parser = ColoredArgumentParser(
        description=CLIStyle.color("Pinned OpenSSH synthetic memory-file probe", 7),
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=CLIStyle.color("Example:\n  python3 ssh-memory-run.py --archive /tmp/openssh-10.0p2.tar.gz\n"
                              "Notes:\n  Linux/glibc host only; loopback socket permission required.\n"
                              "  No real endpoint, personal SSH directory or plaintext fixture file.", 4),
    )
    parser.add_argument("--archive", type=Path, required=True,
                        metavar=CLIStyle.color("TAR_GZ", 7),
                        help=CLIStyle.color("Official openssh-10.0p2 archive", 3))
    parser.add_argument("--workdir", type=Path,
                        metavar=CLIStyle.color("TMP_DIR", 7),
                        help=CLIStyle.color("Existing /tmp build directory, or a new directory", 3))
    parser.add_argument("--log", action="store_true",
                        help=CLIStyle.color("Print subprocess failure details", 3))
    args = parser.parse_args()
    workdir = (args.workdir or Path(tempfile.mkdtemp(prefix="ctos-ssh-memory-"))).resolve()
    try:
        if not workdir.is_relative_to(Path("/tmp")):
            raise ValueError("The build directory must be under /tmp")
        workdir.mkdir(parents=True, exist_ok=True)
        digest = hashlib.sha256(args.archive.read_bytes()).hexdigest()
        if digest != ARCHIVE_SHA256:
            raise ValueError("Official archive hash mismatch")
        source = workdir / "openssh-10.0p1"
        if not source.exists():
            with tarfile.open(args.archive, "r:gz") as archive:
                archive.extractall(workdir, filter="data")
        version = (source / "version.h").read_text(encoding="utf-8")
        if '"p2"' not in version or '"OpenSSH_10.0"' not in version:
            raise ValueError("Extracted source version mismatch")
        if not (source / "Makefile").exists():
            execute(["./configure", "--without-zlib", "--without-pam", "--without-selinux",
                     "--without-kerberos5", "--disable-pkcs11", "--disable-security-key"],
                    source, workdir / "configure.log")
        execute(["make", "-j4", "ssh", "ssh-keygen"], source, workdir / "build.log")
        shutil.copy2(source / "ssh", workdir / "ssh-stock")
        probe = build_probe(source, workdir)
        completed = subprocess.run([str(probe), str(workdir / "ssh-memory"),
                                    str(workdir / "ssh-stock")],
                                   cwd=source, capture_output=True, text=True, timeout=20,
                                   env={"PATH": os.defpath, "LANG": "C"}, check=False)
        (workdir / "results.log").write_text(completed.stdout + completed.stderr, encoding="utf-8")
        print(CLIStyle.color(completed.stdout.rstrip()))
        print(CLIStyle.color(f"Evidence: {workdir}", 7))
        if completed.returncode != 0:
            print(CLIStyle.color(completed.stderr.rstrip(), 2), file=sys.stderr)
            return 1
        return 0
    except FileNotFoundError as error:
        print(CLIStyle.color(f"Missing probe input/tool: {error.filename}", 2), file=sys.stderr)
    except (OSError, ValueError, subprocess.SubprocessError, tarfile.TarError) as error:
        detail = str(error) if args.log else type(error).__name__
        print(CLIStyle.color(f"Probe failed: {detail}; evidence: {workdir}", 2), file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
