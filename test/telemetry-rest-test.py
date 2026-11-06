#!/usr/bin/env python3
# Copyright (C) 2026 David González López-Tercero
# SPDX-License-Identifier: GPL-3.0-or-later
# See LICENSE in the repository root for licensing details.

"""Exercise the real telemetry serializer on the JVM, without Android or third-party libraries."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = "local.termux.observatorycamera"
P = Path("local/termux/observatorycamera")

STUBS = r'''
package local.termux.observatorycamera;
import android.content.Context;
class EnvironmentalTelemetry {
    static class Reading { double temperatureC, humidityPct, pressureHpa; }
    Reading latest() { return null; }
    String state(long wall, long stale) { return "disabled"; }
}
class DeviceTelemetry {
    Integer batteryPercent; Double batteryTemperatureC;
    String charging = "unknown", thermalStatus = "unknown";
    static DeviceTelemetry read(Context c) { return new DeviceTelemetry(); }
}
class ContinuousStatus {
    static final String STOPPED = "stopped";
    String state = STOPPED;
    double actualFps;
    long targetFpsTenths, procLastNanos, frames, procAvgNanos, procMaxNanos;
}
class ContinuousCaptureService {
    static ContinuousStatus getStatus() { return new ContinuousStatus(); }
}
class CaptureConfig {
    long targetFpsTenths = 10;
    boolean panelEnabled, panelEnvironment, panelDevice, panelCapture, panelServer;
    String telemetryPosition = "left", panelTextColor = "#ffffff", panelBackgroundColor = "#000000";
    int panelTextSize = 14;
}
class CaptureSettings { static CaptureConfig load(Context c) { return new CaptureConfig(); } }
class OverlayTelemetry {
    OverlayTelemetry(EnvironmentalTelemetry.Reading e, Integer b, Double t, String c, String s) {}
}
class SnapshotHttpService {
    static class Telemetry {
        String state = "running", requestedListeners = "", effectiveListeners = "", listenerError = "";
        int activeConnections, activeStreams;
    }
    static Telemetry telemetry(CaptureConfig c) { return new Telemetry(); }
    static boolean isRunning() { return true; }
    static String getLastError() { return null; }
}
class TelemetryRuntime {
    static void refresh(Context c) { throw new AssertionError("unit tests must not sample Android"); }
    static EnvironmentalTelemetry environment() { return new EnvironmentalTelemetry(); }
    static long staleMillis() { return 15000; }
    static ProcessMetrics.Snapshot processMetrics() { throw new AssertionError(); }
    static CpuNormalization.Reading normalizedProcessCpu() { throw new AssertionError(); }
}
class ImageAge { static Double seconds(long modified, long wall) { return null; } }
class TelemetryDisplayFormat {
    static String fps(Double v) { return String.valueOf(v); }
    static String memoryKilobytes(Number v) { return String.valueOf(v); }
}
'''

HARNESS = r'''
package local.termux.observatorycamera;
import java.lang.reflect.Constructor;
import java.util.Locale;
public final class TelemetryRestFixture {
    private static void emit(String name, Double current, Double average, Integer count,
                             int samples, long age, boolean forceStale) throws Exception {
        ProcessMetrics.Sample latest = samples == 0 ? null : new ProcessMetrics.Sample(
            5000, current, 100L, 200L, 400L, false, 5000L);
        ProcessMetrics.Snapshot metrics = new ProcessMetrics.Snapshot(latest, average, 100L, samples, age);
        CpuNormalization.Reading cpu = new CpuNormalization.Reading(current, average, count,
            forceStale || metrics.isStale());
        Constructor<TelemetrySnapshot> ctor = TelemetrySnapshot.class.getDeclaredConstructor(
            String.class, EnvironmentalTelemetry.Reading.class, DeviceTelemetry.class,
            ContinuousStatus.class, ProcessMetrics.Snapshot.class, CpuNormalization.Reading.class,
            SnapshotHttpService.Telemetry.class, String.class, Double.class, CaptureConfig.class);
        ctor.setAccessible(true);
        TelemetrySnapshot snapshot = ctor.newInstance("disabled", null, new DeviceTelemetry(),
            new ContinuousStatus(), metrics, cpu, new SnapshotHttpService.Telemetry(), "running",
            null, new CaptureConfig());
        System.out.println(name + "\t" + snapshot.toJson());
    }
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.GERMANY);
        emit("available", 200.0, 160.0, 8, 3, 0, false);
        emit("empty", null, null, null, 0, 0, false);
        emit("first", null, null, 8, 1, 0, false);
        emit("missing_count", 200.0, 160.0, null, 3, 0, false);
        emit("zero_count", 200.0, 160.0, 0, 3, 0, false);
        emit("negative_count", 200.0, 160.0, -2, 3, 0, false);
        emit("missing_cpu", null, null, 8, 3, 0, false);
        emit("partial", null, 160.0, 8, 3, 0, false);
        emit("stale", 200.0, 160.0, 8, 3, ProcessMetrics.STALE_AFTER_MILLIS + 1, false);
        emit("boundary", 200.0, 160.0, 8, 3, ProcessMetrics.STALE_AFTER_MILLIS, false);
        emit("zero", 0.0, 0.0, 8, 3, 0, false);
        emit("unclamped", 1000.0, 900.0, 8, 3, 0, false);
        emit("invalid", Double.NaN, Double.POSITIVE_INFINITY, 8, 3, 0, false);
        emit("negative_cpu", -1.0, -2.0, 8, 3, 0, false);
        emit("stale_partial", null, 160.0, 8, 3, ProcessMetrics.STALE_AFTER_MILLIS + 1, false);
    }
}
'''


class TelemetryRestTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with tempfile.TemporaryDirectory(prefix="telemetry-rest-") as temporary:
            out = Path(temporary)
            package = out / P
            package.mkdir(parents=True)
            context = out / "android/content/Context.java"
            context.parent.mkdir(parents=True)
            context.write_text("package android.content; public class Context { public java.io.File getFilesDir() { return new java.io.File(\".\"); } }", encoding="utf-8")
            stubs = package / "TelemetryTestDependencies.java"
            stubs.write_text(STUBS, encoding="utf-8")
            harness = package / "TelemetryRestFixture.java"
            harness.write_text(HARNESS, encoding="utf-8")
            sources = [ROOT / "src" / P / name for name in (
                "TelemetrySnapshot.java", "CpuNormalization.java", "ProcessMetrics.java")]
            subprocess.run(["javac", "--release", "8", "-sourcepath", str(out), "-d", str(out)]
                           + [str(p) for p in sources + [context, stubs, harness]],
                           check=True, timeout=60)
            result = subprocess.run(["java", "-cp", str(out), PACKAGE + ".TelemetryRestFixture"],
                                    check=True, capture_output=True, text=True, timeout=30)
            cls.readings = {}
            for line in result.stdout.splitlines():
                name, payload = line.split("\t", 1)
                cls.readings[name] = json.loads(payload)

    def test_numbers_and_legacy_fields(self):
        data = self.readings["available"]
        self.assertEqual(data["app_cpu_current_normalized_percent"], 25.0)
        self.assertEqual(data["app_cpu_recent_average_normalized_percent"], 20.0)
        self.assertIs(type(data["app_cpu_current_normalized_percent"]), float)
        self.assertIs(type(data["app_cpu_recent_average_normalized_percent"]), float)
        self.assertIs(type(data["app_cpu_logical_count"]), int)
        self.assertEqual(data["app_cpu_logical_count"], 8)
        self.assertIs(data["app_cpu_normalized_stale"], False)
        self.assertEqual(data["app_cpu_normalized_state"], "available")
        self.assertEqual(data["app_cpu_current_percent"], "200.00 %")
        self.assertEqual(data["app_cpu_recent_average_percent"], "160.00 %")
        self.assertEqual(data["environment_state"], "disabled")
        self.assertEqual(data["app_pss_kb"], "100")

    def test_collecting(self):
        for name in ("empty", "first"):
            with self.subTest(name=name):
                data = self.readings[name]
                self.assertEqual(data["app_cpu_normalized_state"], "collecting")
                self.assertIsNone(data["app_cpu_current_normalized_percent"])
                self.assertIsNone(data["app_cpu_recent_average_normalized_percent"])
                self.assertIs(data["app_cpu_normalized_stale"], False)
        self.assertIsNone(self.readings["empty"]["app_cpu_logical_count"])
        self.assertEqual(self.readings["first"]["app_cpu_logical_count"], 8)

    def test_unavailable_and_invalid_inputs(self):
        for name in ("missing_count", "zero_count", "negative_count", "missing_cpu", "invalid", "negative_cpu"):
            with self.subTest(name=name):
                data = self.readings[name]
                self.assertEqual(data["app_cpu_normalized_state"], "unavailable")
                self.assertIsNone(data["app_cpu_current_normalized_percent"])
                self.assertIsNone(data["app_cpu_recent_average_normalized_percent"])
        for name in ("missing_count", "zero_count", "negative_count"):
            self.assertIsNone(self.readings[name]["app_cpu_logical_count"])

    def test_partial_reading(self):
        data = self.readings["partial"]
        self.assertEqual(data["app_cpu_normalized_state"], "available")
        self.assertIsNone(data["app_cpu_current_normalized_percent"])
        self.assertEqual(data["app_cpu_recent_average_normalized_percent"], 20.0)

    def test_stale_retains_numeric_values(self):
        data = self.readings["stale"]
        self.assertIs(data["app_cpu_normalized_stale"], True)
        self.assertEqual(data["app_cpu_normalized_state"], "stale")
        self.assertEqual(data["app_cpu_current_normalized_percent"], 25.0)
        self.assertEqual(data["app_cpu_recent_average_normalized_percent"], 20.0)
        self.assertEqual(data["app_cpu_current_percent"], "200.00 % (stale)")
        self.assertIs(self.readings["boundary"]["app_cpu_normalized_stale"], False)
        self.assertEqual(self.readings["boundary"]["app_cpu_normalized_state"], "available")
        self.assertEqual(self.readings["stale_partial"]["app_cpu_normalized_state"], "stale")

    def test_zero_and_no_clamping(self):
        self.assertEqual(self.readings["zero"]["app_cpu_current_normalized_percent"], 0.0)
        self.assertEqual(self.readings["zero"]["app_cpu_recent_average_normalized_percent"], 0.0)
        self.assertEqual(self.readings["unclamped"]["app_cpu_current_normalized_percent"], 125.0)
        self.assertEqual(self.readings["unclamped"]["app_cpu_recent_average_normalized_percent"], 112.5)


if __name__ == "__main__":
    unittest.main(verbosity=2)
