/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

import java.io.File;
import java.nio.file.Files;

/** Dependency-free test. Run: bash test/run.sh */
public final class ContinuousStatsTest {
    private static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); }
    private static final long S = 1_000_000_000L;

    public static void main(String[] args) throws Exception {
        FrameStats s = new FrameStats();
        check(Double.isNaN(s.fps()) && s.ageNanos(5 * S) == -1, "no frames");
        check(Double.isNaN(s.fpsWith(S)), "one frame not enough");
        s.recordFrame(S, 100_000_000L);
        check(Double.isNaN(s.fps()), "single frame");
        check(Math.abs(s.fpsWith(3 * S) - 0.5) < 1e-9, "projected FPS for next frame");
        s.recordFrame(3 * S, 300_000_000L);
        check(Math.abs(s.fps() - 0.5) < 1e-9, "0.5 FPS measured");
        for (int i = 3; i <= 30; i++) s.recordFrame(i * S, 200_000_000L);
        check(Math.abs(s.fps() - 1.0) < 1e-9 && s.frames() == 30, "window slides to recent 1 FPS");
        check(Math.abs(s.fpsWith(31 * S) - 1.0) < 1e-9, "projection with full window");
        check(s.ageNanos(32 * S) == 2 * S && s.ageNanos(0) == 0, "age monotonic, never negative");
        check(s.maxProcessingNanos() == 300_000_000L && s.lastProcessingNanos() == 200_000_000L, "processing");
        s.recordFailure(); s.recordLate();
        check(s.failures() == 1 && s.late() == 1, "counters");

        ContinuousStatus st = new ContinuousStatus("running", 30, 1.0, 10, 0, 30 * S, 1_767_225_600_000L, "UTC", 200_000_000L, 190_000_000L, 300_000_000L, 12345, "rendered", 1, 1, null);
        String d = st.describe(32 * S);
        check(d.contains("state=running frames=30 actual_fps=1.00 target_fps=1.0") && d.contains("last_capture=2026-01-01T00:00:00+0000 tz=UTC age_ms=2000")
            && d.contains("proc_avg_ms=190") && d.contains("overlay_mode=rendered") && !d.contains("lastError"), d);
        check(ContinuousStatus.stopped().describe(5 * S).contains("last_capture=none age_ms=none") && ContinuousStatus.stopped().describe(0).contains("state=stopped"), "stopped never shows a capture");
        check(st.withState("error", "boom").describe(32 * S).endsWith("lastError=boom") && !st.withState("stopped", null).isActive(), "state change");

        CameraArbiter a = new CameraArbiter();
        check(a.acquire(CameraArbiter.SINGLE) && !a.acquire(CameraArbiter.CONTINUOUS) && !a.acquire(CameraArbiter.SINGLE), "single owner");
        a.release(CameraArbiter.CONTINUOUS);
        check(a.owner().equals(CameraArbiter.SINGLE), "foreign release ignored");
        a.release(CameraArbiter.SINGLE);
        check(a.owner().equals(CameraArbiter.NONE) && a.acquire(CameraArbiter.CONTINUOUS), "release then acquire");

        File dir = Files.createTempDirectory("snap").toFile();
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3, (byte) 0xFF, (byte) 0xD9};
        check(SnapshotPublisher.publish(dir, "snapshot.jpg", jpeg, () -> true), "published");
        File published = new File(dir, "snapshot.jpg");
        check(java.util.Arrays.equals(Files.readAllBytes(published.toPath()), jpeg)
            && published.lastModified() > 0 && !new File(dir, "snapshot.tmp").exists(),
            "complete file records its successful publication time, no temp left");
        byte[] newer = {(byte) 0xFF, (byte) 0xD8, 9, 9, 9, (byte) 0xFF, (byte) 0xD9};
        check(!SnapshotPublisher.publish(dir, "snapshot.jpg", newer, () -> false), "stale session not published");
        check(java.util.Arrays.equals(Files.readAllBytes(new File(dir, "snapshot.jpg").toPath()), jpeg) && !new File(dir, "snapshot.tmp").exists(), "old file intact, temp removed");
        boolean threw = false;
        try { SnapshotPublisher.publish(dir, "snapshot.jpg", new byte[]{1, 2, 3, 4}, () -> true); } catch (java.io.IOException e) { threw = true; }
        check(threw, "non-JPEG rejected");
        new File(dir, "snapshot.jpg").delete(); dir.delete();
        System.out.println("ContinuousStatsTest passed");
    }
}
