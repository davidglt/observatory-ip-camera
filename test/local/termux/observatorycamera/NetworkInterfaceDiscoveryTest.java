/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class NetworkInterfaceDiscoveryTest {
    public static void main(String[] args) throws Exception {
        List<NetworkInterfaceDiscovery.Address> addresses = NetworkInterfaceDiscovery.discover();
        Set<String> seen = new HashSet<>();
        String previous = null;
        for (NetworkInterfaceDiscovery.Address address : addresses) {
            check(!address.value.startsWith("127.") && !address.value.equals("0.0.0.0"),
                "loopback and wildcard are not discovered as concrete interfaces");
            check(seen.add(address.value), "one entry per IPv4 address");
            if (previous != null) check(compareIpv4(previous, address.value) < 0, "deterministic numeric ordering");
            check(!address.interfaceName.isEmpty(), "actual interface name included");
            previous = address.value;
        }
        System.out.println("NetworkInterfaceDiscoveryTest passed");
    }

    private static int compareIpv4(String first, String second) {
        String[] a = first.split("\\."), b = second.split("\\.");
        for (int i = 0; i < 4; i++) {
            int compared = Integer.compare(Integer.parseInt(a[i]), Integer.parseInt(b[i]));
            if (compared != 0) return compared;
        }
        return 0;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
