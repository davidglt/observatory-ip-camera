package local.termux.observatorycamera;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Rational;
import android.util.Size;
import android.util.Range;
import java.util.ArrayList;
import java.util.List;

/** Reads JPEG output sizes and timing only from the actual selected rear Camera2 camera. */
final class CameraCapabilityReader {
    private CameraCapabilityReader() {}

    static CameraCapabilities read(Context context) throws CameraAccessException {
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        String selected = null;
        CameraCharacteristics info = null;
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics candidate = manager.getCameraCharacteristics(id);
            if (Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK)
                .equals(candidate.get(CameraCharacteristics.LENS_FACING))) {
                selected = id;
                info = candidate;
                break;
            }
        }
        if (selected == null || info == null) throw new IllegalStateException("No rear camera found.");
        StreamConfigurationMap map = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.JPEG);
        if (sizes == null || sizes.length == 0) throw new IllegalStateException("JPEG capture unavailable.");
        List<CameraCapabilities.JpegSize> entries = new ArrayList<>();
        for (Size size : sizes) {
            entries.add(new CameraCapabilities.JpegSize(size.getWidth(), size.getHeight(),
                map.getOutputMinFrameDuration(ImageFormat.JPEG, size),
                map.getOutputStallDuration(ImageFormat.JPEG, size)));
        }
        return new CameraCapabilities(entries, controls(info));
    }

    static CameraControlCapabilities controls(CameraCharacteristics info) {
        List<String> focusModes = new ArrayList<>();
        int[] af = info.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if (af != null) {
            addMode(focusModes, af, CameraCharacteristics.CONTROL_AF_MODE_OFF, "off");
            addMode(focusModes, af, CameraCharacteristics.CONTROL_AF_MODE_AUTO, "auto");
            addMode(focusModes, af, CameraCharacteristics.CONTROL_AF_MODE_MACRO, "macro");
            addMode(focusModes, af, CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_VIDEO, "continuous-video");
            addMode(focusModes, af, CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE, "continuous-picture");
            addMode(focusModes, af, CameraCharacteristics.CONTROL_AF_MODE_EDOF, "edof");
        }
        Float minimumFocus = info.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        boolean focusAdjustment = minimumFocus == null || minimumFocus > 0;
        boolean focusLock = focusAdjustment && (contains(af, CameraCharacteristics.CONTROL_AF_MODE_AUTO)
            || contains(af, CameraCharacteristics.CONTROL_AF_MODE_MACRO)
            || contains(af, CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            || contains(af, CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE));

        int[] ae = info.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES);
        boolean autoExposure = contains(ae, CameraCharacteristics.CONTROL_AE_MODE_ON);
        boolean aeLock = autoExposure && Boolean.TRUE.equals(info.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE));
        Range<Integer> compensation = info.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        Rational step = info.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);

        int[] available = info.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        boolean manualSensor = contains(available, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
            && contains(ae, CameraCharacteristics.CONTROL_AE_MODE_OFF);
        Range<Integer> iso = info.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        Range<Long> exposure = info.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        Long maximumFrame = info.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION);

        List<String> wbModes = new ArrayList<>();
        int[] awb = info.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
        addMode(wbModes, awb, CameraCharacteristics.CONTROL_AWB_MODE_AUTO, "auto");
        addMode(wbModes, awb, CameraCharacteristics.CONTROL_AWB_MODE_INCANDESCENT, "incandescent");
        addMode(wbModes, awb, CameraCharacteristics.CONTROL_AWB_MODE_FLUORESCENT, "fluorescent");
        addMode(wbModes, awb, CameraCharacteristics.CONTROL_AWB_MODE_WARM_FLUORESCENT, "warm-fluorescent");
        addMode(wbModes, awb, CameraCharacteristics.CONTROL_AWB_MODE_DAYLIGHT, "daylight");
        addMode(wbModes, awb, CameraCharacteristics.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, "cloudy-daylight");
        addMode(wbModes, awb, CameraCharacteristics.CONTROL_AWB_MODE_TWILIGHT, "twilight");
        addMode(wbModes, awb, CameraCharacteristics.CONTROL_AWB_MODE_SHADE, "shade");
        boolean awbLock = contains(awb, CameraCharacteristics.CONTROL_AWB_MODE_AUTO)
            && Boolean.TRUE.equals(info.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE));

        return new CameraControlCapabilities(focusModes, focusLock, minimumFocus == null ? Float.NaN : minimumFocus,
            autoExposure, aeLock, compensation == null ? null : compensation.getLower(),
            compensation == null ? null : compensation.getUpper(), step == null ? null : step.toString(),
            manualSensor, iso == null ? null : iso.getLower(), iso == null ? null : iso.getUpper(),
            exposure == null ? null : exposure.getLower(), exposure == null ? null : exposure.getUpper(),
            maximumFrame == null ? 0L : maximumFrame, wbModes, awbLock);
    }

    private static void addMode(List<String> modes, int[] available, int key, String name) {
        if (contains(available, key)) modes.add(name);
    }

    private static boolean contains(int[] values, int value) {
        if (values == null) return false;
        for (int candidate : values) if (candidate == value) return true;
        return false;
    }
}
