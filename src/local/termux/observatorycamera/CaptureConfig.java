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

    public static final int FIELD_LOCATION = 1;
    public static final int FIELD_DATETIME = 2;
    public static final int FIELD_FPS = 4;
    public static final int FIELD_FRAMES = 8;
    public static final int ALL_FIELDS = FIELD_LOCATION | FIELD_DATETIME | FIELD_FPS | FIELD_FRAMES;

    public static final String[] POSITIONS = {"bottom-left", "bottom-right", "top-left", "top-right"};
    public static final int MIN_TEXT_SIZE = 10;
    public static final int MAX_TEXT_SIZE = 96;
    public static final int MIN_FPS_TENTHS = 1;
    public static final int MAX_FPS_TENTHS = 20;
    public static final int MAX_ASSIGNMENTS_LENGTH = 200;
    private static final Pattern LOCATION_PATTERN = Pattern.compile("[A-Za-z0-9_.-]{1,24}");
    private static final Pattern FPS_PATTERN = Pattern.compile("[0-9](\\.[0-9])?");
    private static final String[] FIELD_NAMES = {"location", "datetime", "fps", "frames"};

    public final boolean overlayEnabled;
    public final String location;
    public final String position;
    public final int textSize;
    public final int fields;
    public final int targetFpsTenths;

    private CaptureConfig(boolean overlayEnabled, String location, String position, int textSize, int fields, int targetFpsTenths) {
        this.overlayEnabled = overlayEnabled;
        this.location = location;
        this.position = position;
        this.textSize = textSize;
        this.fields = fields;
        this.targetFpsTenths = targetFpsTenths;
    }

    /** Conservative defaults: overlay on, NYX, bottom-left, size 24 (at 720 px short side), all fields, 1.0 FPS target. */
    public static CaptureConfig defaults() {
        return new CaptureConfig(true, "NYX", "bottom-left", 24, ALL_FIELDS, 10);
    }

    /** Result of parsing: exactly one of config and error is non-null. */
    public static final class Parsed {
        public final CaptureConfig config;
        public final String error;
        Parsed(CaptureConfig config, String error) { this.config = config; this.error = error; }
    }

    /** Applies whitespace-separated key=value assignments on top of base. Unknown keys, duplicates and out-of-range values are rejected. */
    public static Parsed parse(String assignments, CaptureConfig base) {
        if (assignments == null || assignments.trim().isEmpty()) return new Parsed(null, "no-assignments");
        if (assignments.length() > MAX_ASSIGNMENTS_LENGTH) return new Parsed(null, "assignments-too-long");
        boolean overlay = base.overlayEnabled;
        String location = base.location;
        String position = base.position;
        int textSize = base.textSize;
        int fields = base.fields;
        int fps = base.targetFpsTenths;
        Set<String> seen = new HashSet<>();
        for (String part : assignments.trim().split("\\s+")) {
            int eq = part.indexOf('=');
            if (eq <= 0 || eq == part.length() - 1) return new Parsed(null, "malformed-assignment:" + safe(part));
            String key = part.substring(0, eq);
            String value = part.substring(eq + 1);
            if (!seen.add(key)) return new Parsed(null, "duplicate-key:" + safe(key));
            switch (key) {
                case OVERLAY:
                    if (value.equals("on")) overlay = true;
                    else if (value.equals("off")) overlay = false;
                    else return new Parsed(null, "invalid-overlay:expected-on-or-off");
                    break;
                case LOCATION:
                    if (!LOCATION_PATTERN.matcher(value).matches()) return new Parsed(null, "invalid-location:1-24-chars-of-A-Za-z0-9_.-");
                    location = value;
                    break;
                case POSITION:
                    if (!contains(POSITIONS, value)) return new Parsed(null, "invalid-position:expected-" + String.join("|", POSITIONS));
                    position = value;
                    break;
                case TEXT_SIZE: {
                    int n = parseInt(value);
                    if (n < MIN_TEXT_SIZE || n > MAX_TEXT_SIZE) return new Parsed(null, "invalid-text_size:expected-" + MIN_TEXT_SIZE + "-" + MAX_TEXT_SIZE);
                    textSize = n;
                    break;
                }
                case FIELDS: {
                    int mask = parseFields(value);
                    if (mask == 0) return new Parsed(null, "invalid-fields:expected-comma-list-of-" + String.join(",", FIELD_NAMES));
                    fields = mask;
                    break;
                }
                case TARGET_FPS: {
                    int tenths = parseFpsTenths(value);
                    if (tenths < MIN_FPS_TENTHS || tenths > MAX_FPS_TENTHS) return new Parsed(null, "invalid-target_fps:expected-0.1-to-2.0");
                    fps = tenths;
                    break;
                }
                default:
                    return new Parsed(null, "unknown-key:" + safe(key));
            }
        }
        return new Parsed(new CaptureConfig(overlay, location, position, textSize, fields, fps), null);
    }

    /** Rebuilds a config from persisted values; any missing or invalid value falls back to its default. */
    public static CaptureConfig fromMap(Map<String, String> map) {
        CaptureConfig result = defaults();
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (e.getValue() == null) continue;
            Parsed p = parse(e.getKey() + "=" + e.getValue(), result);
            if (p.config != null) result = p.config;
        }
        return result;
    }

    public java.util.Map<String, String> toMap() {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put(OVERLAY, overlayEnabled ? "on" : "off");
        m.put(LOCATION, location);
        m.put(POSITION, position);
        m.put(TEXT_SIZE, Integer.toString(textSize));
        m.put(FIELDS, fieldsString());
        m.put(TARGET_FPS, targetFpsString());
        return m;
    }

    public boolean hasField(int flag) { return (fields & flag) != 0; }
    public double targetFps() { return targetFpsTenths / 10.0; }
    public long targetIntervalNanos() { return 1_000_000_000L * 10L / targetFpsTenths; }
    public String targetFpsString() { return String.format(Locale.ROOT, "%d.%d", targetFpsTenths / 10, targetFpsTenths % 10); }

    public String fieldsString() {
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
        int mask = 0;
        for (String name : value.split(",", -1)) {
            int index = -1;
            for (int i = 0; i < FIELD_NAMES.length; i++) if (FIELD_NAMES[i].equals(name)) index = i;
            if (index < 0 || (mask & (1 << index)) != 0) return 0;
            mask |= 1 << index;
        }
        return mask;
    }

    private static int parseFpsTenths(String value) {
        if (!FPS_PATTERN.matcher(value).matches()) return -1;
        int dot = value.indexOf('.');
        return dot < 0 ? Integer.parseInt(value) * 10 : Integer.parseInt(value.substring(0, dot)) * 10 + Integer.parseInt(value.substring(dot + 1));
    }

    private static int parseInt(String value) {
        if (!value.matches("[0-9]{1,4}")) return -1;
        return Integer.parseInt(value);
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
