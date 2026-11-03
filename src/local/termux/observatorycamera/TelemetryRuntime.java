package local.termux.observatorycamera;

import android.content.Context;

/** MQTT is owned by the explicitly started HTTP service and is never an autonomous Android service. */
final class TelemetryRuntime {
    private static final EnvironmentalTelemetry ENVIRONMENT = new EnvironmentalTelemetry();
    private static MqttEnvironmentalSubscriber subscriber;
    private static CaptureConfig activeConfig;
    private static long credentialsModified;

    private TelemetryRuntime() {}

    static synchronized void start(Context context) {
        reconfigure(context, CaptureSettings.load(context));
    }

    static synchronized void reconfigure(Context context, CaptureConfig config) {
        java.io.File credentials = new java.io.File(context.getFilesDir(), MqttCredentialsStore.FILE_NAME);
        long modified = credentials.exists() ? credentials.lastModified() : 0;
        if (sameMqttConfiguration(activeConfig, config) && credentialsModified == modified) return;
        stopSubscriber();
        activeConfig = config;
        credentialsModified = modified;
        subscriber = new MqttEnvironmentalSubscriber(ENVIRONMENT);
        subscriber.start(context.getApplicationContext(), config);
    }

    static synchronized void stop() {
        stopSubscriber();
        activeConfig = null;
        credentialsModified = 0;
        ENVIRONMENT.setConnectionState("disabled", null);
    }

    static synchronized void refresh(Context context) {
        reconfigure(context, CaptureSettings.load(context));
    }

    static EnvironmentalTelemetry environment() { return ENVIRONMENT; }
    static synchronized long staleMillis() {
        return activeConfig == null ? EnvironmentalTelemetry.DEFAULT_STALE_MILLIS
            : activeConfig.mqttStaleSeconds * 1000L;
    }

    private static boolean sameMqttConfiguration(CaptureConfig a, CaptureConfig b) {
        return a != null && a.mqttHost.equals(b.mqttHost) && a.mqttPort == b.mqttPort
            && a.mqttTopic.equals(b.mqttTopic) && a.mqttTls == b.mqttTls
            && a.mqttStaleSeconds == b.mqttStaleSeconds;
    }

    private static void stopSubscriber() {
        if (subscriber != null) subscriber.stop();
        subscriber = null;
    }
}
