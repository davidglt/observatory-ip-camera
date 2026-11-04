/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

import android.content.Context;

/** MQTT is owned by the explicitly started HTTP service and is never an autonomous Android service. */
final class TelemetryRuntime {
    private static final EnvironmentalTelemetry ENVIRONMENT = new EnvironmentalTelemetry();
    private static MqttEnvironmentalSubscriber subscriber;
    private static CaptureConfig activeConfig;
    private static byte[] credentialsFingerprint;

    private TelemetryRuntime() {}

    static synchronized void start(Context context) {
        reconfigure(context, CaptureSettings.load(context));
    }

    static synchronized void reconfigure(Context context, CaptureConfig config) {
        java.io.File credentials = new java.io.File(context.getFilesDir(), MqttCredentialsStore.FILE_NAME);
        byte[] fingerprint;
        try {
            fingerprint = MqttCredentialFingerprint.read(credentials);
        } catch (java.io.IOException e) {
            fingerprint = new byte[0];
        }
        boolean sameSource = sameMqttSource(activeConfig, config);
        if (sameSource && MqttCredentialFingerprint.matches(credentialsFingerprint, fingerprint)) {
            activeConfig = config;
            return;
        }
        stopSubscriber();
        if (!sameSource) ENVIRONMENT.reset(config.mqttHost.isEmpty() ? "disabled" : "connecting");
        activeConfig = config;
        if (credentialsFingerprint != null) java.util.Arrays.fill(credentialsFingerprint, (byte) 0);
        credentialsFingerprint = fingerprint;
        subscriber = new MqttEnvironmentalSubscriber(ENVIRONMENT);
        subscriber.start(context.getApplicationContext(), config);
    }

    static synchronized void stop() {
        stopSubscriber();
        activeConfig = null;
        if (credentialsFingerprint != null) java.util.Arrays.fill(credentialsFingerprint, (byte) 0);
        credentialsFingerprint = null;
        ENVIRONMENT.reset("disabled");
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

    private static boolean sameMqttSource(CaptureConfig a, CaptureConfig b) {
        return a != null && a.mqttHost.equals(b.mqttHost) && a.mqttPort == b.mqttPort
            && a.mqttTopic.equals(b.mqttTopic) && a.mqttTls == b.mqttTls;
    }

    private static void stopSubscriber() {
        if (subscriber != null) subscriber.stop();
        subscriber = null;
    }
}
