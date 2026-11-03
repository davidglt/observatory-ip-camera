package local.termux.observatorycamera;

import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import java.util.Locale;

/** Applies validated per-frame settings and reports only values returned by Camera2 capture results. */
final class Camera2RequestMapper {
    private Camera2RequestMapper() {}

    static void apply(CaptureRequest.Builder builder, CaptureConfig config, CameraControlCapabilities capabilities,
                      long minimumFrameDurationNanos, boolean startFocus) {
        CameraControlRequest values = CameraControlRequest.map(config, capabilities, minimumFrameDurationNanos, startFocus);
        builder.set(CaptureRequest.CONTROL_AF_MODE, afMode(values.afMode));
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, values.afMode == null ? null : values.startFocus
            ? CaptureRequest.CONTROL_AF_TRIGGER_START : CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
        builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, values.afMode == null ? null : values.focusDistanceDiopters);
        builder.set(CaptureRequest.CONTROL_AE_MODE, aeMode(values.aeMode));
        builder.set(CaptureRequest.CONTROL_AE_LOCK, values.aeLock);
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, values.exposureCompensation);
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, values.iso);
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, values.exposureTimeNanos);
        builder.set(CaptureRequest.SENSOR_FRAME_DURATION, values.frameDurationNanos);
        builder.set(CaptureRequest.CONTROL_AWB_MODE, awbMode(values.whiteBalance));
        builder.set(CaptureRequest.CONTROL_AWB_LOCK, values.awbLock);
        builder.set(CaptureRequest.JPEG_QUALITY, (byte) values.jpegQuality);
    }

    static String effectiveControlsJson(CaptureResult result) {
        Integer af = result.get(CaptureResult.CONTROL_AF_MODE);
        Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
        Integer ae = result.get(CaptureResult.CONTROL_AE_MODE);
        Integer awb = result.get(CaptureResult.CONTROL_AWB_MODE);
        return "{\"af_mode\":" + string(afName(af)) + ",\"af_mode_code\":" + number(af)
            + ",\"af_state\":" + string(afStateName(afState)) + ",\"af_state_code\":" + number(afState)
            + ",\"focus_distance_diopters\":" + decimal(result.get(CaptureResult.LENS_FOCUS_DISTANCE))
            + ",\"ae_mode\":" + string(aeName(ae)) + ",\"ae_mode_code\":" + number(ae)
            + ",\"ae_lock\":" + bool(result.get(CaptureResult.CONTROL_AE_LOCK))
            + ",\"exposure_compensation\":" + number(result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION))
            + ",\"iso\":" + number(result.get(CaptureResult.SENSOR_SENSITIVITY))
            + ",\"exposure_time_ns\":" + number(result.get(CaptureResult.SENSOR_EXPOSURE_TIME))
            + ",\"frame_duration_ns\":" + number(result.get(CaptureResult.SENSOR_FRAME_DURATION))
            + ",\"white_balance_mode\":" + string(awbName(awb)) + ",\"white_balance_mode_code\":" + number(awb)
            + ",\"awb_lock\":" + bool(result.get(CaptureResult.CONTROL_AWB_LOCK))
            + ",\"jpeg_quality\":" + number(result.get(CaptureResult.JPEG_QUALITY))
            + ",\"jpeg_orientation\":" + number(result.get(CaptureResult.JPEG_ORIENTATION)) + "}";
    }

    static String requestedControlsJson(CaptureConfig config) {
        return "{\"focus_mode\":\"" + config.focusMode + "\",\"focus_distance_diopters\":"
            + config.focusDistanceDiopters + ",\"exposure_mode\":\"" + config.exposureMode
            + "\",\"exposure_compensation\":" + config.exposureCompensation
            + ",\"ae_lock\":" + config.aeLock + ",\"iso\":" + config.iso
            + ",\"exposure_time_ns\":" + config.exposureTimeNanos + ",\"white_balance\":\""
            + config.whiteBalance + "\",\"awb_lock\":" + config.awbLock
            + ",\"jpeg_quality\":" + config.jpegQuality + ",\"orientation_mode\":\""
            + config.orientationMode + "\",\"rotation_degrees\":" + config.rotationDegrees
            + ",\"mirror\":" + config.mirror + "}";
    }

    private static Integer afMode(String mode) {
        if (mode == null) return null;
        switch (mode) {
            case "off": return CaptureRequest.CONTROL_AF_MODE_OFF;
            case "auto": return CaptureRequest.CONTROL_AF_MODE_AUTO;
            case "macro": return CaptureRequest.CONTROL_AF_MODE_MACRO;
            case "continuous-video": return CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO;
            case "continuous-picture": return CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
            case "edof": return CaptureRequest.CONTROL_AF_MODE_EDOF;
            default: return null;
        }
    }

    private static Integer aeMode(String mode) {
        if (mode == null) return null;
        return mode.equals("off") ? CaptureRequest.CONTROL_AE_MODE_OFF : CaptureRequest.CONTROL_AE_MODE_ON;
    }

    private static Integer awbMode(String mode) {
        if (mode == null) return null;
        switch (mode) {
            case "auto": return CaptureRequest.CONTROL_AWB_MODE_AUTO;
            case "incandescent": return CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT;
            case "fluorescent": return CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT;
            case "warm-fluorescent": return CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT;
            case "daylight": return CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT;
            case "cloudy-daylight": return CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT;
            case "twilight": return CaptureRequest.CONTROL_AWB_MODE_TWILIGHT;
            case "shade": return CaptureRequest.CONTROL_AWB_MODE_SHADE;
            default: return null;
        }
    }

    private static String number(Number value) { return value == null ? "null" : value.toString(); }
    private static String string(String value) { return value == null ? "null" : "\"" + value + "\""; }
    private static String decimal(Float value) {
        return value == null || !Float.isFinite(value) ? "null" : String.format(Locale.ROOT, "%.3f", value);
    }
    private static String bool(Boolean value) { return value == null ? "null" : value.toString(); }
    private static String afName(Integer value) {
        if (value == null) return null;
        switch (value) {
            case CaptureResult.CONTROL_AF_MODE_OFF: return "off";
            case CaptureResult.CONTROL_AF_MODE_AUTO: return "auto";
            case CaptureResult.CONTROL_AF_MODE_MACRO: return "macro";
            case CaptureResult.CONTROL_AF_MODE_CONTINUOUS_VIDEO: return "continuous-video";
            case CaptureResult.CONTROL_AF_MODE_CONTINUOUS_PICTURE: return "continuous-picture";
            case CaptureResult.CONTROL_AF_MODE_EDOF: return "edof";
            default: return "unknown";
        }
    }
    private static String aeName(Integer value) {
        if (value == null) return null;
        if (value == CaptureResult.CONTROL_AE_MODE_OFF) return "off";
        if (value == CaptureResult.CONTROL_AE_MODE_ON) return "auto";
        return "unknown";
    }
    private static String afStateName(Integer value) {
        if (value == null) return null;
        switch (value) {
            case CaptureResult.CONTROL_AF_STATE_INACTIVE: return "inactive";
            case CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN: return "passive-scan";
            case CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED: return "passive-focused";
            case CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN: return "active-scan";
            case CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED: return "focused-locked";
            case CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED: return "not-focused-locked";
            case CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED: return "passive-unfocused";
            default: return "unknown";
        }
    }
    private static String awbName(Integer value) {
        if (value == null) return null;
        switch (value) {
            case CaptureResult.CONTROL_AWB_MODE_AUTO: return "auto";
            case CaptureResult.CONTROL_AWB_MODE_INCANDESCENT: return "incandescent";
            case CaptureResult.CONTROL_AWB_MODE_FLUORESCENT: return "fluorescent";
            case CaptureResult.CONTROL_AWB_MODE_WARM_FLUORESCENT: return "warm-fluorescent";
            case CaptureResult.CONTROL_AWB_MODE_DAYLIGHT: return "daylight";
            case CaptureResult.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT: return "cloudy-daylight";
            case CaptureResult.CONTROL_AWB_MODE_TWILIGHT: return "twilight";
            case CaptureResult.CONTROL_AWB_MODE_SHADE: return "shade";
            default: return "unknown";
        }
    }
}
