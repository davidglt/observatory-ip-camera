package local.termux.observatorycamera;

/** Pure conversions for Android battery and thermal values, including explicit unsupported states. */
final class DeviceTelemetryFormat {
    private DeviceTelemetryFormat() {}

    static Integer batteryPercent(int level, int scale) {
        if (level < 0 || scale <= 0) return null;
        return Math.max(0, Math.min(100, Math.round(level * 100f / scale)));
    }

    static Double batteryTemperatureC(int tenthsCelsius, int unavailableValue) {
        return tenthsCelsius == unavailableValue ? null : tenthsCelsius / 10.0;
    }

    static String charging(int status) {
        switch (status) {
            case 2: return "charging";
            case 5: return "full";
            case 3: case 4: return "not charging";
            case 1: return "unknown";
            default: return "unavailable";
        }
    }

    static String thermal(int status, boolean supported) {
        if (!supported) return "unsupported";
        switch (status) {
            case 0: return "none";
            case 1: return "light";
            case 2: return "moderate";
            case 3: return "severe";
            case 4: return "critical";
            case 5: return "emergency";
            case 6: return "shutdown";
            default: return "unavailable";
        }
    }
}
