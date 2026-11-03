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
            new CameraCapabilities.JpegSize(4000, 3000, 100_000_000L, 0)));
        check(caps.find("1280x720").metadataEstimateFpsTenths() == 20, "minimum frame plus stall estimate");
        check(caps.applicationCapFpsTenths(config("jpeg_size=1280x720")) == 20, "metadata cap");
        check(caps.validate(config("jpeg_size=1280x720 target_fps=2")) == null, "supported request");
        check(caps.validate(config("jpeg_size=1280x720 target_fps=2.1")).startsWith("unsupported-target_fps"), "reject above metadata cap");
        check(caps.validate(config("jpeg_size=1024x768")).startsWith("unsupported-jpeg_size"), "reject unadvertised size");
        check(caps.find("4000x3000") != null
            && caps.validate(config("jpeg_size=4000x3000")).startsWith("unsupported-jpeg_size:application-limit"),
            "enumerate but reject output above bounded app pixel limit");
        check(caps.validate(config("jpeg_size=640x480 target_fps=1")) == null, "stall-free metadata");

        CameraCapabilities.JpegSize unknown = caps.find("1920x1080");
        check(!unknown.hasTimingEstimate() && unknown.metadataEstimateFpsTenths() == 0, "zero metadata remains unknown");
        check(caps.applicationCapFpsTenths(config("jpeg_size=1920x1080")) == CameraCapabilities.APPLICATION_MAX_FPS_TENTHS,
            "unknown timing uses documented app limit");
        check(caps.validate(config("jpeg_size=1920x1080 target_fps=3")) == null, "unknown timing bounded by app limit");
        check(CaptureConfig.parse("jpeg_size=1920x1080 target_fps=3.1", CaptureConfig.defaults()).config == null,
            "syntax cap remains bounded");

        CameraCapabilities overflow = new CameraCapabilities(Arrays.asList(
            new CameraCapabilities.JpegSize(1, 1, Long.MAX_VALUE, 2)));
        check(!overflow.sizes().get(0).hasTimingEstimate(), "overflow timing remains unknown");
        check(caps.select(config("jpeg_size=auto")).value().equals("1280x720"), "legacy auto resolution target");
        System.out.println("CameraCapabilitiesTest passed");
    }
}
