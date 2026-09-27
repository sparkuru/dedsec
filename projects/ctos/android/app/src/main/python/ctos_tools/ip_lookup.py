# -*- coding: utf-8 -*-
"""Bounded, user-triggered IP lookup over HTTPS."""

import ipaddress
import json
import re
import socket
import urllib.request

API_ROOT = "https://free.freeipapi.com/api/v1/json"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """Prevent a remote redirect from changing the trusted HTTPS endpoint."""

    def redirect_request(self, request: object, file: object, code: int,
                         message: str, headers: object, new_url: str) -> None:
        """Reject redirects rather than forwarding the request elsewhere."""
        raise ValueError("IP API redirects are not allowed")


def resolve(target: str) -> str:
    """Accept an IP or a plain DNS name, never URLs or arbitrary paths."""
    target = target.strip()
    if not target:
        return ""
    try:
        return str(ipaddress.ip_address(target))
    except ValueError:
        if len(target) > 253 or not re.fullmatch(r"[a-zA-Z0-9](?:[a-zA-Z0-9.-]*[a-zA-Z0-9])?", target):
            raise ValueError("Expected an IP address or DNS name") from None
    return str(ipaddress.ip_address(socket.gethostbyname(target)))


def lookup(target: str = "") -> dict:
    """Resolve the target and read at most 32 KiB from the fixed IP API."""
    address = resolve(target)
    endpoint = API_ROOT + ("/" + address if address else "")
    request = urllib.request.Request(endpoint, headers={"User-Agent": "ctOS/0.1"})
    with urllib.request.build_opener(NoRedirect()).open(request, timeout=8) as response:
        body = response.read(32769)
    if len(body) > 32768:
        raise ValueError("IP API response exceeds 32 KiB")
    data = json.loads(body)
    if not isinstance(data, dict):
        raise ValueError("Unexpected IP API response")
    return {"target": target, "resolved": address, "source": API_ROOT, "data": data}
