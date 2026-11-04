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
import java.io.File;

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

    private static volatile boolean running;
    private static volatile String lastError;
    private static volatile Listener listener;

    public static boolean isRunning() { return running; }
    public static String getLastError() { return lastError; }
    public static void setListener(Listener value) { listener = value; }

    private final Handler main = new Handler(Looper.getMainLooper());
    private SnapshotHttpServer server;
    private String requestId = "-";

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
            startForeground(NOTIFICATION_ID, buildNotification("Starting HTTP snapshot server..."));
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
        if (server == null) {
            server = new SnapshotHttpServer(new File(getFilesDir(), "snapshot.jpg"), SnapshotHttpServer.DEFAULT_PORT,
                message -> Log.i(TAG, message), new AdminTokenStore(getFilesDir()), new SnapshotAdminApi(this),
                () -> new SnapshotHttpServer.ApiResult(200, "OK", TelemetrySnapshot.read(this).toJson()));
            server.setOnTerminated(() -> main.post(() -> {
                if (server != null) fail("HTTP server thread ended unexpectedly.");
            }));
        }
        Log.w(TAG, "HTTP is exposed on all IPv4 interfaces without TLS. Trusted LAN only; bearer token is plaintext on this network.");
        try {
            server.start();
        } catch (Exception e) {
            fail("Could not listen on port " + SnapshotHttpServer.DEFAULT_PORT + ": " + e.getMessage());
            return;
        }
        TelemetryRuntime.start(this);
        manager.notify(NOTIFICATION_ID, buildNotification("Listening on port " + server.getLocalPort()
            + ". Plaintext HTTP; viewer is public: trusted LAN only."));
        setState(true, null);
        Log.i(ControlCommand.LOG_TAG, ControlCommand.line(requestId, "start", "listening", "port=" + server.getLocalPort()));
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
        if (current != null) current.stop();
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
    }

    @Override public void onDestroy() {
        TelemetryRuntime.stop();
        SnapshotHttpServer current = server;
        server = null;
        if (current != null) current.stop();
        if (running) setState(false, lastError);
        super.onDestroy();
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "HTTP snapshot server", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shown while the snapshot HTTP server is running.");
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
