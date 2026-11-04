/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * Owns the snapshot HTTP server. Started only by an explicit user action in MainActivity or the shell-protected ControlActivity (ACTION_START)
 * and stopped by the Stop button or the notification Stop action. START_NOT_STICKY: if Android kills the
 * process the server is not resurrected. No boot receiver, wake lock or camera access.
 * Not declared with a foregroundServiceType: targetSdk 33 does not require one (see README).
 */
public final class SnapshotHttpService extends Service {
    public static final String ACTION_START = "local.termux.observatorycamera.action.START_SERVER";
    public static final String ACTION_STOP = "local.termux.observatorycamera.action.STOP_SERVER";
    public static final String EXTRA_REQUEST_ID = "request_id";
    private static final String TAG = "SnapshotHttp";
    private static final String CHANNEL_ID = "snapshot_http";
    private static final int NOTIFICATION_ID = 1;

    /** Notified on the main thread whenever the confirmed state changes. */
    public interface Listener { void onServerStateChanged(); }

    static final class Telemetry {
        final String state;
        final String requestedListeners;
        final String effectiveListeners;
        final String listenerError;
        final int activeConnections;
        final int activeStreams;

        Telemetry(String state, String requestedListeners, String effectiveListeners, String listenerError,
                  int activeConnections, int activeStreams) {
            this.state = state;
            this.requestedListeners = requestedListeners;
            this.effectiveListeners = effectiveListeners;
            this.listenerError = listenerError;
            this.activeConnections = activeConnections;
            this.activeStreams = activeStreams;
        }
    }

    private static volatile boolean running;
    private static volatile String lastError;
    private static volatile String boundEndpoints = "none";
    private static volatile String networkError;
    private static volatile String certificateStatus = "not configured";
    private static volatile Listener listener;
    private static volatile SnapshotHttpService instance;

    public static boolean isRunning() { return running; }
    public static String getLastError() { return lastError; }
    static String getNetworkError() { return networkError; }
    public static void setListener(Listener value) { listener = value; }
    static String networkStatus(CaptureConfig requested) {
        return "Requested: " + describeRequested(requested) + "; bound: " + boundEndpoints
            + (networkError == null ? "" : "; error: " + networkError) + "; certificate: " + certificateStatus;
    }

    static Telemetry telemetry(CaptureConfig requested) {
        SnapshotHttpService current = instance;
        String state = running ? "running" : lastError == null ? "stopped" : "error";
        String listenerError = networkError;
        if (listenerError == null)
            listenerError = current == null || current.serverCounters.listenerError == null
                ? "none" : current.serverCounters.listenerError;
        return new Telemetry(state, describeRequested(requested), boundEndpoints,
            listenerError,
            current == null ? 0 : current.serverCounters.activeConnections.get(),
            current == null ? 0 : current.serverCounters.activeStreams.get());
    }

    private static String describeRequested(CaptureConfig config) {
        java.util.List<String> endpoints = new java.util.ArrayList<>();
        for (String address : config.listenAddresses.split(",")) {
            if (config.httpEnabled) endpoints.add("http://" + address + ":" + config.httpPort);
            if (config.httpsEnabled) endpoints.add("https://" + address + ":" + config.httpsPort);
        }
        return String.join(", ", endpoints);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final SnapshotHttpServer.Counters serverCounters = new SnapshotHttpServer.Counters();
    private volatile SnapshotHttpServer server;
    private CaptureConfig serverConfig;
    private String requestId = "-";

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
    }

    private static void setState(boolean isRunning, String error) {
        running = isRunning;
        lastError = error;
        Listener l = listener;
        if (l != null) l.onServerStateChanged();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        String id = intent == null ? null : intent.getStringExtra(EXTRA_REQUEST_ID);
        requestId = ControlCommand.isValidRequestId(id) ? id : "-";
        if (ACTION_START.equals(action)) handleStart();
        else handleStop();
        return START_NOT_STICKY;
    }

    private void handleStart() {
        // Must call startForeground promptly after startForegroundService().
        try {
            createChannel();
            startForeground(NOTIFICATION_ID, buildNotification("Starting camera network listeners..."));
        } catch (RuntimeException e) {
            fail("Could not enter foreground: " + e);
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
        if (!manager.areNotificationsEnabled() || (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE)) {
            fail("Notifications are disabled; the server is not started without a visible Stop notification.");
            return;
        }
        if (server != null && server.isRunning()) {
            setState(true, null);
            return;
        }
        ConfigStore.Loaded loaded = CaptureSettings.store(this).load();
        if (loaded.fileState.equals("corrupt")) {
            networkError = "invalid-config-file:" + loaded.error;
            fail("The settings file is invalid; listeners were not started. " + networkError);
            return;
        }
        CaptureConfig config = loaded.config;
        serverConfig = config;
        networkError = null;
        if (config.httpEnabled)
            Log.w(TAG, "HTTP is unencrypted and exposes the admin token to network observers. Use only a trusted LAN.");
        try {
            server = createServer(config);
            server.start();
        } catch (Exception e) {
            networkError = safeNetworkError(e);
            fail("Could not apply requested network listeners: " + networkError);
            return;
        }
        boundEndpoints = String.join(", ", server.getBoundEndpoints());
        certificateStatus = certificateSummary(config);
        serverCounters.listenerError = null;
        TelemetryRuntime.start(this);
        manager.notify(NOTIFICATION_ID, buildNotification("Listening on " + boundEndpoints
            + ". Viewer and public telemetry remain unauthenticated."));
        setState(true, null);
        Log.i(ControlCommand.LOG_TAG, ControlCommand.line(requestId, "start", "listening",
            "endpoints=" + boundEndpoints.replaceAll("[^A-Za-z0-9:/., -]", "-")));
    }

    private SnapshotHttpServer createServer(CaptureConfig config) throws Exception {
        TlsCertificateStore.Identity identity = config.httpsEnabled ? TlsCertificateStore.load(getFilesDir()) : null;
        if (identity != null) TlsCertificateStore.writePublicCertificate(getFilesDir(), identity);
        SnapshotHttpServer created = new SnapshotHttpServer(new File(getFilesDir(), "snapshot.jpg"), config,
            identity == null ? null : identity.context, identity == null ? null : identity.publicPem(),
            message -> Log.i(TAG, message), new AdminTokenStore(getFilesDir()), new SnapshotAdminApi(this),
            () -> new SnapshotHttpServer.ApiResult(200, "OK", TelemetrySnapshot.read(this).toJson()),
            readProjectLicense(), serverCounters);
        created.setOnTerminated(() -> main.post(() -> {
            if (server == created) fail("Network listener thread ended unexpectedly.");
        }));
        return created;
    }

    private String readProjectLicense() throws IOException {
        try (InputStream input = getAssets().open("LICENSE"); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > 65536) throw new IOException("Bundled license exceeds the allowed size.");
                output.write(buffer, 0, count);
            }
            return output.toString("UTF-8");
        }
    }

    static String applyNetworkSettings(CaptureConfig config) {
        SnapshotHttpService current = instance;
        if (current == null || !current.running || current.server == null) return null;
        return current.replaceNetworkSettings(config);
    }

    private synchronized String replaceNetworkSettings(CaptureConfig config) {
        if (sameNetworkConfig(serverConfig, config)) return null;
        SnapshotHttpServer replacement = null;
        try {
            replacement = createServer(config);
            replacement.start();
        } catch (Exception e) {
            if (replacement != null) replacement.stop();
            networkError = safeNetworkError(e);
            return networkError;
        }
        SnapshotHttpServer previous = server;
        server = replacement;
        serverConfig = config;
        boundEndpoints = String.join(", ", replacement.getBoundEndpoints());
        certificateStatus = certificateSummary(config);
        networkError = null;
        serverCounters.listenerError = null;
        if (previous != null) previous.stop();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification("Listening on " + boundEndpoints
            + ". Viewer and public telemetry remain unauthenticated."));
        return null;
    }

    private static boolean sameNetworkConfig(CaptureConfig a, CaptureConfig b) {
        return a != null && a.httpEnabled == b.httpEnabled && a.httpPort == b.httpPort
            && a.httpsEnabled == b.httpsEnabled && a.httpsPort == b.httpsPort
            && a.listenAddresses.equals(b.listenAddresses);
    }

    private static String safeNetworkError(Exception error) {
        String message = error.getMessage();
        if (message == null) return "network-configuration-unavailable";
        return message.replaceAll("[^A-Za-z0-9_.:-]", "-");
    }

    private static String certificateSummary(CaptureConfig config) {
        if (!config.httpsEnabled) return "HTTPS disabled";
        try {
            TlsCertificateStore.Identity identity = TlsCertificateStore.load(
                instance == null ? null : instance.getFilesDir());
            return "SHA-256 " + identity.fingerprint + ", expires " + identity.expiresAt;
        } catch (Exception e) {
            return "certificate unavailable";
        }
    }

    private void handleStop() {
        shutdown();
        setState(false, null);
        Log.i(ControlCommand.LOG_TAG, ControlCommand.line(requestId, "stop", "stopped", null));
        stopSelf();
    }

    private void fail(String message) {
        Log.e(TAG, message);
        Log.e(ControlCommand.LOG_TAG, ControlCommand.line(requestId, "start", "failed", "reason=" + message));
        shutdown();
        setState(false, message);
        stopSelf();
    }

    private void shutdown() {
        TelemetryRuntime.stop();
        SnapshotHttpServer current = server;
        server = null;
        serverConfig = null;
        if (current != null) current.stop();
        boundEndpoints = "none";
        certificateStatus = "not serving";
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
    }

    @Override public void onDestroy() {
        TelemetryRuntime.stop();
        SnapshotHttpServer current = server;
        server = null;
        if (current != null) current.stop();
        boundEndpoints = "none";
        certificateStatus = "not serving";
        if (instance == this) instance = null;
        if (running) setState(false, lastError);
        super.onDestroy();
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Camera network listeners", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shown while camera viewer network listeners are running.");
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
            new Intent(this, SnapshotHttpService.class).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Observatory snapshot server")
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Stop", stop).build())
            .addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_view), "Open app", open).build())
            .build();
    }
}
