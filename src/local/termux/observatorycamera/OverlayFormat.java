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
        return lines(config, actualFps, wallMillis, zone, null);
    }

    static List<String> lines(CaptureConfig config, double actualFps, long wallMillis, TimeZone zone,
                              OverlayTelemetry telemetry) {
        List<String> lines = new ArrayList<>();
        if (config.hasField(CaptureConfig.FIELD_LOCATION)) lines.add(config.location);
        if (config.hasField(CaptureConfig.FIELD_DATETIME)) lines.add(dateTime(wallMillis, zone));
        if (config.hasField(CaptureConfig.FIELD_FPS))
            lines.add("FPS: " + formatFps(actualFps) + " / " + config.targetFpsString());
        if (telemetry != null) lines.addAll(telemetry.lines(config));
        else {
            if (config.hasField(CaptureConfig.FIELD_TEMPERATURE)) lines.add("Temperature: --");
            if (config.hasField(CaptureConfig.FIELD_HUMIDITY)) lines.add("Humidity: --");
            if (config.hasField(CaptureConfig.FIELD_PRESSURE)) lines.add("Pressure: --");
            if (config.hasField(CaptureConfig.FIELD_BATTERY)) lines.add("Battery level: --");
            if (config.hasField(CaptureConfig.FIELD_BATTERY_TEMPERATURE)) lines.add("Battery temperature: --");
            if (config.hasField(CaptureConfig.FIELD_CHARGING)) lines.add("Charging status: --");
            if (config.hasField(CaptureConfig.FIELD_THERMAL)) lines.add("Thermal status: --");
        }
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
