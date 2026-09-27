# -*- coding: utf-8 -*-
"""Explicit file service confined to a private library or authorized tree."""

import base64
import hmac
import html
import json
import logging
import secrets
import socket
import sys
import threading
import unicodedata
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import TextIO

from ctos_tools.files import MAX_FILE_BYTES, MAX_STORE_BYTES
from ctos_tools.hftp_storage import PathStorage, QuotaError, SafStorage, transfer, validate_parts


class EventLog:
    """Emit bounded known events as native JSON lines or ordinary CLI diagnostics."""

    def __init__(self, protocol: bool = False, stream: TextIO | None = None) -> None:
        self.protocol = protocol
        self.stream = stream if stream is not None else sys.stdout if protocol else sys.stderr
        self.lock = threading.Lock()
        self.logger = logging.Logger("ctos.hftp", logging.INFO)
        handler = logging.StreamHandler(self.stream)
        handler.setFormatter(logging.Formatter("%(asctime)s [%(levelname)s] %(message)s", "%H:%M:%S"))
        self.logger.addHandler(handler)

    @staticmethod
    def clean(value: str, limit: int = 240) -> str:
        """Prevent multiline or directional log injection and bound dynamic values."""
        return "".join("?" if unicodedata.category(character).startswith("C") or character in "\u2028\u2029"
                       else character for character in value[:limit])

    def emit(self, event: str, **fields: str | int) -> None:
        """Serialize allowlisted fields; never accept an arbitrary process message."""
        value: dict[str, str | int] = {"type": "log", "event": event}
        for key in ("method", "path", "remote", "status", "bytes", "error", "port", "maxUploadMiB"):
            if key in fields:
                field = fields[key]
                value[key] = self.clean(field) if isinstance(field, str) else field
        with self.lock:
            if self.protocol:
                self.stream.write(json.dumps(value, ensure_ascii=True, separators=(",", ":")) + "\n")
                self.stream.flush()
            else:
                description = " ".join(f"{key}={item}" for key, item in value.items() if key != "type")
                self.logger.log(logging.ERROR if event == "error" else logging.WARNING
                                if event == "capacity" or int(value.get("status", 0)) >= 400 else logging.INFO,
                                self.clean(description, 480))


class FileServer(ThreadingHTTPServer):
    """Bound connection concurrency and serialize quota-sensitive writes."""

    daemon_threads = True
    allow_reuse_address = False

    def __init__(self, address: tuple[str, int], root: Path | PathStorage | SafStorage,
                 password: str | None = None, max_upload_bytes: int = MAX_FILE_BYTES,
                 event_log: EventLog | None = None) -> None:
        if not 1024 * 1024 <= max_upload_bytes <= 1024 * 1024 * 1024:
            raise ValueError("Expected an upload limit of 1-1024 MiB")
        if password is not None and len(password) < 16:
            raise ValueError("Invalid session credential")
        quota = max(MAX_STORE_BYTES, 4 * max_upload_bytes)
        self.storage = PathStorage(root, quota) if isinstance(root, Path) else root
        self.root = root
        self.password = password
        self.max_upload_bytes = max_upload_bytes
        self.max_download_bytes = 1024 * 1024 * 1024
        self.capacity = threading.BoundedSemaphore(4)
        self.write_lock = threading.Lock()
        self.events = event_log if event_log is not None else EventLog()
        super().__init__(address, FileHandler)

    def process_request(self, request: socket.socket, client_address: tuple) -> None:
        """Reject excess connections instead of creating unlimited threads."""
        if not self.capacity.acquire(blocking=False):
            self.events.emit("capacity", remote=client_address[0])
            request.close()
            return
        self.events.emit("connection", remote=client_address[0])
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
        """Report only the exception class, never exception text or request headers."""
        error = sys.exc_info()[0]
        self.events.emit("error", remote=client_address[0], error=error.__name__ if error else "ServiceError")


class FileHandler(BaseHTTPRequestHandler):
    """Serve directory listings, downloads and bounded exclusive uploads."""

    server: FileServer

    def setup(self) -> None:
        """Apply an idle timeout to every incoming connection."""
        super().setup()
        self.connection.settimeout(15)

    def log_message(self, format: str, *args: object) -> None:
        """Suppress the base class's unbounded raw request-line diagnostics."""
        return None

    def log_error(self, format: str, *args: object) -> None:
        """Expose a known failure category without interpolating raw arguments."""
        self._event("error", error="TimeoutError" if "timed out" in format else "ProtocolError")

    def _event(self, event: str, **fields: str | int) -> None:
        """Attach safe request context without headers, query or body contents."""
        method = getattr(self, "command", "OTHER")
        if method not in ("GET", "PUT", "HEAD", "POST", "DELETE", "OPTIONS", "PATCH"):
            method = "OTHER"
        try:
            path = urllib.parse.unquote(urllib.parse.urlsplit(getattr(self, "path", "/")).path, errors="strict")
            if not path.startswith("/") or path.startswith("//"):
                path = "/[invalid]"
        except (ValueError, UnicodeError):
            path = "/[invalid]"
        self.server.events.emit(event, remote=self.client_address[0], method=method, path=path, **fields)

    def parse_request(self) -> bool:
        """Record request arrival before authentication or file operations."""
        parsed = super().parse_request()
        if parsed:
            self._event("request")
        return parsed

    def send_response(self, code: int, message: str | None = None) -> None:
        """Record status for successful, denied and unsupported requests."""
        self._event("response", status=code)
        super().send_response(code, message)

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
        """Support authenticated CLI sessions and the App's explicit no-login mode."""
        if self.server.password is None:
            return True
        expected = "Basic " + base64.b64encode(("ctos:" + self.server.password).encode()).decode("ascii")
        received = self.headers.get("Authorization", "")
        if hmac.compare_digest(received.encode("utf-8"), expected.encode("ascii")):
            return True
        self._reply(401, b"Authentication required", extra={"WWW-Authenticate": 'Basic realm="ctOS HFTP"'})
        return False

    def _path(self) -> tuple[str, ...]:
        """Decode and validate a relative path independently of its provider."""
        relative = urllib.parse.unquote(urllib.parse.urlsplit(self.path).path, errors="strict").lstrip("/").rstrip("/")
        parts = tuple(relative.split("/")) if relative else ()
        validate_parts(parts)
        return parts

    def do_GET(self) -> None:
        """Browse only the library and download files as attachments."""
        if not self._authenticated():
            return
        try:
            path = self._path()
            metadata = self.server.storage.stat(path)
            if metadata.get("exists") and metadata.get("directory"):
                self._listing(path)
            elif metadata.get("exists") and 0 <= metadata.get("size", -1) <= self.server.max_download_bytes:
                self._download(path)
            else:
                self._reply(404, b"File not found or download limit exceeded")
        except (ValueError, OSError) as error:
            self._event("error", error=type(error).__name__)
            self._reply(400, b"Invalid or unavailable path")

    def _download(self, path: tuple[str, ...]) -> None:
        """Stream a bounded regular file without loading it into memory."""
        streaming = False
        try:
            with self.server.storage.read(path) as (stream, size):
                if not 0 <= size <= self.server.max_download_bytes:
                    raise ValueError("Download limit exceeded")
                self.send_response(200)
                self.send_header("Content-Type", "application/octet-stream")
                self.send_header("Content-Length", str(size))
                self.send_header("Content-Disposition", "attachment; filename*=UTF-8''" + urllib.parse.quote(path[-1]))
                self.send_header("X-Content-Type-Options", "nosniff")
                self.send_header("Cache-Control", "no-store")
                streaming = True
                self.end_headers()
                transfer(stream, self.wfile, size)
                self._event("download", bytes=size)
        except (ValueError, OSError) as error:
            if not streaming:
                raise
            self._event("error", error=type(error).__name__)
            # Sending another HTTP response here would corrupt the advertised file body.
            self.close_connection = True

    def _listing(self, path: tuple[str, ...]) -> None:
        """Render a self-contained browser page without external resources."""
        links = []
        if path:
            parent = "/".join(path[:-1])
            links.append('<li><a href="/' + urllib.parse.quote(parent) + '">..</a></li>')
        for entry in self.server.storage.listing(path):
            name = entry["name"]
            validate_parts((name,))
            relative = "/".join((*path, name))
            links.append('<li><a href="/' + urllib.parse.quote(relative) + '">' + html.escape(name) + ('/' if entry["directory"] else '') + '</a></li>')
        nonce = secrets.token_urlsafe(16)
        base = "/" + urllib.parse.quote("/".join(path))
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
                '<title>ctOS HFTP</title><h1>ctOS HFTP</h1><p>Shared directory. Max upload: ' + str(self.server.max_upload_bytes // (1024 * 1024)) + ' MiB. Existing files are preserved.</p>'
                '<ul>' + ''.join(links) + '</ul><input id="file" type="file"><button id="upload">Upload</button>'
                '<p><input id="name" placeholder="Folder name"><button id="mkdir">Create folder</button></p>'
                '<p id="status" role="status"></p><script nonce="' + nonce + '">' + script + '</script>').encode()
        self._reply(200, body, "text/html; charset=utf-8", {
            "Content-Security-Policy": "default-src 'none'; script-src 'nonce-" + nonce + "'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'"})

    def do_PUT(self) -> None:
        """Create files and directories exclusively through the selected store."""
        if not self._authenticated():
            return
        if self.headers.get("X-ctos-upload") != "1" or self.headers.get("Transfer-Encoding"):
            self._reply(400, b"Expected a bounded upload")
            return
        try:
            path = self._path()
            size = int(self.headers.get("Content-Length", "-1"))
            if not 0 <= size <= self.server.max_upload_bytes:
                self._reply(413, b"Upload exceeds configured limit")
                return
            if not path:
                raise ValueError("Invalid upload")
            with self.server.write_lock:
                if urllib.parse.urlsplit(self.path).query == "directory=1":
                    if size != 0:
                        raise ValueError("Directory request must be empty")
                    self.server.storage.mkdir(path)
                    self._event("mkdir", bytes=0)
                else:
                    self.server.storage.write(path, self.rfile, size)
                    self._event("upload", bytes=size)
            self._reply(201, b"Created")
        except FileExistsError as error:
            self._event("error", error=type(error).__name__)
            self._reply(409, b"A file or directory with that name already exists")
        except QuotaError as error:
            self._event("error", error=type(error).__name__)
            self._reply(413, b"Directory quota exceeded")
        except (ValueError, OSError) as error:
            self._event("error", error=type(error).__name__)
            self._reply(400, b"Upload failed or invalid path")


def serve(root: Path | SafStorage, host: str, port: int, password: str | None = None,
          ready: bool = False, max_upload_bytes: int = MAX_FILE_BYTES) -> None:
    """Own one blocking service; shutdown is controlled by the caller."""
    if host not in ("127.0.0.1", "0.0.0.0") or not 0 <= port <= 65535:
        raise ValueError("Invalid server configuration")
    events = EventLog(protocol=ready)
    with FileServer((host, port), root, password, max_upload_bytes, events) as server:
        if not server.storage.stat(()).get("directory"):
            raise ValueError("Shared directory is unavailable")
        if ready:
            sys.stdout.write(json.dumps({"ready": True, "port": server.server_port}) + "\n")
            sys.stdout.flush()
        else:
            events.emit("listen", remote=host, port=server.server_port, maxUploadMiB=max_upload_bytes // (1024 * 1024))
        try:
            server.serve_forever(poll_interval=0.25)
        finally:
            if not ready:
                events.emit("stop")


def main() -> int:
    """Read the native service's configuration without argv credentials."""
    raw = sys.stdin.buffer.read(8193)
    if len(raw) > 8192:
        raise ValueError("Service configuration too large")
    try:
        config = json.loads(raw)
        root = SafStorage(config["broker"]) if config.get("broker") else Path(config["root"])
        serve(root, config["host"], config["port"], config.get("password"), ready=True,
              max_upload_bytes=int(config.get("maxUploadMiB", 32)) * 1024 * 1024)
        return 0
    except (OSError, ValueError) as error:
        sys.stdout.write(json.dumps({"ready": False, "reason": type(error).__name__, "errno": getattr(error, "errno", None)}) + "\n")
        sys.stdout.flush()
        return 1


if __name__ == "__main__":
    sys.exit(main())
