package local.termux.observatorycamera;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Immutable Camera2 JPEG-output metadata and independent JPEG size validation. */
public final class CameraCapabilities {
    public static final long MAX_JPEG_PIXELS = 8_000_000L;
    public static final int MAX_JPEG_BYTES = 16 * 1024 * 1024;
    private static final long DEFAULT_PIXELS = 1280L * 720L;

    public static final class JpegSize {
        public final int width;
        public final int height;
        public final long minFrameDurationNanos;
        public final long stallDurationNanos;

        public JpegSize(int width, int height, long minFrameDurationNanos, long stallDurationNanos) {
            if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid JPEG size.");
            this.width = width;
            this.height = height;
            this.minFrameDurationNanos = minFrameDurationNanos;
            this.stallDurationNanos = stallDurationNanos;
        }

        public String value() { return width + "x" + height; }
        public long estimatedIntervalNanos() {
            if (minFrameDurationNanos <= 0 || stallDurationNanos < 0) return 0;
            if (Long.MAX_VALUE - minFrameDurationNanos < stallDurationNanos) return 0;
            return minFrameDurationNanos + stallDurationNanos;
        }
        public boolean hasTimingEstimate() { return estimatedIntervalNanos() > 0; }
        public long metadataEstimateFpsTenths() {
            long interval = estimatedIntervalNanos();
            if (interval <= 0) return 0;
            return 10_000_000_000L / interval;
        }
    }

    private final List<JpegSize> sizes;
    public final CameraControlCapabilities controls;

    public CameraCapabilities(List<JpegSize> sizes) {
        this(sizes, null);
    }

    public CameraCapabilities(List<JpegSize> sizes, CameraControlCapabilities controls) {
        if (sizes == null || sizes.isEmpty()) throw new IllegalArgumentException("No JPEG output sizes.");
        ArrayList<JpegSize> copy = new ArrayList<>(sizes);
        Collections.sort(copy, Comparator.comparingLong((JpegSize s) -> (long) s.width * s.height)
            .thenComparingInt(s -> s.width).thenComparingInt(s -> s.height));
        this.sizes = Collections.unmodifiableList(copy);
        this.controls = controls;
    }

    public List<JpegSize> sizes() { return sizes; }

    public JpegSize find(String value) {
        for (JpegSize size : sizes) if (size.value().equals(value)) return size;
        return null;
    }

    public JpegSize select(CaptureConfig config) {
        if (!config.jpegSize.equals("auto")) return find(config.jpegSize);
        JpegSize nearest = sizes.get(0);
        for (JpegSize size : sizes) {
            long pixels = (long) size.width * size.height;
            long current = (long) nearest.width * nearest.height;
            if (Math.abs(pixels - DEFAULT_PIXELS) < Math.abs(current - DEFAULT_PIXELS)) nearest = size;
        }
        return nearest;
    }

    public String validate(CaptureConfig config) {
        JpegSize selected = select(config);
        if (selected == null) return "unsupported-jpeg_size:" + config.jpegSize;
        if ((long) selected.width * selected.height > MAX_JPEG_PIXELS)
            return "unsupported-jpeg_size:application-limit-" + MAX_JPEG_PIXELS + "-pixels";
        if (controls != null) {
            String controlError = controls.validate(config, selected.minFrameDurationNanos);
            if (controlError != null) return controlError;
        }
        return null;
    }

    public static String formatFps(long tenths) {
        return String.format(java.util.Locale.ROOT, "%d.%d", tenths / 10, tenths % 10);
    }
}
