package local.termux.observatorycamera;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Same-origin, bearer-authenticated configuration API used only by SnapshotHttpServer. */
final class SnapshotAdminApi implements SnapshotHttpServer.AdminApi {
    private final Context context;

    SnapshotAdminApi(Context context) { this.context = context.getApplicationContext(); }

    @Override public SnapshotHttpServer.ApiResult handle(String method, String path, byte[] body) {
        try {
            if (path.equals("/api/capabilities") && method.equals("GET"))
                return ok(capabilitiesJson(cameraCapabilities()));
            if (path.equals("/api/config") && method.equals("GET"))
                return ok(configJson(CaptureSettings.store(context).load().config, null));
            if (path.equals("/api/config") && method.equals("PUT")) {
                Map<String, String> values;
                try { values = decodeForm(body); }
                catch (IllegalArgumentException | CharacterCodingException e) { return bad("invalid-form"); }
                CameraCapabilities caps = cameraCapabilities();
                ConfigStore.Outcome saved = CaptureSettings.store(context).updateEntries(values, caps::validate);
                if (saved.config == null) return bad(saved.error);
                return ok(configJson(saved.config, null));
            }
            return new SnapshotHttpServer.ApiResult(404, "Not Found", "{\"error\":\"not-found\"}");
        } catch (Exception e) {
            return new SnapshotHttpServer.ApiResult(503, "Service Unavailable",
                "{\"error\":\"" + json(safeMessage(e)) + "\"}");
        }
    }

    private SnapshotHttpServer.ApiResult ok(String body) {
        return new SnapshotHttpServer.ApiResult(200, "OK", body);
    }

    private SnapshotHttpServer.ApiResult bad(String error) {
        return new SnapshotHttpServer.ApiResult(400, "Bad Request", "{\"error\":\"" + json(error) + "\"}");
    }

    private String configJson(CaptureConfig config, String error) {
        StringBuilder out = new StringBuilder("{\"settings\":{");
        boolean first = true;
        for (Map.Entry<String, String> e : config.toMap().entrySet()) {
            if (!first) out.append(',');
            first = false;
            out.append('"').append(json(e.getKey())).append("\":\"").append(json(e.getValue())).append('"');
        }
        ContinuousStatus status = ContinuousCaptureService.getStatus();
        out.append("},\"effective_size\":\"").append(json(ContinuousCaptureService.effectiveJpegSize()))
            .append("\",\"desired_size\":\"").append(json(config.jpegSize))
            .append("\",\"effective_target_fps\":\"").append(json(ContinuousCaptureService.effectiveTargetFps()))
            .append("\",\"actual_fps\":\"")
            .append(Double.isNaN(status.actualFps) ? "n/a" : String.format(java.util.Locale.ROOT, "%.2f", status.actualFps))
            .append("\",\"metadata_estimate_fps\":\"").append(json(ContinuousCaptureService.metadataEstimateFps()))
            .append("\",\"capture_state\":\"").append(json(status.state))
            .append("\",\"reload_error\":\"").append(json(ContinuousCaptureService.reloadError() == null ? "" : ContinuousCaptureService.reloadError()))
            .append("\",\"effective_controls\":")
            .append(ContinuousCaptureService.effectiveControls() == null ? "null" : ContinuousCaptureService.effectiveControls())
            .append(",\"last_capture_request\":")
            .append(ContinuousCaptureService.effectiveControlRequest() == null ? "null" : ContinuousCaptureService.effectiveControlRequest())
            .append(",\"effective_image_transform\":")
            .append(ContinuousCaptureService.effectiveImageTransform() == null ? "null" : ContinuousCaptureService.effectiveImageTransform())
            .append(",\"file_state\":\"").append(json(CaptureSettings.store(context).load().fileState)).append("\"");
        if (error != null) out.append(",\"error\":\"").append(json(error)).append('"');
        return out.append('}').toString();
    }

    private CameraCapabilities cameraCapabilities() throws android.hardware.camera2.CameraAccessException {
        return CameraCapabilityReader.read(context);
    }

    private String capabilitiesJson(CameraCapabilities capabilities) {
        CaptureConfig automatic = CaptureConfig.defaults();
        CameraCapabilities.JpegSize autoSize = capabilities.select(automatic);
        boolean autoSupported = (long) autoSize.width * autoSize.height <= CameraCapabilities.MAX_JPEG_PIXELS;
        StringBuilder out = new StringBuilder("{\"auto_size\":\"")
            .append(autoSize.value()).append("\",\"auto_supported\":").append(autoSupported).append(",\"sizes\":[");
        boolean first = true;
        for (CameraCapabilities.JpegSize size : capabilities.sizes()) {
            if (!first) out.append(',');
            first = false;
            long estimate = size.metadataEstimateFpsTenths();
            boolean supported = (long) size.width * size.height <= CameraCapabilities.MAX_JPEG_PIXELS;
            out.append("{\"size\":\"").append(size.value())
                .append("\",\"application_supported\":").append(supported)
                .append(",\"min_frame_duration_ns\":")
                .append(size.minFrameDurationNanos > 0 ? Long.toString(size.minFrameDurationNanos) : "null")
                .append(",\"jpeg_stall_duration_ns\":")
                .append(size.stallDurationNanos >= 0 ? Long.toString(size.stallDurationNanos) : "null")
                .append(",\"estimate_fps\":\"").append(size.hasTimingEstimate() ? CameraCapabilities.formatFps(estimate) : "unknown")
                .append("\"}");
        }
        return out.append("],\"controls\":").append(controlsJson(capabilities.controls)).append('}').toString();
    }

    private String controlsJson(CameraControlCapabilities c) {
        if (c == null) return "{}";
        StringBuilder out = new StringBuilder("{\"focus\":{\"fixed_focus\":").append(c.fixedFocus())
            .append(",\"focus_lock\":").append(c.focusLockSupported)
            .append(",\"manual_focus\":").append(c.manualFocusSupported())
            .append(",\"automatic\":").append(c.autofocusSupported())
            .append(",\"minimum_focus_distance_diopters\":")
            .append(c.manualFocusSupported() ? Float.toString(c.minimumFocusDistanceDiopters) : "null")
            .append(",\"modes\":[");
        boolean first = true;
        for (String mode : c.focusChoices()) {
            if (!first) out.append(',');
            first = false;
            out.append('"').append(json(mode)).append('"');
        }
        out.append("]},\"exposure\":{\"auto\":").append(c.autoExposureSupported)
            .append(",\"ae_lock\":").append(c.aeLockSupported)
            .append(",\"compensation_min\":").append(!c.exposureCompensationSupported() ? "null" : c.exposureCompensationMinimum)
            .append(",\"compensation_max\":").append(!c.exposureCompensationSupported() ? "null" : c.exposureCompensationMaximum)
            .append(",\"compensation_step_ev\":").append(!c.exposureCompensationSupported() || c.exposureCompensationStep == null
                ? "null" : "\"" + json(c.exposureCompensationStep) + "\"")
            .append(",\"manual_sensor\":").append(c.manualSensorSupported)
            .append(",\"iso_min\":").append(!c.manualSensorSupported ? "null" : c.minimumIso)
            .append(",\"iso_max\":").append(!c.manualSensorSupported ? "null" : c.maximumIso)
            .append(",\"exposure_time_min_ns\":").append(!c.manualSensorSupported ? "null" : c.minimumExposureTimeNanos)
            .append(",\"exposure_time_max_ns\":").append(!c.manualSensorSupported ? "null" : c.maximumExposureTimeNanos)
            .append(",\"maximum_frame_duration_ns\":").append(!c.manualSensorSupported || c.maximumFrameDurationNanos <= 0
                ? "null" : c.maximumFrameDurationNanos)
            .append("},\"white_balance\":{\"modes\":[");
        first = true;
        for (String mode : c.whiteBalanceModes) {
            if (!first) out.append(',');
            first = false;
            out.append('"').append(json(mode)).append('"');
        }
        return out.append("],\"lock\":").append(c.awbLockSupported).append("}}").toString();
    }

    private static Map<String, String> decodeForm(byte[] body) throws CharacterCodingException {
        Map<String, String> result = new LinkedHashMap<>();
        if (body.length == 0) throw new IllegalArgumentException("empty-form");
        int start = 0;
        while (start < body.length) {
            int end = start;
            while (end < body.length && body[end] != '&') end++;
            int equals = start;
            while (equals < end && body[equals] != '=') equals++;
            if (equals == start || equals == end) throw new IllegalArgumentException("malformed-form");
            String key = decode(body, start, equals);
            String value = decode(body, equals + 1, end);
            if (!key.matches("[a-z_]+") || result.put(key, value) != null)
                throw new IllegalArgumentException("invalid-or-duplicate-field");
            start = end + 1;
            if (start == body.length) throw new IllegalArgumentException("malformed-form");
        }
        return result;
    }

    private static String decode(byte[] input, int start, int end) throws CharacterCodingException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = start; i < end; i++) {
            int value = input[i] & 0xff;
            if (value == '+') bytes.write(' ');
            else if (value == '%') {
                if (i + 2 >= end) throw new IllegalArgumentException("bad-percent-escape");
                int high = Character.digit((char) input[++i], 16);
                int low = Character.digit((char) input[++i], 16);
                if (high < 0 || low < 0) throw new IllegalArgumentException("bad-percent-escape");
                bytes.write((high << 4) | low);
            } else {
                if (value < 0x21 || value > 0x7e) throw new IllegalArgumentException("unescaped-form-byte");
                bytes.write(value);
            }
        }
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null ? "capability-or-configuration-unavailable" : message.replaceAll("[^A-Za-z0-9_.:-]", "-");
    }

    private static String json(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 0x20) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
            else out.append(c);
        }
        return out.toString();
    }
}
