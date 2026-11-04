/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

import java.util.Arrays;
import java.util.Collections;

/** Dependency-free capability, Camera2 parameter mapping, and unit-boundary tests. */
public final class CameraControlsTest {
    private static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); }

    private static CaptureConfig config(String settings) {
        CaptureConfig.Parsed parsed = CaptureConfig.parse(settings, CaptureConfig.defaults());
        check(parsed.config != null, "parse " + settings + ": " + parsed.error);
        return parsed.config;
    }

    public static void main(String[] args) {
        CameraControlCapabilities capable = new CameraControlCapabilities(
            Arrays.asList("off", "auto", "continuous-picture", "macro"), true, 10f, true, true,
            -4, 4, "1/3", true, 50, 3200, 1000L, 2_000_000_000L, 2_500_000_000L,
            Arrays.asList("auto", "daylight", "shade"), true);
        CameraCapabilities caps = new CameraCapabilities(Arrays.asList(
            new CameraCapabilities.JpegSize(1280, 720, 1_000_000_000L, 100_000_000L),
            new CameraCapabilities.JpegSize(640, 480, 300_000_000L, 0)), capable);
        check(caps.validate(CaptureConfig.defaults()) == null, "legacy auto defaults remain valid");
        check(capable.minimumFocusDistanceDiopters == 10f && capable.manualFocusSupported(),
            "minimum focus distance uses Camera2 diopters");
        check(capable.exposureCompensationStep.equals("1/3")
            && Math.abs(capable.exposureCompensationStepEv - 1d / 3d) < 0.000001,
            "exposure compensation preserves the exact advertised rational step");
        check(caps.validate(config("focus_mode=manual focus_distance_diopters=10")) == null,
            "manual diopter upper boundary accepted");
        check(caps.validate(config("focus_mode=manual focus_distance_diopters=10.001")).startsWith("invalid-focus_distance"),
            "manual diopter above lens range rejected");
        check(caps.validate(config("focus_mode=lock")) == null, "focus lock accepted when AF modes support it");
        check(caps.validate(config("exposure_compensation=-4")) == null
            && caps.validate(config("exposure_compensation=4")) == null,
            "advertised exposure compensation boundaries accepted");
        check(caps.validate(config("exposure_compensation=5")).startsWith("invalid-exposure_compensation"),
            "exposure compensation above range rejected");
        check(caps.validate(config("ae_lock=on")) == null, "AE lock advertised");
        check(caps.validate(config("white_balance=shade")) == null, "advertised AWB preset accepted");
        check(caps.validate(config("white_balance=incandescent")).startsWith("unsupported-white_balance"),
            "unadvertised AWB preset rejected");
        check(caps.validate(config("white_balance=daylight awb_lock=on")).startsWith("incompatible-awb_lock"),
            "AWB lock cannot be combined with a preset");

        CaptureConfig manual = config("exposure_mode=manual iso=3200 exposure_time_ns=2000000000");
        check(caps.validate(manual) == null, "manual sensor range upper boundaries accepted");
        check(caps.validate(config("exposure_mode=manual iso=50 exposure_time_ns=1000")) == null,
            "manual sensor range lower boundaries accepted");
        CameraControlRequest mapped = CameraControlRequest.map(manual, capable, 1_000_000_000L, false);
        check(mapped.aeMode.equals("off") && mapped.iso == 3200 && mapped.exposureTimeNanos == 2_000_000_000L
            && mapped.frameDurationNanos == 2_000_000_000L && mapped.aeLock == null
            && mapped.exposureCompensation == null, "manual sensor mapping clears AE-only parameters");
        check(caps.validate(config("exposure_mode=manual iso=49")) .startsWith("invalid-iso"),
            "ISO below advertised range rejected");
        check(caps.validate(config("exposure_mode=manual exposure_time_ns=2000000001")).startsWith("invalid-exposure_time_ns"),
            "exposure above advertised range rejected");
        check(caps.validate(config("exposure_mode=manual ae_lock=on")).startsWith("incompatible-manual-exposure"),
            "manual exposure and AE lock are mutually exclusive");
        check(caps.validate(config("exposure_mode=manual exposure_compensation=1")).startsWith("incompatible-manual-exposure"),
            "manual exposure and compensation are mutually exclusive");

        CaptureConfig slow = config("target_fps=20 exposure_mode=manual exposure_time_ns=2000000000");
        CameraControlRequest slowRequest = CameraControlRequest.map(slow, capable, 2_200_000_000L, false);
        check(slow.targetFpsString().equals("20.0") && slowRequest.frameDurationNanos == 2_200_000_000L,
            "frame duration respects exposure and stream minimum without capping requested FPS");
        check(CameraControlRequest.map(config("jpeg_quality=37"), capable, 0, false).jpegQuality == 37,
            "validated JPEG quality reaches the camera request");

        CameraControlRequest automatic = CameraControlRequest.map(CaptureConfig.defaults(), capable, 0, false);
        check(automatic.afMode.equals("continuous-picture") && automatic.aeMode.equals("on")
            && automatic.exposureCompensation == 0 && automatic.whiteBalance.equals("auto"),
            "automatic modes map only to advertised Camera2 controls");
        CameraControlCapabilities autoOnly = new CameraControlCapabilities(
            Arrays.asList("off", "auto"), true, 5f, true, false, 0, 0, null,
            false, null, null, null, null, 0, Arrays.asList("auto"), false);
        CameraControlRequest triggeredAuto = CameraControlRequest.map(CaptureConfig.defaults(), autoOnly, 0, true);
        check(triggeredAuto.afMode.equals("auto") && triggeredAuto.startFocus,
            "AF_AUTO receives a start trigger so one-shot autofocus actually runs");
        check(!CameraControlRequest.map(CaptureConfig.defaults(), autoOnly, 0, false).startFocus,
            "AF_AUTO is not retriggered while its mode remains unchanged");
        CameraControlRequest locked = CameraControlRequest.map(config("focus_mode=lock"), capable, 0, true);
        check(locked.afMode.equals("auto") && locked.startFocus, "focus lock prefers supported one-shot AF and triggers once");
        CameraControlRequest focused = CameraControlRequest.map(config("focus_mode=manual focus_distance_diopters=2.5"), capable, 0, false);
        check(focused.afMode.equals("off") && focused.focusDistanceDiopters == 2.5f,
            "manual focus maps diopters with AF off");

        CameraControlCapabilities fixed = new CameraControlCapabilities(Collections.emptyList(), false, 0f, true,
            false, 0, 0, null, false, null, null, null, null, 0, Arrays.asList("auto"), false);
        CameraCapabilities fixedCaps = new CameraCapabilities(
            Collections.singletonList(new CameraCapabilities.JpegSize(640, 480, 0, 0)), fixed);
        check(fixed.fixedFocus() && fixedCaps.validate(CaptureConfig.defaults()) == null,
            "fixed-focus camera accepts legacy automatic defaults without inventing focus controls");
        check(fixedCaps.validate(config("focus_mode=lock")).startsWith("unsupported-focus_mode"),
            "fixed-focus camera rejects AF lock");
        check(fixedCaps.validate(config("focus_mode=manual focus_distance_diopters=0.1")).startsWith("unsupported-focus_mode"),
            "fixed-focus camera rejects manual focus");
        CameraControlCapabilities fixedWithAfModes = new CameraControlCapabilities(
            Arrays.asList("off", "auto", "continuous-picture"), false, 0f, true, false,
            0, 0, null, false, null, null, null, null, 0, Arrays.asList("auto"), false);
        check(fixedWithAfModes.fixedFocus()
            && CameraControlRequest.map(CaptureConfig.defaults(), fixedWithAfModes, 0, false).afMode == null,
            "zero minimum focus distance prevents misleading AF controls");
        CameraControlCapabilities manualOnlyFocus = new CameraControlCapabilities(
            Collections.singletonList("off"), false, 8f, true, false, 0, 0, null,
            false, null, null, null, null, 0, Arrays.asList("auto"), false);
        check(!manualOnlyFocus.fixedFocus() && manualOnlyFocus.manualFocusSupported()
            && manualOnlyFocus.focusChoices().equals(Arrays.asList("auto", "manual"))
            && CameraControlRequest.map(config("focus_mode=manual focus_distance_diopters=3"), manualOnlyFocus, 0, false)
                .focusDistanceDiopters == 3f,
            "AF-off lens with a positive diopter range exposes safe default and manual focus choices");
        check(fixedCaps.validate(config("ae_lock=on")).startsWith("unsupported-ae_lock"),
            "unsupported AE lock rejected");
        check(fixedCaps.validate(config("exposure_mode=manual")).startsWith("unsupported-exposure_mode"),
            "manual exposure unavailable without manual sensor capability");
        CameraControlCapabilities incompleteManual = new CameraControlCapabilities(Collections.emptyList(),
            false, 0f, true, false, null, null, null, true, null, null, null, null, 0,
            Collections.singletonList("auto"), false);
        check(!incompleteManual.manualSensorSupported, "manual controls are hidden when advertised ranges are incomplete");
        System.out.println("CameraControlsTest passed");
    }
}
