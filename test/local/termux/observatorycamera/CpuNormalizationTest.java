/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

public final class CpuNormalizationTest {
    public static void main(String[] args) {
        near(CpuNormalization.percent(25.0, 1), 25.0, "single CPU");
        near(CpuNormalization.percent(200.0, 8), 25.0, "eight CPUs");
        near(CpuNormalization.percent(800.0, 8), 100.0, "all CPUs busy");
        near(CpuNormalization.percent(0.0, 8), 0.0, "valid zero");
        near(CpuNormalization.percent(900.0, 8), 112.5, "do not silently clamp input");
        for (Integer count : new Integer[]{null, 0, -1})
            check(CpuNormalization.percent(100.0, count) == null, "invalid CPU count");
        for (Double value : new Double[]{null, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
            check(CpuNormalization.percent(value, 8) == null, "invalid CPU value");
        CpuNormalization.Reading reading = new CpuNormalization.Reading(200.0, 120.0, 8, true);
        near(reading.currentPercent, 25.0, "current uses shared count");
        near(reading.recentAveragePercent, 15.0, "average uses shared count");
        check(reading.logicalCpuCount == 8 && reading.stale, "count and stale state retained");
        CpuNormalization.Reading collecting = new CpuNormalization.Reading(null, null, 8, false);
        check(collecting.currentPercent == null && collecting.recentAveragePercent == null
            && !collecting.stale, "collecting stays unavailable, not zero");
        CpuNormalization.Reading invalid = new CpuNormalization.Reading(100.0, 50.0, 0, false);
        check(invalid.logicalCpuCount == null && invalid.currentPercent == null
            && invalid.recentAveragePercent == null, "invalid capacity invalidates both values");
        CpuNormalization.Reading partial = new CpuNormalization.Reading(null, 80.0, 8, true);
        check(partial.currentPercent == null && partial.stale, "partial stale sample");
        near(partial.recentAveragePercent, 10.0, "valid average retained");
        System.out.println("CpuNormalizationTest passed");
    }

    private static void near(Double actual, double expected, String message) {
        check(actual != null && Math.abs(actual - expected) < 0.000001, message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
