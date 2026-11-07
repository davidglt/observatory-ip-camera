#!/usr/bin/env bash
# Copyright (C) 2026 David González López-Tercero
# SPDX-License-Identifier: GPL-3.0-or-later
# See LICENSE in the repository root for licensing details.

set -euo pipefail
cd "$(dirname "$0")/.."
if ! grep -Eq '<manifest[^>]*android:versionCode="9"[^>]*android:versionName="0\.9\.0"' AndroidManifest.xml; then
    echo "Release metadata must be versionCode 9 and versionName 0.9.0" >&2
    exit 1
fi
bash test/license-headers-test.sh
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
P=local/termux/observatorycamera
javac --release 8 -d "$OUT" src/$P/JpegOrientation.java src/$P/SnapshotHttpServer.java src/$P/AdminTokenStore.java src/$P/ControlCommand.java src/$P/CaptureConfig.java src/$P/CameraCapabilities.java src/$P/CameraControlCapabilities.java src/$P/CameraControlRequest.java src/$P/ImageTransformPlan.java src/$P/ExifOrientation.java src/$P/ConfigStore.java src/$P/OverlayFormat.java src/$P/OverlayTelemetry.java src/$P/OverlayLayout.java src/$P/FrameStats.java src/$P/ContinuousStatus.java src/$P/CameraArbiter.java src/$P/SnapshotPublisher.java src/$P/ImageAge.java src/$P/EnvironmentalTelemetry.java src/$P/DeviceTelemetryFormat.java src/$P/TelemetryDisplayFormat.java src/$P/ProcessMetrics.java src/$P/MqttSubscriptionLifecycle.java src/$P/MqttClientIdStore.java src/$P/MqttCredentialFingerprint.java test/$P/JpegOrientationTest.java test/$P/ControlCommandTest.java test/$P/SnapshotHttpServerTest.java test/$P/SnapshotStreamTest.java test/$P/CaptureConfigTest.java test/$P/CameraCapabilitiesTest.java test/$P/CameraControlsTest.java test/$P/ImageTransformPlanTest.java test/$P/AdminTokenStoreTest.java test/$P/ConfigStoreTest.java test/$P/OverlayTest.java test/$P/ContinuousStatsTest.java test/$P/EnvironmentalTelemetryTest.java test/$P/DeviceTelemetryFormatTest.java test/$P/TelemetryDisplayFormatTest.java test/$P/ProcessMetricsTest.java test/$P/MqttSubscriptionLifecycleTest.java test/$P/ImageAgeTest.java test/$P/MqttClientIdStoreTest.java test/$P/MqttCredentialFingerprintTest.java
javac --release 8 -classpath "$OUT" -d "$OUT" src/$P/NetworkInterfaceDiscovery.java test/$P/NetworkInterfaceDiscoveryTest.java
javac --release 8 -classpath "$OUT" -d "$OUT" src/$P/TlsCertificateStore.java test/$P/TlsCertificateStoreTest.java
javac --release 8 -classpath "$OUT" -d "$OUT" test/$P/NetworkListenerTest.java
run_java() {
    echo "Running Java subtest: $1"
    timeout -k 5 120 java -cp "$OUT" "local.termux.observatorycamera.$1"
}
run_java JpegOrientationTest
run_java ImageTransformPlanTest
run_java SnapshotHttpServerTest
run_java SnapshotStreamTest
run_java ControlCommandTest
for t in CaptureConfigTest ConfigStoreTest OverlayTest ContinuousStatsTest CameraCapabilitiesTest CameraControlsTest AdminTokenStoreTest EnvironmentalTelemetryTest DeviceTelemetryFormatTest TelemetryDisplayFormatTest ProcessMetricsTest MqttSubscriptionLifecycleTest ImageAgeTest MqttClientIdStoreTest MqttCredentialFingerprintTest NetworkInterfaceDiscoveryTest TlsCertificateStoreTest NetworkListenerTest; do run_java "$t"; done
echo "Running shell subtest: camera-control-test.sh"
bash test/camera-control-test.sh
echo "Running shell subtest: adb-target-test.sh"
bash test/adb-target-test.sh
echo "Running shell subtest: deploy-test.sh"
bash test/deploy-test.sh
echo "Running normalized CPU telemetry JSON tests"
python3 test/telemetry-rest-test.py
echo "Running normalized CPU arithmetic tests"
bash test/cpu-normalization-test.sh
echo "Running normalized CPU browser renderer tests (Node required)"
python3 test/normalized-cpu-display-test.py
echo "Running normalized CPU text panel tests"
python3 test/normalized-cpu-panel-test.py
echo "Running browser memory renderer tests"
python3 test/browser-memory-test.py
