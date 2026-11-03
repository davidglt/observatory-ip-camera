package local.termux.observatorycamera;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Size;
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
        return new CameraCapabilities(entries);
    }
}
