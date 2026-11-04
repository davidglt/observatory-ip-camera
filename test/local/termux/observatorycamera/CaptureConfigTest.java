/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

import java.util.LinkedHashMap;
import java.util.Map;

/** Dependency-free test. Run: bash test/run.sh */
public final class CaptureConfigTest {
    private static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); }
    private static CaptureConfig ok(String a) {
        CaptureConfig.Parsed p = CaptureConfig.parse(a, CaptureConfig.defaults());
        check(p.config != null, "should accept: " + a + " (" + p.error + ")");
        return p.config;
    }
    private static void bad(String a) { check(CaptureConfig.parse(a, CaptureConfig.defaults()).config == null, "should reject: " + a); }

    public static void main(String[] args) {
        CaptureConfig d = CaptureConfig.defaults();
        check(d.overlayEnabled && d.location.equals("NYX") && d.position.equals("bottom-left"), "defaults");
        check(d.targetFpsTenths == 10 && d.targetIntervalNanos() == 1_000_000_000L, "default 1 FPS");
        check(d.jpegSize.equals("auto"), "legacy files retain automatic default resolution");
        check(d.describe().equals("overlay=on location=NYX position=bottom-left text_size=24 fields=location,datetime,fps target_fps=1.0 jpeg_size=auto text_color=green telemetry_position=right panel_enabled=on panel_text_color=#FF0000 panel_background_color=#000000 panel_text_size=16 panel_environment=on panel_device=on panel_capture=on mqtt_host= mqtt_port=1883 mqtt_topic=homeassistant/nyx/reading mqtt_tls=off mqtt_stale_seconds=120 focus_mode=auto focus_distance_diopters=0.0 exposure_mode=auto exposure_compensation=0 ae_lock=off iso=100 exposure_time_ns=10000000 white_balance=auto awb_lock=off jpeg_quality=90 orientation_mode=auto rotation_degrees=0 mirror=off"), d.describe());
        check(d.fields == CaptureConfig.ALL_FIELDS && !d.hasField(CaptureConfig.FIELD_TEMPERATURE)
            && !d.hasField(CaptureConfig.FIELD_BATTERY) && !d.hasField(CaptureConfig.FIELD_THERMAL),
            "new overlay fields default off");
        check(ok("target_fps=2").targetIntervalNanos() == 500_000_000L, "2 FPS interval");
        check(ok("target_fps=5").targetFpsTenths == 50, "multiple integer digits");
        check(ok("target_fps=10").targetFpsTenths == 100, "above 9 FPS");
        check(ok("target_fps=15.5").targetFpsString().equals("15.5")
            && ok("target_fps=15.5").targetIntervalNanos() == 64_516_129L, "fractional high target");
        check(ok("target_fps=30").targetFpsTenths == 300, "30 FPS");
        check(ok("target_fps=0.1").targetIntervalNanos() == 10_000_000_000L
            && ok("target_fps=0.5").targetFpsTenths == 5
            && ok("target_fps=1.5").targetFpsTenths == 15
            && ok("target_fps=2.5").targetFpsTenths == 25, "existing fractional rates");
        check(ok("target_fps=922337203685477580.7").targetFpsTenths == Long.MAX_VALUE
            && ok("target_fps=922337203685477580.7").targetIntervalNanos() == 1, "largest representable rate and interval");
        check(CaptureConfig.parse("target_fps=0", d).error.equals("invalid-target_fps:must-be-positive"), "zero has clear error");
        check(CaptureConfig.parse("target_fps=922337203685477580.8", d).error.equals("invalid-target_fps:outside-representable-range"), "overflow has clear error");
        check(ok("jpeg_size=1920x1080").jpegSize.equals("1920x1080"), "explicit JPEG size");
        check(ok("overlay=off location=Site-1 position=top-right text_size=40 fields=datetime,frames").describe()
            .equals("overlay=off location=Site-1 position=top-right text_size=40 fields=datetime target_fps=1.0 jpeg_size=auto text_color=green telemetry_position=right panel_enabled=on panel_text_color=#FF0000 panel_background_color=#000000 panel_text_size=16 panel_environment=on panel_device=on panel_capture=on mqtt_host= mqtt_port=1883 mqtt_topic=homeassistant/nyx/reading mqtt_tls=off mqtt_stale_seconds=120 focus_mode=auto focus_distance_diopters=0.0 exposure_mode=auto exposure_compensation=0 ae_lock=off iso=100 exposure_time_ns=10000000 white_balance=auto awb_lock=off jpeg_quality=90 orientation_mode=auto rotation_degrees=0 mirror=off"), "legacy frame field is discarded");
        check(ok("fields=frames,location").fieldsString().equals("location"), "legacy frame field is ignored");
        check(ok("fields=").fieldsString().equals("none") && ok("fields=none").fieldsString().equals("none"),
            "empty field selection is explicit");
        check(ok("fields=location,datetime,fps").fieldsString().equals("location,datetime,fps"), "visible field order");
        CaptureConfig configured = ok("fields=location,temperature,humidity,pressure,battery,battery_temperature,charging,thermal text_color=#aBcDeF telemetry_position=left panel_enabled=off panel_text_color=#123456 panel_background_color=#abcdef panel_text_size=32 panel_environment=off panel_device=on panel_capture=off mqtt_host=broker.example mqtt_port=8883 mqtt_topic=homeassistant/nyx/reading mqtt_tls=on mqtt_stale_seconds=600");
        check(configured.fieldsString().equals("location,temperature,humidity,pressure,battery,battery_temperature,charging,thermal")
            && !configured.panelEnabled && !configured.panelEnvironment && configured.panelDevice
            && !configured.panelCapture && configured.panelTextColor.equals("#123456")
            && configured.textColor.equals("#ABCDEF") && configured.telemetryPosition.equals("left")
            && configured.panelBackgroundColor.equals("#ABCDEF")
            && configured.mqttHost.equals("broker.example") && configured.mqttPort == 8883
            && configured.mqttTls && configured.mqttStaleSeconds == 600, "telemetry settings round trip");
        check(ok("text_color=#abcdef").toMap().get("text_color").equals("#ABCDEF")
            && ok("text_color=green").textColor.equals("green"), "RGB colors canonicalize and legacy green remains valid");
        check(ok("telemetry_position=right").telemetryPosition.equals("right")
            && ok("telemetry_position=left").telemetryPosition.equals("left"), "both panel positions accepted");
        check(ok("mqtt_host=").mqttHost.isEmpty(), "empty MQTT host disables subscriber");
        CaptureConfig imageControls = ok("focus_mode=manual focus_distance_diopters=5.25 exposure_mode=manual exposure_compensation=0 ae_lock=off iso=800 exposure_time_ns=20000000 white_balance=daylight awb_lock=off jpeg_quality=75 orientation_mode=manual rotation_degrees=270 mirror=on");
        check(imageControls.focusMode.equals("manual") && imageControls.focusDistanceDiopters == 5.25f
            && imageControls.exposureMode.equals("manual") && imageControls.iso == 800
            && imageControls.exposureTimeNanos == 20_000_000L && imageControls.whiteBalance.equals("daylight")
            && imageControls.jpegQuality == 75 && imageControls.orientationMode.equals("manual")
            && imageControls.rotationDegrees == 270 && imageControls.mirror,
            "image-control settings parse with explicit diopter and nanosecond units");
        check(CaptureConfig.fromEntries(imageControls.toMap()).config.describe().equals(imageControls.describe()),
            "all image controls round-trip through properties entries");
        for (String b : new String[]{"", "   ", "overlay", "overlay=", "=on", "overlay=yes", "location=", "location=a b".replace(' ', '$'),
            "location=" + new String(new char[25]).replace('\0', 'a'), "location=x;y", "position=middle", "text_size=9", "text_size=97", "text_size=abc",
            "text_size=-5", "fields=gps", "fields=fps,fps", "fields=fps,,frames", "fields=frames,frames", "fields=none,location", "target_fps=0", "target_fps=0.0",
            "target_fps=1.25", "target_fps=1.", "target_fps=.5", "target_fps=-1", "target_fps=NaN", "target_fps=Infinity",
            "target_fps=922337203685477581", "text_color=red", "text_color=#12345", "panel_text_color=red",
            "panel_background_color=#12345", "telemetry_position=middle",
            "panel_text_size=9", "panel_text_size=49", "panel_environment=yes", "mqtt_host=bad/host",
            "mqtt_port=0", "mqtt_port=65536", "mqtt_host=-bad.example", "mqtt_topic=bad topic", "mqtt_topic=room/#/other",
            "mqtt_tls=yes", "mqtt_stale_seconds=9",
            "mqtt_stale_seconds=3601", "bogus=1", "overlay=on overlay=off", "focus_mode=unknown",
            "focus_distance_diopters=-1", "focus_distance_diopters=NaN", "focus_distance_diopters=Infinity",
            "exposure_mode=night", "exposure_compensation=1.2", "iso=0", "exposure_time_ns=0",
            "exposure_time_ns=9223372036854775808", "white_balance=manual", "jpeg_quality=0",
            "jpeg_quality=101", "orientation_mode=sideways", "rotation_degrees=45", "mirror=yes"}) bad(b);
        bad("jpeg_size=1280x0");
        bad("jpeg_size=1280x720;reboot");
        CaptureConfig.Parsed atomic = CaptureConfig.parse("overlay=off bogus=1", d);
        check(atomic.config == null && d.overlayEnabled, "atomic: nothing applied on error");
        check(ok("text_color=green").toMap().get("text_color").equals("green"), "green accepted");
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("location", "Obs 1");
        check(CaptureConfig.fromEntries(entries).config.location.equals("Obs 1"), "space inside location allowed in files");
        entries.put("bogus", "1");
        check(CaptureConfig.fromEntries(entries).config == null, "unknown key rejects the whole set");
        entries.clear();
        entries.put("fields", "");
        check(CaptureConfig.fromEntries(entries).config.fieldsString().equals("none"), "empty properties field selection");
        entries.clear();
        entries.put("fields", "frames");
        check(CaptureConfig.fromEntries(entries).config.fieldsString().equals("none"), "legacy persisted frames field ignored");
        entries.clear();
        entries.put("location", " lead");
        check(CaptureConfig.fromEntries(entries).config == null, "leading space rejected");
        entries.put("location", "trail ");
        check(CaptureConfig.fromEntries(entries).config == null, "trailing space rejected");
        entries.put("location", new String(new char[25]).replace('\0', 'a'));
        check(CaptureConfig.fromEntries(entries).config == null, "25 chars rejected");
        System.out.println("CaptureConfigTest passed");
    }
}
