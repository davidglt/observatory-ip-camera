/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

/** Camera2 JPEG_ORIENTATION calculation, following the official Camera2 reference example. */
final class JpegOrientation {
    private JpegOrientation() {}

    /** Rounds an OrientationEventListener angle (0..359) to the nearest multiple of 90, in 0..270. */
    static int roundDeviceOrientation(int angle) {
        return (((angle + 45) / 90) * 90) % 360;
    }

    static int calculate(int sensorOrientation, int deviceOrientation, boolean frontFacing) {
        int device = roundDeviceOrientation(((deviceOrientation % 360) + 360) % 360);
        if (frontFacing) device = -device;
        return (sensorOrientation + device + 360) % 360;
    }

    static int calculate(CaptureConfig config, int sensorOrientation, int deviceOrientation, boolean frontFacing) {
        return config.orientationMode.equals("manual") ? config.rotationDegrees
            : calculate(sensorOrientation, deviceOrientation, frontFacing);
    }
}
