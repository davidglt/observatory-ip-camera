/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

/** CPU-time normalization, not frequency-weighted capacity. Raw inputs use one CPU as 100%. */
final class CpuNormalization {
    private CpuNormalization() {}

    static Double percent(Double oneCpuPercent, Integer logicalCpuCount) {
        if (oneCpuPercent == null || !Double.isFinite(oneCpuPercent) || oneCpuPercent < 0
            || logicalCpuCount == null || logicalCpuCount <= 0) return null;
        return oneCpuPercent / logicalCpuCount;
    }

    /** Both values use the same explicit capacity basis; callers must supply a suitable count. */
    static final class Reading {
        final Integer logicalCpuCount;
        final Double currentPercent;
        final Double recentAveragePercent;
        final boolean stale;

        Reading(Double currentOneCpuPercent, Double averageOneCpuPercent, Integer logicalCpuCount,
                boolean stale) {
            this.logicalCpuCount = logicalCpuCount != null && logicalCpuCount > 0 ? logicalCpuCount : null;
            this.currentPercent = percent(currentOneCpuPercent, this.logicalCpuCount);
            this.recentAveragePercent = percent(averageOneCpuPercent, this.logicalCpuCount);
            this.stale = stale;
        }
    }
}
