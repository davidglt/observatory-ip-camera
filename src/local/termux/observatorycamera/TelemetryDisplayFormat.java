/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

import java.util.Locale;

final class TelemetryDisplayFormat {
    private TelemetryDisplayFormat() {}

    // Android PSS and RAM samples use 1024-byte units; display decimal KB/MB/GB.
    static String memoryKilobytes(Number kilobytes) {
        if (kilobytes == null) return "Unavailable";
        double input = kilobytes.doubleValue();
        if (!Double.isFinite(input) || input < 0) return "Unavailable";
        double value = input * 1.024;
        if (!Double.isFinite(value)) return "Unavailable";
        String[] units = {"KB", "MB", "GB"};
        int unit = 0;
        while (unit < units.length - 1 && value >= 999.95) {
            value /= 1000.0;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    static String fps(Double value) {
        return value == null || !Double.isFinite(value) || value < 0
            ? "--" : String.format(Locale.ROOT, "%.1f", value);
    }
}
