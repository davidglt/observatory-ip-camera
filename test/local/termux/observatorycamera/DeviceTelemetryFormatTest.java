/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

public final class DeviceTelemetryFormatTest {
    public static void main(String[] args) {
        check(DeviceTelemetryFormat.batteryPercent(0, 100) == 0, "zero battery is measured zero");
        check(DeviceTelemetryFormat.batteryPercent(50, 100) == 50, "battery percentage conversion");
        check(DeviceTelemetryFormat.batteryPercent(-1, 100) == null
            && DeviceTelemetryFormat.batteryPercent(1, 0) == null, "unavailable battery is not zero");
        check(DeviceTelemetryFormat.batteryTemperatureC(251, Integer.MIN_VALUE) == 25.1
            && DeviceTelemetryFormat.batteryTemperatureC(Integer.MIN_VALUE, Integer.MIN_VALUE) == null,
            "battery temperature conversion and unavailable distinction");
        check(DeviceTelemetryFormat.charging(2).equals("charging")
            && DeviceTelemetryFormat.charging(5).equals("full")
            && DeviceTelemetryFormat.charging(1).equals("unknown")
            && DeviceTelemetryFormat.charging(-1).equals("unavailable"), "charging status conversion");
        check(DeviceTelemetryFormat.thermal(3, true).equals("severe")
            && DeviceTelemetryFormat.thermal(-1, true).equals("unavailable")
            && DeviceTelemetryFormat.thermal(-1, false).equals("unsupported"),
            "thermal severity is not presented as CPU temperature");
        System.out.println("DeviceTelemetryFormatTest passed");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
