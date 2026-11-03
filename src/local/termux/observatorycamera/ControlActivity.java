package local.termux.observatorycamera;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/**
 * Non-touch control entry for HTTP server start/stop/status (extras "command" and "request_id").
 * Declared in the manifest with android:permission="android.permission.DUMP", a signature/development
 * permission held by the adb shell and root but not by ordinary apps, so the platform rejects other
 * callers. Same-UID code and any app granted DUMP remain able to call it. The request id is only a log
 * correlation token, never authentication. Never prompts for permissions. The foreground service is
 * started only while this activity is resumed; otherwise the start is deferred until onResume.
 * Results are logged under ObservatoryControl as greppable CONTROL lines. Continuous capture commands
 * (continuous-start/stop/status) and settings (config-show/config-set/config-reset) use the same entry; continuous-start
 * keeps this activity open until the camera service reports running or an error, so the camera foreground
 * service is started and brought up while a visible activity exists.
 */
public final class ControlActivity extends Activity {
    private static final String TAG = ControlCommand.LOG_TAG;
    private ControlCommand pending;
    private boolean resumed;
    private ControlCommand awaiting;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable awaitTimeout = () -> finishAwaiting("failed", "reason=start-timeout (check continuous-status)");
    private static final long AWAIT_MS = 15000;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state == null) handle(getIntent());
        else finish();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    private void handle(Intent intent) {
        String id = intent.getStringExtra(ControlCommand.EXTRA_REQUEST_ID);
        ControlCommand command = ControlCommand.parse(intent.getStringExtra(ControlCommand.EXTRA_COMMAND), id, intent.getStringExtra(ControlCommand.EXTRA_CONFIG));
        intent.removeExtra(ControlCommand.EXTRA_CONFIG);
        intent.removeExtra(ControlCommand.EXTRA_COMMAND);
        intent.removeExtra(ControlCommand.EXTRA_REQUEST_ID);
        if (command == null) {
            Log.e(TAG, ControlCommand.line(ControlCommand.isValidRequestId(id) ? id : "-", "invalid", "error", "reason=invalid-command-or-request-id"));
            if (pending == null && awaiting == null) finish();
            return;
        }
        if (pending != null) Log.w(TAG, ControlCommand.line(pending.requestId, pending.name, "superseded", null));
        pending = command;
        run();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        run();
    }

    @Override protected void onPause() {
        resumed = false;
        super.onPause();
    }

    private void run() {
        ControlCommand command = pending;
        if (command == null) return;
        if (command.needsResumedActivity() && !resumed) {
            Log.i(TAG, ControlCommand.line(command.requestId, command.name, "deferred", "reason=activity-not-resumed"));
            return;
        }
        pending = null;
        switch (command.name) {
            case ControlCommand.START: start(command); break;
            case ControlCommand.STOP: stop(command); break;
            case ControlCommand.CONTINUOUS_START: continuousStart(command); break;
            case ControlCommand.CONTINUOUS_STOP: continuousStop(command); break;
            case ControlCommand.CONTINUOUS_STATUS: continuousStatus(command); break;
            case ControlCommand.CONFIG_SHOW: configShow(command); break;
            case ControlCommand.CONFIG_SET: configSet(command); break;
            case ControlCommand.CONFIG_RESET: configReset(command); break;
            case ControlCommand.CONFIG_EXPORT: configOutcome(command, CaptureSettings.store(this).materialize()); break;
            case ControlCommand.CONFIG_IMPORT: configOutcome(command, CaptureSettings.store(this).importPending()); break;
            default: status(command);
        }
        if (awaiting == null) finish();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(awaitTimeout);
        if (awaiting != null) ContinuousCaptureService.setListener(null);
        super.onDestroy();
    }

    private void continuousStart(ControlCommand command) {
        ContinuousStatus current = ContinuousCaptureService.getStatus();
        if (current.state.equals(ContinuousStatus.RUNNING)) {
            Log.i(TAG, ControlCommand.line(command.requestId, command.name, "started", "already=true state=running"));
            return;
        }
        if (!current.isActive()) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, ControlCommand.line(command.requestId, command.name, "failed", "reason=camera-permission-missing"));
                return;
            }
            String notifications = notificationState();
            if (!notifications.equals("ok")) {
                Log.e(TAG, ControlCommand.line(command.requestId, command.name, "failed", "reason=notifications-" + notifications));
                return;
            }
            String owner = CameraArbiter.shared().owner();
            if (!owner.equals(CameraArbiter.NONE)) {
                Log.e(TAG, ControlCommand.line(command.requestId, command.name, "failed", "reason=camera-busy owner=" + owner));
                return;
            }
        }
        awaiting = command;
        ContinuousCaptureService.setListener(() -> {
            ContinuousStatus s = ContinuousCaptureService.getStatus();
            if (s.state.equals(ContinuousStatus.RUNNING)) finishAwaiting("started", "state=running");
            else if (!s.isActive()) finishAwaiting("failed", "reason=" + (s.lastError == null ? "stopped" : s.lastError));
        });
        handler.postDelayed(awaitTimeout, AWAIT_MS);
        if (current.isActive()) return;
        try {
            startForegroundService(new Intent(this, ContinuousCaptureService.class)
                .setAction(ContinuousCaptureService.ACTION_START)
                .putExtra(ContinuousCaptureService.EXTRA_REQUEST_ID, command.requestId));
            Log.i(TAG, ControlCommand.line(command.requestId, command.name, "accepted", "waiting-for-camera"));
        } catch (RuntimeException e) {
            finishAwaiting("failed", "reason=start-exception " + e);
        }
    }

    private void finishAwaiting(String result, String detail) {
        ControlCommand command = awaiting;
        if (command == null) return;
        awaiting = null;
        handler.removeCallbacks(awaitTimeout);
        ContinuousCaptureService.setListener(null);
        if (result.equals("failed")) Log.e(TAG, ControlCommand.line(command.requestId, command.name, result, detail));
        else Log.i(TAG, ControlCommand.line(command.requestId, command.name, result, detail));
        if (pending == null) finish();
    }

    private void continuousStop(ControlCommand command) {
        stopService(new Intent(this, ContinuousCaptureService.class));
        Log.i(TAG, ControlCommand.line(command.requestId, command.name, "stop-requested", "state=" + ContinuousCaptureService.getStatus().state));
    }

    private void continuousStatus(ControlCommand command) {
        boolean camera = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        Log.i(TAG, ControlCommand.line(command.requestId, command.name, "continuous-status",
            ContinuousCaptureService.getStatus().describe(SystemClock.elapsedRealtimeNanos())
            + " camera=" + (camera ? "granted" : "denied") + " notifications=" + notificationState()
            + " owner=" + CameraArbiter.shared().owner()));
    }

    private void configShow(ControlCommand command) {
        ConfigStore.Loaded loaded = CaptureSettings.store(this).load();
        Log.i(TAG, ControlCommand.line(command.requestId, command.name, "config", loaded.config.describe()
            + " file=" + loaded.fileState + (loaded.error == null ? "" : " fileError=" + loaded.error)));
    }

    private void configSet(ControlCommand command) { configOutcome(command, CaptureSettings.store(this).update(command.config)); }

    private void configReset(ControlCommand command) { configOutcome(command, CaptureSettings.store(this).reset()); }

    /** Logs the saved configuration, or the rejection (the previous configuration is then unchanged). */
    private void configOutcome(ControlCommand command, ConfigStore.Outcome outcome) {
        if (outcome.config == null) {
            Log.e(TAG, ControlCommand.line(command.requestId, command.name, "failed", "reason=" + outcome.error));
            return;
        }
        Log.i(TAG, ControlCommand.line(command.requestId, command.name, "config", outcome.config.describe() + " applied=next-frame"));
    }

    private String notificationState() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return "permission-missing";
        return getSystemService(NotificationManager.class).areNotificationsEnabled() ? "ok" : "blocked";
    }

    private void start(ControlCommand command) {
        if (SnapshotHttpService.isRunning()) {
            Log.i(TAG, ControlCommand.line(command.requestId, command.name, "listening", "port=" + SnapshotHttpServer.DEFAULT_PORT + " already=true"));
            return;
        }
        String notifications = notificationState();
        if (!notifications.equals("ok")) {
            Log.e(TAG, ControlCommand.line(command.requestId, command.name, "failed", "reason=notifications-" + notifications));
            return;
        }
        try {
            startForegroundService(new Intent(this, SnapshotHttpService.class)
                .setAction(SnapshotHttpService.ACTION_START)
                .putExtra(SnapshotHttpService.EXTRA_REQUEST_ID, command.requestId));
            Log.i(TAG, ControlCommand.line(command.requestId, command.name, "accepted", "waiting-for-listener"));
        } catch (RuntimeException e) {
            Log.e(TAG, ControlCommand.line(command.requestId, command.name, "failed", "reason=start-exception " + e));
        }
    }

    private void stop(ControlCommand command) {
        stopService(new Intent(this, SnapshotHttpService.class));
        Log.i(TAG, ControlCommand.line(command.requestId, command.name, "stop-requested", "running=" + SnapshotHttpService.isRunning()));
    }

    private void status(ControlCommand command) {
        String error = SnapshotHttpService.getLastError();
        boolean camera = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        Log.i(TAG, ControlCommand.line(command.requestId, command.name, "status", "running=" + SnapshotHttpService.isRunning()
            + " port=" + SnapshotHttpServer.DEFAULT_PORT + " camera=" + (camera ? "granted" : "denied")
            + " notifications=" + notificationState() + (error == null ? "" : " lastError=" + error)));
    }
}
