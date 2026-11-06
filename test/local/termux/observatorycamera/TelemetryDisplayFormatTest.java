/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

import java.util.Locale;

public final class TelemetryDisplayFormatTest {
    public static void main(String[] args) {
        check(TelemetryDisplayFormat.memoryKilobytes(0L).equals("0.0 KB"), "zero");
        check(TelemetryDisplayFormat.memoryKilobytes(1L).equals("1.0 KB"), "binary input to decimal KB");
        check(TelemetryDisplayFormat.memoryKilobytes(976L).equals("999.4 KB"), "below MB boundary");
        check(TelemetryDisplayFormat.memoryKilobytes(977L).equals("1.0 MB"), "above MB boundary");
        check(TelemetryDisplayFormat.memoryKilobytes(1000L).equals("1.0 MB"), "decimal MB display");
        check(TelemetryDisplayFormat.memoryKilobytes(1024L).equals("1.0 MB"), "MiB input");
        check(TelemetryDisplayFormat.memoryKilobytes(976513L).equals("999.9 MB"), "below rounded GB boundary");
        check(TelemetryDisplayFormat.memoryKilobytes(976514L).equals("1.0 GB"), "promote rounded 1000 MB");
        check(TelemetryDisplayFormat.memoryKilobytes(1048576L).equals("1.1 GB"), "GiB input to decimal GB");
        for (Number invalid : new Number[]{null, -1L, Double.NaN, Double.POSITIVE_INFINITY})
            check(TelemetryDisplayFormat.memoryKilobytes(invalid).equals("Unavailable"), "invalid memory");
        check(TelemetryDisplayFormat.fps(1.46).equals("1.5")
            && TelemetryDisplayFormat.fps(2.0).equals("2.0")
            && TelemetryDisplayFormat.fps(null).equals("--")
            && TelemetryDisplayFormat.fps(Double.NaN).equals("--")
            && TelemetryDisplayFormat.fps(-1.0).equals("--"), "FPS formatting");
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            check(TelemetryDisplayFormat.fps(1.5).equals("1.5"), "locale-independent FPS");
            check(TelemetryDisplayFormat.memoryKilobytes(1048576L).equals("1.1 GB"), "locale-independent memory");
        } finally {
            Locale.setDefault(previous);
        }
        System.out.println("TelemetryDisplayFormatTest passed");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
