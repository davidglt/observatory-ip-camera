/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

/** Re-subscribes after each successful initial/reconnected connection and rejects late callbacks on stop. */
final class MqttSubscriptionLifecycle {
    interface Subscribe { void run() throws Exception; }
    private boolean active = true;

    synchronized boolean connected(Subscribe subscribe) {
        if (!active) return false;
        try {
            subscribe.run();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    synchronized boolean isActive() { return active; }
    synchronized void stop() { active = false; }
}
