package local.termux.observatorycamera;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Camera2 image-control metadata copied into a platform-independent form for validation and tests. */
public final class CameraControlCapabilities {
    public final List<String> focusModes;
    public final boolean focusLockSupported;
    public final float minimumFocusDistanceDiopters;
    public final boolean autoExposureSupported;
    public final boolean aeLockSupported;
    public final Integer exposureCompensationMinimum;
    public final Integer exposureCompensationMaximum;
    public final double exposureCompensationStepEv;
    public final String exposureCompensationStep;
    public final boolean manualSensorSupported;
    public final Integer minimumIso;
    public final Integer maximumIso;
    public final Long minimumExposureTimeNanos;
    public final Long maximumExposureTimeNanos;
    public final long maximumFrameDurationNanos;
    public final List<String> whiteBalanceModes;
    public final boolean awbLockSupported;

    public CameraControlCapabilities(List<String> focusModes, boolean focusLockSupported,
                                    float minimumFocusDistanceDiopters, boolean autoExposureSupported,
                                    boolean aeLockSupported, Integer exposureCompensationMinimum,
                                    Integer exposureCompensationMaximum, String exposureCompensationStep,
                                    boolean manualSensorSupported, Integer minimumIso, Integer maximumIso,
                                    Long minimumExposureTimeNanos, Long maximumExposureTimeNanos,
                                    long maximumFrameDurationNanos, List<String> whiteBalanceModes,
                                    boolean awbLockSupported) {
        this.focusModes = immutableUnique(focusModes);
        this.focusLockSupported = focusLockSupported;
        this.minimumFocusDistanceDiopters = minimumFocusDistanceDiopters;
        this.autoExposureSupported = autoExposureSupported;
        this.aeLockSupported = aeLockSupported;
        this.exposureCompensationMinimum = exposureCompensationMinimum;
        this.exposureCompensationMaximum = exposureCompensationMaximum;
        this.exposureCompensationStep = exposureCompensationStep;
        this.exposureCompensationStepEv = parseStep(exposureCompensationStep);
        this.manualSensorSupported = manualSensorSupported && minimumIso != null && maximumIso != null
            && minimumIso > 0 && maximumIso >= minimumIso && minimumExposureTimeNanos != null
            && maximumExposureTimeNanos != null && minimumExposureTimeNanos > 0
            && maximumExposureTimeNanos >= minimumExposureTimeNanos;
        this.minimumIso = minimumIso;
        this.maximumIso = maximumIso;
        this.minimumExposureTimeNanos = minimumExposureTimeNanos;
        this.maximumExposureTimeNanos = maximumExposureTimeNanos;
        this.maximumFrameDurationNanos = maximumFrameDurationNanos;
        this.whiteBalanceModes = immutableUnique(whiteBalanceModes);
        this.awbLockSupported = awbLockSupported;
    }

    private static List<String> immutableUnique(List<String> values) {
        Set<String> copy = new LinkedHashSet<>(values == null ? Collections.emptyList() : values);
        return Collections.unmodifiableList(new ArrayList<>(copy));
    }

    public boolean manualFocusSupported() {
        return Float.isFinite(minimumFocusDistanceDiopters) && minimumFocusDistanceDiopters > 0
            && focusModes.contains("off");
    }

    public boolean fixedFocus() {
        if (Float.isFinite(minimumFocusDistanceDiopters) && minimumFocusDistanceDiopters <= 0) return true;
        for (String mode : focusModes) if (!mode.equals("off")) return false;
        return !manualFocusSupported();
    }

    public boolean autofocusSupported() {
        if (fixedFocus()) return false;
        for (String mode : focusModes) if (!mode.equals("off")) return true;
        return false;
    }

    public List<String> focusChoices() {
        if (fixedFocus()) return Collections.emptyList();
        ArrayList<String> choices = new ArrayList<>();
        if (autofocusSupported() && !focusModes.contains("auto")
            || !autofocusSupported() && manualFocusSupported()) choices.add("auto");
        for (String mode : focusModes)
            if (!mode.equals("off") && !choices.contains(mode)) choices.add(mode);
        if (focusLockSupported) choices.add("lock");
        if (manualFocusSupported()) choices.add("manual");
        return Collections.unmodifiableList(choices);
    }

    public boolean exposureCompensationSupported() {
        return autoExposureSupported && exposureCompensationMinimum != null && exposureCompensationMaximum != null
            && exposureCompensationMinimum < exposureCompensationMaximum
            && Double.isFinite(exposureCompensationStepEv) && exposureCompensationStepEv > 0;
    }

    private static double parseStep(String step) {
        if (step == null) return 0d;
        try {
            int slash = step.indexOf('/');
            double value = slash < 0 ? Double.parseDouble(step)
                : Double.parseDouble(step.substring(0, slash)) / Double.parseDouble(step.substring(slash + 1));
            return Double.isFinite(value) && value > 0 ? value : 0d;
        } catch (NumberFormatException | ArithmeticException e) {
            return 0d;
        }
    }

    public String validate(CaptureConfig config, long selectedMinimumFrameDurationNanos) {
        String focus = config.focusMode;
        if (fixedFocus() && !focus.equals("auto")) return "unsupported-focus_mode:" + focus;
        if (!focus.equals("auto") && !focusModes.contains(focus)
            && !(focus.equals("lock") && focusLockSupported)
            && !(focus.equals("manual") && manualFocusSupported())) {
            return "unsupported-focus_mode:" + focus;
        }
        if (!focus.equals("manual") && config.focusDistanceDiopters != 0f)
            return "incompatible-focus_distance_diopters:requires-manual-focus";
        if (focus.equals("manual") && (config.focusDistanceDiopters < 0
            || config.focusDistanceDiopters > minimumFocusDistanceDiopters))
            return "invalid-focus_distance_diopters:expected-0-" + minimumFocusDistanceDiopters;

        if (!config.exposureMode.equals("auto") && !config.exposureMode.equals("manual"))
            return "invalid-exposure_mode";
        if (config.exposureMode.equals("manual")) {
            if (!manualSensorSupported || minimumIso == null || maximumIso == null
                || minimumExposureTimeNanos == null || maximumExposureTimeNanos == null)
                return "unsupported-exposure_mode:manual-sensor";
            if (config.aeLock || config.exposureCompensation != 0)
                return "incompatible-manual-exposure:ae-lock-and-compensation-must-be-off";
            if (config.iso < minimumIso || config.iso > maximumIso)
                return "invalid-iso:expected-" + minimumIso + "-" + maximumIso;
            long maximumExposure = Math.min(maximumExposureTimeNanos,
                maximumFrameDurationNanos > 0 ? maximumFrameDurationNanos : Long.MAX_VALUE);
            if (config.exposureTimeNanos < minimumExposureTimeNanos || config.exposureTimeNanos > maximumExposure)
                return "invalid-exposure_time_ns:expected-" + minimumExposureTimeNanos + "-" + maximumExposure;
            long frameDuration = Math.max(config.exposureTimeNanos, Math.max(0, selectedMinimumFrameDurationNanos));
            if (maximumFrameDurationNanos > 0 && frameDuration > maximumFrameDurationNanos)
                return "invalid-exposure_time_ns:frame-duration-exceeds-camera-maximum";
        } else {
            if (config.aeLock && (!aeLockSupported || !autoExposureSupported)) return "unsupported-ae_lock";
            if (config.exposureCompensation != 0 && (!autoExposureSupported || !exposureCompensationSupported()
                || config.exposureCompensation < exposureCompensationMinimum
                || config.exposureCompensation > exposureCompensationMaximum))
                return "invalid-exposure_compensation:outside-camera-range";
        }

        if (!whiteBalanceModes.contains(config.whiteBalance) && !config.whiteBalance.equals("auto"))
            return "unsupported-white_balance:" + config.whiteBalance;
        if (!config.whiteBalance.equals("auto") && config.awbLock)
            return "incompatible-awb_lock:requires-auto-white-balance";
        if (config.awbLock && (!awbLockSupported || !whiteBalanceModes.contains("auto")))
            return "unsupported-awb_lock";
        return null;
    }
}
