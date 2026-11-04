/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

/** Validated per-frame Camera2 request values, independent of Android classes for focused tests. */
final class CameraControlRequest {
    final String afMode;
    final boolean startFocus;
    final Float focusDistanceDiopters;
    final String aeMode;
    final Boolean aeLock;
    final Integer exposureCompensation;
    final Integer iso;
    final Long exposureTimeNanos;
    final Long frameDurationNanos;
    final String whiteBalance;
    final Boolean awbLock;
    final int jpegQuality;

    private CameraControlRequest(String afMode, boolean startFocus, Float focusDistanceDiopters,
                                 String aeMode, Boolean aeLock, Integer exposureCompensation, Integer iso,
                                 Long exposureTimeNanos, Long frameDurationNanos, String whiteBalance,
                                 Boolean awbLock, int jpegQuality) {
        this.afMode = afMode;
        this.startFocus = startFocus;
        this.focusDistanceDiopters = focusDistanceDiopters;
        this.aeMode = aeMode;
        this.aeLock = aeLock;
        this.exposureCompensation = exposureCompensation;
        this.iso = iso;
        this.exposureTimeNanos = exposureTimeNanos;
        this.frameDurationNanos = frameDurationNanos;
        this.whiteBalance = whiteBalance;
        this.awbLock = awbLock;
        this.jpegQuality = jpegQuality;
    }

    static CameraControlRequest map(CaptureConfig config, CameraControlCapabilities capabilities,
                                    long minimumFrameDurationNanos, boolean startFocus) {
        String af = null;
        Float distance = null;
        boolean trigger = false;
        if (config.focusMode.equals("manual")) {
            af = "off";
            distance = config.focusDistanceDiopters;
        } else if (config.focusMode.equals("lock") && !capabilities.fixedFocus()) {
            af = preferredLockFocus(capabilities);
            trigger = startFocus && af != null;
        } else if (config.focusMode.equals("auto") && !capabilities.fixedFocus()) {
            af = preferredAutoFocus(capabilities);
            trigger = startFocus && ("auto".equals(af) || "macro".equals(af));
        } else if (!config.focusMode.equals("auto") && capabilities.focusModes.contains(config.focusMode)) {
            af = config.focusMode;
            trigger = startFocus && ("auto".equals(af) || "macro".equals(af));
        }

        boolean manualExposure = config.exposureMode.equals("manual");
        String ae = manualExposure ? "off" : capabilities.autoExposureSupported ? "on" : null;
        Integer iso = manualExposure ? config.iso : null;
        Long exposure = manualExposure ? config.exposureTimeNanos : null;
        Long frameDuration = manualExposure
            ? Math.max(config.exposureTimeNanos, Math.max(0, minimumFrameDurationNanos)) : null;
        String awb = capabilities.whiteBalanceModes.contains(config.whiteBalance) ? config.whiteBalance : null;
        return new CameraControlRequest(af, trigger, distance, ae,
            !manualExposure && capabilities.aeLockSupported ? config.aeLock : null,
            !manualExposure && capabilities.exposureCompensationSupported() ? config.exposureCompensation : null,
            iso, exposure, frameDuration, awb, capabilities.awbLockSupported ? config.awbLock : null,
            config.jpegQuality);
    }

    private static String preferredAutoFocus(CameraControlCapabilities caps) {
        for (String mode : new String[]{"continuous-picture", "continuous-video", "auto", "macro", "edof"})
            if (caps.focusModes.contains(mode)) return mode;
        return null;
    }

    private static String preferredLockFocus(CameraControlCapabilities caps) {
        for (String mode : new String[]{"auto", "macro", "continuous-picture", "continuous-video"})
            if (caps.focusModes.contains(mode)) return mode;
        return null;
    }
}
