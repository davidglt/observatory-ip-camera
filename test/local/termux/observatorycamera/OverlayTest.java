package local.termux.observatorycamera;

import java.util.List;
import java.util.TimeZone;

/** Dependency-free test. Run: bash test/run.sh */
public final class OverlayTest {
    private static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); }

    public static void main(String[] args) {
        CaptureConfig d = CaptureConfig.defaults();
        TimeZone utc = TimeZone.getTimeZone("UTC");
        long t = 1_767_225_600_000L; // 2026-01-01T00:00:00Z
        List<String> l = OverlayFormat.lines(d, 0.98, 12, t, utc);
        check(l.size() == 3 && l.get(0).equals("NYX") && l.get(1).equals("2026-01-01 00:00:00 UTC") && l.get(2).equals("FPS: 0.98 | Frames: 12"), l.toString());
        check(OverlayFormat.dateTime(t, TimeZone.getTimeZone("Asia/Tokyo")).equals("2026-01-01 09:00:00 JST"), "timezone follows the zone");
        check(OverlayFormat.lines(d, Double.NaN, 1, t, utc).get(2).equals("FPS: -- | Frames: 1"), "unknown FPS is not guessed");
        CaptureConfig one = CaptureConfig.parse("fields=frames", d).config;
        check(OverlayFormat.lines(one, 1.0, 3, t, utc).toString().equals("[Frames: 3]"), "single field");
        CaptureConfig loc = CaptureConfig.parse("fields=location,fps", d).config;
        check(OverlayFormat.lines(loc, 2.0, 3, t, utc).toString().equals("[NYX, FPS: 2.00]"), "location+fps");
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
