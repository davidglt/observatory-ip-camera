package local.termux.observatorycamera;

import java.util.LinkedHashMap;
import java.util.Map;

/** Dependency-free test. Run: bash test/run.sh */
public final class CaptureConfigTest {
    private static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); }
    private static CaptureConfig ok(String a) {
        CaptureConfig.Parsed p = CaptureConfig.parse(a, CaptureConfig.defaults());
        check(p.config != null, "should accept: " + a + " (" + p.error + ")");
        return p.config;
    }
    private static void bad(String a) { check(CaptureConfig.parse(a, CaptureConfig.defaults()).config == null, "should reject: " + a); }

    public static void main(String[] args) {
        CaptureConfig d = CaptureConfig.defaults();
        check(d.overlayEnabled && d.location.equals("NYX") && d.position.equals("bottom-left"), "defaults");
        check(d.targetFpsTenths == 10 && d.targetIntervalNanos() == 1_000_000_000L, "default 1 FPS");
        check(d.describe().equals("overlay=on location=NYX position=bottom-left text_size=24 fields=location,datetime,fps,frames target_fps=1.0 text_color=green"), d.describe());
        check(ok("target_fps=2").targetIntervalNanos() == 500_000_000L, "2 FPS interval");
        check(ok("target_fps=0.5").targetFpsTenths == 5, "0.5");
        check(ok("overlay=off location=Site-1 position=top-right text_size=40 fields=datetime,frames").describe()
            .equals("overlay=off location=Site-1 position=top-right text_size=40 fields=datetime,frames target_fps=1.0 text_color=green"), "multi");
        check(ok("fields=frames,location").fieldsString().equals("location,frames"), "canonical field order");
        for (String b : new String[]{"", "   ", "overlay", "overlay=", "=on", "overlay=yes", "location=", "location=a b".replace(' ', '$'),
            "location=" + new String(new char[25]).replace('\0', 'a'), "location=x;y", "position=middle", "text_size=9", "text_size=97", "text_size=abc",
            "text_size=-5", "fields=", "fields=gps", "fields=fps,fps", "fields=fps,,frames", "target_fps=0", "target_fps=0.0", "target_fps=2.1", "target_fps=3",
            "target_fps=1.25", "target_fps=-1", "bogus=1", "overlay=on overlay=off"}) bad(b);
        CaptureConfig.Parsed atomic = CaptureConfig.parse("overlay=off bogus=1", d);
        check(atomic.config == null && d.overlayEnabled, "atomic: nothing applied on error");
        check(ok("text_color=green").describe().endsWith("text_color=green"), "green accepted");
        bad("text_color=red");
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("location", "Obs 1");
        check(CaptureConfig.fromEntries(entries).config.location.equals("Obs 1"), "space inside location allowed in files");
        entries.put("bogus", "1");
        check(CaptureConfig.fromEntries(entries).config == null, "unknown key rejects the whole set");
        entries.clear();
        entries.put("location", " lead");
        check(CaptureConfig.fromEntries(entries).config == null, "leading space rejected");
        entries.put("location", "trail ");
        check(CaptureConfig.fromEntries(entries).config == null, "trailing space rejected");
        entries.put("location", new String(new char[25]).replace('\0', 'a'));
        check(CaptureConfig.fromEntries(entries).config == null, "25 chars rejected");
        System.out.println("CaptureConfigTest passed");
    }
}
