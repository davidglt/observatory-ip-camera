/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

public final class ProcessMetricsTest {
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }

    private static void near(Double actual, double expected, String name) {
        check(actual != null && Math.abs(actual - expected) < 0.0001, name);
    }

    public static void main(String[] args) {
        near(ProcessMetrics.cpuPercent(250, 1000), 25.0, "single-core CPU percentage");
        near(ProcessMetrics.cpuPercent(2500, 1000), 250.0, "multicore CPU may exceed 100 percent");
        check(ProcessMetrics.cpuPercent(-1, 1000) == null
            && ProcessMetrics.cpuPercent(1, 0) == null
            && ProcessMetrics.cpuPercent(1, -1) == null, "invalid CPU deltas are unavailable");

        ProcessMetrics metrics = new ProcessMetrics();
        metrics.record(0, 100, 100L, 500L, 1000L, false, 50L);
        metrics.record(5000, 225, 125L, 450L, 1000L, false, 5050L);
        metrics.record(10000, 375, -1L, 1100L, 1000L, true, 10050L);
        ProcessMetrics.Snapshot recent = metrics.snapshot(10000);
        near(recent.latest.cpuPercent, 3.0, "current sample reflects CPU delta");
        near(recent.recentAverageCpuPercent, 2.75, "recent average uses valid samples");
        check(recent.latest.pssKb == null && recent.latest.availableRamKb == null
            && recent.latest.totalRamKb == 1000L && Boolean.TRUE.equals(recent.latest.lowMemory),
            "invalid and partial readings remain unavailable");
        check(recent.observedPeakPssKb == 125L, "observed PSS peak tracks valid samples");

        for (int i = 3; i <= 150; i++)
            metrics.record(i * 5000L, 375 + i * 75L, (long) i, 500L, 1000L, false, (long) i * 5000L);
        ProcessMetrics.Snapshot bounded = metrics.snapshot(750000);
        check(bounded.sampleCount == ProcessMetrics.MAX_SAMPLES, "sample count is bounded");
        check(bounded.observedPeakPssKb == 150L, "PSS peak is observed across retained history");
        check(metrics.snapshot(750000 + ProcessMetrics.STALE_AFTER_MILLIS + 1).isStale(),
            "oldest/latest sample age is marked stale");

        metrics.reset();
        ProcessMetrics.Snapshot restarted = metrics.snapshot(0);
        check(restarted.latest == null && restarted.observedPeakPssKb == null && restarted.sampleCount == 0,
            "restart clears process history and observed peak");
        metrics.record(0, 1, 1L, 1L, 2L, false, 0L);
        metrics.record(ProcessMetrics.HISTORY_WINDOW_MILLIS + 1, 2, 2L, 1L, 2L, false,
            ProcessMetrics.HISTORY_WINDOW_MILLIS + 1);
        check(metrics.snapshot(ProcessMetrics.HISTORY_WINDOW_MILLIS + 1).sampleCount == 1,
            "samples older than ten minutes are removed");
        System.out.println("ProcessMetricsTest passed");
    }
}
