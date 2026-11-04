#!/usr/bin/env python3
# Copyright (C) 2026 David González López-Tercero
# SPDX-License-Identifier: GPL-3.0-or-later
# See LICENSE in the repository root for licensing details.

"""Discover local IPv4 ADB TLS-connect services using an installed zeroconf package."""

import ipaddress
import math
import sys
import threading
import time


SERVICE_TYPE = "_adb-tls-connect._tcp.local."
LOCAL_NETWORKS = tuple(
    ipaddress.ip_network(network)
    for network in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "127.0.0.0/8")
)


def fail(code, message):
    print(f"adb-mdns-discover: {message}", file=sys.stderr)
    return code


def main():
    try:
        from zeroconf import IPVersion, ServiceBrowser, Zeroconf
    except ImportError:
        return fail(
            12,
            "Python package 'zeroconf' is unavailable; install it manually in "
            "$HOME/.venvs/adb-discovery or set ADB_DISCOVERY_PYTHON to an interpreter "
            "where it is installed (dependencies are never installed automatically)",
        )

    try:
        seconds = float(sys.argv[1]) if len(sys.argv) > 1 else 15.0
    except ValueError:
        return fail(2, "timeout must be a positive number of seconds")
    if len(sys.argv) > 2 or not math.isfinite(seconds) or seconds < 1 or seconds > 60:
        return fail(2, "timeout must be between 1 and 60 seconds")

    deadline = time.monotonic() + seconds
    changed = threading.Event()
    names_lock = threading.Lock()
    names = set()
    endpoints = set()
    failed_names = set()
    resolution_errors = set()
    processed_names = set()
    discovered = set()
    browser = None
    zeroconf = None

    class Listener:
        def add_service(self, _zeroconf, _service_type, name):
            with names_lock:
                names.add(name)
            changed.set()

        def update_service(self, _zeroconf, _service_type, name):
            with names_lock:
                names.add(name)
            changed.set()

    try:
        zeroconf = Zeroconf(ip_version=IPVersion.V4Only)
        browser = ServiceBrowser(zeroconf, SERVICE_TYPE, Listener())
        pending = set()
        while time.monotonic() < deadline:
            with names_lock:
                discovered = set(names)
            pending.update(discovered - processed_names)
            if pending:
                name = pending.pop()
                processed_names.add(name)
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    break
                try:
                    info = zeroconf.get_service_info(
                        SERVICE_TYPE, name, timeout=max(1, int(remaining * 1000))
                    )
                except Exception as exc:
                    failed_names.add(name)
                    resolution_errors.add(name)
                    print(
                        f"adb-mdns-discover: could not resolve {name!r}: {exc}",
                        file=sys.stderr,
                    )
                    continue
                if info is None:
                    failed_names.add(name)
                    continue
                try:
                    port = info.port
                    if not isinstance(port, int) or isinstance(port, bool) or not 1 <= port <= 65535:
                        raise ValueError(f"invalid TCP port {port!r}")
                    addresses = info.parsed_addresses(IPVersion.V4Only)
                    for text in addresses:
                        try:
                            address = ipaddress.IPv4Address(text)
                        except ipaddress.AddressValueError:
                            print(
                                f"adb-mdns-discover: ignoring malformed IPv4 address {text!r}",
                                file=sys.stderr,
                            )
                            continue
                        if not any(address in network for network in LOCAL_NETWORKS):
                            print(
                                f"adb-mdns-discover: ignoring non-local address {address}",
                                file=sys.stderr,
                            )
                            continue
                        endpoints.add(f"{address}:{port}")
                except Exception as exc:
                    print(
                        f"adb-mdns-discover: ignoring invalid service {name!r}: {exc}",
                        file=sys.stderr,
                    )
            else:
                changed.clear()
                remaining = deadline - time.monotonic()
                if remaining > 0:
                    changed.wait(min(remaining, 0.1))

        if discovered and not endpoints:
            if resolution_errors:
                return fail(13, "zeroconf service resolution backend failed")
            if failed_names:
                return fail(11, "timed out resolving discovered ADB mDNS services")
    except KeyboardInterrupt:
        return fail(11, "zeroconf discovery was interrupted at its deadline")
    except Exception as exc:
        return fail(13, f"zeroconf discovery backend failed: {exc}")
    finally:
        if browser is not None:
            try:
                browser.cancel()
            except Exception as exc:
                print(f"adb-mdns-discover: browser cleanup failed: {exc}", file=sys.stderr)
        if zeroconf is not None:
            try:
                zeroconf.close()
            except Exception as exc:
                print(f"adb-mdns-discover: zeroconf cleanup failed: {exc}", file=sys.stderr)

    if not discovered:
        return fail(10, f"no {SERVICE_TYPE} services were discovered within {seconds:g}s")
    if not endpoints:
        return fail(14, "discovered services had no valid local IPv4 endpoints")
    for endpoint in sorted(endpoints):
        print(endpoint)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
