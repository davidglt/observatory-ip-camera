package local.termux.observatorycamera;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

/**
 * Non-touch control entry for HTTP server start/stop/status (extras "command" and "request_id").
 * Declared in the manifest with android:permission="android.permission.DUMP", a signature/development
 * permission held by the adb shell and root but not by ordinary apps, so the platform rejects other
 * callers. Same-UID code and any app granted DUMP remain able to call it. The request id is only a log
 * correlation token, never authentication. Never prompts for permissions. The foreground service is
 * started only while this activity is resumed; otherwise the start is deferred until onResume.
 * Results are logged under ObservatoryControl as greppable CONTROL lines.
 */
public final class ControlActivity extends Activity {
    private static final String TAG = ControlCommand.LOG_TAG;
    private ControlCommand pending;
    private boolean resumed;

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
        ControlCommand command = ControlCommand.parse(intent.getStringExtra(ControlCommand.EXTRA_COMMAND), id);
        intent.removeExtra(ControlCommand.EXTRA_COMMAND);
        intent.removeExtra(ControlCommand.EXTRA_REQUEST_ID);
        if (command == null) {
            Log.e(TAG, ControlCommand.line(ControlCommand.isValidRequestId(id) ? id : "-", "invalid", "error", "reason=invalid-command-or-request-id"));
            if (pending == null) finish();
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
        if (command.name.equals(ControlCommand.START) && !resumed) {
            Log.i(TAG, ControlCommand.line(command.requestId, command.name, "deferred", "reason=activity-not-resumed"));
            return;
        }
        pending = null;
        if (command.name.equals(ControlCommand.START)) start(command);
        else if (command.name.equals(ControlCommand.STOP)) stop(command);
        else status(command);
        finish();
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
