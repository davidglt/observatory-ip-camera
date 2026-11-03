package local.termux.observatorycamera;

import java.util.Arrays;

/** Dependency-free Camera2 metadata and capability validation test. */
public final class CameraCapabilitiesTest {
    private static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); }

    private static CaptureConfig config(String settings) {
        CaptureConfig.Parsed parsed = CaptureConfig.parse(settings, CaptureConfig.defaults());
        check(parsed.config != null, "parse " + settings);
        return parsed.config;
    }

    public static void main(String[] args) {
        CameraCapabilities caps = new CameraCapabilities(Arrays.asList(
            new CameraCapabilities.JpegSize(1280, 720, 400_000_000L, 100_000_000L),
            new CameraCapabilities.JpegSize(640, 480, 1_000_000_000L, 0),
            new CameraCapabilities.JpegSize(1920, 1080, 0, 0),
            new CameraCapabilities.JpegSize(4000, 3000, 100_000_000L, 0),
            new CameraCapabilities.JpegSize(1, 1, 1, 0)));
        check(caps.find("1280x720").metadataEstimateFpsTenths() == 20, "minimum frame plus stall estimate");
        check(caps.validate(config("jpeg_size=1280x720 target_fps=15.5")) == null, "metadata estimate does not reject target");
        check(caps.validate(config("jpeg_size=640x480 target_fps=10")) == null, "target above low metadata estimate accepted");
        check(caps.validate(config("jpeg_size=1024x768")).startsWith("unsupported-jpeg_size"), "reject unadvertised size");
        check(caps.find("4000x3000") != null
            && caps.validate(config("jpeg_size=4000x3000")).startsWith("unsupported-jpeg_size:application-limit"),
            "enumerate but reject output above bounded app pixel limit");
        check(caps.validate(config("jpeg_size=640x480 target_fps=30")) == null, "stall-free metadata is informational");

        CameraCapabilities.JpegSize unknown = caps.find("1920x1080");
        check(!unknown.hasTimingEstimate() && unknown.metadataEstimateFpsTenths() == 0, "zero metadata remains unknown");
        check(caps.validate(config("jpeg_size=1920x1080 target_fps=30")) == null, "unknown timing does not restrict target");
        check(caps.find("1x1").metadataEstimateFpsTenths() == 10_000_000_000L, "large estimate is represented without overflow");
        check(caps.validate(config("jpeg_size=1x1 target_fps=922337203685477580.7")) == null,
            "largest representable target validates");
        check(CameraCapabilities.MAX_JPEG_PIXELS == 8_000_000L
            && CameraCapabilities.MAX_JPEG_BYTES == 16 * 1024 * 1024, "independent JPEG protections unchanged");

        CameraCapabilities overflow = new CameraCapabilities(Arrays.asList(
            new CameraCapabilities.JpegSize(1, 1, Long.MAX_VALUE, 2)));
        check(!overflow.sizes().get(0).hasTimingEstimate(), "overflow timing remains unknown");
        check(caps.select(config("jpeg_size=auto")).value().equals("1280x720"), "legacy auto resolution target");
        System.out.println("CameraCapabilitiesTest passed");
    }
}
