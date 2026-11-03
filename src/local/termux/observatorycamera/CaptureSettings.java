package local.termux.observatorycamera;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;

/** Persists CaptureConfig in private SharedPreferences. Writes use apply(): visible in memory immediately, flushed to disk off the caller thread. */
final class CaptureSettings {
    private static final String FILE = "capture_config";

    private CaptureSettings() {}

    static CaptureConfig load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        Map<String, String> map = new HashMap<>();
        for (String key : CaptureConfig.defaults().toMap().keySet()) map.put(key, prefs.getString(key, null));
        return CaptureConfig.fromMap(map);
    }

    static void save(Context context, CaptureConfig config) {
        SharedPreferences.Editor editor = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit();
        for (Map.Entry<String, String> e : config.toMap().entrySet()) editor.putString(e.getKey(), e.getValue());
        editor.apply();
    }
}
