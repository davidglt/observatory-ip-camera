/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** MQTT is owned by the explicitly started HTTP service and is never an autonomous Android service. */
final class TelemetryRuntime {
    private static final long PROCESS_SAMPLE_INTERVAL_SECONDS = 5;
    private static final EnvironmentalTelemetry ENVIRONMENT = new EnvironmentalTelemetry();
    private static final ProcessMetrics PROCESS_METRICS = new ProcessMetrics();
    private static MqttEnvironmentalSubscriber subscriber;
    private static CaptureConfig activeConfig;
    private static byte[] credentialsFingerprint;
    private static ScheduledExecutorService processSampler;
    private static ScheduledFuture<?> processSamplerTask;

    private TelemetryRuntime() {}

    static synchronized void start(Context context) {
        reconfigure(context, CaptureSettings.load(context));
    }

    static synchronized void reconfigure(Context context, CaptureConfig config) {
        startProcessSampler(context.getApplicationContext());
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
        stopProcessSampler();
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
    static ProcessMetrics.Snapshot processMetrics() {
        return PROCESS_METRICS.snapshot(SystemClock.elapsedRealtime());
    }

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

    private static synchronized void startProcessSampler(Context context) {
        if (processSamplerTask != null && !processSamplerTask.isDone() && !processSampler.isShutdown()) return;
        PROCESS_METRICS.reset();
        processSampler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "observatory-process-telemetry");
            thread.setDaemon(true);
            return thread;
        });
        Context application = context.getApplicationContext();
        processSamplerTask = processSampler.scheduleAtFixedRate(
            () -> sampleProcess(application), 0, PROCESS_SAMPLE_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private static synchronized void stopProcessSampler() {
        if (processSampler != null) processSampler.shutdownNow();
        processSampler = null;
        processSamplerTask = null;
        PROCESS_METRICS.reset();
    }

    private static void sampleProcess(Context context) {
        long elapsedMillis = SystemClock.elapsedRealtime();
        long cpuMillis = -1;
        Long pssKb = null;
        Long availableRamKb = null;
        Long totalRamKb = null;
        Boolean lowMemory = null;
        try { cpuMillis = Process.getElapsedCpuTime(); } catch (RuntimeException ignored) { }
        try {
            long value = Debug.getPss();
            if (value >= 0) pssKb = value;
        } catch (RuntimeException ignored) { }
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager != null) {
                ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
                manager.getMemoryInfo(memory);
                availableRamKb = memory.availMem / 1024L;
                totalRamKb = memory.totalMem / 1024L;
                lowMemory = memory.lowMemory;
            }
        } catch (RuntimeException ignored) { }
        Long uptimeMillis = null;
        try { uptimeMillis = Math.max(0, elapsedMillis - Process.getStartElapsedRealtime()); }
        catch (RuntimeException ignored) { }
        try {
            PROCESS_METRICS.record(elapsedMillis, cpuMillis, pssKb, availableRamKb, totalRamKb, lowMemory, uptimeMillis);
        } catch (RuntimeException ignored) { }
    }
}
