package local.termux.observatorycamera;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Per-frame values for burned-in overlay rendering; environmental values are set only when fresh. */
final class OverlayTelemetry {
    final EnvironmentalTelemetry.Reading environment;
    final Integer batteryPercent;
    final Double batteryTemperatureC;
    final String charging;
    final String thermalStatus;

    OverlayTelemetry(EnvironmentalTelemetry.Reading environment, Integer batteryPercent,
                     Double batteryTemperatureC, String charging, String thermalStatus) {
        this.environment = environment;
        this.batteryPercent = batteryPercent;
        this.batteryTemperatureC = batteryTemperatureC;
        this.charging = charging;
        this.thermalStatus = thermalStatus;
    }

    List<String> lines(CaptureConfig config) {
        List<String> lines = new ArrayList<>();
        if (config.hasField(CaptureConfig.FIELD_TEMPERATURE))
            lines.add("Temperature: " + (environment == null ? "--" : fixed(environment.temperatureC, 1) + " C"));
        if (config.hasField(CaptureConfig.FIELD_HUMIDITY))
            lines.add("Humidity: " + (environment == null ? "--" : fixed(environment.humidityPct, 1) + " %"));
        if (config.hasField(CaptureConfig.FIELD_PRESSURE))
            lines.add("Pressure: " + (environment == null ? "--" : fixed(environment.pressureHpa, 1) + " hPa"));
        if (config.hasField(CaptureConfig.FIELD_BATTERY))
            lines.add("Battery level: " + (batteryPercent == null ? "--" : batteryPercent + " %"));
        if (config.hasField(CaptureConfig.FIELD_BATTERY_TEMPERATURE))
            lines.add("Battery temperature: " + (batteryTemperatureC == null ? "--" : fixed(batteryTemperatureC, 1) + " C"));
        if (config.hasField(CaptureConfig.FIELD_CHARGING))
            lines.add("Charging status: " + ("unavailable".equals(charging) || "unknown".equals(charging) ? "--" : charging));
        if (config.hasField(CaptureConfig.FIELD_THERMAL))
            lines.add("Thermal status: " + ("unavailable".equals(thermalStatus) ? "--" : thermalStatus));
        return lines;
    }

    private static String fixed(double value, int digits) { return String.format(Locale.ROOT, "%." + digits + "f", value); }
}
