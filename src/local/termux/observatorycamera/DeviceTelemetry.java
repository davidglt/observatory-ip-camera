package local.termux.observatorycamera;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;

/** Reads Android's authoritative sticky battery broadcast and thermal-status API. */
final class DeviceTelemetry {
    final Integer batteryPercent;
    final Double batteryTemperatureC;
    final String charging;
    final String thermalStatus;

    private DeviceTelemetry(Integer batteryPercent, Double batteryTemperatureC, String charging, String thermalStatus) {
        this.batteryPercent = batteryPercent;
        this.batteryTemperatureC = batteryTemperatureC;
        this.charging = charging;
        this.thermalStatus = thermalStatus;
    }

    static DeviceTelemetry read(Context context) {
        Integer percent = null;
        Double temperature = null;
        String charging = "unavailable";
        try {
            Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) {
                int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                percent = DeviceTelemetryFormat.batteryPercent(level, scale);
                int rawTemperature = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
                temperature = DeviceTelemetryFormat.batteryTemperatureC(rawTemperature, Integer.MIN_VALUE);
                int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                charging = DeviceTelemetryFormat.charging(status);
            }
        } catch (RuntimeException ignored) { }
        String thermal = "unavailable";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                PowerManager power = context.getSystemService(PowerManager.class);
                if (power == null) thermal = "unavailable";
                else thermal = DeviceTelemetryFormat.thermal(power.getCurrentThermalStatus(), true);
            } catch (RuntimeException e) {
                thermal = "unavailable";
            }
        } else {
            thermal = DeviceTelemetryFormat.thermal(-1, false);
        }
        return new DeviceTelemetry(percent, temperature, charging, thermal);
    }

}
