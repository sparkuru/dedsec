# -*- coding: utf-8 -*-
"""Terminal entry points reusing the same five portable tool cores."""

import argparse
import getpass
import importlib
import json
import os
import secrets
import sys
import tempfile
import traceback
from pathlib import Path

from ctos_tools import encoder, hftp, ip_lookup, password
from ctos_tools.files import MAX_FILE_BYTES, commit_exclusive, read_bounded


class CLIStyle:
    """Keep terminal help and diagnostics consistent across tools."""

    COLORS = {"TITLE": 7, "SUB_TITLE": 2, "CONTENT": 3, "EXAMPLE": 7, "WARNING": 4, "ERROR": 2}

    @staticmethod
    def color(text: str = "", color: int = 3) -> str:
        """Color interactive terminals; keep redirected output readable."""
        if not sys.stdout.isatty():
            return text
        codes = {0: "", 2: "31", 3: "32", 4: "33", 7: "36"}
        code = codes.get(color, "32")
        return f"\033[1;{code}m{text}\033[0m" if code else text


class ColoredHelpFormatter(argparse.RawDescriptionHelpFormatter):
    """Color section headings and actual option invocations."""

    def _format_action_invocation(self, action: argparse.Action) -> str:
        """Color option invocations in the help pipeline."""
        return CLIStyle.color(super()._format_action_invocation(action), CLIStyle.COLORS["SUB_TITLE"])

    def start_section(self, heading: str | None) -> None:
        """Color generated argument-section headings."""
        super().start_section(CLIStyle.color(heading or "", CLIStyle.COLORS["TITLE"]))


class ColoredArgumentParser(argparse.ArgumentParser):
    """Apply semantic colors to descriptions, options and usage examples."""

    def format_help(self) -> str:
        """Color the complete help output including generated sections."""
        return CLIStyle.color(super().format_help(), CLIStyle.COLORS["TITLE"])


def _parser() -> argparse.ArgumentParser:
    """Describe CLI commands without platform installation hooks."""
    parser = ColoredArgumentParser(description=CLIStyle.color("ctOS portable tools", 7),
        formatter_class=ColoredHelpFormatter,
        epilog=CLIStyle.color("Examples:\n  python3 -m ctos_tools encoder --text hello\n  python3 -m ctos_tools password\n  python3 -m ctos_tools hftp --directory ./share\nNotes: no daemon, global installs or automatic file deletion.", 7))
    parser.add_argument("--log", action="store_true", help=CLIStyle.color("Show error diagnostics", 3))
    sub = parser.add_subparsers(dest="command", required=True)
    descriptions = {
        "password": ("08 deterministic password", "password --length 24"),
        "encoder": ("26 byte conversion and hashes", "encoder --operation hash --input file.bin"),
        "ip": ("09 HTTPS IP lookup", "ip example.com"),
        "crypto": ("02 authenticated file encryption", "crypto encrypt input.bin encrypted.ctos"),
        "hftp": ("16 foreground HTTP file service", "hftp --directory ./share --host 127.0.0.1"),
    }
    commands = {}
    for name, (description, example) in descriptions.items():
        commands[name] = sub.add_parser(name, description=CLIStyle.color(description, 7),
            formatter_class=ColoredHelpFormatter,
            epilog=CLIStyle.color("Example: python3 -m ctos_tools " + example + "\nNotes: explicit inputs; existing files are preserved.", 7))
    generator = commands["password"]
    generator.add_argument("--seed", help=CLIStyle.color("Seed; omitted prompts privately", 3), metavar=CLIStyle.color("SEED", 7))
    generator.add_argument("--salt", help=CLIStyle.color("Explicit salt value", 3), metavar=CLIStyle.color("SALT", 7))
    generator.add_argument("--salt-file", type=Path, help=CLIStyle.color("Read an existing salt file", 3))
    generator.add_argument("--length", type=int, default=16, help=CLIStyle.color("1 to 128 characters", 3))
    generator.add_argument("--charset", default="", help=CLIStyle.color("Explicit alphabet", 3))
    generator.add_argument("--must", default="", help=CLIStyle.color("Required characters", 3))
    conversion = commands["encoder"]
    conversion.add_argument("--operation", choices=("base64", "url", "unicode", "hash"), default="base64", help=CLIStyle.color("Conversion or digest", 3))
    conversion.add_argument("--direction", choices=("encode", "decode", "auto"), default="encode", help=CLIStyle.color("Conversion direction", 3))
    sources = conversion.add_mutually_exclusive_group()
    sources.add_argument("--input", type=Path, help=CLIStyle.color("Input file", 3))
    sources.add_argument("--text", default="", help=CLIStyle.color("UTF-8 text", 3))
    conversion.add_argument("--output", type=Path, help=CLIStyle.color("Create an output file exclusively", 3))
    commands["ip"].add_argument("target", nargs="?", default="", help=CLIStyle.color("IP or DNS name; empty queries public IP", 3))
    encryption = commands["crypto"]
    encryption.add_argument("operation", choices=("encrypt", "decrypt", "legacy-decrypt"), help=CLIStyle.color("Explicit format operation", 3))
    encryption.add_argument("input", type=Path, help=CLIStyle.color("Existing input file", 3))
    encryption.add_argument("output", type=Path, help=CLIStyle.color("New output file", 3))
    encryption.add_argument("--password-file", type=Path, help=CLIStyle.color("Read password from an existing file; otherwise prompt", 3))
    service = commands["hftp"]
    service.add_argument("--directory", type=Path, required=True, help=CLIStyle.color("Existing directory explicitly selected for sharing", 3))
    service.add_argument("--host", choices=("127.0.0.1", "0.0.0.0"), default="127.0.0.1", help=CLIStyle.color("Local or LAN binding", 3))
    service.add_argument("--port", type=int, default=8080, help=CLIStyle.color("Unused TCP port", 3))
    return parser


def _write(path: Path, data: bytes) -> None:
    """Create output only after the operation succeeds, without replacement."""
    if len(data) > MAX_FILE_BYTES:
        raise ValueError("Output exceeds 32 MiB")
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(prefix=".ctos-", suffix=".partial", dir=path.parent, delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        commit_exclusive(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def _run(args: argparse.Namespace) -> dict:
    """Dispatch one command to its shared core implementation."""
    if args.command == "password":
        seed = args.seed if args.seed is not None else getpass.getpass("Seed: ")
        salt = read_bounded(args.salt_file).decode().strip() if args.salt_file else args.salt
        return {"password": password.generate(seed, args.length, salt, args.charset, args.must)}
    if args.command == "encoder":
        data = read_bounded(args.input) if args.input else args.text.encode()
        if args.operation == "hash":
            return encoder.hashes(data)
        data = encoder.transform(data, args.operation, args.direction)
        if args.output:
            _write(args.output, data)
            return {"output": str(args.output), "bytes": len(data)}
        return encoder.display(data[:4096]) | {"previewTruncated": len(data) > 4096}
    if args.command == "ip":
        return ip_lookup.lookup(args.target)
    if args.command == "crypto":
        crypto = importlib.import_module("ctos_tools.crypto")
        secret = read_bounded(args.password_file).decode().rstrip("\r\n") if args.password_file else getpass.getpass("Password: ")
        operation = {"encrypt": crypto.encrypt, "decrypt": crypto.decrypt, "legacy-decrypt": crypto.decrypt_legacy}[args.operation]
        data = operation(read_bounded(args.input), secret)
        _write(args.output, data)
        return {"output": str(args.output), "bytes": len(data)}
    credential = secrets.token_urlsafe(24)
    print(CLIStyle.color(f"HTTP service {args.host}:{args.port}; user ctos; password {credential}\nCtrl-C stops this process. LAN HTTP is not encrypted.", 4))
    hftp.serve(args.directory, args.host, args.port, credential)
    return {"state": "stopped"}


def main() -> int:
    """Return CLI exit codes without swallowing cancellation or deleting input."""
    args = _parser().parse_args()
    try:
        print(CLIStyle.color(json.dumps(_run(args), ensure_ascii=False, indent=2)))
        return 0
    except KeyboardInterrupt:
        print(CLIStyle.color("Stopped", 4))
        return 130
    except Exception as error:
        if args.log:
            traceback.print_exc()
        print(CLIStyle.color(f"Error: {error}", 2), file=sys.stderr)
        return 1
