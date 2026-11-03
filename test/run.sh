#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
P=local/termux/observatorycamera
javac --release 8 -d "$OUT" src/$P/JpegOrientation.java src/$P/SnapshotHttpServer.java src/$P/AdminTokenStore.java src/$P/ControlCommand.java src/$P/CaptureConfig.java src/$P/CameraCapabilities.java src/$P/ConfigStore.java src/$P/OverlayFormat.java src/$P/OverlayTelemetry.java src/$P/OverlayLayout.java src/$P/ExifOrientation.java src/$P/FrameStats.java src/$P/ContinuousStatus.java src/$P/CameraArbiter.java src/$P/SnapshotPublisher.java src/$P/ImageAge.java src/$P/EnvironmentalTelemetry.java src/$P/DeviceTelemetryFormat.java src/$P/MqttSubscriptionLifecycle.java src/$P/MqttClientIdStore.java test/$P/JpegOrientationTest.java test/$P/ControlCommandTest.java test/$P/SnapshotHttpServerTest.java test/$P/SnapshotStreamTest.java test/$P/CaptureConfigTest.java test/$P/CameraCapabilitiesTest.java test/$P/AdminTokenStoreTest.java test/$P/ConfigStoreTest.java test/$P/OverlayTest.java test/$P/ContinuousStatsTest.java test/$P/EnvironmentalTelemetryTest.java test/$P/DeviceTelemetryFormatTest.java test/$P/MqttSubscriptionLifecycleTest.java test/$P/ImageAgeTest.java test/$P/MqttClientIdStoreTest.java
run_java() {
    echo "Running Java subtest: $1"
    timeout -k 5 120 java -cp "$OUT" "local.termux.observatorycamera.$1"
}
run_java JpegOrientationTest
run_java SnapshotHttpServerTest
run_java SnapshotStreamTest
run_java ControlCommandTest
for t in CaptureConfigTest ConfigStoreTest OverlayTest ContinuousStatsTest CameraCapabilitiesTest AdminTokenStoreTest EnvironmentalTelemetryTest DeviceTelemetryFormatTest MqttSubscriptionLifecycleTest ImageAgeTest MqttClientIdStoreTest; do run_java "$t"; done
echo "Running shell subtest: camera-control-test.sh"
bash test/camera-control-test.sh
echo "Running shell subtest: adb-target-test.sh"
bash test/adb-target-test.sh
echo "Running shell subtest: deploy-test.sh"
bash test/deploy-test.sh
