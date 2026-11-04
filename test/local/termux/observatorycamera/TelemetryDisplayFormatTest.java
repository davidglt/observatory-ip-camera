/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

public final class TelemetryDisplayFormatTest {
    public static void main(String[] args) {
        check(TelemetryDisplayFormat.memoryKilobytes(999L).equals("999.0 KB"), "KB below decimal MB boundary");
        check(TelemetryDisplayFormat.memoryKilobytes(1_000L).equals("1.0 MB"), "decimal KB to MB conversion");
        check(TelemetryDisplayFormat.memoryKilobytes(999_999L).equals("1000.0 MB"), "MB below decimal GB boundary");
        check(TelemetryDisplayFormat.memoryKilobytes(1_000_000L).equals("1.0 GB"), "decimal KB to GB conversion");
        check(TelemetryDisplayFormat.fps(1.46).equals("1.5")
            && TelemetryDisplayFormat.fps(2.0).equals("2.0")
            && TelemetryDisplayFormat.fps(null).equals("--"), "FPS uses one decimal and preserves unavailable state");
        System.out.println("TelemetryDisplayFormatTest passed");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
