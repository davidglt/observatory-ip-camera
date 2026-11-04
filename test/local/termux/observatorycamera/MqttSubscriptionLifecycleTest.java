/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

public final class MqttSubscriptionLifecycleTest {
    public static void main(String[] args) {
        MqttSubscriptionLifecycle lifecycle = new MqttSubscriptionLifecycle();
        int[] subscriptions = {0};
        check(lifecycle.connected(() -> subscriptions[0]++), "initial connect subscribes");
        check(lifecycle.connected(() -> subscriptions[0]++), "reconnect resubscribes");
        check(subscriptions[0] == 2, "one ordinary subscription on each connection");
        lifecycle.stop();
        check(!lifecycle.connected(() -> subscriptions[0]++), "cleanup rejects late reconnect");
        check(!lifecycle.isActive() && subscriptions[0] == 2, "cleanup prevents further subscriptions");
        System.out.println("MqttSubscriptionLifecycleTest passed");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
