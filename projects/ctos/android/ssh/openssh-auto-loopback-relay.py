#!/usr/bin/env python3
"""Relay one stdio connection to the isolated OpenSSH fixture's loopback."""

import os
import select
import socket
import sys


def main() -> int:
    """Copy SSH bytes without logging payloads or opening non-loopback sockets."""
    if len(sys.argv) != 2 or sys.argv[1] not in ("22222", "22223"):
        return 2
    with socket.create_connection(
        ("127.0.0.1", int(sys.argv[1])), timeout=10
    ) as connection:
        connection.settimeout(None)
        input_open = True
        while True:
            readable, _, _ = select.select(
                [connection, *([0] if input_open else [])], [], []
            )
            if 0 in readable:
                data = os.read(0, 65536)
                if data:
                    connection.sendall(data)
                else:
                    input_open = False
                    connection.shutdown(socket.SHUT_WR)
            if connection in readable:
                data = connection.recv(65536)
                if not data:
                    return 0
                offset = 0
                while offset < len(data):
                    offset += os.write(1, data[offset:])


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError):
        sys.exit(1)
