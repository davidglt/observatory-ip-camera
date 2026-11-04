/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

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
    final ProcessMetrics.Snapshot processMetrics;
    final SnapshotHttpService.Telemetry serverTelemetry;
    final CaptureConfig config;
    final OverlayTelemetry overlayTelemetry;

    private TelemetrySnapshot(String environmentState, EnvironmentalTelemetry.Reading environment,
                              DeviceTelemetry device, ContinuousStatus capture, ProcessMetrics.Snapshot processMetrics,
                              SnapshotHttpService.Telemetry serverTelemetry, String serverState,
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
        this.processMetrics = processMetrics;
        this.serverTelemetry = serverTelemetry;
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
        ProcessMetrics.Snapshot processMetrics = TelemetryRuntime.processMetrics();
        SnapshotHttpService.Telemetry serverTelemetry = SnapshotHttpService.telemetry(config);
        java.io.File snapshot = new java.io.File(context.getFilesDir(), "snapshot.jpg");
        Double age = ImageAge.seconds(snapshot.isFile() ? snapshot.lastModified() : 0, wall);
        String server = SnapshotHttpService.isRunning() ? "running"
            : SnapshotHttpService.getLastError() == null ? "stopped" : "error";
        return new TelemetrySnapshot(envState, reading, DeviceTelemetry.read(context), capture,
            processMetrics, serverTelemetry, server, age, config);
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
        add(json, "capture_frames", Long.toString(ContinuousCaptureService.getStatus().frames), false);
        add(json, "process_metrics_state", processMetricsState());
        add(json, "app_cpu_current_percent", processValue(processMetrics.latest == null
            ? null : processMetrics.latest.cpuPercent, processMetrics.sampleCount < 2, 2, " %"));
        add(json, "app_cpu_recent_average_percent", processValue(processMetrics.recentAverageCpuPercent,
            processMetrics.sampleCount < 2, 2, " %"));
        add(json, "app_pss_kb", processValue(processMetrics.latest == null
            ? null : processMetrics.latest.pssKb, false, 0, ""));
        add(json, "app_pss_peak_kb", processValue(processMetrics.observedPeakPssKb, false, 0, ""));
        add(json, "app_uptime", uptimeValue());
        add(json, "system_available_ram_kb", processValue(processMetrics.latest == null
            ? null : processMetrics.latest.availableRamKb, false, 0, ""));
        add(json, "system_total_ram_kb", processValue(processMetrics.latest == null
            ? null : processMetrics.latest.totalRamKb, false, 0, ""));
        add(json, "system_low_memory", lowMemoryValue());
        add(json, "server_service_state", serverTelemetry.state);
        add(json, "server_requested_listeners", serverTelemetry.requestedListeners);
        add(json, "server_effective_listeners", serverTelemetry.effectiveListeners);
        add(json, "server_listener_error", serverTelemetry.listenerError);
        add(json, "server_active_connections", Integer.toString(serverTelemetry.activeConnections));
        add(json, "server_active_streams", Integer.toString(serverTelemetry.activeStreams));
        add(json, "panel_enabled", config.panelEnabled ? "true" : "false", false);
        add(json, "telemetry_position", config.telemetryPosition);
        add(json, "panel_text_color", config.panelTextColor);
        add(json, "panel_background_color", config.panelBackgroundColor);
        add(json, "panel_text_size", Integer.toString(config.panelTextSize), false);
        add(json, "panel_environment", config.panelEnvironment ? "true" : "false", false);
        add(json, "panel_device", config.panelDevice ? "true" : "false", false);
        add(json, "panel_capture", config.panelCapture ? "true" : "false", false);
        add(json, "panel_server", config.panelServer ? "true" : "false", false);
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
            lines.add("Application");
            lines.add("CPU current (% of one core): " + processValue(processMetrics.latest == null
                ? null : processMetrics.latest.cpuPercent, processMetrics.sampleCount < 2, 2, " %"));
            lines.add("CPU recent average (% of one core): " + processValue(processMetrics.recentAverageCpuPercent,
                processMetrics.sampleCount < 2, 2, " %"));
            lines.add("Current process PSS: " + memoryValue(processMetrics.latest == null
                ? null : processMetrics.latest.pssKb));
            lines.add("Observed PSS peak: " + memoryValue(processMetrics.observedPeakPssKb));
            lines.add("Process uptime: " + uptimeValue());
            lines.add("System");
            lines.add("Available RAM: " + memoryValue(processMetrics.latest == null
                ? null : processMetrics.latest.availableRamKb));
            lines.add("Total RAM: " + memoryValue(processMetrics.latest == null
                ? null : processMetrics.latest.totalRamKb));
            lines.add("Android low-memory status: " + lowMemoryValue());
        }
        if (config.panelCapture) {
            lines.add("Capture");
            lines.add("Capture state: " + (captureState.equals(ContinuousStatus.STOPPED) ? "Not capturing" : captureState));
            lines.add("Latest image age: " + (imageAgeSeconds == null ? "--" : fixed(imageAgeSeconds, 1) + " s"));
            lines.add("FPS: " + TelemetryDisplayFormat.fps(actualFps) + " / "
                + TelemetryDisplayFormat.fps(requestedFps));
        }
        if (config.panelServer) {
            lines.add("Server");
            lines.add("Service state: " + serverTelemetry.state);
            lines.add("Requested listeners: " + serverTelemetry.requestedListeners);
            lines.add("Effective listeners: " + serverTelemetry.effectiveListeners);
            lines.add("Listener errors: " + serverTelemetry.listenerError);
            lines.add("HTTP/S connections: " + serverTelemetry.activeConnections);
            lines.add("MJPEG connections: " + serverTelemetry.activeStreams);
        }
        return lines;
    }

    private String processMetricsState() {
        if (processMetrics.latest == null) return "Collecting";
        if (processMetrics.isStale()) return "stale";
        ProcessMetrics.Sample latest = processMetrics.latest;
        return latest.cpuPercent != null || latest.pssKb != null || latest.availableRamKb != null
            || latest.totalRamKb != null || latest.lowMemory != null ? "available" : "unavailable";
    }

    private String processValue(Number value, boolean collecting, int digits, String unit) {
        if (processMetrics.latest == null) return "Collecting";
        if (value == null) return collecting ? "Collecting" : "Unavailable";
        String text = digits == 0 ? Long.toString(value.longValue()) : fixed(value.doubleValue(), digits);
        return text + unit + staleSuffix();
    }

    private String memoryValue(Number value) {
        if (processMetrics.latest == null) return "Collecting";
        if (value == null) return "Unavailable";
        return TelemetryDisplayFormat.memoryKilobytes(value) + staleSuffix();
    }

    private String uptimeValue() {
        if (processMetrics.latest == null) return "Collecting";
        Long millis = processMetrics.latest.uptimeMillis;
        if (millis == null) return "Unavailable";
        long seconds = millis / 1000;
        return seconds / 3600 + " h " + seconds / 60 % 60 + " min " + seconds % 60 + " s" + staleSuffix();
    }

    private String lowMemoryValue() {
        if (processMetrics.latest == null) return "Collecting";
        Boolean low = processMetrics.latest.lowMemory;
        return low == null ? "Unavailable" : (low ? "Low" : "Normal") + staleSuffix();
    }

    private String staleSuffix() { return processMetrics.isStale() ? " (stale)" : ""; }

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
