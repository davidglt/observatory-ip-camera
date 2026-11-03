package local.termux.observatorycamera;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Builds the overlay text lines: NYX / yyyy-MM-dd HH:mm:ss z / FPS: actual / requested. Pure Java. */
final class OverlayFormat {
    private OverlayFormat() {}

    /** actualFps is NaN until two frames have been measured; it is then shown as "--", never as a guess. */
    static List<String> lines(CaptureConfig config, double actualFps, long wallMillis, TimeZone zone) {
        List<String> lines = new ArrayList<>();
        if (config.hasField(CaptureConfig.FIELD_LOCATION)) lines.add(config.location);
        if (config.hasField(CaptureConfig.FIELD_DATETIME)) lines.add(dateTime(wallMillis, zone));
        if (config.hasField(CaptureConfig.FIELD_FPS))
            lines.add("FPS: " + formatFps(actualFps) + " / " + config.targetFpsString());
        return lines;
    }

    static String dateTime(long wallMillis, TimeZone zone) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.ROOT);
        format.setTimeZone(zone);
        return format.format(new Date(wallMillis));
    }

    static String formatFps(double fps) {
        return Double.isNaN(fps) || fps < 0 ? "--" : String.format(Locale.ROOT, "%.1f", fps);
    }
}
