package local.termux.observatorycamera;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.util.Size;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.Collections;

public final class MainActivity extends Activity {
    private static final String TAG = "ObservatoryCamera";
    private final Handler handler = new Handler();
    private TextView status;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private int generation;
    private boolean busy;
    private final Runnable timeout = () -> fail("Capture timed out.");

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
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
        setContentView(layout);
        report("Observatory IP Camera 0.2.0\nReady. No network streaming.");
    }

    @Override protected void onResume() {
        super.onResume();
        if (getIntent().getBooleanExtra("capture", false)) {
            getIntent().removeExtra("capture");
            requestCapture();
        }
    }

    private void requestCapture() {
        if (busy) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
            return;
        }
        startCapture();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != 1) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startCapture();
        else report("Camera permission denied.");
    }

    private void startCapture() {
        if (busy || isFinishing() || !hasWindowFocus()) {
            report("Capture requires a visible, focused activity. Try again after unlocking the phone.");
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
            Size size = sizes[0];
            long target = 1280L * 720L;
            for (Size candidate : sizes) {
                if (Math.abs((long) candidate.getWidth() * candidate.getHeight() - target) < Math.abs((long) size.getWidth() * size.getHeight() - target)) size = candidate;
            }
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.JPEG, 2);
            reader.setOnImageAvailableListener(source -> {
                if (token != generation) return;
                try (Image image = source.acquireLatestImage()) {
                    if (image == null) return;
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] data = new byte[buffer.remaining()];
                    buffer.get(data);
                    File pending = new File(getFilesDir(), "snapshot.tmp");
                    try (FileOutputStream out = new FileOutputStream(pending)) { out.write(data); }
                    File destination = new File(getFilesDir(), "snapshot.jpg");
                    if (!pending.renameTo(destination)) throw new java.io.IOException("Could not publish snapshot.");
                    report("Snapshot saved: " + image.getWidth() + "x" + image.getHeight() + ", " + data.length + " bytes. File: snapshot.jpg");
                    handler.post(() -> { if (token == generation) closeCapture(); });
                } catch (Exception e) { fail(e.toString()); }
            }, handler);
            final Integer orientation = info.get(CameraCharacteristics.SENSOR_ORIENTATION);
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
                                    request.set(CaptureRequest.JPEG_ORIENTATION, orientation == null ? 0 : orientation);
                                    report("Capturing JPEG...");
                                    configured.capture(request.build(), new CameraCaptureSession.CaptureCallback() {
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
    private void fail(String message) { report("Error: " + message); closeCapture(); }
    private void closeCapture() {
        ++generation;
        handler.removeCallbacks(timeout);
        if (session != null) { session.close(); session = null; }
        if (camera != null) { camera.close(); camera = null; }
        if (reader != null) { reader.close(); reader = null; }
        busy = false;
    }
    @Override protected void onPause() { closeCapture(); super.onPause(); }
}
