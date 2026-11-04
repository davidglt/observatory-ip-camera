/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

import java.util.ArrayDeque;
import java.util.Deque;

final class ProcessMetrics {
    static final long HISTORY_WINDOW_MILLIS = 10 * 60 * 1000L;
    static final int MAX_SAMPLES = 121;
    static final long STALE_AFTER_MILLIS = 15_000L;

    static final class Sample {
        final long elapsedMillis;
        final Double cpuPercent;
        final Long pssKb;
        final Long availableRamKb;
        final Long totalRamKb;
        final Boolean lowMemory;
        final Long uptimeMillis;

        Sample(long elapsedMillis, Double cpuPercent, Long pssKb, Long availableRamKb, Long totalRamKb,
               Boolean lowMemory, Long uptimeMillis) {
            this.elapsedMillis = elapsedMillis;
            this.cpuPercent = cpuPercent;
            this.pssKb = pssKb;
            this.availableRamKb = availableRamKb;
            this.totalRamKb = totalRamKb;
            this.lowMemory = lowMemory;
            this.uptimeMillis = uptimeMillis;
        }
    }

    static final class Snapshot {
        final Sample latest;
        final Double recentAverageCpuPercent;
        final Long observedPeakPssKb;
        final int sampleCount;
        final long sampleAgeMillis;

        Snapshot(Sample latest, Double recentAverageCpuPercent, Long observedPeakPssKb,
                 int sampleCount, long sampleAgeMillis) {
            this.latest = latest;
            this.recentAverageCpuPercent = recentAverageCpuPercent;
            this.observedPeakPssKb = observedPeakPssKb;
            this.sampleCount = sampleCount;
            this.sampleAgeMillis = sampleAgeMillis;
        }

        boolean isStale() { return latest != null && sampleAgeMillis > STALE_AFTER_MILLIS; }
    }

    private final Deque<Sample> samples = new ArrayDeque<>();
    private long previousElapsedMillis = -1;
    private long previousCpuMillis = -1;
    private Long observedPeakPssKb;

    static Double cpuPercent(long cpuDeltaMillis, long elapsedDeltaMillis) {
        if (cpuDeltaMillis < 0 || elapsedDeltaMillis <= 0) return null;
        double result = (double) cpuDeltaMillis * 100.0 / elapsedDeltaMillis;
        return Double.isFinite(result) ? result : null;
    }

    synchronized void reset() {
        samples.clear();
        previousElapsedMillis = -1;
        previousCpuMillis = -1;
        observedPeakPssKb = null;
    }

    synchronized void record(long elapsedMillis, long processCpuMillis, Long pssKb, Long availableRamKb,
                             Long totalRamKb, Boolean lowMemory, Long uptimeMillis) {
        if (elapsedMillis < 0) {
            previousElapsedMillis = -1;
            previousCpuMillis = -1;
            return;
        }
        if (!samples.isEmpty() && elapsedMillis < samples.getLast().elapsedMillis) reset();

        Double cpu = null;
        if (previousElapsedMillis >= 0 && previousCpuMillis >= 0 && processCpuMillis >= 0) {
            cpu = cpuPercent(processCpuMillis - previousCpuMillis, elapsedMillis - previousElapsedMillis);
        }
        if (processCpuMillis >= 0) {
            previousElapsedMillis = elapsedMillis;
            previousCpuMillis = processCpuMillis;
        } else {
            previousElapsedMillis = -1;
            previousCpuMillis = -1;
        }

        Long validPss = pssKb != null && pssKb >= 0 ? pssKb : null;
        Long validAvailable = availableRamKb != null && availableRamKb >= 0 ? availableRamKb : null;
        Long validTotal = totalRamKb != null && totalRamKb > 0 ? totalRamKb : null;
        if (validAvailable != null && validTotal != null && validAvailable > validTotal) validAvailable = null;
        Long validUptime = uptimeMillis != null && uptimeMillis >= 0 ? uptimeMillis : null;
        if (validPss != null && (observedPeakPssKb == null || validPss > observedPeakPssKb))
            observedPeakPssKb = validPss;

        samples.addLast(new Sample(elapsedMillis, cpu, validPss, validAvailable, validTotal,
            lowMemory, validUptime));
        prune(elapsedMillis);
    }

    synchronized Snapshot snapshot(long nowElapsedMillis) {
        Sample latest = samples.peekLast();
        if (latest == null) return new Snapshot(null, null, observedPeakPssKb, 0, -1);
        double sum = 0;
        int count = 0;
        for (Sample sample : samples) {
            if (sample.cpuPercent != null) {
                sum += sample.cpuPercent;
                count++;
            }
        }
        return new Snapshot(latest, count == 0 ? null : sum / count, observedPeakPssKb,
            samples.size(), Math.max(0, nowElapsedMillis - latest.elapsedMillis));
    }

    private void prune(long nowElapsedMillis) {
        while (!samples.isEmpty() && nowElapsedMillis - samples.getFirst().elapsedMillis > HISTORY_WINDOW_MILLIS)
            samples.removeFirst();
        while (samples.size() > MAX_SAMPLES) samples.removeFirst();
    }
}
