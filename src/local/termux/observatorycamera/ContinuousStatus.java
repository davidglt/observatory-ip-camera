package local.termux.observatorycamera;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Immutable snapshot of the continuous capture session for SSH reporting. Rates and ages come from
 * monotonic time; last_capture is a human wall-clock timestamp (with the device timezone) captured
 * together with the frame, so a stopped or stalled session can never look like a fresh one.
 */
public final class ContinuousStatus {
    public static final String STOPPED = "stopped";
    public static final String STARTING = "starting";
    public static final String RUNNING = "running";
    public static final String ERROR = "error";

    public final String state;
    public final long frames;
    public final double actualFps;
    public final long targetFpsTenths;
    public final long sessionStartNanos;
    public final long lastCaptureNanos;
    public final long lastCaptureWallMillis;
    public final String zoneId;
    public final long procLastNanos;
    public final long procAvgNanos;
    public final long procMaxNanos;
    public final long lastBytes;
    public final String overlayMode;
    public final long late;
    public final long failures;
    public final String lastError;

    public ContinuousStatus(String state, long frames, double actualFps, long targetFpsTenths, long sessionStartNanos,
                            long lastCaptureNanos, long lastCaptureWallMillis, String zoneId, long procLastNanos,
                            long procAvgNanos, long procMaxNanos, long lastBytes, String overlayMode, long late,
                            long failures, String lastError) {
        this.state = state;
        this.frames = frames;
        this.actualFps = actualFps;
        this.targetFpsTenths = targetFpsTenths;
        this.sessionStartNanos = sessionStartNanos;
        this.lastCaptureNanos = lastCaptureNanos;
        this.lastCaptureWallMillis = lastCaptureWallMillis;
        this.zoneId = zoneId;
        this.procLastNanos = procLastNanos;
        this.procAvgNanos = procAvgNanos;
        this.procMaxNanos = procMaxNanos;
        this.lastBytes = lastBytes;
        this.overlayMode = overlayMode;
        this.late = late;
        this.failures = failures;
        this.lastError = lastError;
    }

    public static ContinuousStatus stopped() {
        return new ContinuousStatus(STOPPED, 0, Double.NaN, 0, -1, -1, 0, "-", 0, 0, 0, 0, "-", 0, 0, null);
    }

    public ContinuousStatus withState(String newState, String error) {
        return new ContinuousStatus(newState, frames, actualFps, targetFpsTenths, sessionStartNanos, lastCaptureNanos,
            lastCaptureWallMillis, zoneId, procLastNanos, procAvgNanos, procMaxNanos, lastBytes, overlayMode, late, failures, error);
    }

    public boolean isActive() { return state.equals(STARTING) || state.equals(RUNNING); }

    /** Greppable key=value detail; nowNanos is monotonic time of the query, so age is always current. */
    public String describe(long nowNanos) {
        StringBuilder sb = new StringBuilder();
        sb.append("state=").append(state);
        sb.append(" frames=").append(frames);
        sb.append(" actual_fps=").append(Double.isNaN(actualFps) ? "n/a" : String.format(Locale.ROOT, "%.2f", actualFps));
        sb.append(" target_fps=").append(String.format(Locale.ROOT, "%d.%d", targetFpsTenths / 10, targetFpsTenths % 10));
        if (sessionStartNanos >= 0) sb.append(" uptime_s=").append(String.format(Locale.ROOT, "%.1f", Math.max(0, nowNanos - sessionStartNanos) / 1e9));
        if (lastCaptureNanos < 0) {
            sb.append(" last_capture=none age_ms=none");
        } else {
            sb.append(" last_capture=").append(isoTime(lastCaptureWallMillis, zoneId));
            sb.append(" tz=").append(zoneId);
            sb.append(" age_ms=").append(Math.max(0, nowNanos - lastCaptureNanos) / 1_000_000L);
        }
        sb.append(" proc_last_ms=").append(procLastNanos / 1_000_000L);
        sb.append(" proc_avg_ms=").append(procAvgNanos / 1_000_000L);
        sb.append(" proc_max_ms=").append(procMaxNanos / 1_000_000L);
        sb.append(" bytes=").append(lastBytes);
        sb.append(" overlay_mode=").append(overlayMode);
        sb.append(" late=").append(late);
        sb.append(" failures=").append(failures);
        if (lastError != null) sb.append(" lastError=").append(lastError);
        return sb.toString();
    }

    static String isoTime(long wallMillis, String zoneId) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone(zoneId));
        return format.format(new Date(wallMillis));
    }
}
