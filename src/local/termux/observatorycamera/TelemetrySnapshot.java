package local.termux.observatorycamera;

import android.content.Context;
import java.util.Locale;

/** Immutable per-query/per-frame view assembled from measured capture, Android and sensor state. */
final class TelemetrySnapshot {
    final String environmentState;
    final EnvironmentalTelemetry.Reading environment;
    final Integer batteryPercent;
    final Double batteryTemperatureC;
    final String charging;
    final String thermalStatus;
    final String captureState;
    final String httpServerState;
    final Double imageAgeSeconds;
    final Double actualFps;
    final Double requestedFps;
    final Double processingLastMs;
    final Double processingAverageMs;
    final Double processingMaximumMs;
    final CaptureConfig config;
    final OverlayTelemetry overlayTelemetry;

    private TelemetrySnapshot(String environmentState, EnvironmentalTelemetry.Reading environment,
                              DeviceTelemetry device, ContinuousStatus capture, String serverState,
                              Double imageAgeSeconds, CaptureConfig config) {
        this.environmentState = environmentState;
        this.environment = environment;
        batteryPercent = device.batteryPercent;
        batteryTemperatureC = device.batteryTemperatureC;
        charging = device.charging;
        thermalStatus = device.thermalStatus;
        captureState = capture.state;
        httpServerState = serverState;
        this.imageAgeSeconds = imageAgeSeconds;
        actualFps = Double.isFinite(capture.actualFps) ? capture.actualFps : null;
        long requestedTenths = capture.targetFpsTenths > 0 ? capture.targetFpsTenths : config.targetFpsTenths;
        requestedFps = requestedTenths > 0 ? requestedTenths / 10.0 : null;
        processingLastMs = capture.procLastNanos > 0 ? capture.procLastNanos / 1_000_000.0 : null;
        processingAverageMs = capture.frames > 0 ? capture.procAvgNanos / 1_000_000.0 : null;
        processingMaximumMs = capture.frames > 0 ? capture.procMaxNanos / 1_000_000.0 : null;
        this.config = config;
        overlayTelemetry = new OverlayTelemetry(environment, batteryPercent, batteryTemperatureC, charging, thermalStatus);
    }

    static TelemetrySnapshot read(Context context) {
        TelemetryRuntime.refresh(context);
        CaptureConfig config = CaptureSettings.load(context);
        long wall = System.currentTimeMillis();
        EnvironmentalTelemetry sensor = TelemetryRuntime.environment();
        EnvironmentalTelemetry.Reading reading = sensor.latest();
        String envState = sensor.state(wall, TelemetryRuntime.staleMillis());
        if (!envState.equals("fresh")) reading = null;
        ContinuousStatus capture = ContinuousCaptureService.getStatus();
        java.io.File snapshot = new java.io.File(context.getFilesDir(), "snapshot.jpg");
        Double age = ImageAge.seconds(snapshot.isFile() ? snapshot.lastModified() : 0, wall);
        String server = SnapshotHttpService.isRunning() ? "running"
            : SnapshotHttpService.getLastError() == null ? "stopped" : "error";
        return new TelemetrySnapshot(envState, reading, DeviceTelemetry.read(context), capture, server, age, config);
    }

    String toJson() {
        StringBuilder json = new StringBuilder("{");
        add(json, "environment_state", environmentState);
        add(json, "environment_temperature_c", environment == null ? "--" : fixed(environment.temperatureC, 1));
        add(json, "environment_humidity_pct", environment == null ? "--" : fixed(environment.humidityPct, 1));
        add(json, "environment_pressure_hpa", environment == null ? "--" : fixed(environment.pressureHpa, 1));
        add(json, "battery_percent", batteryPercent == null ? "--" : batteryPercent.toString());
        add(json, "battery_temperature_c", batteryTemperatureC == null ? "--" : fixed(batteryTemperatureC, 1));
        add(json, "charging", charging);
        add(json, "thermal_status", thermalStatus);
        add(json, "capture_state", captureState);
        add(json, "http_server_state", httpServerState);
        add(json, "latest_image_age_seconds", imageAgeSeconds == null ? "--" : fixed(imageAgeSeconds, 1));
        add(json, "actual_fps", actualFps == null ? "--" : fixed(actualFps, 2));
        add(json, "requested_fps", requestedFps == null ? "--" : fixed(requestedFps, 1));
        add(json, "processing_last_ms", processingLastMs == null ? "--" : fixed(processingLastMs, 1));
        add(json, "processing_average_ms", processingAverageMs == null ? "--" : fixed(processingAverageMs, 1));
        add(json, "processing_maximum_ms", processingMaximumMs == null ? "--" : fixed(processingMaximumMs, 1));
        add(json, "panel_enabled", config.panelEnabled ? "true" : "false", false);
        add(json, "panel_text_color", config.panelTextColor);
        add(json, "panel_background_color", config.panelBackgroundColor);
        add(json, "panel_text_size", Integer.toString(config.panelTextSize), false);
        add(json, "panel_environment", config.panelEnvironment ? "true" : "false", false);
        add(json, "panel_device", config.panelDevice ? "true" : "false", false);
        add(json, "panel_capture", config.panelCapture ? "true" : "false", false);
        return json.append('}').toString();
    }

    java.util.List<String> panelLines() {
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (config.panelEnvironment) {
            lines.add("Environment");
            lines.add("Temperature: " + (environment == null ? "--" : fixed(environment.temperatureC, 1) + " C"));
            lines.add("Humidity: " + (environment == null ? "--" : fixed(environment.humidityPct, 1) + " %"));
            lines.add("Pressure: " + (environment == null ? "--" : fixed(environment.pressureHpa, 1) + " hPa"));
            lines.add("Sensor: " + environmentState);
        }
        if (config.panelDevice) {
            lines.add("Device");
            lines.add("Battery level: " + (batteryPercent == null ? "--" : batteryPercent + " %"));
            lines.add("Battery temperature: " + (batteryTemperatureC == null ? "--" : fixed(batteryTemperatureC, 1) + " C"));
            lines.add("Charging status: " + charging);
            lines.add("Thermal status: " + thermalStatus);
        }
        if (config.panelCapture) {
            lines.add("Capture");
            lines.add("Capture state: " + captureState);
            lines.add("HTTP server: " + httpServerState);
            lines.add("Latest image age: " + (imageAgeSeconds == null ? "--" : fixed(imageAgeSeconds, 1) + " s"));
            lines.add("FPS actual/requested: " + (actualFps == null ? "--" : fixed(actualFps, 2)) + " / "
                + (requestedFps == null ? "--" : fixed(requestedFps, 1)));
            lines.add("Processing ms last/average/maximum: " + value(processingLastMs) + " / "
                + value(processingAverageMs) + " / " + value(processingMaximumMs));
        }
        return lines;
    }

    private static String value(Double n) { return n == null ? "--" : fixed(n, 1); }
    private static String fixed(double value, int digits) { return String.format(Locale.ROOT, "%." + digits + "f", value); }

    private static void add(StringBuilder out, String key, String value) {
        add(out, key, value, true);
    }

    private static void add(StringBuilder out, String key, String value, boolean quote) {
        if (out.length() > 1) out.append(',');
        out.append('"').append(key).append("\":");
        if (quote) out.append('"').append(value).append('"');
        else out.append(value);
    }
}
