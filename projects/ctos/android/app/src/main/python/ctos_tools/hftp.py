# -*- coding: utf-8 -*-
"""Authenticated file service confined to one explicit private library."""

import base64
import hmac
import html
import json
import os
import secrets
import shutil
import socket
import sys
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from ctos_tools.files import MAX_FILE_BYTES, MAX_STORE_BYTES, commit_exclusive


class FileServer(ThreadingHTTPServer):
    """Bound connection concurrency and serialize quota-sensitive writes."""

    daemon_threads = True
    allow_reuse_address = False

    def __init__(self, address: tuple[str, int], root: Path, password: str) -> None:
        self.root = root.resolve(strict=True)
        self.password = password
        self.capacity = threading.BoundedSemaphore(4)
        self.write_lock = threading.Lock()
        super().__init__(address, FileHandler)

    def process_request(self, request: socket.socket, client_address: tuple) -> None:
        """Reject excess connections instead of creating unlimited threads."""
        if not self.capacity.acquire(blocking=False):
            request.close()
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self.capacity.release()
            raise

    def process_request_thread(self, request: socket.socket, client_address: tuple) -> None:
        """Always return the connection permit."""
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.capacity.release()

    def handle_error(self, request: socket.socket, client_address: tuple) -> None:
        """Avoid logging authentication headers or user-supplied path details."""
        return None


class FileHandler(BaseHTTPRequestHandler):
    """Serve directory listings, downloads and bounded exclusive uploads."""

    server: FileServer

    def setup(self) -> None:
        """Apply an idle timeout to every incoming connection."""
        super().setup()
        self.connection.settimeout(15)

    def log_message(self, format: str, *args: object) -> None:
        """Keep credentials and file names out of access logs."""
        return None

    def _reply(self, status: int, body: bytes, content_type: str = "text/plain; charset=utf-8",
               extra: dict[str, str] | None = None) -> None:
        """Send bounded responses with explicit browser security headers."""
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Frame-Options", "DENY")
        for key, value in (extra or {}).items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(body)

    def _authenticated(self) -> bool:
        """Require the current session credential on every request."""
        expected = "Basic " + base64.b64encode(("ctos:" + self.server.password).encode()).decode("ascii")
        received = self.headers.get("Authorization", "")
        if hmac.compare_digest(received.encode("utf-8"), expected.encode("ascii")):
            return True
        self._reply(401, b"Authentication required", extra={"WWW-Authenticate": 'Basic realm="ctOS HFTP"'})
        return False

    def _path(self) -> Path:
        """Reject traversal, hidden temporary files, symlinks and deep paths."""
        relative = urllib.parse.unquote(urllib.parse.urlsplit(self.path).path, errors="strict").lstrip("/")
        parts = relative.split("/") if relative else []
        if len(parts) > 8 or any(part in (".", "..") or "\\" in part or "\x00" in part or part.startswith(".") for part in parts):
            raise ValueError("Invalid path")
        current = self.server.root
        for part in parts:
            current = current / part
            if current.is_symlink():
                raise ValueError("Symlinks are not shared")
        if not current.resolve().is_relative_to(self.server.root):
            raise ValueError("Path escapes library")
        return current

    def do_GET(self) -> None:
        """Browse only the library and download files as attachments."""
        if not self._authenticated():
            return
        try:
            path = self._path()
            if path.is_dir():
                self._listing(path)
            elif path.is_file() and path.stat().st_size <= MAX_FILE_BYTES:
                self._download(path)
            else:
                self._reply(404, b"File not found")
        except (ValueError, OSError):
            self._reply(400, b"Invalid or unavailable path")

    def _download(self, path: Path) -> None:
        """Stream a bounded regular file without loading it into memory."""
        with path.open("rb") as stream:
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(path.stat().st_size))
            self.send_header("Content-Disposition", "attachment; filename*=UTF-8''" + urllib.parse.quote(path.name))
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            shutil.copyfileobj(stream, self.wfile, 65536)

    def _listing(self, path: Path) -> None:
        """Render a self-contained browser page without external resources."""
        entries = sorted(path.iterdir(), key=lambda item: (not item.is_dir(), item.name))[:1000]
        links = []
        if path != self.server.root:
            parent = path.parent.relative_to(self.server.root).as_posix()
            links.append('<li><a href="/' + urllib.parse.quote(parent) + '">..</a></li>')
        for entry in entries:
            if entry.is_symlink() or entry.name.startswith(".") or entry.name.endswith(".partial"):
                continue
            relative = entry.relative_to(self.server.root).as_posix()
            links.append('<li><a href="/' + urllib.parse.quote(relative) + '">' + html.escape(entry.name) + ('/' if entry.is_dir() else '') + '</a></li>')
        nonce = secrets.token_urlsafe(16)
        relative = path.relative_to(self.server.root).as_posix()
        base = "/" + ("" if relative == "." else urllib.parse.quote(relative))
        script = """
const base = BASE;
async function send(name, body, directory) {
  if (!name || name.includes('/') || name.includes('\\\\')) return;
  const response = await fetch(base.replace(/\\/$/, '') + '/' + encodeURIComponent(name) + (directory ? '?directory=1' : ''),
    {method:'PUT', headers:{'X-ctos-upload':'1'}, body:body, credentials:'same-origin'});
  document.getElementById('status').textContent = await response.text();
  if (response.ok) location.reload();
}
document.getElementById('upload').onclick = () => {
  const file = document.getElementById('file').files[0];
  if (file) send(file.name, file, false).catch(() => document.getElementById('status').textContent='Upload failed');
};
document.getElementById('mkdir').onclick = () => send(document.getElementById('name').value, '', true);
""".replace("BASE", json.dumps(base))
        body = ('<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width">'
                '<title>ctOS HFTP</title><h1>ctOS HFTP</h1><p>Private shared library. Max file: 32 MiB. Existing files are preserved.</p>'
                '<ul>' + ''.join(links) + '</ul><input id="file" type="file"><button id="upload">Upload</button>'
                '<p><input id="name" placeholder="Folder name"><button id="mkdir">Create folder</button></p>'
                '<p id="status" role="status"></p><script nonce="' + nonce + '">' + script + '</script>').encode()
        self._reply(200, body, "text/html; charset=utf-8", {
            "Content-Security-Policy": "default-src 'none'; script-src 'nonce-" + nonce + "'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'"})

    def do_PUT(self) -> None:
        """Create files and directories exclusively, with atomic file commits."""
        if not self._authenticated():
            return
        if self.headers.get("X-ctos-upload") != "1" or self.headers.get("Transfer-Encoding"):
            self._reply(400, b"Expected a bounded upload")
            return
        try:
            path = self._path()
            size = int(self.headers.get("Content-Length", "-1"))
            if not 0 <= size <= MAX_FILE_BYTES or path == self.server.root or not path.parent.is_dir():
                raise ValueError("Invalid upload")
            with self.server.write_lock:
                if path.exists():
                    self._reply(409, b"A file or directory with that name already exists")
                    return
                count = sum(1 for item in self.server.root.rglob("*") if not item.is_symlink())
                used = sum(item.stat().st_size for item in self.server.root.rglob("*") if item.is_file() and not item.is_symlink())
                if count >= 2000 or used + size > MAX_STORE_BYTES:
                    self._reply(413, b"Private library quota exceeded")
                    return
                if urllib.parse.urlsplit(self.path).query == "directory=1":
                    if size != 0:
                        raise ValueError("Directory request must be empty")
                    path.mkdir()
                else:
                    self._upload(path, size)
            self._reply(201, b"Created")
        except (ValueError, OSError):
            self._reply(400, b"Upload failed or invalid path")

    def _upload(self, path: Path, size: int) -> None:
        """Clean an incomplete upload and never replace an existing file."""
        temporary = path.parent / ("." + secrets.token_hex(16) + ".partial")
        try:
            with temporary.open("xb") as stream:
                remaining = size
                while remaining:
                    block = self.rfile.read(min(65536, remaining))
                    if not block:
                        raise ValueError("Incomplete upload")
                    stream.write(block)
                    remaining -= len(block)
                stream.flush()
                os.fsync(stream.fileno())
            commit_exclusive(temporary, path)
        finally:
            temporary.unlink(missing_ok=True)


def serve(root: Path, host: str, port: int, password: str, ready: bool = False) -> None:
    """Own one blocking service; shutdown is controlled by the caller."""
    if host not in ("127.0.0.1", "0.0.0.0") or not 0 <= port <= 65535 or len(password) < 16:
        raise ValueError("Invalid server configuration")
    if root.is_symlink() or not root.is_dir():
        raise ValueError("Expected a real private library directory")
    with FileServer((host, port), root, password) as server:
        if ready:
            sys.stdout.write(json.dumps({"ready": True, "port": server.server_port}) + "\n")
            sys.stdout.flush()
        server.serve_forever(poll_interval=0.25)


def main() -> int:
    """Read the native service's configuration without argv credentials."""
    raw = sys.stdin.buffer.read(8193)
    if len(raw) > 8192:
        raise ValueError("Service configuration too large")
    try:
        config = json.loads(raw)
        serve(Path(config["root"]), config["host"], config["port"], config["password"], ready=True)
        return 0
    except (OSError, ValueError) as error:
        sys.stdout.write(json.dumps({"ready": False, "reason": str(error)[:256]}) + "\n")
        sys.stdout.flush()
        return 1


if __name__ == "__main__":
    sys.exit(main())
