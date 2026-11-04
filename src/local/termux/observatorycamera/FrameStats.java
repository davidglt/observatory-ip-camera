/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

/**
 * Capture-rate and processing statistics for one continuous session. All times are monotonic
 * nanoseconds (SystemClock.elapsedRealtimeNanos), never wall-clock, so clock changes cannot distort
 * rates or ages. FPS is measured over a sliding window of the most recent frames. Thread-safe.
 */
final class FrameStats {
    static final int WINDOW = 10;
    private final long[] times = new long[WINDOW];
    private int count;
    private long frames;
    private long failures;
    private long late;
    private long lastCaptureNanos = -1;
    private long lastProcessingNanos;
    private long totalProcessingNanos;
    private long maxProcessingNanos;

    synchronized void recordFrame(long captureNanos, long processingNanos) {
        times[(int) (frames % WINDOW)] = captureNanos;
        frames++;
        count = (int) Math.min(frames, WINDOW);
        lastCaptureNanos = captureNanos;
        lastProcessingNanos = processingNanos;
        totalProcessingNanos += processingNanos;
        maxProcessingNanos = Math.max(maxProcessingNanos, processingNanos);
    }

    synchronized void recordFailure() { failures++; }
    synchronized void recordLate() { late++; }

    synchronized long frames() { return frames; }
    synchronized long failures() { return failures; }
    synchronized long late() { return late; }
    synchronized long lastCaptureNanos() { return lastCaptureNanos; }
    synchronized long lastProcessingNanos() { return lastProcessingNanos; }
    synchronized long averageProcessingNanos() { return frames == 0 ? 0 : totalProcessingNanos / frames; }
    synchronized long maxProcessingNanos() { return maxProcessingNanos; }

    /** Measured FPS over the recorded window; NaN with fewer than two frames. */
    synchronized double fps() { return compute(count, -1); }

    /** FPS as it will be once a frame captured at nextCaptureNanos is recorded (used for that frame's overlay). */
    synchronized double fpsWith(long nextCaptureNanos) { return compute(Math.min(count, WINDOW - 1), nextCaptureNanos); }

    /** Rate over the newest `recorded` stored timestamps plus an optional extra newer one (extra < 0: none). */
    private double compute(int recorded, long extra) {
        int n = recorded + (extra < 0 ? 0 : 1);
        if (n < 2) return Double.NaN;
        long first = recorded > 0 ? times[(int) ((frames - recorded) % WINDOW)] : extra;
        long last = extra >= 0 ? extra : times[(int) ((frames - 1) % WINDOW)];
        long span = last - first;
        return span <= 0 ? Double.NaN : (n - 1) * 1e9 / span;
    }

    /** Age of the newest frame at nowNanos, or -1 when no frame has been captured this session. */
    synchronized long ageNanos(long nowNanos) { return lastCaptureNanos < 0 ? -1 : Math.max(0, nowNanos - lastCaptureNanos); }
}
