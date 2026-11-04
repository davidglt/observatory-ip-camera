/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.OrientationEventListener;
import android.util.Size;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.ArrayList;
import java.util.TimeZone;

public final class MainActivity extends Activity {
    private static final String TAG = "ObservatoryCamera";
    private final Handler handler = new Handler();
    private TextView status;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private HandlerThread worker;
    private int generation;
    private boolean busy;
    private boolean resumed;
    private boolean capturePending;
    private String captureId = "-";
    private boolean noPrompt;
    private boolean permissionRequested;
    private TextView serverStatus;
    private boolean serverStartPending;
    private boolean notificationPermissionRequested;
    private static final String STATE_DEVICE_ORIENTATION = "deviceOrientation";
    private OrientationEventListener orientationListener;
    private int lastValidDeviceOrientation = -1;
    private final Runnable timeout = () -> fail("Capture timed out.");

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            int saved = state.getInt(STATE_DEVICE_ORIENTATION, -1);
            if (saved >= 0 && saved < 360) lastValidDeviceOrientation = saved;
        }
        orientationListener = new OrientationEventListener(this) {
            @Override public void onOrientationChanged(int angle) {
                // ORIENTATION_UNKNOWN (device flat/ambiguous) keeps the last valid angle.
                if (angle != ORIENTATION_UNKNOWN && angle >= 0 && angle < 360) lastValidDeviceOrientation = angle;
            }
        };
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(p, p, p, p);
        status = new TextView(this);
        status.setTextSize(18);
        layout.addView(status);
        Button capture = new Button(this);
        capture.setText("Capture JPEG");
        capture.setOnClickListener(v -> requestCapture());
        layout.addView(capture);
        serverStatus = new TextView(this);
        serverStatus.setTextSize(16);
        serverStatus.setPadding(0, p, 0, 0);
        layout.addView(serverStatus);
        Button start = new Button(this);
        start.setText("Start server");
        start.setOnClickListener(v -> requestServerStart());
        layout.addView(start);
        Button stop = new Button(this);
        stop.setText("Stop server");
        stop.setOnClickListener(v -> stopServer());
        layout.addView(stop);
        TextView warning = new TextView(this);
        warning.setText("HTTP is unencrypted when enabled. Viewer/snapshots/telemetry are public; the admin token is exposed to network observers over HTTP.");
        layout.addView(warning);
        setContentView(layout);
        updateServerStatus();
        report("Observatory IP Camera " + appVersion() + "\nReady. No network streaming.");
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "unknown";
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (orientationListener.canDetectOrientation()) orientationListener.enable();
        else Log.w(TAG, "Device orientation sensor unavailable; JPEG orientation will use the fallback.");
        SnapshotHttpService.setListener(this::updateServerStatus);
        updateServerStatus();
        tryPendingServerStart();
        consumeCaptureIntent();
        tryPendingCapture();
    }

    private void updateServerStatus() {
        String error = SnapshotHttpService.getLastError();
        String endpoints = SnapshotHttpService.networkStatus(CaptureSettings.load(this));
        if (SnapshotHttpService.isRunning()) serverStatus.setText("Camera server: running. " + endpoints);
        else if (error != null) serverStatus.setText("Camera server: stopped. " + error + " " + endpoints);
        else serverStatus.setText("Camera server: stopped. " + endpoints);
    }

    private boolean notificationsAllowed() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        return getSystemService(NotificationManager.class).areNotificationsEnabled();
    }

    private void requestServerStart() {
        if (!resumed) return;
        if (SnapshotHttpService.isRunning()) { updateServerStatus(); return; }
        if (Build.VERSION.SDK_INT >= 33 && !notificationPermissionRequested
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionRequested = true;
            serverStartPending = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
            return;
        }
        launchServer();
    }

    private void tryPendingServerStart() {
        if (!serverStartPending || notificationPermissionRequested || !resumed) return;
        serverStartPending = false;
        launchServer();
    }

    private void launchServer() {
        serverStartPending = false;
        if (!notificationsAllowed()) {
            serverStatus.setText("HTTP server: not started. Notifications are blocked for this app; allow them in system settings so the Stop notification can be shown.");
            return;
        }
        try {
            startForegroundService(new Intent(this, SnapshotHttpService.class).setAction(SnapshotHttpService.ACTION_START));
            serverStatus.setText("HTTP server: start requested...");
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not start HTTP service", e);
            serverStatus.setText("HTTP server: could not start. " + e);
        }
    }

    private void stopServer() {
        serverStartPending = false;
        stopService(new Intent(this, SnapshotHttpService.class));
        updateServerStatus();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (lastValidDeviceOrientation >= 0) state.putInt(STATE_DEVICE_ORIENTATION, lastValidDeviceOrientation);
    }

    @Override protected void onDestroy() {
        orientationListener.disable();
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        consumeCaptureIntent();
        tryPendingCapture();
    }

    private void consumeCaptureIntent() {
        if (getIntent().getBooleanExtra("capture", false)) {
            getIntent().removeExtra("capture");
            boolean silent = getIntent().getBooleanExtra("no_prompt", false);
            getIntent().removeExtra("no_prompt");
            String id = getIntent().getStringExtra(ControlCommand.EXTRA_REQUEST_ID);
            getIntent().removeExtra(ControlCommand.EXTRA_REQUEST_ID);
            if (!busy) {
                capturePending = true;
                noPrompt = silent;
                captureId = ControlCommand.isValidRequestId(id) ? id : "-";
            }
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) tryPendingCapture();
    }

    private void requestCapture() {
        if (busy) return;
        noPrompt = false;
        captureId = "-";
        capturePending = true;
        tryPendingCapture();
    }

    private void tryPendingCapture() {
        if (!capturePending || busy || permissionRequested || isFinishing()) return;
        if (!resumed || !hasWindowFocus()) {
            report("Capture pending: waiting for a resumed activity with window focus.");
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (noPrompt) {
                capturePending = false;
                Log.e(ControlCommand.LOG_TAG, ControlCommand.line(captureId, "capture", "failed", "reason=camera-permission-missing"));
                report("Camera permission missing; not prompting (no_prompt).");
                return;
            }
            permissionRequested = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
            return;
        }
        capturePending = false;
        startCapture();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 2) {
            notificationPermissionRequested = false;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                tryPendingServerStart();
            } else {
                serverStartPending = false;
                serverStatus.setText("HTTP server: not started. Notification permission denied; it is required so the running server stays visible and stoppable.");
            }
            return;
        }
        if (request != 1) return;
        permissionRequested = false;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            // A paused activity cancels its pending request; do not revive it here.
            tryPendingCapture();
        } else {
            capturePending = false;
            Log.e(ControlCommand.LOG_TAG, ControlCommand.line(captureId, "capture", "failed", "reason=camera-permission-denied"));
            report("Camera permission denied.");
        }
    }

    private void startCapture() {
        if (!CameraArbiter.shared().acquire(CameraArbiter.SINGLE)) {
            String owner = CameraArbiter.shared().owner();
            Log.e(ControlCommand.LOG_TAG, ControlCommand.line(captureId, "capture", "failed", "reason=camera-busy owner=" + owner));
            report("Camera is busy (owner: " + owner + "); not opening a second camera.");
            return;
        }
        busy = true;
        final int token = ++generation;
        handler.postDelayed(timeout, 15000);
        report("Opening camera...");
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            String selected = null;
            for (String id : manager.getCameraIdList()) {
                if (Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK).equals(manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING))) { selected = id; break; }
            }
            if (selected == null) throw new IllegalStateException("No rear camera found.");
            CameraCharacteristics info = manager.getCameraCharacteristics(selected);
            StreamConfigurationMap map = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.JPEG);
            if (sizes == null || sizes.length == 0) throw new IllegalStateException("JPEG capture unavailable.");
            ArrayList<CameraCapabilities.JpegSize> jpegSizes = new ArrayList<>();
            for (Size candidate : sizes) jpegSizes.add(new CameraCapabilities.JpegSize(candidate.getWidth(), candidate.getHeight(),
                map.getOutputMinFrameDuration(ImageFormat.JPEG, candidate), map.getOutputStallDuration(ImageFormat.JPEG, candidate)));
            CameraCapabilities capabilities = new CameraCapabilities(jpegSizes, CameraCapabilityReader.controls(info));
            CaptureConfig captureConfig = CaptureSettings.load(this);
            String invalid = capabilities.validate(captureConfig);
            if (invalid != null) throw new IllegalArgumentException(invalid);
            CameraCapabilities.JpegSize selectedSize = capabilities.select(captureConfig);
            Size size = new Size(selectedSize.width, selectedSize.height);
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.JPEG, 2);
            worker = new HandlerThread("single-capture");
            worker.start();
            reader.setOnImageAvailableListener(source -> {
                if (token != generation) return;
                long wall = System.currentTimeMillis();
                try {
                    byte[] data;
                    int imageWidth;
                    int imageHeight;
                    try (Image image = source.acquireLatestImage()) {
                        if (image == null) return;
                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        if (buffer.remaining() > CameraCapabilities.MAX_JPEG_BYTES)
                            throw new IOException("JPEG exceeds the application byte limit.");
                        data = new byte[buffer.remaining()];
                        buffer.get(data);
                        imageWidth = image.getWidth();
                        imageHeight = image.getHeight();
                    }
                    FrameOverlay.Result r = FrameOverlay.process(data, captureConfig, Double.NaN, wall, TimeZone.getDefault());
                    if (r.jpeg.length > CameraCapabilities.MAX_JPEG_BYTES)
                        throw new IOException("JPEG output exceeds the application byte limit.");
                    if (!SnapshotPublisher.publish(getFilesDir(), "snapshot.jpg", r.jpeg, () -> token == generation)) return;
                    ContinuousCaptureService.recordEffectiveImageTransform(captureConfig, r);
                    String overlayMode = r.rendered ? "rendered" : r.transformed ? "transformed" : "passthrough";
                    Log.i(ControlCommand.LOG_TAG, ControlCommand.line(captureId, "capture", "saved", "bytes=" + r.jpeg.length + " size=" + imageWidth + "x" + imageHeight + " overlay=" + overlayMode + " processing_ms=" + r.processingNanos / 1_000_000L));
                    handler.post(() -> {
                        if (token != generation) return;
                        report("Snapshot saved: " + imageWidth + "x" + imageHeight + ", " + r.jpeg.length + " bytes. File: snapshot.jpg");
                        closeCapture();
                    });
                } catch (Exception e) { handler.post(() -> { if (token == generation) fail(e.toString()); }); }
            }, new Handler(worker.getLooper()));
            final Integer sensorValue = info.get(CameraCharacteristics.SENSOR_ORIENTATION);
            final int sensorOrientation = sensorValue == null ? 0 : sensorValue;
            final boolean frontFacing = Integer.valueOf(CameraCharacteristics.LENS_FACING_FRONT).equals(info.get(CameraCharacteristics.LENS_FACING));
            manager.openCamera(selected, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) {
                    if (token != generation) { device.close(); return; }
                    camera = device;
                    try {
                        device.createCaptureSession(Collections.singletonList(reader.getSurface()), new CameraCaptureSession.StateCallback() {
                            @Override public void onConfigured(CameraCaptureSession configured) {
                                if (token != generation) { configured.close(); return; }
                                session = configured;
                                try {
                                    CaptureRequest.Builder request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                                    request.addTarget(reader.getSurface());
                                    final int snapshotAngle = lastValidDeviceOrientation;
                                    final boolean fallback = snapshotAngle < 0;
                                    final int deviceOrientation = fallback ? 0 : JpegOrientation.roundDeviceOrientation(snapshotAngle);
                                    final int jpegOrientation = JpegOrientation.calculate(captureConfig,
                                        sensorOrientation, deviceOrientation, frontFacing);
                                    request.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation);
                                    Camera2RequestMapper.apply(request, captureConfig, capabilities.controls,
                                        selectedSize.minFrameDurationNanos, true);
                                    Log.i(TAG, "JPEG orientation: sensor=" + sensorOrientation + ", device=" + deviceOrientation
                                        + ", jpeg=" + jpegOrientation + ", fallback=" + fallback
                                        + (fallback ? " (no valid reading; assumed device angle 0, not measured)" : " (measured)"));
                                    report("Capturing JPEG...");
                                    configured.capture(request.build(), new CameraCaptureSession.CaptureCallback() {
                                        @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r,
                                                                                 android.hardware.camera2.TotalCaptureResult result) {
                                            ContinuousCaptureService.recordEffectiveControls(result, captureConfig);
                                        }
                                        @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, CaptureFailure failure) {
                                            if (token == generation) fail("Capture failed: " + failure.getReason());
                                        }
                                    }, handler);
                                } catch (Exception e) { fail(e.toString()); }
                            }
                            @Override public void onConfigureFailed(CameraCaptureSession s) {
                                s.close();
                                if (token == generation) fail("Camera session configuration failed.");
                            }
                        }, handler);
                    } catch (Exception e) { fail(e.toString()); }
                }
                @Override public void onDisconnected(CameraDevice device) { device.close(); if (token == generation) fail("Camera disconnected."); }
                @Override public void onError(CameraDevice device, int error) { device.close(); if (token == generation) fail("Camera error: " + error); }
            }, handler);
        } catch (Exception e) { fail(e.toString()); }
    }

    private void report(String message) { status.setText(message); Log.i(TAG, message); }
    private void fail(String message) {
        report("Error: " + message);
        if (busy) Log.e(ControlCommand.LOG_TAG, ControlCommand.line(captureId, "capture", "failed", "reason=" + message));
        closeCapture();
    }
    private void closeCapture() {
        ++generation;
        handler.removeCallbacks(timeout);
        if (session != null) { session.close(); session = null; }
        if (camera != null) { camera.close(); camera = null; }
        if (reader != null) { reader.setOnImageAvailableListener(null, null); reader.close(); reader = null; }
        if (worker != null) { worker.quitSafely(); worker = null; }
        if (busy) CameraArbiter.shared().release(CameraArbiter.SINGLE);
        busy = false;
    }
    @Override protected void onPause() {
        resumed = false;
        if (capturePending || busy) Log.e(ControlCommand.LOG_TAG, ControlCommand.line(captureId, "capture", "failed", "reason=activity-paused"));
        if (capturePending) report("Pending capture cancelled: activity paused.");
        capturePending = false;
        orientationListener.disable();
        SnapshotHttpService.setListener(null);
        closeCapture();
        super.onPause();
    }
}
