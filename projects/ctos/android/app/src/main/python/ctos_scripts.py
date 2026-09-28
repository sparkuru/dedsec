# -*- coding: utf-8 -*-
"""Built-in scripts; add new trusted scripts to registry() to expose an item."""

import hashlib
import platform
import re
import sqlite3
import ssl
import sys

from ctos_sdk import Context, Parameter, Script
from ctos_tools.adapters import scripts


def runtime_check(context: Context, parameters: dict[str, str]) -> dict:
    """Exercise important bundled standard-library modules without networking."""
    with sqlite3.connect(":memory:") as connection:
        value = connection.execute("SELECT 6 * 7").fetchone()[0]
    return {"python": platform.python_version(), "implementation": sys.implementation.name,
            "architecture": platform.machine(), "openssl": ssl.OPENSSL_VERSION,
            "sqlite": sqlite3.sqlite_version, "sqliteCheck": value == 42,
            "sha256Check": hashlib.sha256(b"ctOS").hexdigest(), "offline": True}


def device_info(context: Context, parameters: dict[str, str]) -> dict:
    """Read the Android API snapshot delivered for this task."""
    return context.section("system")


def memory_snapshot(context: Context, parameters: dict[str, str]) -> dict:
    """Return memory values and their original source and capture time."""
    return context.section("memory")


def network_interface_diagnose(context: Context, parameters: dict[str, str]) -> dict:
    """Describe one interface from a fresh, App-only Android API snapshot."""
    interface_name = parameters["interface_name"]
    if not re.fullmatch(r"[A-Za-z0-9_.:-]{1,64}", interface_name):
        raise ValueError("Interface name is invalid; refresh the interface list and retry")
    if not isinstance(context.network, dict):
        raise RuntimeError("A fresh App network snapshot is unavailable; retry the task")
    if context.network.get("interfaceError"):
        raise RuntimeError(f"Interface snapshot failed: {context.network['interfaceError']}")

    interfaces = context.network.get("interfaces")
    if not isinstance(interfaces, list):
        raise RuntimeError("The fresh App snapshot has no interface data; retry the task")
    matches = [item for item in interfaces if isinstance(item, dict)
               and item.get("name") == interface_name]
    if not matches:
        raise RuntimeError("This interface is no longer present; refresh the interface list and retry")
    if len(matches) != 1:
        raise RuntimeError("This interface is ambiguous in the fresh snapshot; refresh and retry")

    raw = matches[0]
    interface = {"name": interface_name}
    missing_fields = []
    for key in ("up", "state", "mtu", "addresses"):
        value = raw.get(key)
        if key in raw and not (key == "mtu" and isinstance(value, int) and value < 0):
            interface[key] = value
        else:
            missing_fields.append(key)

    counter_fields = {
        "rx": "rxBytes", "tx": "txBytes", "rxPackets": "rxPackets",
        "txPackets": "txPackets", "rxErrors": "rxErrors",
        "txErrors": "txErrors", "rxDrops": "rxDrops", "txDrops": "txDrops",
    }
    counters = {}
    unavailable_counters = []
    for source_key, result_key in counter_fields.items():
        value = raw.get(source_key)
        if isinstance(value, int) and not isinstance(value, bool) and value >= 0:
            counters[result_key] = value
        else:
            unavailable_counters.append(result_key)
    interface["counters"] = counters
    interface["unavailableCounters"] = unavailable_counters
    if missing_fields:
        interface["missingFields"] = missing_fields

    networks = context.network.get("networks", [])
    if not isinstance(networks, list):
        networks = []
    associated = []
    network_fields = ("interface", "default", "vpn", "transport", "validated",
                      "metered", "mtu", "addresses", "routes", "privateDns")
    for item in networks:
        if not isinstance(item, dict) or item.get("interface") != interface_name:
            continue
        associated.append({key: item[key] for key in network_fields if key in item})

    findings = []
    warnings = []
    if raw.get("state") == "DOWN":
        findings.append("接口状态为 DOWN")
    addresses = raw.get("addresses")
    if isinstance(addresses, list) and not addresses:
        findings.append("快照中没有接口地址")
    if not isinstance(addresses, list):
        findings.append("快照未提供接口地址数据")
    other_missing = [field for field in missing_fields if field not in ("addresses",)]
    if other_missing:
        findings.append(f"快照未提供字段：{', '.join(other_missing)}")
    if unavailable_counters:
        findings.append(f"{len(unavailable_counters)} 项累计计数器不可用")
    if context.network.get("counterError"):
        warnings.append(f"计数器采集提示：{context.network['counterError']}")

    state = raw.get("state") if isinstance(raw.get("state"), str) else "状态数据不可用"
    address_summary = (f"{len(addresses)} 个地址" if isinstance(addresses, list)
                       else "地址数据不可用")
    summary = f"接口 {interface_name}：{state} · {address_summary}"
    return {
        "source": context.network.get("source", "Android App API"),
        "capturedAt": context.network.get("capturedAt"),
        "interfaceName": interface_name,
        "interface": interface,
        "networks": associated,
        "summary": summary,
        "findings": findings,
        "warnings": warnings,
    }


def text_digest(context: Context, parameters: dict[str, str]) -> dict:
    """Compute reproducible UTF-8 text size and SHA-256 locally."""
    text = parameters["text"]
    return {"characters": len(text), "utf8Bytes": len(text.encode("utf-8")),
            "lines": len(text.splitlines()), "sha256": hashlib.sha256(text.encode("utf-8")).hexdigest()}


def registry() -> tuple[Script, ...]:
    """Keep the UI catalogue and execution allowlist in one explicit registry."""
    return (
        Script("python.selftest", "Python self-test", "Check Python and bundled libraries", "runtime", runtime_check),
        Script("device.info", "Device summary", "Model, Android, architecture and uptime", "system", device_info),
        Script("memory.snapshot", "Memory snapshot", "Total, available and low-memory status", "system", memory_snapshot),
        Script("network.interface_diagnose", "Interface diagnosis", "Read-only facts for one current network interface", "system", network_interface_diagnose,
               (Parameter("interface_name", "Interface name", max_length=64),)),
        Script("text.digest", "Text digest", "UTF-8 size and SHA-256", "text", text_digest,
               (Parameter("text", "Text", multiline=True),)),
    ) + scripts()
