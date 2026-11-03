package local.termux.observatorycamera;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.ImageFormat;
import android.graphics.drawable.Icon;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.OrientationEventListener;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.TimeZone;

/**
 * Sustained Camera2 JPEG capture: one camera device, one long-lived capture session and one
 * ImageReader (maxImages 2) per run. Captures are strictly serialized: the next capture is issued only
 * after the previous frame has been processed and published (or has failed), paced by a monotonic
 * clock towards the configured target FPS. Measured FPS is reported, never assumed. Camera callbacks
 * run on a camera thread, decode/overlay/file I/O on a processing thread; the UI thread does neither.
 *
 * Declared with foregroundServiceType="camera". Started only via startForegroundService from a resumed
 * activity (ControlActivity) so Android grants while-in-use camera access; it does not bypass camera,
 * background or lock-screen restrictions. START_NOT_STICKY, no boot receiver, no wake lock. The camera is
 * owned through CameraArbiter; a conflicting single capture is rejected instead of opening a second camera.
 * Stop and every error path cancel pending work and close image reader, session, device, threads and
 * the orientation listener.
 */
public final class ContinuousCaptureService extends Service {
    public static final String ACTION_START = "local.termux.observatorycamera.action.START_CONTINUOUS";
    public static final String ACTION_STOP = "local.termux.observatorycamera.action.STOP_CONTINUOUS";
    public static final String EXTRA_REQUEST_ID = "request_id";
    private static final String TAG = "ObservatoryContinuous";
    private static final String CHANNEL_ID = "continuous_capture";
    private static final int NOTIFICATION_ID = 2;
    private static final long FRAME_TIMEOUT_MS = 10_000;
    private static final int MAX_CONSECUTIVE_FAILURES = 5;
    private static final long FAILURE_RETRY_MS = 1_000;

    /** Notified on the main thread when the session state (starting/running/stopped/error) changes. */
    public interface Listener { void onStatusChanged(); }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile ContinuousStatus status = ContinuousStatus.stopped();
    private static volatile Listener listener;
    private static volatile String effectiveJpegSize = "n/a";
    private static volatile String effectiveTargetFps = "n/a";
    private static volatile String metadataEstimateFps = "unknown";
    private static volatile String reloadError;

    public static ContinuousStatus getStatus() { return status; }
    public static String effectiveJpegSize() { return effectiveJpegSize; }
    public static String effectiveTargetFps() { return effectiveTargetFps; }
    public static String metadataEstimateFps() { return metadataEstimateFps; }
    public static String reloadError() { return reloadError; }
    public static boolean isActive() { return status.isActive(); }
    public static void setListener(Listener value) { listener = value; }

    private static void publish(ContinuousStatus value, boolean stateChanged) {
        status = value;
        if (!stateChanged) return;
        Listener l = listener;
        if (l != null) MAIN.post(l::onStatusChanged);
    }

    private final Object lock = new Object();
    private volatile int generation;
    private boolean open;
    private HandlerThread cameraThread;
    private HandlerThread processThread;
    private Handler cameraHandler;
    private Handler processHandler;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private CaptureRequest.Builder builder;
    private OrientationEventListener orientationListener;
    private volatile int lastAngle = -1;
    private int sensorOrientation;
    private boolean frontFacing;
    private CameraCapabilities capabilities;
    private CameraCapabilities.JpegSize effectiveSize;
    private CaptureConfig activeConfig;
    private FrameStats stats;
    private long sessionStart = -1;
    private String requestId = "-";
    private String overlayMode = "-";
    private long lastBytes;
    private int consecutiveFailures;
    // Camera-thread state for serialization: at most one capture in flight.
    private volatile boolean inFlight;
    private volatile long serial;
    private long captureStartNanos;
    private final Runnable watchdog = () -> runtimeFail("Timed out waiting for a frame after " + FRAME_TIMEOUT_MS + " ms.");

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
        // startForeground must follow startForegroundService() promptly, even if we reject the request.
        try {
            createChannel();
            startForeground(NOTIFICATION_ID, buildNotification("Starting continuous capture..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } catch (RuntimeException e) {
            failStart("Could not enter foreground: " + e);
            return;
        }
        if (isActive()) {
            Log.i(ControlCommand.LOG_TAG, ControlCommand.line(requestId, "continuous-start", "started", "already=true"));
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            failStart("camera-permission-missing");
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
        if (!manager.areNotificationsEnabled() || (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE)) {
            failStart("notifications-blocked");
            return;
        }
        if (!CameraArbiter.shared().acquire(CameraArbiter.CONTINUOUS)) {
            failStart("camera-busy owner=" + CameraArbiter.shared().owner());
            return;
        }
        CaptureConfig config = CaptureSettings.load(this);
        stats = new FrameStats();
        sessionStart = SystemClock.elapsedRealtimeNanos();
        consecutiveFailures = 0;
        inFlight = false;
        lastBytes = 0;
        overlayMode = "-";
        reloadError = null;
        effectiveJpegSize = "unknown";
        effectiveTargetFps = "unknown";
        metadataEstimateFps = "unknown";
        lastAngle = -1;
        publish(snapshot(ContinuousStatus.STARTING, null), true);
        try {
            openResources(config);
        } catch (Exception e) {
            failStart("camera-open-failed " + e);
        }
    }

    private void openResources(CaptureConfig config) throws CameraAccessException {
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
        java.util.List<CameraCapabilities.JpegSize> jpegSizes = new java.util.ArrayList<>();
        for (Size candidate : sizes) {
            jpegSizes.add(new CameraCapabilities.JpegSize(candidate.getWidth(), candidate.getHeight(),
                map.getOutputMinFrameDuration(ImageFormat.JPEG, candidate),
                map.getOutputStallDuration(ImageFormat.JPEG, candidate)));
        }
        capabilities = new CameraCapabilities(jpegSizes);
        String invalid = capabilities.validate(config);
        if (invalid != null) throw new IllegalArgumentException(invalid);
        effectiveSize = capabilities.select(config);
        activeConfig = config;
        Size size = new Size(effectiveSize.width, effectiveSize.height);
        Integer sensorValue = info.get(CameraCharacteristics.SENSOR_ORIENTATION);
        sensorOrientation = sensorValue == null ? 0 : sensorValue;
        frontFacing = Integer.valueOf(CameraCharacteristics.LENS_FACING_FRONT).equals(info.get(CameraCharacteristics.LENS_FACING));

        final int gen = ++generation;
        cameraThread = new HandlerThread("continuous-camera");
        processThread = new HandlerThread("continuous-process");
        cameraThread.start();
        processThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        processHandler = new Handler(processThread.getLooper());
        orientationListener = new OrientationEventListener(this) {
            @Override public void onOrientationChanged(int angle) {
                if (angle != ORIENTATION_UNKNOWN && angle >= 0 && angle < 360) lastAngle = angle;
            }
        };
        if (orientationListener.canDetectOrientation()) orientationListener.enable();
        else Log.w(TAG, "Device orientation sensor unavailable; JPEG orientation uses the fallback angle 0.");
        synchronized (lock) {
            open = true;
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.JPEG, 2);
            reader.setOnImageAvailableListener(source -> onImageAvailable(source, gen), processHandler);
        }
        Log.i(TAG, "Opening camera " + selected + " at " + size.getWidth() + "x" + size.getHeight() + ", target " + config.targetFpsString() + " FPS.");
        manager.openCamera(selected, new CameraDevice.StateCallback() {
            @Override public void onOpened(CameraDevice opened) {
                synchronized (lock) {
                    if (gen != generation || !open) { opened.close(); return; }
                    device = opened;
                    try {
                        opened.createCaptureSession(Collections.singletonList(reader.getSurface()),
                            sessionCallback(gen, effectiveSize, config), cameraHandler);
                    } catch (Exception e) { MAIN.post(() -> fatal(gen, "Could not create session: " + e)); }
                }
            }
            @Override public void onDisconnected(CameraDevice d) { d.close(); MAIN.post(() -> fatal(gen, "Camera disconnected.")); }
            @Override public void onError(CameraDevice d, int error) { d.close(); MAIN.post(() -> fatal(gen, "Camera error: " + error)); }
        }, cameraHandler);
    }

    private CameraCaptureSession.StateCallback sessionCallback(final int gen, final CameraCapabilities.JpegSize configuredSize,
                                                               final CaptureConfig configuredConfig) {
        return new CameraCaptureSession.StateCallback() {
            @Override public void onConfigured(CameraCaptureSession configured) {
                synchronized (lock) {
                    if (gen != generation || !open) { configured.close(); return; }
                    session = configured;
                    effectiveSize = configuredSize;
                    activeConfig = configuredConfig;
                    effectiveJpegSize = configuredSize.value();
                    effectiveTargetFps = configuredConfig.targetFpsString();
                    long estimate = configuredSize.metadataEstimateFpsTenths();
                    metadataEstimateFps = configuredSize.hasTimingEstimate() ? CameraCapabilities.formatFps(estimate) : "unknown";
                    reloadError = null;
                    try {
                        builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                        builder.addTarget(reader.getSurface());
                    } catch (Exception e) { MAIN.post(() -> fatal(gen, "Could not create request: " + e)); return; }
                }
                MAIN.post(() -> {
                    if (gen != generation) return;
                    if (status.state.equals(ContinuousStatus.STARTING)) {
                        publish(snapshot(ContinuousStatus.RUNNING, null), true);
                        updateNotification("Continuous JPEG capture running. The camera is in use.");
                        Log.i(ControlCommand.LOG_TAG, ControlCommand.line(requestId, "continuous-start", "started", "state=running"));
                    }
                });
                cameraHandler.post(() -> captureNext(gen));
            }
            @Override public void onConfigureFailed(CameraCaptureSession s) {
                s.close();
                MAIN.post(() -> fatal(gen, "Camera session configuration failed."));
            }
        };
    }

    /** Camera thread. Issues exactly one capture, only when none is in flight. */
    private void captureNext(int gen) {
        if (gen != generation || inFlight) return;
        final long id = ++serial;
        try {
            synchronized (lock) {
                if (gen != generation || session == null) return;
                int angle = lastAngle;
                int deviceOrientation = angle < 0 ? 0 : JpegOrientation.roundDeviceOrientation(angle);
                builder.set(CaptureRequest.JPEG_ORIENTATION, JpegOrientation.calculate(sensorOrientation, deviceOrientation, frontFacing));
                inFlight = true;
                captureStartNanos = SystemClock.elapsedRealtimeNanos();
                session.capture(builder.build(), new CameraCaptureSession.CaptureCallback() {
                    @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, CaptureFailure failure) {
                        cameraHandler.post(() -> frameFailed(gen, id, "Capture failed: reason " + failure.getReason()));
                    }
                }, cameraHandler);
            }
            cameraHandler.postDelayed(watchdog, FRAME_TIMEOUT_MS);
        } catch (Exception e) {
            inFlight = false;
            MAIN.post(() -> fatal(gen, "Capture request failed: " + e));
        }
    }

    /** Camera thread: a capture cycle ended (frame published or failed); schedule the next one. */
    private void cycleDone(int gen, long id, boolean success) {
        if (gen != generation || !inFlight || id != serial) return;
        inFlight = false;
        cameraHandler.removeCallbacks(watchdog);
        long now = SystemClock.elapsedRealtimeNanos();
        CaptureConfig requested = CaptureSettings.load(this);
        String invalid = capabilities == null ? "camera-capability-unavailable" : capabilities.validate(requested);
        if (invalid != null) {
            boolean changed = !invalid.equals(reloadError);
            reloadError = invalid;
            if (changed) Log.e(TAG, "Settings reload rejected; keeping the active camera configuration: " + invalid);
            requested = activeConfig;
        } else {
            reloadError = null;
        }
        CameraCapabilities.JpegSize desired = capabilities == null ? null : capabilities.select(requested);
        if (desired != null && effectiveSize != null && !desired.value().equals(effectiveSize.value())) {
            reconfigure(gen, desired, requested);
            return;
        }
        activeConfig = requested;
        effectiveTargetFps = requested.targetFpsString();
        if (effectiveSize != null) {
            long estimate = effectiveSize.metadataEstimateFpsTenths();
            metadataEstimateFps = effectiveSize.hasTimingEstimate() ? CameraCapabilities.formatFps(estimate) : "unknown";
        }
        long interval = requested.targetIntervalNanos();
        long elapsed = now - captureStartNanos;
        if (success) {
            consecutiveFailures = 0;
            if (elapsed > interval) stats.recordLate();
            long remaining = Math.max(0, interval - elapsed);
            long delayMillis = remaining / 1_000_000L + (remaining % 1_000_000L == 0 ? 0 : 1);
            cameraHandler.postDelayed(() -> captureNext(gen), delayMillis);
        } else {
            cameraHandler.postDelayed(() -> captureNext(gen), FAILURE_RETRY_MS);
        }
    }

    /** Rebuilds the existing session and JPEG reader only between fully processed, serialized captures. */
    private void reconfigure(int gen, CameraCapabilities.JpegSize desired, CaptureConfig requested) {
        synchronized (lock) {
            if (gen != generation || !open || device == null) return;
            try {
                if (session != null) {
                    try { session.abortCaptures(); } catch (Exception ignored) { }
                    session.close();
                    session = null;
                }
                if (reader != null) {
                    reader.setOnImageAvailableListener(null, null);
                    reader.close();
                }
                Size size = new Size(desired.width, desired.height);
                reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.JPEG, 2);
                reader.setOnImageAvailableListener(source -> onImageAvailable(source, gen), processHandler);
                builder = null;
                device.createCaptureSession(Collections.singletonList(reader.getSurface()),
                    sessionCallback(gen, desired, requested), cameraHandler);
                Log.i(TAG, "Reconfiguring the active JPEG session to " + desired.value() + ".");
            } catch (Exception e) {
                MAIN.post(() -> fatal(gen, "Resolution reconfiguration failed: " + e));
            }
        }
    }

    private void frameFailed(int gen, long id, String reason) {
        if (gen != generation || !inFlight || id != serial) return;
        stats.recordFailure();
        Log.w(TAG, reason);
        if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            MAIN.post(() -> fatal(gen, reason + " (" + consecutiveFailures + " consecutive failures)"));
            return;
        }
        publish(snapshot(ContinuousStatus.RUNNING, reason), false);
        cycleDone(gen, id, false);
    }

    /** Processing thread. Copies and closes the image immediately, then decodes/draws/writes. */
    private void onImageAvailable(ImageReader source, int gen) {
        Handler handler = cameraHandler;
        if (gen != generation || !open || handler == null) return;
        long acquired = SystemClock.elapsedRealtimeNanos();
        long wall = System.currentTimeMillis();
        String zone = TimeZone.getDefault().getID();
        final long id = serial;
        byte[] data;
        try (Image image = source.acquireNextImage()) {
            if (image == null) return;
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            if (buffer.remaining() > CameraCapabilities.MAX_JPEG_BYTES) {
                handler.post(() -> frameFailed(gen, id, "JPEG exceeds the application byte limit."));
                return;
            }
            data = new byte[buffer.remaining()];
            buffer.get(data);
        } catch (RuntimeException e) {
            Log.w(TAG, "Image acquire failed: " + e);
            return;
        }
        if (gen != generation) return;
        if (!inFlight) {
            Log.w(TAG, "Discarded an unexpected frame (no capture in flight); stale frames are never published.");
            return;
        }
        try {
            CaptureConfig config = CaptureSettings.load(this);
            if (capabilities != null && capabilities.validate(config) != null) config = activeConfig;
            FrameOverlay.Result r = FrameOverlay.process(data, config, stats.fpsWith(acquired), wall, TimeZone.getTimeZone(zone));
            if (r.jpeg.length > CameraCapabilities.MAX_JPEG_BYTES) throw new IOException("JPEG output exceeds the application byte limit.");
            boolean published = SnapshotPublisher.publish(getFilesDir(), "snapshot.jpg", r.jpeg, () -> gen == generation);
            if (!published) return;
            stats.recordFrame(acquired, r.processingNanos);
            lastBytes = r.jpeg.length;
            overlayMode = r.rendered ? "rendered" : "passthrough";
            publishFrame(wall, zone);
            Log.i(TAG, "Frame " + stats.frames() + " published, " + r.jpeg.length + " bytes, overlay=" + overlayMode + ", processing " + r.processingNanos / 1_000_000L + " ms.");
            handler.post(() -> cycleDone(gen, id, true));
        } catch (Exception e) {
            handler.post(() -> frameFailed(gen, id, "Frame processing failed: " + e));
        }
    }

    private ContinuousStatus snapshot(String state, String error) {
        long frames = stats == null ? 0 : stats.frames();
        ContinuousStatus previous = status;
        return new ContinuousStatus(state, frames, stats == null ? Double.NaN : stats.fps(), CaptureSettings.load(this).targetFpsTenths, sessionStart,
            stats == null ? -1 : stats.lastCaptureNanos(), previous.lastCaptureWallMillis, previous.zoneId,
            stats == null ? 0 : stats.lastProcessingNanos(), stats == null ? 0 : stats.averageProcessingNanos(),
            stats == null ? 0 : stats.maxProcessingNanos(), lastBytes, overlayMode,
            stats == null ? 0 : stats.late(), stats == null ? 0 : stats.failures(), error);
    }

    private void publishFrame(long wall, String zone) {
        ContinuousStatus s = snapshot(ContinuousStatus.RUNNING, null);
        publish(new ContinuousStatus(s.state, s.frames, s.actualFps, s.targetFpsTenths, s.sessionStartNanos, s.lastCaptureNanos, wall, zone,
            s.procLastNanos, s.procAvgNanos, s.procMaxNanos, s.lastBytes, s.overlayMode, s.late, s.failures, null), false);
    }

    private void runtimeFail(String message) { MAIN.post(() -> fatal(generation, message)); }

    /** Main thread: unrecoverable error; release everything and report state=error. */
    private void fatal(int gen, String message) {
        if (gen != generation || !open) return;
        reloadError = message;
        Log.e(TAG, message);
        Log.e(ControlCommand.LOG_TAG, ControlCommand.line(requestId, "continuous-start", "failed", "reason=" + message));
        ContinuousStatus current = snapshot(ContinuousStatus.ERROR, message);
        closeResources();
        publish(current, true);
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void failStart(String reason) {
        reloadError = reason;
        Log.e(ControlCommand.LOG_TAG, ControlCommand.line(requestId, "continuous-start", "failed", "reason=" + reason));
        ContinuousStatus current = snapshot(ContinuousStatus.ERROR, reason);
        closeResources();
        publish(current, true);
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void handleStop() {
        closeResources();
        if (status.isActive() || status.state.equals(ContinuousStatus.ERROR)) publish(status.withState(ContinuousStatus.STOPPED, null), true);
        Log.i(ControlCommand.LOG_TAG, ControlCommand.line(requestId, "continuous-stop", "stopped", null));
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    /** Idempotent. Cancels pending work, then closes session, device, reader, threads and the sensor listener. */
    private void closeResources() {
        ++generation;
        if (cameraHandler != null) cameraHandler.removeCallbacksAndMessages(null);
        if (processHandler != null) processHandler.removeCallbacksAndMessages(null);
        synchronized (lock) {
            open = false;
            inFlight = false;
            if (session != null) {
                try { session.abortCaptures(); } catch (Exception ignored) { }
                session.close();
                session = null;
            }
            if (device != null) { device.close(); device = null; }
            if (reader != null) {
                reader.setOnImageAvailableListener(null, null);
                reader.close();
                reader = null;
            }
            builder = null;
        }
        if (orientationListener != null) { orientationListener.disable(); orientationListener = null; }
        if (cameraThread != null) { cameraThread.quitSafely(); cameraThread = null; }
        if (processThread != null) { processThread.quitSafely(); processThread = null; }
        cameraHandler = null;
        processHandler = null;
        CameraArbiter.shared().release(CameraArbiter.CONTINUOUS);
    }

    @Override public void onDestroy() {
        closeResources();
        if (status.isActive()) publish(status.withState(ContinuousStatus.STOPPED, null), true);
        super.onDestroy();
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Continuous capture", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shown while continuous camera capture is running.");
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 2,
            new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 3,
            new Intent(this, ContinuousCaptureService.class).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Observatory continuous capture")
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Stop", stop).build())
            .build();
    }
}
