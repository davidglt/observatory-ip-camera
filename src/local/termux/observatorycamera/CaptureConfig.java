/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Persistent capture/overlay settings with bounded validation. Pure Java (no Android classes) so it
 * is unit-tested. Settings are changed over SSH as one atomic "key=value key=value" assignment string:
 * either every assignment is valid and applied, or nothing changes.
 */
public final class CaptureConfig {
    public static final String OVERLAY = "overlay";
    public static final String LOCATION = "location";
    public static final String POSITION = "position";
    public static final String TEXT_SIZE = "text_size";
    public static final String FIELDS = "fields";
    public static final String TARGET_FPS = "target_fps";
    public static final String JPEG_SIZE = "jpeg_size";
    public static final String TEXT_COLOR = "text_color";
    public static final String TEXT_COLOR_GREEN = "green";
    public static final String TELEMETRY_POSITION = "telemetry_position";
    public static final String PANEL_ENABLED = "panel_enabled";
    public static final String PANEL_TEXT_COLOR = "panel_text_color";
    public static final String PANEL_BACKGROUND_COLOR = "panel_background_color";
    public static final String PANEL_TEXT_SIZE = "panel_text_size";
    public static final String PANEL_ENVIRONMENT = "panel_environment";
    public static final String PANEL_DEVICE = "panel_device";
    public static final String PANEL_CAPTURE = "panel_capture";
    public static final String MQTT_HOST = "mqtt_host";
    public static final String MQTT_PORT = "mqtt_port";
    public static final String MQTT_TOPIC = "mqtt_topic";
    public static final String MQTT_TLS = "mqtt_tls";
    public static final String MQTT_STALE_SECONDS = "mqtt_stale_seconds";
    public static final String FOCUS_MODE = "focus_mode";
    public static final String FOCUS_DISTANCE_DIOPTERS = "focus_distance_diopters";
    public static final String EXPOSURE_MODE = "exposure_mode";
    public static final String EXPOSURE_COMPENSATION = "exposure_compensation";
    public static final String AE_LOCK = "ae_lock";
    public static final String ISO = "iso";
    public static final String EXPOSURE_TIME_NS = "exposure_time_ns";
    public static final String WHITE_BALANCE = "white_balance";
    public static final String AWB_LOCK = "awb_lock";
    public static final String JPEG_QUALITY = "jpeg_quality";
    public static final String ORIENTATION_MODE = "orientation_mode";
    public static final String ROTATION_DEGREES = "rotation_degrees";
    public static final String MIRROR = "mirror";
    public static final int MAX_LOCATION_LENGTH = 24;

    public static final int FIELD_LOCATION = 1;
    public static final int FIELD_DATETIME = 2;
    public static final int FIELD_FPS = 4;
    public static final int FIELD_TEMPERATURE = 8;
    public static final int FIELD_HUMIDITY = 16;
    public static final int FIELD_PRESSURE = 32;
    public static final int FIELD_BATTERY = 64;
    public static final int FIELD_BATTERY_TEMPERATURE = 128;
    public static final int FIELD_CHARGING = 256;
    public static final int FIELD_THERMAL = 512;
    public static final int ALL_FIELDS = FIELD_LOCATION | FIELD_DATETIME | FIELD_FPS;

    public static final String[] POSITIONS = {"bottom-left", "bottom-right", "top-left", "top-right"};
    public static final int MIN_TEXT_SIZE = 10;
    public static final int MAX_TEXT_SIZE = 96;
    public static final int MAX_ASSIGNMENTS_LENGTH = 1024;
    private static final String[] FIELD_NAMES = {
        "location", "datetime", "fps", "temperature", "humidity", "pressure", "battery",
        "battery_temperature", "charging", "thermal"
    };
    private static final Pattern LOCATION_PATTERN = Pattern.compile("[\\p{L}\\p{N}_.-]([\\p{L}\\p{N} _.-]*[\\p{L}\\p{N}_.-])?");
    private static final Pattern FPS_PATTERN = Pattern.compile("[0-9]+(\\.[0-9])?");
    private static final Pattern JPEG_SIZE_PATTERN = Pattern.compile("[1-9][0-9]{0,4}x[1-9][0-9]{0,4}");
    private static final Pattern COLOR_PATTERN = Pattern.compile("#[0-9A-Fa-f]{6}");
    private static final Pattern MQTT_HOST_PATTERN = Pattern.compile("[A-Za-z0-9.-]{0,253}");
    private static final Pattern MQTT_TOPIC_PATTERN = Pattern.compile("[A-Za-z0-9_./#+-]{1,128}");
    private static final Pattern FOCUS_DISTANCE_PATTERN = Pattern.compile("[0-9]+(\\.[0-9]{1,6})?");
    private static final String[] FOCUS_MODES = {"auto", "lock", "manual", "continuous-picture", "continuous-video", "macro", "edof"};
    private static final String[] WHITE_BALANCE_MODES = {"auto", "incandescent", "fluorescent", "warm-fluorescent", "daylight",
        "cloudy-daylight", "twilight", "shade"};

    public final boolean overlayEnabled;
    public final String location;
    public final String position;
    public final int textSize;
    public final int fields;
    public final long targetFpsTenths;
    public final String jpegSize;
    public final String textColor;
    public final String telemetryPosition;
    public final boolean panelEnabled;
    public final String panelTextColor;
    public final String panelBackgroundColor;
    public final int panelTextSize;
    public final boolean panelEnvironment;
    public final boolean panelDevice;
    public final boolean panelCapture;
    public final String mqttHost;
    public final int mqttPort;
    public final String mqttTopic;
    public final boolean mqttTls;
    public final int mqttStaleSeconds;
    public final String focusMode;
    public final float focusDistanceDiopters;
    public final String exposureMode;
    public final int exposureCompensation;
    public final boolean aeLock;
    public final int iso;
    public final long exposureTimeNanos;
    public final String whiteBalance;
    public final boolean awbLock;
    public final int jpegQuality;
    public final String orientationMode;
    public final int rotationDegrees;
    public final boolean mirror;

    private CaptureConfig(boolean overlayEnabled, String location, String position, int textSize, int fields,
                          long targetFpsTenths, String jpegSize, String textColor, String telemetryPosition,
                          boolean panelEnabled, String panelTextColor,
                          String panelBackgroundColor, int panelTextSize, boolean panelEnvironment,
                          boolean panelDevice, boolean panelCapture, String mqttHost, int mqttPort,
                          String mqttTopic, boolean mqttTls, int mqttStaleSeconds, String focusMode,
                          float focusDistanceDiopters, String exposureMode, int exposureCompensation,
                          boolean aeLock, int iso, long exposureTimeNanos, String whiteBalance,
                          boolean awbLock, int jpegQuality, String orientationMode, int rotationDegrees,
                          boolean mirror) {
        this.overlayEnabled = overlayEnabled;
        this.location = location;
        this.position = position;
        this.textSize = textSize;
        this.fields = fields;
        this.targetFpsTenths = targetFpsTenths;
        this.jpegSize = jpegSize;
        this.textColor = textColor;
        this.telemetryPosition = telemetryPosition;
        this.panelEnabled = panelEnabled;
        this.panelTextColor = panelTextColor;
        this.panelBackgroundColor = panelBackgroundColor;
        this.panelTextSize = panelTextSize;
        this.panelEnvironment = panelEnvironment;
        this.panelDevice = panelDevice;
        this.panelCapture = panelCapture;
        this.mqttHost = mqttHost;
        this.mqttPort = mqttPort;
        this.mqttTopic = mqttTopic;
        this.mqttTls = mqttTls;
        this.mqttStaleSeconds = mqttStaleSeconds;
        this.focusMode = focusMode;
        this.focusDistanceDiopters = focusDistanceDiopters;
        this.exposureMode = exposureMode;
        this.exposureCompensation = exposureCompensation;
        this.aeLock = aeLock;
        this.iso = iso;
        this.exposureTimeNanos = exposureTimeNanos;
        this.whiteBalance = whiteBalance;
        this.awbLock = awbLock;
        this.jpegQuality = jpegQuality;
        this.orientationMode = orientationMode;
        this.rotationDegrees = rotationDegrees;
        this.mirror = mirror;
    }

    /** Conservative defaults: overlay on, NYX, bottom-left, size 24 (at 720 px short side), all visible fields, 1.0 FPS target. */
    public static CaptureConfig defaults() {
        return new CaptureConfig(true, "NYX", "bottom-left", 24, ALL_FIELDS, 10, "auto",
            TEXT_COLOR_GREEN, "right", true, "#FF0000", "#000000", 16, true, true, true,
            "", 1883, "homeassistant/nyx/reading", false, 120, "auto", 0f, "auto", 0, false,
            100, 10_000_000L, "auto", false, 90, "auto", 0, false);
    }

    /** Result of parsing: exactly one of config and error is non-null. */
    public static final class Parsed {
        public final CaptureConfig config;
        public final String error;
        Parsed(CaptureConfig config, String error) { this.config = config; this.error = error; }
    }

    /** Mutable working copy used while validating assignments; never exposed. */
    private static final class Draft {
        boolean overlay;
        String location;
        String position;
        int textSize;
        int fields;
        long fps;
        String jpegSize;
        String textColor;
        String telemetryPosition;
        boolean panelEnabled;
        String panelTextColor;
        String panelBackgroundColor;
        int panelTextSize;
        boolean panelEnvironment;
        boolean panelDevice;
        boolean panelCapture;
        String mqttHost;
        int mqttPort;
        String mqttTopic;
        boolean mqttTls;
        int mqttStaleSeconds;
        String focusMode;
        float focusDistanceDiopters;
        String exposureMode;
        int exposureCompensation;
        boolean aeLock;
        int iso;
        long exposureTimeNanos;
        String whiteBalance;
        boolean awbLock;
        int jpegQuality;
        String orientationMode;
        int rotationDegrees;
        boolean mirror;
        Draft(CaptureConfig c) {
            overlay = c.overlayEnabled; location = c.location; position = c.position;
            textSize = c.textSize; fields = c.fields; fps = c.targetFpsTenths; jpegSize = c.jpegSize;
            textColor = c.textColor; telemetryPosition = c.telemetryPosition;
            panelEnabled = c.panelEnabled; panelTextColor = c.panelTextColor;
            panelBackgroundColor = c.panelBackgroundColor; panelTextSize = c.panelTextSize;
            panelEnvironment = c.panelEnvironment; panelDevice = c.panelDevice; panelCapture = c.panelCapture;
            mqttHost = c.mqttHost; mqttPort = c.mqttPort; mqttTopic = c.mqttTopic; mqttTls = c.mqttTls;
            mqttStaleSeconds = c.mqttStaleSeconds;
            focusMode = c.focusMode; focusDistanceDiopters = c.focusDistanceDiopters;
            exposureMode = c.exposureMode; exposureCompensation = c.exposureCompensation; aeLock = c.aeLock;
            iso = c.iso; exposureTimeNanos = c.exposureTimeNanos; whiteBalance = c.whiteBalance;
            awbLock = c.awbLock; jpegQuality = c.jpegQuality; orientationMode = c.orientationMode;
            rotationDegrees = c.rotationDegrees; mirror = c.mirror;
        }
        CaptureConfig build() {
            return new CaptureConfig(overlay, location, position, textSize, fields, fps, jpegSize,
                textColor, telemetryPosition, panelEnabled, panelTextColor, panelBackgroundColor,
                panelTextSize, panelEnvironment,
                panelDevice, panelCapture, mqttHost, mqttPort, mqttTopic, mqttTls, mqttStaleSeconds,
                focusMode, focusDistanceDiopters, exposureMode, exposureCompensation, aeLock, iso,
                exposureTimeNanos, whiteBalance, awbLock, jpegQuality, orientationMode, rotationDegrees, mirror);
        }
    }

    /** Validates one key/value into draft; returns null when valid, otherwise an actionable error. */
    private static String apply(Draft d, String key, String value) {
        switch (key) {
            case OVERLAY:
                if (value.equals("on")) d.overlay = true;
                else if (value.equals("off")) d.overlay = false;
                else return "invalid-overlay:expected-on-or-off";
                return null;
            case LOCATION:
                if (value.length() > MAX_LOCATION_LENGTH || !LOCATION_PATTERN.matcher(value).matches())
                    return "invalid-location:1-" + MAX_LOCATION_LENGTH + "-letters-digits-space-_.-(no-leading-or-trailing-space)";
                d.location = value;
                return null;
            case POSITION:
                if (!contains(POSITIONS, value)) return "invalid-position:expected-" + String.join("|", POSITIONS);
                d.position = value;
                return null;
            case TEXT_SIZE: {
                int n = parseInt(value);
                if (n < MIN_TEXT_SIZE || n > MAX_TEXT_SIZE) return "invalid-text_size:expected-" + MIN_TEXT_SIZE + "-" + MAX_TEXT_SIZE;
                d.textSize = n;
                return null;
            }
            case FIELDS: {
                int mask = parseFields(value);
                if (mask < 0) return "invalid-fields:expected-none-or-comma-list-of-" + String.join(",", FIELD_NAMES);
                d.fields = mask;
                return null;
            }
            case TARGET_FPS: {
                long tenths = parseFpsTenths(value);
                if (tenths == -1) return "invalid-target_fps:expected-positive-number-with-at-most-one-decimal";
                if (tenths == -2) return "invalid-target_fps:outside-representable-range";
                if (tenths == 0) return "invalid-target_fps:must-be-positive";
                d.fps = tenths;
                return null;
            }
            case JPEG_SIZE:
                if (!value.equals("auto") && !JPEG_SIZE_PATTERN.matcher(value).matches())
                    return "invalid-jpeg_size:expected-auto-or-WIDTHxHEIGHT";
                d.jpegSize = value;
                return null;
            case TEXT_COLOR:
                if (value.equals(TEXT_COLOR_GREEN)) {
                    d.textColor = TEXT_COLOR_GREEN;
                    return null;
                }
                if (!COLOR_PATTERN.matcher(value).matches()) return "invalid-text_color:expected-green-or-#RRGGBB";
                d.textColor = value.toUpperCase(Locale.ROOT);
                return null;
            case TELEMETRY_POSITION:
                if (!value.equals("left") && !value.equals("right"))
                    return "invalid-telemetry_position:expected-left-or-right";
                d.telemetryPosition = value;
                return null;
            case PANEL_ENABLED:
                if (!value.equals("on") && !value.equals("off")) return "invalid-panel_enabled:expected-on-or-off";
                d.panelEnabled = value.equals("on");
                return null;
            case PANEL_TEXT_COLOR:
                if (!COLOR_PATTERN.matcher(value).matches()) return "invalid-panel_text_color:expected-hex-color";
                d.panelTextColor = value.toUpperCase(Locale.ROOT);
                return null;
            case PANEL_BACKGROUND_COLOR:
                if (!COLOR_PATTERN.matcher(value).matches()) return "invalid-panel_background_color:expected-hex-color";
                d.panelBackgroundColor = value.toUpperCase(Locale.ROOT);
                return null;
            case PANEL_TEXT_SIZE: {
                int n = parseInt(value);
                if (n < 10 || n > 48) return "invalid-panel_text_size:expected-10-48";
                d.panelTextSize = n;
                return null;
            }
            case PANEL_ENVIRONMENT:
            case PANEL_DEVICE:
            case PANEL_CAPTURE:
                if (!value.equals("on") && !value.equals("off")) return "invalid-" + key + ":expected-on-or-off";
                if (key.equals(PANEL_ENVIRONMENT)) d.panelEnvironment = value.equals("on");
                else if (key.equals(PANEL_DEVICE)) d.panelDevice = value.equals("on");
                else d.panelCapture = value.equals("on");
                return null;
            case MQTT_HOST:
                if (!validMqttHost(value))
                    return "invalid-mqtt_host:expected-hostname-or-empty";
                d.mqttHost = value;
                return null;
            case MQTT_PORT: {
                int port = parseBoundedInt(value, 5);
                if (port < 1 || port > 65535) return "invalid-mqtt_port:expected-1-65535";
                d.mqttPort = port;
                return null;
            }
            case MQTT_TOPIC:
                if (!validMqttTopic(value))
                    return "invalid-mqtt_topic:expected-bounded-topic";
                d.mqttTopic = value;
                return null;
            case MQTT_TLS:
                if (!value.equals("on") && !value.equals("off")) return "invalid-mqtt_tls:expected-on-or-off";
                d.mqttTls = value.equals("on");
                return null;
            case MQTT_STALE_SECONDS: {
                int seconds = parseInt(value);
                if (seconds < 10 || seconds > 3600) return "invalid-mqtt_stale_seconds:expected-10-3600";
                d.mqttStaleSeconds = seconds;
                return null;
            }
            case FOCUS_MODE:
                if (!contains(FOCUS_MODES, value)) return "invalid-focus_mode:expected-" + String.join("|", FOCUS_MODES);
                d.focusMode = value;
                return null;
            case FOCUS_DISTANCE_DIOPTERS: {
                if (!FOCUS_DISTANCE_PATTERN.matcher(value).matches()) return "invalid-focus_distance_diopters:expected-nonnegative-diopters";
                try { d.focusDistanceDiopters = Float.parseFloat(value); }
                catch (NumberFormatException e) { return "invalid-focus_distance_diopters:outside-representable-range"; }
                if (!Float.isFinite(d.focusDistanceDiopters))
                    return "invalid-focus_distance_diopters:outside-representable-range";
                return null;
            }
            case EXPOSURE_MODE:
                if (!value.equals("auto") && !value.equals("manual")) return "invalid-exposure_mode:expected-auto-or-manual";
                d.exposureMode = value;
                return null;
            case EXPOSURE_COMPENSATION: {
                int n = parseSignedInt(value);
                if (n == Integer.MIN_VALUE) return "invalid-exposure_compensation:expected-signed-camera-step";
                d.exposureCompensation = n;
                return null;
            }
            case AE_LOCK:
            case AWB_LOCK:
            case MIRROR:
                if (!value.equals("on") && !value.equals("off")) return "invalid-" + key + ":expected-on-or-off";
                if (key.equals(AE_LOCK)) d.aeLock = value.equals("on");
                else if (key.equals(AWB_LOCK)) d.awbLock = value.equals("on");
                else d.mirror = value.equals("on");
                return null;
            case ISO: {
                int n = parseBoundedInt(value, 9);
                if (n < 1) return "invalid-iso:expected-positive-integer";
                d.iso = n;
                return null;
            }
            case EXPOSURE_TIME_NS: {
                long n = parsePositiveLong(value);
                if (n <= 0) return "invalid-exposure_time_ns:expected-positive-nanoseconds";
                d.exposureTimeNanos = n;
                return null;
            }
            case WHITE_BALANCE:
                if (!contains(WHITE_BALANCE_MODES, value))
                    return "invalid-white_balance:expected-" + String.join("|", WHITE_BALANCE_MODES);
                d.whiteBalance = value;
                return null;
            case JPEG_QUALITY: {
                int n = parseBoundedInt(value, 3);
                if (n < 1 || n > 100) return "invalid-jpeg_quality:expected-1-100";
                d.jpegQuality = n;
                return null;
            }
            case ORIENTATION_MODE:
                if (!value.equals("auto") && !value.equals("manual"))
                    return "invalid-orientation_mode:expected-auto-or-manual";
                d.orientationMode = value;
                return null;
            case ROTATION_DEGREES:
                if (!value.equals("0") && !value.equals("90") && !value.equals("180") && !value.equals("270"))
                    return "invalid-rotation_degrees:expected-0-90-180-270";
                d.rotationDegrees = Integer.parseInt(value);
                return null;
            default:
                return "unknown-key:" + safe(key);
        }
    }

    /** Applies whitespace-separated key=value assignments on top of base. Unknown keys, duplicates and out-of-range values are rejected. */
    public static Parsed parse(String assignments, CaptureConfig base) {
        if (assignments == null || assignments.trim().isEmpty()) return new Parsed(null, "no-assignments");
        if (assignments.length() > MAX_ASSIGNMENTS_LENGTH) return new Parsed(null, "assignments-too-long");
        Draft draft = new Draft(base);
        Set<String> seen = new HashSet<>();
        for (String part : assignments.trim().split("\\s+")) {
            int eq = part.indexOf('=');
            if (eq <= 0 || (eq == part.length() - 1 && !part.substring(0, eq).equals(FIELDS)
                && !part.substring(0, eq).equals(MQTT_HOST)))
                return new Parsed(null, "malformed-assignment:" + safe(part));
            String key = part.substring(0, eq);
            if (!seen.add(key)) return new Parsed(null, "duplicate-key:" + safe(key));
            String error = apply(draft, key, part.substring(eq + 1));
            if (error != null) return new Parsed(null, error);
        }
        return new Parsed(draft.build(), null);
    }

    /**
     * Validates a whole key/value set (a parsed properties file) on top of the defaults. Missing keys keep
     * their defaults; unknown keys, bad values and oversized sets are rejected as a whole.
     */
    public static Parsed fromEntries(Map<String, String> entries) {
        if (entries.size() > 48) return new Parsed(null, "too-many-keys");
        Draft draft = new Draft(defaults());
        for (Map.Entry<String, String> e : entries.entrySet()) {
            String value = e.getValue();
            if (value == null || value.length() > 128) return new Parsed(null, "value-too-long:" + safe(e.getKey()));
            String error = apply(draft, e.getKey(), value);
            if (error != null) return new Parsed(null, error);
        }
        return new Parsed(draft.build(), null);
    }

    /** Applies a complete set of decoded properties without parsing shell assignment syntax. */
    public static Parsed updateEntries(Map<String, String> entries, CaptureConfig base) {
        if (entries == null || entries.isEmpty()) return new Parsed(null, "no-assignments");
        if (entries.size() > 48) return new Parsed(null, "too-many-keys");
        Draft draft = new Draft(base);
        for (Map.Entry<String, String> e : entries.entrySet()) {
            String value = e.getValue();
            if (value == null || value.length() > 128) return new Parsed(null, "value-too-long:" + safe(e.getKey()));
            String error = apply(draft, e.getKey(), value);
            if (error != null) return new Parsed(null, error);
        }
        return new Parsed(draft.build(), null);
    }

    public java.util.Map<String, String> toMap() {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put(OVERLAY, overlayEnabled ? "on" : "off");
        m.put(LOCATION, location);
        m.put(POSITION, position);
        m.put(TEXT_SIZE, Integer.toString(textSize));
        m.put(FIELDS, fieldsString());
        m.put(TARGET_FPS, targetFpsString());
        m.put(JPEG_SIZE, jpegSize);
        m.put(TEXT_COLOR, textColor);
        m.put(TELEMETRY_POSITION, telemetryPosition);
        m.put(PANEL_ENABLED, panelEnabled ? "on" : "off");
        m.put(PANEL_TEXT_COLOR, panelTextColor);
        m.put(PANEL_BACKGROUND_COLOR, panelBackgroundColor);
        m.put(PANEL_TEXT_SIZE, Integer.toString(panelTextSize));
        m.put(PANEL_ENVIRONMENT, panelEnvironment ? "on" : "off");
        m.put(PANEL_DEVICE, panelDevice ? "on" : "off");
        m.put(PANEL_CAPTURE, panelCapture ? "on" : "off");
        m.put(MQTT_HOST, mqttHost);
        m.put(MQTT_PORT, Integer.toString(mqttPort));
        m.put(MQTT_TOPIC, mqttTopic);
        m.put(MQTT_TLS, mqttTls ? "on" : "off");
        m.put(MQTT_STALE_SECONDS, Integer.toString(mqttStaleSeconds));
        m.put(FOCUS_MODE, focusMode);
        m.put(FOCUS_DISTANCE_DIOPTERS, Float.toString(focusDistanceDiopters));
        m.put(EXPOSURE_MODE, exposureMode);
        m.put(EXPOSURE_COMPENSATION, Integer.toString(exposureCompensation));
        m.put(AE_LOCK, aeLock ? "on" : "off");
        m.put(ISO, Integer.toString(iso));
        m.put(EXPOSURE_TIME_NS, Long.toString(exposureTimeNanos));
        m.put(WHITE_BALANCE, whiteBalance);
        m.put(AWB_LOCK, awbLock ? "on" : "off");
        m.put(JPEG_QUALITY, Integer.toString(jpegQuality));
        m.put(ORIENTATION_MODE, orientationMode);
        m.put(ROTATION_DEGREES, Integer.toString(rotationDegrees));
        m.put(MIRROR, mirror ? "on" : "off");
        return m;
    }

    public boolean hasField(int flag) { return (fields & flag) != 0; }
    public double targetFps() { return (double) targetFpsTenths / 10.0; }
    public long targetIntervalNanos() { return Math.max(1L, 10_000_000_000L / targetFpsTenths); }
    public String targetFpsString() { return String.format(Locale.ROOT, "%d.%d", targetFpsTenths / 10, targetFpsTenths % 10); }

    public String fieldsString() {
        if (fields == 0) return "none";
        List<String> names = new ArrayList<>();
        for (int i = 0; i < FIELD_NAMES.length; i++) if ((fields & (1 << i)) != 0) names.add(FIELD_NAMES[i]);
        return String.join(",", names);
    }

    /** Single-line, greppable description: key=value pairs in a fixed order. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : toMap().entrySet()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    static int parseFields(String value) {
        if (value.equals("none") || value.isEmpty()) return 0;
        int mask = 0;
        Set<String> seen = new HashSet<>();
        for (String name : value.split(",", -1)) {
            if (!seen.add(name)) return -1;
            if (name.equals("frames")) continue;
            int index = -1;
            for (int i = 0; i < FIELD_NAMES.length; i++) if (FIELD_NAMES[i].equals(name)) index = i;
            if (index < 0) return -1;
            mask |= 1 << index;
        }
        return mask;
    }

    private static long parseFpsTenths(String value) {
        if (!FPS_PATTERN.matcher(value).matches()) return -1;
        int dot = value.indexOf('.');
        int fractional = dot < 0 ? 0 : value.charAt(dot + 1) - '0';
        int end = dot < 0 ? value.length() : dot;
        long whole = 0;
        for (int i = 0; i < end; i++) {
            int digit = value.charAt(i) - '0';
            if (whole > (Long.MAX_VALUE - digit) / 10) return -2;
            whole = whole * 10 + digit;
        }
        if (whole > (Long.MAX_VALUE - fractional) / 10) return -2;
        return whole * 10 + fractional;
    }

    private static int parseInt(String value) {
        if (!value.matches("[0-9]{1,4}")) return -1;
        return Integer.parseInt(value);
    }

    private static int parseBoundedInt(String value, int digits) {
        if (!value.matches("[0-9]{1," + digits + "}")) return -1;
        try { return Integer.parseInt(value); } catch (NumberFormatException e) { return -1; }
    }

    private static int parseSignedInt(String value) {
        if (!value.matches("-?[0-9]{1,4}")) return Integer.MIN_VALUE;
        try { return Integer.parseInt(value); } catch (NumberFormatException e) { return Integer.MIN_VALUE; }
    }

    private static long parsePositiveLong(String value) {
        if (!value.matches("[0-9]{1,19}")) return -1;
        try { return Long.parseLong(value); } catch (NumberFormatException e) { return -1; }
    }

    private static boolean validMqttHost(String host) {
        if (host.isEmpty()) return true;
        if (host.length() > 253 || !MQTT_HOST_PATTERN.matcher(host).matches()) return false;
        for (String label : host.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) return false;
        }
        return true;
    }

    private static boolean validMqttTopic(String topic) {
        if (!MQTT_TOPIC_PATTERN.matcher(topic).matches() || topic.contains("//")) return false;
        String[] levels = topic.split("/", -1);
        for (int i = 0; i < levels.length; i++) {
            String level = levels[i];
            if (level.contains("#") && !(level.equals("#") && i == levels.length - 1)) return false;
            if (level.contains("+") && !level.equals("+")) return false;
        }
        return true;
    }

    private static boolean contains(String[] values, String value) {
        for (String v : values) if (v.equals(value)) return true;
        return false;
    }

    private static String safe(String s) {
        String t = s.length() > 30 ? s.substring(0, 30) : s;
        return t.replaceAll("[^A-Za-z0-9_.=-]", "?");
    }
}
