/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Snapshot of concrete IPv4 addresses currently assigned to non-loopback interfaces. */
final class NetworkInterfaceDiscovery {
    static final class Address {
        final String value;
        final String interfaceName;
        Address(String value, String interfaceName) {
            this.value = value;
            this.interfaceName = interfaceName;
        }
    }

    private NetworkInterfaceDiscovery() {}

    static List<Address> discover() throws SocketException {
        Enumeration<NetworkInterface> available = NetworkInterface.getNetworkInterfaces();
        if (available == null) return Collections.emptyList();
        List<NetworkInterface> interfaces = Collections.list(available);
        interfaces.sort((a, b) -> a.getName().compareTo(b.getName()));
        Map<String, String> byAddress = new TreeMap<>((a, b) -> compareIpv4(a, b));
        for (NetworkInterface network : interfaces) {
            if (network.isLoopback()) continue;
            Enumeration<InetAddress> addresses = network.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (!(address instanceof Inet4Address) || address.isLoopbackAddress() || address.isAnyLocalAddress()) continue;
                byAddress.putIfAbsent(address.getHostAddress(), network.getName());
            }
        }
        List<Address> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : byAddress.entrySet())
            result.add(new Address(entry.getKey(), entry.getValue()));
        return result;
    }

    private static int compareIpv4(String first, String second) {
        String[] a = first.split("\\."), b = second.split("\\.");
        for (int i = 0; i < 4; i++) {
            int compared = Integer.compare(Integer.parseInt(a[i]), Integer.parseInt(b[i]));
            if (compared != 0) return compared;
        }
        return 0;
    }
}
