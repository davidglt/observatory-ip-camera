package local.termux.observatorycamera;

import java.util.List;
import java.util.TimeZone;

/** Dependency-free test. Run: bash test/run.sh */
public final class OverlayTest {
    private static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); }

    public static void main(String[] args) {
        CaptureConfig d = CaptureConfig.defaults();
        check(OverlayFormat.textColor(d).equals("#00FF00"), "legacy green preserves the existing overlay appearance");
        check(OverlayFormat.textColor(CaptureConfig.parse("text_color=#12aBcD", d).config).equals("#12ABCD"),
            "renderer color selection uses the configured canonical RGB value");
        TimeZone utc = TimeZone.getTimeZone("UTC");
        long t = 1_767_225_600_000L; // 2026-01-01T00:00:00Z
        List<String> l = OverlayFormat.lines(d, 4.2, t, utc);
        check(l.size() == 3 && l.get(0).equals("NYX") && l.get(1).equals("2026-01-01 00:00:00 UTC")
            && l.get(2).equals("FPS: 4.2 / 1.0"), l.toString());
        check(OverlayFormat.dateTime(t, TimeZone.getTimeZone("Asia/Tokyo")).equals("2026-01-01 09:00:00 JST"), "timezone follows the zone");
        CaptureConfig high = CaptureConfig.parse("target_fps=10.0", d).config;
        check(OverlayFormat.lines(high, 4.2, t, utc).get(2).equals("FPS: 4.2 / 10.0"),
            "actual and requested FPS are distinct");
        check(OverlayFormat.lines(d, Double.NaN, t, utc).get(2).equals("FPS: -- / 1.0"), "unknown FPS is not guessed");
        CaptureConfig legacyFrames = CaptureConfig.parse("fields=frames", d).config;
        check(legacyFrames != null && legacyFrames.fieldsString().equals("none")
            && OverlayFormat.lines(legacyFrames, 1.0, t, utc).isEmpty(), "legacy frames field loads without display");
        CaptureConfig none = CaptureConfig.parse("fields=none", d).config;
        check(none != null && OverlayFormat.lines(none, 1.0, t, utc).isEmpty(), "no visible fields");
        CaptureConfig loc = CaptureConfig.parse("fields=location,fps", d).config;
        check(OverlayFormat.lines(loc, 2.0, t, utc).toString().equals("[NYX, FPS: 2.0 / 1.0]"), "location+fps");
        CaptureConfig selected = CaptureConfig.parse("fields=temperature,humidity,pressure,battery,battery_temperature,charging,thermal panel_enabled=off", d).config;
        OverlayTelemetry unavailable = new OverlayTelemetry(null, null, null, "unavailable", "unsupported");
        check(OverlayFormat.lines(selected, 1, t, utc, unavailable).toString().equals(
            "[Temperature: --, Humidity: --, Pressure: --, Battery level: --, Battery temperature: --, Charging status: --, Thermal status: unsupported]"),
            "stale and unavailable values are labeled placeholders; unsupported thermal remains distinct");
        EnvironmentalTelemetry.Reading environment = new EnvironmentalTelemetry.Reading(20.5, 40, 1012.3, t);
        OverlayTelemetry available = new OverlayTelemetry(environment, 0, 25.0, "charging", "severe");
        check(OverlayFormat.lines(selected, 1, t, utc, available).toString().equals(
            "[Temperature: 20.5 C, Humidity: 40.0 %, Pressure: 1012.3 hPa, Battery level: 0 %, Battery temperature: 25.0 C, Charging status: charging, Thermal status: severe]"),
            "fresh telemetry fields burn labeled measured values");
        CaptureConfig noneSelected = CaptureConfig.parse("fields=none", d).config;
        check(OverlayFormat.lines(noneSelected, 1, t, utc, available).isEmpty(), "all overlay fields can remain unchecked");
        check(OverlayLayout.margin(1280, 720) == 22 && OverlayLayout.margin(100, 100) == 8, "margin");
        check(OverlayLayout.textSizePx(24, 1280, 720) == 24f && OverlayLayout.textSizePx(24, 640, 360) == 12f, "scaling");
        float[] bl = OverlayLayout.blockOrigin("bottom-left", 1280, 720, 300, 80, 22);
        check(bl[0] == 22 && bl[1] == 720 - 22 - 80, "bottom-left");
        float[] tr = OverlayLayout.blockOrigin("top-right", 1280, 720, 300, 80, 22);
        check(tr[0] == 1280 - 22 - 300 && tr[1] == 22, "top-right");
        float[] big = OverlayLayout.blockOrigin("bottom-right", 100, 100, 500, 500, 8);
        check(big[0] == 0 && big[1] == 0, "oversized block never negative");
        // EXIF orientation: applied once, tag 1 and unknown tags are no-ops.
        check(ExifOrientation.isNormal(1) && ExifOrientation.isNormal(0) && ExifOrientation.isNormal(99), "normal");
        check(ExifOrientation.rotationDegrees(6) == 90 && ExifOrientation.rotationDegrees(3) == 180 && ExifOrientation.rotationDegrees(8) == 270, "rotations");
        check(ExifOrientation.flipHorizontal(2) && ExifOrientation.flipVertical(4) && ExifOrientation.flipHorizontal(5) && ExifOrientation.flipHorizontal(7), "flips");
        check(ExifOrientation.swapsDimensions(6) && ExifOrientation.swapsDimensions(8) && !ExifOrientation.swapsDimensions(3) && ExifOrientation.swapsDimensions(5), "dimension swap");
        System.out.println("OverlayTest passed");
    }
}
