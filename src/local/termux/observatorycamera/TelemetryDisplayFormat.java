/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

import java.util.Locale;

final class TelemetryDisplayFormat {
    private TelemetryDisplayFormat() {}

    static String memoryKilobytes(Number kilobytes) {
        double value = kilobytes.doubleValue();
        String unit = "KB";
        if (value >= 1_000_000) {
            value /= 1_000_000;
            unit = "GB";
        } else if (value >= 1_000) {
            value /= 1_000;
            unit = "MB";
        }
        return String.format(Locale.ROOT, "%.1f %s", value, unit);
    }

    static String fps(Double value) {
        return value == null ? "--" : String.format(Locale.ROOT, "%.1f", value);
    }
}
